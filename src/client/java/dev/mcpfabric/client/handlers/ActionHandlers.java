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
import dev.mcpfabric.client.Targets;
import dev.mcpfabric.client.tasks.AnomalyResponder;
import dev.mcpfabric.client.tasks.ClientTask;
import dev.mcpfabric.client.tasks.CraftTask;
import dev.mcpfabric.client.tasks.EatTask;
import dev.mcpfabric.client.tasks.MineBlockTask;
import dev.mcpfabric.client.tasks.MlgTask;
import dev.mcpfabric.client.tasks.MoveToTask;
import dev.mcpfabric.client.tasks.PlanTask;
import dev.mcpfabric.client.tasks.RetreatTask;
import dev.mcpfabric.client.tasks.SurfaceTask;
import dev.mcpfabric.client.tasks.SwingTask;
import dev.mcpfabric.client.tasks.TaskManager;
import dev.mcpfabric.client.tasks.TaskModules;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.UUID;

/**
 * The slow, goal-oriented actions: each drives a whole multi-tick behaviour and returns only when it
 * settles — "walk there", "mine that block", "hit that mob".
 *
 * <p>Every module is declared once, here, and becomes two things at the same time: a method you can
 * call on its own ({@code action.moveTo}) and a step an {@code action.do} plan can contain
 * ({@link PlanTask}). Same parameters, same code — so a plan is exactly the list of calls you would
 * otherwise make one at a time, minus the round-trips between them.
 */
public final class ActionHandlers {
	private ActionHandlers() {}

	public static void register(RpcRouter router) {
		TaskModules.register("moveTo", ActionHandlers::moveTo, 30);
		TaskModules.register("mineBlock", ActionHandlers::mineBlock, 30);
		TaskModules.register("swing", ActionHandlers::swing, 10);
		TaskModules.register("eat", p -> new EatTask(), 20);
		TaskModules.register("retreat", ActionHandlers::retreat, 20);
		TaskModules.register("surface", p -> new SurfaceTask(), 20);
		TaskModules.register("mlg", p -> new MlgTask(), 20);
		TaskModules.register("craft", ActionHandlers::craft, 60);

		for (TaskModules.Module module : TaskModules.all().values()) {
			router.register("action." + module.name(), ctx -> run(ctx,
					ctx2 -> module.factory().create(ctx2.params()),
					ctx.optInt("timeoutSeconds", module.defaultTimeoutSeconds())));
		}

		registerPlan(router);

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

	// --- the plan ---------------------------------------------------------------------------------

	/** Run a caller-composed list of steps in one call. The steps are the caller's; so are the guards. */
	private static void registerPlan(RpcRouter router) {
		router.register("action.do", ctx -> run(ctx,
				ctx2 -> plan(router, ctx2.params()),
				ctx.optInt("timeoutSeconds", 120)));
	}

	static ClientTask plan(RpcRouter router, JsonObject p) throws RpcException {
		if (!p.has("steps") || !p.get("steps").isJsonArray()) {
			throw RpcException.badRequest("Missing 'steps': give an array of steps, e.g. "
					+ "[{\"action\":\"moveTo\",\"target\":\"nearest_drop\"},{\"rpc\":\"inventory.selectHotbar\",\"slot\":0}].");
		}
		String onFailure = optString(p, "onFailure", "stop");
		if (!"stop".equals(onFailure) && !"continue".equals(onFailure)) {
			throw RpcException.badRequest("'onFailure' is 'stop' (default) or 'continue'; got '" + onFailure + "'.");
		}
		JsonObject guard = p.has("guard") && p.get("guard").isJsonObject() ? p.getAsJsonObject("guard") : new JsonObject();
		return new PlanTask(router, p.getAsJsonArray("steps"), "continue".equals(onFailure),
				optDouble(guard, "abortIfHealthBelow", 0.0),
				optInt(guard, "abortIfAirBelow", 0),
				optBool(guard, "abortIfDead", false));
	}

	// --- modules ----------------------------------------------------------------------------------

	static ClientTask moveTo(JsonObject p) throws RpcException {
		return new MoveToTask(goal(p, "moveTo"),
				optDouble(p, "reachRadius", 1.0),
				optBool(p, "sprint", false));
	}

	static ClientTask mineBlock(JsonObject p) throws RpcException {
		return new MineBlockTask(goal(p, "mineBlock"));
	}

	/** Hit an entity. One module: closing the distance is a moveTo, keeping at it is another swing. */
	static ClientTask swing(JsonObject p) throws RpcException {
		return new SwingTask(uuidOf(p, "swing"), Math.max(0, optInt(p, "hits", 1)));
	}

	/** Withdraw from an entity or a place. Nothing does this on the bot's own initiative. */
	static ClientTask retreat(JsonObject p) throws RpcException {
		double distance = Math.max(1.5, Math.min(64.0, optDouble(p, "distance", 6.0)));
		if (p.has("uuid") || p.has("target")) return new RetreatTask(uuidOf(p, "retreat"), distance);
		if (p.has("x") && p.has("y") && p.has("z")) {
			return new RetreatTask(num(p, "x"), num(p, "y"), num(p, "z"), distance);
		}
		throw RpcException.badRequest("Give 'uuid' or 'target' (back away from an entity), or 'x'/'y'/'z' "
				+ "(back away from a coordinate).");
	}

	/**
	 * Real crafting as a blocking action: opens the container screen and clicks the grid one slot per
	 * tick, so the whole sequence is visible in-game instead of happening invisibly. {@code grid} is
	 * row-major — 4 entries for the player's 2x2 inventory grid, 9 for an open crafting table.
	 */
	static ClientTask craft(JsonObject p) throws RpcException {
		return new CraftTask(parseGrid(p), Math.max(1, Math.min(64, optInt(p, "count", 1))));
	}

	// --- parameter helpers ------------------------------------------------------------------------

	/** Crafting under its older name: the same task and the same parameters as {@code action.craft}. */
	public static JsonObject craftAlias(RpcContext ctx) throws RpcException {
		return run(ctx, ctx2 -> craft(ctx2.params()), ctx.optInt("timeoutSeconds", 60));
	}

	/**
	 * Where a step aims: explicit x/y/z, or the caller's shorthand for "that thing over there"
	 * ({@link Targets}). A shorthand is resolved when the step is built, so inside a plan it names the
	 * drop that exists <em>after</em> the steps before it ran.
	 */
	private static BlockPos goal(JsonObject p, String what) throws RpcException {
		if (p.has("target")) {
			return Targets.block(optString(p, "target", ""), "'" + what + "'");
		}
		if (p.has("x") && p.has("y") && p.has("z")) {
			return BlockPos.containing(num(p, "x"), num(p, "y"), num(p, "z"));
		}
		throw RpcException.badRequest("Give 'x'/'y'/'z' (a block position), or 'target' = " + Targets.SPECS + ".");
	}

	/** An entity to act on: an explicit UUID, or a shorthand the caller would rather say. */
	private static UUID uuidOf(JsonObject p, String what) throws RpcException {
		if (p.has("target")) return Targets.entity(optString(p, "target", ""), "'" + what + "'");
		if (p.has("uuid")) {
			String raw = optString(p, "uuid", "");
			try {
				return UUID.fromString(raw);
			} catch (IllegalArgumentException e) {
				throw RpcException.badRequest("Invalid UUID: " + raw);
			}
		}
		throw RpcException.badRequest("Give 'uuid' (an entity id), or 'target' = " + Targets.SPECS + ".");
	}

	/** Parse the row-major 'grid' array into one item per cell ({@code null} = empty cell). */
	private static Item[] parseGrid(JsonObject p) throws RpcException {
		if (!p.has("grid") || !p.get("grid").isJsonArray()) {
			throw RpcException.badRequest("Missing 'grid' array.");
		}
		JsonArray arr = p.getAsJsonArray("grid");
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

	private static double num(JsonObject p, String key) throws RpcException {
		if (!p.has(key) || !p.get(key).isJsonPrimitive()) {
			throw RpcException.badRequest("Missing required number '" + key + "'.");
		}
		return p.get(key).getAsDouble();
	}

	private static double optDouble(JsonObject p, String key, double fallback) {
		return p.has(key) && p.get(key).isJsonPrimitive() ? p.get(key).getAsDouble() : fallback;
	}

	private static int optInt(JsonObject p, String key, int fallback) {
		return p.has(key) && p.get(key).isJsonPrimitive() ? p.get(key).getAsInt() : fallback;
	}

	private static boolean optBool(JsonObject p, String key, boolean fallback) {
		return p.has(key) && p.get(key).isJsonPrimitive() ? p.get(key).getAsBoolean() : fallback;
	}

	private static String optString(JsonObject p, String key, String fallback) {
		return p.has(key) && p.get(key).isJsonPrimitive() ? p.get(key).getAsString() : fallback;
	}

	// --- plumbing ---------------------------------------------------------------------------------

	private interface TaskFactory {
		ClientTask create(RpcContext ctx) throws RpcException;
	}

	/**
	 * Start a module and hand control straight back — nothing here waits for it to settle.
	 *
	 * <p>A blocking call would take away the caller's only lever for as long as the action lasts, which
	 * is exactly when it might want to use it: a step that turns out to be wrong, a mob appearing, a
	 * cliff. Instead every action is a request the caller can watch (action.status / observe) and
	 * overrule the moment it likes — another action supersedes this one on the next tick, and
	 * action.cancel stops it outright.
	 */
	private static JsonObject run(RpcContext ctx, TaskFactory factory, int timeoutSeconds) throws RpcException {
		requireControl();
		int secs = Math.max(1, Math.min(600, timeoutSeconds));
		ClientTask task = factory.create(ctx);
		task.setDeadline(secs * 1000L);
		return ClientMc.call(() -> TaskManager.get().submit(task));
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}
}
