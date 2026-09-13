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

	/**
	 * Look-priority levels. Several subsystems want to point the camera at once (navigation wants the
	 * next path node, a task wants the block or entity it is working on, an explicit control call
	 * wants whatever the caller asked for). Without arbitration they overwrite each other every tick
	 * and the view whips back and forth between two directions. A request only replaces the current
	 * one if it is at least as important, or if the current one has gone stale.
	 */
	public static final int LOOK_NAV = 1;
	public static final int LOOK_TASK = 2;
	public static final int LOOK_USER = 3;
	/** How long the current look owner keeps the view after its last refresh. */
	private static final long LOOK_HOLD_TICKS = 3;

	private Float lookTargetYaw;
	private Float lookTargetPitch;
	private int lookPriority = Integer.MIN_VALUE;
	/** Tick of the last refresh; small sentinel so tick arithmetic can never overflow. */
	private long lookRefreshedTick = -1000L;
	/** Game-tick counter used for the look latch and staleness checks. */
	private long currentTick;
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

	/**
	 * Aim at a yaw/pitch; the controller interpolates toward it each tick, like moving a mouse.
	 *
	 * @param priority who is asking ({@link #LOOK_NAV}, {@link #LOOK_TASK}, {@link #LOOK_USER}); a
	 *                 lower-priority request cannot steal the view from a higher-priority one that
	 *                 refreshed within {@link #LOOK_HOLD_TICKS}, which is what stops navigation and a
	 *                 task from fighting over the camera every tick.
	 */
	public synchronized void lookAtTarget(float yaw, float pitch, int priority) {
		boolean held = (currentTick - lookRefreshedTick) < LOOK_HOLD_TICKS;
		if (held && priority < lookPriority) return; // someone more important owns the view right now
		this.lookTargetYaw = yaw;
		this.lookTargetPitch = Mth.clamp(pitch, -90.0F, 90.0F);
		this.lookPriority = priority;
		this.lookRefreshedTick = currentTick;
	}

	/** Convenience for callers that are happy with the task-level priority. */
	public synchronized void lookAtTarget(float yaw, float pitch) {
		lookAtTarget(yaw, pitch, LOOK_TASK);
	}

	public synchronized void clearLookTarget() {
		this.lookTargetYaw = null;
		this.lookTargetPitch = null;
		this.lookPriority = Integer.MIN_VALUE;
		this.lookRefreshedTick = -1000L;
	}

	/** Degrees left to turn toward the current target (0 when settled or idle). */
	public synchronized float lookErrorDeg() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || lookTargetYaw == null) return 0.0F;
		float dy = Math.abs(Mth.wrapDegrees(lookTargetYaw - p.getYRot()));
		float dp = Math.abs((lookTargetPitch == null ? p.getXRot() : lookTargetPitch) - p.getXRot());
		return Math.max(dy, dp);
	}

	/**
	 * True when the crosshair is already on the pending target. Callers that mine or attack use this
	 * instead of polling {@link #lookErrorDeg()} for a slightly-less-than-exact angle, so they stop
	 * nudging the camera the instant it is good enough.
	 */
	public synchronized boolean isLookSettled() {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null || lookTargetYaw == null) return true;
		float dy = Math.abs(Mth.wrapDegrees(lookTargetYaw - p.getYRot()));
		float targetPitch = lookTargetPitch == null ? p.getXRot() : lookTargetPitch;
		float dp = Math.abs(targetPitch - p.getXRot());
		return dy <= 1.5F && dp <= 1.5F;
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
			currentTick++;
			// Drop a look target nothing refreshed any more (its owner stopped caring), so the next
			// subsystem to ask is not blocked by a stale latch.
			if (lookTargetYaw != null && (currentTick - lookRefreshedTick) > LOOK_HOLD_TICKS) {
				lookTargetYaw = null;
				lookTargetPitch = null;
			}
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
			// Look is applied last: navigation and the active task have both had their say this tick,
			// so the winner of the priority arbitration is what actually moves the camera.
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
		// Within one tick's step: snap the rest of the way, so the camera lands exactly on target
		// instead of stepping past it and having to come back (the classic oscillation).
		if (Math.abs(dYaw) <= LOOK_STEP_DEG && Math.abs(dPitch) <= LOOK_STEP_DEG) {
			applyLook(p, ty, targetPitch);
			return;
		}
		// A big turn is stepped toward the target; deliberately do not clear the target here, because
		// the owner (navigation / the active task) refreshes it every tick anyway.
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

		// Aim at the next node we have not reached yet. Aiming straight at the node currently under our
		// feet gives a near-zero direction vector, whose yaw flips wildly tick to tick — that is one of
		// the ways the camera ended up whipping around. Look one node ahead whenever the immediate node
		// is already underfoot, and fall back to the nav target if the node still gives no direction.
		BlockPos aimNode = node;
		if (horizOf(p, node) < 0.7 && pathIndex + 1 < path.size()) {
			aimNode = path.get(pathIndex + 1);
		}
		double dx = aimNode.getX() + 0.5 - p.getX();
		double dz = aimNode.getZ() + 0.5 - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		if (horiz < 0.05) {
			dx = navTarget.getX() + 0.5 - p.getX();
			dz = navTarget.getZ() + 0.5 - p.getZ();
			horiz = Math.sqrt(dx * dx + dz * dz);
		}

		boolean inWater = p.isInWater();

		// Sprint like a player does: over a longer haul, and always in water — sprinting is exactly
		// what puts the player into the swimming pose, so disabling it there makes them crawl.
		sprint = navSprint || inWater || p.position().distanceTo(Vec3.atBottomCenterOf(navTarget)) > 4.0;
		if (sneak) sprint = false;

		if (horiz >= 0.05) {
			float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
			if (inWater) {
				// Look along the path including up/down, so swimming can descend to a submerged node.
				double dyNode = (aimNode.getY() + 0.5) - p.getEyeY();
				float pitch = (float) (-(Mth.atan2(dyNode, Math.max(horiz, 0.01)) * (180.0 / Math.PI)));
				lookAtTarget(yaw, Mth.clamp(pitch, -70.0F, 45.0F), LOOK_NAV);
			} else {
				// Only flatten the pitch for level walking; on a multi-level path keep some of the
				// vertical info so the camera does not snap between headings at a step.
				lookAtTarget(yaw, 0.0F, LOOK_NAV);
			}
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

	/** Horizontal distance from the player to a block's centre. */
	private static double horizOf(LocalPlayer p, BlockPos node) {
		double dx = node.getX() + 0.5 - p.getX();
		double dz = node.getZ() + 0.5 - p.getZ();
		return Math.sqrt(dx * dx + dz * dz);
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
