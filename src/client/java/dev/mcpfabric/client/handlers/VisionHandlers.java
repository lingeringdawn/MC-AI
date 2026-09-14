package dev.mcpfabric.client.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import dev.mcpfabric.bridge.RpcRouter;
import dev.mcpfabric.client.ClientMc;
import dev.mcpfabric.client.Vision;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** Vision: framebuffer screenshot (for vision models) and a structured scene description. */
public final class VisionHandlers {
	private VisionHandlers() {}

	public static void register(RpcRouter router) {
		router.register("vision.screenshot", ctx -> {
			if (!McpFabric.config().enableVision) {
				throw RpcException.unavailable("Vision is disabled in mcpfabric.config.json (enableVision=false).");
			}
			Minecraft mc = ClientMc.mc();
			if (mc.player == null || mc.level == null) throw RpcException.noClientPlayer();

			// takeScreenshot performs the GPU readback (new render pipeline) and hands us a CPU-side
			// NativeImage via a callback that may fire after this frame, so coordinate via a future.
			CompletableFuture<JsonObject> future = new CompletableFuture<>();
			mc.execute(() -> {
				try {
					//? if >=1.21.5 {
					Screenshot.takeScreenshot(mainRenderTarget(mc), image -> {
						try {
							future.complete(imageToJson(image));
						} catch (Exception e) {
							future.completeExceptionally(e);
						} finally {
							image.close();
						}
					});
					//?} else {
					/*NativeImage image = Screenshot.takeScreenshot(mainRenderTarget(mc));
					try {
						future.complete(imageToJson(image));
					} finally {
						image.close();
					}
					*///?}
				} catch (Throwable t) {
					future.completeExceptionally(t);
				}
			});

			try {
				return future.get(Math.max(5000, McpFabric.config().callTimeoutMs), TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				Throwable cause = e.getCause() != null ? e.getCause() : e;
				throw new RpcException("screenshot_failed", "Failed to capture screenshot: " + cause.getMessage());
			}
		});

		router.register("vision.describeScene", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			ClientLevel level = ClientMc.level();
			Minecraft mc = ClientMc.mc();
			double maxDistance = ctx.optDouble("maxDistance", 48.0);
			int cols = ctx.optInt("rayColumns", 9);
			int rows = ctx.optInt("rayRows", 5);

			JsonObject o = new JsonObject();
			Vec3 eye = p.getEyePosition();
			o.add("eye", vec(eye));
			o.addProperty("yaw", p.getYRot());
			o.addProperty("pitch", p.getXRot());

			// What the crosshair is on.
			HitResult hit = mc.hitResult;
			if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
				BlockPos bp = bhr.getBlockPos();
				JsonObject la = new JsonObject();
				la.addProperty("type", "block");
				la.addProperty("id", BuiltInRegistries.BLOCK.getKey(level.getBlockState(bp).getBlock()).toString());
				la.add("pos", blockPos(bp));
				o.add("lookingAt", la);
			} else if (hit instanceof EntityHitResult ehr) {
				JsonObject la = new JsonObject();
				la.addProperty("type", "entity");
				la.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(ehr.getEntity().getType()).toString());
				la.addProperty("name", ehr.getEntity().getName().getString());
				o.add("lookingAt", la);
			} else {
				JsonObject la = new JsonObject();
				la.addProperty("type", "none");
				o.add("lookingAt", la);
			}

			// Ray grid across the field of view.
			JsonArray grid = new JsonArray();
			float baseYaw = p.getYRot();
			float basePitch = p.getXRot();
			for (int r = 0; r < rows; r++) {
				float pitchOff = rows == 1 ? 0 : -30f + 60f * r / (rows - 1);
				for (int c = 0; c < cols; c++) {
					float yawOff = cols == 1 ? 0 : -45f + 90f * c / (cols - 1);
					Vec3 dir = dirFromAngles(baseYaw + yawOff, basePitch + pitchOff);
					JsonObject ray = stepRay(level, eye, dir, maxDistance);
					ray.addProperty("col", c);
					ray.addProperty("row", r);
					grid.add(ray);
				}
			}
			o.add("rays", grid);
			return o;
		}));

		// What the player can SEE, as a list of the blocks the field of view actually lands on. This is
		// the reading to make decisions from: every entry is something the player could point at, the
		// line of sight to it is clear by construction, and liquid is reported rather than hidden, so
		// "mine that log" can be answered with "that one is in water".
		router.register("vision.scan", ctx -> ClientMc.call(() -> {
			LocalPlayer p = ClientMc.player();
			Minecraft mc = ClientMc.mc();
			double maxDistance = ctx.optDouble("maxDistance", Vision.DEFAULT_DISTANCE);
			int cols = Math.max(3, Math.min(41, ctx.optInt("rayColumns", 13)));
			int rows = Math.max(2, Math.min(21, ctx.optInt("rayRows", 7)));
			int limit = Math.max(1, Math.min(64, ctx.optInt("maxResults", 24)));
			Predicate<String> filter = idFilter(ctx);
			List<Vision.Seen> seen = Vision.blocks(mc, maxDistance, cols, rows, filter, limit);

			JsonObject o = new JsonObject();
			o.addProperty("yaw", p.getYRot());
			o.addProperty("pitch", p.getXRot());
			o.addProperty("maxDistance", maxDistance);
			o.addProperty("rays", cols * rows);
			JsonArray blocks = new JsonArray();
			for (Vision.Seen s : seen) {
				JsonObject b = new JsonObject();
				b.addProperty("id", s.id());
				b.add("pos", blockPos(s.pos()));
				b.addProperty("distance", round(s.distance()));
				b.addProperty("inReach", s.inReach());
				b.addProperty("wet", s.wet());
				b.addProperty("hardness", round(s.hardness()));
				// See-through blocks (leaves, glass, water, plants) are reported too, and anything seen
				// past one carries the list it was seen through — a trunk under its own canopy is a
				// sighting with through:["minecraft:oak_leaves"], not a blank.
				b.addProperty("transparent", s.transparent());
				if (!s.through().isEmpty()) {
					JsonArray thru = new JsonArray();
					s.through().forEach(thru::add);
					b.add("through", thru);
				}
				blocks.add(b);
			}
			o.add("blocks", blocks);
			o.addProperty("count", blocks.size());
			o.add("entities", visibleEntities(mc, p, ClientMc.level(), maxDistance));
			o.add("lookingAt", crosshair(mc, ClientMc.level()));
			o.addProperty("note", "Only what the eye can see from where it is pointed: turn the camera "
					+ "and scan again. The rays pass through blocks that do not occlude — leaves, glass, "
					+ "water, vines, plants — so a trunk behind foliage appears with "
					+ "through:[\"minecraft:oak_leaves\"], and the foliage itself appears with "
					+ "transparent:true (often the thing worth clearing first). 'wet' means the block sits "
					+ "in liquid, 'inReach' means it is close enough to act on, 'hardness' is how slow it "
					+ "will be: facts to choose between, not a recommendation. Nothing here decides for "
					+ "you, and dig digs whatever position you give it.");
			return o;
		}));
	}

	/** Keep only the block ids the caller asked for, or everything solid when it asked for nothing. */
	private static Predicate<String> idFilter(dev.mcpfabric.bridge.RpcContext ctx) {
		JsonObject p = ctx.params();
		if (!p.has("ids") || !p.get("ids").isJsonArray()) return null;
		JsonArray ids = p.getAsJsonArray("ids");
		List<String> wants = new ArrayList<>();
		for (JsonElement e : ids) {
			if (e != null && e.isJsonPrimitive()) wants.add(e.getAsString());
		}
		if (wants.isEmpty()) return null;
		return id -> {
			for (String w : wants) {
				if (glob(w, id)) return true;
			}
			return false;
		};
	}

	/**
	 * Match a block id against one of the caller's patterns, where {@code *} stands for any run of
	 * characters — so {@code "minecraft:*_log"} covers every wood type and {@code "minecraft:oak_*"}
	 * every oak block. Matching a literal id is the common case and costs nothing.
	 */
	private static boolean glob(String pattern, String id) {
		if (pattern.indexOf('*') < 0) return pattern.equals(id);
		String[] parts = pattern.split("\\*", -1);
		StringBuilder rx = new StringBuilder();
		for (int i = 0; i < parts.length; i++) {
			if (i > 0) rx.append(".*");
			rx.append(java.util.regex.Pattern.quote(parts[i]));
		}
		return id.matches(rx.toString());
	}

	/**
	 * Entities the player can see: inside the view cone, with nothing solid between. Distance alone is
	 * not sight — a zombie behind a wall is not something to swing at.
	 */
	private static JsonArray visibleEntities(Minecraft mc, LocalPlayer p, ClientLevel level, double maxDistance) {
		JsonArray out = new JsonArray();
		Vec3 eye = p.getEyePosition();
		Vec3 look = p.getLookAngle();
		for (Entity e : level.entitiesForRendering()) {
			if (e == p || !e.isAlive()) continue;
			Vec3 to = e.getEyePosition().subtract(eye);
			double d = to.length();
			if (d > maxDistance || d < 0.1) continue;
			if (to.normalize().dot(look) < 0.5) continue; // outside the cone
			BlockHitResult hit = level.clip(new ClipContext(eye, e.getEyePosition(),
					ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			if (hit.getType() == HitResult.Type.BLOCK && hit.getLocation().distanceTo(eye) < d - 0.2) continue;
			JsonObject j = new JsonObject();
			j.addProperty("name", e.getName().getString());
			j.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
			j.addProperty("distance", round(d));
			j.addProperty("inReach", d <= Vision.REACH);
			out.add(j);
			if (out.size() >= 12) break;
		}
		return out;
	}

	/** What the crosshair is on — the same payload describeScene reports. */
	private static JsonObject crosshair(Minecraft mc, ClientLevel level) {
		JsonObject o = new JsonObject();
		HitResult hit = mc.hitResult;
		if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
			BlockPos bp = bhr.getBlockPos();
			o.addProperty("type", "block");
			o.addProperty("id", BuiltInRegistries.BLOCK.getKey(level.getBlockState(bp).getBlock()).toString());
			o.add("pos", blockPos(bp));
			o.addProperty("wet", Vision.wet(level, bp));
		} else if (hit instanceof EntityHitResult ehr) {
			o.addProperty("type", "entity");
			o.addProperty("id", BuiltInRegistries.ENTITY_TYPE.getKey(ehr.getEntity().getType()).toString());
			o.addProperty("name", ehr.getEntity().getName().getString());
		} else {
			o.addProperty("type", "none");
		}
		return o;
	}

	private static double round(double v) {
		return Math.round(v * 100.0) / 100.0;
	}

	/** The main framebuffer. {@code Minecraft.getMainRenderTarget()} moved to {@code gameRenderer.mainRenderTarget()} in 26.2. */
	private static RenderTarget mainRenderTarget(Minecraft mc) {
		//? if <26.2 {
		return mc.getMainRenderTarget();
		//?} else
		/*return mc.gameRenderer.mainRenderTarget();*/
	}

	/** Encode a captured frame as a PNG base64 payload. */
	private static JsonObject imageToJson(NativeImage image) throws IOException {
		Path tmp = Files.createTempFile("mcpfabric_shot", ".png");
		image.writeToFile(tmp);
		byte[] bytes = Files.readAllBytes(tmp);
		Files.deleteIfExists(tmp);
		JsonObject o = new JsonObject();
		o.addProperty("format", "png");
		o.addProperty("width", image.getWidth());
		o.addProperty("height", image.getHeight());
		o.addProperty("bytes", bytes.length);
		o.addProperty("base64", Base64.getEncoder().encodeToString(bytes));
		return o;
	}

	private static JsonObject stepRay(ClientLevel level, Vec3 start, Vec3 dir, double maxDistance) {
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (double t = 0.2; t <= maxDistance; t += 0.25) {
			Vec3 pp = start.add(dir.scale(t));
			m.set((int) Math.floor(pp.x), (int) Math.floor(pp.y), (int) Math.floor(pp.z));
			if (!level.hasChunkAt(m)) break;
			BlockState st = level.getBlockState(m);
			if (!st.isAir()) {
				JsonObject o = new JsonObject();
				o.addProperty("hit", true);
				o.addProperty("id", BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString());
				o.addProperty("distance", t);
				o.add("pos", blockPos(m));
				return o;
			}
		}
		JsonObject o = new JsonObject();
		o.addProperty("hit", false);
		return o;
	}

	private static Vec3 dirFromAngles(float yawDeg, float pitchDeg) {
		double yaw = Math.toRadians(yawDeg);
		double pitch = Math.toRadians(pitchDeg);
		return new Vec3(-Math.cos(pitch) * Math.sin(yaw), -Math.sin(pitch), Math.cos(pitch) * Math.cos(yaw));
	}

	private static JsonObject vec(Vec3 v) {
		JsonObject o = new JsonObject();
		o.addProperty("x", v.x);
		o.addProperty("y", v.y);
		o.addProperty("z", v.z);
		return o;
	}

	private static JsonObject blockPos(BlockPos p) {
		JsonObject o = new JsonObject();
		o.addProperty("x", p.getX());
		o.addProperty("y", p.getY());
		o.addProperty("z", p.getZ());
		return o;
	}
}
