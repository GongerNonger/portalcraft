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
		PlayerCrossings.Carried c = PlayerCrossings.step(LOOP, new Vec3(0, 0, 10), new Vec3(0, 0, -50), new Vec3(0, 0, -800), HALF);
		assertNotNull(c);
		assertEquals(1, c.crossings());
		assertEquals(200 - 14 - HALF, c.feet().z, 1e-6);
		assertEquals(-800, c.velocity().z, 1e-6); // still falling, as fast
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
		assertEquals(-1000, PlayerCrossings.unfoldDir(c.velocity()).z, 1e-6); // Portal's top exit speed

		PlayerCrossings.matched(1); // Portal made the first one: only the second is unfolded now
		Vec3 once = PlayerCrossings.unfold(c.feet());
		assertEquals(200 - 264 - HALF, once.z, 1e-6);
		PlayerCrossings.matched(2);
		assertEquals(c.feet().z, PlayerCrossings.unfold(c.feet()).z, 1e-6);
	}

	@Test
	void aFloorPortalThrowsYouClear() {
		// Two floor portals: stepping slowly into one comes out of the other at Portal's 300 units/s.
		Proto.HostPortal[] floors = {LOOP[0],
			new Proto.HostPortal(Proto.PORTAL_EXISTS | Proto.PORTAL_ACTIVE | Proto.PORTAL_LINKED, new Vec3(400, 0, 0), new Vec3(-90, 0, 0))};
		PlayerCrossings.Carried slow = PlayerCrossings.step(floors, new Vec3(0, 0, -30), new Vec3(0, 0, -40), new Vec3(0, 0, -100), HALF);
		assertNotNull(slow);
		assertEquals(300, slow.velocity().z, 1e-6);
		assertEquals(400, slow.feet().x, 1e-6);
		// ... and a fall into one carries its speed out of the other, upward.
		PlayerCrossings.Carried fast = PlayerCrossings.step(floors, new Vec3(0, 0, -30), new Vec3(0, 0, -50), new Vec3(0, 0, -700), HALF);
		assertNotNull(fast);
		assertEquals(700, fast.velocity().z, 1e-6);
	}

	@Test
	void missingTheOvalIsNoCrossing() {
		assertNull(PlayerCrossings.step(LOOP, new Vec3(0, 60, 10), new Vec3(0, 60, -50), new Vec3(0, 0, -1200), HALF));
	}

	@Test
	void comesOutFittedInsideAWallPortal() {
		// In at the end of the floor portal's long axis, 30 out: carried as is, that is feet 12 units
		// under the wall portal's bottom edge, inside the floor it stands on. He comes out just over it.
		Proto.HostPortal wall = new Proto.HostPortal(Proto.PORTAL_EXISTS | Proto.PORTAL_ACTIVE | Proto.PORTAL_LINKED, new Vec3(500, 0, 54), new Vec3(0, 0, 0));
		Proto.HostPortal[] pair = {LOOP[0], wall};
		Vec3 up = Units.angleVectors(LOOP[0].angles())[2]; // the floor portal's long axis
		Vec3 in = up.scale(-30.0);
		Vec3 before = in.add(0, 0, 10 - HALF), after = in.add(0, 0, -10 - HALF);
		PlayerCrossings.Carried c = PlayerCrossings.step(pair, before, after, new Vec3(0, 0, -400), HALF, 16.6);
		assertNotNull(c);
		assertEquals(1.5, c.feet().z, 1e-6); // the floor (and the wall portal's bottom edge) is at z 0
		assertEquals(13.5, c.fit().z, 1e-6);
		assertEquals(510.0, c.feet().x, 1e-6); // 10 out of the wall, as far as he was under the floor
		assertEquals(400, c.velocity().x, 1e-6);
		// ... and Portal, playing the unfolded steps, sees him the same 13.5 units nearer the floor
		// portal's middle: carried through plainly, that is where Steve now is.
		Vec3 back = PlayerCrossings.unfold(c.feet());
		assertEquals(13.5, Math.abs(back.subtract(after).dot(up)), 1e-6);
		assertEquals(13.5, back.subtract(after).length(), 1e-6);
	}
}
