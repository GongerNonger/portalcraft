package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Portal air's numbers and limits (PortalAir.limit), as docs/PORTAL_MECHANICS.md states them. */
class PortalAirTest {
	@Test
	void constantsArePortals() {
		// sv_gravity 600 units/s^2 at 40 units a block and 20 ticks a second.
		assertEquals(600.0, PortalAir.GRAVITY * Units.PER_BLOCK * 400.0, 1e-9);
		// sv_maxvelocity 3500 units/s.
		assertEquals(3500.0, PortalAir.MAX_SPEED * Units.PER_BLOCK * 20.0, 1e-9);
	}

	@Test
	void everyDescentIsPortalsButNotTheWayUp() {
		assertTrue(PortalAir.falling(false, -0.01)); // just past the top of a jump, or off a ledge
		assertTrue(!PortalAir.falling(false, 0.42)); // jumping: Minecraft's own rise
		assertTrue(!PortalAir.falling(true, -0.08)); // standing
		// From rest under Portal's gravity, 163 units down is 442 units/s, as in Portal (Minecraft's
		// own fall had Steve at 581 there).
		double v = 0.0, fallen = 0.0;
		while (fallen * Units.PER_BLOCK < 163.0) {
			v += PortalAir.GRAVITY;
			fallen += v;
		}
		assertEquals(442.0, v * Units.PER_BLOCK * 20.0, 30.0);
	}

	@Test
	void airControlSteersButDoesntAddSpeed() {
		// A fling at 1 block/tick, air control pushed it to 1.1 sideways: back to 1, same direction.
		Vec3 v = PortalAir.limit(new Vec3(1.1, -0.5, 0.0), 1.0);
		assertEquals(1.0, Math.hypot(v.x, v.z), 1e-9);
		assertEquals(-0.5, v.y, 1e-9);
		// Steering (turning) at the same speed is kept.
		Vec3 turned = PortalAir.limit(new Vec3(0.6, 0.0, 0.8), 1.0);
		assertEquals(0.6, turned.x, 1e-9);
		assertEquals(0.8, turned.z, 1e-9);
	}

	@Test
	void aLittleControlFromAStandstill() {
		Vec3 v = PortalAir.limit(new Vec3(0.2, -1.0, 0.0), 0.0);
		assertEquals(0.2, v.x, 1e-9); // under the 0.3 floor: kept
		Vec3 capped = PortalAir.limit(new Vec3(0.5, -1.0, 0.0), 0.0);
		assertEquals(0.3, capped.x, 1e-9);
	}

	@Test
	void neverFasterThanPortalAllows() {
		Vec3 v = PortalAir.limit(new Vec3(0.0, -10.0, 0.0), 0.0);
		assertEquals(PortalAir.MAX_SPEED, v.length(), 1e-9);
		assertTrue(v.y < 0);
	}
}
