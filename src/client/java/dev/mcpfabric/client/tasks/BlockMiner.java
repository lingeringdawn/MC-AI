package dev.mcpfabric.client.tasks;

import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.Humanizer;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
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
	/** The block whose break we have already started, so start is not re-issued every tick. */
	private BlockPos destroying;
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
		// Only dig once the crosshair is actually settled on the block. A tight per-tick tolerance
		// makes the controller keep re-aiming and never press, so wait for the interpolated turn to
		// arrive (isLookSettled) instead of re-issuing the aim forever.
		if (!BotController.get().isLookSettled() && BotController.get().lookErrorDeg() > 3.0F) {
			settleTicks = 0;
			stopBreaking(mc);
			return State.WORKING;
		}
		// A person looks at the block for a beat before swinging at it; pressing the instant the
		// crosshair lands reads as a machine.
		if (Humanizer.enabled() && settleTicks < WINDUP_TICKS) {
			settleTicks++;
			stopBreaking(mc);
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
			startBreaking(mc, p, pos, hit.getDirection());
			return State.WORKING;
		}
		stopBreaking(mc);
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
			startBreaking(mc, p, clearing, hit.getDirection());
			return State.WORKING;
		}

		return State.UNREACHABLE;
	}

	void cancel() {
		stop();
	}

	/**
	 * Keep breaking one block by driving the destruction calls the vanilla input pipeline makes for a
	 * held left button, rather than leaving the attack key pressed.
	 *
	 * <p>Merely holding the key is not enough: the "start breaking" branch fires on the press edge and
	 * only the "continue" branch runs while it is held, so a key that is nothing but down can leave the
	 * bot swinging at a block that never actually starts to break. That is the block-side twin of the
	 * entity-attack bug, and it looks identical from the outside — crosshair on target, key pressed,
	 * block untouched.
	 */
	private void startBreaking(Minecraft mc, LocalPlayer p, BlockPos target, Direction face) {
		if (mc.gameMode == null) return;
		if (!target.equals(destroying)) {
			mc.gameMode.startDestroyBlock(target, face);
			destroying = target.immutable();
		}
		mc.gameMode.continueDestroyBlock(target, face);
		p.swing(InteractionHand.MAIN_HAND);
		// Never leave the button held: a stuck attack key would dig whatever the crosshair drifts onto.
		BotController.get().setAttackHeld(false);
	}

	private void stopBreaking(Minecraft mc) {
		destroying = null;
		BotController.get().setAttackHeld(false);
	}

	private void stop() {
		destroying = null;
		BotController.get().setAttackHeld(false);
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
}
