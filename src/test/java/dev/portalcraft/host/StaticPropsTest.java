package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Portal's own maps and models, if Portal is installed (skipped otherwise). */
class StaticPropsTest {
	private static final Path MAPS = Path.of("D:/SteamLibrary/steamapps/common/Portal/portal/maps");
	private static final String CLEANSER = "models/props/portal_cleanser_1.mdl";

	private static StaticProps.Result load(String map) throws Exception {
		Path file = MAPS.resolve(map + ".bsp");
		assumeTrue(Files.exists(file), "Portal not installed");
		ByteBuffer bsp = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		StaticProps.Result r = StaticProps.load(bsp, GameFiles.forMap(file));
		System.out.println(map + ": " + r.props().size() + " props, " + r.solid() + " solid, " + r.viaPhy() + " via .phy, "
			+ r.viaBox() + " via box, " + r.failed() + " failed, " + r.brushes().size() + " brushes");
		return r;
	}

	@Test
	void vpkFindsModels() throws Exception {
		Path dir = MAPS.resolveSibling("portal_pak_dir.vpk");
		assumeTrue(Files.exists(dir), "Portal not installed");
		Vpk pak = Vpk.open(dir);
		assertTrue(pak.size() > 1000);
		byte[] mdl = pak.read(CLEANSER.toUpperCase().replace('/', '\\'));
		assertTrue(mdl != null && mdl.length > 1000);
		assertEquals(0x54534449, ByteBuffer.wrap(mdl).order(ByteOrder.LITTLE_ENDIAN).getInt(0), "IDST");
		assertEquals(null, pak.read("models/props/no_such_model.mdl"));
	}

	@Test
	void firstChamberPropsCollide() throws Exception {
		StaticProps.Result r = load("testchmb_a_00");
		assertEquals(53, r.props().size());
		assertTrue(r.solid() > 40);
		assertTrue(r.viaPhy() >= r.solid() - 2, "most props should use their .phy");
		assertEquals(0, r.failed());

		// The emancipation grid emitter at (-596, -202, 641), yaw 180. Its .phy is one tapered hull:
		// flat at the back (local y = -10, 23.6 wide), a rounded nose out to y = +10.6, 131 tall.
		// Yaw 180 maps local (x, y) to world (-x, -y).
		StaticProps.Prop cleanser = r.props().stream()
			.filter(p -> p.model().equals(CLEANSER) && p.origin().distanceTo(new Vec3(-596, -202, 641)) < 1).findFirst().orElseThrow();
		assertEquals(180.0, cleanser.angles().y, 0.01);
		Vec3 o = cleanser.origin();
		assertTrue(solid(r.brushes(), o), "nothing at the cleanser's origin");
		assertTrue(solid(r.brushes(), o.add(-11, 9.5, 0)), "back corner");
		assertTrue(solid(r.brushes(), o.add(11, 9.5, 0)), "back corner");
		assertTrue(solid(r.brushes(), o.add(0, -10.3, 0)), "nose");
		assertFalse(solid(r.brushes(), o.add(0, -11, 0)), "past the nose");
		assertFalse(solid(r.brushes(), o.add(0, 10.6, 0)), "behind the back");
		assertFalse(solid(r.brushes(), o.add(-8, -9, 0)), "beside the nose");
		assertTrue(solid(r.brushes(), o.add(0, 9.5, 60)), "top of the back");
		assertFalse(solid(r.brushes(), o.add(0, -9.5, 60)), "the top tapers toward the nose");
		assertFalse(solid(r.brushes(), o.add(0, 0, 67)), "above the top");
		boolean boxed = r.brushes().stream().anyMatch(b -> b.mcBox().contains(Units.toMc(o)));
		assertTrue(boxed, "the cleanser's Minecraft box isn't at its origin");
	}

	@Test
	void otherMapsCollide() throws Exception {
		for (String map : List.of("testchmb_a_08", "escape_00", "escape_02")) {
			StaticProps.Result r = load(map);
			assertTrue(r.solid() > 100, map);
			assertTrue(r.viaPhy() > r.solid() * 9 / 10, map);
			assertEquals(0, r.failed(), map);
			for (BspMap.Brush b : r.brushes()) {
				assertTrue(b.mcBox().getXsize() < 100 && b.mcBox().getYsize() < 100 && b.mcBox().getZsize() < 100, map + " has a runaway brush");
			}
		}
	}

	/**
	 * vbsp recorded which BSP leaves each prop's (render) hull touches. A point inside a collision
	 * hull we placed must be in one of them, unless it's buried in solid world, which vbsp skips.
	 * That checks pitch and roll too, which plenty of pipes and tubes use.
	 */
	@Test
	void hullsLandInTheirPropsLeaves() throws Exception {
		for (String map : List.of("testchmb_a_08", "escape_01")) {
			Path file = MAPS.resolve(map + ".bsp");
			assumeTrue(Files.exists(file), "Portal not installed");
			ByteBuffer bsp = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
			GameFiles files = GameFiles.forMap(file);
			int inLeaf = 0, buried = 0, rotated = 0;
			for (StaticProps.Prop prop : StaticProps.parse(bsp)) {
				byte[] phy = files.read(prop.model().replace(".mdl", ".phy"));
				if (prop.solid() == 0 || phy == null) {
					continue;
				}
				if (Math.abs(prop.angles().x) > 1 || Math.abs(prop.angles().z) > 1) {
					rotated++;
				}
				for (Phy.Hull hull : Phy.firstSolid(phy)) {
					BspMap.Brush brush = StaticProps.brush(hull, prop.origin(), Units.angleVectors(prop.angles()), StaticProps.COLLISION_MARGIN);
					Vec3 c = brush == null ? null : Units.toSrc(brush.mcBox().getCenter());
					if (c == null || !brush.containsSrc(c.x, c.y, c.z)) {
						continue;
					}
					int leaf = leafAt(bsp, c);
					if (Arrays.stream(prop.leaves()).anyMatch(l -> l == leaf)) {
						inLeaf++;
					} else {
						// dleaf_t (v20): contents first, 32 bytes each.
						assertTrue((bsp.getInt(bsp.getInt(8 + 10 * 16) + leaf * 32) & 1) != 0, map + ": " + prop.model() + " at " + prop.origin() + " " + prop.angles() + " is outside its leaves");
						buried++;
					}
				}
			}
			System.out.println(map + ": " + inLeaf + " hull centres in their prop's leaves, " + buried + " in solid world; " + rotated + " props pitched or rolled");
			assertTrue(inLeaf > 100 && rotated > 10, map);
		}
	}

	/** Walks the world's BSP tree (nodes: planenum, children[2]; planes: normal, dist) to a leaf. */
	private static int leafAt(ByteBuffer bsp, Vec3 p) {
		int nodes = bsp.getInt(8 + 5 * 16), planes = bsp.getInt(8 + 16);
		int n = 0;
		while (n >= 0) {
			int o = nodes + n * 32;
			int plane = planes + bsp.getInt(o) * 20;
			double side = bsp.getFloat(plane) * p.x + bsp.getFloat(plane + 4) * p.y + bsp.getFloat(plane + 8) * p.z - bsp.getFloat(plane + 12);
			n = bsp.getInt(o + (side >= 0 ? 4 : 8));
		}
		return -1 - n;
	}

	@Test
	void bspMapIncludesProps() throws Exception {
		Path file = MAPS.resolve("testchmb_a_00.bsp");
		assumeTrue(Files.exists(file), "Portal not installed");
		long t0 = System.nanoTime();
		BspMap.load(file, "cold");
		System.out.println("BspMap.load, first (opens the VPKs): " + (System.nanoTime() - t0) / 1_000_000 + " ms");
		t0 = System.nanoTime();
		BspMap map = BspMap.load(file, "testchmb_a_00");
		System.out.println("BspMap.load: " + map.brushes.size() + " brushes in " + (System.nanoTime() - t0) / 1_000_000 + " ms");
		StaticProps.Prop cleanser = load("testchmb_a_00").props().stream().filter(p -> p.model().equals(CLEANSER)).findFirst().orElseThrow();
		assertTrue(solid(map.brushes, cleanser.origin()));
	}

	private static boolean solid(List<BspMap.Brush> brushes, Vec3 src) {
		return brushes.stream().anyMatch(b -> b.containsSrc(src.x, src.y, src.z));
	}
}
