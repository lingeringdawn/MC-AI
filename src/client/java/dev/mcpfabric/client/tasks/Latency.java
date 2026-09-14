package dev.mcpfabric.client.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * How quickly the caller reacts: from something becoming visible to the first action taken about it.
 *
 * <p>This is the number that says whether operating is getting more fluid, and it is the only one that
 * cannot be argued with. "Behaviour is smoother" is a claim; "the last anomaly was noticed at seq 8121 and
 * answered 340ms later, the median over the last twenty is 900ms, two incidents are still open" is a
 * measurement. It is also the thing that learning should move: as lessons accumulate and the right
 * sequence of steps becomes known, seeing something and doing something about it get closer together.
 *
 * <p>Two latencies are kept, because they fail differently:
 * <ul>
 *   <li><b>incident</b> — from an anomaly first being reported (a hit taken, air running down, a mob in
 *       reach) to the first action submitted afterwards. Long here means the bot sat in trouble.</li>
 *   <li><b>loop</b> — from a read (observe/status/vision/player/entities/world) to the next action. Long
 *       here means the caller is deliberating, not that the world is dangerous.</li>
 * </ul>
 *
 * <p>Only actions count as answers, and "action" is anything that changes the player's state: a module, a
 * plan, a resume, a cancel. A read is not an answer — reading while nothing is done is exactly the
 * behaviour this is meant to expose. Nothing here decides anything or reports into the event stream: it is
 * a reading, in the snapshot and on request, the same way the rest of the observation is.
 */
public final class Latency {
	/** Rolling window per latency. Twenty samples is short enough to notice a change, long enough to be a trend. */
	private static final int WINDOW = 20;
	/** An incident left this long without an answer is counted as missed, and kept visible. */
	private static final long OVERDUE_MS = 5000L;

	private static final Deque<Long> INCIDENTS = new ArrayDeque<>();
	private static final Deque<Long> LOOPS = new ArrayDeque<>();

	private static long sightedAtMs = -1L;
	private static String sightedWhat = "";
	private static long lastReadAtMs = -1L;
	private static long lastActionAtMs = -1L;
	private static int incidentsTotal;
	private static int actionsTotal;
	private static long worstIncidentMs;

	private Latency() {}

	/** Something worth answering has appeared. Only one incident is open at a time: whatever is worst. */
	public static synchronized void sighted(String what) {
		if (sightedAtMs >= 0L) return;
		sightedAtMs = System.currentTimeMillis();
		sightedWhat = what == null ? "" : what;
		incidentsTotal++;
		Watch.record("incident", "opened: " + sightedWhat, null, 2);
	}

	/** A call arrived. Reads mark the clock; state-changing calls answer whatever was open. */
	public static synchronized void called(String method) {
		if (method == null) return;
		long now = System.currentTimeMillis();
		if (isRead(method)) {
			lastReadAtMs = now;
			return;
		}
		if (!isAction(method)) return;
		lastActionAtMs = now;
		actionsTotal++;
		if (lastReadAtMs > 0L) push(LOOPS, now - lastReadAtMs);
		if (sightedAtMs > 0L) {
			long ms = now - sightedAtMs;
			push(INCIDENTS, ms);
			worstIncidentMs = Math.max(worstIncidentMs, ms);
			Watch.record("incident", "answered after " + ms + "ms: " + sightedWhat, null, 1);
			sightedAtMs = -1L;
			sightedWhat = "";
		}
	}

	/**
	 * The reading, small enough to ride along in every observation sample: what is open, how long it has
	 * been open, and the recent medians. A caller watching the stream sees its own reaction speed without
	 * asking for it.
	 */
	public static synchronized JsonObject snapshot() {
		JsonObject o = new JsonObject();
		long now = System.currentTimeMillis();
		if (sightedAtMs > 0L) {
			o.addProperty("openIncident", sightedWhat);
			o.addProperty("openForMs", now - sightedAtMs);
		}
		o.addProperty("incidentsAnswered", incidentsTotal - (sightedAtMs > 0L ? 1 : 0));
		o.addProperty("incidentMedianMs", median(INCIDENTS));
		o.addProperty("loopMedianMs", median(LOOPS));
		// The two windows above only ever measure between calls, which flatters them: the time a caller
		// spends thinking and writing is invisible to them. These two do not care where the gap is.
		if (lastActionAtMs > 0L) o.addProperty("sinceLastActionMs", now - lastActionAtMs);
		if (lastReadAtMs > 0L) o.addProperty("sinceLastReadMs", now - lastReadAtMs);
		return o;
	}

	/** The fuller report: both windows in full, totals, and the worst case — for judging progress. */
	public static synchronized JsonObject stats() {
		JsonObject o = new JsonObject();
		o.addProperty("incidentMedianMs", median(INCIDENTS));
		o.addProperty("incidentMaxMs", max(INCIDENTS));
		o.addProperty("loopMedianMs", median(LOOPS));
		o.addProperty("loopMaxMs", max(LOOPS));
		o.add("incidentWindow", window(INCIDENTS));
		o.add("loopWindow", window(LOOPS));
		o.addProperty("incidentsTotal", incidentsTotal);
		o.addProperty("actionsTotal", actionsTotal);
		o.addProperty("worstIncidentMs", worstIncidentMs);
		if (sightedAtMs > 0L) {
			o.addProperty("openIncident", sightedWhat);
			o.addProperty("openForMs", System.currentTimeMillis() - sightedAtMs);
			if (System.currentTimeMillis() - sightedAtMs > OVERDUE_MS) {
				o.addProperty("overdue", true);
				o.addProperty("overdueNote", "This incident has been open longer than " + (OVERDUE_MS / 1000L)
						+ "s with no action submitted. Reading is not answering.");
			}
		}
		o.addProperty("whatCounts", "incident = from an anomaly first being reported to the first action "
				+ "afterwards; loop = from a read to the next action. Reads are observe/status/vision/"
				+ "player/entities/world; actions are the action.* calls that change something (a read "
				+ "disguised as action.status is a read).");
		return o;
	}

	/** For the log/console: a one-line summary, or null when there is nothing worth saying. */
	public static synchronized String describe() {
		if (sightedAtMs <= 0L) return null;
		return "incident open " + (System.currentTimeMillis() - sightedAtMs) + "ms: " + sightedWhat;
	}

	// --- classification -----------------------------------------------------------------------------

	private static boolean isRead(String method) {
		return method.equals("action.observe") || method.equals("action.status") || method.equals("action.help")
				|| method.startsWith("vision.") || method.startsWith("player.get") || method.startsWith("entities.")
				|| method.startsWith("world.") || method.startsWith("info.") || method.startsWith("nav.status")
				|| method.startsWith("ui.state") || method.startsWith("memory.") || method.startsWith("latency.")
				|| method.startsWith("plan.status") || method.startsWith("rules.list") || method.startsWith("chat.");
	}

	private static boolean isAction(String method) {
		// Everything under action.* that is not a read, plus the control calls that change what the bot is
		// doing. The raw input channel counts too: steering with control.setInput changes the player's state
		// just as much as a module does, and leaving it out would have flattered the reading.
		if (method.startsWith("action.") && !isRead(method)) return true;
		if (method.startsWith("plan.resume") || method.startsWith("plan.discard")
				|| method.startsWith("rules.set") || method.startsWith("rules.clear")) {
			return true;
		}
		return method.startsWith("interact.") || method.startsWith("inventory.selectHotbar")
				|| method.startsWith("inventory.dropSlot") || method.startsWith("inventory.swapSlots")
				|| method.startsWith("ui.clickSlot") || method.startsWith("control.setInput")
				|| method.startsWith("control.stop") || method.startsWith("control.jumpOnce")
				|| method.startsWith("control.look") || method.startsWith("control.startUsing")
				|| method.startsWith("control.stopUsing") || method.startsWith("control.respawn")
				|| method.startsWith("nav.pathTo") || method.startsWith("nav.stop");
	}

	// --- windows ------------------------------------------------------------------------------------

	private static void push(Deque<Long> window, long ms) {
		window.addLast(Math.max(0L, ms));
		while (window.size() > WINDOW) window.removeFirst();
	}

	/** Median rather than mean: one two-minute think should not hide twenty fast reactions. */
	private static long median(Deque<Long> window) {
		if (window.isEmpty()) return -1L;
		long[] a = new long[window.size()];
		int i = 0;
		for (long v : window) a[i++] = v;
		java.util.Arrays.sort(a);
		return a[a.length / 2];
	}

	private static long max(Deque<Long> window) {
		long best = -1L;
		for (long v : window) best = Math.max(best, v);
		return best;
	}

	private static JsonArray window(Deque<Long> w) {
		JsonArray a = new JsonArray();
		for (long v : w) a.add(v);
		return a;
	}
}
