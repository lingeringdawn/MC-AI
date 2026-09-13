package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Crafts the way a player does — through the real, visible GUI:
 *
 * <ol>
 *   <li>open the screen (the inventory, exactly what pressing E does — for a 3x3 grid the caller has
 *       to have opened the crafting table already, same as a player standing at it);</li>
 *   <li>move one ingredient into its grid cell with a slot click, <b>one click per tick</b>, so the
 *       whole thing plays out on screen instead of happening invisibly in one frame;</li>
 *   <li>shift-click the result out, repeat for the requested number of crafts;</li>
 *   <li>hold the finished screen briefly so it is visible, then close it and return to the world.</li>
 * </ol>
 *
 * Nothing is spawned: the recipe match, ingredient consumption and output count all come from the
 * game's own menu logic.
 */
public final class CraftTask extends ClientTask {
	private enum Phase { OPENING, FILLING, TAKING, HOLDING, CLOSING }

	/** Ticks the crafted result stays on screen before the screen is closed again. */
	private static final int HOLD_TICKS = 12;

	private final Item[] wanted;
	private final int crafts;

	private Phase phase = Phase.OPENING;
	private int settleTicks;
	private int holdTicks;
	private int cell;
	private int sub;
	private int srcSlot = -1;
	private int made;
	private int perCraft;
	private int total;
	private String outputId;
	private boolean openedByUs;
	private int cols;
	private int invFrom;
	private int invTo;
	private String problem;

	public CraftTask(Item[] wanted, int crafts) {
		this.wanted = wanted;
		this.crafts = crafts;
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		MultiPlayerGameMode gm = mc.gameMode;
		if (p == null || mc.level == null || gm == null) {
			failed("no world");
			return;
		}
		if (expired()) {
			cleanup(mc, p);
			finishFail("timeout");
			return;
		}
		AbstractContainerMenu menu = p.containerMenu;

		switch (phase) {
			case OPENING -> {
				if (!openScreen(mc, p, menu)) return;
				phase = Phase.FILLING;
			}
			case FILLING -> {
				if (!fillStep(mc, gm, p, menu)) return; // one click per tick
				if (++settleTicks < 2) return;          // give the menu a tick to compute the result
				ItemStack preview = menu.slots.get(0).getItem();
				if (preview.isEmpty()) {
					cleanup(mc, p);
					finishFail("That grid does not match a valid recipe.");
					return;
				}
				if (outputId == null) {
					outputId = BuiltInRegistries.ITEM.getKey(preview.getItem()).toString();
					perCraft = preview.getCount();
				}
				phase = Phase.TAKING;
			}
			case TAKING -> {
				quickMove(gm, menu, p, 0);
				made++;
				total += perCraft;
				if (made >= crafts) {
					phase = Phase.HOLDING; // leave the finished craft on screen for a moment
				} else {
					cell = 0;
					sub = 0;
					settleTicks = 0;
					phase = Phase.FILLING;
				}
			}
			case HOLDING -> {
				if (++holdTicks < HOLD_TICKS) return;
				cleanup(mc, p);
				phase = Phase.CLOSING;
			}
			case CLOSING -> {
				JsonObject o = new JsonObject();
				o.addProperty("crafted", made);
				o.addProperty("output", outputId == null ? "none" : outputId);
				o.addProperty("outputPerCraft", perCraft);
				o.addProperty("totalItems", total);
				done("crafted", o);
			}
		}
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.addProperty("phase", phase.name().toLowerCase());
		o.addProperty("crafted", made);
		o.addProperty("targetCrafts", crafts);
		o.addProperty("cell", cell);
		o.addProperty("output", outputId == null ? "unknown" : outputId);
		AbstractContainerMenu menu = Minecraft.getInstance().player != null
				? Minecraft.getInstance().player.containerMenu : null;
		if (menu != null) {
			o.addProperty("menu", menu.getClass().getSimpleName());
			ItemStack preview = menu.slots.isEmpty() ? ItemStack.EMPTY : menu.slots.get(0).getItem();
			o.addProperty("result", preview.isEmpty() ? "none"
					: BuiltInRegistries.ITEM.getKey(preview.getItem()).toString());
			o.addProperty("carried", menu.getCarried().isEmpty() ? "none"
					: BuiltInRegistries.ITEM.getKey(menu.getCarried().getItem()) + "x" + menu.getCarried().getCount());
		}
		return o;
	}

	@Override
	public String describe() {
		return "craft " + crafts + "x from a " + wanted.length + "-cell grid";
	}

	@Override
	public void onCancel(Minecraft mc) {
		if (mc.player != null) cleanup(mc, mc.player);
	}

	/** Open the container a player would use. Returns true once the screen is up and clickable. */
	private boolean openScreen(Minecraft mc, LocalPlayer p, AbstractContainerMenu menu) {
		if (!menu.getCarried().isEmpty()) {
			finishFail("Your cursor is holding an item; put it down before crafting.");
			return false;
		}
		if (wanted.length == 9) {
			if (!(menu instanceof CraftingMenu)) {
				finishFail("A 9-cell grid needs a crafting table: place one and right-click it first.");
				return false;
			}
			if (mc.screen == null) {
				finishFail("The crafting table menu is not open.");
				return false;
			}
			cols = 3;
			invFrom = 10; // CraftingMenu: slot 0 result, 1-9 grid, 10-45 inventory
			invTo = 46;
			return true;
		}
		if (menu instanceof CraftingMenu) {
			finishFail("A crafting table is open — pass a 9-cell grid for it, or close it for the 2x2.");
			return false;
		}
		if (!(menu instanceof InventoryMenu)) {
			finishFail("No crafting grid is available.");
			return false;
		}
		cols = 2;
		invFrom = 9; // InventoryMenu: slot 0 result, 1-4 grid, 5-8 armor, 9-44 inventory
		invTo = 45;
		if (mc.screen == null) {
			mc.setScreen(new InventoryScreen(p)); // what pressing E does
			openedByUs = true;
			return false;
		}
		return ++settleTicks >= 2;
	}

	/** One mouse click of the fill sequence; true once every occupied cell holds exactly one item. */
	private boolean fillStep(Minecraft mc, MultiPlayerGameMode gm, LocalPlayer p, AbstractContainerMenu menu) {
		while (cell < wanted.length && wanted[cell] == null) {
			cell++;
			sub = 0;
		}
		if (cell >= wanted.length) return true;

		int dst = 1 + cell;
		if (sub == 0) {
			int src = findInInventory(menu, wanted[cell]);
			if (src < 0) {
				cleanup(mc, p);
				finishFail("Ran out of materials while filling the grid: "
						+ BuiltInRegistries.ITEM.getKey(wanted[cell]) + " not in slots " + invFrom + "-"
						+ (invTo - 1) + " of " + menu.getClass().getSimpleName() + " ("
						+ menu.slots.size() + " slots)");
				return false;
			}
			srcSlot = src;
			click(gm, menu, p, src, 0); // pick the stack up
			sub = 1;
			return false;
		}
		if (sub == 1) {
			click(gm, menu, p, dst, 1); // right-click drops exactly one item into the cell
			sub = 2;
			return false;
		}
		putCarriedBack(gm, menu, p); // the leftover stack goes back into a *free* slot
		sub = 0;
		cell++;
		return false;
	}

	/**
	 * Click the carried stack down into a free player slot. The source slot is empty by now, so it is
	 * the natural place; anything else free will do. Never close the screen while still carrying an
	 * item, or the game deletes the stack.
	 */
	private void putCarriedBack(MultiPlayerGameMode gm, AbstractContainerMenu menu, LocalPlayer p) {
		if (menu.getCarried().isEmpty()) return;
		if (srcSlot >= invFrom && srcSlot < invTo && menu.slots.get(srcSlot).getItem().isEmpty()) {
			click(gm, menu, p, srcSlot, 0);
			return;
		}
		for (int i = invFrom; i < invTo && i < menu.slots.size(); i++) {
			if (menu.slots.get(i).getItem().isEmpty()) {
				click(gm, menu, p, i, 0);
				return;
			}
		}
	}

	/**
	 * Shift-click every non-empty grid cell back into the inventory, put down anything still on the
	 * cursor (closing a screen while holding an item would destroy it), then close what we opened.
	 */
	private void cleanup(Minecraft mc, LocalPlayer p) {
		MultiPlayerGameMode gm = mc.gameMode;
		AbstractContainerMenu menu = p.containerMenu;
		if (gm != null && menu != null && cols > 0) {
			for (int i = 1; i <= cols * cols && i < menu.slots.size(); i++) {
				if (!menu.slots.get(i).getItem().isEmpty()) quickMove(gm, menu, p, i);
			}
			putCarriedBack(gm, menu, p);
		}
		if (openedByUs && mc.screen != null) mc.setScreen(null);
	}

	/** First player-owned slot (never a grid cell or the result) holding the item. */
	private int findInInventory(AbstractContainerMenu menu, Item item) {
		for (int i = invFrom; i < invTo && i < menu.slots.size(); i++) {
			ItemStack st = menu.slots.get(i).getItem();
			if (!st.isEmpty() && st.getItem() == item) return i;
		}
		return -1;
	}

	private static void click(MultiPlayerGameMode gm, AbstractContainerMenu menu, LocalPlayer p, int slot, int button) {
		//? if <26.1 {
		gm.handleInventoryMouseClick(menu.containerId, slot, button, ClickType.PICKUP, p);
		//?} else
		/*gm.handleContainerInput(menu.containerId, slot, button, net.minecraft.world.inventory.ContainerInput.PICKUP, p);*/
	}

	private static void quickMove(MultiPlayerGameMode gm, AbstractContainerMenu menu, LocalPlayer p, int slot) {
		//? if <26.1 {
		gm.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, p);
		//?} else
		/*gm.handleContainerInput(menu.containerId, slot, 0, net.minecraft.world.inventory.ContainerInput.QUICK_MOVE, p);*/
	}

	private void finishFail(String reason) {
		if (problem == null) problem = reason;
		failed(problem);
	}
}
