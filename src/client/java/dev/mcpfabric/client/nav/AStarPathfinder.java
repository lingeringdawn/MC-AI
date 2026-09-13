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
 * a one-block step-up (jump), and dropping down up to three blocks. Walkability is derived from
 * block collision shapes, so it works on any {@link BlockGetter} (client or server level).
 */
public final class AStarPathfinder {
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

	private boolean dangerous(BlockPos pos) {
		return DANGEROUS.contains(BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString());
	}

	/**
	 * Can the bot's two-block body occupy this cell? True when standing on ground, treading water, or
	 * swimming — so paths may cross ponds/rivers instead of refusing them. Hazardous cells are never
	 * considered, so routes naturally go around lava/fire/cactus the way a cautious player would.
	 */
	private boolean canOccupy(BlockPos feet) {
		if (!passable(feet) || !passable(feet.above())) return false;
		if (dangerous(feet) || dangerous(feet.above()) || dangerous(feet.below())) return false;
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
				BlockPos next = null;
				double moveCost = 1.0;

				if (canOccupy(h)) {
					next = h;
					if (liquid(h)) moveCost = 1.4; // wading / swimming is slower
				} else if (canOccupy(h.above()) && passable(current.pos.above().above())) {
					next = h.above(); // step / swim up
					moveCost = 1.5;
				} else {
					for (int d = 1; d <= 3; d++) {
						if (canOccupy(h.below(d))) {
							next = h.below(d);
							moveCost = 1.0 + 0.3 * d;
							break;
						}
					}
				}
				if (next == null) continue;

				double tentativeG = current.g + moveCost;
				long key = next.asLong();
				Double prev = best.get(key);
				if (prev != null && tentativeG >= prev) continue;
				best.put(key, tentativeG);
				open.add(new Node(next.immutable(), tentativeG, heuristic(next, goal), current));
			}
		}
		return null;
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
