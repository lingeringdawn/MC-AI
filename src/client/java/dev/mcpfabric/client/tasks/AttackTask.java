package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/**
 * Fight an entity by <em>composing short steps</em> rather than owning one long fight.
 *
 * <p>Each cycle picks one small task — an {@link ApproachTask} to close the gap, or a
 * {@link SwingTask} to trade blows once in range — runs it for a short, fixed budget, then re-plans
 * from scratch against where the target actually is now. Nothing commits to a 30-second plan: the
 * step budget is a few seconds, so the task adapts to a moving target, stays observable the whole
 * way, and can be abandoned between steps without leaving the bot mid-swing.
 *
 * <p>The two steps are also exposed as tools in their own right, so a caller that wants to do its
 * own composing can drive them directly.
 */
public final class AttackTask extends ClientTask {
	/** Get this close before switching from walking to swinging. */
	private static final double APPROACH_REACH = 2.5;
	/** Budget for a single short step. Deliberately small — this is a composition of small steps. */
	private static final long STEP_BUDGET_MS = 3000L;
	/** Give up after this many consecutive steps that fail outright (no path, target lost, ...). */
	private static final int MAX_STEP_FAILURES = 3;

	private final UUID target;
	private final int maxSwings;

	private int swings;
	private int steps;
	private int consecutiveFailures;
	private String lastFailure = "unknown";
	private ClientTask step;
	private boolean stepStarted;
	private String stepName = "none";

	public AttackTask(UUID target, int maxSwings) {
		this.target = target;
		this.maxSwings = maxSwings;
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			cancelStep(mc);
			failed("no_world");
			return;
		}
		if (expired()) {
			cancelStep(mc);
			failed("timeout");
			return;
		}

		Entity e = EntityLookup.find(level, target);
		if (e == null) {
			cancelStep(mc);
			finish(everSeen() ? "target_gone" : "not_found");
			return;
		}
		if (!e.isAlive()) {
			cancelStep(mc);
			finish("killed");
			return;
		}
		if (maxSwings > 0 && swings >= maxSwings) {
			cancelStep(mc);
			finish("max_swings");
			return;
		}

		if (step == null && !plan(p, e)) return;
		if (!stepStarted) {
			step.onStart(mc);
			stepStarted = true;
		}
		try {
			step.tick(mc);
		} catch (Exception ex) {
			step.fail("step error: " + ex.getMessage());
		}
		if (step.isDone()) {
			harvest(step);
			step = null;
			stepStarted = false;
		}
	}

	/** Choose the next short step: close the distance, or swing if we are already there. */
	private boolean plan(LocalPlayer p, Entity e) {
		if (p.distanceTo(e) > APPROACH_REACH) {
			step = new ApproachTask(target, APPROACH_REACH);
			stepName = "approach";
		} else {
			int budget = maxSwings > 0 ? maxSwings - swings : 0;
			step = new SwingTask(target, budget);
			stepName = "swing";
		}
		step.setDeadline(STEP_BUDGET_MS);
		steps++;
		return true;
	}

	/** Collect what a finished step achieved and let a repeated hard failure end the task. */
	private void harvest(ClientTask finished) {
		JsonObject r = finished.result();
		if (r.has("swings")) swings += r.get("swings").getAsInt();
		String state = r.has("state") ? r.get("state").getAsString() : "";
		if ("failed".equals(state)) {
			consecutiveFailures++;
			if (r.has("detail")) lastFailure = r.get("detail").getAsString();
			if (consecutiveFailures >= MAX_STEP_FAILURES) failed("could not attack: " + lastFailure);
		} else {
			consecutiveFailures = 0;
		}
	}

	private void cancelStep(Minecraft mc) {
		if (step != null) {
			try {
				step.onCancel(mc);
			} catch (Exception ignored) {
				// best effort
			}
			step.fail("cancelled");
			step = null;
			stepStarted = false;
		}
	}

	@Override
	public void onCancel(Minecraft mc) {
		cancelStep(mc);
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.addProperty("target", target.toString());
		o.addProperty("swings", swings);
		o.addProperty("maxSwings", maxSwings);
		o.addProperty("stepName", stepName);
		o.addProperty("steps", steps);
		if (step != null) {
			JsonObject s = step.progress();
			if (s != null && s.size() > 0) o.add("step", s);
		}
		Entity e = EntityLookup.find(Minecraft.getInstance().level, target);
		LocalPlayer p = Minecraft.getInstance().player;
		if (e != null) {
			o.addProperty("targetName", e.getName().getString());
			if (e instanceof net.minecraft.world.entity.LivingEntity le) {
				o.addProperty("targetHealth", le.getHealth());
			}
			o.addProperty("targetAlive", e.isAlive());
			if (p != null) o.addProperty("distance", Math.round(p.distanceTo(e) * 100.0) / 100.0);
		} else {
			o.addProperty("targetAlive", false);
		}
		return o;
	}

	@Override
	public String describe() {
		return "fight entity " + target + " in short steps";
	}

	private boolean everSeen() {
		return steps > 0;
	}

	private void finish(String state) {
		JsonObject extra = new JsonObject();
		extra.addProperty("swings", swings);
		extra.addProperty("steps", steps);
		done(state, extra);
	}
}
