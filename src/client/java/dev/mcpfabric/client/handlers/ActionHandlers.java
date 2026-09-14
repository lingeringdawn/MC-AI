package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.tasks.AnomalyResponder;
import dev.mcpfabric.client.tasks.ApproachTask;
import dev.mcpfabric.client.tasks.AttackTask;
import dev.mcpfabric.client.tasks.ClientTask;
import dev.mcpfabric.client.tasks.SwingTask;
import dev.mcpfabric.client.tasks.CollectItemsTask;
import dev.mcpfabric.client.tasks.CraftTask;
import dev.mcpfabric.client.tasks.EatTask;
import dev.mcpfabric.client.tasks.MineBlockTask;
import dev.mcpfabric.client.tasks.MineVeinTask;
import dev.mcpfabric.client.tasks.MlgTask;
import dev.mcpfabric.client.tasks.MoveToTask;
import dev.mcpfabric.client.tasks.RetreatTask;
import dev.mcpfabric.client.tasks.SurfaceTask;
import dev.mcpfabric.client.tasks.TaskManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.UUID;

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

		router.register("action.eat", ctx -> run(ctx, ctx2 -> new EatTask(), ctx.optInt("timeoutSeconds", 20)));

		// Combat is a composition of short steps, so the default budget stays small: an attack call
		// closes the gap and trades a few blows, then returns. The caller composes the next call.
		router.register("action.attack", ctx -> run(ctx, ctx2 -> new AttackTask(
				uuid(ctx2, "uuid"), Math.max(0, ctx2.optInt("maxSwings", 0))),
				ctx.optInt("timeoutSeconds", 10)));

		router.register("action.approach", ctx -> run(ctx, ctx2 -> new ApproachTask(
				uuid(ctx2, "uuid"), Math.max(0.5, ctx2.optDouble("reach", 2.5))),
				ctx.optInt("timeoutSeconds", 10)));

		router.register("action.swing", ctx -> run(ctx, ctx2 -> new SwingTask(
				uuid(ctx2, "uuid"), Math.max(0, ctx2.optInt("swings", 1))),
				ctx.optInt("timeoutSeconds", 10)));

		// Withdraw from something. Nothing calls this on the bot's own initiative: the observation feed
		// reports health and nearby hostiles, and the caller decides whether to back off.
		router.register("action.retreat", ctx -> {
			double distance = Math.max(1.5, Math.min(64.0, ctx.optDouble("distance", 6.0)));
			RetreatTask task;
			if (ctx.has("uuid")) {
				task = new RetreatTask(uuid(ctx, "uuid"), distance);
			} else if (ctx.has("x") && ctx.has("y") && ctx.has("z")) {
				task = new RetreatTask(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"), distance);
			} else {
				throw RpcException.badRequest("Give either 'uuid' (back away from an entity) "
						+ "or 'x'/'y'/'z' (back away from a coordinate).");
			}
			return run(ctx, ctx2 -> task, ctx.optInt("timeoutSeconds", 20));
		});

		// Swim up for air. There is no drowning reflex any more: the caller watches the air bar in the
		// observation feed and asks for this when it wants the head above water.
		router.register("action.surface", ctx -> run(ctx, ctx2 -> new SurfaceTask(),
				ctx.optInt("timeoutSeconds", 20)));

		// Place water under yourself mid-fall and take it back. Explicit for the same reason: whether a
		// drop is worth saving is a decision, not a reflex.
		router.register("action.mlg", ctx -> run(ctx, ctx2 -> new MlgTask(),
				ctx.optInt("timeoutSeconds", 20)));

		router.register("action.craft", ActionHandlers::craftAction);

		router.register("action.status", ctx -> TaskManager.get().status());

		router.register("action.observe", ctx -> TaskManager.get().observe());

		// React to whatever the observation says is wrong, in one call. The remedy is the one the
		// observer already computed (tool + arguments + the offending entity), and it goes out after a
		// human reaction time rather than on the exact tick the state changed — which is the difference
		// between looking like you noticed and looking like a script.
		router.register("action.react", ctx -> ClientMc.call(() -> {
			requireControl();
			JsonObject out = AnomalyResponder.get().requestReaction(ClientMc.mc());
			if (out == null) {
				throw RpcException.unavailable("Nothing to react to: the observation recommends no remedy "
						+ "right now. Call action.observe for the full picture, or action.status.");
			}
			return out;
		}));

		router.register("action.cancel", ctx -> ClientMc.call(() -> {
			TaskManager.get().cancel(ClientMc.mc());
			return Json.ok("cancelled");
		}));
	}

	private interface TaskFactory {
		ClientTask create(RpcContext ctx) throws RpcException;
	}

	/**
	 * Real crafting as a blocking action: opens the container screen and clicks the grid one slot per
	 * tick, so the whole sequence is visible in-game instead of happening invisibly. {@code grid} is
	 * row-major — 4 entries for the player's 2x2 inventory grid, 9 for an open crafting table.
	 */
	static JsonObject craftAction(RpcContext ctx) throws RpcException {
		Item[] wanted = parseGrid(ctx);
		int count = Math.max(1, Math.min(64, ctx.optInt("count", 1)));
		return run(ctx, ctx2 -> new CraftTask(wanted, count), ctx.optInt("timeoutSeconds", 60));
	}

	/** Parse the row-major 'grid' array into one item per cell ({@code null} = empty cell). */
	private static Item[] parseGrid(RpcContext ctx) throws RpcException {
		if (!ctx.has("grid") || !ctx.params().get("grid").isJsonArray()) {
			throw RpcException.badRequest("Missing 'grid' array.");
		}
		JsonArray arr = ctx.params().getAsJsonArray("grid");
		if (arr.size() != 4 && arr.size() != 9) {
			throw RpcException.badRequest("'grid' needs 4 entries (player 2x2) or 9 (crafting table 3x3); got "
					+ arr.size() + ".");
		}
		Item[] wanted = new Item[arr.size()];
		boolean any = false;
		for (int i = 0; i < arr.size(); i++) {
			JsonElement e = arr.get(i);
			if (e == null || e.isJsonNull()) continue;
			String id = e.getAsString();
			Item item = InventoryHandlers.itemById(id);
			if (item == null) throw RpcException.badRequest("Unknown item id: " + id);
			wanted[i] = item;
			any = true;
		}
		if (!any) throw RpcException.badRequest("'grid' contains no items.");
		return wanted;
	}

	private static JsonObject run(RpcContext ctx, TaskFactory factory, int timeoutSeconds) throws RpcException {
		requireControl();
		int secs = Math.max(1, Math.min(300, timeoutSeconds));
		ClientTask task = factory.create(ctx);
		task.setDeadline(secs * 1000L);
		ClientMc.call(() -> {
			if (!TaskManager.get().submit(task)) {
				throw RpcException.unavailable("Another action is already running. This is a short-step API: omit "
						+ "'waitSeconds' so each call returns once its step has settled, then compose the next step. "
						+ "Or poll action_status until idle, or call action.cancel first.");
			}
			return Json.ok("started");
		});
		// A caller can cap how long this HTTP call blocks and then keep polling. That keeps a long
		// action from being cut off by an MCP client's request timeout while still letting the model
		// watch the world tick via the live snapshot in the returned payload.
		long waitMs = ctx.has("waitSeconds")
				? Math.max(0L, ctx.optInt("waitSeconds", 0)) * 1000L
				: secs * 1000L + 2000L;
		return TaskManager.get().await(waitMs);
	}

	private static UUID uuid(RpcContext ctx, String key) throws RpcException {
		try {
			return UUID.fromString(ctx.getString(key));
		} catch (IllegalArgumentException e) {
			throw RpcException.badRequest("Invalid UUID: " + ctx.optString(key, ""));
		}
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
