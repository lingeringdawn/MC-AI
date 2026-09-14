package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;

/**
 * One interrupted plan, kept so it can be picked up again.
 *
 * <p>Being superseded used to be the end of a plan: the caller's next instruction cancelled it and the
 * steps it had already got through were gone. That is the wrong default. An instruction is usually a
 * correction — "stop there, come back and eat first" — not a statement that the other eight steps are
 * worthless, and re-issuing them by hand costs the caller the whole flow again, from the top, in a world
 * that has moved on.
 *
 * <p>So a plan that is taken over or cancelled leaves its snapshot here instead of vanishing, and resuming
 * re-enters at the step it was interrupted on. That step may have been half done (a dig that got three
 * ticks into the block); redoing it is the honest reading of "carry on", which is why the cursor marks
 * the step to <em>run</em>, not the last one that finished.
 *
 * <p>One slot, deliberately: this is "the plan I was in the middle of", not a task queue. Resuming is
 * explicit — nothing here starts a plan on its own, because deciding to act is the caller's job.
 */
public final class ParkedPlan {
	private ParkedPlan() {}

	private static JsonObject snapshot;
	private static String describe = "";
	private static String pausedBy = "";
	private static long parkedAtMs;

	/** Keep a plan's own resume descriptor. Called from the game thread when a task is taken over. */
	public static synchronized void put(JsonObject snap, String pausedBy, String described) {
		if (snap == null) return;
		snapshot = snap.deepCopy();
		ParkedPlan.pausedBy = pausedBy == null ? "" : pausedBy;
		ParkedPlan.describe = described == null ? "" : described;
		parkedAtMs = System.currentTimeMillis();
	}

	/** The descriptor to resume from, or null when nothing is parked. */
	public static synchronized JsonObject get() {
		return snapshot == null ? null : snapshot.deepCopy();
	}

	public static synchronized boolean has() {
		return snapshot != null;
	}

	/** What is parked and where it stopped, or an empty answer. Never throws: it is a read. */
	public static synchronized JsonObject status() {
		JsonObject o = new JsonObject();
		if (snapshot == null) {
			o.addProperty("parked", false);
			o.addProperty("note", "No plan is parked. A plan parks itself when another instruction takes "
					+ "over while it runs, or when it is cancelled.");
			return o;
		}
		o.addProperty("parked", true);
		o.addProperty("describe", describe);
		o.addProperty("pausedBy", pausedBy);
		o.addProperty("parkedAgoMs", System.currentTimeMillis() - parkedAtMs);
		o.addProperty("nextStep", snapshot.has("index") ? snapshot.get("index").getAsInt() : 0);
		o.addProperty("total", snapshot.has("steps") ? snapshot.getAsJsonArray("steps").size() : 0);
		if (snapshot.has("log")) o.add("steps", snapshot.getAsJsonArray("log"));
		o.addProperty("note", "plan_resume carries on from step " + o.get("nextStep").getAsInt()
				+ " (that step is redone from the start — it may have been half finished). plan_discard drops it.");
		return o;
	}

	/** Drop whatever is parked. */
	public static synchronized JsonObject clear() {
		boolean had = snapshot != null;
		String was = describe;
		snapshot = null;
		describe = "";
		pausedBy = "";
		parkedAtMs = 0L;
		JsonObject o = new JsonObject();
		o.addProperty("discarded", had);
		if (had) o.addProperty("was", was);
		return o;
	}
}
