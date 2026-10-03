package dev.portalcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GunLightTest {
	@Test
	void flashStartsNearWhiteAndSettlesOnThePortalColour() {
		for (int rgb : new int[] {GunLight.BLUE, GunLight.ORANGE}) {
			int start = GunLight.flash(rgb, 0);
			assertTrue(brightness(start) > brightness(rgb) + 50, "flares");
			int previous = start;
			for (int t = 1; t <= 8; t++) {
				int now = GunLight.flash(rgb, t);
				assertTrue(brightness(now) <= brightness(previous), "only fades");
				previous = now;
			}
			assertEquals(rgb, GunLight.flash(rgb, 8));
		}
	}

	@Test
	void spillRestsNearGreyAndFlaresWithTheShot() {
		for (int rgb : new int[] {GunLight.BLUE, GunLight.ORANGE}) {
			for (boolean near : new boolean[] {true, false}) {
				int rest = GunLight.spill(rgb, GunLight.FLASH_TICKS, near);
				assertTrue(Math.abs(brightness(rest) - brightness(GunLight.UNLIT)) < 60, "a hint, not a repaint");
				assertTrue(brightness(GunLight.spill(rgb, 0, near)) > brightness(rest), "lit by the shot");
				assertEquals(rest, GunLight.spill(rgb, 99, near));
			}
			assertTrue(brightness(GunLight.spill(rgb, 0, true)) > brightness(GunLight.spill(rgb, 0, false)), "brighter near the tube");
		}
	}

	@Test
	void keyframesRunOnceThenRest() {
		assertEquals(1, GunLight.keyframe(0));
		assertEquals(GunLight.FIRE_TICKS, GunLight.keyframe(GunLight.FIRE_TICKS - 1));
		assertEquals(0, GunLight.keyframe(GunLight.FIRE_TICKS));
		assertEquals(0, GunLight.keyframe(-1));
	}

	private static int brightness(int rgb) {
		return ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
	}
}
