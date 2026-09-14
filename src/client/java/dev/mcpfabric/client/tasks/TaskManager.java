package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import net.minecraft.client.Minecraft;

/**
 * Runs at most one goal-oriented {@link ClientTask} at a time, and never makes anyone wait for it.
 *
 * <p>{@link #submit} and {@link #tick} run on the game thread. {@link #submit} hands back the moment
 * the task is in charge: it does not wait for it to settle, because a caller that is blocked on a long
 * action has no way to change its mind while the world moves underneath it. The caller watches through
 * {@link #status} / {@link #observe} and keeps the last word — submitting anything else supersedes the
 * running task on the spot, and {@link #cancel} stops it.
 *
 * <p>Every running task is also fed through a {@link TaskObserver}, which samples the world each
 * tick. That becomes part of the status payload, so a caller blocked on a long action (or polling
 * it) can still see the world move: health, nearby hostiles, drops, what the crosshair is on, the
 * task's own progress, and a rolling log of notable moments. {@code danger()} in the snapshot tells
 * the caller when the situation has turned bad enough to abort.
 */
public final class TaskManager {
	private static final TaskManager INSTANCE = new TaskManager();

	public static TaskManager get() {
		return INSTANCE;
	}

	private TaskManager() {}

	private volatile ClientTask current;
	private final TaskObserver observer = new TaskObserver();

	/**
	 * Put this task in charge on the game thread and return at once with what happened.
	 *
	 * <p>Whatever was running is superseded — cancelled and named in the reply — rather than refused.
	 * A caller changing its mind is the normal case, not an error: a new instruction should take effect
	 * on the next tick, not once the old one has decided it is finished. Nothing here waits for the task
	 * to settle; that is what {@link #status} is for.
	 */
	public synchronized JsonObject submit(ClientTask task) {
		Minecraft mc = Minecraft.getInstance();
		ClientTask previous = current;
		String superseded = null;
		if (previous != null && !previous.isDone()) {
			superseded = previous.describe();
			try {
				previous.onCancel(mc);
			} catch (Throwable ignored) {
				// best effort: a superseded task gets no say in the matter
			}
			previous.fail("superseded");
			observer.note("superseded " + superseded);
		}
		current = task;
		observer.begin(task);
		task.onStart(mc);
		observer.sample(mc, task);

		JsonObject o = new JsonObject();
		o.addProperty("state", "running");
		o.addProperty("active", true);
		o.addProperty("task", task.describe());
		o.addProperty("deadlineMs", task.remainingMsLeft());
		o.addProperty("note", superseded == null
				? "Started. Nothing waits for it: watch it with action.status / observe, send any other "
						+ "action to take over, or action.cancel to stop it."
				: "Replaced '" + superseded + "', which was still running. Same controls apply to this one.");
		if (superseded != null) o.addProperty("superseded", superseded);
		addObservation(o);
		return o;
	}

	public ClientTask current() {
		return current;
	}

	/** The live observation of the running task (never null). */
	public TaskObserver observer() {
		return observer;
	}

	/** Drive the active task; called from the client tick (game thread). */
	public void tick(Minecraft mc) {
		ClientTask t = current;
		if (t == null) {
			// Keep sampling even between tasks so an idle player is still observable.
			observer.sampleIdle(mc);
			return;
		}
		if (t.isDone()) {
			// Keep the settled result available for whoever polls action.status (a caller using
			// waitSeconds may only learn the outcome on a later poll), but go back to sampling the
			// idle world so the observation does not freeze on the task's last tick.
			observer.sampleIdle(mc);
			return;
		}
		// A dead player cannot act. Fail the task instead of letting it thrash against a corpse for
		// the rest of its budget, which is what used to happen after the bot was killed mid-walk.
		if (mc.player != null && mc.player.isDeadOrDying()) {
			t.fail("dead");
			observer.note("task failed — the player died");
			observer.sampleIdle(mc);
			return;
		}
		try {
			t.tick(mc);
		} catch (Exception e) {
			t.fail("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
		if (watching) {
			try {
				observer.sample(mc, t);
			} catch (Exception e) {
				McpFabric.LOGGER.warn("[mcpfabric] observer sample failed", e);
			}
		}
	}

	private volatile boolean watching = true;

	/** Turn per-tick world sampling on/off (config). */
	public void setWatching(boolean on) {
		this.watching = on;
		observer.setEnabled(on);
	}

	public boolean watching() {
		return watching;
	}


	/**
	 * Stop the active task on the game thread. It settles as {@code cancelled} and stays readable
	 * through {@link #status}, so the caller can see that its instruction was obeyed and why.
	 */
	public synchronized void cancel(Minecraft mc) {
		ClientTask t = current;
		if (t == null || t.isDone()) return;
		try {
			t.onCancel(mc);
		} catch (Throwable ignored) {
			// best effort
		}
		t.fail("cancelled");
		observer.note("cancelled " + t.describe());
	}

	public JsonObject status() {
		ClientTask t = current;
		if (t == null) {
			JsonObject o = idle("idle");
			addObservation(o);
			return o;
		}
		JsonObject o = t.result();
		o.addProperty("active", !t.isDone());
		o.addProperty("task", t.getClass().getSimpleName());
		attachObservation(o, t);
		return o;
	}

	/** Just the live watch payload (world + progress + log), without the task result. */
	public JsonObject observe() {
		JsonObject o = new JsonObject();
		ClientTask t = current;
		o.addProperty("task", t == null ? "none" : t.getClass().getSimpleName());
		o.addProperty("active", t != null && !t.isDone());
		attachObservation(o, t);
		return o;
	}

	private void addObservation(JsonObject o) {
		o.add("observe", observer.json());
	}

	private void attachObservation(JsonObject o, ClientTask t) {
		if (t != null) {
			o.addProperty("elapsedMs", t.elapsedMs());
			o.addProperty("remainingMs", t.remainingMsLeft());
			JsonObject p = t.progress();
			if (p != null && p.size() > 0) o.add("progress", p);
		}
		addObservation(o);
	}

	private static JsonObject idle(String detail) {
		JsonObject o = new JsonObject();
		o.addProperty("state", "idle");
		o.addProperty("detail", detail);
		o.addProperty("active", false);
		return o;
	}
}
