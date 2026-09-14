package dev.mcpfabric.client.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.Vision;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The caller's own reflexes, written by the caller.
 *
 * <p>A person does not decide to surface when they drown — they notice and act, in about a quarter of
 * a second, without a round trip through anywhere. That is what this is for: the caller states rules
 * once ("if air drops below 100, surface"; "if something is chewing on me and I am under half health,
 * back off from it"), and the mod evaluates them every tick and fires the action when the condition
 * holds.
 *
 * <p>It is deliberately NOT a behaviour layer, and the distinction is the whole design:
 * <ul>
 *   <li>every rule is written by the caller, in the caller's words — none is built in, and the set is
 *       replaced wholesale by the next {@code rules.set};</li>
 *   <li>conditions are only facts the observation already reports (vitals, threats, drops, inventory,
 *       what is visible) — there is no scoring, no preference and no "good idea" anywhere in here;</li>
 *   <li>the action fired is the caller's own wording too, dispatched through exactly the same modules
 *       a manual call would use, and every firing lands in the observation stream as an event.</li>
 * </ul>
 *
 * <p>So the judgement lives in the rules, where the caller can read it, change it and delete it — not
 * buried in an atomic module. A rule is the caller's sentence, spoken once, executed by the tick loop.
 */
public final class Rules {
	private static final Rules INSTANCE = new Rules();

	public static Rules get() {
		return INSTANCE;
	}

	private Rules() {}

	/** A caller-written reflex. */
	private static final class Rule {
		String name = "";
		JsonObject when = new JsonObject();
		JsonObject then = new JsonObject();
		/** Ticks between firings, so a rule that stays true does not fire every tick. */
		int cooldownTicks = 60;
		/** Ticks between noticing and acting — a person does not react on the same tick. */
		int delayTicks = 3;
		boolean once;
		long nextAllowedTick;
		long pendingTick = -1L;
		boolean fired;
		int fires;
	}

	private final List<Rule> rules = new ArrayList<>();
	private RpcRouter router;
	private long tick;

	/** Hand the rule layer the dispatcher it fires through. Called once, when handlers register. */
	public void bind(RpcRouter router) {
		this.router = router;
	}

	// --- the caller's interface -------------------------------------------------------------------

	/**
	 * Replace the rule set. Each rule is {@code {name, when:{...conditions}, then:{action|rpc, ...},
	 * cooldownSeconds, delayTicks, once}}. Replacing rather than appending on purpose: the caller's
	 * current intent is the whole of what should be running.
	 */
	public synchronized JsonObject set(JsonObject params) throws RpcException {
		if (!params.has("rules") || !params.get("rules").isJsonArray()) {
			throw RpcException.badRequest("Missing 'rules': give an array of {when:{...}, then:{...}}.");
		}
		JsonArray arr = params.getAsJsonArray("rules");
		if (arr.size() > 16) {
			throw RpcException.badRequest("At most 16 rules; got " + arr.size() + ".");
		}
		List<Rule> parsed = new ArrayList<>();
		for (JsonElement e : arr) {
			if (e == null || !e.isJsonObject()) {
				throw RpcException.badRequest("Every rule must be an object.");
			}
			JsonObject r = e.getAsJsonObject();
			if (!r.has("when") || !r.get("when").isJsonObject()) {
				throw RpcException.badRequest("A rule needs a 'when' object of conditions.");
			}
			if (!r.has("then") || !r.get("then").isJsonObject()) {
				throw RpcException.badRequest("A rule needs a 'then' object: {action:...} or {rpc:...}.");
			}
			JsonObject then = r.getAsJsonObject("then");
			if (!then.has("action") && !then.has("rpc")) {
				throw RpcException.badRequest("A rule's 'then' needs 'action' (a module) or 'rpc' (a method).");
			}
			if (then.has("action") && !TaskModules.all().containsKey(then.get("action").getAsString())) {
				throw RpcException.badRequest("No such module: '" + then.get("action").getAsString()
						+ "'. Modules: " + String.join(", ", TaskModules.all().keySet()) + ".");
			}
			if (then.has("rpc") && (router == null || !router.has(then.get("rpc").getAsString()))) {
				throw RpcException.badRequest("No such method: '" + then.get("rpc").getAsString() + "'.");
			}
			Rule rule = new Rule();
			rule.name = r.has("name") ? r.get("name").getAsString() : summarize(then);
			rule.when = r.getAsJsonObject("when");
			rule.then = then;
			rule.cooldownTicks = ticks(r, "cooldownSeconds", 3.0);
			rule.delayTicks = intOr(r, "delayTicks", 3);
			rule.once = r.has("once") && r.get("once").getAsBoolean();
			parsed.add(rule);
		}
		synchronized (this) {
			rules.clear();
			rules.addAll(parsed);
		}
		JsonObject o = new JsonObject();
		o.addProperty("count", parsed.size());
		o.add("rules", list().getAsJsonArray("rules"));
		o.addProperty("note", "Rules run every tick from now on. They are yours: none is built in, "
				+ "rules.set replaces the whole set, and every firing is reported in the observation "
				+ "stream as an event with kind 'rule'.");
		return o;
	}

	public synchronized JsonObject clear() {
		rules.clear();
		JsonObject o = new JsonObject();
		o.addProperty("count", 0);
		o.addProperty("note", "No rules are running. Nothing here acts on its own.");
		return o;
	}

	public synchronized JsonObject list() {
		JsonArray arr = new JsonArray();
		for (Rule r : rules) {
			JsonObject o = new JsonObject();
			o.addProperty("name", r.name);
			o.add("when", r.when.deepCopy());
			o.add("then", r.then.deepCopy());
			o.addProperty("cooldownSeconds", r.cooldownTicks / 20.0);
			o.addProperty("delayTicks", r.delayTicks);
			o.addProperty("once", r.once);
			o.addProperty("fires", r.fires);
			o.addProperty("done", r.once && r.fired);
			arr.add(o);
		}
		JsonObject o = new JsonObject();
		o.add("rules", arr);
		o.addProperty("count", arr.size());
		o.addProperty("tick", tick);
		return o;
	}

	// --- the tick loop -----------------------------------------------------------------------------

	/**
	 * Evaluate every rule. Called from the client tick, before the running task, so a reflex can act
	 * while something else is in progress — which is the point of having it at all.
	 */
	public void tick(Minecraft mc, TaskObserver observer) {
		LocalPlayer p = mc.player;
		if (p == null || mc.level == null || router == null) {
			return;
		}
		tick++;
		List<Rule> snapshot;
		synchronized (this) {
			if (rules.isEmpty()) return;
			snapshot = new ArrayList<>(rules);
		}
		for (Rule rule : snapshot) {
			try {
				// Already armed: wait it out. Re-arming every tick would push the firing time a little
				// further away each time and it would never arrive at all — which is exactly what the
				// condition "true, and still true next tick" produced before.
				if (rule.pendingTick >= 0L) {
					if (tick >= rule.pendingTick) {
						rule.pendingTick = -1L;
						fire(rule, observer);
					}
					continue;
				}
				if (rule.once && rule.fired) continue;
				if (tick < rule.nextAllowedTick) continue;
				if (!matches(rule, mc, p, observer)) continue;
				// Notice now, act a few ticks later: the pause is what separates a person who has just
				// seen something from a script that was waiting for it.
				rule.pendingTick = tick + Math.max(0, rule.delayTicks);
			} catch (Throwable t) {
				observer.note("rule '" + rule.name + "' failed to evaluate: " + t.getMessage());
				rule.nextAllowedTick = tick + 40;
			}
		}
	}

	private void fire(Rule rule, TaskObserver observer) {
		String method = rule.then.has("action")
				? "action." + rule.then.get("action").getAsString()
				: rule.then.get("rpc").getAsString();
		JsonObject params = rule.then.deepCopy();
		params.remove("action");
		params.remove("rpc");
		JsonObject env = router.dispatch(method, params);
		boolean ok = env.has("ok") && env.get("ok").getAsBoolean();
		rule.fired = true;
		rule.fires++;
		rule.nextAllowedTick = tick + rule.cooldownTicks;
		if (ok) {
			String detail = env.has("result") && env.get("result").isJsonObject()
					&& env.getAsJsonObject("result").has("note")
					? env.getAsJsonObject("result").get("note").getAsString() : "";
			observer.record("rule", "rule '" + rule.name + "' -> " + method
					+ (detail.isEmpty() ? "" : " (" + detail + ")"));
		} else {
			String why = env.has("error") && env.getAsJsonObject("error").has("message")
					? env.getAsJsonObject("error").get("message").getAsString() : "failed";
			observer.record("rule_failed", "rule '" + rule.name + "' -> " + method + " failed: " + why);
		}
	}

	// --- conditions --------------------------------------------------------------------------------

	/**
	 * Do the conditions hold? Every key present must hold; keys that are absent are not asked about.
	 *
	 * <p>Each one is a fact the observation already carries, so a rule reads like the observation and
	 * the caller only has to learn one vocabulary.
	 */
	private boolean matches(Rule rule, Minecraft mc, LocalPlayer p, TaskObserver observer) {
		JsonObject w = rule.when;

		if (w.has("dead") && p.isDeadOrDying() != w.get("dead").getAsBoolean()) return false;
		if (w.has("healthBelow") && !(p.getHealth() < w.get("healthBelow").getAsDouble())) return false;
		if (w.has("healthAbove") && !(p.getHealth() > w.get("healthAbove").getAsDouble())) return false;
		if (w.has("airBelow") && !(p.getAirSupply() < w.get("airBelow").getAsInt())) return false;
		if (w.has("foodBelow") && !(p.getFoodData().getFoodLevel() < w.get("foodBelow").getAsInt())) return false;
		if (w.has("underwater") && p.isUnderWater() != w.get("underwater").getAsBoolean()) return false;
		if (w.has("inWater") && p.isInWater() != w.get("inWater").getAsBoolean()) return false;
		if (w.has("inLava") && p.isInLava() != w.get("inLava").getAsBoolean()) return false;
		if (w.has("onFire") && p.isOnFire() != w.get("onFire").getAsBoolean()) return false;
		if (w.has("onGround") && p.onGround() != w.get("onGround").getAsBoolean()) return false;
		if (w.has("fallingHard") && !(p.fallDistance >= 4.0 || (fallingHard(p) && w.get("fallingHard").getAsBoolean()))) {
			return false;
		}
		if (w.has("hostileWithin") && !(nearestHostile(p) <= w.get("hostileWithin").getAsDouble())) return false;
		if (w.has("hostilesAtLeast")) {
			int need = w.get("hostilesAtLeast").getAsInt();
			JsonObject now = observer.json().getAsJsonObject("now");
			int have = now.has("threatsNearby") ? now.get("threatsNearby").getAsInt() : 0;
			if (have < need) return false;
		}
		if (w.has("dropsAtLeast")) {
			int need = w.get("dropsAtLeast").getAsInt();
			JsonObject now = observer.json().getAsJsonObject("now");
			int have = now.has("dropsNearby") ? now.get("dropsNearby").getAsInt() : 0;
			if (have < need) return false;
		}
		if (w.has("itemCount")) {
			JsonObject want = w.getAsJsonObject("itemCount");
			for (String id : want.keySet()) {
				if (countOf(p, id) < want.get(id).getAsInt()) return false;
			}
		}
		if (w.has("holding")) {
			ItemStack held = p.getMainHandItem();
			String id = held.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
			if (!id.equals(w.get("holding").getAsString())) return false;
		}
		if (w.has("visible")) {
			JsonObject want = w.getAsJsonObject("visible");
			String id = want.has("id") ? want.get("id").getAsString() : null;
			int atLeast = want.has("atLeast") ? want.get("atLeast").getAsInt() : 1;
			double within = want.has("within") ? want.get("within").getAsDouble() : Vision.DEFAULT_DISTANCE;
			int found = 0;
			for (Vision.Seen s : Vision.blocks(mc, within, 11, 6, null, 40)) {
				if (id == null || s.id().equals(id)) found++;
			}
			if (found < atLeast) return false;
		}
		return true;
	}

	private static boolean fallingHard(LocalPlayer p) {
		return !p.onGround() && !p.isInWater() && p.fallDistance >= 4.0;
	}

	private static double nearestHostile(LocalPlayer p) {
		double best = Double.MAX_VALUE;
		for (Entity e : p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(24.0))) {
			if (e == p || !e.isAlive() || !(e instanceof Enemy)) continue;
			best = Math.min(best, p.distanceTo(e));
		}
		return best;
	}

	private static int countOf(LocalPlayer p, String itemId) {
		int n = 0;
		for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty()) continue;
			if (BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(itemId)) n += st.getCount();
		}
		return n;
	}

	// --- parsing helpers ---------------------------------------------------------------------------

	private static int ticks(JsonObject o, String key, double fallbackSeconds) {
		double seconds = o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsDouble() : fallbackSeconds;
		return (int) Math.max(0, Math.min(600, Math.round(seconds * 20.0)));
	}

	private static int intOr(JsonObject o, String key, int fallback) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsInt() : fallback;
	}

	private static String summarize(JsonObject then) {
		return then.toString();
	}

	/** The condition vocabulary, for tool descriptions and for {@code action.help}. */
	public static final String CONDITIONS =
			"dead, healthBelow, healthAbove, airBelow, foodBelow, underwater, inWater, inLava, onFire, "
					+ "onGround, fallingHard, hostileWithin, hostilesAtLeast, dropsAtLeast, "
					+ "itemCount:{itemId:count}, holding, visible:{id, atLeast, within}";
}
