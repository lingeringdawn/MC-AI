package dev.mcpfabric.client.tasks;

import com.google.gson.JsonObject;
import dev.mcpfabric.bridge.RpcException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The multi-tick modules an action plan may contain, and nothing else.
 *
 * <p>Populated by the action handlers, which also expose every entry as its own {@code action.<name>}
 * method — so a plan step and a direct call are literally the same code with the same parameters. Each
 * module does one physical step and reports how it went; none of them decides anything, which is what
 * makes them safe to chain in whatever order the caller works out.
 */
public final class TaskModules {
	/** Builds one module from the parameters of a plan step (or of a direct call). */
	public interface Factory {
		ClientTask create(JsonObject params) throws RpcException;
	}

	public record Module(String name, Factory factory, int defaultTimeoutSeconds) {}

	private static final Map<String, Module> MODULES = new LinkedHashMap<>();

	private TaskModules() {}

	public static void register(String name, Factory factory, int defaultTimeoutSeconds) {
		MODULES.put(name, new Module(name, factory, defaultTimeoutSeconds));
	}

	/** The module with this name, or null when the caller named something that does not exist. */
	public static Module get(String name) {
		return name == null ? null : MODULES.get(name);
	}

	public static Map<String, Module> all() {
		return Collections.unmodifiableMap(MODULES);
	}

	/** Comma-separated names, for error messages that tell the caller what it could have said. */
	public static String names() {
		StringJoiner j = new StringJoiner(", ");
		MODULES.keySet().forEach(j::add);
		return j.toString();
	}
}
