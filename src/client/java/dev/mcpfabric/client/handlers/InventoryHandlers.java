package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcContext;
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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
//? if <26.1 {
import net.minecraft.world.inventory.ClickType;
//?}

import java.util.LinkedHashMap;
import java.util.Map;

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

		router.register("inventory.craft", ctx -> ClientMc.call(() -> craftGrid(ctx)));
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

	/** Shift-click a slot, moving its contents to the natural target (used for the crafting result). */
	private static void containerQuickMove(MultiPlayerGameMode gm, int containerId, int slot, LocalPlayer p) {
		//? if <26.1 {
		gm.handleInventoryMouseClick(containerId, slot, 0, ClickType.QUICK_MOVE, p);
		//?} else
		/*gm.handleContainerInput(containerId, slot, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p);*/
	}

	/**
	 * Perform a REAL craft using the currently open container grid. It drives the vanilla crafting menu
	 * with ordinary container clicks, so the recipe match, ingredient consumption, result count and any
	 * advancement all come from the game itself — nothing is spawned.
	 *
	 * <p>{@code grid} is row-major: 4 entries while the player inventory (2x2) is active, or 9 while a
	 * crafting table (3x3) is open. Each entry is an item id (e.g. "minecraft:oak_planks") or null.
	 */
	private static JsonElement craftGrid(RpcContext ctx) throws RpcException {
		LocalPlayer p = ClientMc.player();
		MultiPlayerGameMode gm = ClientMc.gameMode();
		AbstractContainerMenu menu = p.containerMenu;

		final boolean table = menu instanceof CraftingMenu;
		final boolean inv = menu instanceof InventoryMenu;
		if (!table && !inv) {
			throw RpcException.unavailable("No crafting grid is open. The player inventory (2x2) is always "
					+ "available; right-click a placed crafting table for 3x3.");
		}
		final int cols = table ? 3 : 2;
		final int gridSlots = cols * cols;
		// Player main-inventory slots inside this menu. CraftingMenu: 0 result, 1-9 grid, 10-45 inv.
		// InventoryMenu: 0 result, 1-4 grid, 5-8 armor, 9-44 inv, 45 offhand.
		final int invFrom = table ? 10 : 9;
		final int invTo = table ? 46 : 45;

		if (!ctx.has("grid") || !ctx.params().get("grid").isJsonArray()) {
			throw RpcException.badRequest("Missing 'grid' array.");
		}
		JsonArray arr = ctx.params().getAsJsonArray("grid");
		if (arr.size() != gridSlots) {
			throw RpcException.badRequest("This " + cols + "x" + cols + " grid needs " + gridSlots
					+ " entries (row-major, null for empty); got " + arr.size() + ".");
		}
		int count = Math.max(1, Math.min(64, ctx.optInt("count", 1)));

		Item[] wanted = new Item[gridSlots];
		for (int i = 0; i < gridSlots; i++) {
			JsonElement e = arr.get(i);
			if (e == null || e.isJsonNull()) continue;
			String id = e.getAsString();
			Item item = itemById(id);
			if (item == null) throw RpcException.badRequest("Unknown item id: " + id);
			wanted[i] = item;
		}
		boolean any = false;
		for (Item w : wanted) {
			if (w != null) { any = true; break; }
		}
		if (!any) throw RpcException.badRequest("'grid' contains no items.");

		if (!menu.getCarried().isEmpty()) {
			throw RpcException.unavailable("Your cursor is holding an item; place or drop it before crafting.");
		}

		// Each occupied grid slot costs one item per craft; cap the batch by what the inventory holds.
		Map<Item, Integer> slotsPerItem = new LinkedHashMap<>();
		for (Item w : wanted) {
			if (w != null) slotsPerItem.merge(w, 1, Integer::sum);
		}
		int crafts = count;
		for (Map.Entry<Item, Integer> e : slotsPerItem.entrySet()) {
			int have = countInInventory(menu, invFrom, invTo, e.getKey());
			crafts = Math.min(crafts, have / e.getValue());
		}
		if (crafts <= 0) {
			throw RpcException.unavailable("Not enough materials in the inventory for even one craft.");
		}

		// Place the ingredients.
		for (int i = 0; i < gridSlots; i++) {
			if (wanted[i] != null) fillGridSlot(gm, menu, p, wanted[i], 1 + i, crafts, invFrom, invTo);
		}

		ItemStack preview = menu.slots.get(0).getItem();
		if (preview.isEmpty()) {
			clearGrid(gm, menu, p, gridSlots);
			throw RpcException.badRequest("That grid does not match a valid recipe.");
		}
		String outputId = BuiltInRegistries.ITEM.getKey(preview.getItem()).toString();
		int perCraft = preview.getCount();

		// Take the result. Shift-click auto-deposits into the inventory; loop so this is correct whether
		// one shift-click crafts a single item or drains the whole grid at once.
		for (int c = 0; c < crafts; c++) {
			if (menu.slots.get(0).getItem().isEmpty()) break;
			containerQuickMove(gm, menu.containerId, 0, p);
		}
		clearGrid(gm, menu, p, gridSlots); // safety: return any leftover ingredients

		JsonObject o = new JsonObject();
		o.addProperty("crafted", crafts);
		o.addProperty("output", outputId);
		o.addProperty("outputPerCraft", perCraft);
		o.addProperty("totalItems", crafts * perCraft);
		return o;
	}

	/** Move exactly {@code amount} of {@code item} from the player inventory into menu slot {@code dst}. */
	private static void fillGridSlot(MultiPlayerGameMode gm, AbstractContainerMenu menu, LocalPlayer p,
			Item item, int dst, int amount, int invFrom, int invTo) throws RpcException {
		int remaining = amount;
		while (remaining > 0) {
			int src = findInInventory(menu, invFrom, invTo, item);
			if (src < 0) throw RpcException.unavailable("Ran out of materials while filling the grid.");
			int take = Math.min(remaining, menu.slots.get(src).getItem().getCount());
			containerClick(gm, menu.containerId, src, 0, false, p); // pick the stack up
			for (int i = 0; i < take; i++) {
				containerClick(gm, menu.containerId, dst, 1, false, p); // right-click: place one
			}
			containerClick(gm, menu.containerId, src, 0, false, p); // put the remainder back
			remaining -= take;
		}
	}

	/** Shift-click every non-empty grid slot back into the inventory. */
	private static void clearGrid(MultiPlayerGameMode gm, AbstractContainerMenu menu, LocalPlayer p, int gridSlots) {
		for (int i = 1; i <= gridSlots; i++) {
			if (!menu.slots.get(i).getItem().isEmpty()) containerQuickMove(gm, menu.containerId, i, p);
		}
	}

	private static int findInInventory(AbstractContainerMenu menu, int from, int to, Item item) {
		for (int i = from; i < to; i++) {
			ItemStack st = menu.slots.get(i).getItem();
			if (!st.isEmpty() && st.getItem() == item) return i;
		}
		return -1;
	}

	private static int countInInventory(AbstractContainerMenu menu, int from, int to, Item item) {
		int n = 0;
		for (int i = from; i < to; i++) {
			ItemStack st = menu.slots.get(i).getItem();
			if (!st.isEmpty() && st.getItem() == item) n += st.getCount();
		}
		return n;
	}

	private static Item itemById(String id) {
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
