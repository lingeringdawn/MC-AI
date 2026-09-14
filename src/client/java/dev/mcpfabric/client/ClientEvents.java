package dev.mcpfabric.client;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.tasks.EventFeed;
import dev.mcpfabric.events.EventBus;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Client-side event feed.
 *
 * <p>Observing is done the way a player observes: messages the client receives, and changes it can
 * see in its own player. Nothing is subscribed to on a server, which is what lets the feed work on a
 * multiplayer server where the mod has no server-side presence at all.
 *
 * <p>The health and dimension edges are derived in {@link #tick} by comparing this tick's state with
 * the last one, so no server callback is needed to notice that the player got hit or changed world.
 */
public final class ClientEvents {
	private static EventBus bus;

	// last-observed state, for deriving edges
	private static float lastHealth = Float.NaN;
	private static boolean lastDead;
	private static String lastDimension = "";

	private ClientEvents() {}

	public static void register(EventBus events) {
		bus = events;
		// The wake-up stream rides the same bus, so a driver can either subscribe to /events or block in
		// watch.wait — same transitions either way.
		EventFeed.bind(events);

		ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
			JsonObject d = new JsonObject();
			d.addProperty("text", message.getString());
			d.addProperty("overlay", overlay);
			events.emit("system_message", d);
		});

		ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
			JsonObject d = new JsonObject();
			d.addProperty("text", message.getString());
			if (sender != null) {
				//? if <1.21.9 {
				d.addProperty("sender", sender.getName());
				//?} else
				/*d.addProperty("sender", sender.name());*/
			}
			events.emit("chat", d);
		});
	}

	/** Called every client tick to derive the events the client can see for itself. */
	public static void tick(Minecraft mc) {
		if (bus == null) return;
		LocalPlayer p = mc.player;
		if (p == null) {
			lastHealth = Float.NaN;
			lastDead = false;
			lastDimension = "";
			return;
		}

		float health = p.getHealth();
		boolean dead = p.isDeadOrDying() || health <= 0.0F;

		// Damage: any drop in health that the client itself can see. This mirrors the damage a player
		// would feel, without needing the server to tell us about it.
		if (!Float.isNaN(lastHealth) && health < lastHealth - 0.01F) {
			JsonObject d = new JsonObject();
			d.addProperty("amount", lastHealth - health);
			d.addProperty("healthBefore", lastHealth);
			d.addProperty("healthAfter", health);
			d.addProperty("x", p.getX());
			d.addProperty("y", p.getY());
			d.addProperty("z", p.getZ());
			bus.emit("player_damage", d);
		}
		if (dead && !lastDead) {
			JsonObject d = new JsonObject();
			d.addProperty("x", p.getX());
			d.addProperty("y", p.getY());
			d.addProperty("z", p.getZ());
			bus.emit("player_death", d);
		}

		String dimension = dimensionId(p);
		if (!lastDimension.isEmpty() && !lastDimension.equals(dimension)) {
			JsonObject d = new JsonObject();
			d.addProperty("from", lastDimension);
			d.addProperty("to", dimension);
			bus.emit("dimension_change", d);
		}
		lastDimension = dimension;
		lastHealth = health;
		lastDead = dead;
	}

	/** The dimension id. {@code ResourceKey.location()} became {@code identifier()} in 1.21.11. */
	private static String dimensionId(LocalPlayer p) {
		//? if <1.21.11 {
		return p.level().dimension().location().toString();
		//?} else
		/*return p.level().dimension().identifier().toString();*/
	}
}
