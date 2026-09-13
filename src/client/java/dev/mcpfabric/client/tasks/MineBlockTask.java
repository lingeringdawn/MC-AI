package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

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
		if (Minecraft.getInstance().gameMode != null) {
			int stage = Minecraft.getInstance().gameMode.getDestroyStage(); // 0-9 while digging
			if (stage >= 0) o.addProperty("breakStage", stage);
		}
		return o;
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
