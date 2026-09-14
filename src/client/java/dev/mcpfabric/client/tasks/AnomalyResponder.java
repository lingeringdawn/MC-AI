package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Opt-in: turn the observer's worst anomaly into the matching short action.
 *
 * <p>The reporting half — detecting and naming abnormal player states every tick — is always on, and is
 * what an AI should be reading. This half executes the remedy, so a caller that would rather not poll
 * can let the mod deal with drowning, a lethal fall, hunger and a mob inside melee range on its own.
 *
 * <p>Two rules keep it from being the automation the rest of the mod deliberately avoids:
 *
 * <ul>
 *   <li>It is off unless {@code autoHandleAnomalies} is set — the default is to report, not to act.</li>
 *   <li>It never interrupts a task the caller asked for. A remedy only fires in the gap between
 *       actions, and each episode fires once rather than re-submitting every tick.</li>
 * </ul>
 */
public final class AnomalyResponder {
	private static final AnomalyResponder INSTANCE = new AnomalyResponder();

	public static AnomalyResponder get() {
		return INSTANCE;
	}

	/** Budget handed to a remedy: all of these are meant to settle in a second or two. */
	private static final long BUDGET_MS = 20_000L;
	/** How far to withdraw when the remedy is a retreat. */
	private static final double RETREAT_DISTANCE = 10.0;

	/** The anomaly already being handled, so one episode fires once. */
	private String handling = "";

	private AnomalyResponder() {}

	public void tick(Minecraft mc, boolean enabled) {
		if (!enabled) {
			handling = "";
			return;
		}
		TaskObserver observer = TaskManager.get().observer();
		JsonObject top = observer.topAnomaly();
		if (top == null || !top.has("remedy")) {
			// Nothing actionable (or nothing wrong): arm again for the next episode.
			handling = "";
			return;
		}
		String id = observer.topAnomalyId();
		if (id.equals(handling)) return;

		// Never cut across a task the caller asked for — the remedy waits for the next gap.
		JsonObject status = TaskManager.get().status();
		if (status.has("active") && status.get("active").getAsBoolean()) return;

		String remedy = top.get("remedy").getAsString();
		if ("action_cancel".equals(remedy)) {
			TaskManager.get().cancel(mc);
			handling = id;
			observer.note("auto: cancelled the action — " + top.get("evidence").getAsString());
			return;
		}

		ClientTask task = build(remedy, top);
		if (task == null) return;
		task.setDeadline(BUDGET_MS);
		if (TaskManager.get().submit(task)) {
			handling = id;
			observer.note("auto: " + remedy + " for " + id + " (" + top.get("evidence").getAsString() + ")");
		}
	}

	private ClientTask build(String remedy, JsonObject anomaly) {
		switch (remedy) {
			case "surface":
				return new SurfaceTask();
			case "water_bucket_save":
				return new MlgTask();
			case "eat":
				return new EatTask();
			case "retreat_from":
				return retreat(anomaly);
			default:
				return null;
		}
	}

	/** Withdraw from whatever the anomaly pointed at, when it pointed at something. */
	private ClientTask retreat(JsonObject anomaly) {
		if (!anomaly.has("at") || !anomaly.get("at").isJsonObject()) return null;
		JsonObject at = anomaly.getAsJsonObject("at");
		return new RetreatTask(at.get("x").getAsDouble(), at.get("y").getAsDouble(),
				at.get("z").getAsDouble(), RETREAT_DISTANCE);
	}
}
