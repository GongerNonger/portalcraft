package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Aiming and building against the host's geometry (the map half is skipped without Portal). */
class HostAimTest {
	private static final Path MAP = Path.of("D:/SteamLibrary/steamapps/common/Portal/portal/maps/testchmb_a_00.bsp");
	/** Where Portal spawned the player in this map (from the plugin log). */
	private static final Vec3 SPAWN_SRC = new Vec3(-607.8, -346.7, 162.0);
	private static final double EYE = 1.62 * HostScale.STEVE;

	@AfterEach
	void clear() {
		HostCollision.clear();
	}

	@Test
	void halfCellRule() {
		BlockPos cell = new BlockPos(2, 3, -4);
		// A full wall: the surface is on the cell's face, so the block goes in front.
		assertTrue(HostAim.placeInNeighbour(cell, Direction.UP, new Vec3(2.5, 4.0, -3.5)));
		assertTrue(HostAim.placeInNeighbour(cell, Direction.DOWN, new Vec3(2.5, 3.0, -3.5)));
		assertTrue(HostAim.placeInNeighbour(cell, Direction.WEST, new Vec3(2.0, 3.5, -3.5)));
		assertTrue(HostAim.placeInNeighbour(cell, Direction.SOUTH, new Vec3(2.5, 3.5, -3.0)));
		// A sliver: most of the cell is open, so the block goes in it.
		assertFalse(HostAim.placeInNeighbour(cell, Direction.UP, new Vec3(2.5, 3.1, -3.5)));
		assertFalse(HostAim.placeInNeighbour(cell, Direction.EAST, new Vec3(2.2, 3.5, -3.5)));
		assertFalse(HostAim.placeInNeighbour(cell, Direction.NORTH, new Vec3(2.5, 3.5, -3.1)));
		assertTrue(HostAim.placeInNeighbour(cell, Direction.NORTH, new Vec3(2.5, 3.5, -3.9)));
	}

	@Test
	void noMapNoHostShape() {
		HostCollision.clear();
		assertFalse(HostAim.isHostCell(BlockPos.ZERO));
		assertTrue(HostAim.withHost(Shapes.empty(), BlockPos.ZERO).isEmpty());
		assertEquals(Shapes.block(), HostAim.withHost(Shapes.block(), BlockPos.ZERO));
	}

	@Test
	void aimFromTheSpawn() throws Exception {
		assumeTrue(Files.exists(MAP), "Portal not installed");
		HostCollision.setMap(BspMap.load(MAP, "testchmb_a_00"));
		Vec3 eye = Units.toMc(SPAWN_SRC).add(0, EYE, 0);

		// Straight down: the vault's floor is at 160 units, a whole number of blocks at 32 units to the
		// block, so its top is a cell's top face and the block placed on it sits exactly on the floor.
		Vec3 spawn = Units.toMc(SPAWN_SRC);
		double floorY = 160.0 / Units.PER_BLOCK;
		assertEquals(Math.rint(floorY), floorY, 1e-9, "Portal's floors sit on the block grid");
		BlockHitResult floor = look(eye, Direction.DOWN);
		BlockPos under = BlockPos.containing(spawn.x, floorY - 0.5, spawn.z);
		assertEquals(under, floor.getBlockPos().immutable());
		assertEquals(Direction.UP, floor.getDirection());
		assertEquals(floorY, floor.getLocation().y, 1e-6);
		assertEquals(under.above(), placed(floor));

		// Every direction: a hit, on the face toward the eye, and the placed block within half a
		// block of the surface it was placed against.
		for (Direction d : Direction.values()) {
			BlockHitResult hit = look(eye, d);
			assertEquals(HitResult.Type.BLOCK, hit.getType(), d + ": missed");
			assertEquals(d.getOpposite(), hit.getDirection(), d + ": wrong face");
			assertTrue(HostAim.isHostCell(hit.getBlockPos()), d + ": hit a cell without host geometry");
			BlockPos at = placed(hit);
			Direction.Axis axis = d.getAxis();
			double surface = axis.choose(hit.getLocation().x, hit.getLocation().y, hit.getLocation().z);
			double nearFace = hit.getDirection().getAxisDirection() == Direction.AxisDirection.POSITIVE ? axis.choose(at.getX(), at.getY(), at.getZ()) : axis.choose(at.getX(), at.getY(), at.getZ()) + 1;
			assertTrue(Math.abs(nearFace - surface) <= 0.5 + 1e-9, d + ": block placed " + Math.abs(nearFace - surface) + " off the surface");
		}
	}

	/** Where a block placed against hit goes: BlockPlaceContext's choice once BlockPlaceContextMixin has run. */
	private static BlockPos placed(BlockHitResult hit) {
		BlockPos pos = hit.getBlockPos().immutable();
		return HostAim.placeInNeighbour(pos, hit.getDirection(), hit.getLocation()) ? pos.relative(hit.getDirection()) : pos;
	}

	private static BlockHitResult look(Vec3 eye, Direction d) {
		return clip(eye, eye.add(new Vec3(d.getStepX(), d.getStepY(), d.getStepZ()).scale(20)));
	}

	/** BlockGetter.clip over an empty Minecraft world, with the shape ClipContextMixin hands it. */
	private static BlockHitResult clip(Vec3 from, Vec3 to) {
		return BlockGetter.traverseBlocks(from, to, null, (c, pos) -> HostAim.withHost(Shapes.empty(), pos).clip(from, to, pos), c -> {
			Vec3 delta = from.subtract(to);
			return BlockHitResult.miss(to, Direction.getApproximateNearest(delta.x, delta.y, delta.z), BlockPos.containing(to));
		});
	}
}
