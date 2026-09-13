package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Per-tick driver for the local player: holds desired movement input (applied through key
 * mappings so it integrates with the vanilla input pipeline), single-shot jumps, survival mining,
 * and navigation following. The single instance ticks from {@code ClientTickEvents.END_CLIENT_TICK}.
 */
public final class BotController {
	private static final BotController INSTANCE = new BotController();

	public static BotController get() {
		return INSTANCE;
	}

	private BotController() {}

	// desired movement
	private volatile boolean fwd, back, left, right, jumpHeld, sneak, sprint;
	private int jumpOnceTicks = 0;

	// survival mining
	private BlockPos miningPos;
	private Direction miningFace = Direction.UP;

	// navigation
	private List<BlockPos> path;
	private int pathIndex;
	private BlockPos navTarget;
	private double reachRadius = 1.0;
	private boolean navSprint;
	private long navDeadline;
	private double lastDist = Double.MAX_VALUE;
	private int stuckTicks;
	private volatile String navState = "idle";
	private boolean drivingKeys;

	// smooth look: interpolate toward a target each tick instead of snapping
	private static final float LOOK_STEP_DEG = 20.0F;
	private Float lookTargetYaw;
	private Float lookTargetPitch;
	private int swingTimer;

	// --- public control surface (called from handlers, on the render thread) ----------------

	public synchronized void setMovement(Boolean f, Boolean b, Boolean l, Boolean r, Boolean jump, Boolean sn, Boolean sp) {
		if (f != null) fwd = f;
		if (b != null) back = b;
		if (l != null) left = l;
		if (r != null) right = r;
		if (jump != null) jumpHeld = jump;
		if (sn != null) sneak = sn;
		if (sp != null) sprint = sp;
	}

	public synchronized void stopAllMovement() {
		fwd = back = left = right = jumpHeld = sneak = sprint = false;
		jumpOnceTicks = 0;
	}

	public synchronized void jumpOnce() {
		jumpOnceTicks = Math.max(jumpOnceTicks, 2);
	}

	/** Aim at a yaw/pitch; the controller interpolates toward it each tick (like turning a mouse). */
	public synchronized void lookAtTarget(float yaw, float pitch) {
		this.lookTargetYaw = yaw;
		this.lookTargetPitch = Mth.clamp(pitch, -90.0F, 90.0F);
	}

	public synchronized void clearLookTarget() {
		this.lookTargetYaw = null;
		this.lookTargetPitch = null;
	}

	public synchronized void startMining(BlockPos pos, Direction face) {
		this.miningPos = pos;
		this.miningFace = face;
	}

	public synchronized void stopMining() {
		this.miningPos = null;
	}

	public synchronized void startNavigation(List<BlockPos> path, BlockPos target, double reachRadius, boolean sprint, long deadlineMillis) {
		this.path = path;
		this.pathIndex = 0;
		this.navTarget = target;
		this.reachRadius = reachRadius;
		this.navSprint = sprint;
		this.navDeadline = deadlineMillis;
		this.lastDist = Double.MAX_VALUE;
		this.stuckTicks = 0;
		this.navState = "navigating";
	}

	public synchronized void stopNavigation(String reason) {
		this.path = null;
		this.navState = reason;
		fwd = false;
		sprint = false;
	}

	public synchronized JsonObject statusJson() {
		JsonObject o = new JsonObject();
		boolean active = path != null;
		o.addProperty("active", active);
		o.addProperty("state", navState);
		if (navTarget != null) {
			JsonObject t = new JsonObject();
			t.addProperty("x", navTarget.getX());
			t.addProperty("y", navTarget.getY());
			t.addProperty("z", navTarget.getZ());
			o.add("target", t);
		}
		if (active) {
			o.addProperty("remainingNodes", Math.max(0, path.size() - pathIndex));
		}
		LocalPlayer p = Minecraft.getInstance().player;
		if (p != null && navTarget != null) {
			o.addProperty("distance", p.position().distanceTo(Vec3.atBottomCenterOf(navTarget)));
		}
		return o;
	}

	// --- tick --------------------------------------------------------------------------------

	public void onClientTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			return;
		}

		synchronized (this) {
			if (path != null) {
				steer(mc, p);
			}
			tickLook(p);
			boolean driving = fwd || back || left || right || jumpHeld || sneak || sprint || jumpOnceTicks > 0 || path != null;
			if (driving) {
				applyKeys(mc.options);
				drivingKeys = true;
			} else if (drivingKeys) {
				// Release any keys the bot forced down instead of continuing to stomp on them
				// every tick — without this, real keyboard input can never move the player
				// again once the bot has issued any movement command.
				releaseKeys(mc.options);
				drivingKeys = false;
			}
			if (jumpOnceTicks > 0) jumpOnceTicks--;
			tickMining(mc);
		}
	}

	private void applyKeys(Options o) {
		o.keyUp.setDown(fwd);
		o.keyDown.setDown(back);
		o.keyLeft.setDown(left);
		o.keyRight.setDown(right);
		o.keyShift.setDown(sneak);
		o.keySprint.setDown(sprint);
		o.keyJump.setDown(jumpHeld || jumpOnceTicks > 0);
	}

	private void releaseKeys(Options o) {
		o.keyUp.setDown(false);
		o.keyDown.setDown(false);
		o.keyLeft.setDown(false);
		o.keyRight.setDown(false);
		o.keyShift.setDown(false);
		o.keySprint.setDown(false);
		o.keyJump.setDown(false);
	}

	private void tickMining(Minecraft mc) {
		if (miningPos == null) return;
		MultiPlayerGameMode gm = mc.gameMode;
		if (gm == null || mc.level == null) {
			miningPos = null;
			return;
		}
		if (mc.level.getBlockState(miningPos).isAir()) {
			gm.stopDestroyBlock();
			miningPos = null;
			return;
		}
		gm.continueDestroyBlock(miningPos, miningFace);
		// Keep the arm swinging while digging, the way a real player's does.
		if (++swingTimer % 4 == 0) mc.player.swing(InteractionHand.MAIN_HAND);
	}

	private void tickLook(LocalPlayer p) {
		Float ty = lookTargetYaw;
		if (ty == null) return;
		float targetPitch = lookTargetPitch == null ? p.getXRot() : lookTargetPitch;
		float dYaw = Mth.wrapDegrees(ty - p.getYRot());
		float dPitch = targetPitch - p.getXRot();
		if (Math.abs(dYaw) <= LOOK_STEP_DEG && Math.abs(dPitch) <= LOOK_STEP_DEG) {
			applyLook(p, ty, targetPitch);
			lookTargetYaw = null;
			lookTargetPitch = null;
			return;
		}
		applyLook(p, p.getYRot() + Mth.clamp(dYaw, -LOOK_STEP_DEG, LOOK_STEP_DEG),
				p.getXRot() + Mth.clamp(dPitch, -LOOK_STEP_DEG, LOOK_STEP_DEG));
	}

	private static void applyLook(LocalPlayer p, float yaw, float pitch) {
		p.setYRot(yaw);
		p.setXRot(Mth.clamp(pitch, -90.0F, 90.0F));
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	private void steer(Minecraft mc, LocalPlayer p) {
		if (System.currentTimeMillis() > navDeadline) {
			stopNavigationInternal("timeout");
			return;
		}
		Vec3 tgt = Vec3.atBottomCenterOf(navTarget);
		double dist = p.position().distanceTo(tgt);
		if (dist <= Math.max(reachRadius, 0.6)) {
			stopNavigationInternal("reached");
			return;
		}
		if (pathIndex >= path.size()) {
			stopNavigationInternal("reached");
			return;
		}

		BlockPos node = path.get(pathIndex);
		double dx = node.getX() + 0.5 - p.getX();
		double dz = node.getZ() + 0.5 - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);

		float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		lookAtTarget(yaw, 0.0F);

		fwd = true;
		back = left = right = false;
		boolean inWater = p.isInWater();
		sprint = navSprint && !inWater;

		if (inWater) {
			// Swim: keep stroking forward and bob upward toward the next node.
			jumpOnceTicks = Math.max(jumpOnceTicks, 1);
		} else if (node.getY() > p.getY() + 0.4) {
			jumpOnceTicks = Math.max(jumpOnceTicks, 1);
		}
		if (horiz < 0.55) {
			pathIndex++;
		}

		// Stuck recovery ladder: hop -> re-plan -> give up.
		if (dist < lastDist - 0.02) {
			stuckTicks = 0;
			lastDist = dist;
		} else {
			stuckTicks++;
			if (stuckTicks == 15 || stuckTicks == 30) {
				jumpOnceTicks = Math.max(jumpOnceTicks, 2);
			} else if (stuckTicks == 50) {
				repath(mc, p);
				stuckTicks = 0;
				lastDist = Double.MAX_VALUE;
			} else if (stuckTicks > 160) {
				stopNavigationInternal("stuck");
			}
		}
	}

	/** Re-plan from the player's current position to the same target (returns true if a path was found). */
	private boolean repath(Minecraft mc, LocalPlayer p) {
		if (mc.level == null || navTarget == null) return false;
		List<BlockPos> fresh = new AStarPathfinder(mc.level, 12000)
				.findPath(p.blockPosition(), navTarget, Math.max(1.0, reachRadius));
		if (fresh == null || fresh.isEmpty()) return false;
		this.path = fresh;
		this.pathIndex = 0;
		return true;
	}

	private void stopNavigationInternal(String reason) {
		path = null;
		navState = reason;
		fwd = false;
		sprint = false;
	}
}
