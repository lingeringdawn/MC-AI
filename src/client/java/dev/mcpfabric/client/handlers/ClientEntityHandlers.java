package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Querying entities from the client's view: every entity the client is currently rendering, which is
 * exactly the set a player can see. No server access is involved, so this behaves identically on a
 * public server.
 */
public final class ClientEntityHandlers {
	private ClientEntityHandlers() {}

	public static void register(RpcRouter router) {
		router.register("entities.query", ctx -> ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			LocalPlayer p = ClientMc.player();
			double radius = ctx.optDouble("radius", 32.0);
			JsonObject c = ctx.optObject("center");
			double cx;
			double cy;
			double cz;
			if (c != null) {
				cx = c.get("x").getAsDouble();
				cy = c.get("y").getAsDouble();
				cz = c.get("z").getAsDouble();
			} else {
				cx = p.getX();
				cy = p.getY();
				cz = p.getZ();
			}
			boolean includePlayers = ctx.optBool("includePlayers", true);
			boolean onlyLiving = ctx.optBool("onlyLiving", false);
			int maxResults = ctx.optInt("maxResults", 100);
			Set<String> types = new HashSet<>(ctx.getStringList("types"));

			List<Entity> found = new ArrayList<>();
			for (Entity e : level.entitiesForRendering()) {
				if (!e.isAlive()) continue;
				if (!includePlayers && e instanceof Player) continue;
				if (onlyLiving && !(e instanceof LivingEntity)) continue;
				if (!types.isEmpty() && !types.contains(typeId(e))) continue;
				if (e.distanceToSqr(cx, cy, cz) > radius * radius) continue;
				found.add(e);
			}
			found.sort(Comparator.comparingDouble(e -> e.distanceToSqr(cx, cy, cz)));

			JsonArray arr = new JsonArray();
			for (int i = 0; i < Math.min(maxResults, found.size()); i++) {
				arr.add(describe(found.get(i), cx, cy, cz));
			}
			JsonObject o = new JsonObject();
			o.addProperty("dimension", dimensionId(level));
			o.addProperty("total", found.size());
			o.addProperty("returned", arr.size());
			o.add("entities", arr);
			return o;
		}));

		router.register("entities.get", ctx -> ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			LocalPlayer p = ClientMc.player();
			UUID uuid;
			try {
				uuid = UUID.fromString(ctx.getString("uuid"));
			} catch (IllegalArgumentException e) {
				throw RpcException.badRequest("Invalid UUID: " + ctx.optString("uuid", ""));
			}
			for (Entity e : level.entitiesForRendering()) {
				if (!e.getUUID().equals(uuid)) continue;
				JsonObject o = describe(e, p.getX(), p.getY(), p.getZ());
				o.addProperty("dimension", dimensionId(level));
				o.addProperty("onGround", e.onGround());
				o.addProperty("yaw", e.getYRot());
				o.addProperty("pitch", e.getXRot());
				JsonObject motion = new JsonObject();
				motion.addProperty("x", e.getDeltaMovement().x);
				motion.addProperty("y", e.getDeltaMovement().y);
				motion.addProperty("z", e.getDeltaMovement().z);
				o.add("motion", motion);
				return o;
			}
			throw RpcException.notFound("No visible entity with uuid " + uuid);
		}));
	}

	private static JsonObject describe(Entity e, double cx, double cy, double cz) {
		JsonObject o = new JsonObject();
		o.addProperty("uuid", e.getUUID().toString());
		o.addProperty("type", typeId(e));
		o.addProperty("name", e.getName().getString());
		o.addProperty("isPlayer", e instanceof Player);
		o.addProperty("x", e.getX());
		o.addProperty("y", e.getY());
		o.addProperty("z", e.getZ());
		o.addProperty("distance", Math.sqrt(e.distanceToSqr(cx, cy, cz)));
		if (e instanceof LivingEntity le) {
			o.addProperty("health", le.getHealth());
			o.addProperty("maxHealth", le.getMaxHealth());
		}
		return o;
	}

	private static String typeId(Entity e) {
		return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
	}

	/** The dimension id. {@code ResourceKey.location()} became {@code identifier()} in 1.21.11. */
	private static String dimensionId(ClientLevel level) {
		//? if <1.21.11 {
		return level.dimension().location().toString();
		//?} else
		/*return level.dimension().identifier().toString();*/
	}
}
