package dev.portalcraft;

/** The portal gun light's colours, and its flash when it fires (plain maths, for tests). */
public final class GunLight {
	/** The light's blue and orange (the in-hand model's tint, items/portal_gun.json). */
	public static final int BLUE = 0x2A8CFF, ORANGE = 0xFF8A1E;
	/** How long a shot's flash takes to settle back to the portal's colour, in ticks. */
	public static final int FLASH_TICKS = 8;

	private GunLight() {
	}

	/** The light `ticks` into a flash that settles to `rgb`: three quarters white at first, easing out. */
	public static int flash(int rgb, long ticks) {
		double left = 1.0 - Math.min(1.0, ticks / (double) FLASH_TICKS);
		double white = 0.75 * left * left;
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		r += (int) Math.round((255 - r) * white);
		g += (int) Math.round((255 - g) * white);
		b += (int) Math.round((255 - b) * white);
		return (r << 16) | (g << 8) | b;
	}
}
