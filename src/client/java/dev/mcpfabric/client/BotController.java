package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
	/**
	 * Air left when the bot abandons what it is doing and heads for the surface. Generous on purpose:
	 * surfacing takes a moment, and a bot that starts late arrives with no margin.
	 */
	private static final int AIR_SURFACE_AT = 220;
	/** Air that counts as "breathing again"; below this we keep the head above the water. */
	private static final int AIR_CLEAR_AT = 290;
	private boolean surfacing;
	private boolean surfaceJumpHeld;

	// smooth look (mouse-delta style): interpolate toward a yaw/pitch target each tick
	/**
	 * Look-priority levels. Several subsystems want to point the camera at once (navigation wants the
	 * next path node, a task wants the block or entity it is working on, an explicit control call
	 * wants whatever the caller asked for). Without arbitration they overwrite each other every tick
	 * and the view whips back and forth between two directions. A request only replaces the current
	 * one if it is at least as important, or if the current one has gone stale.
	 */
	public static final int LOOK_NAV = 1;
	public static final int LOOK_TASK = 2;
	/**
	 * Self-defence: a mob chewing on you outranks whatever job you were doing, but not an explicit
	 * instruction from the caller — so it sits between the task and the user.
	 */
	public static final int LOOK_DEFEND = 3;
	/**
	 * Drowning beats everything except being told what to do: once the head goes under, where the task
	 * wanted to look stops mattering.
	 */
	public static final int LOOK_SAFETY = 4;
	public static final int LOOK_USER = 5;
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

	// idle gaze drift: a person standing still still moves their head a little
	/** Ticks spent doing nothing at all; drives the sway phase so it always restarts smooth. */
	private int idleTicks;
	/** Sway value applied last tick, so only the *change* is added (never accumulates, never fights). */
	private float swayYaw;
	private float swayPitch;

	/**
	 * Sub-degree aim differences are ignored. Every tick the aim point is recomputed from a slightly
	 * different player position, so chasing it to the last 0.1° renders as a camera that shivers in
	 * place; a person's hand is nowhere near that precise either.
	 */
	private static final float LOOK_DEAD_ZONE = 1.0F;
	/**
	 * Walking tolerates a much wider dead zone than aiming does. A couple of degrees off course costs
	 * nothing while following a path (the next node keeps correcting it), whereas making the camera
	 * follow the aim point to within a degree while the aim point itself moves every tick is exactly
	 * what renders as a shivering view.
	 */
	private static final float NAV_LOOK_DEAD_ZONE = 3.0F;

	// --- walking ------------------------------------------------------------------------------
	/** Consider a path node reached once within this horizontal distance, and never back up past it. */
	private static final double NODE_ARRIVE_HORIZ = 0.9;
	/** How far ahead (in nodes / blocks) to look when smoothing the grid path into a straight line. */
	private static final int MAX_LOOKAHEAD_NODES = 8;
	private static final double MAX_LOOKAHEAD_BLOCKS = 9.0;
	/** Don't smooth across a height change bigger than this — that leg is a real step, not a zigzag. */
	private static final double SMOOTH_MAX_DY = 1.2;
	/** Turn on the spot until the heading is within this many degrees, instead of walking while wrong. */
	private static final float MOVE_ALIGN_DEG = 60.0F;
	/** Randomised jump hold, so no two hops are identical. */
	private final java.util.Random jumpRng = new java.util.Random();
	/** Water-bucket fall save. Runs before steering and takes over while it is saving us. */
	private final FallGuard fallGuard = new FallGuard();

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

	/**
	 * Point the view at an angle in this very tick, bypassing the interpolated turn. The eased flick is
	 * right for normal aiming, but some things happen inside a single tick — emptying a water bucket
	 * one tick before hitting the ground cannot wait four ticks for the crosshair to get there.
	 */
	public synchronized void snapLook(float yaw, float pitch) {
		LocalPlayer p = Minecraft.getInstance().player;
		if (p == null) return;
		clearLookTarget();
		applyLook(p, yaw, pitch);
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
			// A navigation/task target that its owner stopped refreshing means the walk or task is
			// over, so drop it rather than holding the camera hostage. An explicit user look is NOT
			// dropped here: it is a one-shot request that must survive until the turn actually
			// arrives, otherwise a large turn would stall part-way and never finish.
			if (lookTargetYaw != null && lookPriority < LOOK_USER
					&& (currentTick - lookRefreshedTick) > LOOK_HOLD_TICKS) {
				lookTargetYaw = null;
				lookTargetPitch = null;
				lookPriority = Integer.MIN_VALUE;
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

			// Fall protection runs first: while it is saving us it owns the movement keys and the
			// crosshair, so skip steering for those ticks rather than fighting it for control.
			boolean saving = McpFabric.config().enableFallSaving && fallGuard.tick(mc, p, this);
			if (!saving && path != null) {
				steer(mc, p);
			}
			applyWaterSafety(p);
			// Look is applied last: navigation and the active task have both had their say this tick,
			// so the winner of the priority arbitration is what actually moves the camera. Capture
			// whether anyone wanted the view *before* applying it, because tickLook releases the
			// target on arrival and the sway gate below must not mistake that for "nobody cares".
			boolean lookWanted = lookTargetYaw != null;
			tickLook(p);
			boolean driving = fwd || back || left || right || jumpHeld || sneak || sprint
					|| jumpOnceTicks > 0 || attackHeld || useHeld || path != null;
			// Nobody is steering or working: drift the view the way a person does instead of freezing
			// the camera solid. Skipped the moment any owner wants the view, and never while the fall
			// guard is holding the crosshair straight down.
			if (!driving && !lookWanted && !saving) {
				applyIdleSway(p);
			} else {
				idleTicks = 0;
				swayYaw = 0.0F;
				swayPitch = 0.0F;
			}
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
		// Already close enough: leave the camera exactly where it is. Chasing the last fraction of a
		// degree makes the view shiver, because the aim point itself moves a little every tick.
		float dead = lookPriority == LOOK_NAV ? NAV_LOOK_DEAD_ZONE : LOOK_DEAD_ZONE;
		if (Math.abs(dYaw) <= dead && Math.abs(dPitch) <= dead) {
			lookTargetYaw = null;
			lookTargetPitch = null;
			lookPriority = Integer.MIN_VALUE;
			return;
		}
		// Human-style flick: the step scales with the remaining error, so the sweep starts fast and
		// settles softly instead of panning at one constant speed the whole way. Each axis is handled
		// separately, and when a step would reach the target we land exactly on it rather than step
		// past and have to come back (which is what produced the old oscillation).
		float yawStep = Humanizer.lookStep(dYaw);
		float pitchStep = Humanizer.lookStep(dPitch);
		boolean yawDone = Math.abs(dYaw) <= yawStep;
		boolean pitchDone = Math.abs(dPitch) <= pitchStep;
		if (yawDone && pitchDone) {
			applyLook(p, ty, targetPitch);
			// Arrived: release the view so the latch does not block the next request. Navigation and
			// the active task re-issue their aim on the next tick anyway.
			lookTargetYaw = null;
			lookTargetPitch = null;
			lookPriority = Integer.MIN_VALUE;
			return;
		}
		float yaw = yawDone ? ty : p.getYRot() + Mth.clamp(dYaw, -yawStep, yawStep);
		float pitch = pitchDone ? targetPitch : p.getXRot() + Mth.clamp(dPitch, -pitchStep, pitchStep);
		applyLook(p, yaw, pitch);
	}

	/**
	 * Drift the view a fraction of a degree at a time while the bot is idle, so a standing player is
	 * not a locked-off camera. Only the <em>change</em> in the sway curve is added to the live
	 * rotation: that way real mouse input is never fought and no offset accumulates, and when the
	 * sway stops the view simply stays where it is.
	 */
	private void applyIdleSway(LocalPlayer p) {
		idleTicks++;
		float seconds = idleTicks / 20.0F;
		float yawSway = Humanizer.idleSway(seconds, 1.6F, 0.35F, 0.0F);
		float pitchSway = Humanizer.idleSway(seconds, 0.8F, 0.27F, 1.3F);
		float dYaw = yawSway - swayYaw;
		float dPitch = pitchSway - swayPitch;
		swayYaw = yawSway;
		swayPitch = pitchSway;
		if (dYaw == 0.0F && dPitch == 0.0F) return;
		applyLook(p, p.getYRot() + dYaw, Mth.clamp(p.getXRot() + dPitch, -90.0F, 90.0F));
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

		// Advance monotonically to the first node we have not reached yet, and aim at that one.
		//
		// Monotonic is the whole point. Picking "the next node" from a bare distance threshold (look
		// one node ahead below 0.7, otherwise at the current node) had no hysteresis, so while the
		// player hovered around that distance the aim flicked between two neighbouring nodes every
		// tick. When those two nodes lie in different directions the camera visibly trembles, and the
		// walking direction flips with it. Once a node is behind us it stays behind us.
		int idx = pathIndex;
		while (idx < path.size() - 1 && reachedNode(p, path.get(idx))) {
			idx++;
		}
		pathIndex = idx;

		// Smooth the grid path: walk to the furthest node we can reach in a straight line rather than
		// to the very next one. A 4-direction A* route is a staircase wherever it runs diagonally, so
		// following it node by node would mean a 90 degree turn at every single step — which reads as
		// stop-start, zig-zag walking. Aiming at the furthest visible node turns that staircase back
		// into the straight diagonal a person would actually walk, and keeps the heading steady.
		double feetY = p.getY();
		int limit = Math.min(path.size() - 1, pathIndex + MAX_LOOKAHEAD_NODES);
		for (int k = pathIndex + 1; k <= limit; k++) {
			BlockPos cand = path.get(k);
			if (Math.abs(cand.getY() - feetY) > SMOOTH_MAX_DY) break;
			if (horizOf(p, cand) > MAX_LOOKAHEAD_BLOCKS) break;
			if (!straightWalkable(mc.level, p, cand)) break;
			pathIndex = k;
		}

		BlockPos node = path.get(pathIndex);

		// A one-node path whose only node is where we already stand means the pathfinder decided the
		// start was inside its own reach and had nothing to walk. Following that aims the bot at its
		// own feet and leaves it orbiting that block until the deadline — which is what happened when
		// collecting a drop sitting one block below. The caller's tolerance can be tighter than the
		// pathfinder's, so head straight for the target and cover the last fraction of a block.
		BlockPos aimNode = node;
		if (path.size() == 1 && horizOf(p, node) < 0.7) aimNode = navTarget;

		double dx = aimNode.getX() + 0.5 - p.getX();
		double dz = aimNode.getZ() + 0.5 - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		if (horiz < 0.05) {
			// Only the final node can be underfoot now. Fall back to the nav target so the heading is
			// never derived from a zero-length vector, whose yaw flips every tick.
			dx = navTarget.getX() + 0.5 - p.getX();
			dz = navTarget.getZ() + 0.5 - p.getZ();
			horiz = Math.sqrt(dx * dx + dz * dz);
		}

		boolean inWater = p.isInWater();
		ClientLevel level = mc.level;
		if (level == null) {
			stopNavigationInternal("no_world");
			return;
		}

		double ux = 0.0;
		double uz = 0.0;
		if (horiz >= 0.05) {
			ux = dx / horiz;
			uz = dz / horiz;
		}
		BlockPos aheadFeet = BlockPos.containing(p.getX() + ux, p.getY(), p.getZ() + uz);
		boolean onLadder = AStarPathfinder.isClimbable(level, p.blockPosition())
				|| AStarPathfinder.isClimbable(level, aheadFeet);
		// Only "climbing" while there is still height to gain. Once the path turns off the ladder at
		// the top, the ordinary heading has to win, or the bot would hug the wall forever.
		boolean climbing = onLadder && node.getY() > p.getY() + 0.3;

		float yawErr = 180.0F;
		Float climbYaw = climbing ? wallFacingYaw(level, p) : null;
		if (climbYaw != null) {
			// A ladder is climbed by facing the wall it hangs on and walking into it. Aiming at the path
			// node instead points the bot *along* the wall, so "forward" walks it straight off the
			// ladder — which is exactly what happened the first time this was tried.
			yawErr = Math.abs(Mth.wrapDegrees(climbYaw - p.getYRot()));
			lookAtTarget(climbYaw, p.getXRot(), LOOK_NAV);
		} else if (horiz >= 0.05) {
			float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
			yawErr = Math.abs(Mth.wrapDegrees(yaw - p.getYRot()));
			if (inWater) {
				// Look along the path including up/down, so swimming can descend to a submerged node.
				double dyNode = (node.getY() + 0.5) - p.getEyeY();
				float pitch = (float) (-(Mth.atan2(dyNode, Math.max(horiz, 0.01)) * (180.0 / Math.PI)));
				lookAtTarget(yaw, Mth.clamp(pitch, -70.0F, 45.0F), LOOK_NAV);
			} else {
				lookAtTarget(yaw, 0.0F, LOOK_NAV);
			}
		}

		// Turn first, walk after — the way a person does. Pressing forward while still facing the wrong
		// way makes the bot travel along an arc, and when the path nearly doubles back that arc becomes
		// a full circle around the node it is trying to reach. Standing still for the couple of ticks a
		// big turn takes costs almost nothing and is what actually makes the walk follow the path.
		boolean facing = yawErr <= MOVE_ALIGN_DEG;

		// Sprint like a player does: over a longer haul, and always in water — sprinting is exactly
		// what puts the player into the swimming pose, so disabling it there makes them crawl. Never
		// sprint through a sharp turn: it only widens the arc.
		sprint = (navSprint || inWater || p.position().distanceTo(Vec3.atBottomCenterOf(navTarget)) > 4.0)
				&& (facing || inWater);
		if (sneak) sprint = false;

		fwd = facing;
		back = left = right = false;

		// Decide to hop *before* we arrive, and from a sprint. That is how a person clears a step or a
		// gap without slowing down: the jump is pressed a little early so the sprint momentum is
		// already there, rather than arriving, stopping, and hopping from a standstill. Jumping only
		// from the ground and only when facing the way we are going keeps it from looking twitchy.
		if (!inWater && !onLadder && p.onGround() && facing) {
			BlockPos stepBlock = BlockPos.containing(p.getX() + ux * 1.1, p.getY(), p.getZ() + uz * 1.1);
			BlockPos floorBlock = BlockPos.containing(p.getX() + ux * 1.1, p.getY() - 1.0, p.getZ() + uz * 1.1);
			boolean stepUp = solid(level, stepBlock) && passable(level, stepBlock.above());
			boolean holeAhead = passable(level, stepBlock) && !solid(level, floorBlock);
			boolean nodeHigher = node.getY() > p.getY() + 0.5;

			// A gap the path wants crossed: the landing node sits a couple of blocks away with nothing
			// to walk on in between. Walking would drop us in, so this has to be a sprint-jump.
			double nodeGap = horizOf(p, node);
			// Only attempt a hop the current state can actually fly: three blocks needs a sprint, and a
			// starving player cannot sprint at all.
			boolean canSprint = p.getFoodData().getFoodLevel() > 6;
			double maxHop = canSprint ? 4.2 : 2.4;
			boolean gapRoute = holeAhead && nodeGap > 1.6 && nodeGap <= maxHop
					&& Math.abs(node.getY() - p.getY()) <= 1.2;
			// Sprint while still approaching, so the run-up is already at speed by take-off.
			if (gapRoute) sprint = true;

			// A step-up is cleared by jumping slightly early (you need the height as you arrive), but a
			// gap has to be jumped from the very lip: taking off a block early just loses a block of
			// distance and lands in the hole, which is exactly what happened before.
			BlockPos nearFeet = BlockPos.containing(p.getX() + ux * 0.6, p.getY(), p.getZ() + uz * 0.6);
			BlockPos nearFloor = BlockPos.containing(p.getX() + ux * 0.6, p.getY() - 1.0, p.getZ() + uz * 0.6);
			boolean atLip = passable(level, nearFeet) && !solid(level, nearFloor);

			if (stepUp || nodeHigher) {
				sprint = true;
				jumpOnceTicks = Math.max(jumpOnceTicks, Humanizer.ticks(jumpRng, 4, 7));
			} else if (gapRoute && atLip) {
				// A full jump, held: a short tap would not carry far enough horizontally.
				jumpOnceTicks = Math.max(jumpOnceTicks, Humanizer.ticks(jumpRng, 8, 11));
			}
		}

		if (inWater) {
			// Stroke upward to climb, or to keep the head at the surface on a level swim. When the node
			// is below, do NOT jump — that is how the bot dives to it.
			boolean needHeight = node.getY() > p.getY() + 0.5;
			boolean descending = node.getY() < p.getY() - 0.4;
			// A bank at roughly our own level: swim up to it and hop out. The step-up hop above is
			// skipped while in water, so without this the bot treads water against the shore forever
			// instead of climbing out — and never getting out is what kills it.
			BlockPos feetAhead = BlockPos.containing(p.getX() + ux, p.getY(), p.getZ() + uz);
			BlockPos headAhead = feetAhead.above();
			boolean bankAhead = horiz >= 0.05 && !descending
					&& solid(level, feetAhead) && passable(level, headAhead) && !liquid(level, headAhead);
			if (needHeight || (p.isUnderWater() && !descending) || bankAhead) {
				jumpOnceTicks = Math.max(jumpOnceTicks, Humanizer.ticks(jumpRng, 3, 6));
			}
		} else if (climbing) {
			// On a ladder or vine the way up is to hold forward against it and keep pressing jump.
			sprint = false;
			if (node.getY() > p.getY() + 0.3) jumpOnceTicks = Math.max(jumpOnceTicks, 3);
		}

		// Stuck recovery ladder: hop -> re-plan -> give up. Nothing counts as stuck while we are still
		// turning on the spot, or every sharp corner would register as being wedged.
		if (!facing) {
			stuckTicks = 0;
			lastDist = Double.MAX_VALUE;
		} else if (dist < lastDist - 0.02) {
			stuckTicks = 0;
			lastDist = dist;
		} else {
			stuckTicks++;
			if (stuckTicks == 15 || stuckTicks == 30) {
				jumpOnceTicks = Math.max(jumpOnceTicks, 4);
			} else if (stuckTicks == 50) {
				repath(mc, p);
				stuckTicks = 0;
				lastDist = Double.MAX_VALUE;
			} else if (stuckTicks > 160) {
				stopNavigationInternal("stuck");
			}
		}
	}

	private static boolean passable(ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private static boolean solid(ClientLevel level, BlockPos pos) {
		return !passable(level, pos);
	}

	private static boolean liquid(ClientLevel level, BlockPos pos) {
		return !level.getBlockState(pos).getFluidState().isEmpty();
	}

	/**
	 * Can the player walk in a straight horizontal line to {@code node} without hitting anything and
	 * without crossing a hole? This is what lets the follower skip the staircase nodes of a grid path
	 * safely: it only ever cuts a corner it can actually walk through.
	 */
	private static boolean straightWalkable(ClientLevel level, LocalPlayer p, BlockPos node) {
		double sx = p.getX();
		double sz = p.getZ();
		double dx = (node.getX() + 0.5) - sx;
		double dz = (node.getZ() + 0.5) - sz;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 0.3) return true;
		int feetY = node.getY();
		int samples = (int) Math.ceil(len / 0.5);
		for (int i = 1; i <= samples; i++) {
			double t = (double) i / samples;
			BlockPos feet = new BlockPos(Mth.floor(sx + dx * t), feetY, Mth.floor(sz + dz * t));
			if (!passable(level, feet)) return false;
			if (!passable(level, feet.above())) return false;
			// Ground must be under every sample, so a "shortcut" never walks over a hole.
			if (!solid(level, feet.below())) return false;
		}
		return true;
	}

	/**
	 * Yaw that faces the wall a ladder or vine is attached to, or null when no side is solid. Climbing
	 * is done by walking <em>into</em> that wall, so this is the heading that actually makes the bot
	 * ascend instead of stepping sideways off the ladder.
	 */
	private static Float wallFacingYaw(ClientLevel level, LocalPlayer p) {
		BlockPos base = p.blockPosition();
		for (Direction d : Direction.Plane.HORIZONTAL) {
			BlockPos side = base.relative(d);
			if (!solid(level, side)) continue;
			double dx = side.getX() + 0.5 - p.getX();
			double dz = side.getZ() + 0.5 - p.getZ();
			return (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		}
		return null;
	}

	/**
	 * Has the player arrived at this path node? Both axes matter. A ladder column stacks all of its
	 * nodes on one footprint, so a purely horizontal "have I arrived" test marks the whole climb as
	 * reached the instant the bot touches the bottom rung — and then the follower aims at whatever the
	 * path does next while still standing on the ground, which stalls the climb entirely.
	 */
	private static boolean reachedNode(LocalPlayer p, BlockPos node) {
		double dy = node.getY() - p.getY();
		return horizOf(p, node) < NODE_ARRIVE_HORIZ && dy < 1.5 && dy > -4.0;
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
		boolean eyeUnder = p.isUnderWater();
		if (!p.isInWater()) {
			surfacing = false;
		} else if (eyeUnder) {
			// While a path is being followed the bot may be diving to a submerged node on purpose, so
			// hold off until the air genuinely runs low. With no path it is only in the water by
			// accident — idle, pushed in, or knocked back into it — and there is no reason to let it
			// sink at all, so keep the head up from the first bubble lost.
			int limit = path != null ? AIR_SURFACE_AT : 300;
			if (p.getAirSupply() <= limit) surfacing = true;
		} else if (p.getAirSupply() >= AIR_CLEAR_AT) {
			// Head is clear and the lungs are full again: safe.
			surfacing = false;
		}
		if (surfacing) {
			// Override whatever the task wanted: rise straight up until we can breathe.
			fwd = back = left = right = false;
			sprint = false;
			// Stroke only while the eyes are actually under water. Holding the key the whole time
			// launches the bot clear of the surface and it drops straight back with a splash — the head
			// then ping-pongs between the air and the pond floor, which reads as drowning and wastes the
			// stroke. Pulsing it keeps the head riding the waterline, which is what treading water looks
			// like, and the eyes break the surface every few ticks so the air bar refills.
			jumpHeld = surfaceStroke(p);
			surfaceJumpHeld = jumpHeld;
			// Tilt the view toward the surface while stroking up. The swim itself is driven by the jump
			// key, but a bot that keeps its face pointed at the pond floor while rising looks like it is
			// drowning on purpose.
			lookAtTarget(p.getYRot(), -45.0F, LOOK_SAFETY);
		} else if (surfaceJumpHeld) {
			jumpHeld = false;
			surfaceJumpHeld = false;
		}
	}

	/**
	 * Whether to push upward this tick while surfacing: yes while the eyes are under water, no once they
	 * break the surface. Vanilla swims on the jump key, so a steady hold launches the bot clean out of
	 * the pond and drops it back; pulsing keeps the head at the waterline instead.
	 */
	private static boolean surfaceStroke(LocalPlayer p) {
		return p.isUnderWater();
	}

	private void stopNavigationInternal(String reason) {
		path = null;
		navState = reason;
		fwd = false;
		sprint = false;
	}
}
