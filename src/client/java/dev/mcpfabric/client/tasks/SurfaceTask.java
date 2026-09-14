package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Swim up for air, once — the explicit counterpart to the reflex this mod used to run on its own.
 *
 * <p>Rises straight up until the head is out of the water and the lungs have refilled, then stops and
 * reports. Nothing triggers it but a caller asking for it: the observation feed publishes the air bar
 * and the position, so deciding "I need to surface" is the caller's job, not the mod's.
 */
public final class SurfaceTask extends ClientTask {
	/** Air that counts as breathing normally again (the vanilla maximum is 300). */
	private static final int AIR_FULL = 290;
	/** Tilt applied while rising, so the camera faces the surface instead of the pond floor. */
	private static final float SURFACE_PITCH = -35.0F;

	/** Set once we have actually been in the water, so an immediate no-op is not reported as success. */
	private boolean wasInWater;

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p == null) {
			failed("no_player");
			return;
		}
		if (expired()) {
			finish(p, "timeout");
			return;
		}
		if (!p.isInWater()) {
			// Already out (or never in) — there is nothing to swim up to.
			finish(p, wasInWater ? "surfaced" : "not_in_water");
			return;
		}
		wasInWater = true;

		// Rise straight up. No walking and no sprinting: a sprinting swimmer travels along their look
		// direction, which is not necessarily up, and that is how a "surface" command ends up going
		// sideways into a wall.
		BotController.get().stopNavigation("surfacing");
		BotController.get().setMovement(false, false, false, false, true, null, false);
		BotController.get().setAttackHeld(false);
		BotController.get().lookAtTarget(p.getYRot(), SURFACE_PITCH, BotController.LOOK_TASK);

		if (!p.isUnderWater() && p.getAirSupply() >= AIR_FULL) {
			finish(p, "surfaced");
		}
	}

	@Override
	public void onCancel(Minecraft mc) {
		release();
	}

	@Override
	public JsonObject progress() {
		LocalPlayer p = Minecraft.getInstance().player;
		JsonObject o = new JsonObject();
		if (p != null) {
			o.addProperty("air", p.getAirSupply());
			o.addProperty("inWater", p.isInWater());
			o.addProperty("eyesUnderWater", p.isUnderWater());
			o.addProperty("y", Math.round(p.getY() * 100.0) / 100.0);
		}
		return o;
	}

	@Override
	public String describe() {
		return "swim up to the surface";
	}

	private void finish(LocalPlayer p, String detail) {
		release();
		JsonObject extra = new JsonObject();
		extra.addProperty("air", p.getAirSupply());
		extra.addProperty("inWater", p.isInWater());
		extra.addProperty("y", Math.round(p.getY() * 100.0) / 100.0);
		done(detail, extra);
	}

	private void release() {
		BotController.get().setMovement(false, false, false, false, false, null, false);
	}
}
