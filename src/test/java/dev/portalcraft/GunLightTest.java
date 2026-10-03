package dev.portalcraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GunLightTest {
	@Test
	void flashStartsNearWhiteAndSettlesOnThePortalColour() {
		for (int rgb : new int[] {GunLight.BLUE, GunLight.ORANGE}) {
			int start = GunLight.flash(rgb, 0);
			assertTrue(brightness(start) > brightness(rgb) + 200, "flares");
			int previous = start;
			for (int t = 1; t <= 8; t++) {
				int now = GunLight.flash(rgb, t);
				assertTrue(brightness(now) <= brightness(previous), "only fades");
				previous = now;
			}
			assertEquals(rgb, GunLight.flash(rgb, 8));
		}
	}

	private static int brightness(int rgb) {
		return ((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF);
	}
}
