package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;

import java.util.List;
import java.util.UUID;

/**
 * Fight an entity the way a player does: walk into reach, keep the crosshair on it, and swing only
 * when the attack cooldown is charged (so hits land fully charged, not spam-clicked).
 */
public final class AttackTask extends ClientTask {
	private static final int NODE_BUDGET = 12000;
	private static final double ATTACK_REACH = 3.0;

	private final UUID target;
	private final int maxSwings;
	private int swings;
	private boolean approached;
	private boolean seen;

	public AttackTask(UUID target, int maxSwings) {
		this.target = target;
		this.maxSwings = maxSwings;
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		MultiPlayerGameMode gm = mc.gameMode;
		if (p == null || level == null || gm == null) {
			failed("no_world");
			return;
		}
		if (expired()) {
			BotController.get().stopNavigation("timeout");
			failed("timeout");
			return;
		}
		Entity e = find(level, target);
		if (e == null) {
			BotController.get().stopNavigation("done");
			if (seen) finish("killed"); else failed("not_found");
			return;
		}
		seen = true;
		if (!e.isAlive()) {
			BotController.get().stopNavigation("done");
			finish("killed");
			return;
		}
		if (maxSwings > 0 && swings >= maxSwings) {
			BotController.get().stopNavigation("done");
			finish("max_swings");
			return;
		}

		if (p.distanceTo(e) > ATTACK_REACH) {
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
		// Only swing with a fully charged attack — that is how a player fights.
		if (p.getAttackStrengthScale(0.5F) < 0.9F) return;
		gm.attack(p, e);
		p.swing(InteractionHand.MAIN_HAND);
		swings++;
	}

	@Override
	public void onCancel(Minecraft mc) {
		BotController.get().stopNavigation("cancelled");
	}

	private void finish(String state) {
		JsonObject extra = new JsonObject();
		extra.addProperty("swings", swings);
		done(state, extra);
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
