package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcRouter;
import net.minecraft.client.Minecraft;

/**
 * Status and capability discovery for a client-only mod.
 *
 * <p>Every group reported here is something this client can do on its own, with no server co-operation
 * and no operator rights — which is the whole point of the design. If a capability would need a
 * server to grant it, it does not exist in this mod.
 */
public final class ClientInfoHandlers {
	private ClientInfoHandlers() {}

	public static void register(RpcRouter router) {
		router.register("info.status", ctx -> {
			Minecraft mc = Minecraft.getInstance();
			JsonObject o = new JsonObject();
			o.addProperty("mod", "mcpfabric");
			o.addProperty("modVersion", McpFabric.MOD_VERSION);
			o.addProperty("minecraftVersion", McpFabric.MC_VERSION);
			o.addProperty("side", "client");
			o.addProperty("serverIndependent", true);
			o.addProperty("inWorld", mc.player != null && mc.level != null);
			o.add("capabilities", capabilities());
			o.add("methods", router.methodNames());
			return o;
		});

		router.register("info.capabilities", ctx -> {
			JsonObject o = new JsonObject();
			o.add("groups", groups());
			o.addProperty("serverIndependent", true);
			return o;
		});
	}

	private static JsonArray capabilities() {
		JsonArray a = new JsonArray();
		for (var key : groups().entrySet()) {
			if (key.getValue().getAsBoolean()) a.add(key.getKey());
		}
		return a;
	}

	private static JsonObject groups() {
		Minecraft mc = Minecraft.getInstance();
		boolean inWorld = mc.player != null && mc.level != null;
		boolean control = McpFabric.config().enablePlayerControl;

		JsonObject g = new JsonObject();
		g.addProperty("info", true);
		g.addProperty("chat", true);
		g.addProperty("events", true);
		g.addProperty("player_local", inWorld);
		// Reading is served from the client's own loaded view, so it needs no server.
		g.addProperty("world_read", inWorld);
		g.addProperty("entities", inWorld);
		g.addProperty("inventory", inWorld);
		g.addProperty("control", inWorld && control);
		g.addProperty("interact", inWorld && control);
		g.addProperty("navigation", inWorld && control);
		g.addProperty("vision", inWorld && McpFabric.config().enableVision);
		return g;
	}
}
