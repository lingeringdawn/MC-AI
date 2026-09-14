package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.events.EventBus;

/**
 * Pushes watch transitions onto the bridge's event bus.
 *
 * <p>{@link Watch} is what a driver can block on; this is the same information for a driver that would
 * rather subscribe than wait — it ends up on the SSE stream ({@code GET /events}) as a {@code watch}
 * event, so a host can either wait on the socket or block in {@code watch.wait}, whichever suits it.
 *
 * <p>Emission is best effort and never throws: nothing about observing should be able to disturb a tick.
 */
public final class EventFeed {
	private static volatile EventBus bus;

	private EventFeed() {}

	/** Called once at startup, next to the other event registrations. */
	public static void bind(EventBus events) {
		bus = events;
	}

	static void emit(String kind, JsonObject entry) {
		EventBus b = bus;
		if (b == null || entry == null) return;
		try {
			JsonObject o = entry.deepCopy();
			o.addProperty("kind", kind);
			b.emit("watch", o);
		} catch (Throwable ignored) {
			// a subscriber must never be able to break the tick
		}
	}
}
