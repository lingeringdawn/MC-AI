package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

/**
 * The GUI: open, close, look inside, and click a slot.
 *
 * <p>Clicking a slot is the atomic act behind every container in the game — taking a crafted item,
 * pulling something out of a chest, feeding a furnace, moving a stack between the hotbar and the bag —
 * so it is one module rather than one module per screen. It reports what the click did, and it does not
 * decide anything: what to click and when is the caller's.
 */
public final class UiHandlers {
	private UiHandlers() {}

	public static void register(RpcRouter router) {
		// What is on screen, and what is in every slot of it. Slot indices are what ui.clickSlot takes,
		// so this is the reading a caller needs before it can click anything sensibly.
		router.register("ui.state", ctx -> ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			LocalPlayer p = ClientMc.player();
			JsonObject o = new JsonObject();
			o.addProperty("screen", screenName(mc.screen));
			o.addProperty("hasScreen", mc.screen != null);
			AbstractContainerMenu menu = p.containerMenu;
			o.addProperty("containerId", menu.containerId);
			o.addProperty("menu", menuName(menu));
			o.addProperty("slotCount", menu.slots.size());
			JsonArray slots = new JsonArray();
			for (int i = 0; i < menu.slots.size(); i++) {
				ItemStack st = menu.slots.get(i).getItem();
				JsonObject s = new JsonObject();
				s.addProperty("i", i);
				if (!st.isEmpty()) {
					s.addProperty("id", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
					s.addProperty("n", st.getCount());
				}
				slots.add(s);
			}
			o.add("slots", slots);
			ItemStack carried = menu.getCarried();
			o.addProperty("carried", carried.isEmpty() ? ""
					: BuiltInRegistries.ITEM.getKey(carried.getItem()) + "x" + carried.getCount());
			o.addProperty("note", "slots is the full index list — empty entries carry only 'i'. In a crafting "
					+ "menu slot 0 is the result, 1..9 the grid, then the inventory; in the player inventory "
					+ "menu slot 0 is the result, 1..4 the 2x2 grid, 5..8 armour and 9..44 the inventory.");
			return o;
		}));

		router.register("ui.openInventory", ctx -> ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			LocalPlayer p = ClientMc.player();
			if (mc.screen != null) mc.setScreen(null);
			mc.setScreen(new InventoryScreen(p));
			return Json.ok("inventory opened");
		}));

		router.register("ui.close", ctx -> ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			LocalPlayer p = mc.player;
			if (p != null && p.containerMenu != p.inventoryMenu) {
				p.closeContainer();
			}
			mc.setScreen(null);
			return Json.ok("screen closed");
		}));

		// One slot click, through the same call vanilla's mouse handler makes. Everything container-shaped
		// is this: taking a craft, a chest, a furnace, shuffling the bag.
		router.register("ui.clickSlot", ctx -> ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			LocalPlayer p = ClientMc.player();
			AbstractContainerMenu menu = p.containerMenu;
			int slot = ctx.getInt("slot");
			if (slot < 0 || slot >= menu.slots.size()) {
				throw RpcException.badRequest("Slot " + slot + " is out of range: this menu ("
						+ menuName(menu) + ") has " + menu.slots.size() + " slots. Read ui.state for the list.");
			}
			int button = ctx.optInt("button", 0);
			String mode = ctx.has("mode") ? ctx.getString("mode") : "pickup";
			ItemStack before = menu.slots.get(slot).getItem().copy();
			ItemStack carriedBefore = menu.getCarried().copy();
			click(mc, menu, p, slot, button, mode);

			JsonObject o = new JsonObject();
			o.addProperty("clicked", slot);
			o.addProperty("mode", mode);
			o.addProperty("button", button);
			o.addProperty("menu", menuName(menu));
			o.addProperty("slotBefore", idOf(before));
			o.addProperty("carriedBefore", idOf(carriedBefore));
			o.addProperty("carriedAfter", idOf(menu.getCarried()));
			// "clicked" would be a lie if the server refused it; report what is in the slot now.
			o.addProperty("slotAfter", idOf(menu.slots.get(slot).getItem()));
			return o;
		}));
	}

	private static void click(Minecraft mc, AbstractContainerMenu menu, LocalPlayer p, int slot, int button,
			String mode) throws RpcException {
		ClickType type = switch (mode) {
			case "quick_move" -> ClickType.QUICK_MOVE;
			case "swap" -> ClickType.SWAP;
			case "throw" -> ClickType.THROW;
			case "clone" -> ClickType.CLONE;
			case "pickup" -> ClickType.PICKUP;
			default -> throw RpcException.badRequest("Unknown click mode '" + mode + "': use pickup (left/right "
					+ "click, button 0 or 1), quick_move (shift-click), swap, throw or clone.");
		};
		if (mc.gameMode == null) throw RpcException.unavailable("No game mode yet.");
		//? if <26.1 {
		mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, button, type, p);
		//?} else
		/*mc.gameMode.handleContainerInput(menu.containerId, slot, button, net.minecraft.world.inventory.ContainerInput.PICKUP, p);*/
	}

	private static String idOf(ItemStack st) {
		return st.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(st.getItem()) + "x" + st.getCount();
	}

	/** Friendly name for the open screen (runtime class names are obfuscated). */
	private static String screenName(Screen s) {
		if (s == null) return "none";
		if (s instanceof InventoryScreen) return "inventory";
		if (s instanceof net.minecraft.client.gui.screens.inventory.CraftingScreen) return "crafting_table";
		if (s instanceof net.minecraft.client.gui.screens.inventory.FurnaceScreen) return "furnace";
		if (s instanceof net.minecraft.client.gui.screens.PauseScreen) return "pause";
		if (s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen) return "container";
		return s.getClass().getSimpleName();
	}

	/** Friendly name for the active container menu. */
	private static String menuName(AbstractContainerMenu m) {
		if (m == null) return "none";
		if (m instanceof net.minecraft.world.inventory.InventoryMenu) return "inventory";
		if (m instanceof net.minecraft.world.inventory.CraftingMenu) return "crafting";
		if (m instanceof net.minecraft.world.inventory.FurnaceMenu) return "furnace";
		if (m instanceof net.minecraft.world.inventory.ChestMenu) return "chest";
		return m.getClass().getSimpleName();
	}
}
