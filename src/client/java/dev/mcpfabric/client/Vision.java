package dev.mcpfabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.function.Predicate;

/**
 * What the player can actually see from where they are standing.
 *
 * <p>This is deliberately not a block scan. A scan of loaded chunks knows about a trunk through a hill,
 * a drop behind a wall and an ore vein three caves away, none of which the player has any way of
 * knowing; acting on it is acting on information nobody has. Vision casts rays across the field of
 * view instead — the same cone the player looks through — and reports the first thing each ray lands
 * on, so every entry is something the player could point at and say "that one".
 *
 * <p>Two consequences worth stating, because they are the point:
 * <ul>
 *   <li>seeing more means <em>looking</em> — turn the camera and the visible set changes, exactly as it
 *       does for a person;</li>
 *   <li>what is not visible is not a target, and no amount of module-side scoring should pretend
 *       otherwise.</li>
 * </ul>
 *
 * <p>Rays use the block outline clip, so a hit is the nearest block along the ray and the line of
 * sight to it is clear by construction. Liquids are reported rather than skipped: "that log is in
 * water" is exactly the sort of thing the caller needs to be told before it decides to mine it.
 */
public final class Vision {
	/** How far the default sweep looks. Beyond this, voxel detail stops being meaningful. */
	public static final double DEFAULT_DISTANCE = 24.0;
	/** Player reach for mining/interacting; a visible block further away is a walk, not an action. */
	public static final double REACH = 4.5;

	/** One visible block: where, what, how far, and whether it sits in liquid. */
	public record Seen(BlockPos pos, String id, double distance, boolean wet, double hardness) {
		/** Visible and close enough to act on right now. */
		public boolean inReach() {
			return distance <= REACH;
		}
	}

	private Vision() {}

	/**
	 * Sweep the field of view and return the distinct blocks it lands on, nearest first.
	 *
	 * @param maxDistance how far the rays travel
	 * @param cols       rays across the horizontal field of view
	 * @param rows       rays down the vertical field of view
	 * @param idFilter   keep only these block ids (null = everything that is not air)
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
		// first ray to land on a block wins, and the sweep is then sorted by distance, so the entry
		// kept for a block is its nearest visible face.
		LinkedHashMap<BlockPos, Seen> seen = new LinkedHashMap<>();
		for (int r = 0; r < rows; r++) {
			float pitchOff = rows == 1 ? 0.0F : -32.0F + 64.0F * r / (rows - 1);
			for (int c = 0; c < cols; c++) {
				float yawOff = cols == 1 ? 0.0F : -48.0F + 96.0F * c / (cols - 1);
				Vec3 dir = dir(baseYaw + yawOff, basePitch + pitchOff);
				BlockHitResult hit = level.clip(new ClipContext(eye, eye.add(dir.scale(maxDistance)),
						ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
				if (hit.getType() != HitResult.Type.BLOCK) continue;
				BlockPos bp = hit.getBlockPos();
				if (seen.containsKey(bp)) continue;
				BlockState st = level.getBlockState(bp);
				String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
				if (idFilter != null && !idFilter.test(id)) continue;
				double d = eye.distanceTo(hit.getLocation());
				seen.put(bp, new Seen(bp, id, d, wet(level, bp), st.getDestroySpeed(level, bp)));
			}
		}
		out.addAll(seen.values());
		out.sort(Comparator.comparingDouble(Seen::distance));
		return limit > 0 && out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
	}

	/** Nearest visible block matching the filter, or null. */
	public static Seen nearestVisible(Minecraft mc, Predicate<String> idFilter, double maxDistance) {
		List<Seen> list = blocks(mc, maxDistance, 11, 6, idFilter, 1);
		return list.isEmpty() ? null : list.get(0);
	}

	/** True when this block has liquid in it — i.e. mining it means mining in water. */
	public static boolean wet(ClientLevel level, BlockPos pos) {
		return !level.getFluidState(pos).isEmpty();
	}

	/**
	 * True when mining this block would put the player's own head or feet under liquid. Checked for the
	 * block's own cell and the one above it, which is where the eye ends up while digging from the side.
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
