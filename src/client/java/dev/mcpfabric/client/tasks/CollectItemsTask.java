package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.List;

/** Walk over nearby dropped items (logs, ore, drops) so the player picks them up. */
public final class CollectItemsTask extends ClientTask {
	private static final int NODE_BUDGET = 12000;

	private final double radius;
	private int picked = 0;
	private boolean navStarted;

	public CollectItemsTask(double radius) {
		this.radius = radius;
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			failed("no_world");
			return;
		}
		if (expired()) {
			finish("timeout");
			return;
		}

		ItemEntity nearest = nearestItem(p, level);
		if (nearest == null) {
			finish("done");
			return;
		}
		// Within pickup range: let the vanilla pickup resolve on a later tick.
		if (p.position().distanceTo(nearest.position()) <= 1.2) return;

		JsonObject s = BotController.get().statusJson();
		boolean active = s.has("active") && s.get("active").getAsBoolean();
		if (!active) {
			BlockPos goal = nearest.blockPosition();
			AStarPathfinder pf = new AStarPathfinder(level, NODE_BUDGET);
			List<BlockPos> path = pf.findPath(p.blockPosition(), goal, 1.0);
			if (path == null || path.isEmpty()) {
				finish("done");
				return;
			}
			BotController.get().startNavigation(path, goal, 1.0, false,
					System.currentTimeMillis() + Math.max(1000L, remainingMs()));
			navStarted = true;
		}
	}

	@Override
	public void onCancel(Minecraft mc) {
		BotController.get().stopNavigation("cancelled");
	}

	private void finish(String state) {
		BotController.get().stopNavigation("done");
		JsonObject extra = new JsonObject();
		extra.addProperty("pickedApprox", picked);
		done(state, extra);
	}

	private ItemEntity nearestItem(LocalPlayer p, ClientLevel level) {
		ItemEntity best = null;
		double bestD = radius * radius;
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
}
