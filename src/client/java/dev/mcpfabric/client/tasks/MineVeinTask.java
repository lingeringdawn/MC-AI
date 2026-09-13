package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mine a connected cluster of same-id blocks: a whole tree, an ore vein, a column of logs. Walks
 * between blocks as needed and follows the cluster to exhaustion (bounded by {@code max}). Once the
 * cluster is gone it walks over the dropped items to collect them, so one call is the complete human
 * intent "chop that tree down and pick up the wood".
 */
public final class MineVeinTask extends ClientTask {
	private static final int[][] NEIGHBOURS = {
			{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
	};
	private static final int NODE_BUDGET = 12000;
	private static final double COLLECT_RADIUS = 12.0;

	private final Deque<BlockPos> queue = new ArrayDeque<>();
	private final Set<Long> seen = new HashSet<>();
	private final BlockPos seed;
	private final int max;
	private BlockMiner miner;
	private BlockPos current;
	private String targetId = "";
	private int mined;
	private int remaining;
	private boolean collecting;
	private long collectStartMs;

	public MineVeinTask(BlockPos origin, int max) {
		this.seed = origin;
		this.max = max;
		enqueue(origin);
	}

	@Override
	public void onStart(Minecraft mc) {
		ClientLevel level = mc.level;
		if (level != null) targetId = idOf(level, seed);
	}

	@Override
	public void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		LocalPlayer p = mc.player;
		if (level == null || p == null) {
			failed("no_world");
			return;
		}
		if (expired()) {
			if (miner != null) miner.cancel();
			finish("timeout");
			return;
		}

		if (!collecting) {
			if (miner != null) {
				BlockMiner.State s = miner.tick(mc);
				if (s == BlockMiner.State.DONE) {
					mined++;
					enqueueNeighbours(level, current);
					miner = null;
					current = null;
				} else if (s == BlockMiner.State.UNREACHABLE) {
					miner = null;
					current = null;
				} else {
					return;
				}
			}
			while (mined < max) {
				BlockPos next = queue.poll();
				if (next == null) break;
				if (level.getBlockState(next).isAir()) continue;
				if (!idOf(level, next).equals(targetId)) continue;
				BlockMiner candidate = new BlockMiner(next);
				if (!candidate.begin(mc)) continue;
				miner = candidate;
				current = next;
				return;
			}
			// Cluster exhausted (or max reached) — switch to collecting the drops.
			collecting = true;
			collectStartMs = System.currentTimeMillis();
			remaining = countRemaining(level);
		}

		ItemEntity drop = nearestDrop(p, level);
		if (drop == null) {
			// Give freshly-broken blocks a moment to spawn their drops as entities.
			if (mined > 0 && System.currentTimeMillis() - collectStartMs < 2500) return;
			finish(remaining > 0 ? "partial" : "done");
			return;
		}
		if (p.position().distanceTo(drop.position()) <= 1.2) return; // let the vanilla pickup resolve
		if (!isNavigating()) {
			BlockPos goal = drop.blockPosition();
			List<BlockPos> path = new AStarPathfinder(level, NODE_BUDGET).findPath(p.blockPosition(), goal, 1.0);
			if (path == null || path.isEmpty()) {
				finish(remaining > 0 ? "partial" : "done");
				return;
			}
			BotController.get().startNavigation(path, goal, 1.0, false,
					System.currentTimeMillis() + Math.max(1000L, remainingMs()));
		}
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.addProperty("block", targetId);
		o.addProperty("mined", mined);
		o.addProperty("max", max);
		o.addProperty("queued", queue.size());
		o.addProperty("remaining", remaining);
		o.addProperty("phase", collecting ? "collecting" : "mining");
		if (current != null) {
			JsonObject c = new JsonObject();
			c.addProperty("x", current.getX());
			c.addProperty("y", current.getY());
			c.addProperty("z", current.getZ());
			o.add("current", c);
		}
		return o;
	}

	@Override
	public String describe() {
		return "mine " + (targetId.isEmpty() ? "vein" : targetId) + " at " + seed.getX() + "," + seed.getY()
				+ "," + seed.getZ() + " (max " + max + ")";
	}

	@Override
	public void onCancel(Minecraft mc) {
		if (miner != null) miner.cancel();
		BotController.get().stopNavigation("cancelled");
	}

	private void finish(String state) {
		JsonObject extra = new JsonObject();
		extra.addProperty("mined", mined);
		extra.addProperty("remaining", remaining);
		extra.addProperty("block", targetId);
		done(state, extra);
	}

	private void enqueue(BlockPos pos) {
		if (seen.add(pos.asLong())) queue.add(pos.immutable());
	}

	private void enqueueNeighbours(ClientLevel level, BlockPos pos) {
		if (pos == null) return;
		for (int[] d : NEIGHBOURS) {
			BlockPos n = pos.offset(d[0], d[1], d[2]);
			if (seen.contains(n.asLong())) continue;
			if (idOf(level, n).equals(targetId)) enqueue(n);
		}
	}

	/** How many of the discovered cluster blocks are still present (skipped / unreachable ones count). */
	private int countRemaining(ClientLevel level) {
		int n = 0;
		for (long key : seen) {
			BlockPos pos = BlockPos.of(key);
			if (!level.getBlockState(pos).isAir() && idOf(level, pos).equals(targetId)) n++;
		}
		return n;
	}

	private ItemEntity nearestDrop(LocalPlayer p, ClientLevel level) {
		ItemEntity best = null;
		double bestD = COLLECT_RADIUS * COLLECT_RADIUS;
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity it) || !it.isAlive()) continue;
			double d = p.position().distanceToSqr(it.position());
			if (d <= bestD) {
				bestD = d;
				best = it;
			}
		}
		return best;
	}

	private static boolean isNavigating() {
		JsonObject s = BotController.get().statusJson();
		return s.has("active") && s.get("active").getAsBoolean();
	}

	private static String idOf(ClientLevel level, BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock()).toString();
	}
}
