package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import net.minecraft.client.Minecraft;

/**
 * Runs at most one goal-oriented {@link ClientTask} at a time.
 *
 * <p>{@link #submit} and {@link #tick} run on the game thread; {@link #await} is meant to be called
 * from an RPC worker thread and simply waits for the active task to settle, so the game keeps
 * ticking underneath.
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

	/** Submit on the game thread. Returns false if another task is still running. */
	public synchronized boolean submit(ClientTask task) {
		if (current != null && !current.isDone()) return false;
		current = task;
		observer.begin(task);
		task.onStart(Minecraft.getInstance());
		return true;
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
		if (abortOnDanger && !t.isDone() && observer.danger() >= 2) {
			t.fail("aborted: " + observer.dangerReason());
			observer.note("aborted — " + observer.dangerReason());
		}
	}

	private volatile boolean watching = true;
	private volatile boolean abortOnDanger;

	/** Turn per-tick world sampling on/off (config). */
	public void setWatching(boolean on) {
		this.watching = on;
		observer.setEnabled(on);
	}

	/** Turn automatic task abort on extreme danger on/off (config). */
	public void setAbortOnDanger(boolean on) {
		this.abortOnDanger = on;
	}

	public boolean watching() {
		return watching;
	}

	public boolean abortOnDanger() {
		return abortOnDanger;
	}

	/**
	 * Block the calling (worker) thread until the active task settles or {@code waitMs} elapses.
	 * A return value with {@code state:"running"} means the task is still going — poll action.status.
	 */
	public JsonObject await(long waitMs) {
		long deadline = System.currentTimeMillis() + Math.max(1L, waitMs);
		ClientTask t = current;
		if (t == null) return idle("no active task");
		while (System.currentTimeMillis() < deadline) {
			if (t.isDone()) {
				JsonObject r = t.result();
				synchronized (this) {
					if (current == t) current = null;
				}
				attachObservation(r, t);
				return r;
			}
			try {
				Thread.sleep(20L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return idle("interrupted");
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("state", "running");
		o.addProperty("detail", "still running after wait budget; poll action.status");
		attachObservation(o, t);
		return o;
	}

	/** Cancel the active task on the game thread. */
	public synchronized void cancel(Minecraft mc) {
		ClientTask t = current;
		if (t != null && !t.isDone()) {
			try {
				t.onCancel(mc);
			} catch (Throwable ignored) {
				// best effort
			}
			t.fail("cancelled");
		}
		current = null;
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
