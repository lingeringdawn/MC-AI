package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcRouter;

import java.util.List;

/**
 * Polling the client-side event feed: chat and system messages the client received, plus the player
 * edges the client can see for itself (damage, death, dimension change).
 */
public final class ClientEventsHandlers {
	private ClientEventsHandlers() {}

	public static void register(RpcRouter router) {
		router.register("chat.getRecent", ctx -> {
			int limit = ctx.optInt("limit", 50);
			JsonObject o = new JsonObject();
			o.add("messages", McpFabric.events().recent(limit, List.of("chat", "system_message"), 0));
			return o;
		});

		router.register("events.getRecent", ctx -> {
			int limit = ctx.optInt("limit", 50);
			long sinceId = ctx.optLong("sinceId", 0);
			JsonObject o = new JsonObject();
			o.add("events", McpFabric.events().recent(limit, ctx.getStringList("types"), sinceId));
			o.addProperty("lastId", McpFabric.events().lastId());
			return o;
		});
	}
}
