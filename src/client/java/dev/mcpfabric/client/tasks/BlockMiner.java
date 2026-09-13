package dev.mcpfabric.client.tasks;

import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Reusable single-block mining behaviour: approach into reach, aim, auto-pick the best hotbar tool,
 * then mine until the block is gone. Shared by {@link MineBlockTask} and {@link MineVeinTask} so the
 * "dig one block" logic lives in exactly one place.
 */
final class BlockMiner {
	enum State { WORKING, DONE, UNREACHABLE }

	private static final int NODE_BUDGET = 12000;
	private static final double REACH = 4.5;
	/** A* approach target: close enough that arriving guarantees the eye is within {@link #REACH}. */
	private static final double APPROACH_REACH = 2.5;

	private final BlockPos pos;
	private boolean navStarted;
	private boolean miningStarted;
	private String tool = "hand";

	BlockMiner(BlockPos pos) {
		this.pos = pos;
	}

	BlockPos pos() {
		return pos;
	}

	String tool() {
		return tool;
	}

	/** Claim this block: returns false if it cannot be reached (no path / no world). */
	boolean begin(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) return false;
		if (level.getBlockState(pos).isAir()) return true;
		if (inReach(p)) return true;
		return approach(mc, p, level);
	}

	State tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		MultiPlayerGameMode gm = mc.gameMode;
		if (p == null || level == null || gm == null) return State.UNREACHABLE;
		if (level.getBlockState(pos).isAir()) {
			BotController.get().stopNavigation("done");
			return State.DONE;
		}
		if (!inReach(p)) {
			if (isNavigating()) return State.WORKING;
			navStarted = false;
			if (!approach(mc, p, level)) return State.UNREACHABLE;
			return State.WORKING;
		}
		if (navStarted) {
			BotController.get().stopNavigation("in_reach");
			navStarted = false;
		}
		aim(p);
		if (!miningStarted) {
			tool = equipBestTool(mc, level.getBlockState(pos));
			BotController.get().startMining(pos, faceToward(pos, p.getEyePosition()));
			miningStarted = true;
		}
		return State.WORKING;
	}

	void cancel() {
		BotController.get().stopMining();
		BotController.get().stopNavigation("cancelled");
		miningStarted = false;
		navStarted = false;
	}

	private boolean approach(Minecraft mc, LocalPlayer p, ClientLevel level) {
		AStarPathfinder pf = new AStarPathfinder(level, NODE_BUDGET);
		double reach = APPROACH_REACH;
		List<BlockPos> path = pf.findPath(p.blockPosition(), pos, reach);
		if (path == null || path.isEmpty()) return false;
		BotController.get().startNavigation(path, pos, reach, false, System.currentTimeMillis() + 30_000L);
		navStarted = true;
		return true;
	}

	private boolean inReach(LocalPlayer p) {
		Vec3 eye = p.getEyePosition();
		// Distance to the closest point of the block box — matches vanilla block reach better than the centre.
		double cx = Mth.clamp(eye.x, pos.getX(), pos.getX() + 1.0);
		double cy = Mth.clamp(eye.y, pos.getY(), pos.getY() + 1.0);
		double cz = Mth.clamp(eye.z, pos.getZ(), pos.getZ() + 1.0);
		return eye.distanceToSqr(cx, cy, cz) <= REACH * REACH;
	}

	private static boolean isNavigating() {
		com.google.gson.JsonObject s = BotController.get().statusJson();
		return s.has("active") && s.get("active").getAsBoolean();
	}

	private void aim(LocalPlayer p) {
		double dx = pos.getX() + 0.5 - p.getX();
		double dy = pos.getY() + 0.5 - p.getEyeY();
		double dz = pos.getZ() + 0.5 - p.getZ();
		double horiz = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
		float pitch = (float) (-(Math.atan2(dy, horiz) * (180.0 / Math.PI)));
		p.setYRot(yaw);
		p.setXRot(pitch);
		p.setYHeadRot(yaw);
		p.setYBodyRot(yaw);
	}

	/** Pick the hotbar item with the highest destroy speed for this block. */
	private String equipBestTool(Minecraft mc, BlockState state) {
		LocalPlayer p = mc.player;
		int best = -1;
		float bestSpeed = 1.0F;
		for (int i = 0; i <= 8; i++) {
			ItemStack st = p.getInventory().getItem(i);
			if (st.isEmpty()) continue;
			float sp = st.getDestroySpeed(state);
			if (sp > bestSpeed + 0.01F) {
				bestSpeed = sp;
				best = i;
			}
		}
		if (best < 0) return "hand";
		//? if >=1.21.5 {
		p.getInventory().setSelectedSlot(best);
		//?} else
		/*p.getInventory().selected = best;*/
		p.connection.send(new ServerboundSetCarriedItemPacket(best));
		return BuiltInRegistries.ITEM.getKey(p.getInventory().getItem(best).getItem()).toString();
	}

	private static Direction faceToward(BlockPos pos, Vec3 eye) {
		double dx = eye.x - (pos.getX() + 0.5);
		double dy = eye.y - (pos.getY() + 0.5);
		double dz = eye.z - (pos.getZ() + 0.5);
		double ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
		if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		if (az >= ax && az >= ay) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
		return dy > 0 ? Direction.UP : Direction.DOWN;
	}
}
