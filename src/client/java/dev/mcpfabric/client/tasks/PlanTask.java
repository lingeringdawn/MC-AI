package dev.mcpfabric.client.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Run a caller-supplied list of steps, in order, in one call.
 *
 * <p>This is not a behaviour. It has no idea what the steps are for: the caller names every one of
 * them out of the same small modules it could call one at a time, and says what should make the run
 * give up early. What you get for it is continuity — a nine-step tree-to-planks flow costs one
 * round-trip instead of nine, and the steps run back-to-back on the game thread with no chance for the
 * world to move underneath the plan between them.
 *
 * <p>A step is either a module ({@code {"action":"mineBlock","x":..}}) — a multi-tick task, built when
 * its turn comes so a symbolic target like {@code "nearest_drop"} resolves against the world as it is
 * <em>then</em>, not as it was when the plan was written — or any other method fired inline
 * ({@code {"rpc":"inventory.selectHotbar","slot":0}}).
 *
 * <p>The guards are the caller's own thresholds, applied mechanically: {@code guard.abortIfHealthBelow},
 * {@code guard.abortIfAirBelow}, {@code guard.abortIfDead}. They exist because a plan runs unattended
 * for tens of seconds, and "stop digging if I am drowning" should not have to be a step.
 */
public final class PlanTask extends ClientTask {
	/** Long enough for any real composition; a runaway caller is refused rather than left spinning. */
	private static final int MAX_STEPS = 64;
	/**
	 * Instant steps that would act on the plan itself. {@code action.do} would recurse, and cancel or
	 * status would reach back into the thing that is currently running.
	 */
	private static final List<String> REJECTED = List.of("action.cancel", "action.do", "action.status");

	private static final int HANDLED = 0;
	private static final int STARTED = 1;
	private static final int STOPPED = 2;

	private final RpcRouter router;
	private final List<JsonObject> steps = new ArrayList<>();
	private final boolean continueOnFailure;
	private final double abortHealthBelow;
	private final int abortAirBelow;
	private final boolean abortIfDead;

	private final JsonArray log = new JsonArray();
	private int index;
	private long stepStartMs;
	private ClientTask sub;

	public PlanTask(RpcRouter router, JsonArray rawSteps, boolean continueOnFailure,
			double abortHealthBelow, int abortAirBelow, boolean abortIfDead) throws RpcException {
		this(router, rawSteps, continueOnFailure, abortHealthBelow, abortAirBelow, abortIfDead, 0, null);
	}

	/**
	 * Resume a plan that was parked. {@code startIndex} is the step to run — the one that was interrupted,
	 * redone from the top, because a step half done is not a step done — and {@code priorLog} keeps what
	 * the earlier steps reported so the resumed run still tells the whole story.
	 */
	public PlanTask(RpcRouter router, JsonArray rawSteps, boolean continueOnFailure,
			double abortHealthBelow, int abortAirBelow, boolean abortIfDead, int startIndex, JsonArray priorLog)
			throws RpcException {
		this.router = router;
		this.continueOnFailure = continueOnFailure;
		this.abortHealthBelow = abortHealthBelow;
		this.abortAirBelow = abortAirBelow;
		this.abortIfDead = abortIfDead;
		if (rawSteps == null || rawSteps.isEmpty()) {
			throw RpcException.badRequest("'steps' must be a non-empty array of steps.");
		}
		if (rawSteps.size() > MAX_STEPS) {
			throw RpcException.badRequest("'steps' holds at most " + MAX_STEPS + " entries; got " + rawSteps.size() + ".");
		}
		for (JsonElement e : rawSteps) {
			if (!e.isJsonObject()) {
				throw RpcException.badRequest("Step " + steps.size() + " must be an object, e.g. "
						+ "{\"action\":\"moveTo\",\"x\":1,\"y\":64,\"z\":2} or {\"rpc\":\"inventory.selectHotbar\",\"slot\":0}.");
			}
			JsonObject step = e.getAsJsonObject();
			String action = text(step, "action");
			String rpc = text(step, "rpc");
			if (action == null && rpc == null) {
				throw RpcException.badRequest("Step " + steps.size() + " names neither 'action' (a module: "
						+ TaskModules.names() + ") nor 'rpc' (any other method).");
			}
			// Guarded against null on purpose: List.of(...).contains(null) throws rather than returning
			// false, which would take down every plan whose steps are modules (the common case).
			if (rpc != null && REJECTED.contains(rpc)) {
				throw RpcException.badRequest("'" + rpc + "' cannot be a step: it would act on the plan itself.");
			}
			// A module reached through 'rpc' would be submitted as a second task and refused; say so here
			// rather than letting the caller debug an "already running" error from their own plan.
			if (rpc != null && rpc.startsWith("action.") && TaskModules.get(rpc.substring(7)) != null) {
				throw RpcException.badRequest("Use {\"action\":\"" + rpc.substring(7) + "\"} for modules, not "
						+ "{\"rpc\":\"" + rpc + "\"} — a module is a multi-tick step the plan has to drive.");
			}
			steps.add(step);
		}
		this.index = Math.max(0, Math.min(startIndex, steps.size()));
		if (priorLog != null) this.log.addAll(priorLog);
	}

	/**
	 * Where this plan had got to, as the caller's own data: the steps as given, the guards, the cursor and
	 * what the finished steps reported. Opaque to everything else, which is what lets the cursor and the
	 * guards travel together instead of being re-supplied from memory.
	 */
	@Override
	public JsonObject parkSnapshot() {
		JsonObject o = new JsonObject();
		JsonArray raw = new JsonArray();
		for (JsonObject s : steps) raw.add(s.deepCopy());
		o.add("steps", raw);
		o.addProperty("continueOnFailure", continueOnFailure);
		o.addProperty("abortIfHealthBelow", abortHealthBelow);
		o.addProperty("abortIfAirBelow", abortAirBelow);
		o.addProperty("abortIfDead", abortIfDead);
		// The step to run next, not the last one finished: an interrupted step is redone.
		o.addProperty("index", Math.min(index, steps.size()));
		o.add("log", log.deepCopy());
		o.addProperty("describe", describe());
		return o;
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null) {
			failed("no_world");
			return;
		}
		String guard = guardTripped(p);
		if (guard != null) {
			releaseSub(mc);
			finish("aborted", guard);
			return;
		}
		if (expired()) {
			releaseSub(mc);
			finish("budget", "the plan's own budget ran out after " + elapsedMs() + "ms");
			return;
		}

		// Take every step that costs no time (inline methods, and steps that fail to build), then hand
		// the rest of the tick to whichever multi-tick step is now in progress.
		while (sub == null) {
			if (index >= steps.size()) {
				finish("done", null);
				return;
			}
			int outcome = advance(mc);
			if (outcome == STOPPED) return;
			if (outcome == STARTED) break;
			index++;
		}

		JsonObject step = steps.get(index);
		if (sub.isDone()) {
			ClientTask finished = sub;
			sub = null;
			boolean ok = finished.status() == ClientTaskStatus.DONE;
			record(finished.describe(), ok, detailOf(finished.result()), finished.result());
			index++;
			if (!ok && !optional(step) && !continueOnFailure) {
				finish("stopped", "step " + (index - 1) + " (" + finished.describe() + ") "
						+ (detailOf(finished.result()).isEmpty() ? "failed" : detailOf(finished.result())));
				return;
			}
			return;
		}
		sub.tick(mc);
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.addProperty("completed", index);
		o.addProperty("total", steps.size());
		if (index < steps.size()) {
			JsonObject step = steps.get(index);
			String name = text(step, "action");
			o.addProperty("current", name != null ? name : text(step, "rpc"));
		}
		o.addProperty("stepMs", elapsedStepMs());
		if (sub != null) o.add("phase", sub.progress());
		if (log.size() > 0) o.add("steps", log);
		return o;
	}

	@Override
	public String describe() {
		return "plan " + Math.min(index + 1, steps.size()) + "/" + steps.size();
	}

	@Override
	public void onCancel(Minecraft mc) {
		releaseSub(mc);
	}

	// --- steps ------------------------------------------------------------------------------------

	private int advance(Minecraft mc) {
		JsonObject step = steps.get(index);
		String rpc = text(step, "rpc");
		if (rpc != null) {
			JsonObject envelope = router.dispatch(rpc, paramsOf(step));
			boolean ok = envelope.has("ok") && envelope.get("ok").getAsBoolean();
			JsonObject result = envelope.has("result") && envelope.get("result").isJsonObject()
					? envelope.getAsJsonObject("result") : null;
			record(rpc, ok, ok ? detailOf(result) : messageOf(envelope), result);
			if (ok || optional(step) || continueOnFailure) return HANDLED;
			finish("stopped", "step " + index + " (" + rpc + ") failed: " + messageOf(envelope));
			return STOPPED;
		}

		String name = text(step, "action");
		TaskModules.Module module = TaskModules.get(name);
		if (module == null) {
			record(String.valueOf(name), false, "no such action", null);
			if (optional(step) || continueOnFailure) return HANDLED;
			finish("stopped", "step " + index + ": no action named '" + name + "'. Available: " + TaskModules.names());
			return STOPPED;
		}
		ClientTask task;
		try {
			task = module.factory().create(paramsOf(step));
		} catch (RpcException e) {
			record(name, false, e.getMessage(), null);
			if (optional(step) || continueOnFailure) return HANDLED;
			finish("stopped", "step " + index + " (" + name + ") rejected: " + e.getMessage());
			return STOPPED;
		}
		task.setDeadline(Math.max(1, Math.min(300, intOr(step, "timeoutSeconds", module.defaultTimeoutSeconds()))) * 1000L);
		// Modules are submitted through TaskManager when called directly, which is where onStart runs.
		// A step is driven here instead, so it has to be started here too — a task that never gets
		// onStart is one that never begins (a moveTo would report "not_started" and do nothing).
		task.onStart(mc);
		stepStartMs = System.currentTimeMillis();
		sub = task;
		return STARTED;
	}

	// --- guards -----------------------------------------------------------------------------------

	private String guardTripped(LocalPlayer p) {
		if (abortIfDead && p.isDeadOrDying()) return "the player died";
		if (abortHealthBelow > 0 && p.getHealth() < abortHealthBelow) {
			return "health " + p.getHealth() + " < " + abortHealthBelow;
		}
		if (abortAirBelow > 0 && p.getAirSupply() < abortAirBelow) {
			return "air " + p.getAirSupply() + " < " + abortAirBelow;
		}
		return null;
	}

	private void releaseSub(Minecraft mc) {
		if (sub == null) return;
		sub.onCancel(mc);
		sub = null;
		// An abandoned step must not leave the bot walking or swinging: it never settles, so it never
		// gets to release its own inputs.
		BotController.get().stopAllMovement();
		BotController.get().stopNavigation("plan_stopped");
	}

	// --- reporting --------------------------------------------------------------------------------

	private void finish(String state, String reason) {
		JsonObject extra = new JsonObject();
		extra.add("steps", log);
		extra.addProperty("completed", index);
		extra.addProperty("total", steps.size());
		if (reason != null) extra.addProperty("stoppedBy", reason);
		if ("done".equals(state)) {
			done("all " + steps.size() + " steps ran", extra);
		} else {
			failed(reason == null ? "stopped" : reason, extra);
		}
	}

	private void record(String step, boolean ok, String detail, JsonObject result) {
		JsonObject e = new JsonObject();
		e.addProperty("i", index);
		e.addProperty("step", step);
		e.addProperty("state", ok ? "done" : "failed");
		if (detail != null && !detail.isEmpty()) e.addProperty("detail", detail);
		e.addProperty("ms", elapsedStepMs());
		if (result != null && result.size() > 0) {
			// Inline steps are the caller's own reads, so hand the result back — but not so much of it
			// that a plan ends up costing more tokens than the calls it replaced.
			String json = result.toString();
			if (json.length() <= 3000) e.add("result", result);
			else e.addProperty("resultOmitted", json.length() + " chars — call '" + step + "' on its own if you need it");
		}
		log.add(e);
	}

	private long elapsedStepMs() {
		return stepStartMs == 0L ? 0L : System.currentTimeMillis() - stepStartMs;
	}

	private static String detailOf(JsonObject r) {
		return r != null && r.has("detail") && r.get("detail").isJsonPrimitive() ? r.get("detail").getAsString() : "";
	}

	private static String messageOf(JsonObject envelope) {
		if (envelope.has("error") && envelope.get("error").isJsonObject()) {
			JsonObject err = envelope.getAsJsonObject("error");
			if (err.has("message") && err.get("message").isJsonPrimitive()) return err.get("message").getAsString();
		}
		return "failed";
	}

	// --- parameters ------------------------------------------------------------------------------

	/** Step parameters, with the plan's own keys stripped so a module never reads them by accident. */
	private static JsonObject paramsOf(JsonObject step) {
		JsonObject p = step.deepCopy();
		p.remove("action");
		p.remove("rpc");
		p.remove("optional");
		p.remove("timeoutSeconds");
		return p;
	}

	private static boolean optional(JsonObject step) {
		return step.has("optional") && step.get("optional").isJsonPrimitive() && step.get("optional").getAsBoolean();
	}

	private static String text(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}

	private static int intOr(JsonObject o, String key, int fallback) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
	}
}
