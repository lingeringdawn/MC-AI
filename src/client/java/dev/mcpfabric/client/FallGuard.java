package dev.mcpfabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
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
	/**
	 * Place the water only when the ground is this close. Reach is ~4.5 from the eye, and the eye sits
	 * ~1.62 above the feet, so the feet have to be within ~2.8 for the ray to land on the ground.
	 */
	private static final double PLACE_REACH = 2.6;
	/** How far down to look for something to land on. */
	private static final int GROUND_SCAN = 8;

	private Phase phase = Phase.IDLE;
	private int timer;
	private int savedSlot = -1;
	/** True once the bucket has been emptied, so it is never emptied twice. */
	private boolean placed;
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
			// Snap the view straight down for real and empty the bucket. Both have to happen in this
			// tick: the ground arrives within a tick or two of the water coming into range, so an
			// interpolated turn (which is right for ordinary aiming) would still be half-way there.
			bot.snapLook(p.getYRot(), 90.0F);
			bot.setMovement(false, false, false, false, false, false, false);
			bot.setAttackHeld(false);
			if (!placed) {
				// Exactly one attempt: after it succeeds we are holding an empty bucket, and using that
				// again would scoop the water straight back up.
				if (mc.gameMode != null) {
					mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
					p.swing(InteractionHand.MAIN_HAND);
				}
				placed = true;
			}
			timer++;
			// Done once we have stopped falling (landed, in the water, or caught by something).
			if (p.onGround() || p.isInWater() || timer > 10) {
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
		placed = false;
	}

	private boolean needsSaving(LocalPlayer p) {
		if (phase != Phase.IDLE) return false;
		if (p.onGround() || p.isInWater() || p.isFallFlying()) return false;
		if (p.getAbilities().flying) return false;
		if (Double.isNaN(peakY)) return false;

		// Water can only be placed on a block we can actually reach, so the save has to wait until the
		// ground is within placing range. Emptying the bucket while still high up would just fail: the
		// ray from the eye never gets to the ground.
		double groundDist = groundDistance(p);
		if (groundDist <= 0.0 || groundDist > PLACE_REACH) return false;

		// Now ask whether the whole fall — from the highest point down to that ground — would hurt.
		double landingFeetY = p.getY() - groundDist;
		double totalDrop = peakY - landingFeetY;
		if (totalDrop < 5.0) return false;
		// Vanilla fall damage is (distance - 3), so only bother when that would genuinely hurt.
		float predicted = (float) (totalDrop - 3.0);
		return predicted >= Math.max(4.0F, p.getHealth() - 2.0F);
	}

	/**
	 * Distance from the player's feet down to the first block we could land on, or -1 when there is
	 * nothing solid within scan range. Measured from the feet, so 0 means standing on it.
	 */
	private static double groundDistance(LocalPlayer p) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) return -1.0;
		int feetY = Mth.floor(p.getY());
		int x = Mth.floor(p.getX());
		int z = Mth.floor(p.getZ());
		for (int dy = 0; dy <= GROUND_SCAN; dy++) {
			BlockPos pos = new BlockPos(x, feetY - dy, z);
			if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
				double top = (feetY - dy) + 1.0;
				return p.getY() - top;
			}
		}
		return -1.0;
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
