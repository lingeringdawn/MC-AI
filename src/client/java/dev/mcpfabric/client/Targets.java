package dev.mcpfabric.client;

import dev.mcpfabric.bridge.RpcException;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The caller's shorthand for "that thing", resolved here and now.
 *
 * <p>Purely mechanical — nearest by distance, or whatever the crosshair is resting on — so a caller (or
 * a plan step) can say what it means instead of reading a query result, copying coordinates out of the
 * reply and passing them back in. It picks nothing and decides nothing: no scoring, no preference for
 * one mob over another, and when nothing matches it says so instead of guessing.
 *
 * <p>Resolved where it is used, which is what makes {@code {"action":"moveTo","target":"nearest_drop"}}
 * inside a plan mean the drop that exists <em>after</em> the previous steps ran, rather than the one
 * that existed when the plan was written.
 */
public final class Targets {
	/** How far to look for "the nearest one". */
	private static final double SEARCH_RADIUS = 48.0;

	/** The full vocabulary, for error messages and tool descriptions. */
	public static final String SPECS = "\"nearest_hostile\", \"nearest_drop\", \"nearest_animal\", "
			+ "\"looking_at\", \"visible_log\", \"visible_stone\", \"visible:<block id>\"";

	private Targets() {}

	/** The block the spec names, as a position to walk to or act on. */
	public static BlockPos block(String spec, String what) throws RpcException {
		LocalPlayer p = requirePlayer();
		switch (spec) {
			case "visible_log":
				return visible(p, s -> s.id().endsWith("_log"), "log", what).pos();
			case "visible_stone":
				return visible(p, s -> s.id().endsWith("stone") || s.id().contains("deepslate"),
						"stone", what).pos();
			case "looking_at": {
				HitResult hit = ClientMc.mc().hitResult;
				if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK) return b.getBlockPos();
				throw RpcException.unavailable("The crosshair is not on a block, so there is no 'looking_at' "
						+ "position for " + what + ". Look at it first (control.lookAt), or give x/y/z.");
			}
			case "nearest_drop": {
				ItemEntity drop = nearest(p, ItemEntity.class);
				if (drop == null) throw noTarget("dropped item", what);
				return drop.blockPosition();
			}
			case "nearest_hostile": {
				LivingEntity mob = nearestLiving(p, e -> e instanceof Enemy);
				if (mob == null) throw noTarget("hostile mob", what);
				return mob.blockPosition();
			}
			case "nearest_animal": {
				LivingEntity animal = nearestLiving(p, e -> !(e instanceof Enemy) && !(e instanceof Player));
				if (animal == null) throw noTarget("animal", what);
				return animal.blockPosition();
			}
			default:
				if (spec.startsWith("visible:")) {
					String want = spec.substring("visible:".length());
					if (want.isBlank()) {
						throw RpcException.badRequest("'visible:' needs a block id, e.g. \"visible:minecraft:stone\".");
					}
					return visible(p, s -> s.id().equals(want), want, what).pos();
				}
				throw RpcException.badRequest("Unknown target '" + spec + "' for " + what
						+ ". Use one of: " + SPECS + ".");
		}
	}

	/**
	 * The nearest block of a kind the player can <em>see</em> from where the player is looking.
	 *
	 * <p>Vision, not a chunk scan: only blocks the field of view actually lands on count, so the answer
	 * is always something the player could point at. It returns the nearest match and nothing else — no
	 * ranking, no preference, and no opinion about whether the block is a good idea to dig. A log
	 * standing in water is still a log, and choosing between it and a dry one is the caller's decision.
	 * The per-block facts to decide on ({@code wet}, {@code inReach}, {@code hardness}) are what
	 * {@code vision.scan} reports; read that when the choice matters.
	 */
	private static Vision.Seen visible(LocalPlayer p, Predicate<Vision.Seen> kind, String described, String what)
			throws RpcException {
		for (Vision.Seen s : Vision.blocks(ClientMc.mc(), Vision.DEFAULT_DISTANCE, 13, 7, null, 40)) {
			if (kind.test(s)) return s;
		}
		throw RpcException.unavailable("No " + described + " is visible from where the player is looking, so '"
				+ what + "' has nothing to act on. Turn the camera toward it (control.lookAt) and scan "
				+ "again — 'visible_*' targets are what the eye can see, not what the chunks contain.");
	}

	/** The UUID of the entity the spec names. */
	public static UUID entity(String spec, String what) throws RpcException {
		LocalPlayer p = requirePlayer();
		switch (spec) {
			case "looking_at": {
				HitResult hit = ClientMc.mc().hitResult;
				if (hit instanceof EntityHitResult e && hit.getType() == HitResult.Type.ENTITY) {
					return e.getEntity().getUUID();
				}
				throw RpcException.unavailable("The crosshair is not on an entity, so there is no 'looking_at' "
						+ "target for " + what + ". Look at it first (control.lookAt), or give 'uuid'.");
			}
			case "nearest_drop":
				return uuidOf(nearest(p, ItemEntity.class), "dropped item", what);
			case "nearest_hostile":
				return uuidOf(nearestLiving(p, e -> e instanceof Enemy), "hostile mob", what);
			case "nearest_animal":
				return uuidOf(nearestLiving(p, e -> !(e instanceof Enemy) && !(e instanceof Player)), "animal", what);
			default:
				throw RpcException.badRequest("Unknown target '" + spec + "' for " + what
						+ ". Use one of: " + SPECS + ".");
		}
	}

	private static <T extends Entity> T nearest(LocalPlayer p, Class<T> type) {
		return p.level().getEntitiesOfClass(type, p.getBoundingBox().inflate(SEARCH_RADIUS))
				.stream()
				.filter(Entity::isAlive)
				.min(Comparator.comparingDouble(p::distanceToSqr))
				.orElse(null);
	}

	private static LivingEntity nearestLiving(LocalPlayer p, Predicate<LivingEntity> matches) {
		return p.level().getEntitiesOfClass(LivingEntity.class, p.getBoundingBox().inflate(SEARCH_RADIUS))
				.stream()
				.filter(e -> e != p && e.isAlive() && matches.test(e))
				.min(Comparator.comparingDouble(p::distanceToSqr))
				.orElse(null);
	}

	private static UUID uuidOf(Entity e, String described, String what) throws RpcException {
		if (e == null) throw noTarget(described, what);
		return e.getUUID();
	}

	private static RpcException noTarget(String described, String what) {
		return RpcException.unavailable("No " + described + " within " + (int) SEARCH_RADIUS + " blocks, so '"
				+ what + "' has nothing to act on. Move closer, or give explicit coordinates.");
	}

	private static LocalPlayer requirePlayer() throws RpcException {
		LocalPlayer p = ClientMc.mc().player;
		if (p == null) throw RpcException.noClientPlayer();
		return p;
	}
}
