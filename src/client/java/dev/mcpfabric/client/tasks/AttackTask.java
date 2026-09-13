package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Fight an entity the way a player does: walk into reach, keep the crosshair on it, then hold the
 * left mouse button. The vanilla pipeline swings at the attack-cooldown rate (no spam-clicking) and
 * animates the arm by itself.
 */
public final class AttackTask extends ClientTask {
	private static final int NODE_BUDGET = 12000;
	private static final double ATTACK_REACH = 3.0;

	private final UUID target;
	private final int maxSwings;
	private int swings;
	private boolean approached;
	private boolean seen;
	private boolean wasCharged;

	public AttackTask(UUID target, int maxSwings) {
		this.target = target;
		this.maxSwings = maxSwings;
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			failed("no_world");
			return;
		}
		if (expired()) {
			release();
			failed("timeout");
			return;
		}
		Entity e = find(level, target);
		if (e == null) {
			release();
			if (seen) finish("killed"); else failed("not_found");
			return;
		}
		seen = true;
		if (!e.isAlive()) {
			release();
			finish("killed");
			return;
		}
		if (maxSwings > 0 && swings >= maxSwings) {
			release();
			finish("max_swings");
			return;
		}

		if (p.distanceTo(e) > ATTACK_REACH) {
			BotController.get().setAttackHeld(false);
			if (isNavigating()) return;
			if (approached) {
				failed("unreachable");
				return;
			}
			approached = true;
			BlockPos goal = e.blockPosition();
			List<BlockPos> path = new AStarPathfinder(level, NODE_BUDGET)
					.findPath(p.blockPosition(), goal, ATTACK_REACH - 0.5);
			if (path == null || path.isEmpty()) {
				failed("unreachable");
				return;
			}
			BotController.get().startNavigation(path, goal, ATTACK_REACH - 0.5, true,
					System.currentTimeMillis() + Math.max(1000L, remainingMs()));
			return;
		}

		if (isNavigating()) BotController.get().stopNavigation("in_reach");
		aim(p, e);
		boolean ready = BotController.get().lookErrorDeg() <= 6.0F && clearShot(mc, p, e);
		BotController.get().setAttackHeld(ready);
		if (ready) {
			// Count charged swings the vanilla pipeline performs while the button is held.
			boolean charged = p.getAttackStrengthScale(0.5F) >= 0.9F;
			if (charged && !wasCharged) swings++;
			wasCharged = charged;
		}
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.addProperty("target", target.toString());
		o.addProperty("swings", swings);
		o.addProperty("maxSwings", maxSwings);
		Entity e = Minecraft.getInstance().level != null ? find(Minecraft.getInstance().level, target) : null;
		LocalPlayer p = Minecraft.getInstance().player;
		if (e != null) {
			o.addProperty("targetName", e.getName().getString());
			if (e instanceof LivingEntity le) o.addProperty("targetHealth", le.getHealth());
			o.addProperty("targetAlive", e.isAlive());
			if (p != null) o.addProperty("distance", Math.round(p.distanceTo(e) * 100.0) / 100.0);
		} else {
			o.addProperty("targetAlive", false);
		}
		return o;
	}

	@Override
	public String describe() {
		return "fight entity " + target;
	}

	@Override
	public void onCancel(Minecraft mc) {
		release();
	}

	private void release() {
		BotController.get().setAttackHeld(false);
		BotController.get().stopNavigation("cancelled");
	}

	private void finish(String state) {
		JsonObject extra = new JsonObject();
		extra.addProperty("swings", swings);
		done(state, extra);
	}

	/** No block sits between the eye and the entity. */
	private boolean clearShot(Minecraft mc, LocalPlayer p, Entity e) {
		Vec3 from = p.getEyePosition();
		Vec3 to = e.getBoundingBox().getCenter();
		BlockHitResult hit = mc.level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		if (hit.getType() == HitResult.Type.MISS) return true;
		return from.distanceTo(hit.getLocation()) >= from.distanceTo(to) - 0.2;
	}

	private static Entity find(ClientLevel level, UUID uuid) {
		for (Entity e : level.entitiesForRendering()) {
			if (e.getUUID().equals(uuid)) return e;
		}
		return null;
	}

	private void aim(LocalPlayer p, Entity e) {
		double dx = e.getX() - p.getX();
		double dy = (e.getY() + e.getBbHeight() * 0.5) - p.getEyeY();
		double dz = e.getZ() - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		float pitch = (float) (-(Math.atan2(dy, horiz) * (180.0 / Math.PI)));
		BotController.get().lookAtTarget(yaw, pitch);
	}

	private static boolean isNavigating() {
		JsonObject s = BotController.get().statusJson();
		return s.has("active") && s.get("active").getAsBoolean();
	}
}
