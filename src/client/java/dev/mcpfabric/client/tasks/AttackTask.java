package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.UUID;

/**
 * Land one hit. Aim at the entity, wait for the attack cooldown to be fully charged, swing, and wait for
 * the cooldown to come back before settling.
 *
 * <p>One swing is the unit here, not "fight this mob": it does not walk, does not strafe, does not decide
 * when the fight is over and never picks its own target. Closing the distance is a {@code moveTo},
 * stepping back is a {@code moveTo}, and carrying on is calling this again — which is the difference
 * between a bot that can be interrupted mid-fight and one that has already committed to winning it.
 *
 * <p>It waits out the cooldown on both sides of the swing. Swinging before the bar is full lands a
 * weakened hit, and returning before it refills leaves a half-charged attack behind for whoever calls
 * next; either way the caller gets a hit that is worth a hit.
 *
 * <p>The swing goes through {@code MultiPlayerGameMode.attack}, the same call vanilla's input pipeline
 * makes for a real click. Holding the attack key is not an option: vanilla repeats the <em>block</em>
 * branch while the button is down (which is why digging works) but the entity branch only fires on a
 * press edge, so a held key never lands on a mob at all.
 */
public final class AttackTask extends ClientTask {
	/** Only swing inside this range; beyond it a hit is wasted and the caller should move first. */
	private static final double REACH = 3.0;
	/** How charged the attack bar must be for a swing to count as a full-damage hit. */
	private static final float FULL_CHARGE = 0.99F;

	private final UUID target;
	private boolean seen;
	private boolean swung;
	/** Target health when it was first seen — the honest measure of what the hit actually did. */
	private float startHealth = -1.0F;

	public AttackTask(UUID target) {
		this.target = target;
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			failed("no_world");
			return;
		}
		Entity e = EntityLookup.find(level, target);
		if (e == null) {
			failed(seen ? "target_gone" : "not_found");
			return;
		}
		seen = true;
		if (startHealth < 0.0F && e instanceof LivingEntity le0) startHealth = le0.getHealth();
		if (!e.isAlive()) {
			finish("killed");
			return;
		}
		if (expired()) {
			finish(swung ? "cooldown" : "no_hit");
			return;
		}

		aim(p, e);
		if (p.distanceTo(e) > REACH) {
			finish("out_of_reach");
			return;
		}
		if (swung) {
			if (p.getAttackStrengthScale(0.5F) >= FULL_CHARGE) finish("hit");
			return;
		}
		if (p.getAttackStrengthScale(0.5F) < FULL_CHARGE) return;
		if (mc.gameMode == null) {
			failed("no_game_mode");
			return;
		}
		mc.gameMode.attack(p, e);
		p.swing(InteractionHand.MAIN_HAND);
		swung = true;
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.addProperty("target", target.toString());
		o.addProperty("swung", swung);
		Entity e = EntityLookup.find(Minecraft.getInstance().level, target);
		LocalPlayer p = Minecraft.getInstance().player;
		if (e != null) {
			o.addProperty("targetName", e.getName().getString());
			if (e instanceof LivingEntity le) o.addProperty("targetHealth", le.getHealth());
			o.addProperty("targetAlive", e.isAlive());
			if (p != null) o.addProperty("distance", round(p.distanceTo(e)));
		} else {
			o.addProperty("targetAlive", false);
		}
		if (p != null) o.addProperty("charge", round(p.getAttackStrengthScale(0.5F)));
		return o;
	}

	@Override
	public String describe() {
		return "attack " + target;
	}

	private void finish(String state) {
		JsonObject extra = new JsonObject();
		extra.addProperty("swung", swung);
		// Report the damage the target actually took, not merely that a swing happened: a swing the
		// server rejects as out of range is not a hit, and only the health bar knows the difference.
		if (startHealth >= 0.0F) {
			Entity e = EntityLookup.find(Minecraft.getInstance().level, target);
			float now = e instanceof LivingEntity le ? le.getHealth() : 0.0F;
			extra.addProperty("damageDealt", round(Math.max(0.0F, startHealth - now)));
		}
		done(state, extra);
	}

	private void aim(LocalPlayer p, Entity e) {
		double dx = e.getX() - p.getX();
		double dy = (e.getY() + e.getBbHeight() * 0.5) - p.getEyeY();
		double dz = e.getZ() - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		float pitch = (float) (-(Math.atan2(dy, horiz) * (180.0 / Math.PI)));
		BotController.get().lookAtTarget(yaw, pitch, BotController.LOOK_TASK);
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
