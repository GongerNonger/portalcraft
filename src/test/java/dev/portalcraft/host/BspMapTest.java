package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

/** Reads the real first test chamber if Portal is installed (skipped otherwise). */
class BspMapTest {
	private static final Path MAP = Path.of("D:/SteamLibrary/steamapps/common/Portal/portal/maps/testchmb_a_00.bsp");
	/** Where Portal spawned the player in this map (from the plugin log). */
	private static final Vec3 SPAWN_SRC = new Vec3(-607.8, -346.7, 162.0);

	@Test
	void spawnHasFloorAndHeadroom() throws Exception {
		assumeTrue(Files.exists(MAP), "Portal not installed");
		BspMap map = BspMap.load(MAP, "testchmb_a_00");
		long sloped = map.brushes.stream().filter(BspMap.Brush::sloped).count();
		System.out.println("brushes: " + map.brushes.size() + " (" + sloped + " sloped)");
		assertTrue(map.brushes.size() > 100);
		HostCollision.setMap(map);

		Vec3 feet = Units.toMc(SPAWN_SRC);
		System.out.println("spawn in blocks: " + feet);
		BlockPos below = BlockPos.containing(feet.x, feet.y - 4.0 / Units.PER_BLOCK, feet.z); // the spawn is 2 units over the floor
		VoxelShape floor = HostCollision.shapeAt(below);
		assertNotNull(floor, "no floor under the spawn point");
		System.out.println("floor cell " + below + " top at " + (below.getY() + floor.max(net.minecraft.core.Direction.Axis.Y)));

		// The player's own box, standing there, must touch nothing.
		double s = HostScale.STEVE; // Steve's size in blocks inside a host map
		net.minecraft.world.phys.AABB body = new net.minecraft.world.phys.AABB(feet.x - 0.3 * s, feet.y + 0.06 * s, feet.z - 0.3 * s, feet.x + 0.3 * s,
			feet.y + 1.8 * s, feet.z + 0.3 * s);
		assertFalse(intersects(body), "the spawn point is inside a wall");
		// And the same box dropped a little must land on something.
		assertTrue(intersects(body.move(0, -0.12 * s, 0)), "nothing to stand on");
		// The relaxation vault's glass (func_brush *61, stored relative to its origin at x = -638.5)
		// is 31 units (0.78 blocks) toward -X from the spawn: a step that way must hit it.
		assertTrue(intersects(body.expandTowards(Units.toMc(new Vec3(-48, 0, 0)))), "walked through the vault glass");

		HostCollision.clear();
		assertFalse(HostCollision.active());
	}

	private static boolean intersects(net.minecraft.world.phys.AABB box) {
		for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ), BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
			VoxelShape shape = HostCollision.shapeAt(pos);
			if (shape != null && shape.move(pos).toAabbs().stream().anyMatch(box::intersects)) {
				return true;
			}
		}
		return false;
	}
}
