package dev.mcpfabric.client.tasks;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mcpfabric.McpFabric;
import dev.mcpfabric.bridge.RpcException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the caller has learned, kept between sessions.
 *
 * <p>Everything else in this mod forgets. The observation stream is a window that slides, the log rolls,
 * the task manager keeps one task — all of it is about right now. So every session started from nothing:
 * the same pond drowned the bot twice, the same trunk turned out to be hidden behind leaves three times,
 * the same dig-and-collect sequence was written out again and again, because there was nowhere for a
 * lesson to live past the conversation that produced it. This is that place.
 *
 * <p>The division is the same one the rest of the architecture uses, applied to knowledge: <b>the caller
 * decides what is worth remembering and writes it down; the mod stores it, hands it back, and never forms
 * an opinion of its own.</b> Nothing here is auto-derived, summarised or scored — a lesson that the mod
 * wrote for itself would be the mod deciding what matters.
 *
 * <p>Entries are keyed so they can be revised rather than accumulating: writing the same key again
 * replaces the text and bumps {@code writes}, which turns "how often have I needed to restate this" into
 * something readable. Kinds are the caller's own vocabulary, with these in common use:
 * <ul>
 *   <li>{@code fact} — something true about this world (where the trees are, that the spawn is a pond);</li>
 *   <li>{@code lesson} — something learned the hard way ("mobs come out of that canyon at night");</li>
 *   <li>{@code procedure} — a way of doing something, as steps ({@code data.steps});</li>
 *   <li>{@code goal} — what the caller is currently trying to achieve.</li>
 * </ul>
 *
 * <p>Procedures are checked for absolute coordinates and the result is reported as a warning. That is not
 * a refusal — pinning a coordinate is sometimes exactly right — but a procedure that can only work at one
 * x/y/z will be wrong the moment the world changes, and saying so at write time is cheaper than
 * discovering it when a habit quietly stops working. The symbolic vocabulary ({@code visible_log},
 * {@code nearest_drop}, {@code looking_at}) exists so procedures can avoid this entirely.
 */
public final class Memory {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	/** Enough for a long campaign of lessons; a runaway caller is refused rather than left unbounded. */
	private static final int MAX_ENTRIES = 200;
	/** Per entry. Long enough for a real procedure, short enough that the file stays readable. */
	private static final int MAX_TEXT = 4000;

	/** One thing the caller wrote down. */
	private record Entry(String key, String kind, String text, JsonObject data, long updatedAtMs, int writes) {}

	private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
	private static boolean loaded;

	private Memory() {}

	// --- the caller's interface --------------------------------------------------------------------

	/**
	 * Write or revise entries. Same key = revise (the text is replaced and {@code writes} goes up), which
	 * is how a lesson gets refined as it is learned better rather than duplicated.
	 */
	public static synchronized JsonObject set(JsonObject params) throws RpcException {
		ensureLoaded();
		JsonArray arr = array(params, "entries");
		if (arr.size() > 50) throw RpcException.badRequest("At most 50 entries per call; got " + arr.size() + ".");
		JsonArray written = new JsonArray();
		JsonArray warnings = new JsonArray();
		for (JsonElement e : arr) {
			if (!e.isJsonObject()) throw RpcException.badRequest("Every entry must be an object.");
			JsonObject o = e.getAsJsonObject();
			String key = str(o, "key");
			if (key == null || key.isBlank()) {
				throw RpcException.badRequest("An entry needs a 'key' — it is how the next write revises this one "
						+ "instead of adding another.");
			}
			String text = str(o, "text");
			if (text == null) text = "";
			if (text.length() > MAX_TEXT) {
				throw RpcException.badRequest("Entry '" + key + "' is " + text.length() + " chars; the cap is "
						+ MAX_TEXT + ". Keep the entry to what is worth re-reading, not a transcript.");
			}
			Entry old = ENTRIES.get(key);
			if (old == null && ENTRIES.size() >= MAX_ENTRIES) {
				throw RpcException.badRequest("Memory holds at most " + MAX_ENTRIES + " entries; '" + key
						+ "' would be number " + (ENTRIES.size() + 1) + ". Delete what is stale first — a memory "
						+ "nobody prunes stops being read.");
			}
			String kind = str(o, "kind");
			if (kind == null || kind.isBlank()) kind = "fact";
			JsonObject data = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data").deepCopy() : null;
			ENTRIES.put(key, new Entry(key, kind, text, data,
					System.currentTimeMillis(), old == null ? 1 : old.writes() + 1));
			JsonObject w = new JsonObject();
			w.addProperty("key", key);
			w.addProperty("kind", kind);
			w.addProperty("revised", old != null);
			written.add(w);
			collectCoordinateWarnings(key, kind, data, warnings);
		}
		save();
		JsonObject o = new JsonObject();
		o.add("written", written);
		o.addProperty("count", ENTRIES.size());
		if (warnings.size() > 0) {
			o.add("warnings", warnings);
			o.addProperty("warningNote", "Warnings only — the write went through. A procedure that pins x/y/z "
					+ "is true here and now; one written against symbolic targets (visible_log, nearest_drop, "
					+ "looking_at) still works when the world changes.");
		}
		o.addProperty("note", "Stored and saved. This is the one thing that survives a restart: read it back "
				+ "at the start of the next session (memory.list) before working anything out again.");
		return o;
	}

	/** One entry in full, or the index when no key is given. */
	public static synchronized JsonObject get(String key) throws RpcException {
		ensureLoaded();
		if (key == null || key.isBlank()) return list(null, null);
		Entry e = ENTRIES.get(key);
		if (e == null) {
			throw RpcException.unavailable("Nothing stored under '" + key + "'. memory.list shows what is.");
		}
		JsonObject o = new JsonObject();
		o.addProperty("key", e.key());
		o.addProperty("kind", e.kind());
		o.addProperty("text", e.text());
		if (e.data() != null) o.add("data", e.data().deepCopy());
		o.addProperty("writes", e.writes());
		o.addProperty("updatedAgoMs", System.currentTimeMillis() - e.updatedAtMs());
		return o;
	}

	/**
	 * The index: keys, kinds and how often each has been revised — the reading to take at the start of a
	 * session, when what matters is "what do I already know" rather than the text of every entry.
	 */
	public static synchronized JsonObject list(String kind, String textFilter) {
		ensureLoaded();
		JsonArray arr = new JsonArray();
		int matched = 0;
		for (Entry e : ENTRIES.values()) {
			if (kind != null && !kind.isBlank() && !kind.equals(e.kind())) continue;
			if (textFilter != null && !textFilter.isBlank()
					&& !(e.key() + " " + e.text()).toLowerCase().contains(textFilter.toLowerCase())) {
				continue;
			}
			matched++;
			JsonObject o = new JsonObject();
			o.addProperty("key", e.key());
			o.addProperty("kind", e.kind());
			o.addProperty("writes", e.writes());
			o.addProperty("updatedAgoMs", System.currentTimeMillis() - e.updatedAtMs());
			o.addProperty("text", shorten(e.text(), 240));
			if (e.data() != null) o.add("data", e.data().deepCopy());
			arr.add(o);
		}
		JsonObject o = new JsonObject();
		o.add("entries", arr);
		o.addProperty("count", matched);
		o.addProperty("total", ENTRIES.size());
		o.addProperty("note", "keys are the address: writing one again revises it. 'writes' counts how often "
				+ "it has been revised — a high number on a 'lesson' usually means it is still not right.");
		return o;
	}

	public static synchronized JsonObject delete(String key) {
		ensureLoaded();
		Entry gone = ENTRIES.remove(key);
		if (gone != null) save();
		JsonObject o = new JsonObject();
		o.addProperty("deleted", gone != null);
		if (gone != null) o.addProperty("was", gone.kind() + ": " + shorten(gone.text(), 120));
		o.addProperty("count", ENTRIES.size());
		return o;
	}

	public static synchronized JsonObject clear() {
		ensureLoaded();
		int had = ENTRIES.size();
		ENTRIES.clear();
		save();
		JsonObject o = new JsonObject();
		o.addProperty("cleared", had);
		o.addProperty("note", "Everything the caller had written down is gone. Stored with it is nothing the "
				+ "mod derived, so there is nothing else to lose.");
		return o;
	}

	// --- storage -----------------------------------------------------------------------------------

	private static void ensureLoaded() {
		if (loaded) return;
		loaded = true;
		Path file = file();
		if (!Files.exists(file)) return;
		try {
			JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
			if (root == null || !root.has("entries") || !root.get("entries").isJsonArray()) return;
			for (JsonElement e : root.getAsJsonArray("entries")) {
				if (!e.isJsonObject()) continue;
				JsonObject o = e.getAsJsonObject();
				String key = str(o, "key");
				if (key == null || key.isBlank()) continue;
				JsonObject data = o.has("data") && o.get("data").isJsonObject()
						? o.getAsJsonObject("data").deepCopy() : null;
				ENTRIES.put(key, new Entry(key, orDefault(str(o, "kind"), "fact"), orDefault(str(o, "text"), ""), data,
						o.has("updatedAtMs") ? o.get("updatedAtMs").getAsLong() : System.currentTimeMillis(),
						o.has("writes") ? o.get("writes").getAsInt() : 1));
			}
			McpFabric.LOGGER.info("[mcpfabric] memory: loaded {} entries", ENTRIES.size());
		} catch (Exception e) {
			// A corrupt file must not stop the mod from starting: say so and carry on empty, so the caller
			// can look at the file rather than at a mod that will not load.
			McpFabric.LOGGER.error("[mcpfabric] memory: could not read {} — starting empty", file, e);
		}
	}

	private static void save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			JsonObject root = new JsonObject();
			root.addProperty("version", 1);
			JsonArray arr = new JsonArray();
			for (Entry e : ENTRIES.values()) {
				JsonObject o = new JsonObject();
				o.addProperty("key", e.key());
				o.addProperty("kind", e.kind());
				o.addProperty("text", e.text());
				if (e.data() != null) o.add("data", e.data().deepCopy());
				o.addProperty("updatedAtMs", e.updatedAtMs());
				o.addProperty("writes", e.writes());
				arr.add(o);
			}
			root.add("entries", arr);
			Files.writeString(file, GSON.toJson(root));
		} catch (IOException e) {
			McpFabric.LOGGER.error("[mcpfabric] memory: could not write {}", file, e);
		}
	}

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("mcpfabric-memory.json");
	}

	// --- helpers -----------------------------------------------------------------------------------

	/**
	 * Point out a procedure that can only work at one place. Reported, never enforced: whether a pinned
	 * coordinate is a mistake depends on what the procedure is for.
	 */
	private static void collectCoordinateWarnings(String key, String kind, JsonObject data, JsonArray warnings) {
		if (!"procedure".equals(kind) || data == null) return;
		JsonElement steps = data.get("steps");
		if (steps == null || !steps.isJsonArray()) return;
		int pinned = 0;
		for (JsonElement s : steps.getAsJsonArray()) {
			if (!s.isJsonObject()) continue;
			JsonObject step = s.getAsJsonObject();
			if (step.has("x") && step.has("y") && step.has("z")) pinned++;
		}
		if (pinned > 0) {
			JsonObject w = new JsonObject();
			w.addProperty("key", key);
			w.addProperty("pinnedSteps", pinned);
			w.addProperty("of", steps.getAsJsonArray().size());
			w.addProperty("message", pinned + " of " + steps.getAsJsonArray().size() + " steps pin x/y/z. "
					+ "Symbolic targets (visible_log, nearest_drop, looking_at) keep the same procedure working "
					+ "when the world moves.");
			warnings.add(w);
		}
	}

	private static JsonArray array(JsonObject o, String key) throws RpcException {
		if (!o.has(key) || !o.get(key).isJsonArray()) {
			throw RpcException.badRequest("Missing '" + key + "': give an array of "
					+ "{key, kind, text, data?} entries.");
		}
		return o.getAsJsonArray(key);
	}

	private static String str(JsonObject o, String key) {
		return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : null;
	}

	private static String orDefault(String v, String fallback) {
		return v == null || v.isBlank() ? fallback : v;
	}

	private static String shorten(String s, int max) {
		return s == null || s.length() <= max ? s : s.substring(0, max) + "…";
	}
}
