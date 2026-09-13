package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Per-tick driver for the local player. Every action is expressed as ordinary device input —
 * movement keys, jump, and a held left mouse button — and applied through the vanilla key mappings,
 * so the game's own input pipeline decides what actually happens (you dig/attack whatever the
 * crosshair is on). Nothing here calls game-mode methods directly, and turning is interpolated like
 * mouse movement instead of snapping.
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
	/** Held left mouse button: attack / mine whatever the crosshair points at. */
	private volatile boolean attackHeld;
	/** Held right mouse button: use / eat / place with the crosshair context. */
	private volatile boolean useHeld;

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

	// water safety: swim up for air instead of drowning
	/** Air left when the bot abandons what it is doing and heads for the surface. */
	private static final int AIR_SURFACE_AT = 180;
	/** Air that counts as "breathing again"; below this we keep the head above the water. */
	private static final int AIR_CLEAR_AT = 285;
	private boolean surfacing;
	private boolean surfaceJumpHeld;

	// smooth look (mouse-delta style): interpolate toward a yaw/pitch target each tick
	private static final float LOOK_STEP_DEG = 20.0F;
	private Float lookTargetYaw;
	private Float lookTargetPitch;
	/** Rotation the controller last applied — compared with the live rotation to spot real mouse input. */
	private float lastAppliedYaw = Float.NaN;
	private float lastAppliedPitch = Float.NaN;

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

	/** Hold or release the left mouse button (vanilla then mines/attacks the crosshair target). */
	public synchronized void setAttackHeld(boolean held) {
		attackHeld = held;
	}

	public synchronized boolean isAttackHeld() {
		return attackHeld;
	}

	/** Hold or release the right mouse button (vanilla then uses/eats/places at the crosshair). */
	public synchronized void setUseHeld(boolean held) {
		useHeld = held;
	}

	public synchronized boolean isUseHeld() {
		return useHeld;
	}

	/** Seed the expected rotation so real mouse movement can be detected before the bot ever turns. */
	public synchronized void seedLook(LocalPlayer p) {
		if (!Float.isNaN(lastAppliedYaw)) return;
		lastAppliedYaw = p.getYRot();
		lastAppliedPitch = p.getXRot();
	}

	/** Aim at a yaw/pitch; the controller interpolates toward it each tick, like moving a mouse. */
	public synchronized void lookAtTarget(float yaw, float pitch) {
		this.lookTargetYaw = yaw;
		this.lookTargetPitch = Mth.clamp(pitch, -90.0F, 90.0F);
	}

	public synchronized void clearLookTarget() {
		this.lookTargetYaw = null;
		this.lookTargetPitch = null;
	}

	/** Degrees left to turn toward the current target (0 when settled or idle). */
	public synchronized float lookErrorDeg() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || lookTargetYaw == null) return 0.0F;
		float dy = Math.abs(Mth.wrapDegrees(lookTargetYaw - p.getYRot()));
		float dp = Math.abs((lookTargetPitch == null ? p.getXRot() : lookTargetPitch) - p.getXRot());
		return Math.max(dy, dp);
	}

	public synchronized boolean hasAppliedLook() {
		return !Float.isNaN(lastAppliedYaw);
	}

	public synchronized float lastAppliedYaw() {
		return lastAppliedYaw;
	}

	public synchronized float lastAppliedPitch() {
		return lastAppliedPitch;
	}

	// desired key state, so a watcher can tell bot input from real input
	public synchronized boolean wantsForward() { return fwd; }
	public synchronized boolean wantsBack() { return back; }
	public synchronized boolean wantsLeft() { return left; }
	public synchronized boolean wantsRight() { return right; }
	public synchronized boolean wantsJump() { return jumpHeld || jumpOnceTicks > 0; }
	public synchronized boolean wantsSneak() { return sneak; }
	public synchronized boolean wantsSprint() { return sprint; }

	// diagnostics
	public synchronized boolean isDrivingKeys() { return drivingKeys; }
	public synchronized float pendingLookYaw() { return lookTargetYaw == null ? Float.NaN : lookTargetYaw; }
	public synchronized float pendingLookPitch() { return lookTargetPitch == null ? Float.NaN : lookTargetPitch; }
	public synchronized String navStateText() { return navState; }
	public synchronized int navRemainingNodes() { return path == null ? -1 : Math.max(0, path.size() - pathIndex); }

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
			// A real player has the controls: drop everything and let them drive.
			if (HumanControl.suspended()) {
				stopAllMovement();
				attackHeld = false;
				useHeld = false;
				clearLookTarget();
				if (drivingKeys) {
					releaseKeys(mc.options);
					drivingKeys = false;
				}
				return;
			}

			if (path != null) {
				steer(mc, p);
			}
			applyWaterSafety(p);
			tickLook(p);
			boolean driving = fwd || back || left || right || jumpHeld || sneak || sprint
					|| jumpOnceTicks > 0 || attackHeld || useHeld || path != null;
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
		o.keyAttack.setDown(attackHeld);
		o.keyUse.setDown(useHeld);
	}

	private void releaseKeys(Options o) {
		o.keyUp.setDown(false);
		o.keyDown.setDown(false);
		o.keyLeft.setDown(false);
		o.keyRight.setDown(false);
		o.keyShift.setDown(false);
		o.keySprint.setDown(false);
		o.keyJump.setDown(false);
		o.keyAttack.setDown(false);
		o.keyUse.setDown(false);
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

	private void applyLook(LocalPlayer p, float yaw, float pitch) {
		float cy = Mth.wrapDegrees(yaw);
		float cp = Mth.clamp(pitch, -90.0F, 90.0F);
		p.setYRot(cy);
		p.setXRot(cp);
		p.setYHeadRot(cy);
		p.setYBodyRot(cy);
		lastAppliedYaw = cy;
		lastAppliedPitch = cp;
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

		boolean inWater = p.isInWater();

		// Sprint like a player does: over a longer haul, and always in water — sprinting is exactly
		// what puts the player into the swimming pose, so disabling it there makes them crawl.
		sprint = navSprint || inWater || p.position().distanceTo(Vec3.atBottomCenterOf(navTarget)) > 4.0;
		if (sneak) sprint = false;

		if (inWater) {
			// Look along the path including up/down, so swimming can descend to a submerged node.
			double dyNode = (node.getY() + 0.5) - p.getEyeY();
			float pitch = (float) (-(Mth.atan2(dyNode, Math.max(horiz, 0.01)) * (180.0 / Math.PI)));
			lookAtTarget(yaw, Mth.clamp(pitch, -70.0F, 45.0F));
		} else {
			lookAtTarget(yaw, 0.0F);
		}

		fwd = true;
		back = left = right = false;

		// Stroke upward when the next node is higher, or to keep the head at the surface on a level
		// swim. When the node is below, do NOT jump — that is how the bot dives to it.
		boolean needHeight = node.getY() > p.getY() + 0.4;
		boolean descending = node.getY() < p.getY() - 0.4;
		if (inWater) {
			if (needHeight || (p.isUnderWater() && !descending)) {
				jumpOnceTicks = Math.max(jumpOnceTicks, 1);
			}
		} else if (needHeight) {
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

	/**
	 * Never drown. The moment the head goes under with the air bar running low, the bot drops what it
	 * is doing and strokes upward until it can breathe again. Runs after navigation so it always wins,
	 * and works even when no task is active — otherwise the bot would happily leave the player bobbing
	 * under the surface until they died.
	 */
	private void applyWaterSafety(LocalPlayer p) {
		boolean under = p.isUnderWater();
		if (under) {
			if (p.getAirSupply() <= AIR_SURFACE_AT) surfacing = true;
		} else if (!p.isInWater() || p.getAirSupply() >= AIR_CLEAR_AT) {
			// Head is out of the water (or we are back on land): safe again.
			surfacing = false;
		}
		if (surfacing) {
			// Override whatever the task wanted: rise straight up until we can breathe.
			fwd = back = left = right = false;
			sprint = false;
			jumpHeld = true;
			surfaceJumpHeld = true;
		} else if (surfaceJumpHeld) {
			jumpHeld = false;
			surfaceJumpHeld = false;
		}
	}

	private void stopNavigationInternal(String reason) {
		path = null;
		navState = reason;
		fwd = false;
		sprint = false;
	}
}
