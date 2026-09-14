package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.Humanizer;
import net.minecraft.client.Minecraft;

import java.util.Random;

/**
 * Executes the remedy the observation recommends — the "react" half of the anomaly layer.
 *
 * <p>The reporting half (in {@link TaskObserver}) already turns a bad state into a ready-to-run call:
 * tool name, arguments, offending entity included. This class is what runs it, and it exists so that
 * reacting is <em>one</em> request rather than a read, a decision and an argument-hunt while the player
 * drowns.
 *
 * <p>Two ways in:
 *
 * <ul>
 *   <li>{@link #requestReaction} — for {@code action.react}. The caller asks to deal with whatever is
 *       wrong; the remedy is queued behind a human reaction time and then run.</li>
 *   <li>{@link #tick} with {@code autoHandleAnomalies} on — the same thing without being asked.</li>
 * </ul>
 *
 * <p>The reaction delay is the point of the first path. Firing on the exact tick the state changed is
 * the clearest machine tell there is; a person takes a beat. 150–350ms costs nothing that matters and
 * is what makes the bot look like it noticed rather than like it computed.
 *
 * <p>Neither path interrupts a task the caller asked for: a remedy only fires in the gaps between
 * actions, and each episode fires once instead of re-submitting every tick.
 */
public final class AnomalyResponder {
	private static final AnomalyResponder INSTANCE = new AnomalyResponder();

	public static AnomalyResponder get() {
		return INSTANCE;
	}

	/** Budget handed to a remedy: all of these are meant to settle in a second or two. */
	private static final long BUDGET_MS = 20_000L;
	/** Reaction time before a requested remedy goes out, in ticks (150–350ms at 20 ticks/s). */
	private static final int REACTION_MIN_TICKS = 3;
	private static final int REACTION_MAX_TICKS = 7;
	/** Fallback withdrawal distance when the remedy carries no argument of its own. */
	private static final double RETREAT_DISTANCE = 8.0;

	private final Random rng = new Random();

	/** The anomaly already handled by the automatic path, so one episode fires once. */
	private String handling = "";
	/** A reaction asked for through {@code action.react}, waiting out its reaction time. */
	private String pendingTool = "";
	private JsonObject pendingArgs = new JsonObject();
	private int countdown;

	private AnomalyResponder() {}

	/**
	 * Queue the recommended remedy behind a human reaction time. Returns what was queued, or null when
	 * the observation recommends nothing — in which case there is nothing to react to.
	 */
	public synchronized JsonObject requestReaction(Minecraft mc) {
		JsonObject next = TaskManager.get().observer().nextAction();
		if (next == null || !next.has("tool")) return null;
		pendingTool = next.get("tool").getAsString();
		pendingArgs = next.has("args") && next.get("args").isJsonObject()
				? next.getAsJsonObject("args").deepCopy()
				: new JsonObject();
		countdown = Humanizer.reactionTicks(rng, REACTION_MIN_TICKS, REACTION_MAX_TICKS);

		JsonObject out = new JsonObject();
		out.addProperty("queued", pendingTool);
		out.add("args", pendingArgs.deepCopy());
		out.addProperty("inTicks", countdown);
		out.addProperty("because", next.has("because") ? next.get("because").getAsString() : "");
		return out;
	}

	/** True while a requested reaction is still waiting out its reaction time. */
	public synchronized boolean reactionPending() {
		return !pendingTool.isEmpty();
	}

	public void tick(Minecraft mc, boolean enabled) {
		if (tickPending(mc)) return;
		if (!enabled) {
			handling = "";
			return;
		}
		TaskObserver observer = TaskManager.get().observer();
		JsonObject next = observer.nextAction();
		if (next == null) {
			handling = "";
			return;
		}
		String id = observer.topAnomalyId();
		if (id.equals(handling)) return;
		if (!execute(mc, next)) return;
		handling = id;
		observer.note("auto: " + next.get("tool").getAsString() + " for " + id);
	}

	/** Count the queued reaction down and run it. Returns true while it still owns the tick. */
	private boolean tickPending(Minecraft mc) {
		if (pendingTool.isEmpty()) return false;
		if (countdown > 0) {
			countdown--;
			return true;
		}
		JsonObject call = new JsonObject();
		call.addProperty("tool", pendingTool);
		call.add("args", pendingArgs);
		pendingTool = "";
		pendingArgs = new JsonObject();
		execute(mc, call);
		return true;
	}

	/** Run a recommended call. Shared by the queued reaction and the automatic handler. */
	private boolean execute(Minecraft mc, JsonObject call) {
		if (!call.has("tool")) return false;
		String tool = call.get("tool").getAsString();
		JsonObject args = call.has("args") && call.get("args").isJsonObject()
				? call.getAsJsonObject("args")
				: new JsonObject();
		// Never cut across a task the caller asked for; the remedy waits for the next gap.
		JsonObject status = TaskManager.get().status();
		if (status.has("active") && status.get("active").getAsBoolean()) return false;

		if ("action_cancel".equals(tool)) {
			TaskManager.get().cancel(mc);
			return true;
		}
		ClientTask task = build(tool, args);
		if (task == null) return false;
		task.setDeadline(BUDGET_MS);
		return TaskManager.get().submit(task);
	}

	private ClientTask build(String tool, JsonObject args) {
		switch (tool) {
			case "surface":
				return new SurfaceTask();
			case "water_bucket_save":
				return new MlgTask();
			case "eat":
				return new EatTask();
			case "retreat_from":
				return retreat(args);
			default:
				return null;
		}
	}

	/** Back away from the entity the anomaly named, or from the spot it gave. */
	private ClientTask retreat(JsonObject args) {
		if (args.has("uuid")) {
			try {
				return new RetreatTask(java.util.UUID.fromString(args.get("uuid").getAsString()),
						RETREAT_DISTANCE);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}
		if (args.has("x") && args.has("y") && args.has("z")) {
			return new RetreatTask(args.get("x").getAsDouble(), args.get("y").getAsDouble(),
					args.get("z").getAsDouble(), RETREAT_DISTANCE);
		}
		return null;
	}
}
