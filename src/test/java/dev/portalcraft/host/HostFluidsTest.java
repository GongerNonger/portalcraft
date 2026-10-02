package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Fluids against the host's geometry (the map half is skipped without Portal). */
class HostFluidsTest {
	private static final Path MAP = Path.of("D:/SteamLibrary/steamapps/common/Portal/portal/maps/testchmb_a_00.bsp");
	/** Where Portal spawned the player in this map (HostAimTest): the floor is the top of cell y = 3. */
	private static final Vec3 SPAWN_SRC = new Vec3(-607.8, -346.7, 162.0);
	/** Water's reach on a flat floor: a source spreads 7 cells. */
	private static final int WATER_REACH = 7;
	private static final int VAULT_FLOOR = 5 * 4;

	@AfterEach
	void clear() {
		HostCollision.clear();
	}

	@Test
	void noMapNothingBlocks() {
		HostCollision.clear();
		for (Direction d : Direction.values()) {
			assertFalse(HostFluids.blocks(BlockPos.ZERO, BlockPos.ZERO.relative(d), d));
		}
		BlockPos clicked = new BlockPos(0, 3, 0);
		assertEquals(clicked.above(), HostFluids.bucketTarget(clicked, Direction.UP, new Vec3(0.5, 3.05, 0.5), clicked.above()));
	}

	@Test
	void sliverFloorHoldsButDoesNotWall() {
		// Portal's floor at y = 4.05: cell 4 holds a 0.05 sliver at its bottom.
		long[] floor = masks(Shapes.box(0, 0, 0, 1, 0.05, 1));
		long[] open = masks(null);
		long[] full = masks(Shapes.block());
		assertTrue(cross(floor, full, Direction.DOWN), "water in the sliver cell falls through the floor");
		assertTrue(cross(floor, open, Direction.DOWN), "the sliver alone must floor its cell");
		assertFalse(cross(open, floor, Direction.DOWN), "the cell above must drain into the sliver cell");
		for (Direction d : Direction.Plane.HORIZONTAL) {
			assertFalse(cross(floor, floor, d), "the sliver blocks spreading " + d);
			assertFalse(cross(floor, open, d), "the sliver blocks spreading " + d);
		}
	}

	@Test
	void floorAboveTheCentreKeepsFluidOut() {
		// A surface at y = 4.6: cell 4's centre is inside it, so water rests in cell 5.
		long[] high = masks(Shapes.box(0, 0, 0, 1, 0.6, 1));
		assertTrue(cross(masks(null), high, Direction.DOWN));
		assertTrue(cross(masks(null), high, Direction.EAST));
	}

	@Test
	void thinWallsAnywhereInTheCell() {
		long[] open = masks(null);
		// A 16-unit (0.4-block) wall in the middle of a cell touches neither face: vanilla's face
		// test would let water through it.
		long[] middle = masks(Shapes.box(0.3, 0, 0, 0.7, 1, 1));
		assertTrue(cross(open, middle, Direction.EAST));
		assertTrue(cross(middle, open, Direction.EAST));
		// In the far half of the cell: water may enter the cell, but not leave it through the wall.
		long[] far = masks(Shapes.box(0.6, 0, 0, 0.9, 1, 1));
		assertFalse(cross(open, far, Direction.EAST));
		assertTrue(cross(far, open, Direction.EAST));
		assertFalse(cross(far, open, Direction.WEST));
		// One unit of glass (the relaxation vault's panes) is still a wall.
		long[] glass = masks(Shapes.box(0.75, 0, 0, 0.775, 1, 1));
		assertTrue(cross(glass, open, Direction.EAST));
		assertFalse(cross(glass, open, Direction.NORTH));
		// Walls along the other axes.
		long[] zWall = masks(Shapes.box(0, 0, 0.1, 1, 1, 0.3));
		assertTrue(cross(open, zWall, Direction.SOUTH));
		assertFalse(cross(zWall, open, Direction.SOUTH));
		assertTrue(cross(zWall, open, Direction.NORTH));
	}

	@Test
	void wallsTurningACornerInsideACell() {
		// The vault's corner on testchmb_a_00: the cell north of the corner holds the east pane
		// (water inside, west of it); the corner cell holds the end of the east pane and the south
		// pane, and its centre is outside the vault, south of the south pane. Projected along z, the
		// strip east of the east pane looks open, but water inside can't get there.
		long[] inside = masks(Shapes.box(0.75, 0, 0, 0.775, 1, 1));
		long[] corner = masks(Shapes.or(Shapes.box(0, 0, 0.325, 0.8, 1, 0.35), Shapes.box(0.75, 0, 0, 0.775, 1, 0.3)));
		assertTrue(cross(inside, corner, Direction.SOUTH));
		assertTrue(cross(corner, inside, Direction.NORTH));
	}

	@Test
	void gapsAndLedges() {
		long[] open = masks(null);
		// A doorway: half the face open.
		assertFalse(cross(open, masks(Shapes.or(Shapes.box(0, 0, 0, 1, 1, 0.25), Shapes.box(0, 0, 0.75, 1, 1, 1))), Direction.EAST));
		// A ledge below the centre is stepped over; one over the centre fills the cell.
		assertFalse(cross(open, masks(Shapes.box(0, 0, 0, 1, 0.45, 1)), Direction.EAST));
		assertTrue(cross(open, masks(Shapes.box(0, 0, 0, 1, 0.75, 1)), Direction.EAST));
		// A wall with a 2.4-unit seam holds; with a 10-unit gap it doesn't.
		VoxelShape seam = Shapes.or(Shapes.box(0.1, 0, 0, 0.3, 1, 0.47), Shapes.box(0.1, 0, 0.53, 0.3, 1, 1));
		assertTrue(cross(open, masks(seam), Direction.EAST));
		VoxelShape gap = Shapes.or(Shapes.box(0.1, 0, 0, 0.3, 1, 0.375), Shapes.box(0.1, 0, 0.625, 0.3, 1, 1));
		assertFalse(cross(open, masks(gap), Direction.EAST));
	}

	@Test
	void slopeSplitAcrossTwoCells() {
		// A staircase: the upper cell has a step at x < 0.5, the lower cell's top x >= 0.5. Neither
		// alone floors the upper cell, together they do.
		long[] upper = masks(Shapes.box(0, 0, 0, 0.5, 0.2, 1));
		long[] lower = masks(Shapes.box(0.5, 0.7, 0, 1, 1, 1));
		assertTrue(cross(upper, lower, Direction.DOWN));
		assertFalse(cross(upper, masks(null), Direction.DOWN));
		assertFalse(cross(masks(null), lower, Direction.DOWN));
	}

	@Test
	void portalHolesAreSolidForFluids() {
		// A floor portal's hole cuts the floor out of the cell; the hole box itself holds water.
		AABB hole = new AABB(9.2, 2.2, -5.0, 11.4, 4.05, -2.3);
		long[] cut = HostFluids.faceMasks(null, List.of(hole), 10, 4, -4);
		assertTrue(cross(cut, masks(null), Direction.DOWN));
		assertFalse(cross(cut, masks(null), Direction.EAST));
		long[] elsewhere = HostFluids.faceMasks(null, List.of(hole), 20, 4, -4);
		assertFalse(cross(elsewhere, masks(null), Direction.DOWN));
	}

	@Test
	void waterStaysInTheSpawnRoom() throws Exception {
		assumeTrue(Files.exists(MAP), "Portal not installed");
		HostCollision.setMap(BspMap.load(MAP, "testchmb_a_00"));
		BlockPos spawn = BlockPos.containing(Units.toMc(SPAWN_SRC));
		assertEquals(4, spawn.getY());
		assertTrue(HostFluids.floored(spawn), "water at the spawn falls through the floor");
		assertFalse(HostFluids.floored(spawn.above()), "water above the spawn can't fall to the floor");

		// The walls HostAimTest measures: the vault's +X glass stands 0.75 into cell x = -12 (water
		// may enter that cell, not leave it); 0.65 of cell z = 5 is behind the -Z wall (water stays in z = 6).
		BlockPos east = new BlockPos(-12, 4, 8);
		assertFalse(HostFluids.blocks(east.west(), east, Direction.EAST));
		assertTrue(HostFluids.blocks(east, east.east(), Direction.EAST));
		BlockPos north = new BlockPos(-16, 4, 6);
		assertTrue(HostFluids.blocks(north, north.north(), Direction.NORTH));

		// A bucket emptied on the floor (the top of a full cell) pours into the cell above, as
		// vanilla does; one emptied against the +X wall pours into the wall's cell, like a block.
		BlockPos floor = spawn.below();
		assertEquals(spawn, HostFluids.bucketTarget(floor, Direction.UP, new Vec3(-15.2, 4.0, 8.67), spawn));
		BlockPos wall = new BlockPos(-12, 5, 8);
		assertEquals(wall, HostFluids.bucketTarget(wall, Direction.WEST, new Vec3(-11.25, 5.6, 8.67), wall.west()));
		assertTrue(HostFluids.blocks(wall, wall.east(), Direction.EAST));

		// The spawn is in the relaxation vault, a glass box with a 5 x 4-block floor at y = 4.0.
		// Water poured there covers all of it and none leaves through the glass or the floor.
		Map<BlockPos, Integer> wet = flood(spawn, WATER_REACH);
		System.out.println("HostFluidsTest: water from the spawn reaches " + wet.size() + " cells: x " + range(wet.keySet(), Direction.Axis.X) + ", y "
			+ range(wet.keySet(), Direction.Axis.Y) + ", z " + range(wet.keySet(), Direction.Axis.Z));
		assertEquals(VAULT_FLOOR, wet.size(), "the vault's floor is 5 x 4");
		for (BlockPos p : wet.keySet()) {
			assertEquals(4, p.getY(), "water fell to " + p);
			assertTrue(p.getX() >= -16 && p.getX() <= -12 && p.getZ() >= 6 && p.getZ() <= 9, "water left the vault to " + p);
		}
	}

	@Test
	void theFirstRoomsHoldWater() throws Exception {
		assumeTrue(Files.exists(MAP), "Portal not installed");
		BspMap map = BspMap.load(MAP, "testchmb_a_00");
		HostCollision.setMap(map);
		AABB bounds = null;
		for (BspMap.Brush b : map.brushes) {
			bounds = bounds == null ? b.mcBox() : bounds.minmax(b.mcBox());
		}
		// Everywhere a fluid could ever get from the vault, and from the chamber's floor just outside
		// it, crossing any face it's allowed to (up included, as if the fluid were pumped). A leak
		// anywhere lets the search out of the sealed map into the void around it.
		BlockPos vault = BlockPos.containing(Units.toMc(SPAWN_SRC));
		BlockPos chamber = new BlockPos(-12, 3, 10);
		Set<BlockPos> inVault = reachable(vault, bounds);
		Set<BlockPos> inChamber = reachable(chamber, bounds);
		System.out.println("HostFluidsTest: sealed regions: vault " + inVault.size() + " cells, chamber " + inChamber.size() + " cells, x "
			+ range(inChamber, Direction.Axis.X) + ", y " + range(inChamber, Direction.Axis.Y) + ", z " + range(inChamber, Direction.Axis.Z));
		assertFalse(inVault.contains(chamber), "the vault's glass leaks");
		assertTrue(inChamber.size() > 1000, "the chamber is too small: " + inChamber.size());
	}

	/** Every cell connected to start through faces a fluid may cross; fails if that leaves bounds. */
	private static Set<BlockPos> reachable(BlockPos start, AABB bounds) {
		Set<BlockPos> seen = new HashSet<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.add(start);
		queue.add(start);
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			assertTrue(bounds.intersects(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1), "a fluid from " + start + " leaks out of the map at " + p);
			assertTrue(seen.size() < 100_000, "a fluid from " + start + " floods everything");
			for (Direction d : Direction.values()) {
				BlockPos n = p.relative(d);
				if (!seen.contains(n) && !HostFluids.blocks(p, n, d)) {
					seen.add(n);
					queue.add(n);
				}
			}
		}
		return seen;
	}

	/**
	 * Where a source at start can get, the way FlowingFluid spreads (ignoring levels' fine detail):
	 * down when it can (a falling column keeps its reach), otherwise sideways losing one level per
	 * cell. Value: the level left at the cell.
	 */
	private static Map<BlockPos, Integer> flood(BlockPos start, int reach) {
		Map<BlockPos, Integer> seen = new HashMap<>();
		ArrayDeque<BlockPos> queue = new ArrayDeque<>();
		seen.put(start, reach);
		queue.add(start);
		while (!queue.isEmpty()) {
			BlockPos p = queue.poll();
			int left = seen.get(p);
			if (!HostFluids.floored(p)) {
				BlockPos below = p.below();
				if (below.getY() > start.getY() - 64 && seen.getOrDefault(below, -1) < reach) {
					seen.put(below, reach);
					queue.add(below);
				}
				continue;
			}
			if (left <= 0) {
				continue;
			}
			for (Direction d : Direction.Plane.HORIZONTAL) {
				BlockPos n = p.relative(d);
				if (!HostFluids.blocks(p, n, d) && seen.getOrDefault(n, -1) < left - 1) {
					seen.put(n, left - 1);
					queue.add(n);
				}
			}
		}
		return seen;
	}

	private static String range(Collection<BlockPos> cells, Direction.Axis axis) {
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (BlockPos p : cells) {
			int v = axis.choose(p.getX(), p.getY(), p.getZ());
			lo = Math.min(lo, v);
			hi = Math.max(hi, v);
		}
		return lo + ".." + hi;
	}

	private static long[] masks(VoxelShape shape) {
		return HostFluids.faceMasks(shape, List.of(), 0, 0, 0);
	}

	/** Whether the crossing from a cell with masks a into its neighbour d with masks b is blocked. */
	private static boolean cross(long[] a, long[] b, Direction d) {
		return !HostFluids.opening(a[d.get3DDataValue()], b[d.getOpposite().get3DDataValue()]);
	}
}
