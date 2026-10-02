package dev.portalcraft.host;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Water and lava against the host map's geometry. Minecraft's world is AIR wherever the host has
 * walls and floors, so without this a fluid placed on Portal's floor falls straight through it and
 * spreads sideways through its walls. FlowingFluid asks one place whether a fluid may cross from a
 * cell into its neighbour (canPassThroughWall, for falling, spreading, slope finding and feeding
 * alike); FlowingFluidMixin adds {@link #blocks} there.
 *
 * <p><b>What blocks a fluid.</b> A cell's fluid is the open space around the cell's centre: the
 * cell is cut into {@link #N}^3 voxels, any voxel the host geometry touches is solid, and the
 * fluid's region is every open voxel connected to the central 2x2x2 without leaving the cell. A
 * fluid crosses from cell A into its neighbour B only where A's region and B's region meet on
 * their shared face, through an opening of at least {@link #MIN_OPENING} voxel faces.
 *
 * <p>Why not vanilla's rule (the two shapes' faces exactly on the shared boundary): Portal's
 * surfaces almost never sit on block boundaries (1 block = 40 units against a 16-unit grid), so a
 * 16-unit wall usually lies inside a cell without touching either face, and the face test lets
 * water through it. Why not "the cell is mostly full": a floor at y = 4.05 leaves cell 4 nearly
 * empty, yet water in it must not fall through. Why the region and not just "is the face covered
 * between the two centres": walls turn corners inside cells, and a projection along one axis sees
 * the outside of the corner as an opening even though the fluid at the centre can't get there
 * without crossing the other wall. (Measured on testchmb_a_00: the projection let the spawn's
 * water out through the relaxation vault's glass corners and on into the void.)
 *
 * <p>The region keeps to the cell's open side the same way HostAim places a block: a floor
 * sliver at a cell's bottom floors that cell and water spreads over it; a cell whose centre is
 * inside a wall holds no fluid, and the fluid stops in the cell before it, at most half a block
 * short of the surface. Openings under {@link #MIN_OPENING} (a seam between two props, a
 * rasterised speck) don't leak.
 *
 * <p>Linked portals cut holes through the collision so the player can walk into them. Fluids don't
 * teleport, and a hole reaches 72 units behind the surface, through the wall: water would pour
 * through a wall portal into whatever is behind it. So for fluids each hole's box is solid.
 *
 * <p>Cost: fluid ticks call this hundreds of times per spread (the slope search). Each cell's
 * shape comes from HostCollision's cache, and its six face masks are worked out once per shape
 * instance and kept as long as HostCollision keeps that shape (a weak identity map, so a rebuilt
 * cell gets new masks and dropped ones go with their shape). Cells without host geometry, most of
 * them, cost two map lookups.
 */
public final class HostFluids {
	/** Voxels per cell edge: 1/8 block = 5 units. */
	static final int N = 8;
	/** Shared voxel faces it takes to let a fluid through: about a 10 x 10-unit hole. */
	static final int MIN_OPENING = 4;
	private static final double EPS = 1.0E-6;
	/** A cell with no host geometry: the whole of every face is open. */
	private static final long[] OPEN = {-1L, -1L, -1L, -1L, -1L, -1L};
	/** Face masks per HostCollision shape. VoxelShape has identity equality. */
	private static final Map<VoxelShape, long[]> MASKS = Collections.synchronizedMap(new WeakHashMap<>());
	/** Voxel bits that have a neighbour at z - 1 / z + 1 in the same long (bit y * N + z). */
	private static final long HAS_LOWER_Z = 0xFEFEFEFEFEFEFEFEL, HAS_UPPER_Z = 0x7F7F7F7F7F7F7F7FL;

	private HostFluids() {
	}

	/**
	 * True if host geometry stops a fluid crossing from cell from into its neighbour to =
	 * from.relative(dir). False whenever no host map is loaded.
	 */
	public static boolean blocks(BlockPos from, BlockPos to, Direction dir) {
		if (!HostCollision.active()) {
			return false;
		}
		return !opening(faceMasks(from)[dir.get3DDataValue()], faceMasks(to)[dir.getOpposite().get3DDataValue()]);
	}

	/** True if host geometry under this cell holds a fluid in it: it can't fall out of the cell. */
	public static boolean floored(BlockPos pos) {
		return blocks(pos, pos.below(), Direction.DOWN);
	}

	/**
	 * Where a bucket empties when it's used on host geometry hit in cell clicked: the clicked cell
	 * when most of it is open (HostAim's half-cell rule), else vanilla's choice (the neighbour on
	 * the face hit). Vanilla always uses the neighbour, so water poured on Portal's floor at
	 * y = 4.05 would hang a block above it with a waterfall under it.
	 */
	public static BlockPos bucketTarget(BlockPos clicked, Direction face, Vec3 hit, BlockPos vanilla) {
		if (vanilla.equals(clicked) || !HostAim.isHostCell(clicked) || HostAim.placeInNeighbour(clicked, face, hit)) {
			return vanilla;
		}
		return clicked.immutable();
	}

	/** Whether two cells' masks on their shared face leave a way through. */
	static boolean opening(long from, long to) {
		return Long.bitCount(from & to) >= MIN_OPENING;
	}

	/**
	 * This cell's six face masks, by Direction.get3DDataValue(): which of the face's N x N voxel
	 * faces the cell's fluid region touches. X faces are indexed (y, z), Y faces (x, z), Z faces
	 * (x, y), bit u * N + v, the same for both faces of an axis so neighbours' masks line up.
	 */
	static long[] faceMasks(BlockPos pos) {
		VoxelShape shape = HostCollision.shapeAt(pos);
		List<AABB> holes = HostCollision.holes();
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		for (int i = 0, n = holes.size(); i < n; i++) {
			if (holes.get(i).intersects(x, y, z, x + 1, y + 1, z + 1)) {
				// Rare (a handful of cells per portal) and the holes change with the portals: not cached.
				return faceMasks(shape, holes, x, y, z);
			}
		}
		if (shape == null) {
			return OPEN;
		}
		long[] masks = MASKS.get(shape);
		if (masks == null) {
			masks = faceMasks(shape, List.of(), x, y, z);
			MASKS.put(shape, masks);
		}
		return masks;
	}

	/** The face masks of a cell-local shape plus any of the world-space holes reaching into cell (x, y, z). */
	static long[] faceMasks(@Nullable VoxelShape shape, List<AABB> holes, int x, int y, int z) {
		// solid[i] holds the voxels at x index i, bit y * N + z.
		long[] solid = new long[N];
		if (shape != null) {
			shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> fill(solid, x1, y1, z1, x2, y2, z2));
		}
		for (AABB hole : holes) {
			if (hole.intersects(x, y, z, x + 1, y + 1, z + 1)) {
				fill(solid, hole.minX - x, hole.minY - y, hole.minZ - z, hole.maxX - x, hole.maxY - y, hole.maxZ - z);
			}
		}
		return faces(region(solid));
	}

	/** Marks every voxel the box overlaps by more than a hair: thin glass still makes a wall. */
	private static void fill(long[] solid, double x1, double y1, double z1, double x2, double y2, double z2) {
		int i0 = lo(x1), i1 = hi(x2), j0 = lo(y1), j1 = hi(y2), k0 = lo(z1), k1 = hi(z2);
		if (i0 > i1 || j0 > j1 || k0 > k1) {
			return;
		}
		long row = ((1L << (k1 - k0 + 1)) - 1) << k0;
		long slab = 0L;
		for (int j = j0; j <= j1; j++) {
			slab |= row << (j * N);
		}
		for (int i = i0; i <= i1; i++) {
			solid[i] |= slab;
		}
	}

	private static int lo(double v) {
		return Math.max(0, (int) Math.floor(v * N + EPS));
	}

	private static int hi(double v) {
		return Math.min(N - 1, (int) Math.ceil(v * N - EPS) - 1);
	}

	/** The open voxels connected, inside the cell, to the open ones of the central 2x2x2. */
	static long[] region(long[] solid) {
		long[] open = new long[N];
		long[] r = new long[N];
		long centre = 0L;
		for (int j = N / 2 - 1; j <= N / 2; j++) {
			centre |= 0b11L << (j * N + N / 2 - 1);
		}
		for (int i = 0; i < N; i++) {
			open[i] = ~solid[i];
			if (i == N / 2 - 1 || i == N / 2) {
				r[i] = centre & open[i];
			}
		}
		// Grow by one voxel in all six directions until nothing changes: a few dozen rounds at most
		// in practice (the region's longest path inside the cell).
		boolean grew = true;
		while (grew) {
			grew = false;
			for (int i = 0; i < N; i++) {
				long g = r[i] | r[i] << N | r[i] >>> N | (r[i] << 1 & HAS_LOWER_Z) | (r[i] >>> 1 & HAS_UPPER_Z);
				if (i > 0) {
					g |= r[i - 1];
				}
				if (i < N - 1) {
					g |= r[i + 1];
				}
				g &= open[i];
				if (g != r[i]) {
					r[i] = g;
					grew = true;
				}
			}
		}
		return r;
	}

	/** The region's voxels in the layer against each face, projected onto the face. */
	static long[] faces(long[] r) {
		long[] m = new long[6];
		m[Direction.WEST.get3DDataValue()] = r[0];
		m[Direction.EAST.get3DDataValue()] = r[N - 1];
		long down = 0L, up = 0L, north = 0L, south = 0L;
		for (int i = 0; i < N; i++) {
			// y = 0 / y = N - 1 rows: z bits for this x.
			down |= (r[i] & 0xFFL) << (i * N);
			up |= (r[i] >>> ((N - 1) * N) & 0xFFL) << (i * N);
			for (int j = 0; j < N; j++) {
				north |= (r[i] >>> (j * N) & 1L) << (i * N + j);
				south |= (r[i] >>> (j * N + N - 1) & 1L) << (i * N + j);
			}
		}
		m[Direction.DOWN.get3DDataValue()] = down;
		m[Direction.UP.get3DDataValue()] = up;
		m[Direction.NORTH.get3DDataValue()] = north;
		m[Direction.SOUTH.get3DDataValue()] = south;
		return m;
	}
}
