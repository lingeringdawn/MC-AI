package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;

/** Open / close / inspect the player's GUI screens (menus), the way a player presses E or Esc. */
public final class UiHandlers {
	private UiHandlers() {}

	public static void register(RpcRouter router) {
		router.register("ui.state", ctx -> ClientMc.call(() -> {
			Minecraft mc = ClientMc.mc();
			JsonObject o = new JsonObject();
			o.addProperty("screen", screenName(mc.screen));
			o.addProperty("hasScreen", mc.screen != null);
			LocalPlayer p = mc.player;
			if (p != null) {
				o.addProperty("containerId", p.containerMenu.containerId);
				o.addProperty("menu", menuName(p.containerMenu));
			}
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
