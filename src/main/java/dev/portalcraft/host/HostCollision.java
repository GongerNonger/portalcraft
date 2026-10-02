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

	private static volatile @Nullable BspMap map;
	private static volatile Map<Long, List<BspMap.Brush>> buckets = Map.of();
	private static volatile List<BspMap.Brush> huge = List.of();
	private static volatile List<AABB> holes = List.of();
	private static final ConcurrentHashMap<Long, VoxelShape> CACHE = new ConcurrentHashMap<>();
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
		buckets = Map.of();
		huge = List.of();
		CACHE.clear();
	}

	public static void setMap(BspMap m) {
		Map<Long, List<BspMap.Brush>> index = new HashMap<>();
		List<BspMap.Brush> big = new ArrayList<>();
		for (BspMap.Brush brush : m.brushes) {
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
		buckets = index;
		huge = big;
		map = m;
		CACHE.clear();
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
		VoxelShape shape = CACHE.computeIfAbsent(pos.asLong(), k -> build(pos.getX(), pos.getY(), pos.getZ()));
		return shape == EMPTY ? null : shape;
	}

	private static VoxelShape build(int x, int y, int z) {
		AABB cell = new AABB(x, y, z, x + 1, y + 1, z + 1);
		List<BspMap.Brush> near = buckets.getOrDefault(BlockPos.asLong(Math.floorDiv(x, BUCKET), Math.floorDiv(y, BUCKET), Math.floorDiv(z, BUCKET)), List.of());
		List<VoxelShape> parts = new ArrayList<>();
		boolean full = false;
		for (List<BspMap.Brush> list : List.of(near, huge)) {
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
