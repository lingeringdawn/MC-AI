package dev.mcpfabric.client.tasks;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.Humanizer;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Single-block mining with human-like priorities. It always goes for the requested block via the
 * normal left-mouse pipeline (nothing is destroyed through API calls), and when the shot is blocked
 * it behaves like a careful player:
 *
 * <ol>
 *   <li>crosshair on the target (in reach) -&gt; hold left-click and dig it;</li>
 *   <li>blocked -&gt; prefer walking to a nearby cell that has a clean line of sight;</li>
 *   <li>no such angle exists (or we already walked around enough) -&gt; clear the block actually in
 *       the way, the way a player digs through the vine/leaf covering a trunk;</li>
 *   <li>otherwise report UNREACHABLE.</li>
 * </ol>
 *
 * Clears are capped, so it never clear-cuts a forest to reach one log.
 */
final class BlockMiner {
	enum State { WORKING, DONE, UNREACHABLE }

	private static final int NODE_BUDGET = 12000;
	private static final double REACH = 4.5;
	private static final int MAX_REPOSITIONS = 3;
	private static final int MAX_CLEARS = 3;
	/** How close to the block we walk before aiming at it. */
	private static final double APPROACH_REACH = 1.5;
	/** Re-plans allowed while closing the distance, so a moving target does not loop forever. */
	private static final int MAX_WALKS = 4;
	/**
	 * How long a walk may run before it is treated as going nowhere. A vantage the pathfinder thought
	 * reachable but the steering can never arrive at would otherwise stall the miner for good, because
	 * every tick it simply defers to the walk — which is how standing next to a trunk, crosshair on a
	 * log, ended in mining nothing at all.
	 */
	private static final int NAV_LIMIT_TICKS = 60;
	/** Ticks spent looking at the block before the first swing (see {@link Humanizer}). */
	private static final int WINDUP_TICKS = 2;

	private final BlockPos pos;
	private int repositions;
	private int clears;
	private int walks;
	private int navTicks;
	private BlockPos clearing;
	/** The block whose press edge has already been sent, so a new one is not issued every tick. */
	private BlockPos digging;
	/** Ticks the crosshair has rested on the block; models the look-then-swing beat. */
	private int settleTicks;

	BlockMiner(BlockPos pos) {
		this.pos = pos;
	}

	BlockPos pos() {
		return pos;
	}

	boolean begin(Minecraft mc) {
		return mc.player != null && mc.level != null;
	}

	State tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) return State.UNREACHABLE;
		if (level.getBlockState(pos).isAir()) {
			stop();
			return State.DONE;
		}

		// Out of reach: walk to it. Aiming at a block you cannot touch just parks the bot in front of
		// whatever happens to lie between — and then the clear-the-obstruction fallback below spends
		// its whole allowance digging that instead of going to the thing it was asked to mine, which is
		// how "chop that tree" used to end with nothing chopped.
		if (p.getEyePosition().distanceTo(aimPoint(mc, pos)) > REACH) {
			if (isNavigating()) {
				if (++navTicks <= NAV_LIMIT_TICKS) return State.WORKING;
				BotController.get().stopNavigation("no_progress");
			}
			navTicks = 0;
			if (walks++ >= MAX_WALKS) return State.UNREACHABLE;
			List<BlockPos> approach = new AStarPathfinder(level, NODE_BUDGET)
					.findPath(p.blockPosition(), pos, APPROACH_REACH);
			if (approach == null || approach.isEmpty()) return State.UNREACHABLE;
			BotController.get().startNavigation(approach, pos, APPROACH_REACH, true,
					System.currentTimeMillis() + 30_000L);
			return State.WORKING;
		}

		aim(mc, p);
		// No jumping while the crosshair is supposed to be on the block. In shallow water the swim stroke
		// used to keep the jump key held, so the bot bounced in place, its eye moved every tick, and the
		// aim slipped off the block — restarting the break it was half-way through. Nothing is more
		// expensive to a dig than moving the camera, and nothing about mining needs a hop.
		BotController.get().clearJump();
		// Press only once the crosshair is actually on the block: the click that starts a break uses
		// vanilla's own hit result, and the ray clip further down only proves there is a clear line to
		// the block, which is not the same question. This is checked against that same hit result rather
		// than an angle threshold — a threshold can stay unsatisfied for as long as the aim is still
		// turning, and then the dig never starts at all, which is how a miner can sit on a block for its
		// entire budget without ever taking a swing at it.
		// Digging stays tied to the crosshair, deliberately: the break only counts while the view is
		// actually on the block, so when something knocks the aim off, stopping is the correct answer
		// rather than pushing a break the game is no longer tracking.
		if (!crosshairOn(mc, pos)) {
			settleTicks = 0;
			// The crosshair is not on the target. If it is resting on something else that is in reach,
			// that thing is what the next click would actually hit — a leaf over a trunk, a vine over a
			// log — so dig through it. Returning here instead is what made a log under its own canopy
			// unmineable: the aim was right, the crosshair was on the leaf, and the miner simply waited
			// for the crosshair to move on its own until the budget ran out.
			BlockHitResult on = mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
			BlockPos blocking = on == null ? null : on.getBlockPos();
			if (blocking != null && !blocking.equals(pos)
					&& p.getEyePosition().distanceTo(Vec3.atCenterOf(blocking)) <= REACH) {
				if (clearing == null || !clearing.equals(blocking)) {
					if (clears >= MAX_CLEARS) {
						stopDigging();
						return State.UNREACHABLE;
					}
					clearing = blocking.immutable();
					clears++;
				}
				if (level.getBlockState(clearing).isAir()) {
					clearing = null;
					return State.WORKING;
				}
				// Crosshair already rests on the obstruction — that is the whole point of this branch.
				dig(mc, clearing);
				return State.WORKING;
			}
			stopDigging();
			return State.WORKING;
		}
		// A person looks at the block for a beat before swinging at it; pressing the instant the
		// crosshair lands reads as a machine.
		if (Humanizer.enabled() && settleTicks < WINDUP_TICKS) {
			settleTicks++;
			stopDigging();
			return State.WORKING;
		}

		Vec3 eye = p.getEyePosition();
		Vec3 aimPoint = aimPoint(mc, pos);
		double reach = eye.distanceTo(aimPoint);
		BlockHitResult hit = level.clip(new ClipContext(eye, aimPoint, ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, p));
		BlockPos hitPos = hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;

		// 1) Clear shot at the target, in reach: this is the good case, just dig it.
		if (pos.equals(hitPos) && reach <= REACH) {
			clearing = null;
			dig(mc, pos);
			return State.WORKING;
		}
		stopDigging();
		if (isNavigating()) {
			if (++navTicks <= NAV_LIMIT_TICKS) return State.WORKING;
			// The walk is going nowhere: drop it and let the strategies below have a turn. Digging the
			// block actually in the crosshair is usually the answer when the target sits behind it.
			BotController.get().stopNavigation("no_progress");
		}
		navTicks = 0;

		// 2) Look for a nearby spot with a clear line of sight and walk there first.
		if (repositions < MAX_REPOSITIONS) {
			BlockPos vantage = findVantage(mc, p);
			if (vantage != null && !vantage.equals(p.blockPosition())) {
				List<BlockPos> path = new AStarPathfinder(level, NODE_BUDGET)
						.findPath(p.blockPosition(), vantage, 0.8);
				if (path != null && !path.isEmpty()) {
					BotController.get().startNavigation(path, vantage, 0.8, false,
							System.currentTimeMillis() + 30_000L);
					repositions++;
					return State.WORKING;
				}
			}
		}

		// 3) No clean angle: dig through whatever is in the way, exactly like a player would.
		if (hitPos != null && !hitPos.equals(pos) && eye.distanceTo(Vec3.atCenterOf(hitPos)) <= REACH) {
			if (clearing == null || !clearing.equals(hitPos)) {
				if (clears >= MAX_CLEARS) return State.UNREACHABLE;
				clearing = hitPos.immutable();
				clears++;
			}
			if (level.getBlockState(clearing).isAir()) {
				clearing = null;
				return State.WORKING;
			}
			// Crosshair already rests on the obstruction.
			dig(mc, clearing);
			return State.WORKING;
		}

		return State.UNREACHABLE;
	}

	void cancel() {
		stop();
	}

	/**
	 * Dig by holding the real attack key and, once per block, registering a click — the same two things
	 * a person does. Vanilla's own input handling then fires the press edge that starts the break and
	 * carries it on for as long as the button stays down.
	 *
	 * <p>Calling {@code gameMode.startDestroyBlock}/{@code continueDestroyBlock} directly looked as if it
	 * worked: the block disappeared, in about a quarter of the time a bare hand should take. What it did
	 * not do was produce anything — a chopped trunk left no wood on the ground, because the break was
	 * never the one the server agreed to. Going through the input path means the break is started,
	 * progressed and finished by exactly the code that runs when a player holds the button.
	 */
	private void dig(Minecraft mc, BlockPos target) {
		// Through the real input channel and nothing else: the real attack binding held down, plus one
		// genuine click notification per block — the same call the mouse handler makes when a player
		// clicks. Vanilla's own input handling then starts the break and carries it on, so what the server
		// sees is a player's break: it agrees to it, and the block drops what it should.
		//
		// Reaching into gameMode.startDestroyBlock/continueDestroyBlock instead is a back door, and it
		// showed: the block vanished while no wood appeared on the ground, because the break was never the
		// one the server had agreed to. The channel is not a stylistic preference here, it is what makes
		// the break real.
		BotController.get().setAttackHeld(true);
		if (!target.equals(digging)) {
			digging = target.immutable();
			KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(0));
		}
	}

	private void stopDigging() {
		digging = null;
		// Never leave the button held: a stuck attack key would dig whatever the crosshair drifts onto.
		BotController.get().setAttackHeld(false);
	}

	private void stop() {
		stopDigging();
		BotController.get().stopNavigation("cancelled");
	}

	/** Nearest standable cell within reach of the block from which the crosshair would land on it. */
	private BlockPos findVantage(Minecraft mc, LocalPlayer p) {
		Vec3 aimPoint = aimPoint(mc, pos);
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dy = -2; dy <= 2; dy++) {
			for (int dx = -3; dx <= 3; dx++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (dx == 0 && dz == 0) continue;
					BlockPos c = pos.offset(dx, dy, dz);
					if (!standable(mc, c)) continue;
					Vec3 eye = Vec3.atBottomCenterOf(c).add(0.0, 1.62, 0.0);
					double d = eye.distanceToSqr(aimPoint);
					if (d > REACH * REACH || d >= bestD) continue;
					BlockHitResult hit = mc.level.clip(new ClipContext(eye, aimPoint,
							ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
					if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)) {
						bestD = d;
						best = c;
					}
				}
			}
		}
		return best;
	}

	private boolean standable(Minecraft mc, BlockPos c) {
		ClientLevel level = mc.level;
		return level.getBlockState(c).getCollisionShape(level, c).isEmpty()
				&& level.getBlockState(c.above()).getCollisionShape(level, c.above()).isEmpty()
				&& !level.getBlockState(c.below()).getCollisionShape(level, c.below()).isEmpty();
	}

	/** Aim at the centre of the block's shape (not the block centre — vines etc. are not full cubes). */
	private void aim(Minecraft mc, LocalPlayer p) {
		Vec3 point = aimPoint(mc, pos);
		double dx = point.x - p.getX();
		double dy = point.y - p.getEyeY();
		double dz = point.z - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		float pitch = (float) (-(Math.atan2(dy, horiz) * (180.0 / Math.PI)));
		// Task priority: aiming at the block we are digging outranks the walking direction, so the two
		// do not alternate control of the camera every tick.
		BotController.get().lookAtTarget(yaw, pitch, BotController.LOOK_TASK);
	}

	private static Vec3 aimPoint(Minecraft mc, BlockPos at) {
		VoxelShape shape = mc.level.getBlockState(at).getShape(mc.level, at);
		if (shape.isEmpty()) return Vec3.atCenterOf(at);
		AABB b = shape.bounds().move(at.getX(), at.getY(), at.getZ());
		return b.getCenter();
	}

	private static boolean isNavigating() {
		var s = BotController.get().statusJson();
		return s.has("active") && s.get("active").getAsBoolean();
	}

	/**
	 * True when vanilla's crosshair — the thing a click would target — is resting on this block. Shared
	 * with the task's progress so "is it aimed yet" is answered by the same question the game asks.
	 */
	static boolean crosshairOn(Minecraft mc, BlockPos target) {
		return mc.hitResult instanceof BlockHitResult hit
				&& hit.getType() == HitResult.Type.BLOCK
				&& target.equals(hit.getBlockPos());
	}
}
