package dev.mcpfabric.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;

/**
 * Arbitrates control between the AI and a real player with an explicit handshake, instead of trying
 * to guess which device input was "not the bot's":
 *
 * <ul>
 *   <li><b>Human takes over</b> — they press ESC so the pause menu comes up. The bot never opens that
 *       screen, so a fresh pause menu can only mean "hands off".</li>
 *   <li><b>AI takes over again</b> — the human aims at the sky (pitch at its limit, straight up) and
 *       right-clicks. That is an unambiguous, hard-to-do-by-accident gesture.</li>
 * </ul>
 *
 * The hand-off is latched: once the human has the controls the bot stays off until the hand-back
 * gesture, however long that takes.
 */
public final class HumanControl {
	/** Looking at or past this pitch (i.e. practically straight up) counts as "aimed at the sky". */
	private static final float SKY_PITCH = -88.0F;

	private static boolean humanDriving;
	/**
	 * Last observed pause-menu state. {@code null} until the first tick, so a menu that was already
	 * open when the mod loaded (e.g. the game launched unfocused) does not count as a hand-off.
	 */
	private static Boolean pauseWasOpen;
	private static boolean useWasDown;

	private HumanControl() {}

	public static boolean suspended() {
		return humanDriving;
	}

	/** Hand control back to the bot without the in-game gesture (used by tooling/tests). */
	public static void resumeByAi() {
		humanDriving = false;
	}

	/** Called every client tick, before the bot drives the player. */
	public static void tick(Minecraft mc) {
		boolean pauseOpen = mc.screen instanceof PauseScreen;
		if (pauseWasOpen == null) {
			pauseWasOpen = pauseOpen; // initial state is context, not an input
		} else {
			if (pauseOpen && !pauseWasOpen) humanDriving = true; // a fresh ESC -> human takes over
			pauseWasOpen = pauseOpen;
		}

		// Sky + right-click gives control back. Only the press edge counts, so holding the button is
		// harmless and a click made while looking elsewhere does not qualify.
		boolean useDown = mc.options.keyUse.isDown();
		boolean rightClickPressed = useDown && !useWasDown;
		useWasDown = useDown;

		LocalPlayer p = mc.player;
		if (humanDriving && rightClickPressed && p != null && p.getXRot() <= SKY_PITCH) {
			humanDriving = false;
		}
	}
}
