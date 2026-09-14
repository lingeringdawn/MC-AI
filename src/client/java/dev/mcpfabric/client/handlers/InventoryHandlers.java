package dev.mcpfabric.client.handlers;

import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
//? if <1.21.11 {
import net.minecraft.resources.ResourceLocation;
//?} else
/*import net.minecraft.resources.Identifier;*/
import net.minecraft.world.item.Item;
//? if <26.1 {
import net.minecraft.world.inventory.ClickType;
//?}

/** Inventory manipulation: hotbar selection, dropping, swapping, and real crafting (2x2 / 3x3). */
public final class InventoryHandlers {
	private InventoryHandlers() {}

	public static void register(RpcRouter router) {
		router.register("inventory.selectHotbar", ctx -> ClientMc.call(() -> {
			int slot = ctx.getInt("slot");
			if (slot < 0 || slot > 8) throw RpcException.badRequest("Hotbar slot must be 0-8.");
			LocalPlayer p = ClientMc.player();
			//? if >=1.21.5 {
			p.getInventory().setSelectedSlot(slot);
			//?} else
			/*p.getInventory().selected = slot;*/
			p.connection.send(new ServerboundSetCarriedItemPacket(slot));
			return Json.ok("selected slot " + slot);
		}));

		router.register("inventory.dropSlot", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			int menuSlot = toMenuSlot(ctx.getInt("slot"));
			boolean whole = ctx.optBool("wholeStack", true);
			containerClick(gm, p.inventoryMenu.containerId, menuSlot, whole ? 1 : 0, true, p);
			return Json.ok("dropped slot");
		}));

		router.register("inventory.swapSlots", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			int a = toMenuSlot(ctx.getInt("slotA"));
			int b = toMenuSlot(ctx.getInt("slotB"));
			int id = p.inventoryMenu.containerId;
			containerClick(gm, id, a, 0, false, p);
			containerClick(gm, id, b, 0, false, p);
			containerClick(gm, id, a, 0, false, p);
			return Json.ok("swapped");
		}));

		// Crafting is the very same task as action.craft: it opens the container screen like a player
		// pressing E and clicks the grid one slot per tick, so it is visible and blocks until done.
		router.register("inventory.craft", ActionHandlers::craftAlias);
	}

	/**
	 * Click a container slot. {@code handleInventoryMouseClick(..., ClickType, ...)} became
	 * {@code handleContainerInput(..., ContainerInput, ...)} in 26.1 (same constant names).
	 */
	private static void containerClick(MultiPlayerGameMode gm, int containerId, int slot, int button, boolean throwItem, LocalPlayer p) {
		//? if <26.1 {
		gm.handleInventoryMouseClick(containerId, slot, button, throwItem ? ClickType.THROW : ClickType.PICKUP, p);
		//?} else
		/*gm.handleContainerInput(containerId, slot, button, throwItem ? net.minecraft.world.inventory.ContainerInput.THROW : net.minecraft.world.inventory.ContainerInput.PICKUP, p);*/
	}

	/** Resolve an item id ("oak_log" or "minecraft:oak_log"); null when unknown. */
	public static Item itemById(String id) {
		String normalized = id.indexOf(':') >= 0 ? id : "minecraft:" + id;
		//? if <1.21.11 {
		ResourceLocation rl = ResourceLocation.tryParse(normalized);
		//?} else
		/*Identifier rl = Identifier.tryParse(normalized);*/
		if (rl == null) return null;
		//? if >=1.21.2 {
		// Registry#get(ResourceLocation) returns an Optional holder reference on 1.21.2+.
		Item item = BuiltInRegistries.ITEM.get(rl).map(ref -> ref.value()).orElse(null);
		//?} else
		/*Item item = BuiltInRegistries.ITEM.get(rl);*/
		if (item == null) return null;
		// A defaulted registry may hand back air for unknown ids; verify the round-trip instead.
		return BuiltInRegistries.ITEM.getKey(item).toString().equals(rl.toString()) ? item : null;
	}

	/**
	 * Map a player-inventory index to the slot index inside the player's {@code InventoryMenu}.
	 * Convention: 0-8 hotbar, 9-35 main, 36-39 armor (helmet..boots), 40 offhand.
	 */
	private static int toMenuSlot(int inv) throws RpcException {
		if (inv >= 0 && inv <= 8) return 36 + inv;       // hotbar -> menu 36-44
		if (inv >= 9 && inv <= 35) return inv;            // main -> menu 9-35
		if (inv >= 36 && inv <= 39) return 5 + (inv - 36); // armor -> menu 5-8 (helmet..boots)
		if (inv == 40) return 45;                         // offhand -> menu 45
		throw RpcException.badRequest("Inventory slot out of range (0-40): " + inv);
	}
}
