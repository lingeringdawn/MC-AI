package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reading the world, from the client's own copy of it.
 *
 * <p>This is all a client can honestly offer, and all it needs: the blocks in loaded chunks and what
 * the client knows about them. Because nothing privileged is used, the same calls return the same
 * answers whether the player is an operator or a complete stranger on a public server.
 *
 * <p>Unloaded chunks are reported as absent rather than guessed at, so a caller can tell "nothing
 * there" apart from "that is beyond what I can see".
 */
public final class ClientWorldHandlers {
	private static final int DEFAULT_REGION_CAP = 32768;
	private static final int SCAN_BUDGET = 250_000;

	private ClientWorldHandlers() {}

	public static void register(RpcRouter router) {
		router.register("world.getBlock", ctx -> ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			BlockPos pos = BlockPos.containing(ctx.getDouble("x"), ctx.getDouble("y"), ctx.getDouble("z"));
			if (!level.hasChunkAt(pos)) {
				throw RpcException.notFound("Chunk not loaded at " + pos.toShortString());
			}
			BlockState state = level.getBlockState(pos);
			JsonObject o = new JsonObject();
			o.addProperty("x", pos.getX());
			o.addProperty("y", pos.getY());
			o.addProperty("z", pos.getZ());
			o.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
			o.addProperty("air", state.isAir());
			o.addProperty("liquid", !state.getFluidState().isEmpty());
			o.addProperty("solid", !state.getCollisionShape(level, pos).isEmpty());
			o.add("properties", properties(state));
			o.addProperty("dimension", dimensionId(level));
			o.addProperty("blockLight", level.getBrightness(LightLayer.BLOCK, pos));
			o.addProperty("skyLight", level.getBrightness(LightLayer.SKY, pos));
			o.addProperty("hardness", state.getDestroySpeed(level, pos));
			return o;
		}));

		router.register("world.getBlocks", ctx -> ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			JsonObject from = ctx.getObject("from");
			JsonObject to = ctx.getObject("to");
			int x1 = from.get("x").getAsInt(), y1 = from.get("y").getAsInt(), z1 = from.get("z").getAsInt();
			int x2 = to.get("x").getAsInt(), y2 = to.get("y").getAsInt(), z2 = to.get("z").getAsInt();
			int minX = Math.min(x1, x2), minY = Math.min(y1, y2), minZ = Math.min(z1, z2);
			int maxX = Math.max(x1, x2), maxY = Math.max(y1, y2), maxZ = Math.max(z1, z2);
			boolean includeAir = ctx.optBool("includeAir", false);
			int cap = ctx.optInt("maxBlocks", DEFAULT_REGION_CAP);

			JsonArray blocks = new JsonArray();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			boolean truncated = false;
			outer:
			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					for (int z = minZ; z <= maxZ; z++) {
						if (blocks.size() >= cap) {
							truncated = true;
							break outer;
						}
						m.set(x, y, z);
						if (!level.hasChunkAt(m)) continue;
						BlockState state = level.getBlockState(m);
						if (!includeAir && state.isAir()) continue;
						JsonObject b = new JsonObject();
						b.addProperty("x", x);
						b.addProperty("y", y);
						b.addProperty("z", z);
						b.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
						blocks.add(b);
					}
				}
			}
			JsonObject o = new JsonObject();
			o.addProperty("dimension", dimensionId(level));
			o.addProperty("count", blocks.size());
			o.addProperty("truncated", truncated);
			o.add("blocks", blocks);
			return o;
		}));

		router.register("world.findBlocks", ctx -> ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			JsonObject center = ctx.getObject("center");
			int cx = center.get("x").getAsInt(), cy = center.get("y").getAsInt(), cz = center.get("z").getAsInt();
			int radius = ctx.getInt("radius");
			int maxResults = ctx.optInt("maxResults", 64);
			Set<String> wanted = new HashSet<>(ctx.getStringList("blockIds"));
			if (wanted.isEmpty()) throw RpcException.badRequest("blockIds must not be empty.");

			List<JsonObject> found = new ArrayList<>();
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
			long scanned = 0;
			boolean truncated = false;
			int r2 = radius * radius;
			outer:
			for (int y = cy - radius; y <= cy + radius; y++) {
				for (int x = cx - radius; x <= cx + radius; x++) {
					for (int z = cz - radius; z <= cz + radius; z++) {
						int dx = x - cx, dy = y - cy, dz = z - cz;
						if (dx * dx + dy * dy + dz * dz > r2) continue;
						if (++scanned > SCAN_BUDGET) {
							truncated = true;
							break outer;
						}
						m.set(x, y, z);
						if (!level.hasChunkAt(m)) continue;
						BlockState state = level.getBlockState(m);
						String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
						if (!wanted.contains(id)) continue;
						JsonObject b = new JsonObject();
						b.addProperty("x", x);
						b.addProperty("y", y);
						b.addProperty("z", z);
						b.addProperty("id", id);
						b.addProperty("distance", Math.sqrt(dx * dx + dy * dy + dz * dz));
						found.add(b);
						if (found.size() >= maxResults) break outer;
					}
				}
			}
			found.sort(Comparator.comparingDouble(b -> b.get("distance").getAsDouble()));
			JsonArray arr = new JsonArray();
			found.forEach(arr::add);
			JsonObject o = new JsonObject();
			o.addProperty("dimension", dimensionId(level));
			o.addProperty("count", arr.size());
			o.addProperty("truncated", truncated);
			o.add("blocks", arr);
			return o;
		}));

		router.register("world.getTimeAndWeather", ctx -> ClientMc.call(() -> {
			ClientLevel level = ClientMc.level();
			long dayTime = level.getDayTime();
			long wrapped = dayTime % 24000L;
			if (wrapped < 0) wrapped += 24000L;
			JsonObject o = new JsonObject();
			o.addProperty("dimension", dimensionId(level));
			o.addProperty("dayTime", wrapped);
			o.addProperty("gameTime", level.getGameTime());
			o.addProperty("day", dayTime / 24000L);
			o.addProperty("raining", level.isRaining());
			o.addProperty("thundering", level.isThundering());
			return o;
		}));

		router.register("world.getDimensions", ctx -> ClientMc.call(() -> {
			// A client only ever has the dimension it is in; the others are not loaded, so claiming to
			// list them would be inventing information the client does not have.
			ClientLevel level = ClientMc.level();
			JsonArray dims = new JsonArray();
			dims.add(dimensionId(level));
			JsonObject o = new JsonObject();
			o.add("dimensions", dims);
			o.addProperty("current", dimensionId(level));
			o.addProperty("clientView", true);
			return o;
		}));
	}

	private static JsonObject properties(BlockState state) {
		JsonObject props = new JsonObject();
		for (var entry : state.getValues().entrySet()) {
			props.addProperty(entry.getKey().getName(), entry.getValue().toString());
		}
		return props;
	}

	/** The dimension id. {@code ResourceKey.location()} became {@code identifier()} in 1.21.11. */
	private static String dimensionId(ClientLevel level) {
		//? if <1.21.11 {
		return level.dimension().location().toString();
		//?} else
		/*return level.dimension().identifier().toString();*/
	}
}
