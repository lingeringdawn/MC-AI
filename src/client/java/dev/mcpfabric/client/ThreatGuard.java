package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.nav.AStarPathfinder;
import dev.mcpfabric.client.tasks.TaskManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Self-preservation while a task is busy: notice hostile mobs, hit back, and break off when hurt.
 *
 * <p>A task drives the bot with single-minded focus — walk here, mine that — which is exactly why the
 * bot could be beaten to death by a zombie it was strolling past: nothing ever looked at the mob, and
 * the task's own inputs kept it walking away mid-fight. This guard ticks *after* the task, so it has
 * the last word on movement, aim and the attack button, and hands control back the moment the coast
 * is clear.
 */
public final class ThreatGuard {
	private static final ThreatGuard INSTANCE = new ThreatGuard();

	public static ThreatGuard get() {
		return INSTANCE;
	}

	/** React to hostile mobs inside this radius. */
	private static final double ENGAGE_RADIUS = 5.0;
	/** Keep pursuing whatever recently hit us out to here, so a stepping-away attacker is not lost. */
	private static final double RECALL_RADIUS = 10.0;
	/**
	 * Once a fight is on, stay in it until the target dies or gets this far away. Re-deciding every
	 * tick dropped the guard in and out of combat as the mob stepped in and out of line of sight, and
	 * because leaving combat releases the attack button, the bot never held it long enough to land a
	 * swing at all.
	 */
	private static final double DROP_RADIUS = 8.0;
	/** Swing at anything this close (vanilla survival reach is 3.0). */
	private static final double MELEE_REACH = 3.0;
	/** Below this much health, break off and retreat instead of trading blows. */
	private static final float FLEE_HEALTH = 6.0F;
	/** How long to keep trying to reach a target before deciding it is unreachable (5s). */
	private static final int CHASE_LIMIT_TICKS = 100;
	private static final int NODE_BUDGET = 12000;

	private boolean enabled = true;
	/** The mob being dealt with, kept across brief flickers of distance and line of sight. */
	private LivingEntity target;
	/** True while this guard owns the inputs, so it only releases what it actually took. */
	private boolean engaged;
	/** What the guard is doing right now, for the observation snapshot. */
	private String posture = "";
	/** True when the crosshair or the pending aim is on the target — the condition for swinging. */
	private boolean aimedAtTarget;
	/** True when the attack cooldown has finished, so a swing would deal full damage. */
	private boolean charged;
	private String targetName = "";
	/** Swings landed since the last reset — exposed so "did it actually hit anything" is checkable. */
	private int swings;
	/** Ticks since construction, used for chase deadlines and repath pacing. */
	private int age;
	/** Tick the current chase began (or -1 when the target is in reach). */
	private int closingSince = -1;
	private int repathAt;
	private String lastNote = "";

	private ThreatGuard() {}

	public void setEnabled(boolean on) {
		this.enabled = on;
	}

	public boolean engaged() {
		return engaged;
	}

	/** Live guard state, folded into the observer snapshot so it can be watched from outside. */
	public synchronized JsonObject snapshot() {
		JsonObject o = new JsonObject();
		o.addProperty("engaged", engaged);
		if (!engaged) return o;
		o.addProperty("posture", posture);
		o.addProperty("target", targetName);
		o.addProperty("aimedAtTarget", aimedAtTarget);
		o.addProperty("charged", charged);
		o.addProperty("swings", swings);
		o.addProperty("lookErrorDeg", BotController.get().lookErrorDeg());
		return o;
	}

	/** Called from the client tick, after the active task has had its say. */
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null || !enabled || p.isDeadOrDying()) {
			target = null;
			release();
			return;
		}
		age++;
		if (target != null && (!target.isAlive() || p.distanceToSqr(target) > DROP_RADIUS * DROP_RADIUS)) {
			target = null;
		}
		if (target == null) {
			target = findThreat(p, level);
			if (target != null) {
				swings = 0;
				closingSince = -1;
				repathAt = 0;
			}
		}
		if (target == null) {
			release();
			return;
		}
		engage(mc, p, level, target);
	}

	/** Hand the inputs back to whoever else wants them (the task re-asserts itself next tick). */
	private void release() {
		if (!engaged) return;
		engaged = false;
		posture = "";
		targetName = "";
		aimedAtTarget = false;
		charged = false;
		closingSince = -1;
		repathAt = 0;
		lastNote = "";
		BotController.get().setAttackHeld(false);
		BotController.get().stopAllMovement();
	}

	/**
	 * The mob worth reacting to: whatever just hit us (any kind — a wolf pack or a bee counts, not only
	 * the undead), else the nearest hostile monster we can actually see.
	 */
	private LivingEntity findThreat(LocalPlayer p, ClientLevel level) {
		LivingEntity attacker = p.getLastHurtByMob();
		if (attacker != null && attacker.isAlive() && !(attacker instanceof Player)
				&& p.distanceToSqr(attacker) <= RECALL_RADIUS * RECALL_RADIUS) {
			return attacker;
		}

		LivingEntity best = null;
		double bestD = ENGAGE_RADIUS * ENGAGE_RADIUS;
		for (Entity e : level.entitiesForRendering()) {
			if (!(e instanceof Enemy) || !(e instanceof LivingEntity mob) || !mob.isAlive()) continue;
			double d = p.distanceToSqr(mob);
			if (d > bestD) continue;
			// Behind a wall is not a threat: reacting to it would abandon the task for nothing.
			if (!canSee(level, p, mob)) continue;
			bestD = d;
			best = mob;
		}
		return best;
	}

	private void engage(Minecraft mc, LocalPlayer p, ClientLevel level, LivingEntity threat) {
		engaged = true;
		targetName = threat.getName().getString();
		aim(p, threat);

		// Deliberately leave the jump flag alone: drowning protection owns it, and hopping while
		// trading blows only makes the crosshair wander.
		Boolean jump = null;

		// Creepers are not a melee fight — one swing at arm's length trades a hit for an explosion.
		// Back off and keep watching it instead.
		if (threat instanceof Creeper || p.getHealth() <= FLEE_HEALTH) {
			posture = "retreating";
			aimedAtTarget = false;
			charged = false;
			note("backing off " + targetName);
			BotController.get().setAttackHeld(false);
			// We are already facing the threat, so "back" retreats along the line away from it — but
			// only while that way stays on solid ground. Backing into a pond just trades the mob for
			// the drowning, so sidestep instead, and if every way out is water or a drop, hold ground.
			if (standable(level, p, -1.0, 0.0)) {
				BotController.get().setMovement(false, true, false, false, jump, null, null);
			} else if (standable(level, p, 0.0, -1.0)) {
				BotController.get().setMovement(false, false, true, false, jump, null, null);
			} else if (standable(level, p, 0.0, 1.0)) {
				BotController.get().setMovement(false, false, false, true, jump, null, null);
			} else {
				BotController.get().setMovement(false, false, false, false, jump, null, null);
			}
			return;
		}

		if (p.distanceTo(threat) > MELEE_REACH) {
			posture = "closing";
			aimedAtTarget = false;
			charged = false;
			BotController.get().setAttackHeld(false);
			// A mob we cannot actually get to is not a fight. Standing here staring at one across a
			// ledge or a pond abandons the job for nothing, so give the chase a deadline.
			if (closingSince < 0) closingSince = age;
			if (age - closingSince > CHASE_LIMIT_TICKS) {
				note("cannot reach " + targetName + " — resuming task");
				release();
				target = null;
				return;
			}
			note("closing on " + targetName);
			// Walk there along a real path: shoving the forward key at a mob on the far side of an
			// edge just pins the bot against it. Re-plan periodically rather than every tick.
			if (age >= repathAt) {
				repathAt = age + 10;
				BlockPos goal = threat.blockPosition();
				List<BlockPos> path = new AStarPathfinder(level, NODE_BUDGET)
						.findPath(p.blockPosition(), goal, MELEE_REACH - 0.5);
				if (path != null && !path.isEmpty()) {
					BotController.get().startNavigation(path, goal, MELEE_REACH - 0.5, true,
							System.currentTimeMillis() + 3000L);
				} else {
					BotController.get().setMovement(true, false, false, false, jump, null, null);
				}
			}
			return;
		}
		closingSince = -1;

		// In reach: plant the feet and swing. Standing still is the point — the task underneath is
		// usually walking, and drifting away mid-swing is how the bot got chewed on without landing
		// a single hit.
		posture = "fighting";
		BotController.get().setMovement(false, false, false, false, jump, null, null);
		boolean onCrosshair = mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == threat;
		aimedAtTarget = onCrosshair || canSee(level, p, threat);
		// Only swing when the cooldown has finished, so every hit is a full-damage one instead of a
		// stream of weak taps.
		charged = p.getAttackStrengthScale(0.5F) >= 0.9F;
		note("fighting " + targetName);
		if (aimedAtTarget && charged) {
			// Swing by invoking the attack action directly. Holding the mouse button does not work
			// here: vanilla repeats the *block* branch while the button is held (which is why mining
			// works), but the entity branch only fires on a press edge — so a held key left the bot
			// standing in a fight without ever landing a hit. This is the same call the interact
			// capability uses, and the server validates reach itself.
			mc.gameMode.attack(p, threat);
			p.swing(InteractionHand.MAIN_HAND);
			swings++;
		}
		// Never leave the button held: the task underneath would re-assert it and a stuck attack
		// button would dig whatever the crosshair drifts onto.
		BotController.get().setAttackHeld(false);
	}

	/** Face the threat's torso, at self-defence priority. */
	private void aim(LocalPlayer p, LivingEntity e) {
		double dx = e.getX() - p.getX();
		double dy = (e.getY() + e.getBbHeight() * 0.6) - p.getEyeY();
		double dz = e.getZ() - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		float pitch = (float) (-(Math.atan2(dy, Math.max(horiz, 0.01)) * (180.0 / Math.PI)));
		BotController.get().lookAtTarget(yaw, pitch, BotController.LOOK_DEFEND);
	}

	/**
	 * World-space offset of a camera-relative movement, so a "is that way clear?" test matches the keys
	 * we actually press. Forward is wherever the bot is looking, which is the threat it just turned to.
	 */
	private static double[] offsetOf(LocalPlayer p, double fwd, double right) {
		double yaw = Math.toRadians(p.getYRot());
		double fx = -Math.sin(yaw);
		double fz = Math.cos(yaw);
		double rx = -Math.cos(yaw);
		double rz = -Math.sin(yaw);
		return new double[] { fx * fwd + rx * right, fz * fwd + rz * right };
	}

	/** True when stepping this way lands on ground, rather than in water or over a drop. */
	private boolean standable(ClientLevel level, LocalPlayer p, double fwd, double right) {
		double[] d = offsetOf(p, fwd, right);
		BlockPos feet = BlockPos.containing(p.getX() + d[0], p.getY(), p.getZ() + d[1]);
		if (!level.getBlockState(feet).getFluidState().isEmpty()) return false;
		if (!level.getBlockState(feet.above()).getFluidState().isEmpty()) return false;
		BlockPos floor = feet.below();
		return !level.getBlockState(floor).getCollisionShape(level, floor).isEmpty();
	}

	/** Clear line of sight to the entity (so walls, not distance, decide what counts as a threat). */
	private boolean canSee(ClientLevel level, LocalPlayer p, Entity e) {
		Vec3 from = p.getEyePosition();
		Vec3 to = new Vec3(e.getX(), e.getY() + e.getBbHeight() * 0.6, e.getZ());
		BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
				ClipContext.Fluid.NONE, p));
		return hit.getType() == HitResult.Type.MISS;
	}

	/** Put what we are doing into the observer's rolling log (the observer collapses repeats). */
	private void note(String text) {
		if (text.equals(lastNote)) return;
		lastNote = text;
		TaskManager.get().observer().note(text);
	}
}
