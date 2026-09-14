package dev.mcpfabric.client;

import dev.mcpfabric.McpFabric;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

/**
 * Whether the AI may drive, and the one thing it needs from its surroundings in order to.
 *
 * <p>Switchable from inside the game — {@code /mcai on|off} — because the prerequisite the mod cannot
 * create for itself is an active window. Vanilla's attack branch only runs while the mouse is grabbed,
 * and {@code MouseHandler.grabMouse()} does nothing at all unless the window is in the foreground; with
 * neither, {@code handleKeybinds} passes {@code continueAttack(false)} and digging is skipped whole. So
 * turning the AI on brings the window forward and keeps it there while there is work to do, and turning
 * it off hands the keyboard back to whoever is sitting at it.
 *
 * <p>It is deliberately the same switch as {@code enablePlayerControl}: that is the gate every action
 * already checks, so there is one answer to "may the bot act" rather than two that can disagree.
 */
public final class AiControl {
	/** How often to re-ask for focus while the window is not active, in ticks. */
	private static final int FOCUS_RETRY_TICKS = 20;

	private static volatile boolean enabled = true;
	private static int focusCooldown;

	private AiControl() {}

	public static boolean enabled() {
		return enabled && McpFabric.config().enablePlayerControl;
	}

	/** Turn the AI on or off. Keeps the config gate in step, since that is what actions check. */
	public static void setEnabled(boolean on) {
		enabled = on;
		McpFabric.config().enablePlayerControl = on;
	}

	/**
	 * Called every tick with whether the bot has anything on. While it does, the game window is kept in
	 * the foreground — not for show, but because that is what makes the input channel it drives live.
	 */
	public static void tick(Minecraft mc, boolean busy) {
		if (!enabled || !busy) return;
		if (mc.isWindowActive()) {
			focusCooldown = 0;
			return;
		}
		if (focusCooldown > 0) {
			focusCooldown--;
			return;
		}
		focusCooldown = FOCUS_RETRY_TICKS;
		GLFW.glfwFocusWindow(mc.getWindow().getWindow());
	}
}
