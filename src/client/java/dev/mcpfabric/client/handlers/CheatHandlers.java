package dev.mcpfabric.client.handlers;

import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcContext;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;

/**
 * Test-only cheat commands — for standing up a scenario quickly (hand the bot a pickaxe, spawn the mob
 * you want to fight, force night, build a rig) instead of grinding for it.
 *
 * <p>Every one of these just builds a vanilla command string and sends it through
 * {@code connection.sendCommand}, so nothing here bypasses a permission, a gamerule or a server-side
 * check: if the world does not allow cheats, these fail exactly like typing the command would. The
 * group is off unless {@code enableCheats} is set in the config, and the reply reports whether the
 * player actually holds operator rights so a failure is obvious instead of mysterious.
 */
public final class CheatHandlers {
	private CheatHandlers() {}

	public static void register(RpcRouter router) {
		// Escape hatch: anything not wrapped below.
		router.register("cheat.command", ctx -> run(ctx.getString("command")));

		router.register("cheat.give", ctx -> run("give @s " + id(ctx.getString("item"))
				+ " " + count(ctx.optInt("count", 1))));

		router.register("cheat.summon", ctx -> {
			StringBuilder sb = new StringBuilder("summon ").append(id(ctx.getString("type")));
			if (ctx.has("x") && ctx.has("y") && ctx.has("z")) {
				sb.append(' ').append(num(ctx.getDouble("x"))).append(' ')
						.append(num(ctx.getDouble("y"))).append(' ').append(num(ctx.getDouble("z")));
				if (ctx.has("nbt")) sb.append(' ').append(ctx.getString("nbt"));
			}
			return run(sb.toString());
		});

		router.register("cheat.set_time", ctx -> run("time set " + ctx.getString("time")));

		router.register("cheat.set_weather", ctx -> {
			String cmd = "weather " + ctx.getString("weather");
			if (ctx.has("durationSeconds")) cmd += " " + count(ctx.getInt("durationSeconds"));
			return run(cmd);
		});

		router.register("cheat.set_game_mode", ctx -> run("gamemode " + ctx.getString("mode")));

		router.register("cheat.teleport", ctx -> {
			String cmd = "tp @s " + num(ctx.getDouble("x")) + " " + num(ctx.getDouble("y"))
					+ " " + num(ctx.getDouble("z"));
			if (ctx.has("yaw") && ctx.has("pitch")) {
				cmd += " " + num(ctx.getDouble("yaw")) + " " + num(ctx.getDouble("pitch"));
			}
			return run(cmd);
		});

		router.register("cheat.effect", ctx -> {
			String cmd = "effect give @s " + id(ctx.getString("effect"))
					+ " " + count(ctx.optInt("seconds", 30)) + " " + count(ctx.optInt("amplifier", 0));
			if (ctx.optBool("hideParticles", false)) cmd += " true";
			return run(cmd);
		});

		router.register("cheat.enchant", ctx -> run("enchant @s " + id(ctx.getString("enchantment"))
				+ " " + count(ctx.optInt("level", 1))));

		router.register("cheat.xp", ctx -> run("xp add @s " + count(ctx.getInt("amount"))
				+ (ctx.optBool("levels", false) ? " levels" : "")));

		router.register("cheat.gamerule", ctx -> run("gamerule " + ctx.getString("rule")
				+ " " + ctx.getString("value")));

		router.register("cheat.difficulty", ctx -> run("difficulty " + ctx.getString("difficulty")));

		// Handy for testing damage/death handling without hunting for a mob.
		router.register("cheat.damage", ctx -> run("damage @s " + num(ctx.getDouble("amount"))
				+ (ctx.has("type") ? " " + id(ctx.getString("type")) : "")));

		router.register("cheat.kill", ctx -> {
			String selector = "@e[type=!player,distance=.." + count(ctx.optInt("radius", 8));
			if (ctx.has("type")) selector += ",type=" + id(ctx.getString("type"));
			return run("kill " + selector + "]");
		});

		router.register("cheat.clear", ctx -> run("clear @s"
				+ (ctx.has("item") ? " " + id(ctx.getString("item")) : "")));

		// World editing, still through commands so the world's own rules decide what is allowed.
		router.register("cheat.set_block", ctx -> run("setblock " + num(ctx.getDouble("x")) + " "
				+ num(ctx.getDouble("y")) + " " + num(ctx.getDouble("z")) + " " + id(ctx.getString("block"))
				+ (ctx.has("mode") ? " " + ctx.getString("mode") : "")));

		router.register("cheat.fill", ctx -> {
			JsonObject from = ctx.getObject("from");
			JsonObject to = ctx.getObject("to");
			return run("fill " + coord(from, "x") + " " + coord(from, "y") + " " + coord(from, "z")
					+ " " + coord(to, "x") + " " + coord(to, "y") + " " + coord(to, "z")
					+ " " + id(ctx.getString("block"))
					+ (ctx.has("mode") ? " " + ctx.getString("mode") : ""));
		});

		// Everything above needs the player to hold operator rights, which a single-player world only
		// grants when cheats are on. Rather than make the caller hunt through the pause menu, expose the
		// same thing the "Open to LAN" screen does — the host owning their own world, nothing granted
		// from outside. Deliberately single-player only: on a real server it refuses instead of trying.
		router.register("cheat.open_to_lan", ctx -> {
			requireEnabled();
			return ClientMc.call(() -> {
				Minecraft mc = Minecraft.getInstance();
				IntegratedServer server = mc.getSingleplayerServer();
				if (server == null) {
					throw RpcException.unavailable("This is not a single-player world, so the mod has no "
							+ "business granting rights here — have an operator give permission instead.");
				}
				boolean published = server.publishServer(server.getWorldData().getGameType(), true,
						ctx.optInt("port", 0));
				JsonObject o = new JsonObject();
				o.addProperty("published", published);
				o.addProperty("port", server.getPort());
				o.addProperty("cheats", true);
				o.addProperty("note", "The world is now open to LAN with cheats enabled, which is what "
						+ "gives the host operator rights — the cheat_* group becomes usable.");
				return o;
			});
		});
	}

	private static void requireEnabled() throws RpcException {
		if (!McpFabric.config().enableCheats) {
			throw RpcException.unavailable("Cheat commands are disabled. Set \"enableCheats\": true in "
					+ "config/mcpfabric.config.json to use this group (intended for testing only).");
		}
	}

	// --- internals ---------------------------------------------------------------------------

	private static JsonObject run(String command) throws RpcException {
		requireEnabled();
		String cmd = command.startsWith("/") ? command.substring(1) : command;
		if (cmd.isBlank()) throw RpcException.badRequest("Empty command.");
		return ClientMc.call(() -> send(cmd));
	}

	private static JsonObject send(String cmd) throws RpcException {
		LocalPlayer p = ClientMc.player();
		// Permission level 2 = the "cheats"/operator level vanilla commands require. The client is told
		// this by the server, so it is a reliable prediction of whether the command will be accepted.
		boolean permitted = p != null && p.hasPermissions(2);
		if (p != null) p.connection.sendCommand(cmd);
		JsonObject o = new JsonObject();
		o.addProperty("sent", true);
		o.addProperty("command", cmd);
		o.addProperty("permitted", permitted);
		if (!permitted) {
			o.addProperty("hint", "the player does not hold operator rights in this world, so the "
					+ "command will be rejected — open the world to LAN with cheats enabled");
		}
		return o;
	}

	/** Accept both "minecraft:stone" and a bare "stone". */
	private static String id(String raw) {
		return raw.contains(":") ? raw : "minecraft:" + raw;
	}

	private static String coord(JsonObject o, String key) {
		return num(o.get(key).getAsDouble());
	}

	/** Commands take plain integers; keeping "1" from becoming "1.0" matters for count arguments. */
	private static String count(int n) {
		return Integer.toString(n);
	}

	private static String num(double v) {
		if (!Double.isInfinite(v) && v == Math.rint(v)) return Long.toString((long) v);
		return Double.toString(Math.round(v * 100.0) / 100.0);
	}
}
