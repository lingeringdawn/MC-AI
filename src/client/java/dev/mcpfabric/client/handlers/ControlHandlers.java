package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.Json;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.HumanControl;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/** Movement and look control for the local player. */
public final class ControlHandlers {
	private ControlHandlers() {}

	public static void register(RpcRouter router) {
		router.register("control.setInput", ctx -> {
			BotController.get().setMovement(
					ctx.optBoolean("forward"),
					ctx.optBoolean("back"),
					ctx.optBoolean("left"),
					ctx.optBoolean("right"),
					ctx.optBoolean("jump"),
					ctx.optBoolean("sneak"),
					ctx.optBoolean("sprint"));
			return Json.ok("input updated");
		});

		router.register("control.stop", ctx -> {
			BotController.get().stopAllMovement();
			return Json.ok("stopped");
		});

		router.register("control.jumpOnce", ctx -> {
			BotController.get().jumpOnce();
			return Json.ok("jump");
		});

		router.register("control.respawn", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			p.respawn();
			return Json.ok("respawned");
		}));

		router.register("control.look", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			float yaw = p.getYRot();
			float pitch = p.getXRot();
			if (ctx.has("yaw")) yaw = (float) ctx.getDouble("yaw");
			if (ctx.has("pitch")) pitch = (float) ctx.getDouble("pitch");
			if (ctx.has("deltaYaw")) yaw += (float) ctx.getDouble("deltaYaw");
			if (ctx.has("deltaPitch")) pitch += (float) ctx.getDouble("deltaPitch");
			pitch = Mth.clamp(pitch, -90.0F, 90.0F);
			aim(p, yaw, pitch, ctx.optBool("instant", false));
			return look(p);
		}));

		router.register("control.lookAt", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			double dx = ctx.getDouble("x") - p.getX();
			double dy = ctx.getDouble("y") - p.getEyeY();
			double dz = ctx.getDouble("z") - p.getZ();
			double horiz = Math.sqrt(dx * dx + dz * dz);
			float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
			float pitch = (float) (-(Mth.atan2(dy, horiz) * (180.0 / Math.PI)));
			aim(p, yaw, Mth.clamp(pitch, -90.0F, 90.0F), ctx.optBool("instant", false));
			return look(p);
		}));

		router.register("control.startUsing", ctx -> ClientMc.call(() -> {
			BotController.get().setUseHeld(true);
			return Json.ok("using");
		}));

		router.register("control.stopUsing", ctx -> ClientMc.call(() -> {
			BotController.get().setUseHeld(false);
			ClientMc.player().stopUsingItem();
			return Json.ok("stopped using");
		}));

		router.register("control.resumeAi", ctx -> {
			HumanControl.resumeByAi();
			return Json.ok("ai has control");
		});

		router.register("control.setPauseOnLostFocus", ctx -> ClientMc.call(() -> {
			Minecraft.getInstance().options.pauseOnLostFocus = ctx.optBool("value", false);
			return Json.ok("pauseOnLostFocus=" + Minecraft.getInstance().options.pauseOnLostFocus);
		}));

		/** Full picture of the input pipeline — used to debug "the bot does nothing" situations. */
		router.register("control.debug", ctx -> ClientMc.call(() -> {
			Minecraft mc = Minecraft.getInstance();
			BotController b = BotController.get();
			JsonObject o = new JsonObject();
			o.addProperty("suspended", HumanControl.suspended());
			o.addProperty("drivingKeys", b.isDrivingKeys());
			o.addProperty("wantsForward", b.wantsForward());
			o.addProperty("wantsBack", b.wantsBack());
			o.addProperty("wantsLeft", b.wantsLeft());
			o.addProperty("wantsRight", b.wantsRight());
			o.addProperty("wantsJump", b.wantsJump());
			o.addProperty("wantsSneak", b.wantsSneak());
			o.addProperty("wantsSprint", b.wantsSprint());
			o.addProperty("attackHeld", b.isAttackHeld());
			o.addProperty("useHeld", b.isUseHeld());
			o.addProperty("hasAppliedLook", b.hasAppliedLook());
			o.addProperty("lastAppliedYaw", b.lastAppliedYaw());
			o.addProperty("lastAppliedPitch", b.lastAppliedPitch());
			o.addProperty("pendingLookYaw", b.pendingLookYaw());
			o.addProperty("pendingLookPitch", b.pendingLookPitch());
			o.addProperty("navState", b.navStateText());
			o.addProperty("navRemaining", b.navRemainingNodes());

			Options op = mc.options;
			JsonObject keys = new JsonObject();
			keys.addProperty("up", op.keyUp.isDown());
			keys.addProperty("down", op.keyDown.isDown());
			keys.addProperty("left", op.keyLeft.isDown());
			keys.addProperty("right", op.keyRight.isDown());
			keys.addProperty("jump", op.keyJump.isDown());
			keys.addProperty("shift", op.keyShift.isDown());
			keys.addProperty("sprint", op.keySprint.isDown());
			keys.addProperty("attack", op.keyAttack.isDown());
			keys.addProperty("use", op.keyUse.isDown());
			keys.addProperty("inventory", op.keyInventory.isDown());
			keys.addProperty("drop", op.keyDrop.isDown());
			keys.addProperty("pickItem", op.keyPickItem.isDown());
			o.add("keys", keys);

			o.addProperty("screen", mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
			o.addProperty("pauseOnLostFocus", op.pauseOnLostFocus);
			LocalPlayer p = mc.player;
			if (p != null) {
				o.addProperty("playerYaw", p.getYRot());
				o.addProperty("playerPitch", p.getXRot());
				o.addProperty("onGround", p.onGround());
				o.addProperty("inWater", p.isInWater());
			}
			return o;
		}));
	}

	/** Smooth by default (the controller interpolates over ticks); {@code instant} snaps the view. */
	private static void aim(LocalPlayer p, float yaw, float pitch, boolean instant) {
		if (instant) {
			BotController.get().clearLookTarget();
			applyLook(p, yaw, pitch);
		} else {
			BotController.get().lookAtTarget(yaw, pitch);
		}
	}

	private static void applyLook(LocalPlayer p, float yaw, float pitch) {
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	private static JsonObject look(LocalPlayer p) {
		JsonObject o = new JsonObject();
		o.addProperty("yaw", p.getYRot());
		o.addProperty("pitch", p.getXRot());
		return o;
	}
}
