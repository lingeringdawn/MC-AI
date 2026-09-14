package dev.mcpfabric.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.mcpfabric.McpFabric;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Persistent configuration for the bridge, stored at {@code config/mcpfabric.config.json}.
 * An auth token is generated on first run and remains in the config file so it does not leak into
 * logs. Copy it into the MCP server's {@code MCPFABRIC_TOKEN} environment variable.
 */
public final class McpConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Bind host. Keep on loopback unless you really know what you are doing. */
	public String host = "127.0.0.1";
	public int port = 25599;
	/** Shared secret required in the Authorization: Bearer header. */
	public String token = "";
	/** When false, the bridge accepts unauthenticated requests (loopback only — use with care). */
	public boolean requireAuth = true;

	/** Max time a single RPC may block the game thread before timing out. */
	public int callTimeoutMs = 8000;

	// capability gates ------------------------------------------------------------------------
	// Cheat-like powers are OFF by default: the AI must act through real player operations.
	public boolean enableWorldWrite = false;
	public boolean enableCommands = false;
	public boolean enablePlayerControl = true;
	public boolean enableVision = true;
	/**
	 * Make the bot's input read as human rather than mechanical: eased mouse-look (fast flick, soft
	 * settle) instead of constant-rate turning, a short reaction delay before acting, circle-strafing
	 * and spacing in combat, and a slow gaze drift while standing still. Turn off for deterministic,
	 * machine-precise motion.
	 */
	public boolean enableHumanization = true;
	/**
	 * Let the bot save itself from a lethal fall by emptying a water bucket underneath it (the classic
	 * "MLG water"), then scoop the water back up. Only fires when the drop would genuinely hurt, and
	 * only when a water bucket is in the hotbar.
	 */
	public boolean enableFallSaving = true;

	/**
	 * Sample the world every tick while a blocking action runs, so callers can see health, nearby
	 * hostiles, drops and task progress instead of staring at an opaque in-flight call.
	 */
	public boolean enableTaskObservation = true;
	/**
	 * When observing, abort a running action as soon as the situation becomes critical (about to die,
	 * or a hostile mob inside melee range). Off by default: aborting is a judgement call.
	 */
	public boolean abortTaskOnDanger = false;

	public transient Path source;

	public static McpConfig load() {
		Path dir = FabricLoader.getInstance().getConfigDir();
		Path file = dir.resolve("mcpfabric.config.json");
		McpConfig cfg;
		if (Files.exists(file)) {
			try {
				cfg = GSON.fromJson(Files.readString(file), McpConfig.class);
				if (cfg == null) cfg = new McpConfig();
			} catch (Exception e) {
				McpFabric.LOGGER.error("[mcpfabric] failed to read config, using defaults", e);
				cfg = new McpConfig();
			}
		} else {
			cfg = new McpConfig();
		}
		if (cfg.token == null || cfg.token.isBlank()) {
			cfg.token = UUID.randomUUID().toString().replace("-", "");
		}
		cfg.source = file;
		cfg.save();
		return cfg;
	}

	public void save() {
		try {
			if (source == null) {
				source = FabricLoader.getInstance().getConfigDir().resolve("mcpfabric.config.json");
			}
			Files.createDirectories(source.getParent());
			Files.writeString(source, GSON.toJson(this));
		} catch (IOException e) {
			McpFabric.LOGGER.error("[mcpfabric] failed to write config", e);
		}
	}
}
