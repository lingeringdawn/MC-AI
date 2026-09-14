package dev.mcpfabric.client;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.handlers.ActionHandlers;
import dev.mcpfabric.client.handlers.ClientChatHandlers;
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
 * Client entrypoint. Registers all client-only handlers into the shared router started by
 * {@link McpFabric} and drives the {@link BotController} once per client tick.
 */
public class McpFabricClient implements ClientModInitializer {
	/** One-shot: single-player auto-pauses on focus loss, which would freeze the bot mid-action. */
	private static boolean pausePatched;

	@Override
	public void onInitializeClient() {
		RpcRouter router = McpFabric.router();
		if (router == null) {
			McpFabric.LOGGER.error("[mcpfabric] router not initialized; client handlers unavailable");
			return;
		}

		LocalPlayerHandlers.register(router);
		ControlHandlers.register(router);
		InteractHandlers.register(router);
		InventoryHandlers.register(router);
		VisionHandlers.register(router);
		NavHandlers.register(router);
		ActionHandlers.register(router);
		UiHandlers.register(router);
		ClientChatHandlers.register(router); // client variant of chat.send (speaks as local player)
		ClientEvents.register(McpFabric.events());

		// Single-player pauses itself the moment the window loses focus, which would stall every bot
		// action mid-swing while the human is in another window. Keep the world ticking instead; the
		// bot still only moves when it has control (see HumanControl). This waits for the first tick:
		// Minecraft.options does not exist yet while mod entrypoints are running.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (!pausePatched) {
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
		// Human-like motion is a live config toggle, so reflect it before the first tick.
		Humanizer.setEnabled(McpFabric.config().enableHumanization);

		McpFabric.LOGGER.info("[mcpfabric] client handlers registered");
	}
}
