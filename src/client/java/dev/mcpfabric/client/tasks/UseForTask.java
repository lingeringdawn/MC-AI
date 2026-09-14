package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

/**
 * Hold the use button for a while, then let go.
 *
 * <p>One physical act, and the only one that can finish an act that takes time: eating takes about 32
 * ticks, a potion a few less, a bow draws for as long as it is held, a shield stays up until it is
 * lowered. All of those are the same gesture — button down, wait, button up — so they are one module
 * rather than one module each, and nothing here decides which of them is happening.
 *
 * <p>It reports what it was holding before and after and whether the stack was consumed, so the caller
 * can tell "ate the bread" from "stood there holding a torch", without a second lookup.
 */
public final class UseForTask extends ClientTask {
	/** Long enough for a full meal; the caller can ask for more or less. */
	public static final int DEFAULT_TICKS = 35;

	private final int ticks;
	private int heldTicks;
	private ItemStack before = ItemStack.EMPTY;
	private String beforeId = "";
	private boolean began;

	public UseForTask(int ticks) {
		this.ticks = Math.max(1, Math.min(1200, ticks));
	}

	@Override
	public void onStart(Minecraft mc) {
		LocalPlayer p = mc.player;
		began = p != null;
		if (p != null) {
			before = p.getMainHandItem().copy();
			beforeId = idOf(before);
		}
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (!began || p == null) {
			failed("no_world");
			return;
		}
		if (expired()) {
			release();
			failed("timeout", report(p));
			return;
		}
		if (heldTicks >= ticks) {
			release();
			done("used", report(p));
			return;
		}
		// Button down and let vanilla do the rest: eating takes the ticks it takes, and holding is how
		// the game is told to spend them. Release happens below, or in onCancel, and nowhere else.
		BotController.get().setUseHeld(true);
		heldTicks++;
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.addProperty("heldTicks", heldTicks);
		o.addProperty("ticks", ticks);
		o.addProperty("holding", beforeId);
		return o;
	}

	@Override
	public String describe() {
		return "use " + beforeId + " for " + ticks + " ticks";
	}

	@Override
	public void onCancel(Minecraft mc) {
		release();
	}

	private void release() {
		// Never leave the button held: a stuck use key eats the next thing the crosshair finds.
		BotController.get().setUseHeld(false);
	}

	private JsonObject report(LocalPlayer p) {
		JsonObject o = new JsonObject();
		ItemStack now = p.getMainHandItem();
		o.addProperty("holdingBefore", beforeId);
		o.addProperty("holdingAfter", idOf(now));
		o.addProperty("heldTicks", heldTicks);
		// "consumed" is the fact that separates a meal from a held torch: the stack got smaller, or the
		// whole item went.
		o.addProperty("consumed", now.isEmpty() || now.getCount() < before.getCount());
		return o;
	}

	private static String idOf(ItemStack st) {
		return st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
	}
}
