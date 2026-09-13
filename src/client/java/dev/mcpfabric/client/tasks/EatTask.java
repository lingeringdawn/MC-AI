package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.item.ItemStack;

/**
 * Eat food from the hotbar until the hunger bar is full — the "I'm hungry" intent as one call. It
 * holds the real "use" key, so the vanilla eating animation and timing are used.
 */
public final class EatTask extends ClientTask {
	private static final int FOOD_FULL = 20;
	private static final int MAX_TICKS = 20 * 20; // 20s hard cap

	private int selectedSlot = -1;
	private int ticks;

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			failed("no_world");
			return;
		}
		if (p.getFoodData().getFoodLevel() >= FOOD_FULL) {
			release(mc);
			done("full");
			return;
		}
		if (expired() || ticks++ > MAX_TICKS) {
			release(mc);
			failed("timeout");
			return;
		}
		int slot = findFood(p);
		if (slot < 0) {
			release(mc);
			failed("no_food");
			return;
		}
		if (slot != selectedSlot) {
			select(p, slot);
			selectedSlot = slot;
		}
		BotController.get().setUseHeld(true);
	}

	@Override
	public JsonObject progress() {
		LocalPlayer p = Minecraft.getInstance().player;
		JsonObject o = new JsonObject();
		if (p != null) {
			o.addProperty("food", p.getFoodData().getFoodLevel());
			o.addProperty("usingItem", p.isUsingItem());
		}
		o.addProperty("slot", selectedSlot);
		return o;
	}

	@Override
	public String describe() {
		return "eat until the hunger bar is full";
	}

	@Override
	public void onCancel(Minecraft mc) {
		release(mc);
	}

	private void release(Minecraft mc) {
		BotController.get().setUseHeld(false);
		LocalPlayer p = mc.player;
		if (p != null) p.stopUsingItem();
	}

	/** Prefer the already-held slot, else the first edible hotbar item. */
	private static int findFood(LocalPlayer p) {
		int cur = selected(p);
		ItemStack held = p.getInventory().getItem(cur);
		if (!held.isEmpty() && held.has(DataComponents.FOOD)) return cur;
		for (int i = 0; i <= 8; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (!st.isEmpty() && st.has(DataComponents.FOOD)) return i;
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
