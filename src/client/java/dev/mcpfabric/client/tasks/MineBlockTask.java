package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * One blocking call = the whole "dig this block" intent: walk within reach, face the block, pick the
 * best tool, then mine until it breaks. Thin wrapper over {@link BlockMiner}.
 */
public final class MineBlockTask extends ClientTask {
	private final BlockMiner miner;
	private boolean ready;

	public MineBlockTask(BlockPos pos) {
		this.miner = new BlockMiner(pos);
	}

	@Override
	public void onStart(Minecraft mc) {
		ready = miner.begin(mc);
	}

	@Override
	public void tick(Minecraft mc) {
		if (!ready) {
			failed("unreachable");
			return;
		}
		if (expired()) {
			miner.cancel();
			failed("timeout");
			return;
		}
		BlockMiner.State s = miner.tick(mc);
		if (s == BlockMiner.State.DONE) {
			JsonObject extra = new JsonObject();
			extra.addProperty("x", miner.pos().getX());
			extra.addProperty("y", miner.pos().getY());
			extra.addProperty("z", miner.pos().getZ());
			done("mined", extra);
		} else if (s == BlockMiner.State.UNREACHABLE) {
			failed("unreachable");
		}
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		JsonObject t = new JsonObject();
		t.addProperty("x", miner.pos().getX());
		t.addProperty("y", miner.pos().getY());
		t.addProperty("z", miner.pos().getZ());
		o.add("target", t);
		Minecraft mc = Minecraft.getInstance();
		LocalPlayer p = mc.player;
		// "Standing next to a block doing nothing" is indistinguishable from "slowly digging it" without
		// this, so report the whole chain of conditions the dig depends on: is the block in reach, is the
		// crosshair on it (that is what a click targets), is the button held, and is progress accruing.
		if (p != null) {
			o.addProperty("inReach", p.getEyePosition().distanceTo(Vec3.atCenterOf(miner.pos())) <= 4.5);
			o.addProperty("crosshairOnTarget", BlockMiner.crosshairOn(mc, miner.pos()));
			o.addProperty("lookErrorDeg", round(BotController.get().lookErrorDeg()));
			o.addProperty("attackHeld", BotController.get().isAttackHeld());
			o.addProperty("navState", BotController.get().navStateText());
			o.addProperty("distance", round(p.position().distanceTo(Vec3.atBottomCenterOf(miner.pos()))));
		}
		if (mc.gameMode != null) {
			int stage = mc.gameMode.getDestroyStage(); // 0-9 while digging, -1 when nothing is
			o.addProperty("breakStage", stage);
		}
		return o;
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	@Override
	public String describe() {
		BlockPos p = miner.pos();
		return "mine block at " + p.getX() + "," + p.getY() + "," + p.getZ();
	}

	@Override
	public void onCancel(Minecraft mc) {
		miner.cancel();
	}
}
