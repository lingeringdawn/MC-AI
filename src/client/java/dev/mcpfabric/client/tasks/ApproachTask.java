package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.client.BotController;
import dev.mcpfabric.client.nav.AStarPathfinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * Short task: walk until a target entity is within {@code reach} blocks, then stop.
 *
 * <p>Deliberately does exactly one small thing and settles fast. A bigger intent like "fight that
 * mob" is built by <em>composing</em> short steps — approach, swing, approach again — instead of one
 * call that owns the whole fight for half a minute. Short steps mean the caller keeps control: it
 * sees the world between steps, can abandon the plan, or switch targets, and nothing important
 * happens off-screen.
 *
 * <p>Because a moving target invalidates a path quickly, this only ever plans one leg and re-plans
 * when the leg goes stale, rather than committing to a long route.
 */
public final class ApproachTask extends ClientTask {
	private static final int NODE_BUDGET = 12000;
	/** Re-plan the last leg after this many ticks of navigation, since the target keeps moving. */
	private static final int REPLAN_TICKS = 30;

	private final UUID target;
	private final double reach;
	private int navTicks;
	private int legs;

	public ApproachTask(UUID target, double reach) {
		this.target = target;
		this.reach = reach;
	}

	@Override
	public void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		ClientLevel level = mc.level;
		if (p == null || level == null) {
			failed("no_world");
			return;
		}
		if (expired()) {
			// Out of budget: hand back cleanly as a finished step, not a failure — the caller decides
			// whether to spend another short step closing the rest of the gap.
			stopNav();
			JsonObject extra = new JsonObject();
			extra.addProperty("distance", distanceTo(p, level));
			done("budget", extra);
			return;
		}

		Entity e = EntityLookup.find(level, target);
		if (e == null || !e.isAlive()) {
			stopNav();
			failed("not_found");
			return;
		}
		double dist = p.distanceTo(e);
		if (dist <= reach) {
			stopNav();
			JsonObject extra = new JsonObject();
			extra.addProperty("distance", round(dist));
			done("reached", extra);
			return;
		}

		if (isNavigating()) {
			navTicks++;
			if (navTicks % REPLAN_TICKS == 0) plan(p, level, e);
			return;
		}
		plan(p, level, e);
	}

	@Override
	public JsonObject progress() {
		JsonObject o = new JsonObject();
		o.addProperty("target", target.toString());
		o.addProperty("reach", reach);
		o.addProperty("legs", legs);
		LocalPlayer p = Minecraft.getInstance().player;
		ClientLevel level = Minecraft.getInstance().level;
		if (p != null && level != null) o.addProperty("distance", distanceTo(p, level));
		return o;
	}

	@Override
	public String describe() {
		return "approach " + target + " within " + reach + " blocks";
	}

	@Override
	public void onCancel(Minecraft mc) {
		stopNav();
	}

	/** Plan one leg toward the target and start walking it. */
	private void plan(LocalPlayer p, ClientLevel level, Entity e) {
		BlockPos goal = e.blockPosition();
		List<BlockPos> path = new AStarPathfinder(level, NODE_BUDGET)
				.findPath(p.blockPosition(), goal, Math.max(1.0, reach - 0.5));
		if (path == null || path.isEmpty()) {
			failed("no_path");
			return;
		}
		legs++;
		navTicks = 0;
		BotController.get().startNavigation(path, goal, Math.max(1.0, reach - 0.5), true,
				System.currentTimeMillis() + Math.max(1000L, remainingMs()));
	}

	private double distanceTo(LocalPlayer p, ClientLevel level) {
		Entity e = EntityLookup.find(level, target);
		return e == null ? -1.0 : round(p.distanceTo(e));
	}

	private static boolean isNavigating() {
		JsonObject s = BotController.get().statusJson();
		return s.has("active") && s.get("active").getAsBoolean();
	}

	private void stopNav() {
		BotController.get().stopNavigation("step_done");
		BotController.get().stopAllMovement();
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}
}
