package dev.portalcraft;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The hole behind each linked portal, as in Portal: the blocks the oval sits on lose their
 * collision inside it, so you walk (or fall) into the portal, and you go through when your centre
 * crosses its surface (PortalCraftClient.checkPortals) instead of bumping into the wall. Kept on
 * both sides (the server checks the player's moves against the same holes) and cut out of every
 * block-collision query by BlockCollisionsMixin.
 */
public final class PortalHoles {
	private static final Map<PortalEntity, AABB> HOLES = new ConcurrentHashMap<>();

	private PortalHoles() {
	}

	/** Every tick, both sides: a linked portal has its hole, an unlinked or gone one doesn't. */
	public static void update(PortalEntity portal) {
		if (portal.isLinked() && !portal.isRemoved()) {
			HOLES.put(portal, portal.hole());
		} else {
			HOLES.remove(portal);
		}
	}

	public static void remove(PortalEntity portal) {
		HOLES.remove(portal);
	}

	/** `shape` (the block at `pos` in `level`) with the holes that reach into it taken out. */
	public static VoxelShape cut(Level level, BlockPos pos, VoxelShape shape) {
		if (HOLES.isEmpty() || shape.isEmpty()) {
			return shape;
		}
		AABB cell = new AABB(pos);
		for (Map.Entry<PortalEntity, AABB> e : HOLES.entrySet()) {
			if (e.getKey().level() == level && e.getValue().intersects(cell)) {
				AABB local = e.getValue().move(-pos.getX(), -pos.getY(), -pos.getZ());
				shape = Shapes.join(shape, Shapes.create(local), BooleanOp.ONLY_FIRST);
			}
		}
		return shape;
	}
}
