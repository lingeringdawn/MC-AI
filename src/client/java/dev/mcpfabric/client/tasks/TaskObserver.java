package dev.mcpfabric.client.tasks;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
import java.util.List;

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
	private int threatCount;
	private double closestThreat = Double.MAX_VALUE;
	private String closestThreatName = "";
	private int dropCount;
	private float health = 20.0F;
	private float maxHealth = 20.0F;

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
		o.addProperty("health", health);
		o.addProperty("maxHealth", maxHealth);
		o.addProperty("food", p.getFoodData().getFoodLevel());
		o.addProperty("air", p.getAirSupply());
		o.addProperty("onGround", p.onGround());
		o.addProperty("inWater", p.isInWater());
		o.addProperty("dead", p.isDeadOrDying() || p.getHealth() <= 0.0F);

		// --- position / motion ----------------------------------------------
		JsonObject pos = new JsonObject();
		pos.addProperty("x", round(p.getX()));
		pos.addProperty("y", round(p.getY()));
		pos.addProperty("z", round(p.getZ()));
		o.add("pos", pos);
		o.addProperty("moved", round(p.getDeltaMovement().horizontalDistance()));

		// --- what the crosshair is on ---------------------------------------
		o.add("lookingAt", lookingAt(mc, level));

		// --- threats ---------------------------------------------------------
		sampleThreats(p, level, o);

		// --- drops on the ground --------------------------------------------
		sampleDrops(p, level, o);

		// --- the task's own progress ----------------------------------------
		if (taskProgress != null && taskProgress.size() > 0) o.add("progress", taskProgress);

		// --- navigation state (shared by every walking task) ------------------
		JsonObject nav = BotController.get().statusJson();
		if (nav.has("active") && nav.get("active").getAsBoolean()) o.add("nav", nav);

		snapshot = o;
		raiseAlerts(p);
	}

	/** Latest sample plus the rolling log — the payload handed to RPC callers. */
	public JsonObject json() {
		JsonObject o = new JsonObject();
		o.add("now", snapshot.deepCopy());
		o.addProperty("danger", danger());
		o.addProperty("dangerReason", dangerReason());
		JsonArray logs = new JsonArray();
		synchronized (log) {
			for (JsonObject e : log) logs.add(e.deepCopy());
		}
		o.add("log", logs);
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
		if (closestThreat <= THREAT_CLOSE && threatCount > 0) return 2;
		if (threatCount > 0) return 1;
		return 0;
	}

	public String dangerReason() {
		if (health <= 0.0F) return "dead";
		if (maxHealth > 0.0F && health <= maxHealth * LOW_HEALTH_FRACTION) {
			return "low_health " + Math.round(health) + "/" + Math.round(maxHealth);
		}
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
