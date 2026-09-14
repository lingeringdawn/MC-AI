package dev.mcpfabric.client;

import net.minecraft.util.Mth;

import java.util.Random;

/**
 * Helpers that make the bot's input look like a person's instead of a machine's.
 *
 * <p>Three things give a bot away no matter how well it plays: it turns the camera at a perfectly
 * constant rate, it stands rock-still while fighting, and it reacts the instant something happens.
 * A person flicks the mouse (fast, then a soft settle), shifts their weight and circles while
 * fighting, and needs a moment to notice before acting. These helpers cover those three.
 *
 * <p>Everything here is gated by {@link #enabled()} so the behaviour can be switched off wholesale
 * from the config if a caller wants deterministic, machine-precise motion.
 */
public final class Humanizer {
	private Humanizer() {}

	private static volatile boolean enabled = true;

	public static boolean enabled() {
		return enabled;
	}

	public static void setEnabled(boolean on) {
		enabled = on;
	}

	// --- mouse-look curve ---------------------------------------------------------------------

	/** Fraction of the remaining angle covered in the fast part of a flick. */
	private static final float LOOK_GAIN = 0.45F;
	/** Floor so the last degree does not crawl; a person snaps the final correction. */
	private static final float LOOK_MIN_STEP = 0.8F;
	/** Ceiling so a 180° turn is still a flick rather than a teleport. */
	private static final float LOOK_MAX_STEP = 26.0F;

	/**
	 * How far to turn this tick when {@code errorDeg} degrees are left.
	 *
	 * <p>A person moves the mouse ballistically: the bulk of the distance is covered while the hand
	 * is already accelerating, then everything slows down to land on target. Scaling the step with
	 * the remaining error reproduces that fast-start / soft-settle curve, whereas a fixed step turns
	 * at one constant speed for the whole sweep — the classic robot tell.
	 */
	public static float lookStep(float errorDeg) {
		float e = Math.abs(errorDeg);
		if (!enabled) return Math.min(e, LOOK_MAX_STEP);
		// The lower bound is min(LOOK_MIN_STEP, e): while the error is still larger than the floor we
		// never step further than it, so the final correction lands on target instead of overshooting.
		return Mth.clamp(e * LOOK_GAIN, Math.min(LOOK_MIN_STEP, e), LOOK_MAX_STEP);
	}

	// --- reaction time ------------------------------------------------------------------------

	/**
	 * How many ticks a person needs to notice something and start acting. Around 150-300 ms for a
	 * visual cue, which is 3-6 ticks — short, but never zero. A bot that swings on the same tick a
	 * mob enters range reads as inhuman instantly.
	 */
	public static int reactionTicks(Random rng, int minTicks, int maxTicks) {
		if (!enabled) return 0;
		if (maxTicks <= minTicks) return Math.max(0, minTicks);
		return minTicks + rng.nextInt(maxTicks - minTicks + 1);
	}

	/** A random duration in {@code [min, max]} ticks (inclusive). */
	public static int ticks(Random rng, int min, int max) {
		if (!enabled || max <= min) return Math.max(0, min);
		return min + rng.nextInt(max - min + 1);
	}

	/** True with the given probability, and never when humanization is off. */
	public static boolean chance(Random rng, float probability) {
		return enabled && rng.nextFloat() < probability;
	}

	/** Small symmetric jitter, in degrees — used to keep repeated aims from landing identically. */
	public static float jitter(Random rng, float amplitude) {
		if (!enabled || amplitude <= 0.0F) return 0.0F;
		return (rng.nextFloat() * 2.0F - 1.0F) * amplitude;
	}
}
