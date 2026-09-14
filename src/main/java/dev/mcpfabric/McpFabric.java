package dev.mcpfabric;

import dev.mcpfabric.bridge.HttpBridgeServer;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.bridge.SseHub;
import dev.mcpfabric.config.McpConfig;
import dev.mcpfabric.events.EventBus;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mod bootstrap: config, event bus and the embedded HTTP bridge.
 *
 * <p>The mod is <b>client-only</b>, and deliberately so. Everything it does is something a player's
 * own client can do — observe the world it has loaded, and drive the local player's input — so it
 * works unchanged on any server, with or without operator rights. There is no server-side half to
 * enable, disable, or keep in sync, and nothing here ever asks a server to do something on the
 * player's behalf.
 *
 * <p>Started from {@code McpFabricClient} rather than a common entrypoint, which is what keeps a
 * dedicated server from ever loading it.
 */
public class McpFabric {
	public static final String MOD_ID = "mcpfabric";
	public static final Logger LOGGER = LoggerFactory.getLogger("mcpfabric");

	public static final String MC_VERSION = FabricLoader.getInstance()
			.getModContainer("minecraft")
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	public static final String MOD_VERSION = FabricLoader.getInstance()
			.getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("dev");

	private static McpConfig config;
	private static RpcRouter router;
	private static EventBus eventBus;
	private static SseHub sseHub;
	private static HttpBridgeServer httpServer;

	public static McpConfig config() {
		return config;
	}

	public static RpcRouter router() {
		return router;
	}

	public static EventBus events() {
		return eventBus;
	}

	/** Idempotent: safe to call more than once, and safe to call before anything is registered. */
	public static void init() {
		if (router != null) return;

		config = McpConfig.load();
		sseHub = new SseHub();
		eventBus = new EventBus(sseHub);
		router = new RpcRouter();

		httpServer = new HttpBridgeServer(config, router, eventBus, sseHub);
		try {
			httpServer.start();
		} catch (Exception e) {
			LOGGER.error("[mcpfabric] failed to start HTTP bridge on {}:{}", config.host, config.port, e);
		}

		if (config.requireAuth) {
			LOGGER.info("[mcpfabric] ready — bridge http://{}:{} (token: {})", config.host, config.port,
					config.source);
		} else {
			LOGGER.warn("[mcpfabric] ready — bridge http://{}:{} (authentication disabled)", config.host,
					config.port);
		}
	}

	/** Stop the bridge (used when the client shuts down). */
	public static void shutdown() {
		if (httpServer != null) httpServer.stop();
	}
}
