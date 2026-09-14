package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Interaction: use/place, attack/use entities, drop held item. */
public final class InteractHandlers {
	private InteractHandlers() {}

	public static void register(RpcRouter router) {
		// Right-click through the real channel: hold the use key and register one click, which is the pair
		// of things a mouse does. Vanilla's own input handling then decides what that means — use the held
		// item, put a block against the face in the crosshair, open a crafting table, feed an animal, light
		// a fire. Driving gameMode.useItem/useItemOn by hand skips that decision, acts on a hit point the
		// caller invented rather than the one the game has, and answers "no" with a class name.
		router.register("interact.useItem", ctx -> ClientMc.call(() -> {
			requireControl();
			BotController bc = BotController.get();
			// No 'hold' at all means a single click; 'hold:true' keeps the button down (for eating, drawing a
			// bow, holding a shield); 'hold:false' is the same as omitting it.
			boolean hold = ctx.has("hold") && ctx.optBool("hold", false);
			bc.setUseHeld(true);
			if (!ctx.has("hold")) {
				// A single click: the press edge is what makes vanilla start the use at all.
				KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(1));
				bc.setUseHeld(false);
			}
			JsonObject o = new JsonObject();
			o.addProperty("result", hold ? "holding right-click" : "right-click sent");
			o.add("lookingAt", lookingAt());
			o.addProperty("held", heldItem());
			if (!hold) {
				o.addProperty("note", "The game applies it on the next tick against the crosshair — read "
						+ "observe.lookingAt to see what it landed on. If nothing happened, aim at the "
						+ "block's centre with control.lookAt (instant) and try again.");
			}
			return o;
		}));

		// Place at an explicit position, for building rather than interacting: the hit is aimed at the
		// middle of the named face, so the caller says where the block goes rather than what to look at.
		router.register("interact.placeBlock", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			Direction face = parseFace(ctx.optString("face", "up"));
			Vec3 hitLoc = new Vec3(
					pos.getX() + 0.5 + face.getStepX() * 0.5,
					pos.getY() + 0.5 + face.getStepY() * 0.5,
					pos.getZ() + 0.5 + face.getStepZ() * 0.5);
			BlockHitResult hit = new BlockHitResult(hitLoc, face, pos, false);
			InteractionResult result = gm.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			p.swing(InteractionHand.MAIN_HAND);
			return describe(result, "place against " + pos.getX() + "," + pos.getY() + "," + pos.getZ()
					+ " (" + face.getName() + ")");
		}));

		router.register("interact.attackEntity", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			Entity e = findEntity(ctx.getString("uuid"));
			gm.attack(p, e);
			p.swing(InteractionHand.MAIN_HAND);
			JsonObject o = new JsonObject();
			o.addProperty("result", "attacked");
			o.addProperty("target", e.getName().getString());
			o.addProperty("distance", Math.round(p.distanceTo(e) * 100.0) / 100.0);
			return o;
		}));

		router.register("interact.useEntity", ctx -> ClientMc.call(() -> {
			requireControl();
			MultiPlayerGameMode gm = ClientMc.gameMode();
			LocalPlayer p = ClientMc.player();
			Entity e = findEntity(ctx.getString("uuid"));
			//? if <26.1 {
			InteractionResult result = gm.interact(p, e, InteractionHand.MAIN_HAND);
			//?} else
			/*InteractionResult result = gm.interact(p, e, new net.minecraft.world.phys.EntityHitResult(e), InteractionHand.MAIN_HAND);*/
			return describe(result, "use " + e.getName().getString());
		}));

		router.register("interact.dropItem", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			boolean whole = ctx.optBool("wholeStack", false);
			p.drop(whole);
			return Json.ok(whole ? "dropped stack" : "dropped one");
		}));
	}

	/**
	 * Say what an {@link InteractionResult} means. It has no useful {@code toString} — the caller used to
	 * get "class_9857[]", which is the same answer for "you were not aiming at anything", "that spot is
	 * occupied" and "you are too far away", so a failed placement told the caller nothing it could act on.
	 */
	private static JsonObject describe(InteractionResult result, String what) {
		JsonObject o = new JsonObject();
		String outcome = result == null ? "none"
				: result == InteractionResult.FAIL ? "fail"
				: result.consumesAction() ? "success"
				: "pass";
		o.addProperty("result", outcome);
		o.addProperty("what", what);
		o.add("lookingAt", lookingAt());
		o.addProperty("held", heldItem());
		if ("fail".equals(outcome)) {
			o.addProperty("hint", "The game refused it. Usually nothing to act on where the hit points — "
					+ "aim at the centre of the block you mean (control.lookAt with instant), check the "
					+ "destination is air, and make sure the block is within reach.");
		} else if ("pass".equals(outcome)) {
			o.addProperty("hint", "Nothing happened: the held item has no use against that target.");
		}
		return o;
	}

	/** What the crosshair is on right now, so a refusal can be read against what was actually aimed at. */
	private static JsonObject lookingAt() {
		JsonObject o = new JsonObject();
		LocalPlayer p = ClientMc.mc().player;
		HitResult hit = ClientMc.mc().hitResult;
		if (hit == null || p == null) {
			o.addProperty("type", "none");
			return o;
		}
		if (hit.getType() == HitResult.Type.BLOCK) {
			BlockPos b = ((BlockHitResult) hit).getBlockPos();
			o.addProperty("type", "block");
			o.addProperty("x", b.getX());
			o.addProperty("y", b.getY());
			o.addProperty("z", b.getZ());
			o.addProperty("face", ((BlockHitResult) hit).getDirection().getName());
			if (ClientMc.mc().level != null) {
				o.addProperty("id", net.minecraft.core.registries.BuiltInRegistries.BLOCK
						.getKey(ClientMc.mc().level.getBlockState(b).getBlock()).toString());
			}
			o.addProperty("distance", Math.round(p.getEyePosition().distanceTo(hit.getLocation()) * 100.0) / 100.0);
		} else {
			o.addProperty("type", "miss");
		}
		return o;
	}

	private static String heldItem() {
		LocalPlayer p = ClientMc.mc().player;
		if (p == null) return "none";
		ItemStack s = p.getMainHandItem();
		return s.isEmpty() ? "empty hand" : net.minecraft.core.registries.BuiltInRegistries.ITEM
				.getKey(s.getItem()).toString() + " x" + s.getCount();
	}

	private static void requireControl() throws RpcException {
		if (!McpFabric.config().enablePlayerControl) {
			throw RpcException.unavailable("Player control is disabled in mcpfabric.config.json (enablePlayerControl=false).");
		}
	}

	private static Entity findEntity(String uuidStr) throws RpcException {
		UUID uuid;
		try {
			uuid = UUID.fromString(uuidStr);
		} catch (IllegalArgumentException e) {
			throw RpcException.badRequest("Invalid UUID: " + uuidStr);
		}
		for (Entity e : ClientMc.level().entitiesForRendering()) {
			if (e.getUUID().equals(uuid)) return e;
		}
		throw RpcException.notFound("No visible entity with uuid " + uuid);
	}

	private static Direction parseFace(String name) {
		Direction d = Direction.byName(name.toLowerCase());
		return d == null ? Direction.UP : d;
	}
}
