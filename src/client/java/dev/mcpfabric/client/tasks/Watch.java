package dev.mcpfabric.client.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The stream a driver can wait on.
 *
 * <p>The mod cannot wake the caller. MCP is a pull channel: a model that is not generating a call is not
 * watching anything, so no amount of cleverness inside the game closes that gap — the wake-up has to live
 * in whatever drives the caller. What that driver needs, though, is not one more endpoint to poll; it is
 * one it can <em>block on</em>. That is this: a numbered log of the moments worth a turn, and a call that
 * returns the instant one of them lands.
 *
 * <p>Everything recorded here is a transition rather than a level, because a level is not a reason to
 * wake anybody: "health is 20" is true forever, "health fell 18 → 4" happened once. So the entries are
 * things starting or stopping — an anomaly appearing or clearing, a task settling or being taken over, a
 * rule firing, an incident opening or being answered — each with the sequence number, the age and a
 * severity the driver can filter on.
 *
 * <p>Nothing here decides whether a wake-up is justified. It reports what happened and how long ago; the
 * thresholds live in the caller's filter ({@code sinceSeq}, {@code kinds}, {@code minSeverity}) or in the
 * driver's own policy. The mod does not have an opinion about what matters, and this is not the place it
 * acquires one.
 */
public final class Watch {
	/** Long enough for a session's worth of history, short enough that it is not a leak. */
	private static final int CAPACITY = 200;
	/** How long {@code await} will block for, at most. Keeps a stuck driver from holding a thread for ever. */
	private static final long MAX_WAIT_MS = 120_000L;

	private static final Deque<Entry> LOG = new ArrayDeque<>();
	private static long seq;
	/** Woken whenever something is recorded; see {@link #await}. */
	private static final Object LOCK = new Object();

	private record Entry(long seq, long atMs, String kind, String text, JsonObject detail, int severity) {}

	private Watch() {}

	// --- recording (game thread) --------------------------------------------------------------------

	/** Record a transition. Cheap by design: it is called from the tick, and it must never be the reason a tick is slow. */
	public static void record(String kind, String text, JsonObject detail, int severity) {
		Entry e;
		synchronized (LOCK) {
			e = new Entry(++seq, System.currentTimeMillis(), kind, text == null ? "" : text,
					detail == null ? null : detail.deepCopy(), severity);
			LOG.addLast(e);
			while (LOG.size() > CAPACITY) LOG.removeFirst();
			LOCK.notifyAll(); // a driver may be waiting on exactly this
		}
		EventFeed.emit(kind, entryJson(e));
	}

	/** Convenience for the common case: no structured detail, ordinary importance. */
	public static void record(String kind, String text) {
		record(kind, text, null, 1);
	}

	public static long seq() {
		synchronized (LOCK) {
			return seq;
		}
	}

	// --- reading (worker threads) -------------------------------------------------------------------

	/** Everything after {@code sinceSeq} that matches, oldest first. Never blocks. */
	public static JsonObject pending(long sinceSeq, String[] kinds, int minSeverity, int limit) {
		JsonArray hits;
		long now;
		synchronized (LOCK) {
			hits = matches(sinceSeq, kinds, minSeverity, limit);
			now = seq;
		}
		JsonObject o = new JsonObject();
		o.add("transitions", hits);
		o.addProperty("count", hits.size());
		o.addProperty("seq", now);
		o.addProperty("since", sinceSeq);
		o.addProperty("note", hits.size() == 0
				? "Nothing has happened since seq " + sinceSeq + ". A driver watching for a turn should "
						+ "block in watch.wait rather than poll this: a level is not a reason to wake anybody, "
						+ "and a transition is."
				: hits.size() + " transition(s) since seq " + sinceSeq + ". Each carries kind, age and "
						+ "severity — whether any of them justifies a turn is your call, not the mod's.");
		return o;
	}

	/**
	 * Block until a matching transition lands, or until the timeout. This is the wake-up: a driver calls
	 * it once and gets a turn the moment something happens, instead of polling and being late.
	 *
	 * <p>It blocks on a monitor rather than touching the game, so it runs on whatever thread called it —
	 * deliberately not the game thread, which is also why it is never wrapped in {@code ClientMc.call}.
	 */
	public static JsonObject await(long sinceSeq, long timeoutMs, String[] kinds, int minSeverity) {
		long deadline = System.currentTimeMillis() + Math.max(0L, Math.min(MAX_WAIT_MS, timeoutMs));
		synchronized (LOCK) {
			while (true) {
				JsonArray hits = matches(sinceSeq, kinds, minSeverity, 20);
				if (hits.size() > 0) return answer(true, hits, sinceSeq, timeoutMs);
				long left = deadline - System.currentTimeMillis();
				if (left <= 0L) return answer(false, hits, sinceSeq, timeoutMs);
				try {
					LOCK.wait(Math.min(left, 500L));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return answer(false, hits, sinceSeq, timeoutMs);
				}
			}
		}
	}

	private static JsonObject answer(boolean woke, JsonArray hits, long sinceSeq, long askedMs) {
		JsonObject o = new JsonObject();
		o.addProperty("wake", woke);
		o.add("transitions", hits);
		o.addProperty("count", hits.size());
		o.addProperty("seq", seq());
		o.addProperty("since", sinceSeq);
		o.addProperty("waitedMs", askedMs);
		o.addProperty("note", woke
				? "Something worth a turn happened. Read what you need in ONE batched call (action.status + "
						+ "action.observe{sinceSeq} + latency.stats), decide, act — then block here again. "
						+ "Reading is not answering."
				: "Nothing matching arrived within " + askedMs + "ms. The world has been quiet: that is "
						+ "information too, and the next call should block again rather than poll.");
		return o;
	}

	/** The index: how far along the sequence is and what kinds have been seen recently. */
	public static JsonObject status() {
		JsonArray kinds = new JsonArray();
		synchronized (LOCK) {
			JsonArray last = matches(0L, null, 0, 20);
			for (int i = 0; i < last.size(); i++) kinds.add(last.get(i).getAsJsonObject().get("kind").getAsString());
		}
		JsonObject o = new JsonObject();
		o.addProperty("seq", seq());
		o.addProperty("capacity", CAPACITY);
		o.add("kindsRecent", kinds);
		o.addProperty("kinds", "anomaly (appeared/cleared), task (started/settled/parked), rule (fired), "
				+ "incident (opened/answered), plan (parked/resumed). Severity is the anomaly's own 0-2, or "
				+ "1 for ordinary transitions.");
		o.addProperty("note", "watch.wait {sinceSeq, timeoutMs} blocks until a transition lands; "
				+ "watch.pending reads what has already happened. The mod decides neither.");
		return o;
	}

	private static JsonArray matches(long sinceSeq, String[] kinds, int minSeverity, int limit) {
		JsonArray out = new JsonArray();
		for (Entry e : LOG) {
			if (e.seq() <= sinceSeq) continue;
			if (e.severity() < minSeverity) continue;
			if (kinds != null && kinds.length > 0 && !kindAllowed(kinds, e.kind())) continue;
			out.add(entryJson(e));
			if (out.size() >= limit) break;
		}
		return out;
	}

	/** One entry as the JSON a caller (or an SSE subscriber) sees. */
	private static JsonObject entryJson(Entry e) {
		JsonObject o = new JsonObject();
		o.addProperty("seq", e.seq());
		o.addProperty("agoMs", System.currentTimeMillis() - e.atMs());
		o.addProperty("kind", e.kind());
		o.addProperty("text", e.text());
		o.addProperty("severity", e.severity());
		if (e.detail() != null) o.add("detail", e.detail());
		return o;
	}

	private static boolean kindAllowed(String[] kinds, String kind) {
		for (String k : kinds) {
			if (k != null && k.equalsIgnoreCase(kind)) return true;
		}
		return false;
	}
}
