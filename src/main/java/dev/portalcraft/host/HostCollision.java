package dev.portalcraft.host;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * The host map's solid geometry as Minecraft collision, one block cell at a time. Vanilla movement
 * (step-up, sneaking at edges, fall damage, ...) then runs unchanged against it.
 *
 * Linked portals cut a hole through whatever they sit on, so Minecraft can walk or fall into the
 * oval far enough for the host to see the player cross the portal plane and teleport them.
 */
public final class HostCollision {
	/** Portal 1's opening: 64 x 108 units. */
	private static final double PORTAL_HALF_WIDTH = 32.0;
	private static final double PORTAL_HALF_HEIGHT = 54.0;
	/** How far behind the surface the hole reaches, in units. Thicker than any Portal wall. */
	private static final double HOLE_DEPTH = 72.0;
	private static final int BUCKET = 8;
	/** Columns per block edge for sloped brushes: 1/16 block = 2.5 units. */
	private static final int COLUMNS = 16;

	/** Brushes bucketed by 8-block region; ones too big to bucket are checked everywhere. */
	private record Index(Map<Long, List<BspMap.Brush>> buckets, List<BspMap.Brush> huge) {
		static final Index EMPTY = new Index(Map.of(), List.of());

		List<BspMap.Brush> near(int x, int y, int z) {
			return buckets.getOrDefault(BlockPos.asLong(Math.floorDiv(x, BUCKET), Math.floorDiv(y, BUCKET), Math.floorDiv(z, BUCKET)), List.of());
		}
	}

	private static volatile @Nullable BspMap map;
	/** The world and static props. */
	private static volatile Index world = Index.EMPTY;
	/** Brush entities where the map compiled them, until the host streams live ones. */
	private static volatile Index baked = Index.EMPTY;
	/** Live entities from the host: doors, buttons, lifts, cubes, toggling walls. */
	private static volatile Index dynamic = Index.EMPTY;
	private static volatile boolean live;
	private static volatile List<AABB> holes = List.of();
	private static final ConcurrentHashMap<Long, VoxelShape> CACHE = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Long, VoxelShape> DYNAMIC_CACHE = new ConcurrentHashMap<>();
	private static final VoxelShape EMPTY = Shapes.empty();

	private HostCollision() {
	}

	public static boolean active() {
		return map != null;
	}

	public static @Nullable String mapName() {
		BspMap m = map;
		return m == null ? null : m.name;
	}

	public static void clear() {
		map = null;
		world = baked = dynamic = Index.EMPTY;
		live = false;
		CACHE.clear();
		DYNAMIC_CACHE.clear();
	}

	public static void setMap(BspMap m) {
		world = index(m.brushes);
		baked = index(m.bakedEntityBrushes);
		dynamic = Index.EMPTY;
		live = false;
		map = m;
		CACHE.clear();
		DYNAMIC_CACHE.clear();
	}

	public static @Nullable BspMap map() {
		return map;
	}

	/**
	 * The host's live solid entities, already placed. The first call switches the baked brush
	 * entities off: from then on the host says what's solid and where.
	 */
	public static void setDynamic(List<BspMap.Brush> brushes, List<AABB> dirty) {
		dynamic = index(brushes);
		if (!live) {
			live = true;
			CACHE.clear();
			DYNAMIC_CACHE.clear();
			return;
		}
		// Only cells something moved through need rebuilding: sloped cells cost milliseconds each,
		// and doors, buttons and lifts update about 16 times a second.
		for (AABB box : dirty) {
			int x0 = (int) Math.floor(box.minX) - 1, x1 = (int) Math.floor(box.maxX) + 1;
			int y0 = (int) Math.floor(box.minY) - 1, y1 = (int) Math.floor(box.maxY) + 1;
			int z0 = (int) Math.floor(box.minZ) - 1, z1 = (int) Math.floor(box.maxZ) + 1;
			if ((long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) > 50_000) {
				DYNAMIC_CACHE.clear();
				return;
			}
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						DYNAMIC_CACHE.remove(BlockPos.asLong(x, y, z));
					}
				}
			}
		}
	}

	private static Index index(List<BspMap.Brush> brushes) {
		Map<Long, List<BspMap.Brush>> index = new HashMap<>();
		List<BspMap.Brush> big = new ArrayList<>();
		for (BspMap.Brush brush : brushes) {
			AABB box = brush.mcBox();
			int x0 = Math.floorDiv((int) Math.floor(box.minX), BUCKET), x1 = Math.floorDiv((int) Math.floor(box.maxX), BUCKET);
			int y0 = Math.floorDiv((int) Math.floor(box.minY), BUCKET), y1 = Math.floorDiv((int) Math.floor(box.maxY), BUCKET);
			int z0 = Math.floorDiv((int) Math.floor(box.minZ), BUCKET), z1 = Math.floorDiv((int) Math.floor(box.maxZ), BUCKET);
			long cells = (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1);
			if (cells > 20_000) {
				big.add(brush);
				continue;
			}
			for (int x = x0; x <= x1; x++) {
				for (int y = y0; y <= y1; y++) {
					for (int z = z0; z <= z1; z++) {
						index.computeIfAbsent(BlockPos.asLong(x, y, z), k -> new ArrayList<>()).add(brush);
					}
				}
			}
		}
		return new Index(index, big);
	}

	/** The linked portals' holes, in world coordinates. Fluids treat them as solid (HostFluids). */
	public static List<AABB> holes() {
		return holes;
	}

	/** Recomputes the holes from the host's portals; cheap when nothing changed. */
	public static void setPortals(Proto.HostPortal[] portals) {
		List<AABB> next = new ArrayList<>();
		for (Proto.HostPortal p : portals) {
			if (p != null && p.linked()) {
				next.add(hole(p));
			}
		}
		if (!next.equals(holes)) {
			holes = List.copyOf(next);
			CACHE.clear();
			DYNAMIC_CACHE.clear();
		}
	}

	private static AABB hole(Proto.HostPortal p) {
		Vec3[] axes = Units.angleVectors(p.angles());
		Vec3 forward = axes[0], right = axes[1], up = axes[2];
		Vec3 o = p.origin();
		double minX = 1e9, minY = 1e9, minZ = 1e9, maxX = -1e9, maxY = -1e9, maxZ = -1e9;
		for (int sr = -1; sr <= 1; sr += 2) {
			for (int su = -1; su <= 1; su += 2) {
				for (double depth : new double[] {2.0, -HOLE_DEPTH}) {
					Vec3 c = o.add(right.scale(sr * PORTAL_HALF_WIDTH)).add(up.scale(su * PORTAL_HALF_HEIGHT)).add(forward.scale(depth));
					Vec3 m = Units.toMc(c);
					minX = Math.min(minX, m.x);
					minY = Math.min(minY, m.y);
					minZ = Math.min(minZ, m.z);
					maxX = Math.max(maxX, m.x);
					maxY = Math.max(maxY, m.y);
					maxZ = Math.max(maxZ, m.z);
				}
			}
		}
		return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
	}

	/** Host geometry inside this block cell, in cell-local coordinates, or null for none. */
	public static @Nullable VoxelShape shapeAt(BlockPos pos) {
		if (map == null) {
			return null;
		}
		// DYNAMIC_CACHE holds the finished cell: the fixed geometry joined with whatever live
		// entities are in it. Joining costs a full optimize (hundreds of boxes in a sloped cell), so it
		// must not happen per query. Every invalidation that drops a CACHE entry clears this too, and a
		// moving entity drops just the cells it passed through (setDynamic).
		int x = pos.getX(), y = pos.getY(), z = pos.getZ();
		VoxelShape shape = DYNAMIC_CACHE.computeIfAbsent(pos.asLong(), k -> {
			VoxelShape fixed = CACHE.computeIfAbsent(k, kk -> live ? build(x, y, z, world) : build(x, y, z, world, baked));
			VoxelShape moving = build(x, y, z, dynamic);
			return moving == EMPTY ? fixed : fixed == EMPTY ? moving : Shapes.or(fixed, moving);
		});
		return shape == EMPTY ? null : shape;
	}

	private static VoxelShape build(int x, int y, int z, Index... sources) {
		AABB cell = new AABB(x, y, z, x + 1, y + 1, z + 1);
		List<List<BspMap.Brush>> lists = new ArrayList<>();
		for (Index source : sources) {
			lists.add(source.near(x, y, z));
			lists.add(source.huge());
		}
		List<VoxelShape> parts = new ArrayList<>();
		boolean full = false;
		for (List<BspMap.Brush> list : lists) {
			for (BspMap.Brush brush : list) {
				AABB box = brush.mcBox();
				if (!box.intersects(cell)) {
					continue;
				}
				if (brush.sloped()) {
					columns(brush, x, y, z, parts);
					continue;
				}
				AABB clip = box.intersect(cell);
				if (clip.getXsize() >= 0.999 && clip.getYsize() >= 0.999 && clip.getZsize() >= 0.999) {
					full = true;
					break;
				}
				parts.add(local(clip, x, y, z));
			}
			if (full) {
				break;
			}
		}
		VoxelShape shape = full ? Shapes.block() : union(parts, 0, parts.size());
		for (AABB hole : holes) {
			if (hole.intersects(cell)) {
				shape = Shapes.joinUnoptimized(shape, local(hole.intersect(cell), x, y, z), BooleanOp.ONLY_FIRST);
			}
		}
		shape = optimize(shape);
		return shape.isEmpty() ? EMPTY : shape;
	}

	/** VoxelShape.optimize() re-joins its boxes one at a time; a slope's hundreds of columns made that the slow part. */
	private static VoxelShape optimize(VoxelShape shape) {
		List<VoxelShape> boxes = new ArrayList<>();
		shape.forAllBoxes((x1, y1, z1, x2, y2, z2) -> boxes.add(Shapes.box(x1, y1, z1, x2, y2, z2)));
		return union(boxes, 0, boxes.size());
	}

	/** Balanced OR of many boxes: joining them one at a time costs the square of their count. */
	private static VoxelShape union(List<VoxelShape> parts, int from, int to) {
		if (to - from <= 0) {
			return Shapes.empty();
		}
		if (to - from == 1) {
			return parts.get(from);
		}
		int mid = (from + to) >>> 1;
		return Shapes.joinUnoptimized(union(parts, from, mid), union(parts, mid, to), BooleanOp.OR);
	}

	/**
	 * A sloped brush inside the cell as COLUMNS x COLUMNS vertical prisms. Each prism spans exactly
	 * what the brush covers on the vertical line through its centre, from the brush's own planes, so
	 * flat tops and bottoms land where they really are (a voxel grid rounds them to its step, which
	 * put chamfered floor panels 2 units above their axial neighbours and missed hulls thinner than
	 * a voxel). Only the horizontal extent is sampled: walls are right to within half a column, and
	 * a slope becomes a staircase of column-wide steps whose treads touch the plane at their centre.
	 * Columns with the same span are merged into runs along X.
	 */
	private static void columns(BspMap.Brush brush, int x, int y, int z, List<VoxelShape> out) {
		double step = 1.0 / COLUMNS;
		double[] span = new double[2];
		for (int k = 0; k < COLUMNS; k++) {
			double runLo = 0, runHi = 0;
			int runStart = -1;
			for (int i = 0; i <= COLUMNS; i++) {
				boolean inside = false;
				double lo = 0, hi = 0;
				if (i < COLUMNS && span(brush, (x + (i + 0.5) * step) * Units.PER_BLOCK, -(z + (k + 0.5) * step) * Units.PER_BLOCK, span)) {
					lo = Math.max(span[0] / Units.PER_BLOCK - y, 0.0);
					hi = Math.min(span[1] / Units.PER_BLOCK - y, 1.0);
					inside = hi - lo >= 1.0E-7;
				}
				if (runStart >= 0 && (!inside || lo != runLo || hi != runHi)) {
					out.add(Shapes.box(runStart * step, runLo, k * step, i * step, runHi, (k + 1) * step));
					runStart = -1;
				}
				if (inside && runStart < 0) {
					runStart = i;
					runLo = lo;
					runHi = hi;
				}
			}
		}
	}

	/**
	 * The brush along the vertical line (sx, sy), in Source units, into span as {bottom, top}; false
	 * if the line misses it. Heights snap to 1/256 unit so a flat face gives every column the same
	 * height and the columns merge, and an integer height matches the axial brushes' exactly.
	 */
	private static boolean span(BspMap.Brush brush, double sx, double sy, double[] span) {
		float[] p = brush.planes();
		double lo = -1e9, hi = 1e9;
		for (int i = 0; i < p.length; i += 4) {
			double a = p[i] * sx + p[i + 1] * sy, d = p[i + 3], nz = p[i + 2];
			if (Math.abs(nz) < 1e-6) {
				if (a > d + 0.01) { // the same slack as Brush.containsSrc
					return false;
				}
			} else if (nz > 0) {
				hi = Math.min(hi, (d - a) / nz);
			} else {
				lo = Math.max(lo, (d - a) / nz);
			}
		}
		if (!(lo < hi)) {
			return false;
		}
		span[0] = Math.rint(lo * 256.0) / 256.0;
		span[1] = Math.rint(hi * 256.0) / 256.0;
		return span[0] < span[1];
	}

	private static VoxelShape local(AABB box, int x, int y, int z) {
		return Shapes.box(
			clamp(box.minX - x), clamp(box.minY - y), clamp(box.minZ - z),
			clamp(box.maxX - x), clamp(box.maxY - y), clamp(box.maxZ - z)
		);
	}

	private static double clamp(double v) {
		return Math.max(0.0, Math.min(1.0, v));
	}
}
