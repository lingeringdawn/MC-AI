package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * The "chop/ dig this block" behaviour a human performs in one intent: walk within reach, face the
 * block, pick the best tool in the hotbar, then mine until it breaks. One RPC call does the lot.
 */
public final class MineBlockTask extends ClientTask {
	private static final int NODE_BUDGET = 12000;
	private static final double BLOCK_REACH = 4.5;

	private final BlockPos pos;
	private boolean started;
	private boolean approachFailed;
	private boolean navStarted;
	private boolean miningStarted;
	private String toolUsed = "hand";

	public MineBlockTask(BlockPos pos) {
		this.pos = pos;
	}

	@Override
	public void onStart(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			approachFailed = true;
			return;
		}
		if (!inReach(p)) {
			if (!beginApproach(mc, p, level)) {
				approachFailed = true;
				return;
			}
		}
		started = true;
	}

	@Override
	public void tick(Minecraft mc) {
		if (approachFailed) {
			failed("unreachable");
			return;
		}
		if (!started) {
			failed("not_started");
			return;
		}
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		MultiPlayerGameMode gm = mc.gameMode;
		if (p == null || level == null || gm == null) {
			failed("no_world");
			return;
		}
		if (expired()) {
			release(mc);
			failed("timeout");
			return;
		}

		if (level.getBlockState(pos).isAir()) {
			BotController.get().stopNavigation("done");
			JsonObject extra = new JsonObject();
			extra.addProperty("x", pos.getX());
			extra.addProperty("y", pos.getY());
			extra.addProperty("z", pos.getZ());
			extra.addProperty("tool", toolUsed);
			done("mined", extra);
			return;
		}

		if (!inReach(p)) {
			if (!navStarted && !beginApproach(mc, p, level)) {
				failed("unreachable");
			}
			return;
		}

		if (navStarted) {
			BotController.get().stopNavigation("in_reach");
			navStarted = false;
		}
		aim(p);
		if (!miningStarted) {
			toolUsed = equipBestTool(mc, level.getBlockState(pos));
			Direction face = faceToward(pos, p.getEyePosition());
			BotController.get().startMining(pos, face);
			miningStarted = true;
		}
	}

	@Override
	public void onCancel(Minecraft mc) {
		release(mc);
	}

	private boolean beginApproach(Minecraft mc, LocalPlayer p, ClientLevel level) {
		AStarPathfinder pf = new AStarPathfinder(level, NODE_BUDGET);
		double reach = Math.max(1.0, BLOCK_REACH - 0.8);
		List<BlockPos> path = pf.findPath(p.blockPosition(), pos, reach);
		if (path == null || path.isEmpty()) return false;
		BotController.get().startNavigation(path, pos, reach, false,
				System.currentTimeMillis() + Math.max(1000L, remainingMs()));
		navStarted = true;
		return true;
	}

	private void release(Minecraft mc) {
		BotController.get().stopMining();
		BotController.get().stopNavigation("cancelled");
		miningStarted = false;
		navStarted = false;
	}

	private boolean inReach(LocalPlayer p) {
		return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= BLOCK_REACH;
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
