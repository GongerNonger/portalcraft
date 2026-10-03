package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Each map in its own stretch of the Minecraft world, and positions round-trip through it. */
class MapRegionsTest {
	@AfterEach
	void reset() {
		Units.setOffsetX(0.0);
	}

	@Test
	void portalsMapsHaveTheirOwnRegions() {
		assertEquals(0.0, MapRegions.offsetX("testchmb_a_00"));
		assertEquals(MapRegions.SPACING, MapRegions.offsetX("testchmb_a_01"));
		assertNotEquals(MapRegions.offsetX("testchmb_a_08"), MapRegions.offsetX("testchmb_a_08_advanced"));
		assertEquals(MapRegions.offsetX("ESCAPE_02"), MapRegions.offsetX("escape_02"));
		// A map Portal 1 doesn't have still gets a slot past them, the same every time.
		assertEquals(MapRegions.offsetX("my_mod_map"), MapRegions.offsetX("my_mod_map"));
	}

	@Test
	void positionsCarryTheOffsetVelocitiesDont() {
		Units.setOffsetX(MapRegions.offsetX("testchmb_a_02"));
		Vec3 src = new Vec3(-1234.5, 678.0, 64.0);
		Vec3 mc = Units.toMc(src);
		assertEquals(-1234.5 / 40.0 + 2 * MapRegions.SPACING, mc.x, 1e-9);
		Vec3 back = Units.toSrc(mc);
		assertEquals(src.x, back.x, 1e-9);
		assertEquals(src.y, back.y, 1e-9);
		assertEquals(src.z, back.z, 1e-9);
		assertEquals(1.0, Units.velocityToMc(new Vec3(800, 0, 0)).x, 1e-9); // no offset in a velocity
	}
}
