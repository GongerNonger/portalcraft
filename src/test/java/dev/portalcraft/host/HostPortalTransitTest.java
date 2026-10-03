package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Things through Portal's portals keep their momentum the way Portal carries its own. */
class HostPortalTransitTest {
	private static final int LINKED = Proto.PORTAL_EXISTS | Proto.PORTAL_ACTIVE | Proto.PORTAL_LINKED;

	// Source angles (pitch, yaw, roll): forward = surface normal.
	private static Proto.HostPortal wall(Vec3 at, double yaw) {
		return new Proto.HostPortal(LINKED, at, new Vec3(0, yaw, 0));
	}

	private static Proto.HostPortal floor(Vec3 at) {
		return new Proto.HostPortal(LINKED, at, new Vec3(-90, 0, 0)); // pitch -90: forward is up (+z)
	}

	private static void near(Vec3 expected, Vec3 actual) {
		assertEquals(expected.x, actual.x, 1e-6, "x");
		assertEquals(expected.y, actual.y, 1e-6, "y");
		assertEquals(expected.z, actual.z, 1e-6, "z");
	}

	@Test
	void facingWallsKeepTheDirectionOfTravel() {
		// A faces +x (on a wall at x=0), B faces -x (on a wall at x=500): walking into A (-x)...
		Proto.HostPortal a = wall(new Vec3(0, 0, 64), 0), b = wall(new Vec3(500, 0, 64), 180);
		// ...comes out of B going -x, away from B's wall.
		near(new Vec3(-100, 0, 0), HostPortalTransit.carryDirection(a, b, new Vec3(-100, 0, 0)));
		// A point 10 units behind A (inside its hole) is 10 units in front of B.
		near(new Vec3(490, 0, 64), HostPortalTransit.carryPoint(a, b, new Vec3(-10, 0, 64)));
	}

	@Test
	void fallingIntoAFloorPortalFlingsOutOfAWall() {
		// The fling: fall into a floor portal, come out of a wall portal facing +y at speed.
		Proto.HostPortal f = floor(new Vec3(0, 0, 0)), w = wall(new Vec3(0, 1000, 128), 90);
		Vec3 out = HostPortalTransit.carryDirection(f, w, new Vec3(0, 0, -700)); // falling at 700 u/s
		near(new Vec3(0, 700, 0), out);
	}

	@Test
	void sidewaysMotionIsNotMirrored() {
		// Portals at right angles: the carry must be a rotation (determinant +1), never a mirror,
		// or sideways motion would come out the wrong way round.
		Proto.HostPortal a = wall(new Vec3(0, 0, 64), 0), b = wall(new Vec3(500, 0, 64), 90);
		Vec3 x = HostPortalTransit.carryDirection(a, b, new Vec3(1, 0, 0));
		Vec3 y = HostPortalTransit.carryDirection(a, b, new Vec3(0, 1, 0));
		Vec3 z = HostPortalTransit.carryDirection(a, b, new Vec3(0, 0, 1));
		assertEquals(1.0, x.cross(y).dot(z), 1e-6);
	}
}
