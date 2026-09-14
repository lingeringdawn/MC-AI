package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.Humanizer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Random;
import java.util.UUID;

/**
 * Short task: fight whatever is already in reach — aim, swing, and move like a person while doing
 * it (react once, circle-strafe, step back after landing a hit, jump to crit).
 *
 * <p>It never walks anywhere. If the target drifts out of reach it settles with {@code out_of_reach}
 * and lets the caller decide: compose an {@link ApproachTask} step, pick a different target, or stop.
 * Keeping "close the distance" and "hit it" in separate short tasks is what makes the pair
 * composable and keeps each step small enough to watch.
 */
public final class SwingTask extends ClientTask {
	/** Only actually attack inside this range, so swings are not wasted on out-of-reach targets. */
	private static final double SWING_RANGE = 2.8;
	/** Give the step up when the target drifts past this. */
	private static final double LEAVE_RANGE = 3.4;

	private final UUID target;
	/** Stop after this many landed hits; 0 = keep swinging until the step budget runs out. */
	private final int budgetSwings;

	private final Random rng = new Random();
	private int age;
	private int inReachTicks;
	private int reactionNeeded;
	private boolean reacted;
	private int strafeDir = 1;
	private int strafeUntil;
	private int backoffUntil;
	private int critCooldown;
	private int swings;
	private boolean seen;
	/** Target health when we first got in range — the honest measure of what we actually did. */
	private float startHealth = -1.0F;

	public SwingTask(UUID target, int budgetSwings) {
		this.target = target;
		this.budgetSwings = budgetSwings;
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		age++;
		if (p == null || level == null) {
			failed("no_world");
			return;
		}
		Entity e = EntityLookup.find(level, target);
		if (e == null) {
			release();
			if (seen) finish("target_gone"); else failed("not_found");
			return;
		}
		seen = true;
		if (startHealth < 0.0F && e instanceof LivingEntity le0) startHealth = le0.getHealth();
		if (!e.isAlive()) {
			release();
			finish("killed");
			return;
		}
		if (budgetSwings > 0 && swings >= budgetSwings) {
			release();
			finish("swung");
			return;
		}
		if (expired()) {
			// Short step used up: settle as a normal completion so the caller can compose the next
			// step rather than treating a bounded step as an error.
			release();
			finish("budget");
			return;
		}
		double dist = p.distanceTo(e);
		if (dist > LEAVE_RANGE) {
			// Out of range: not this step's job to close the gap.
			release();
			finish("out_of_reach");
			return;
		}

		inReachTicks++;
		aim(p, e);

		// Reaction time: a person takes a beat to register a target before swinging. Rolled once per
		// task, not per approach, or a target that keeps stepping away would never get hit.
		if (!reacted) {
			if (reactionNeeded == 0) reactionNeeded = Humanizer.reactionTicks(rng, 3, 6);
			if (inReachTicks <= reactionNeeded) {
				BotController.get().setAttackHeld(false);
				standStill();
				return;
			}
			reacted = true;
		}

		// Spacing: after landing a hit, step back for a moment — but only when genuinely in the
		// target's face. Backing away from something already running would just lose ground.
		if (age < backoffUntil && dist < 2.2) {
			BotController.get().setAttackHeld(false);
			BotController.get().setMovement(false, true, false, false, null, null, null);
			return;
		}

		// Circle-strafe so we are not a static target. Timed off {@code age} so a brief disengage does
		// not keep re-rolling the direction; never strafe toward a drop.
		if (age >= strafeUntil) {
			strafeDir = rng.nextBoolean() ? 1 : -1;
			strafeUntil = age + Humanizer.ticks(rng, 18, 40);
			if (ledgeAhead(level, p, strafeDir > 0)) strafeDir = -strafeDir;
		}
		if (!ledgeAhead(level, p, strafeDir > 0)) {
			boolean left = strafeDir > 0;
			BotController.get().setMovement(false, false, left, !left, null, null, null);
		} else {
			standStill();
		}

		// Critical hits: hop just before the swing while closing, the way a player jumps to crit.
		if (p.onGround() && age >= critCooldown && BotController.get().isLookSettled()
				&& clearShot(mc, p, e) && Humanizer.chance(rng, 0.35F)) {
			BotController.get().jumpOnce();
			critCooldown = age + Humanizer.ticks(rng, 20, 45);
		}

		// Attack one discrete hit at a time, and only once the attack cooldown has recharged — that is
		// what a player does, and clicking faster than the cooldown gains nothing.
		//
		// Note this goes through the same call the vanilla input pipeline makes for a real click
		// (MultiPlayerGameMode.attack), rather than holding the attack key: holding it drives block
		// breaking but does NOT perform entity attacks, so a held key silently never deals damage.
		boolean charged = p.getAttackStrengthScale(0.5F) >= 0.9F;
		boolean ready = charged && dist <= SWING_RANGE
				&& (BotController.get().isLookSettled() || BotController.get().lookErrorDeg() <= 6.0F)
				&& clearShot(mc, p, e);
		BotController.get().setAttackHeld(false);
		if (ready && mc.gameMode != null) {
			mc.gameMode.attack(p, e);
			p.swing(InteractionHand.MAIN_HAND);
			swings++;
			backoffUntil = age + Humanizer.ticks(rng, 3, 7);
		}
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.addProperty("step", "swing");
		o.addProperty("target", target.toString());
		o.addProperty("swings", swings);
		o.addProperty("budgetSwings", budgetSwings);
		o.addProperty("reacted", reacted);
		o.addProperty("strafeDir", strafeDir);
		Entity e = EntityLookup.find(Minecraft.getInstance().level, target);
		LocalPlayer p = Minecraft.getInstance().player;
		if (e != null) {
			o.addProperty("targetName", e.getName().getString());
			if (e instanceof net.minecraft.world.entity.LivingEntity le) {
				o.addProperty("targetHealth", le.getHealth());
			}
			o.addProperty("targetAlive", e.isAlive());
			if (p != null) o.addProperty("distance", round(p.distanceTo(e)));
		} else {
			o.addProperty("targetAlive", false);
		}
		return o;
	}

	@Override
	public String describe() {
		return "swing at " + target;
	}

	@Override
	public void onCancel(Minecraft mc) {
		release();
	}

	private void release() {
		BotController.get().setAttackHeld(false);
		BotController.get().stopNavigation("step_done");
		BotController.get().stopAllMovement();
	}

	private void finish(String state) {
		JsonObject extra = new JsonObject();
		extra.addProperty("swings", swings);
		// Report the damage the target actually took, not just how often we clicked: a swing the
		// server rejects as out of range is not a hit, and only the health bar knows the difference.
		if (startHealth >= 0.0F) {
			Entity e = EntityLookup.find(Minecraft.getInstance().level, target);
			float now = e instanceof LivingEntity le ? le.getHealth() : 0.0F;
			extra.addProperty("damageDealt", round(Math.max(0.0F, startHealth - now)));
		}
		done(state, extra);
	}

	/** Release the strafe keys but leave the attack button alone. */
	private void standStill() {
		BotController.get().setMovement(false, false, false, false, null, null, null);
	}

	/** True when a step in the strafe direction would put the player over a drop. */
	private boolean ledgeAhead(ClientLevel level, LocalPlayer p, boolean toLeft) {
		double yaw = Math.toRadians(p.getYRot());
		// For a player at this yaw, "right" is (-cos, 0, -sin) and "left" is its negation.
		double sign = toLeft ? 1.0 : -1.0;
		double sx = Math.cos(yaw) * sign;
		double sz = Math.sin(yaw) * sign;
		BlockPos ahead = BlockPos.containing(p.getX() + sx, p.getY() - 1.0, p.getZ() + sz);
		return level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty();
	}

	/** No block sits between the eye and the entity. */
	private boolean clearShot(Minecraft mc, LocalPlayer p, Entity e) {
		Vec3 from = p.getEyePosition();
		Vec3 to = e.getBoundingBox().getCenter();
		BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE,
				ClipContext.Fluid.NONE, p));
		if (hit.getType() == HitResult.Type.MISS) return true;
		return from.distanceTo(hit.getLocation()) >= from.distanceTo(to) - 0.2;
	}

	private void aim(LocalPlayer p, Entity e) {
		double dx = e.getX() - p.getX();
		double dy = (e.getY() + e.getBbHeight() * 0.5) - p.getEyeY();
		double dz = e.getZ() - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		float pitch = (float) (-(Math.atan2(dy, horiz) * (180.0 / Math.PI)));
		// Task priority so the entity we are fighting wins the camera over anything else.
		BotController.get().lookAtTarget(yaw, pitch, BotController.LOOK_TASK);
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
