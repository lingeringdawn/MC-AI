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
import dev.mcpfabric.client.tasks.AttackTask;
import dev.mcpfabric.client.tasks.ClientTask;
import dev.mcpfabric.client.tasks.CraftTask;
import dev.mcpfabric.client.tasks.DigTask;
import dev.mcpfabric.client.tasks.Latency;
import dev.mcpfabric.client.tasks.Memory;
import dev.mcpfabric.client.tasks.MoveToTask;
import dev.mcpfabric.client.tasks.ParkedPlan;
import dev.mcpfabric.client.tasks.PlanTask;
import dev.mcpfabric.client.tasks.Rules;
import dev.mcpfabric.client.tasks.TaskManager;
import dev.mcpfabric.client.tasks.TaskModules;
import dev.mcpfabric.client.tasks.UseForTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
		// The atoms. Each does one physical act and reports how it went; none decides what to do next and
		// none contains a reflex. A reflex is a rule the caller writes (rules.set) and a sequence is a
		// plan the caller writes (action.do) — that division is the whole design.
		TaskModules.register("moveTo", ActionHandlers::moveTo, 30);
		TaskModules.register("dig", ActionHandlers::dig, 30);
		TaskModules.register("attack", ActionHandlers::attack, 10);
		TaskModules.register("useFor", ActionHandlers::useFor, 10);
		TaskModules.register("craft", ActionHandlers::craft, 60);

		for (TaskModules.Module module : TaskModules.all().values()) {
			router.register("action." + module.name(), ctx -> run(ctx,
					ctx2 -> module.factory().create(ctx2.params()),
					ctx.optInt("timeoutSeconds", module.defaultTimeoutSeconds())));
		}

		registerPlan(router);
		registerParkedPlan(router);
		registerMemory(router);

		// One hook on the dispatcher times how long the caller takes to answer what it was shown. No
		// per-call instrumentation, and nothing in it decides anything.
		router.onDispatch(Latency::called);

		// The caller's own reflexes. The mod supplies the loop and nothing else: the conditions and the
		// actions are the caller's sentences, replaced wholesale by the next rules.set, and every firing
		// is reported in the observation stream.
		Rules.get().bind(router);
		router.register("rules.set", ctx -> Rules.get().set(ctx.params()));
		router.register("rules.clear", ctx -> Rules.get().clear());
		router.register("rules.list", ctx -> Rules.get().list());

		// One call that answers "what can I do, with what, and what will it say back" — so understanding
		// the module surface is a read, not an inference from a dozen tool descriptions.
		router.register("action.help", ctx -> help());

		router.register("action.status", ctx -> TaskManager.get().status());

		// The watch as a stream rather than a snapshot: hand back the 'seq' from the last read and get
		// everything that changed since. Nothing has to be caught at the one moment it was true.
		router.register("action.observe", ctx -> {
			JsonObject p = ctx.params();
			long since = p.has("sinceSeq") && p.get("sinceSeq").isJsonPrimitive() ? p.get("sinceSeq").getAsLong() : 0L;
			return TaskManager.get().observe(since);
		});

		// Parallel input/output: several calls in one round trip, dispatched at the same time on their
		// own threads. Reading the world while an action runs should not cost a trip per read, and none
		// of it needs to wait its turn behind the others.
		router.register("rpc.batch", ctx -> batch(router, ctx));

		router.register("action.cancel", ctx -> ClientMc.call(() -> {
			TaskManager.get().cancel(ClientMc.mc());
			JsonObject o = Json.ok("cancelled");
			// A cancelled plan parks itself on the way out, so say so: otherwise nothing tells the caller
			// the flow it just stopped is still there to carry on from.
			JsonObject parked = ParkedPlan.status();
			if (parked.has("parked") && parked.get("parked").getAsBoolean()) {
				o.addProperty("parked", parked.get("describe").getAsString());
				o.addProperty("resumeNote", "It is parked at the step it had reached: plan_status for where, "
						+ "plan_resume to carry on, plan_discard to throw it away.");
			}
			return o;
		}));
	}

	// --- the plan ---------------------------------------------------------------------------------

	/** Run a caller-composed list of steps in one call. The steps are the caller's; so are the guards. */
	private static void registerPlan(RpcRouter router) {
		router.register("action.do", ctx -> run(ctx,
				ctx2 -> plan(router, ctx2.params()),
				ctx.optInt("timeoutSeconds", 120)));
	}

	/**
	 * What the caller has learned, and how fast it has been reacting.
	 *
	 * <p>Memory is the only thing in this mod that survives a restart. It exists because everything else
	 * forgets: the same pond drowned the bot twice, the same hidden trunk took three attempts to see, and
	 * the same sequence was rewritten every session, because there was nowhere for a lesson to live. The
	 * caller writes the content and does the deciding — nothing here summarises, scores or auto-writes.
	 *
	 * <p>Latency is the matching measurement: how long between something being reported and the first
	 * action taken about it. A claim that behaviour is getting smoother is not evidence; this is.
	 */
	private static void registerMemory(RpcRouter router) {
		router.register("memory.set", ctx -> Memory.set(ctx.params()));
		router.register("memory.get", ctx -> Memory.get(param(ctx, "key")));
		router.register("memory.list", ctx -> Memory.list(param(ctx, "kind"), param(ctx, "filter")));
		router.register("memory.delete", ctx -> Memory.delete(param(ctx, "key")));
		router.register("memory.clear", ctx -> Memory.clear());
		router.register("latency.stats", ctx -> Latency.stats());
	}

	private static String param(RpcContext ctx, String key) {
		JsonObject p = ctx.params();
		return p.has(key) && p.get(key).isJsonPrimitive() ? p.get(key).getAsString() : null;
	}

	/**
	 * The parked plan: see where it stopped, carry on from there, or throw it away.
	 *
	 * <p>Being taken over used to end a plan, which made a mid-flow correction expensive — the caller had
	 * to re-issue every step from the top, in a world that had moved on. Now the remainder is parked, and
	 * these three calls are the whole interface to it. Nothing resumes a plan on its own: whether to carry
	 * on is exactly the kind of decision that belongs to the caller.
	 */
	private static void registerParkedPlan(RpcRouter router) {
		router.register("plan.status", ctx -> ClientMc.call(() -> {
			JsonObject o = ParkedPlan.status();
			JsonObject running = new JsonObject();
			ClientTask t = TaskManager.get().current();
			boolean active = TaskManager.get().busy() && t != null;
			running.addProperty("active", active);
			if (active) {
				running.addProperty("task", t.describe());
				JsonObject progress = t.progress();
				if (progress != null && progress.size() > 0) running.add("progress", progress);
			}
			o.add("running", running);
			return o;
		}));

		router.register("plan.resume", ctx -> ClientMc.call(() -> {
			requireControl();
			JsonObject snap = ParkedPlan.get();
			if (snap == null) {
				throw RpcException.unavailable("Nothing is parked. A plan parks itself when another "
						+ "instruction takes over while it runs, or when it is cancelled.");
			}
			int from = snap.has("index") ? snap.get("index").getAsInt() : 0;
			PlanTask plan = new PlanTask(router, snap.getAsJsonArray("steps"),
					snap.has("continueOnFailure") && snap.get("continueOnFailure").getAsBoolean(),
					snap.has("abortIfHealthBelow") ? snap.get("abortIfHealthBelow").getAsDouble() : 0.0,
					snap.has("abortIfAirBelow") ? snap.get("abortIfAirBelow").getAsInt() : 0,
					snap.has("abortIfDead") && snap.get("abortIfDead").getAsBoolean(),
					from, snap.has("log") ? snap.getAsJsonArray("log") : null);
			int secs = Math.max(1, Math.min(600, ctx.optInt("timeoutSeconds", 120)));
			plan.setDeadline(secs * 1000L);
			// Clear before submitting: if this resume takes over something that is running, that task parks
			// itself on the way out and must find the slot free, not be wiped by the one it replaced.
			ParkedPlan.clear();
			JsonObject o = TaskManager.get().submit(plan);
			o.addProperty("resumedFrom", from);
			o.addProperty("resumedSteps", plan.describe());
			return o;
		}));

		router.register("plan.discard", ctx -> ParkedPlan.clear());
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
		JsonArray steps = p.getAsJsonArray("steps");
		if (steps.size() > MAX_PLAN_STEPS) {
			throw RpcException.badRequest("A plan takes at most " + MAX_PLAN_STEPS + " steps. Long plans are the "
					+ "thing to avoid here: the world moves while a plan runs, and every step after the first is "
					+ "written before the first one's outcome is known. Send a short plan, read what came back "
					+ "(action.status / observe), then send the next one.");
		}
		JsonObject guard = p.has("guard") && p.get("guard").isJsonObject() ? p.getAsJsonObject("guard") : new JsonObject();
		return new PlanTask(router, steps, "continue".equals(onFailure),
				optDouble(guard, "abortIfHealthBelow", 0.0),
				optInt(guard, "abortIfAirBelow", 0),
				optBool(guard, "abortIfDead", false));
	}

	// --- the module surface, in one call ----------------------------------------------------------

	/**
	 * The whole module surface: what each one does, what it takes, and what it says back.
	 *
	 * <p>Declared here, next to the modules themselves, so the description cannot drift away from the
	 * code it describes. Reading this is meant to replace inferring the API from a pile of tool
	 * descriptions: parameters, return states, the target vocabulary, the fields a scan reports and the
	 * conditions a rule can use, all in one payload.
	 */
	private static JsonObject help() {
		JsonObject o = new JsonObject();
		JsonObject mods = new JsonObject();
		mods.add("moveTo", module("Walk to a position and stop there. The only module with a goal, because "
						+ "walking somewhere is one physical act.",
				"x,y,z or target", "reachRadius=1, sprint=false, timeoutSeconds=30",
				"done/reached, done/no_path, done/stuck, failed/timeout"));
		mods.add("dig", module("Break the one block named, from where the player already stands. Holds the "
						+ "best hotbar tool for it and keeps the crosshair on it. Never walks, never clears a "
						+ "block out of the way.",
				"x,y,z or target", "timeoutSeconds=30",
				"done/mined, failed/out_of_reach, failed/blocked (+blockedBy/blockedAt), failed/interrupted, failed/timeout"));
		mods.add("attack", module("Land one hit: aim, wait for the attack bar to be full, swing, wait for it "
						+ "to refill. Never walks or strafes.",
				"uuid or target", "timeoutSeconds=10",
				"done/hit (+damageDealt), done/killed, done/out_of_reach, done/cooldown, failed/target_gone, failed/not_found"));
		mods.add("useFor", module("Hold the use button for N ticks — eating, drinking, drawing, guarding.",
				"ticks=35", "timeoutSeconds=10", "done/used (+holdingBefore/After, consumed), failed/timeout"));
		mods.add("craft", module("Lay out a craft in the player's 2x2 grid (4 cells) or an open table "
						+ "(9 cells), one click per tick. Places the ingredients and stops: taking the product "
						+ "is ui.clickSlot on the output slot.",
				"grid[] of 4 or 9 item ids (null = empty cell), count=1", "timeoutSeconds=60",
				"done/crafted, failed/no_recipe"));
		o.add("modules", mods);
		o.addProperty("targets", Targets.SPECS);
		o.addProperty("ruleConditions", Rules.CONDITIONS);
		o.addProperty("visionFields", "id, pos, distance, inReach, wet, hardness, transparent, through[] "
				+ "(vision.scan). Rays pass through blocks that do not occlude — leaves, glass, water, vines, "
				+ "plants — so a trunk under its own canopy comes back as a sighting with the leaves listed "
				+ "in 'through', and the leaves themselves come back with transparent:true.");
		o.addProperty("watchStream", "action.observe {sinceSeq} returns newEvents since that seq; "
				+ "now.visible is what the eye can see on this tick.");
		o.addProperty("planLimit", MAX_PLAN_STEPS);
		o.addProperty("planHandle", "A plan that is taken over or cancelled is PARKED, not lost: plan.status "
				+ "shows where it stopped (its next step), plan.resume carries on from that step, plan.discard "
				+ "throws it away. The interrupted step is redone from the top — it may have been half "
				+ "finished. Nothing resumes a plan on its own; that decision is yours. One slot only: this is "
				+ "'the plan I was in the middle of', not a queue.");
		o.addProperty("rules", "rules.set replaces the whole set; rules.list shows it; each firing is "
				+ "reported in the stream as an event with kind 'rule'.");
		o.addProperty("memory", "memory.set/get/list/delete/clear — the only thing that survives a restart. "
				+ "The mod stores what you write and forms no opinion of its own: no summary, no scoring, no "
				+ "auto-written lessons (an auto-written lesson would be the mod deciding what matters). Read "
				+ "memory.list FIRST on a new session: whatever was expensive to work out last time is exactly "
				+ "what should not be worked out again. 'writes' counts revisions — a lesson revised five times "
				+ "is still not right. A 'procedure' whose steps pin x/y/z comes back with a warning, because "
				+ "symbolic targets (visible_log, nearest_drop, looking_at) keep it working when the world moves.");
		o.addProperty("fluency", "observe.now.latency, and latency.stats, is the reaction reading: 'incident' "
				+ "is the time from an anomaly being reported to the first action submitted afterwards, 'loop' "
				+ "is from a read to the next action, both as medians over the last 20. Reading is not "
				+ "answering — action.status and action.observe are reads, and while one is open the snapshot "
				+ "shows openForMs. This is the number that says whether operating is getting more fluid.");
		o.addProperty("noPolicy", "None of these modules decides anything. Each does the one physical thing "
				+ "it is named for, reports what happened and stops. There is no 'chop a tree', no 'kill that "
				+ "mob', no refusal and no preference — the composing is yours, and the only standing "
				+ "instructions are the rules you wrote.");
		return o;
	}

	private static JsonObject module(String what, String params, String optional, String returns) {
		JsonObject o = new JsonObject();
		o.addProperty("does", what);
		o.addProperty("params", params);
		if (!"—".equals(optional)) o.addProperty("optional", optional);
		o.addProperty("returns", returns);
		return o;
	}

	// --- parallel I/O -----------------------------------------------------------------------------

	/** Steps a single plan may contain. Short enough that the caller can still steer the outcome. */
	private static final int MAX_PLAN_STEPS = 8;

	/** Dispatch pool for {@code rpc.batch}; small, because each call is mostly waiting on the game thread. */
	private static final Executor BATCH_POOL = Executors.newFixedThreadPool(4, r -> {
		Thread t = new Thread(r, "mcpfabric-batch");
		t.setDaemon(true);
		return t;
	});

	/**
	 * Run several calls at once and return all of their results, in order.
	 *
	 * <p>The HTTP bridge already serves requests on a thread pool, so this exists for the other half of
	 * the problem: a caller that wants to read three things, or read while acting, should not have to
	 * make three round trips and wait for each. The calls here run concurrently — a slow one does not
	 * hold up a fast one — and each result is the full envelope it would have had on its own, so a
	 * failure in one is visible as that one failing rather than as the batch failing.
	 */
	private static JsonObject batch(RpcRouter router, RpcContext ctx) throws RpcException {
		JsonObject p = ctx.params();
		if (!p.has("calls") || !p.get("calls").isJsonArray()) {
			throw RpcException.badRequest("Missing 'calls': give an array of {\"method\":\"...\",\"params\":{...}}.");
		}
		JsonArray calls = p.getAsJsonArray("calls");
		if (calls.isEmpty() || calls.size() > 24) {
			throw RpcException.badRequest("'calls' takes 1..24 entries; got " + calls.size() + ".");
		}
		JsonObject[] results = new JsonObject[calls.size()];
		List<CompletableFuture<Void>> pending = new ArrayList<>();
		for (int i = 0; i < calls.size(); i++) {
			final int idx = i;
			JsonObject call = calls.get(i).getAsJsonObject();
			String method = call.has("method") && call.get("method").isJsonPrimitive()
					? call.get("method").getAsString() : null;
			JsonObject params = call.has("params") && call.get("params").isJsonObject()
					? call.getAsJsonObject("params") : new JsonObject();
			pending.add(CompletableFuture.runAsync(() -> results[idx] = router.dispatch(method, params), BATCH_POOL));
		}
		try {
			CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).get(60L, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw RpcException.unavailable("A batched call did not finish in time: " + e.getMessage());
		}
		JsonArray out = new JsonArray();
		int ok = 0;
		for (JsonObject r : results) {
			if (r == null) r = Json.envelopeError("internal", "no result from this call", null);
			JsonElement okFlag = r.get("ok");
			if (okFlag != null && okFlag.getAsBoolean()) ok++;
			out.add(r);
		}
		JsonObject o = new JsonObject();
		o.add("results", out);
		o.addProperty("okCount", ok);
		o.addProperty("count", out.size());
		return o;
	}

	// --- modules ----------------------------------------------------------------------------------

	static ClientTask moveTo(JsonObject p) throws RpcException {
		return new MoveToTask(goal(p, "moveTo"),
				optDouble(p, "reachRadius", 1.0),
				optBool(p, "sprint", false));
	}

	/**
	 * Break one block, from where the player already stands. Deliberately without judgement: it digs the
	 * block it is given. Whether that block is worth digging, whether it sits in water, and what to do
	 * about whatever is in the way are all decisions — the facts for them come from {@code vision.scan}
	 * ({@code wet}, {@code inReach}, {@code hardness}, {@code through}) and the answer comes from the
	 * caller. When the shot is blocked it says which block is blocking it and stops.
	 */
	static ClientTask dig(JsonObject p) throws RpcException {
		return new DigTask(goal(p, "dig"));
	}

	/** Land one hit on an entity: one swing at full charge, and no walking. */
	static ClientTask attack(JsonObject p) throws RpcException {
		return new AttackTask(uuidOf(p, "attack"));
	}

	/**
	 * Hold the use button for a while — the one gesture behind eating, drinking, drawing a bow and
	 * holding up a shield. It does not look at what is in the hand or whether the food is worth eating;
	 * that is the caller's call, and usually a rule's.
	 */
	static ClientTask useFor(JsonObject p) throws RpcException {
		return new UseForTask(optInt(p, "ticks", UseForTask.DEFAULT_TICKS));
	}

	/**
	 * Lay out a craft in the player's 2x2 grid (4 cells) or an open table (9 cells), clicking one slot
	 * per tick so the whole thing is visible in game. It places the ingredients and stops there: taking
	 * the product is a click on the output slot, which is {@code ui.clickSlot}, so what gets made and
	 * what gets picked up stay two separate decisions.
	 */
	static ClientTask craft(JsonObject p) throws RpcException {
		return new CraftTask(parseGrid(p));
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
