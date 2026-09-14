package dev.mcpfabric.client;

import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.MainThread;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.ThrowingSupplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;

/** Null-safe access to client singletons + scheduling onto the render thread. */
public final class ClientMc {
	private ClientMc() {}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public static LocalPlayer player() throws RpcException {
		LocalPlayer p = mc().player;
		if (p == null) throw RpcException.noClientPlayer();
		return p;
	}

	public static ClientLevel level() throws RpcException {
		ClientLevel l = mc().level;
		if (l == null) throw RpcException.noClientPlayer();
		return l;
	}

	public static MultiPlayerGameMode gameMode() throws RpcException {
		MultiPlayerGameMode g = mc().gameMode;
		if (g == null) throw RpcException.noClientPlayer();
		return g;
	}

	/**
	 * Run a task on the render thread and wait for the result.
	 *
	 * <p>When the caller is <em>already</em> on the render thread — a plan step driving a handler from
	 * inside the client tick, for instance — the work runs inline instead. Scheduling it would put it
	 * behind the very tick that is waiting for it, which is a deadlock that only ends when the call
	 * times out.
	 */
	public static <T> T call(ThrowingSupplier<T> task) throws RpcException {
		Minecraft mc = mc();
		if (mc.isSameThread()) {
			try {
				return task.get();
			} catch (RpcException e) {
				throw e;
			} catch (Throwable t) {
				throw new RpcException("internal", t.getClass().getSimpleName() + ": " + t.getMessage());
			}
		}
		return MainThread.call(mc, McpFabric.config().callTimeoutMs, task);
	}
}
