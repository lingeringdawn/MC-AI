package dev.mcpfabric.client.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.Vision;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Live situational awareness for a running task: sampled every client tick, so a caller that is
 * blocked on (or polling) a long action can still see the world changing underneath it — health,
 * nearby threats, drops on the ground, what the crosshair is on, the task's own progress, and a
 * rolling log of notable events.
 *
 * <p>The point is that "mine this tree" is not an opaque black box: while it runs you can watch the
 * log grow ("mined 3/5", "threat: zombie 4.2 blocks"), see health drop, and notice danger early
 * enough to cancel. {@link #danger()} raises a level when the situation turns bad, which lets a
 * caller abort a task that has stopped making sense.
 */
public final class TaskObserver {
	/** Samples older than this are dropped from the rolling log. */
	private static final int LOG_CAPACITY = 60;
	/** Entities within this radius count as "nearby". */
	private static final double THREAT_RADIUS = 8.0;
	/** A mob closer than this is an immediate danger rather than a distant one. */
	private static final double THREAT_CLOSE = 3.5;
	private static final double DROP_RADIUS = 12.0;
	/** Health at/below this fraction of max raises the danger level. */
	private static final float LOW_HEALTH_FRACTION = 0.35F;
	/**
	 * Air at/below this is reported as an emergency. The mod deliberately has no drowning reflex of its
	 * own — it reports and nothing else — so the only thing standing between the bot and a lungful of
	 * water is the caller noticing this number (or a rule it wrote against it).
	 */
	private static final int AIR_ALERT = 120;
	/** Air at which being under water becomes urgent, i.e. worth a remedy rather than a warning. */
	private static final int DROWNING_AIR = 240;
	/** Air at which being under water is first worth mentioning at all. */
	private static final int AIR_LOW = 285;
	/** Fall distance already accumulated that is enough to hurt: 4 blocks is 1 damage, and rising. */
	private static final double HARMFUL_FALL = 4.0;
	/** A walk that has made no progress for this many ticks is wedged, not walking. */
	private static final int STUCK_TICKS = 25;
	/**
	 * How long a cleared anomaly stays in the snapshot (3s). Long enough for a caller polling a couple
	 * of times a second to see it, short enough that it is not mistaken for something still happening —
	 * a stale entry is marked, and a live one always outranks it.
	 */
	private static final int ANOMALY_STICKY_TICKS = 60;
	private final Deque<JsonObject> log = new ArrayDeque<>();
	private final List<String> logKeys = new ArrayList<>();

	private long startedMs = System.currentTimeMillis();
	private String taskName = "";
	private String described = "";
	private int ticks;

	/** When off, the per-tick sampling is skipped entirely (callers still get the last snapshot). */
	private volatile boolean enabled = true;

	// latest sample
	private JsonObject snapshot = new JsonObject();
	/** Position in the stream: every sample counts, so a caller can ask for "what since I last read". */
	private long seq;
	/** Sequence number shared by log lines and change events, so one cursor reads both. */
	private long eventSeq;
	/**
	 * Changes worth reporting on their own — air ticking away, food dropping, a heal, a step that moved
	 * the player somewhere new. Kept in a ring buffer rather than only as a level, so a caller reading a
	 * couple of times a second can never miss one however briefly it was true.
	 */
	private final Deque<JsonObject> events = new ArrayDeque<>();
	private static final int EVENT_CAPACITY = 80;
	/** Previous values, so a change is reported as a change rather than only as a state. */
	private float prevAir = 300.0F;
	private int prevFoodSeen = -1;
	private float prevHealthSeen = -1.0F;
	/** Which 8-block cell the player was in, so walking somewhere new is an event, not every step. */
	private int prevPosKey = Integer.MIN_VALUE;
	private int threatCount;
	private double closestThreat = Double.MAX_VALUE;
	private String closestThreatName = "";
	private int dropCount;
	private float health = 20.0F;
	private float maxHealth = 20.0F;
	/** Air left in the current lungful; the caller's cue to look at the water it is standing in. */
	private int air = 300;

	// --- anomaly detection: what is wrong with the player right now --------------------------
	/** Health at the previous sample, so a hit is visible as a delta rather than only as a value. */
	private float prevHealth = 20.0F;
	/** Where the player was the last time they actually moved, to spot being wedged. */
	private double lastMoveX = Double.NaN;
	private double lastMoveZ = Double.NaN;
	private int stillTicks;
	private boolean moving;
	/** Anomalies found this tick, keyed by id; each carries severity, the evidence, and where it came from. */
	private JsonObject anomalies = new JsonObject();
	/** The worst one this tick, or null. */
	private JsonObject worst;
	private String worstId = "";
	/** Ids seen last tick, used to log appearance and clearing rather than repeating every tick. */
	private Set<String> knownAnomalies = new LinkedHashSet<>();
	/** When each anomaly was first seen, and its last description, so a one-tick event stays visible. */
	private final Map<String, Long> anomalySince = new HashMap<>();
	private final Map<String, JsonObject> anomalyLast = new HashMap<>();

	/** Begin observing a task; resets the log and the sample baseline. */
	public void begin(ClientTask task) {
		startedMs = System.currentTimeMillis();
		if (task == null) return;
		taskName = task.getClass().getSimpleName();
		described = task.describe();
		ticks = 0;
		log.clear();
		logKeys.clear();
		threatCount = 0;
		closestThreat = Double.MAX_VALUE;
		closestThreatName = "";
		dropCount = 0;
		snapshot = new JsonObject();
		note("started " + described);
	}

	/** Take one sample. Runs on the game thread from the task tick. */
	public void sample(Minecraft mc, ClientTask task) {
		taskName = task.getClass().getSimpleName();
		described = task.describe();
		sample0(mc, task.progress());
	}

	/**
	 * Keep the world observable while no task is running, so "what is around me right now" works the
	 * same whether the player is idle or busy.
	 */
	public void sampleIdle(Minecraft mc) {
		taskName = "";
		described = "";
		sample0(mc, null);
	}

	private void sample0(Minecraft mc, JsonObject taskProgress) {
		if (!enabled) return;
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) return;
		ticks++;

		JsonObject o = new JsonObject();
		o.addProperty("tick", ticks);
		o.addProperty("elapsedMs", System.currentTimeMillis() - startedMs);
		o.addProperty("task", taskName);
		o.addProperty("detail", described);

		// --- vitals ---------------------------------------------------------
		health = p.getHealth();
		maxHealth = p.getMaxHealth();
		air = p.getAirSupply();
		o.addProperty("health", health);
		o.addProperty("maxHealth", maxHealth);
		o.addProperty("food", p.getFoodData().getFoodLevel());
		o.addProperty("air", air);
		o.addProperty("eyesUnderWater", p.isUnderWater());
		o.addProperty("onGround", p.onGround());
		o.addProperty("inWater", p.isInWater());
		o.addProperty("dead", p.isDeadOrDying() || p.getHealth() <= 0.0F);
		o.addProperty("seq", ++seq);

		// --- position / motion ----------------------------------------------
		JsonObject pos = new JsonObject();
		pos.addProperty("x", round(p.getX()));
		pos.addProperty("y", round(p.getY()));
		pos.addProperty("z", round(p.getZ()));
		o.add("pos", pos);
		o.addProperty("moved", round(p.getDeltaMovement().horizontalDistance()));
		// The input channel the bot drives — held keys, clicks — is only live while the game window is
		// active and the mouse is grabbed: MouseHandler.grabMouse() returns immediately when the window is
		// not active, and vanilla's handleKeybinds then passes continueAttack(false), which skips mining
		// altogether. Reported here because its absence is indistinguishable from "the dig is stuck for no
		// reason", which is exactly how it looked until this was tracked down.
		o.addProperty("windowActive", mc.isWindowActive());
		o.addProperty("mouseGrabbed", mc.mouseHandler.isMouseGrabbed());

		// --- what the crosshair is on ---------------------------------------
		o.add("lookingAt", lookingAt(mc, level));

		// --- what the eye can see, every tick --------------------------------
		// Carried in the live sample on purpose: reading the world should not mean stopping to look, and
		// a caller deciding what to do next needs the same view the player has, not a chunk scan.
		o.add("visible", visibleSummary(mc));

		// --- how quickly the caller has been answering, live -----------------
		// Small enough to ride along every tick, and the one number that says whether operating is getting
		// more fluid: from something being reported to the first action taken about it.
		o.add("latency", Latency.snapshot());

		// --- threats ---------------------------------------------------------
		sampleThreats(p, level, o);

		// --- drops on the ground --------------------------------------------
		sampleDrops(p, level, o);

		// --- abnormal player states, live, each with the module that fixes it -------------------
		sampleAnomalies(p, level, o);

		// --- and the changes themselves, so the stream never has a gap -------------------------
		sampleChanges(p, o);

		// --- the task's own progress ----------------------------------------
		if (taskProgress != null && taskProgress.size() > 0) o.add("progress", taskProgress);

		// --- navigation state (shared by every walking task) ------------------
		JsonObject nav = BotController.get().statusJson();
		if (nav.has("active") && nav.get("active").getAsBoolean()) o.add("nav", nav);

		snapshot = o;
		raiseAlerts(p);
	}

	/**
	 * Read the player's condition every tick and report anything abnormal, with the evidence.
	 *
	 * <p>This is the part a health bar alone hides: drowning with air quietly ticking down, a fall
	 * already long enough to hurt, being on fire, freezing, wedged while a walk is running, being
	 * carried along by water you placed yourself. Every entry is a fact — what is wrong, how bad, the
	 * evidence, and where the thing that caused it is. There is deliberately no remedy: naming a module
	 * to call is a judgement, and the caller makes it (or writes a rule that does). The set is diffed
	 * against last tick, so the log shows a problem appearing and clearing instead of repeating.
	 */
	private void sampleAnomalies(LocalPlayer p, ClientLevel level, JsonObject now) {
		JsonObject found = new JsonObject();
		worst = null;
		float h = p.getHealth();
		int food = p.getFoodData().getFoodLevel();

		if (h <= 0.0F) {
			add(found, "dead", 2, "health=0", null);
		} else if (maxHealth > 0.0F && h <= maxHealth * LOW_HEALTH_FRACTION) {
			JsonObject threat = firstThreat(now);
			// Carry the attacker's UUID as well as its position, so the caller can see exactly which
			// entity is responsible rather than only roughly where it was.
			JsonObject at = null;
			if (threat != null && threat.has("pos")) {
				at = threat.getAsJsonObject("pos").deepCopy();
				if (threat.has("uuid")) at.addProperty("uuid", threat.get("uuid").getAsString());
			}
			add(found, "low_health", 2,
					"health=" + Math.round(h) + "/" + Math.round(maxHealth)
							+ (threat != null ? ", " + threat.get("name").getAsString() + " within "
							+ threat.get("distance").getAsString() + "b" : ""),
					at);
		}

		float lost = prevHealth - h;
		if (h > 0.0F && lost >= 0.5F) {
			// Say who did it, not merely that it happened. "Lost 2 health" leaves the caller unable to
			// tell a zombie in melee range from a fall off a ledge, and those want opposite answers — and
			// a caller that cannot see the attacker will keep restarting the task the attacker keeps
			// interrupting.
			Entity attacker = attackerOf(p);
			add(found, "taking_damage", attacker != null ? 2 : (lost >= 4.0F ? 2 : 1),
					(attacker == null
							? "took damage from the world, not a creature"
							: attacker.getName().getString() + " hit me, " + round(p.distanceTo(attacker))
									+ " blocks away")
							+ " — lost " + round(lost) + " health",
					attacker == null ? null : entityRef(attacker));
		}

		if (p.isUnderWater()) {
			// Two stages on purpose: air below the drowning line is urgent, air merely low is a warning
			// with time to think. Neither carries an action — how to get out is the caller's call.
			if (air < DROWNING_AIR) {
				add(found, "drowning", 2, "air=" + air + ", eyes under water", null);
			} else if (air < AIR_LOW) {
				add(found, "low_air", 1, "air=" + air + ", eyes under water", null);
			}
		}

		if (!p.onGround() && !p.isInWater() && !p.getAbilities().flying && !p.isFallFlying()) {
			double speed = Math.abs(p.getDeltaMovement().y);
			if (p.fallDistance >= HARMFUL_FALL) {
				add(found, "falling_hard", 2,
						"fallen " + round(p.fallDistance) + " blocks at " + round(speed) + "/tick", null);
			} else if (speed > 0.5) {
				add(found, "falling", 0, "airborne at " + round(speed) + "/tick", null);
			}
		}

		if (food <= 0) add(found, "starving", 1, "food=0", null);
		else if (food <= 6) add(found, "hungry", 1, "food=" + food, null);

		if (p.isOnFire()) add(found, "on_fire", 2, "burning", null);
		if (p.isInLava()) add(found, "in_lava", 2, "standing in lava", null);
		if (p.getTicksFrozen() > 0) {
			add(found, "freezing", 1, "frozen for " + p.getTicksFrozen() + " ticks", null);
		}
		if (p.isInWall()) add(found, "suffocating", 2, "inside a solid block", null);
		if (level != null && p.getY() < level.getMinY() + 1) {
			add(found, "below_world", 2, "y=" + round(p.getY()), null);
		}

		// Have we actually gone anywhere? A walk can be running while the player is pinned against
		// something, and "the task says navigating" is not the same as "the player is moving".
		double moved = Math.hypot(p.getX() - lastMoveX, p.getZ() - lastMoveZ);
		if (Double.isNaN(lastMoveX) || moved > 0.05) {
			stillTicks = 0;
			lastMoveX = p.getX();
			lastMoveZ = p.getZ();
		} else {
			stillTicks++;
		}
		JsonObject nav = BotController.get().statusJson();
		moving = nav.has("active") && nav.get("active").getAsBoolean();
		if (moving && stillTicks > STUCK_TICKS) {
			add(found, "stuck", 1, "walk running but no progress for " + stillTicks + " ticks", null);
		}

		// Moving with nothing asked for: a current, a piston, an explosion. Worth knowing about,
		// because it is exactly how a bot gets carried off the thing it just saved itself onto.
		double drift = p.getDeltaMovement().horizontalDistance();
		if (!moving && drift > 0.25) {
			add(found, "carried_along", 1, "drifting at " + round(drift) + "/tick with nothing asked", null);
		}

		// --- report -----------------------------------------------------------------------------
		// Transitions are logged from the raw detection, so the log stays an exact record.
		Set<String> raw = new LinkedHashSet<>(found.keySet());
		for (String id : raw) {
			if (knownAnomalies.contains(id)) continue;
			JsonObject a = found.getAsJsonObject(id);
			int severity = a.get("severity").getAsInt();
			note("anomaly: " + id + " (" + a.get("evidence").getAsString() + ")");
			// A transition, and therefore a candidate wake-up: something started. Recorded with its own
			// severity so a driver can filter to what it cares about; whether it is worth a turn is the
			// driver's call, not this class's.
			Watch.record("anomaly", id + ": " + a.get("evidence").getAsString(), null, severity);
			// This is also the moment the clock starts for the caller's reaction time: the instant the
			// problem became visible, not the instant it began.
			if (severity >= 1) Latency.sighted(id + ": " + a.get("evidence").getAsString());
		}
		for (String id : knownAnomalies) {
			if (!raw.contains(id)) {
				note("cleared: " + id);
				Watch.record("anomaly", "cleared: " + id, null, 1);
			}
		}
		knownAnomalies = raw;

		// --- but keep a just-cleared anomaly visible in the snapshot for a moment ----------------
		// Something that lasts a single tick — one hit taken, one stumble — would otherwise only ever
		// be reachable by reading the log, and a caller polling twice a second would miss exactly the
		// events it most needs to react to.
		for (String id : raw) {
			anomalySince.putIfAbsent(id, (long) ticks);
			anomalyLast.put(id, found.getAsJsonObject(id));
		}
		Set<String> report = new LinkedHashSet<>(raw);
		for (String id : new ArrayList<>(anomalyLast.keySet())) {
			if (raw.contains(id)) continue;
			Long since = anomalySince.get(id);
			if (since == null || ticks - since > ANOMALY_STICKY_TICKS) {
				anomalyLast.remove(id);
				anomalySince.remove(id);
				continue;
			}
			report.add(id);
		}

		JsonObject reported = new JsonObject();
		worst = null;
		worstId = "";
		for (String id : report) {
			boolean fresh = raw.contains(id);
			JsonObject a = fresh ? found.getAsJsonObject(id).deepCopy() : anomalyLast.get(id).deepCopy();
			if (!fresh) {
				a.addProperty("stale", true);
				a.addProperty("ageTicks", ticks - anomalySince.get(id));
			}
			reported.add(id, a);
			pickWorst(id, a, fresh);
		}
		anomalies = reported;
		now.add("anomalies", reported);
		now.addProperty("anomalyCount", reported.size());
		if (worst != null) now.addProperty("worstAnomaly", worstId);
		now.addProperty("summary", summary(report, h, food));

		prevHealth = h;
	}

	/**
	 * Keep the most urgent anomaly on top. A live one outranks a stale one whatever their severities:
	 * acting on something that has already stopped is worse than acting on something smaller that is
	 * still true.
	 */
	private void pickWorst(String id, JsonObject a, boolean fresh) {
		int severity = a.get("severity").getAsInt();
		if (worst == null) {
			worst = a;
			worstId = id;
			return;
		}
		boolean worstFresh = !worst.has("stale");
		if (fresh && !worstFresh) {
			worst = a;
			worstId = id;
			return;
		}
		if (fresh == worstFresh && severity > worst.get("severity").getAsInt()) {
			worst = a;
			worstId = id;
		}
	}

	/** One line to read at a glance, instead of assembling the situation from a dozen fields. */
	private String summary(Set<String> ids, float h, int food) {
		StringBuilder sb = new StringBuilder();
		sb.append("hp ").append(Math.round(h)).append('/').append(Math.round(maxHealth));
		sb.append(", food ").append(food).append(", air ").append(air);
		if (moving) sb.append(", walking");
		if (threatCount > 0) {
			sb.append(", ").append(threatCount).append(" hostile, nearest ")
					.append(round(closestThreat)).append('b');
		}
		if (dropCount > 0) sb.append(", ").append(dropCount).append(" drop(s)");
		if (!ids.isEmpty()) sb.append(" | ").append(String.join(", ", ids));
		return sb.toString();
	}

	/**
	 * Record one anomaly: what is wrong, how bad, the evidence for it, and where the thing that caused it
	 * is. Deliberately without a remedy: naming a module to call is a judgement, and the facts here are
	 * what a caller (or a rule it wrote) needs to make one itself.
	 */
	private void add(JsonObject into, String id, int severity, String evidence, JsonObject at) {
		JsonObject a = new JsonObject();
		a.addProperty("severity", severity);
		a.addProperty("evidence", evidence);
		if (at != null) a.add("at", at);
		into.add(id, a);
		if (worst == null || severity > worst.get("severity").getAsInt()) {
			worst = a;
			worstId = id;
		}
	}

	private static JsonObject firstThreat(JsonObject now) {
		if (!now.has("threats") || !now.get("threats").isJsonArray()) return null;
		JsonArray t = now.getAsJsonArray("threats");
		return t.isEmpty() ? null : t.get(0).getAsJsonObject();
	}

	/**
	 * Whoever last hurt the player. The damage source records the attacker (the shooter for an arrow, the
	 * mob for a bite); the world itself — falling, drowning, fire — leaves no entity, and that difference
	 * is reported rather than hidden.
	 */
	private static Entity attackerOf(LocalPlayer p) {
		DamageSource source = p.getLastDamageSource();
		if (source == null) return null;
		Entity attacker = source.getEntity() != null ? source.getEntity() : source.getDirectEntity();
		return attacker == null || attacker == p ? null : attacker;
	}

	/** Where an attacker is, plus what to call it, so a remedy can be aimed without another query. */
	private static JsonObject entityRef(Entity e) {
		JsonObject o = new JsonObject();
		o.addProperty("uuid", e.getUUID().toString());
		o.addProperty("name", e.getName().getString());
		o.addProperty("x", round(e.getX()));
		o.addProperty("y", round(e.getY()));
		o.addProperty("z", round(e.getZ()));
		return o;
	}

	/** The worst anomaly from the latest sample, or null when nothing is wrong. */
	public synchronized JsonObject topAnomaly() {
		return worst;
	}

	public synchronized String topAnomalyId() {
		return worst == null ? "" : worstId;
	}

	/** Latest sample plus the rolling log — the payload handed to RPC callers. */
	public JsonObject json() {
		JsonObject o = new JsonObject();
		o.add("now", snapshot.deepCopy());
		o.addProperty("danger", danger());
		o.addProperty("dangerReason", dangerReason());
		o.addProperty("seq", seq);
		o.addProperty("eventSeq", eventSeq);
		JsonArray logs = new JsonArray();
		synchronized (log) {
			for (JsonObject e : log) logs.add(e.deepCopy());
		}
		o.add("log", logs);
		return o;
	}

	/**
	 * The same payload plus everything that changed after {@code sinceSeq}.
	 *
	 * <p>This is what makes the watch a stream rather than a poll: keep the {@code seq} you read last
	 * time, pass it back on the next read, and every change in between arrives with its sequence number
	 * — no gaps to fall into, and no need to read often enough to be sure of catching something.
	 */
	public JsonObject json(long sinceSeq) {
		JsonObject o = json();
		if (sinceSeq <= 0L) return o;
		JsonArray fresh = new JsonArray();
		synchronized (log) {
			for (JsonObject e : events) {
				JsonElement s = e.get("seq");
				if (s != null && s.getAsLong() > sinceSeq) fresh.add(e.deepCopy());
			}
		}
		o.add("newEvents", fresh);
		o.addProperty("sinceSeq", sinceSeq);
		o.addProperty("note", fresh.isEmpty()
				? "Nothing changed since seq " + sinceSeq + "; 'now' is still current."
				: fresh.size() + " change(s) since seq " + sinceSeq + " — 'now' is the state they left.");
		return o;
	}

	public boolean enabled() {
		return enabled;
	}

	public void setEnabled(boolean on) {
		this.enabled = on;
	}

	/** 0 = fine, 1 = needs attention, 2 = act now (dying / mob in your face). */
	public int danger() {
		if (health <= 0.0F) return 2;
		if (maxHealth > 0.0F && health <= maxHealth * LOW_HEALTH_FRACTION) return 2;
		if (air < AIR_ALERT) return 2;
		if (air < 240) return 1;
		if (closestThreat <= THREAT_CLOSE && threatCount > 0) return 2;
		if (threatCount > 0) return 1;
		return 0;
	}

	public String dangerReason() {
		if (health <= 0.0F) return "dead";
		if (maxHealth > 0.0F && health <= maxHealth * LOW_HEALTH_FRACTION) {
			return "low_health " + Math.round(health) + "/" + Math.round(maxHealth);
		}
		if (air < AIR_ALERT) return "drowning air=" + air + " — get out of the water";
		if (air < 240) return "air_low " + air;
		if (closestThreat <= THREAT_CLOSE && threatCount > 0) {
			return "hostile_close " + closestThreatName + " " + round(closestThreat) + "b";
		}
		if (threatCount > 0) return "hostiles_nearby " + threatCount;
		return "ok";
	}

	/**
	 * Notice a low-health emergency the moment it starts. The whole point of watching during a task
	 * is that you should not have to wait for the task to end (or for the player to die) before you
	 * learn something went wrong.
	 */
	private void raiseAlerts(LocalPlayer p) {
		if (maxHealth > 0.0F && health <= maxHealth * LOW_HEALTH_FRACTION && health > 0.0F) {
			noteOnce("lowhealth", "alert: health " + Math.round(health) + "/" + Math.round(maxHealth));
		}
		if (air < AIR_ALERT) {
			// Bucketed so the log repeats as it gets worse instead of only once at the threshold.
			noteOnce("air" + (air / 40), "alert: air " + air + " — underwater, and running out");
		}
		if (closestThreat <= THREAT_CLOSE && threatCount > 0) {
			noteOnce("threat_" + closestThreatName, "alert: " + closestThreatName + " within "
					+ round(closestThreat) + " blocks");
		}
		if (dropCount > 0) {
			noteOnce("drops_" + dropCount, "drops nearby: " + dropCount + " item(s)");
		}
		ItemStack held = p.getMainHandItem();
		if (!held.isEmpty()) {
			noteOnce("held_" + BuiltInRegistries.ITEM.getKey(held.getItem()) + "_" + held.getCount(),
					"holding " + held.getCount() + "x " + BuiltInRegistries.ITEM.getKey(held.getItem()));
		}
	}

	/**
	 * The stream of changes, taken every tick whether a task is running or not.
	 *
	 * <p>A snapshot answers "what is true now"; this answers "what happened while you were not looking".
	 * That difference is the whole point, because most of what needs reacting to is short-lived — a bite
	 * taken, air running down, food slipping — and a caller polling a couple of times a second would
	 * otherwise have to be lucky to see it. Each change carries its own sequence number, so reading with
	 * {@code sinceSeq} returns exactly what was missed and nothing else.
	 */
	private void sampleChanges(LocalPlayer p, JsonObject now) {
		if (air != prevAir) {
			event("air", air < prevAir ? "air " + (int) prevAir + " -> " + air : "air back to " + air);
			prevAir = air;
		}
		int food = p.getFoodData().getFoodLevel();
		if (prevFoodSeen >= 0 && food != prevFoodSeen) {
			event("food", "food " + prevFoodSeen + " -> " + food);
		}
		prevFoodSeen = food;
		// Damage already raises its own anomaly with the attacker named; a *gain* is the half nothing
		// else reports, so that is the one logged here.
		if (prevHealthSeen >= 0.0F && health > prevHealthSeen + 0.01F) {
			event("health_up", "health " + round(prevHealthSeen) + " -> " + round(health));
		}
		prevHealthSeen = health;

		BlockPos at = p.blockPosition();
		int cell = (at.getX() >> 3) * 71 + (at.getZ() >> 3);
		if (cell != prevPosKey) {
			if (prevPosKey != Integer.MIN_VALUE) {
				event("moved", "now around " + at.getX() + "," + at.getY() + "," + at.getZ());
			}
			prevPosKey = cell;
		}
		now.addProperty("eventSeq", eventSeq);
	}

	/**
	 * Record something that happened, from outside the observer (a rule firing, for instance).
	 * Public so a firing shows up in {@code newEvents} — where a caller watching the stream will see
	 * it — rather than only in the log that a snapshot reader may never look at.
	 */
	public void record(String kind, String text) {
		event(kind, text);
		// Also onto the wake-up stream: a rule firing is one of the moments a driver may want a turn for.
		Watch.record(kind, text, null, 1);
	}

	/** Record a change with its own sequence number. */
	private void event(String kind, String text) {
		JsonObject e = note0(text);
		e.addProperty("kind", kind);
		synchronized (log) {
			events.addLast(e);
			while (events.size() > EVENT_CAPACITY) events.removeFirst();
		}
	}

	/** A cheap sweep of what is in front of the player, small enough to take every tick. */
	private JsonObject visibleSummary(Minecraft mc) {
		JsonObject o = new JsonObject();
		JsonArray arr = new JsonArray();
		for (Vision.Seen s : Vision.blocks(mc, 16.0, 7, 4, null, 6)) {
			JsonObject b = new JsonObject();
			b.addProperty("id", s.id());
			b.addProperty("x", s.pos().getX());
			b.addProperty("y", s.pos().getY());
			b.addProperty("z", s.pos().getZ());
			b.addProperty("distance", round(s.distance()));
			b.addProperty("wet", s.wet());
			b.addProperty("inReach", s.inReach());
			arr.add(b);
		}
		o.add("blocks", arr);
		o.addProperty("count", arr.size());
		return o;
	}

	/** Append a log entry (deduplicated consecutive repeats). Thread-safe: reads happen off-thread. */
	public void note(String text) {
		synchronized (log) {
			log.addLast(note0(text));
			logKeys.add("");
			while (log.size() > LOG_CAPACITY) {
				log.removeFirst();
				logKeys.remove(0);
			}
		}
	}

	/** Append only if this key has not already been logged (used by the alert watchdog). */
	private void noteOnce(String key, String text) {
		synchronized (log) {
			if (logKeys.contains(key)) return;
			log.addLast(note0(text));
			logKeys.add(key);
			while (log.size() > LOG_CAPACITY) {
				log.removeFirst();
				logKeys.remove(0);
			}
		}
	}

	private JsonObject note0(String text) {
		JsonObject e = new JsonObject();
		e.addProperty("seq", ++eventSeq);
		e.addProperty("t", ticks);
		e.addProperty("ms", System.currentTimeMillis() - startedMs);
		e.addProperty("text", text);
		return e;
	}

	private static JsonObject lookingAt(Minecraft mc, ClientLevel level) {
		JsonObject o = new JsonObject();
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos bp = bhr.getBlockPos();
			o.addProperty("type", "block");
			o.addProperty("id", BuiltInRegistries.BLOCK.getKey(level.getBlockState(bp).getBlock()).toString());
			o.addProperty("x", bp.getX());
			o.addProperty("y", bp.getY());
			o.addProperty("z", bp.getZ());
			o.addProperty("distance", round(Math.sqrt(hit.distanceTo(mc.player))));
		} else if (hit instanceof EntityHitResult ehr) {
			o.addProperty("type", "entity");
			o.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(ehr.getEntity().getType()).toString());
			o.addProperty("name", ehr.getEntity().getName().getString());
		} else {
			o.addProperty("type", "none");
		}
		return o;
	}

	private void sampleThreats(LocalPlayer p, ClientLevel level, JsonObject o) {
		JsonArray threats = new JsonArray();
		int count = 0;
		double closest = Double.MAX_VALUE;
		String closestName = "";
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof Mob mob) || !mob.isAlive()) continue;
			if (!(mob instanceof Enemy)) continue;
			double d = p.position().distanceTo(mob.position());
			if (d > THREAT_RADIUS) continue;
			count++;
			if (d < closest) {
				closest = d;
				closestName = mob.getName().getString();
			}
			if (threats.size() < 8) {
				JsonObject t = new JsonObject();
				// The UUID is what a remedy needs to target this exact mob, so it travels with the
				// threat and ends up in nextAction.args — the caller should never have to hunt for it.
				t.addProperty("uuid", mob.getUUID().toString());
				t.addProperty("name", mob.getName().getString());
				t.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()).toString());
				t.addProperty("distance", round(d));
				t.addProperty("health", mob.getHealth());
				t.add("pos", vec(mob.position()));
				threats.add(t);
			}
		}
		threatCount = count;
		closestThreat = closest;
		closestThreatName = closestName;
		o.addProperty("threatsNearby", count);
		o.add("threats", threats);
	}

	private void sampleDrops(LocalPlayer p, ClientLevel level, JsonObject o) {
		JsonArray drops = new JsonArray();
		int count = 0;
		double d2 = DROP_RADIUS * DROP_RADIUS;
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof ItemEntity it) || !it.isAlive()) continue;
			double d = p.position().distanceToSqr(it.position());
			if (d > d2) continue;
			count++;
			if (drops.size() < 8) {
				JsonObject j = new JsonObject();
				ItemStack st = it.getItem();
				j.addProperty("id", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
				j.addProperty("count", st.getCount());
				j.addProperty("distance", round(Math.sqrt(d)));
				drops.add(j);
			}
		}
		dropCount = count;
		o.addProperty("dropsNearby", count);
		o.add("drops", drops);
	}

	private static JsonObject vec(Vec3 v) {
		JsonObject o = new JsonObject();
		o.addProperty("x", round(v.x));
		o.addProperty("y", round(v.y));
		o.addProperty("z", round(v.z));
		return o;
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
