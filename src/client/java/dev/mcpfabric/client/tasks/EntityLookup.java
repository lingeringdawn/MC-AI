package dev.mcpfabric.client.tasks;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

import java.util.UUID;

/** Shared entity lookup for the short tasks that all operate on one target entity. */
final class EntityLookup {
	private EntityLookup() {}

	static Entity find(ClientLevel level, UUID uuid) {
		if (level == null) return null;
		for (Entity e : level.entitiesForRendering()) {
			if (e.getUUID().equals(uuid)) return e;
		}
		return null;
	}
}
