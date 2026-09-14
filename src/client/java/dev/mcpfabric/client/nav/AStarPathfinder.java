package dev.mcpfabric.client.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A small A* pathfinder over block positions for walking bots. Considers same-level walking,
 * a one-block step-up (jump), dropping down up to three blocks, climbing ladder/vine columns, and
 * sprint-jumping across gaps, so routes use the same shortcuts a person would. Walkability is derived
 * from block collision shapes, so it works on any {@link BlockGetter} (client or server level).
 */
public final class AStarPathfinder {
	/** Widest gap we will route a sprint-jump across. A sprint-jump clears about four blocks flat. */
	private static final int MAX_JUMP = 3;

	private final BlockGetter level;
	private final int maxNodes;

	public AStarPathfinder(BlockGetter level, int maxNodes) {
		this.level = level;
		this.maxNodes = maxNodes;
	}

	private boolean passable(BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private boolean solid(BlockPos pos) {
		return !passable(pos);
	}

	private boolean liquid(BlockPos pos) {
		return !level.getBlockState(pos).getFluidState().isEmpty();
	}

	/** Blocks a real player would never walk into/onto. */
	private static final Set<String> DANGEROUS = Set.of(
			"minecraft:lava", "minecraft:fire", "minecraft:soul_fire", "minecraft:cactus",
			"minecraft:magma_block", "minecraft:sweet_berry_bush", "minecraft:campfire",
			"minecraft:soul_campfire", "minecraft:powder_snow");

	/** Blocks you can hold onto and climb, which a player uses as a vertical shortcut. */
	private static final Set<String> CLIMBABLE = Set.of(
			"minecraft:ladder", "minecraft:vine", "minecraft:scaffolding",
			"minecraft:twisting_vines", "minecraft:twisting_vines_plant",
			"minecraft:weeping_vines", "minecraft:weeping_vines_plant",
			"minecraft:cave_vines", "minecraft:cave_vines_plant");

	private boolean dangerous(BlockPos pos) {
		return DANGEROUS.contains(idOf(pos));
	}

	/** Can the bot grab this block and climb it? Shared with the movement follower. */
	public static boolean isClimbable(BlockGetter level, BlockPos pos) {
		return CLIMBABLE.contains(
				BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
	}

	private boolean climbable(BlockPos pos) {
		return isClimbable(level, pos);
	}

	private String idOf(BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
	}

	/**
	 * Can the bot's two-block body occupy this cell? True when standing on ground, treading water,
	 * swimming, or hanging on a ladder/vine — so paths may cross ponds and climb, instead of refusing
	 * them. Hazardous cells are never considered, so routes naturally go around lava/fire/cactus the
	 * way a cautious player would.
	 */
	private boolean canOccupy(BlockPos feet) {
		if (!passable(feet) || !passable(feet.above())) return false;
		if (dangerous(feet) || dangerous(feet.above()) || dangerous(feet.below())) return false;
		// Hanging on a ladder or vine: no floor needed, that is the whole point of climbing it.
		if (climbable(feet) || climbable(feet.above())) return true;
		return solid(feet.below()) || liquid(feet) || liquid(feet.below());
	}

	/**
	 * @return a non-empty path of block positions (excluding start, ending at/near goal), or null if
	 * none. When the start already satisfies the reach a single-element path (the start) is returned,
	 * so callers can tell "nothing to walk" apart from "no path".
	 */
	public List<BlockPos> findPath(BlockPos start, BlockPos goal, double reachRadius) {
		Node startNode = new Node(start, 0, heuristic(start, goal), null);
		PriorityQueue<Node> open = new PriorityQueue<>();
		Map<Long, Double> best = new HashMap<>();
		open.add(startNode);
		best.put(start.asLong(), 0.0);

		int expanded = 0;
		while (!open.isEmpty() && expanded < maxNodes) {
			Node current = open.poll();
			expanded++;

			if (withinReach(current.pos, goal, reachRadius)) {
				List<BlockPos> path = reconstruct(current);
				if (path.isEmpty()) path.add(start); // start already qualifies -> nothing to walk
				return path;
			}

			for (Direction dir : Direction.Plane.HORIZONTAL) {
				BlockPos h = current.pos.relative(dir);

				if (canOccupy(h)) {
					// Water is slow and can drain the air bar, so land routes should win ties: wading
					// costs a bit more than walking, being fully submerged costs a lot.
					consider(open, best, current, h, liquid(h) ? (liquid(h.below()) ? 3.0 : 1.6) : 1.0, goal);
				} else if (canOccupy(h.above()) && passable(current.pos.above().above())) {
					consider(open, best, current, h.above(), 1.5, goal); // step / swim up
				} else {
					for (int d = 1; d <= 3; d++) {
						if (canOccupy(h.below(d))) {
							consider(open, best, current, h.below(d), 1.0 + 0.3 * d, goal);
							break;
						}
					}
				}

				// Sprint-jump across a gap. Without this the bot would refuse a route a person clears
				// in one hop (a ravine, a stream, a hole), and would take a long way round instead.
				for (int d = 2; d <= MAX_JUMP; d++) {
					BlockPos landing = current.pos.relative(dir, d);
					if (!canOccupy(landing)) break;
					if (Math.abs(landing.getY() - current.pos.getY()) > 1) break;
					if (!clearJumpArc(current.pos, landing, dir)) break;
					consider(open, best, current, landing, 1.4 * d, goal);
					break; // the shortest jump across is the one to take
				}
			}

			// Climb a ladder or vine column, one cell up or down at a time.
			if (climbable(current.pos) || climbable(current.pos.above())) {
				BlockPos up = current.pos.above();
				if (canOccupy(up)) consider(open, best, current, up, 1.0, goal);
				BlockPos down = current.pos.below();
				if (canOccupy(down)) consider(open, best, current, down, 0.8, goal);
			}
		}
		return null;
	}

	/** Relax an edge: record the better cost and queue the node when it improves. */
	private void consider(PriorityQueue<Node> open, Map<Long, Double> best, Node from, BlockPos next,
			double cost, BlockPos goal) {
		double tentativeG = from.g + cost;
		long key = next.asLong();
		Double prev = best.get(key);
		if (prev != null && tentativeG >= prev) return;
		best.put(key, tentativeG);
		open.add(new Node(next.immutable(), tentativeG, heuristic(next, goal), from));
	}

	/**
	 * Is the space between a take-off and a landing clear to fly through? Everything strictly between
	 * must be open at both body heights, so the bot only ever hops a gap it can actually clear rather
	 * than one with something sticking up in the middle of it.
	 */
	private boolean clearJumpArc(BlockPos from, BlockPos to, Direction dir) {
		int d = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
		int y = from.getY();
		for (int i = 1; i < d; i++) {
			BlockPos mid = from.relative(dir, i);
			if (!passable(new BlockPos(mid.getX(), y, mid.getZ()))) return false;
			if (!passable(new BlockPos(mid.getX(), y + 1, mid.getZ()))) return false;
			if (!passable(new BlockPos(mid.getX(), y + 2, mid.getZ()))) return false;
		}
		return true;
	}

	private static boolean withinReach(BlockPos a, BlockPos goal, double reach) {
		double dx = a.getX() - goal.getX();
		double dz = a.getZ() - goal.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		double dy = Math.abs(a.getY() - goal.getY());
		// Separate tolerances: a target Y is often just a guess, so allow vertical slack.
		return horiz <= Math.max(0.9, reach) && dy <= Math.max(1.5, reach);
	}

	private static double heuristic(BlockPos a, BlockPos b) {
		double dx = a.getX() - b.getX();
		double dy = a.getY() - b.getY();
		double dz = a.getZ() - b.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	private static List<BlockPos> reconstruct(Node end) {
		List<BlockPos> path = new ArrayList<>();
		for (Node n = end; n != null && n.parent != null; n = n.parent) {
			path.add(n.pos);
		}
		Collections.reverse(path);
		return path;
	}

	private static final class Node implements Comparable<Node> {
		final BlockPos pos;
		final double g;
		final double f;
		final Node parent;

		Node(BlockPos pos, double g, double h, Node parent) {
			this.pos = pos;
			this.g = g;
			this.f = g + h;
			this.parent = parent;
		}

		@Override
		public int compareTo(Node o) {
			return Double.compare(this.f, o.f);
		}
	}
}
