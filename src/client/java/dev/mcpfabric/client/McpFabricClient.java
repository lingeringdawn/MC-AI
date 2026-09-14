package dev.mcpfabric.client;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.handlers.ActionHandlers;
import dev.mcpfabric.client.handlers.ClientChatHandlers;
import dev.mcpfabric.client.handlers.ClientEntityHandlers;
import dev.mcpfabric.client.handlers.ClientEventsHandlers;
import dev.mcpfabric.client.handlers.ClientInfoHandlers;
import dev.mcpfabric.client.handlers.ClientWorldHandlers;
import dev.mcpfabric.client.handlers.ControlHandlers;
import dev.mcpfabric.client.handlers.InteractHandlers;
import dev.mcpfabric.client.handlers.InventoryHandlers;
import dev.mcpfabric.client.handlers.LocalPlayerHandlers;
import dev.mcpfabric.client.handlers.NavHandlers;
import dev.mcpfabric.client.handlers.UiHandlers;
import dev.mcpfabric.client.handlers.VisionHandlers;
import dev.mcpfabric.client.tasks.TaskManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

/**
 * The mod's single entrypoint. Everything is client-side by design, so there is no common entrypoint
 * and a dedicated server never loads any of it.
 *
 * <p>Boots the bridge first, then registers every handler into it, then drives the per-tick work:
 * the {@link BotController}, the blocking task runner and the client-side event feed.
 */
public class McpFabricClient implements ClientModInitializer {
	/** One-shot: single-player auto-pauses on focus loss, which would freeze the bot mid-action. */
	private static boolean pausePatched;

	@Override
	public void onInitializeClient() {
		McpFabric.init();

		RpcRouter router = McpFabric.router();
		if (router == null) {
			McpFabric.LOGGER.error("[mcpfabric] router not initialized; client handlers unavailable");
			return;
		}

		// --- observation: only what the client itself can see ---------------------------------
		ClientInfoHandlers.register(router);
		ClientWorldHandlers.register(router);
		ClientEntityHandlers.register(router);
		LocalPlayerHandlers.register(router);
		ClientEventsHandlers.register(router);
		ClientEvents.register(McpFabric.events());

		// --- acting: only what the player at this keyboard could do ----------------------------
		ControlHandlers.register(router);
		InteractHandlers.register(router);
		InventoryHandlers.register(router);
		NavHandlers.register(router);
		ActionHandlers.register(router);
		UiHandlers.register(router);
		VisionHandlers.register(router);
		ClientChatHandlers.register(router);

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!pausePatched) {
				// Single-player pauses itself the moment the window loses focus, which would stall every
				// bot action mid-swing while the human is in another window. Keep the world ticking
				// instead; the bot still only moves when it has control (see HumanControl).
				client.options.pauseOnLostFocus = false;
				pausePatched = true;
				McpFabric.LOGGER.info("[mcpfabric] pause-on-lost-focus disabled");
			}
			HumanControl.tick(client);
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> BotController.get().onClientTick(client));
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			TaskManager.get().setWatching(McpFabric.config().enableTaskObservation);
			TaskManager.get().setAbortOnDanger(McpFabric.config().abortTaskOnDanger);
			TaskManager.get().tick(client);
		});
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			// Keep the event ring buffer's tick stamp in step with the client's own world clock.
			if (client.level != null) McpFabric.events().setTick(client.level.getGameTime());
			ClientEvents.tick(client);
		});

		// Human-like motion is a live config toggle, so reflect it before the first tick.
		Humanizer.setEnabled(McpFabric.config().enableHumanization);

		McpFabric.LOGGER.info("[mcpfabric] client handlers registered");
	}
}
