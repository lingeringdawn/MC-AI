package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * Walk to within {@code reachRadius} of a target block using A* + the shared navigation follower.
 * Settles when navigation reports a terminal state (reached / stuck / timeout) or the task expires.
 */
public final class MoveToTask extends ClientTask {
	private static final int NODE_BUDGET = 12000;

	private final BlockPos goal;
	private final double reachRadius;
	private final boolean sprint;
	private boolean started;
	private boolean pathFailed;

	public MoveToTask(BlockPos goal, double reachRadius, boolean sprint) {
		this.goal = goal;
		this.reachRadius = reachRadius;
		this.sprint = sprint;
	}

	@Override
	public void onStart(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			pathFailed = true;
			return;
		}
		AStarPathfinder pf = new AStarPathfinder(level, NODE_BUDGET);
		List<BlockPos> path = pf.findPath(p.blockPosition(), goal, reachRadius);
		if (path == null || path.isEmpty()) {
			pathFailed = true;
			return;
		}
		long deadline = System.currentTimeMillis() + Math.max(1000L, remainingMs());
		BotController.get().startNavigation(path, goal, reachRadius, sprint, deadline);
		started = true;
	}

	@Override
	public void tick(Minecraft mc) {
		if (pathFailed) {
			failed("no_path");
			return;
		}
		if (!started) {
			failed("not_started");
			return;
		}
		JsonObject s = BotController.get().statusJson();
		boolean active = s.has("active") && s.get("active").getAsBoolean();
		if (!active) {
			String state = s.has("state") ? s.get("state").getAsString() : "unknown";
			JsonObject extra = new JsonObject();
			extra.addProperty("navState", state);
			done(state, extra);
			return;
		}
		if (expired()) {
			BotController.get().stopNavigation("task_timeout");
			failed("timeout");
		}
	}

	@Override
	public void onCancel(Minecraft mc) {
		BotController.get().stopNavigation("cancelled");
	}
}
