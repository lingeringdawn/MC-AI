package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/**
 * The "MLG water" save, once — place a water bucket under yourself while falling, land in it, then scoop
 * the source back up. The explicit counterpart to the automatic fall guard this mod used to run.
 *
 * <p>Everything is measured rather than assumed:
 *
 * <ol>
 *   <li><b>Where the landing is.</b> The column below the player is scanned for the surface they would
 *       land on, and the cell above it is checked for being placeable into.</li>
 *   <li><b>Whether that landing can cushion the fall — re-checked every tick.</b> Water, cobwebs,
 *       powder snow, slime, berry bushes and hay absorb a drop; a drop that would not hurt needs
 *       nothing. Crucially the check is not made once: a thin sheet of water can drain away before the
 *       player arrives, and a single early "looks fine" would then be a death sentence. The landing is
 *       re-surveyed all the way down, and the bucket goes out the moment the cushion stops holding.</li>
 *   <li><b>When the bucket can reach.</b> A bucket can only be emptied against a block the eye's ray
 *       lands on: with the eye 1.62 above the feet and a 4.5 reach, the feet must be within 2.88 of the
 *       landing. Distance and fall speed are measured each tick to place on exactly that tick — and to
 *       recognise the case where the next tick of physics would carry the player into the ground, which
 *       is reported as {@code too_late} instead of being dressed up as an attempt.</li>
 *   <li><b>Whether it actually worked.</b> Health before the fall is compared with health after: a
 *       claimed cushion that does not hold is reported, not glossed over.</li>
 * </ol>
 */
public final class MlgTask extends ClientTask {
	private static final String WATER_BUCKET = "minecraft:water_bucket";
	private static final String BUCKET = "minecraft:bucket";

	/** The eye sits about this far above the feet. */
	private static final double EYE_HEIGHT = 1.62;
	/** Vanilla survival block reach: how far the placement ray can land. */
	private static final double REACH = 4.5;
	/** Feet must be within this of the landing surface for the ray to reach it. */
	private static final double PLACE_WINDOW = REACH - EYE_HEIGHT;
	private static final int SCAN_DEPTH = 64;
	/** A drop shorter than this hurts nobody. */
	private static final double HARMLESS_DROP = 4.0;
	/** Vanilla fall damage is (distance - 3). */
	private static final double FALL_DAMAGE_OFFSET = 3.0;
	/** Ticks to keep trying to take the water back before reporting what is left behind. */
	private static final int SCOOP_LIMIT = 100;

	/** Surfaces that absorb a drop entirely, so no bucket is needed. */
	private static final Set<String> SOFT_LANDING = Set.of(
			"minecraft:cobweb", "minecraft:powder_snow", "minecraft:slime_block",
			"minecraft:sweet_berry_bush");
	/** Surfaces that absorb most of a drop (vanilla hay cuts fall damage by 80%). */
	private static final Set<String> CUSHIONING_LANDING = Set.of("minecraft:hay_block");

	/** What the scan found: where the landing is and whether it is worth saving. */
	private record Landing(String block, double distance, boolean liquid, boolean replaceableAbove,
			boolean soft, boolean cushioned) {}

	private final boolean[] surveyed = new boolean[1];
	private Landing landing;
	private String lastBlock = "";
	private float startHealth = 20.0F;

	private int ticks;
	private int savedSlot = -1;
	private boolean used;

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			failed("no_world");
			return;
		}
		if (expired()) {
			restoreSlot(mc);
			failed(used ? "timeout" : "never_reached_window");
			return;
		}

		boolean falling = !p.onGround() && !p.isInWater() && !p.getAbilities().flying && !p.isFallFlying();
		if (used && !falling) {
			tickRecovering(mc, p);
			return;
		}

		if (!surveyed[0]) {
			surveyed[0] = true;
			startHealth = p.getHealth();
			if (!falling) {
				done("nothing_to_save", report(p, "not_falling"));
				return;
			}
			int slot = findHotbar(p, WATER_BUCKET);
			if (slot < 0) {
				failed("no_water_bucket", report(p, null));
				return;
			}
			savedSlot = selected(p);
			select(p, slot);
		}

		// Re-measure every tick: the player drifts horizontally on the way down, and a cushion can be
		// taken away again (water drains), so an old reading is not evidence about the landing.
		landing = survey(level, p);
		lastBlock = landing == null ? "" : landing.block();

		if (!falling) {
			// Down, without our water. The health comparison is the honest verdict on whether the
			// landing actually cushioned the fall.
			if (p.getHealth() >= startHealth) {
				done("nothing_to_save", report(p, "landed_safely"));
			} else if (p.getHealth() > 0.0F) {
				done("landed_with_damage", report(p, null));
			} else {
				failed("landing_did_not_cushion", report(p, null));
			}
			return;
		}

		if (landing == null) {
			failed("no_landing_in_range", report(p, null));
			return;
		}
		double drop = p.fallDistance + landing.distance();
		// Two separate questions, and mixing them up is how an earlier version talked itself out of
		// saving a fatal drop: "is this height dangerous at all" must be asked without the landing's
		// cushion folded in, otherwise any water below is mistaken for a harmless fall.
		double rawDamage = Math.max(0.0, drop - FALL_DAMAGE_OFFSET);
		if (rawDamage < HARMLESS_DROP) {
			done("nothing_to_save", report(p, "fall_would_not_hurt"));
			return;
		}
		if (landing.cushioned() || landing.soft()) {
			// The landing might absorb it — but stay armed and keep re-checking all the way down. A thin
			// sheet of water can drain away before we arrive, and hay only takes 80% off, so an early
			// "looks fine" is not something to bet a life on.
			ticks++;
			return;
		}
		if (!landing.replaceableAbove()) {
			failed("cannot_place", report(p, "landing_not_placeable"));
			return;
		}
		tryPlace(p);
	}

	/** Place on the exact tick the bucket can reach the ground; say so when that tick has gone by. */
	private void tryPlace(LocalPlayer p) {
		double h = landing == null ? -1.0 : landing.distance();
		double speed = Math.abs(p.getDeltaMovement().y);
		if (h < 0.0) return;

		if (h <= PLACE_WINDOW) {
			BotController.get().snapLook(p.getYRot(), 90.0F);
			BotController.get().setMovement(false, false, false, false, false, false, false);
			BotController.get().setAttackHeld(false);
			Minecraft mc = Minecraft.getInstance();
			if (mc.gameMode != null) {
				mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
				p.swing(InteractionHand.MAIN_HAND);
				if (!holdsWater(p)) used = true;
			}
			ticks++;
			return;
		}
		if (speed > 0.0 && h - speed <= 0.0) {
			// Next tick of physics puts us into the ground and the window has already been stepped over.
			restoreSlot(Minecraft.getInstance());
			failed("too_late", report(p, "window_passed_between_ticks"));
			return;
		}
		ticks++;
	}

	/**
	 * Take the water back, and keep checking until it is actually gone.
	 *
	 * <p>This is not a formality. The source that saved the fall spreads sideways the moment it lands,
	 * the flowing water pushes the player along with it, and on a narrow ledge that is enough to shove
	 * them straight back off — so a save is only a save once the water is out of the world and the
	 * player is confirmed standing on something. Reporting success and looking away is exactly how an
	 * earlier version left the player to be washed off the platform.
	 */
	private void tickRecovering(Minecraft mc, LocalPlayer p) {
		ClientLevel level = mc.level;
		BotController.get().setUseHeld(false);
		BotController.get().setMovement(false, false, false, false, false, false, false);
		ticks++;

		BlockPos source = level == null ? null : nearestWaterSource(level, p);
		if (source != null) {
			int slot = findHotbar(p, BUCKET);
			if (slot < 0) {
				// Nothing to scoop with: leave honestly, flagging the water we could not take back.
				finishRecovery(mc, p, "saved_water_left", source);
				return;
			}
			if (selected(p) != slot) select(p, slot);
			// Snap onto the source itself rather than straight down: the flow may already have carried
			// the player off the block they landed on, and a ray down would then miss it entirely.
			aimAt(p, source);
			if (mc.gameMode != null) {
				mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
				p.swing(InteractionHand.MAIN_HAND);
			}
			if (ticks > SCOOP_LIMIT) finishRecovery(mc, p, "saved_water_left", source);
			return;
		}

		// No source left; the flowing water will drain on its own. Confirm where we actually ended up
		// before calling it a success.
		if (p.onGround() && !p.isInWater()) {
			finishRecovery(mc, p, "saved", null);
			return;
		}
		if (ticks > SCOOP_LIMIT) finishRecovery(mc, p, "saved_not_on_ground", null);
	}

	private void finishRecovery(Minecraft mc, LocalPlayer p, String detail, BlockPos waterLeft) {
		BotController.get().setUseHeld(false);
		restoreSlot(mc);
		JsonObject extra = report(p, null);
		extra.addProperty("waterLeft", waterLeft != null);
		done(detail, extra);
	}

	/** The nearest water source within a few blocks, or null. Only sources need scooping. */
	private static BlockPos nearestWaterSource(ClientLevel level, LocalPlayer p) {
		int x = Mth.floor(p.getX());
		int y = Mth.floor(p.getY());
		int z = Mth.floor(p.getZ());
		BlockPos best = null;
		double bestD = Double.MAX_VALUE;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -3; dy <= 2; dy++) {
				for (int dz = -3; dz <= 3; dz++) {
					BlockPos pos = new BlockPos(x + dx, y + dy, z + dz);
					if (!level.getBlockState(pos).getFluidState().isSource()) continue;
					double d = p.position().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
					if (d < bestD) {
						bestD = d;
						best = pos;
					}
				}
			}
		}
		return best;
	}

	/** Point the camera straight at a block, immediately — used for scooping. */
	private static void aimAt(LocalPlayer p, BlockPos pos) {
		double dx = pos.getX() + 0.5 - p.getX();
		double dy = pos.getY() + 0.5 - p.getEyeY();
		double dz = pos.getZ() + 0.5 - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		float pitch = (float) (-(Math.atan2(dy, Math.max(horiz, 0.01)) * (180.0 / Math.PI)));
		BotController.get().snapLook(yaw, pitch);
	}

	@Override
	public void onCancel(Minecraft mc) {
		BotController.get().setUseHeld(false);
		BotController.get().setMovement(false, false, false, false, false, false, false);
		restoreSlot(mc);
	}

	@Override
	public JsonObject progress() {
		LocalPlayer p = Minecraft.getInstance().player;
		JsonObject o = new JsonObject();
		o.addProperty("waterPlaced", used);
		if (p != null) {
			o.addProperty("feetY", round(p.getY()));
			o.addProperty("fallSpeed", round(Math.abs(p.getDeltaMovement().y)));
			o.addProperty("fallDistance", round(p.fallDistance));
			if (landing != null) {
				o.addProperty("landingBlock", landing.block());
				o.addProperty("distanceToLanding", round(landing.distance()));
				o.addProperty("placeWindow", PLACE_WINDOW);
				o.addProperty("landingCushions", landing.cushioned() || landing.soft());
			}
		}
		return o;
	}

	@Override
	public String describe() {
		return "water-bucket fall save onto " + (lastBlock.isEmpty() ? "the ground" : lastBlock);
	}

	// --- measurement ---------------------------------------------------------------------------

	/**
	 * Scan the column under the player for the surface they would land on, and describe it: what it is,
	 * how far away it is, whether it absorbs a drop on its own, and whether the cell above it could take
	 * a water source.
	 */
	private static Landing survey(ClientLevel level, LocalPlayer p) {
		int feetY = Mth.floor(p.getY());
		int x = Mth.floor(p.getX());
		int z = Mth.floor(p.getZ());
		for (int dy = 0; dy <= SCAN_DEPTH; dy++) {
			BlockPos pos = new BlockPos(x, feetY - dy, z);
			BlockState state = level.getBlockState(pos);
			boolean fluid = !state.getFluidState().isEmpty();
			// Open air is not a landing: keep looking down.
			if (!fluid && state.getCollisionShape(level, pos).isEmpty()) continue;

			String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
			double distance = p.getY() - (pos.getY() + 1.0);
			BlockPos above = pos.above();
			BlockState aboveState = level.getBlockState(above);
			boolean replaceableAbove = aboveState.getCollisionShape(level, above).isEmpty()
					&& aboveState.getFluidState().isEmpty();
			boolean soft = CUSHIONING_LANDING.contains(id);
			return new Landing(id, distance, fluid, replaceableAbove, soft,
					fluid || SOFT_LANDING.contains(id));
		}
		return null;
	}

	/** Fall damage the player is heading for, after accounting for a cushioning surface. */
	private static double damageAfter(double drop, boolean cushioned, boolean soft) {
		if (cushioned) return 0.0;
		double damage = drop - FALL_DAMAGE_OFFSET;
		if (damage <= 0.0) return 0.0;
		return soft ? damage * 0.2 : damage;
	}

	/** Everything the caller needs to see why the outcome was what it was. */
	private JsonObject report(LocalPlayer p, String reason) {
		JsonObject o = new JsonObject();
		o.addProperty("placed", used);
		o.addProperty("fallDistance", round(p.fallDistance));
		o.addProperty("onGround", p.onGround());
		o.addProperty("health", p.getHealth());
		if (reason != null) o.addProperty("reason", reason);
		if (landing != null) {
			o.addProperty("landingBlock", landing.block());
			o.addProperty("distanceToLanding", round(landing.distance()));
			o.addProperty("landingCushions", landing.cushioned() || landing.soft());
			double drop = p.fallDistance + landing.distance();
			// Both figures, so the two questions stay visibly separate: what the drop would do on its
			// own, and what it would do if the landing's cushion holds.
			o.addProperty("rawDamage", round(Math.max(0.0, drop - FALL_DAMAGE_OFFSET)));
			o.addProperty("predictedDamage", round(damageAfter(drop, landing.cushioned(), landing.soft())));
		}
		return o;
	}

	private void restoreSlot(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (savedSlot >= 0 && p != null) {
			select(p, savedSlot);
			savedSlot = -1;
		}
	}

	/** True while the player is still holding the water bucket, i.e. nothing has been emptied yet. */
	private static boolean holdsWater(LocalPlayer p) {
		ItemStack held = p.getMainHandItem();
		return !held.isEmpty()
				&& BuiltInRegistries.ITEM.getKey(held.getItem()).toString().equals(WATER_BUCKET);
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
		return p.getInventory().getSelectedSlot();
	}

	private static void select(LocalPlayer p, int slot) {
		p.getInventory().setSelectedSlot(slot);
		p.connection.send(new ServerboundSetCarriedItemPacket(slot));
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
