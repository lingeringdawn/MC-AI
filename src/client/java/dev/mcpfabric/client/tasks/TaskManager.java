package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * Runs at most one goal-oriented {@link ClientTask} at a time.
 *
 * <p>{@link #submit} and {@link #tick} run on the game thread; {@link #await} is meant to be called
 * from an RPC worker thread and simply waits for the active task to settle, so the game keeps
 * ticking underneath.
 */
public final class TaskManager {
	private static final TaskManager INSTANCE = new TaskManager();

	public static TaskManager get() {
		return INSTANCE;
	}

	private TaskManager() {}

	private volatile ClientTask current;

	/** Submit on the game thread. Returns false if another task is still running. */
	public synchronized boolean submit(ClientTask task) {
		if (current != null && !current.isDone()) return false;
		current = task;
		task.onStart(Minecraft.getInstance());
		return true;
	}

	public ClientTask current() {
		return current;
	}

	/** Drive the active task; called from the client tick (game thread). */
	public void tick(Minecraft mc) {
		ClientTask t = current;
		if (t == null || t.isDone()) return;
		try {
			t.tick(mc);
		} catch (Throwable e) {
			t.fail("error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
		}
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
		if (t == null) return idle("idle");
		JsonObject o = t.result();
		o.addProperty("active", !t.isDone());
		o.addProperty("task", t.getClass().getSimpleName());
		return o;
	}

	private static JsonObject idle(String detail) {
		JsonObject o = new JsonObject();
		o.addProperty("state", "idle");
		o.addProperty("detail", detail);
		o.addProperty("active", false);
		return o;
	}
}
