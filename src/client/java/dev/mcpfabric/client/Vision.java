package dev.mcpfabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * What the player can actually see from where they are standing — including what is behind leaves,
 * glass and water.
 *
 * <p>This is deliberately not a block scan. A scan of loaded chunks knows about a trunk through a hill,
 * a drop behind a wall and an ore vein three caves away, none of which the player has any way of
 * knowing; acting on it is acting on information nobody has. Vision casts rays across the field of
 * view — the cone the player looks through — and walks each ray a fraction of a block at a time.
 *
 * <p>Walking rather than clipping is what makes see-through blocks work. Vanilla's clip stops at the
 * first block with a shape, so a trunk under its own canopy reads as "nothing there" when in fact a
 * player looking at that tree sees bark through the gaps in the leaves. Here a block that does not
 * occlude the view — leaves, glass, water, vines, plants — is recorded and the ray carries on, so a
 * trunk behind leaves is reported as a sighting with {@code through:["minecraft:oak_leaves"]}, and the
 * leaves themselves are reported too (with {@code transparent:true}) because they are often exactly
 * the thing worth clearing.
 *
 * <p>The two consequences worth stating, because they are the point:
 * <ul>
 *   <li>seeing more means <em>looking</em> — turn the camera and the visible set changes;</li>
 *   <li>nothing here ranks, prefers or refuses. It reports positions, distances, whether a block sits
 *       in liquid, whether it occludes, and what was in front of it. Deciding is the caller's job.</li>
 * </ul>
 */
public final class Vision {
	/** How far the default sweep looks. Beyond this, voxel detail stops being meaningful. */
	public static final double DEFAULT_DISTANCE = 24.0;
	/** Player reach for mining/interacting; a visible block further away is a walk, not an action. */
	public static final double REACH = 4.5;
	/** Ray step. A quarter block is fine enough not to slip through a corner and cheap enough per tick. */
	private static final double STEP = 0.25;

	/**
	 * One thing a ray landed on.
	 *
	 * @param transparent true when this block does not occlude the view (leaves, glass, water, plants),
	 *                    i.e. the ray carried on past it and what is behind it is visible too
	 * @param through     the see-through blocks the ray passed before reaching this one, nearest first
	 */
	public record Seen(BlockPos pos, String id, double distance, boolean wet, double hardness,
			boolean transparent, List<String> through) {
		/** Visible and close enough to act on right now. */
		public boolean inReach() {
			return distance <= REACH;
		}
	}

	private Vision() {}

	/**
	 * Sweep the field of view and return everything the rays landed on, nearest first.
	 *
	 * @param maxDistance how far the rays travel
	 * @param cols       rays across the horizontal field of view
	 * @param rows       rays down the vertical field of view
	 * @param idFilter   keep only these block ids (null = everything that is not air). Blocks that are
	 *                   filtered out are still walked through, which is what lets a filter for logs
	 *                   find the log standing behind a wall of leaves.
	 * @param limit      at most this many entries
	 */
	public static List<Seen> blocks(Minecraft mc, double maxDistance, int cols, int rows,
			Predicate<String> idFilter, int limit) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		List<Seen> out = new ArrayList<>();
		if (p == null || level == null) return out;
		Vec3 eye = p.getEyePosition();
		float baseYaw = p.getYRot();
		float basePitch = p.getXRot();

		// Distinct blocks only: a trunk fills a dozen rays and saying so twelve times is noise. The
		// first ray to land on a block wins — so the entry kept for it is its nearest visible face —
		// and the list is sorted by distance afterwards.
		LinkedHashMap<BlockPos, Seen> seen = new LinkedHashMap<>();
		for (int r = 0; r < rows; r++) {
			float pitchOff = rows == 1 ? 0.0F : -32.0F + 64.0F * r / (rows - 1);
			for (int c = 0; c < cols; c++) {
				float yawOff = cols == 1 ? 0.0F : -48.0F + 96.0F * c / (cols - 1);
				walk(level, eye, dir(baseYaw + yawOff, basePitch + pitchOff), maxDistance, idFilter, seen);
			}
		}
		out.addAll(seen.values());
		out.sort(Comparator.comparingDouble(Seen::distance));
		return limit > 0 && out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
	}

	/**
	 * Follow one ray until it leaves the view or hits something that occludes.
	 *
	 * <p>See-through blocks are collected as they are passed and the ray continues, which is how a
	 * sighting ends up carrying the leaves it was seen through. A block that does occlude ends the ray:
	 * nothing behind it is visible, so reporting it would be inventing information.
	 */
	private static void walk(ClientLevel level, Vec3 eye, Vec3 dir, double maxDistance,
			Predicate<String> idFilter, LinkedHashMap<BlockPos, Seen> into) {
		List<String> through = new ArrayList<>();
		int lastX = Integer.MIN_VALUE;
		int lastY = Integer.MIN_VALUE;
		int lastZ = Integer.MIN_VALUE;
		for (double t = 0.15; t <= maxDistance; t += STEP) {
			Vec3 at = eye.add(dir.scale(t));
			int x = (int) Math.floor(at.x);
			int y = (int) Math.floor(at.y);
			int z = (int) Math.floor(at.z);
			if (x == lastX && y == lastY && z == lastZ) continue;
			lastX = x;
			lastY = y;
			lastZ = z;
			BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos(x, y, z);
			if (!level.hasChunkAt(m)) return; // unloaded: nothing honest to report beyond here

			BlockState st = level.getBlockState(m);
			boolean liquid = !level.getFluidState(m).isEmpty();
			if (st.isAir() && !liquid) continue;
			String id = st.isAir()
					? BuiltInRegistries.FLUID.getKey(level.getFluidState(m).getType()).toString()
					: BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
			boolean seeThrough = liquid || !st.canOcclude();
			BlockPos fixed = m.immutable();
			if ((idFilter == null || idFilter.test(id)) && !into.containsKey(fixed)) {
				into.put(fixed, new Seen(fixed, id, eye.distanceTo(at), liquid,
						st.getDestroySpeed(level, fixed), seeThrough, List.copyOf(through)));
			}
			if (seeThrough) through.add(id);
			else return;
		}
	}

	/** Nearest visible block matching the filter, or null. */
	public static Seen nearestVisible(Minecraft mc, Predicate<String> idFilter, double maxDistance) {
		List<Seen> list = blocks(mc, maxDistance, 11, 6, idFilter, 1);
		return list.isEmpty() ? null : list.get(0);
	}

	/** True when this block has liquid in it — i.e. digging it means digging in water. */
	public static boolean wet(ClientLevel level, BlockPos pos) {
		return !level.getFluidState(pos).isEmpty();
	}

	/**
	 * True when digging this block would put the player's own head or feet under liquid. Reported as a
	 * fact by the tasks that know it; whether that matters is the caller's decision.
	 */
	public static boolean submerged(ClientLevel level, BlockPos pos) {
		return wet(level, pos) || wet(level, pos.above());
	}

	private static Vec3 dir(float yawDeg, float pitchDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		return new Vec3(-Math.cos(pitch) * Math.sin(yaw), -Math.sin(pitch), Math.cos(pitch) * Math.cos(yaw));
	}
}
