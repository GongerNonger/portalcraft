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
	void missingTheOpeningIsNoCrossing() {
		assertNull(PlayerCrossings.step(LOOP, new Vec3(0, 60, 10), new Vec3(0, 60, -50), new Vec3(0, 0, -1200), HALF));
	}

	@Test
	void theOpeningIsPortalsRectangleNotTheOval() {
		// In by a corner: outside the oval drawn in the opening, inside the 64 x 108 rectangle that
		// Portal teleports through (and that Minecraft's hole is).
		Vec3[] axes = Units.angleVectors(LOOP[0].angles());
		Vec3 corner = axes[1].scale(30.0).add(axes[2].scale(50.0));
		assertNotNull(PlayerCrossings.step(LOOP, corner.add(0, 0, 10 - HALF), corner.add(0, 0, -10 - HALF), new Vec3(0, 0, -400), HALF));
		PlayerCrossings.reset();
		Vec3 past = axes[1].scale(33.0);
		assertNull(PlayerCrossings.step(LOOP, past.add(0, 0, 10 - HALF), past.add(0, 0, -10 - HALF), new Vec3(0, 0, -400), HALF));
	}

	/** Steve sneaking (a hull 60 high) over a Portal player that is ducked: its centre is 18 above its feet. */
	private static final double SNEAK_HALF = 30.0, DUCKED = 18.0;

	@Test
	void goesThroughWherePortalsCentreDoesNotWhereHisOwnDoes() {
		// Feet from -15 to -17: the middle of his hull is still 13 above the floor portal, Portal's
		// centre for its player 1 above. Nobody is through.
		assertNull(PlayerCrossings.step(LOOP, new Vec3(0, 0, -15), new Vec3(0, 0, -17), new Vec3(0, 0, -40), SNEAK_HALF, 0.0, DUCKED));
		// Feet to -25: Portal's centre is 7 behind the plane, and Portal will teleport its player.
		// So Steve is through, by that point: it comes out 7 below the ceiling portal.
		PlayerCrossings.Carried c = PlayerCrossings.step(LOOP, new Vec3(0, 0, -17), new Vec3(0, 0, -25), new Vec3(0, 0, -160), SNEAK_HALF, 0.0, DUCKED);
		assertNotNull(c);
		assertEquals(200 - 7 - DUCKED, c.feet().z, 1e-6);
		assertEquals(DUCKED, PlayerCrossings.centreOf(1), 1e-6); // what the host is told it was carried by
		assertEquals(-25, PlayerCrossings.unfold(c.feet()).z, 1e-6);
		// Standing, the two points are the same one and nothing has changed: through at feet -36.
		PlayerCrossings.reset();
		assertNull(PlayerCrossings.step(LOOP, new Vec3(0, 0, -17), new Vec3(0, 0, -25), new Vec3(0, 0, -160), HALF, 0.0, HALF));
	}

	@Test
	void comesOutWithPortalsCentreInFrontOfThePortal() {
		// Two floor portals, sneaking, stepping in slowly: Portal's centre a unit behind the first
		// comes out a unit in front of the second, and is put two out (FIT_FRONT). Portal's own test
		// for taking its player back in is on that point, so it must be the one in front: by the
		// middle of Steve's hull it was left 10 units under the floor, and Portal took him back.
		Proto.HostPortal[] floors = {LOOP[0],
			new Proto.HostPortal(Proto.PORTAL_EXISTS | Proto.PORTAL_ACTIVE | Proto.PORTAL_LINKED, new Vec3(400, 0, 0), new Vec3(-90, 0, 0))};
		PlayerCrossings.Carried c = PlayerCrossings.step(floors, new Vec3(0, 0, -17), new Vec3(0, 0, -19), new Vec3(0, 0, -40), SNEAK_HALF, 16.6, DUCKED);
		assertNotNull(c);
		assertEquals(2.0, c.feet().z + DUCKED, 1e-6);
		assertEquals(400, c.feet().x, 1e-6);
		assertEquals(300, c.velocity().z, 1e-6);
	}

	@Test
	void theBoxFittedIsTheTallerOfTheTwoHulls() {
		// Gliding (a hull 24 high) under a standing Portal player: in at the end of the floor
		// portal's long axis, out of a wall portal that stands on the floor. It is Portal's player,
		// 72 high on the same feet, that has to clear the floor and the opening's top.
		Proto.HostPortal wall = new Proto.HostPortal(Proto.PORTAL_EXISTS | Proto.PORTAL_ACTIVE | Proto.PORTAL_LINKED, new Vec3(500, 0, 54), new Vec3(0, 0, 0));
		Proto.HostPortal[] pair = {LOOP[0], wall};
		Vec3 up = Units.angleVectors(LOOP[0].angles())[2];
		for (double along : new double[] {-30.0, 30.0}) {
			PlayerCrossings.reset();
			Vec3 in = up.scale(along);
			PlayerCrossings.Carried c = PlayerCrossings.step(pair, in.add(0, 0, 10 - HALF), in.add(0, 0, -10 - HALF), new Vec3(0, 0, -400), 12.0, 16.6, HALF);
			assertNotNull(c);
			assertEquals(true, c.feet().z >= 1.5 - 1e-6, "feet over the bottom edge");
			assertEquals(true, c.feet().z + 2 * HALF <= 108 - 1.5 + 1e-6, "Portal's head under the top edge");
		}
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
