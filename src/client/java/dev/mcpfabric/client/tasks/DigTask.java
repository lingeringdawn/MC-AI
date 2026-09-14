package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.Humanizer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Break one block. That is the whole module.
 *
 * <p>It does not walk anywhere, it does not clear anything out of the way, and it does not choose what to
 * mine: the caller names a position that is already in reach and this digs it, then says how it went.
 * Everything the old "mine a block" did around the digging — closing the distance, digging through the
 * vine in front of the trunk, deciding that the obstruction was worth the cost — was a decision, and
 * decisions belong to the caller. When the shot is blocked this reports <em>what</em> is blocking it
 * ({@code blocked} + {@code blockedBy}/{@code blockedAt}) and hands control straight back, so clearing
 * the way is something the caller can choose rather than something that quietly happens.
 *
 * <p>Two things it does keep, because they are part of the act and not of the strategy:
 * <ul>
 *   <li>it holds the best tool the hotbar has for this block — digging stone with a pickaxe in the bag
 *       is not a decision, it is doing it properly;</li>
 *   <li>it keeps the crosshair on the block while it digs, and stops the instant the aim can no longer
 *       hold, rather than swinging into whatever drifted under the crosshair.</li>
 * </ul>
 */
public final class DigTask extends ClientTask {
	/** Vanilla reach. Out of this, the block cannot be touched — a moveTo, not a bigger number. */
	private static final double REACH = 4.5;
	/** Ticks the crosshair rests on the block before the first swing (see {@link Humanizer}). */
	private static final int WINDUP_TICKS = 2;
	/** Ticks the aim is given to turn onto the block before the shot is judged as blocked. */
	private static final int AIM_SETTLE_TICKS = 15;
	/** Re-pick the tool this often, so a pickaxe breaking mid-dig is noticed. */
	private static final int TOOL_CHECK_TICKS = 10;

	private final BlockPos pos;
	private int aimTicks;
	private int settleTicks;
	private int toolTicks;
	private int toolSlot = -1;
	/** The block whose press edge has already been sent, so a new one is not issued every tick. */
	private BlockPos digging;
	private boolean began;

	public DigTask(BlockPos pos) {
		this.pos = pos;
	}

	@Override
	public void onStart(Minecraft mc) {
		began = mc.player != null && mc.level != null;
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (!began || p == null || level == null) {
			failed("no_world", where());
			return;
		}
		if (level.getBlockState(pos).isAir()) {
			stopDigging();
			done("mined", where());
			return;
		}
		if (expired()) {
			stopDigging();
			failed("timeout", where());
			return;
		}
		if (p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) > REACH) {
			// Never walks. Closing the distance is a moveTo the caller composes, and folding it in here is
			// what turned "mine a block" into a behaviour that could be asked for a block it could not
			// reach and then spend its whole budget finding out.
			stopDigging();
			failed("out_of_reach", where());
			return;
		}

		if (toolTicks-- <= 0) {
			toolTicks = TOOL_CHECK_TICKS;
			pickTool(p, level);
		}
		aim(p);

		// Let the head finish turning before judging what the crosshair is on: on the first tick almost
		// nothing is aimed at anything yet, and calling that "blocked" would be a lie.
		if (aimTicks < AIM_SETTLE_TICKS) {
			aimTicks++;
			stopDigging();
			return;
		}

		BlockPos on = crosshairBlock(mc);
		if (on == null || !on.equals(pos)) {
			stopDigging();
			JsonObject extra = where();
			extra.addProperty("aimErrorDeg", round(BotController.get().lookErrorDeg()));
			if (on != null) {
				// Name what the click would actually hit. Clearing it is the caller's call, but the caller
				// should not have to go and ask another module which block is in the way.
				extra.addProperty("blockedBy", idOf(level, on));
				extra.add("blockedAt", blockJson(on));
				extra.addProperty("blockedDistance", round(p.getEyePosition().distanceTo(Vec3.atCenterOf(on))));
				failed("blocked", extra);
			} else {
				extra.addProperty("crosshair", mc.hitResult == null ? "null"
						: mc.hitResult.getType().name().toLowerCase());
				failed("interrupted", extra);
			}
			return;
		}
		if (settleTicks < WINDUP_TICKS) {
			settleTicks++;
			stopDigging();
			return;
		}
		dig(pos);
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.add("target", blockJson(pos));
		o.addProperty("toolSlot", toolSlot);
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		if (p != null) {
			o.addProperty("inReach", p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= REACH);
			o.addProperty("distance", round(p.getEyePosition().distanceTo(Vec3.atCenterOf(pos))));
			o.addProperty("aimErrorDeg", round(BotController.get().lookErrorDeg()));
		}
		o.addProperty("crosshairOnTarget", BlockHitResult.class.isInstance(mc.hitResult)
				&& crosshairBlock(mc) != null && pos.equals(crosshairBlock(mc)));
		o.addProperty("aimTicks", aimTicks);
		o.addProperty("digging", digging != null);
		// Naming what the crosshair is actually on is the difference between "stuck" and "the leaf in
		// front of the trunk", which are the same thing from the outside and want different answers.
		BlockPos on = crosshairBlock(mc);
		if (on != null && !on.equals(pos) && mc.level != null) {
			o.addProperty("crosshairOnBlock", idOf(mc.level, on));
		}
		return o;
	}

	@Override
	public String describe() {
		return "dig " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
	}

	@Override
	public void onCancel(Minecraft mc) {
		stopDigging();
	}

	// --- the act -----------------------------------------------------------------------------------

	/**
	 * Dig by holding the real attack key and, once per block, registering a click — the same two things
	 * a person does. Vanilla's own input handling then fires the press edge that starts the break and
	 * carries it on for as long as the button stays down; going through the pipeline is what makes the
	 * break one the server agrees to, so the block drops what it should.
	 */
	private void dig(BlockPos target) {
		BotController.get().setAttackHeld(true);
		if (!target.equals(digging)) {
			digging = target.immutable();
			KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(0));
		}
	}

	private void stopDigging() {
		digging = null;
		// Never leave the button held: a stuck attack key digs whatever the crosshair drifts onto.
		BotController.get().setAttackHeld(false);
	}

	/** Aim at the centre of the block's shape (vines etc. are not full cubes). */
	private void aim(LocalPlayer p) {
		Vec3 point = Vec3.atCenterOf(pos);
		double dx = point.x - p.getX();
		double dy = point.y - p.getEyeY();
		double dz = point.z - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		float pitch = (float) (-(Math.atan2(dy, horiz) * (180.0 / Math.PI)));
		// Task priority: aiming at the block being dug outranks the walking direction, so the two do not
		// alternate control of the camera every tick.
		BotController.get().lookAtTarget(yaw, pitch, BotController.LOOK_TASK);
	}

	/**
	 * Hold the best tool the hotbar has for this block, measured as "correct tool for the drop" first and
	 * breaking speed second. Part of digging properly rather than a choice about what to dig.
	 */
	private void pickTool(LocalPlayer p, ClientLevel level) {
		BlockState st = level.getBlockState(pos);
		Inventory inv = p.getInventory();
		int best = selected(p);
		float bestScore = score(inv.getItem(best), st);
		for (int slot = 0; slot < 9; slot++) {
			float s = score(inv.getItem(slot), st);
			if (s > bestScore + 1.0E-4F) {
				bestScore = s;
				best = slot;
			}
		}
		if (best != selected(p)) select(p, best);
		toolSlot = best;
	}

	/** Which hotbar slot is held. The accessor for this changed in 1.21.5. */
	private static int selected(LocalPlayer p) {
		//? if >=1.21.5 {
		return p.getInventory().getSelectedSlot();
		//?} else
		/*return p.getInventory().selected;*/
	}

	/**
	 * Hold a hotbar slot, and tell the server about it. Switching only on the client leaves the server
	 * believing the old item is in hand — which is exactly how "correct tool for the drop" turns into a
	 * bare hand on the server's side of the break.
	 */
	private static void select(LocalPlayer p, int slot) {
		//? if >=1.21.5 {
		p.getInventory().setSelectedSlot(slot);
		//?} else
		/*p.getInventory().selected = slot;*/
		if (p.connection != null) p.connection.send(new ServerboundSetCarriedItemPacket(slot));
	}

	private static float score(ItemStack stack, BlockState state) {
		if (stack.isEmpty()) return 0.0F;
		float bonus = stack.isCorrectToolForDrops(state) ? 100.0F : 0.0F;
		return bonus + stack.getDestroySpeed(state);
	}

	// --- reading the shot --------------------------------------------------------------------------

	private static BlockPos crosshairBlock(Minecraft mc) {
		return mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK
				? b.getBlockPos() : null;
	}

	private static String idOf(ClientLevel level, BlockPos at) {
		return BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock()).toString();
	}

	private JsonObject where() {
		JsonObject o = new JsonObject();
		o.add("target", blockJson(pos));
		o.addProperty("targetId", Minecraft.getInstance().level == null ? ""
				: BuiltInRegistries.BLOCK.getKey(
						Minecraft.getInstance().level.getBlockState(pos).getBlock()).toString());
		return o;
	}

	private static JsonObject blockJson(BlockPos at) {
		JsonObject o = new JsonObject();
		o.addProperty("x", at.getX());
		o.addProperty("y", at.getY());
		o.addProperty("z", at.getZ());
		return o;
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
