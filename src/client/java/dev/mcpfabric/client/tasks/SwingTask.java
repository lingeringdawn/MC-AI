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
 * Hit an entity: aim at it, and swing once it is in reach and the attack cooldown is charged.
 *
 * <p>One small module, no policy. It does not walk anywhere, does not strafe, does not decide when a
 * fight is won, and never picks its own target — "close the distance", "step back", "keep hitting until
 * it dies" are sequences the caller composes out of this and the movement modules. If the target drifts
 * out of reach it settles with {@code out_of_reach} and hands the decision straight back.
 *
 * <p>The swing goes through {@code MultiPlayerGameMode.attack}, the same call the vanilla input
 * pipeline makes for a real click. Holding the attack key is not an option: vanilla repeats the
 * <em>block</em> branch while the button is held (which is why mining works) but the entity branch only
 * fires on a press edge, so a held key never lands on a mob at all.
 */
public final class SwingTask extends ClientTask {
	/** Only swing inside this range, so hits are not wasted on out-of-reach targets. */
	private static final double SWING_RANGE = 3.0;

	private final UUID target;
	/** How many landed hits to make; 0 = keep swinging for the whole step budget. */
	private final int hitsWanted;

	private int hits;
	private boolean seen;
	/** Target health when we first got in range — the honest measure of what we actually did. */
	private float startHealth = -1.0F;

	public SwingTask(UUID target, int hitsWanted) {
		this.target = target;
		this.hitsWanted = hitsWanted;
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
			finish(seen ? "target_gone" : "not_found");
			return;
		}
		seen = true;
		if (startHealth < 0.0F && e instanceof LivingEntity le0) startHealth = le0.getHealth();
		if (!e.isAlive()) {
			finish("killed");
			return;
		}
		if (hitsWanted > 0 && hits >= hitsWanted) {
			finish("hit");
			return;
		}
		if (expired()) {
			// A bounded module used up its step: settle as a normal completion so the caller composes
			// the next step rather than treating running out of time as an error.
			finish("budget");
			return;
		}

		aim(p, e);
		if (p.distanceTo(e) > SWING_RANGE) {
			finish("out_of_reach");
			return;
		}
		if (p.getAttackStrengthScale(0.5F) < 0.9F) {
			// Cooldown still recovering: swinging now would land a weakened hit for nothing.
			return;
		}
		if (mc.gameMode == null) {
			failed("no_game_mode");
			return;
		}
		mc.gameMode.attack(p, e);
		p.swing(InteractionHand.MAIN_HAND);
		hits++;
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.addProperty("target", target.toString());
		o.addProperty("hits", hits);
		o.addProperty("hitsWanted", hitsWanted);
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
		return o;
	}

	@Override
	public String describe() {
		return "hit " + target + (hitsWanted > 0 ? " x" + hitsWanted : "");
	}

	private void finish(String state) {
		JsonObject extra = new JsonObject();
		extra.addProperty("hits", hits);
		// Report the damage the target actually took, not just how often we swung: a swing the server
		// rejects as out of range is not a hit, and only the health bar knows the difference.
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
