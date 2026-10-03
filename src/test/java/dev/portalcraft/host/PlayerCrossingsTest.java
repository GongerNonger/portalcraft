package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** An infinite-fall loop: a floor portal at z 0 facing up, a ceiling portal at z 200 facing down. */
class PlayerCrossingsTest {
	private static final double HALF = 36.0;
	private static final Proto.HostPortal[] LOOP = {
		new Proto.HostPortal(Proto.PORTAL_EXISTS | Proto.PORTAL_ACTIVE | Proto.PORTAL_LINKED, new Vec3(0, 0, 0), new Vec3(-90, 0, 0)),
		new Proto.HostPortal(Proto.PORTAL_EXISTS | Proto.PORTAL_ACTIVE | Proto.PORTAL_LINKED, new Vec3(0, 0, 200), new Vec3(90, 0, 0)),
	};

	@BeforeEach
	void fresh() {
		PlayerCrossings.reset();
	}

	@Test
	void noCrossingAboveTheFloorPortal() {
		assertNull(PlayerCrossings.step(LOOP, new Vec3(0, 0, 100), new Vec3(0, 0, 60), new Vec3(0, 0, -800), HALF));
	}

	@Test
	void fallsThroughTheFloorAndOutOfTheCeiling() {
		// Centre from 46 to -14: 14 behind the floor portal, so 14 in front of (below) the ceiling one.
		PlayerCrossings.Carried c = PlayerCrossings.step(LOOP, new Vec3(0, 0, 10), new Vec3(0, 0, -50), new Vec3(0, 0, -1200), HALF);
		assertNotNull(c);
		assertEquals(1, c.crossings());
		assertEquals(200 - 14 - HALF, c.feet().z, 1e-6);
		assertEquals(-1200, c.velocity().z, 1e-6); // still falling, as fast
		assertEquals(0.0, c.feet().x, 1e-6);
		assertEquals(0.0, c.feet().y, 1e-6);
	}

	@Test
	void aStepLongerThanTheLoopGoesThroughTwice() {
		// Centre from 46 to -264: through the floor (264 behind), out of the ceiling at -64, which is
		// behind the floor portal again: through once more, out at 200 - 64.
		PlayerCrossings.Carried c = PlayerCrossings.step(LOOP, new Vec3(0, 0, 10), new Vec3(0, 0, -300), new Vec3(0, 0, -3500), HALF);
		assertNotNull(c);
		assertEquals(2, c.crossings());
		assertEquals(200 - 64 - HALF, c.feet().z, 1e-6);
		assertEquals(2, PlayerCrossings.count());
	}

	@Test
	void unfoldingUndoesWhatThePortalHasntMatched() {
		Vec3 straight = new Vec3(5, -3, -300);
		PlayerCrossings.Carried c = PlayerCrossings.step(LOOP, new Vec3(5, -3, 10), straight, new Vec3(0, 0, -3500), HALF);
		assertNotNull(c);
		Vec3 back = PlayerCrossings.unfold(c.feet());
		assertEquals(straight.x, back.x, 1e-6);
		assertEquals(straight.y, back.y, 1e-6);
		assertEquals(straight.z, back.z, 1e-6);
		assertEquals(-3500, PlayerCrossings.unfoldDir(c.velocity()).z, 1e-6);

		PlayerCrossings.matched(1); // Portal made the first one: only the second is unfolded now
		Vec3 once = PlayerCrossings.unfold(c.feet());
		assertEquals(200 - 264 - HALF, once.z, 1e-6);
		PlayerCrossings.matched(2);
		assertEquals(c.feet().z, PlayerCrossings.unfold(c.feet()).z, 1e-6);
	}

	@Test
	void missingTheOvalIsNoCrossing() {
		assertNull(PlayerCrossings.step(LOOP, new Vec3(0, 60, 10), new Vec3(0, 60, -50), new Vec3(0, 0, -1200), HALF));
	}
}
