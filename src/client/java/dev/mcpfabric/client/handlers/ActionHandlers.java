package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.tasks.ClientTask;
import dev.mcpfabric.client.tasks.CollectItemsTask;
import dev.mcpfabric.client.tasks.MineBlockTask;
import dev.mcpfabric.client.tasks.MineVeinTask;
import dev.mcpfabric.client.tasks.MoveToTask;
import dev.mcpfabric.client.tasks.TaskManager;
import net.minecraft.core.BlockPos;

/**
 * Goal-oriented, blocking actions. Unlike the atomic control/interact tools (which return the moment
 * an input is issued), each call here drives a whole multi-tick behaviour to completion and returns
 * only when it settles — "mine that block", "walk there", "grab the drops".
 */
public final class ActionHandlers {
	private ActionHandlers() {}

	public static void register(RpcRouter router) {
		router.register("action.moveTo", ctx -> run(ctx, ctx2 -> new MoveToTask(
				BlockPos.containing(ctx2.getDouble("x"), ctx2.getDouble("y"), ctx2.getDouble("z")),
				ctx2.optDouble("reachRadius", 1.0),
				ctx2.optBool("sprint", false)), ctx.optInt("timeoutSeconds", 30)));

		router.register("action.mineBlock", ctx -> run(ctx, ctx2 -> new MineBlockTask(
				BlockPos.containing(ctx2.getDouble("x"), ctx2.getDouble("y"), ctx2.getDouble("z"))),
				ctx.optInt("timeoutSeconds", 30)));

		router.register("action.mineVein", ctx -> run(ctx, ctx2 -> new MineVeinTask(
				BlockPos.containing(ctx2.getDouble("x"), ctx2.getDouble("y"), ctx2.getDouble("z")),
				Math.max(1, Math.min(512, ctx2.optInt("max", 64)))),
				ctx.optInt("timeoutSeconds", 60)));

		router.register("action.collectItems", ctx -> run(ctx, ctx2 -> new CollectItemsTask(
				ctx2.optDouble("radius", 16.0)), ctx.optInt("timeoutSeconds", 30)));

		router.register("action.status", ctx -> TaskManager.get().status());

		router.register("action.cancel", ctx -> ClientMc.call(() -> {
			TaskManager.get().cancel(ClientMc.mc());
			return Json.ok("cancelled");
		}));
	}

	private interface TaskFactory {
		ClientTask create(RpcContext ctx) throws RpcException;
	}

	private static JsonObject run(RpcContext ctx, TaskFactory factory, int timeoutSeconds) throws RpcException {
		requireControl();
		int secs = Math.max(1, Math.min(300, timeoutSeconds));
		ClientTask task = factory.create(ctx);
		task.setDeadline(secs * 1000L);
		ClientMc.call(() -> {
			if (!TaskManager.get().submit(task)) {
				throw RpcException.unavailable("Another action is already running; call action.cancel first.");
			}
			return Json.ok("started");
		});
		return TaskManager.get().await(secs * 1000L + 2000L);
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
