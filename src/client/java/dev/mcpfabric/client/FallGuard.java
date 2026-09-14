package dev.mcpfabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.ItemStack;

/**
 * Last-resort fall protection: the "MLG water" save.
 *
 * <p>When the bot is dropping far enough to be lethal and a water bucket is in the hotbar, it looks
 * straight down and empties the bucket underneath itself, then picks the water back up after landing.
 * Placing water is the one trick a player uses to survive a drop with nothing else available, and the
 * bot should not die to a fall it could have walked away from just because it never thought to use an
 * item. The bucket comes back: emptying a water bucket leaves an empty bucket, which is exactly the
 * tool for scooping the source back up.
 *
 * <p>Deliberately conservative — it only fires when the incoming damage would actually be dangerous,
 * so an ordinary drop stays an ordinary drop.
 */
final class FallGuard {
	private enum Phase { IDLE, PLACING, RECOVERING }

	private static final String WATER_BUCKET = "minecraft:water_bucket";
	private static final String BUCKET = "minecraft:bucket";

	private Phase phase = Phase.IDLE;
	private int timer;
	private int savedSlot = -1;
	/** Highest point of the current fall. Tracked here because the client's fallDistance is unreliable. */
	private double peakY = Double.NaN;

	/** True while the guard is driving the player, so the caller yields control to it. */
	boolean active() {
		return phase != Phase.IDLE;
	}

	/**
	 * Run one tick. Returns true when the guard is handling the situation, in which case the caller
	 * should not steer — but should still apply the look, which the guard requests at user priority.
	 */
	boolean tick(Minecraft mc, LocalPlayer p, BotController bot) {
		if (p == null || mc.level == null) {
			reset();
			return false;
		}

		// Track the fall ourselves: peak height while airborne, drop = peak - current.
		if (p.onGround() || p.isInWater()) {
			peakY = Double.NaN;
		} else if (Double.isNaN(peakY)) {
			peakY = p.getY();
		} else {
			peakY = Math.max(peakY, p.getY());
		}

		if (phase == Phase.IDLE) {
			if (!needsSaving(p)) return false;
			int slot = findHotbar(p, WATER_BUCKET);
			if (slot < 0) return false;
			savedSlot = selected(p);
			select(p, slot);
			timer = 0;
			phase = Phase.PLACING;
		}

		if (phase == Phase.PLACING) {
			// Crosshair straight down, nobody else touching the movement keys, and empty the bucket.
			bot.lookAtTarget(p.getYRot(), 90.0F, BotController.LOOK_USER);
			bot.setMovement(false, false, false, false, false, false, false);
			bot.setAttackHeld(false);
			bot.setUseHeld(timer < 3);
			timer++;
			// Done once we have stopped falling (landed, in the water, or caught by something).
			if (p.onGround() || p.isInWater() || timer > 10) {
				bot.setUseHeld(false);
				phase = Phase.RECOVERING;
				timer = 0;
			}
			return true;
		}

		// RECOVERING: scoop the water back up so the bucket is not left behind in the world.
		bot.setUseHeld(false);
		bot.setMovement(false, false, false, false, false, false, false);
		timer++;
		if (timer == 6 && p.onGround()) {
			int slot = findHotbar(p, BUCKET);
			if (slot >= 0) {
				select(p, slot);
				bot.lookAtTarget(p.getYRot(), 90.0F, BotController.LOOK_USER);
				bot.setUseHeld(true);
			}
		}
		if (timer >= 10) {
			bot.setUseHeld(false);
			if (savedSlot >= 0) {
				select(p, savedSlot); // put the hotbar back where the caller left it
				savedSlot = -1;
			}
			reset();
			return false;
		}
		return true;
	}

	/** Forget everything and hand control back (used on cancel / when the player vanishes). */
	void reset() {
		phase = Phase.IDLE;
		timer = 0;
		peakY = Double.NaN;
		savedSlot = -1;
	}

	private boolean needsSaving(LocalPlayer p) {
		if (phase != Phase.IDLE) return false;
		if (p.onGround() || p.isInWater() || p.isFallFlying()) return false;
		if (p.getAbilities().flying) return false;
		if (Double.isNaN(peakY)) return false;
		double drop = peakY - p.getY();
		if (drop < 5.0) return false;
		// Vanilla fall damage is (distance - 3), so only bother when that would genuinely hurt.
		float predicted = (float) (drop - 3.0);
		return predicted >= Math.max(4.0F, p.getHealth() - 2.0F);
	}

	private static int findHotbar(LocalPlayer p, String itemId) {
		for (int i = 0; i <= 8; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty()) continue;
			if (BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(itemId)) return i;
		}
		return -1;
	}

	private static int selected(LocalPlayer p) {
		//? if >=1.21.5 {
		return p.getInventory().getSelectedSlot();
		//?} else
		/*return p.getInventory().selected;*/
	}

	private static void select(LocalPlayer p, int slot) {
		//? if >=1.21.5 {
		p.getInventory().setSelectedSlot(slot);
		//?} else
		/*p.getInventory().selected = slot;*/
		p.connection.send(new ServerboundSetCarriedItemPacket(slot));
	}
}
