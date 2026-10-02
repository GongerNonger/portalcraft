package dev.portalcraft.host;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Aiming at the host map's geometry: block raycasts (the crosshair, line of sight, projectiles)
 * see the host's walls, and a block placed against one lands in front of it.
 *
 * Minecraft's own world is AIR wherever the host has geometry, so attacking such a cell does
 * nothing (vanilla skips air) and nothing there can be broken. The player's own blocks break
 * quickly (breakProgress).
 */
public final class HostAim {
	private HostAim() {
	}

	/** A raycast's shape for this cell with the host's geometry added (ClipContextMixin). */
	public static VoxelShape withHost(VoxelShape shape, BlockPos pos) {
		VoxelShape host = HostCollision.shapeAt(pos);
		if (host == null) {
			return shape;
		}
		return shape.isEmpty() ? host : Shapes.or(shape, host);
	}

	public static boolean isHostCell(BlockPos pos) {
		return HostCollision.shapeAt(pos) != null;
	}

	/**
	 * A block placed against host geometry that was hit in cell pos, on face, at hit: true to put it
	 * in the neighbouring cell pos.relative(face), false to put it in pos itself.
	 *
	 * The host's surfaces don't sit on block boundaries (Portal's spawn floor is at y = 4.05, so
	 * cell y = 4 holds just a 0.05-block sliver of it). The block goes in whichever cell needs the
	 * smaller nudge: the clicked cell when less than half of it lies behind the surface (the block
	 * then sinks up to half a block into the wall), the neighbour otherwise (it then floats up to
	 * half a block in front). A neighbour that itself reaches into host geometry is still allowed.
	 */
	public static boolean placeInNeighbour(BlockPos pos, Direction face, Vec3 hit) {
		double t = face.getAxis().choose(hit.x - pos.getX(), hit.y - pos.getY(), hit.z - pos.getZ());
		double behind = face.getAxisDirection() == Direction.AxisDirection.POSITIVE ? t : 1.0 - t;
		return behind > 0.5;
	}

	/** Ticks of holding left click that break any breakable block while a host map is loaded. */
	public static final int BREAK_TICKS = 4;

	/**
	 * A block's mining progress per tick (BlockStateBase.getDestroyProgress), sped up while a host
	 * map is loaded (BlockStateBaseMixin). Every Minecraft block there is one the player placed in
	 * a void world, mostly stone, and stone by hand takes 150 ticks (7.5 s; five times that in the
	 * air) with no cracks to show for it, since the host draws only the finished blocks. So
	 * anything breakable goes in at most BREAK_TICKS: quick, but not instant, so a held click
	 * sweeping across a wall doesn't eat a block every tick. Faster vanilla speeds are kept, and
	 * so are unbreakable blocks (progress 0).
	 */
	public static float breakProgress(float vanilla) {
		if (vanilla <= 0.0F || !HostCollision.active()) {
			return vanilla;
		}
		return Math.max(vanilla, 1.0F / BREAK_TICKS);
	}
}
