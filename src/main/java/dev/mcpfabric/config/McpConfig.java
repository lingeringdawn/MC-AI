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
 *
 * <p>Every switch here controls a <em>client</em> behaviour. The one exception worth calling out is
 * {@link #enableCheats}, which only permits the mod to <em>type commands</em> on the player's behalf —
 * it grants no privileged capability of its own, since the world still decides whether the player may
 * run them.
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
	/** Allow driving the local player: movement, look, mining, interaction, crafting, navigation. */
	public boolean enablePlayerControl = true;
	/** Allow capturing screenshots and structured scene descriptions. */
	public boolean enableVision = true;
	/**
	 * Make the bot's input read as human rather than mechanical: eased mouse-look (fast flick, soft
	 * settle) instead of constant-rate turning, a short reaction delay before acting, and
	 * circle-strafing and spacing in combat. Turn off for deterministic, machine-precise motion.
	 */
	public boolean enableHumanization = true;
	/**
	 * FOR TESTING ONLY. Allow the {@code cheat.*} group to issue vanilla commands as the local player
	 * — give yourself a pickaxe, summon the mob you want to train against, force night, build a test
	 * rig. Nothing is bypassed: the commands go through the normal path, so they still fail in a world
	 * where the player is not an operator and cheats are off. Turn it off for anything but testing.
	 */
	public boolean enableCheats = false;

	/**
	 * Sample the world every tick while a blocking action runs, so callers can see health, nearby
	 * hostiles, drops and task progress instead of staring at an opaque in-flight call. Read-only: it
	 * decides nothing, it only tells the caller what is going on.
	 */
	public boolean enableTaskObservation = true;
	/**
	 * Opt-in: act on the worst anomaly the observation reports — surface, water-bucket a lethal fall,
	 * eat when starving, withdraw from a mob — instead of only naming it and leaving the caller to
	 * respond. Off by default, so the mod reports rather than acts; even when on it never interrupts a
	 * task the caller asked for, and only fires in the gaps between actions.
	 */
	public boolean autoHandleAnomalies = false;

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
