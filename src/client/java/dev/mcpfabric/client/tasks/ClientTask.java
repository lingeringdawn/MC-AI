package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;

/**
 * A goal-oriented, multi-tick client action.
 *
 * <p>Tasks are driven from the client tick on the game thread ({@link #tick(Minecraft)}) and publish
 * a settled {@link #result()} the instant they finish. That lets an RPC caller block until the whole
 * behaviour is actually done ("chop this block", "walk there") instead of polling low-level state
 * after every keystroke — the single biggest difference between driving inputs and playing a game.
 *
 * <p>Threading: {@code onStart}/{@code tick}/{@code onCancel} run on the game thread; {@code isDone}
 * and {@code result} are safe to read from the RPC worker thread.
 */
public abstract class ClientTask {
	private final long startMs = System.currentTimeMillis();
	private long deadlineMs = startMs + 30_000L;
	private volatile ClientTaskStatus status = ClientTaskStatus.RUNNING;
	private volatile JsonObject result;

	/** Bound the task's lifetime (from now). */
	public final void setDeadline(long timeoutMs) {
		this.deadlineMs = System.currentTimeMillis() + Math.max(1L, timeoutMs);
	}

	protected final boolean expired() {
		return System.currentTimeMillis() > deadlineMs;
	}

	protected final long remainingMs() {
		return Math.max(0L, deadlineMs - System.currentTimeMillis());
	}

	public final boolean isDone() {
		return status != ClientTaskStatus.RUNNING;
	}

	public ClientTaskStatus status() {
		return status;
	}

	public JsonObject result() {
		JsonObject r = result;
		return r != null ? r : new JsonObject();
	}

	/** Called once, on the game thread, when the task is submitted. */
	public void onStart(Minecraft mc) {}

	/** Called every client tick, on the game thread, while the task is running. */
	public abstract void tick(Minecraft mc);

	/** Called on the game thread when the task is cancelled. */
	public void onCancel(Minecraft mc) {}

	// --- completion helpers (game thread) ----------------------------------------------------

	protected final void done(String detail) {
		settle(ClientTaskStatus.DONE, detail, null);
	}

	protected final void done(String detail, JsonObject extra) {
		settle(ClientTaskStatus.DONE, detail, extra);
	}

	protected final void failed(String detail) {
		settle(ClientTaskStatus.FAILED, detail, null);
	}

	protected final void failed(String detail, JsonObject extra) {
		settle(ClientTaskStatus.FAILED, detail, extra);
	}

	/** Force-settle from outside (e.g. the tick loop catching a throwable). */
	public final void fail(String detail) {
		if (!isDone()) settle(ClientTaskStatus.FAILED, detail, null);
	}

	private void settle(ClientTaskStatus st, String detail, JsonObject extra) {
		JsonObject o = new JsonObject();
		o.addProperty("state", st.name().toLowerCase());
		o.addProperty("detail", detail);
		o.addProperty("elapsedMs", System.currentTimeMillis() - startMs);
		if (extra != null) {
			for (var e : extra.entrySet()) o.add(e.getKey(), e.getValue());
		}
		this.result = o;
		this.status = st;
	}
}
