package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Withdraw from something, once — the explicit counterpart to the automatic retreat this mod used to
 * decide on its own.
 *
 * <p>Picks a standable spot roughly {@code distance} away, on the far side of the player from the
 * threat (or from a coordinate), walks there and stops. Whether the situation deserves a retreat is
 * the caller's call; this only carries it out, and it will not walk into water or off a ledge to do it.
 */
public final class RetreatTask extends ClientTask {
	private static final int NODE_BUDGET = 12000;
	/** Off-axis directions tried when the line of retreat is blocked, in degrees. */
	private static final double[] ANGLES = { 0.0, 25.0, -25.0, 50.0, -50.0, 75.0, -75.0 };

	private final UUID entityId;
	private final double fromX;
	private final double fromY;
	private final double fromZ;
	private final double distance;
	private boolean navStarted;

	/** Retreat from a moving entity (looked up fresh each tick). */
	public RetreatTask(UUID entityId, double distance) {
		this.entityId = entityId;
		this.fromX = 0.0;
		this.fromY = 0.0;
		this.fromZ = 0.0;
		this.distance = distance;
	}

	/** Retreat from a fixed coordinate. */
	public RetreatTask(double x, double y, double z, double distance) {
		this.entityId = null;
		this.fromX = x;
		this.fromY = y;
		this.fromZ = z;
		this.distance = distance;
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
			finish(p, "timeout");
			return;
		}

		Vec3 from = source(p, level);
		if (from == null) {
			// The thing we were getting away from is gone (killed, despawned) — nothing left to do.
			finish(p, "no_threat");
			return;
		}
		if (p.position().distanceTo(from) >= distance) {
			finish(p, "withdrew");
			return;
		}

		if (!navStarted) {
			BlockPos goal = chooseGoal(level, p, from);
			if (goal == null) {
				failed("no_room");
				return;
			}
			List<BlockPos> path = new AStarPathfinder(level, NODE_BUDGET)
					.findPath(p.blockPosition(), goal, 1.0);
			if (path == null || path.isEmpty()) {
				failed("no_path");
				return;
			}
			BotController.get().startNavigation(path, goal, 1.0, true,
					System.currentTimeMillis() + Math.max(1000L, remainingMs()));
			navStarted = true;
		}
	}

	@Override
	public void onCancel(Minecraft mc) {
		BotController.get().stopNavigation("cancelled");
	}

	@Override
	public JsonObject progress() {
		LocalPlayer p = Minecraft.getInstance().player;
		JsonObject o = new JsonObject();
		o.addProperty("distance", distance);
		if (p != null) {
			Vec3 from = source(p, Minecraft.getInstance().level);
			if (from != null) {
				o.addProperty("current", Math.round(p.position().distanceTo(from) * 100.0) / 100.0);
			}
		}
		return o;
	}

	@Override
	public String describe() {
		return "fall back " + Math.round(distance) + " blocks";
	}

	/** Where we are getting away from: the entity's current position, or the fixed coordinate. */
	private Vec3 source(LocalPlayer p, ClientLevel level) {
		if (entityId == null) return new Vec3(fromX, fromY, fromZ);
		if (level == null) return null;
		for (Entity e : level.entitiesForRendering()) {
			if (entityId.equals(e.getUUID())) return e.position();
		}
		return null;
	}

	/**
	 * A standable spot at the requested distance, directly away from the threat — or off to one side
	 * when that line is blocked. Null when nowhere near the ring is walkable.
	 */
	private BlockPos chooseGoal(ClientLevel level, LocalPlayer p, Vec3 from) {
		double dx = p.getX() - from.x;
		double dz = p.getZ() - from.z;
		double len = Math.sqrt(dx * dx + dz * dz);
		if (len < 0.001) {
			// Standing on top of it: there is no "away", so any direction will do.
			dx = 1.0;
			dz = 0.0;
			len = 1.0;
		}
		double ux = dx / len;
		double uz = dz / len;

		// Try the full distance first, then closer in, and around the arc at each step: a wall directly
		// behind the player should mean stepping aside, not giving up on retreating at all.
		double[] radii = { distance, distance * 0.7, distance * 0.45 };
		for (double r : radii) {
			if (r < 1.5) continue;
			for (double angle : ANGLES) {
				double rad = Math.toRadians(angle);
				double rx = ux * Math.cos(rad) - uz * Math.sin(rad);
				double rz = ux * Math.sin(rad) + uz * Math.cos(rad);
				BlockPos goal = standableNear(level, p, p.getX() + rx * r, p.getZ() + rz * r);
				if (goal != null) return goal;
			}
		}
		return null;
	}

	/**
	 * The nearest cell to (x, z) that the player could stand in — two blocks of clearance over solid
	 * ground, and not in water (retreating into a pond trades a mob for a drowning).
	 */
	private static BlockPos standableNear(ClientLevel level, LocalPlayer p, double x, double z) {
		int bx = Mth.floor(x);
		int bz = Mth.floor(z);
		int feetY = Mth.floor(p.getY());
		for (int dy = 1; dy >= -4; dy--) {
			BlockPos feet = new BlockPos(bx, feetY + dy, bz);
			if (!level.getBlockState(feet).getCollisionShape(level, feet).isEmpty()) continue;
			if (!level.getBlockState(feet.above()).getCollisionShape(level, feet.above()).isEmpty()) continue;
			if (!level.getBlockState(feet).getFluidState().isEmpty()) continue;
			BlockPos floor = feet.below();
			if (level.getBlockState(floor).getCollisionShape(level, floor).isEmpty()) continue;
			return feet;
		}
		return null;
	}

	private void finish(LocalPlayer p, String detail) {
		BotController.get().stopNavigation("done");
		JsonObject extra = new JsonObject();
		Vec3 from = source(p, Minecraft.getInstance().level);
		if (from != null) {
			extra.addProperty("finalDistance",
					Math.round(p.position().distanceTo(from) * 100.0) / 100.0);
		}
		done(detail, extra);
	}
}
