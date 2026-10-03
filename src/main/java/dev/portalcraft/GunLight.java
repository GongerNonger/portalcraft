package dev.portalcraft;

/** The portal gun light's colours, and its flash when it fires (plain maths, for tests). */
public final class GunLight {
	/** The light's blue and orange (the in-hand model's tint, items/portal_gun.json). */
	public static final int BLUE = 0x2A8CFF, ORANGE = 0xFF8A1E;
	/** How long a shot's flash takes to settle back to the portal's colour, in ticks. */
	public static final int FLASH_TICKS = 8;

	/**
	 * A tint that changes nothing on a spill surface. Those faces are painted at double brightness
	 * (a tint multiplies), so mid grey shows them as they are and anything brighter lights them.
	 */
	public static final int UNLIT = 0x808080;

	private GunLight() {
	}

	/**
	 * The light the gun's glow throws on the surfaces around it (the model's tint 1 near the tube,
	 * tint 2 further out): at rest a faint wash of the selected portal's colour, so the claws and
	 * collar tell you which one is up; `ticks` into a shot, a bright wash that fades with the flash.
	 */
	public static int spill(int rgb, long ticks, boolean near) {
		double left = 1.0 - Math.min(1.0, Math.max(0, ticks) / (double) FLASH_TICKS);
		double fade = left * left;
		int rest = mix(UNLIT, rgb, near ? 0.12 : 0.06);
		int glow = mix(rgb, 0xFFFFFF, 0.5);
		int lit = near ? glow : mix(UNLIT, glow, 0.6); // never darker than unlit in any channel that matters
		return mix(rest, lit, fade);
	}

	/** `a` blended `t` of the way to `b`, per channel. */
	static int mix(int a, int b, double t) {
		int out = 0;
		for (int shift = 16; shift >= 0; shift -= 8) {
			int ca = (a >> shift) & 0xFF, cb = (b >> shift) & 0xFF;
			out |= (int) Math.round(ca + (cb - ca) * t) << shift;
		}
		return out;
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
