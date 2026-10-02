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
	private static final int SUBCELLS = 8;

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
		VoxelShape shape = Shapes.empty();
		boolean full = false;
		for (List<BspMap.Brush> list : List.of(near, huge)) {
			for (BspMap.Brush brush : list) {
				AABB box = brush.mcBox();
				if (!box.intersects(cell)) {
					continue;
				}
				if (brush.sloped()) {
					shape = Shapes.joinUnoptimized(shape, voxelise(brush, x, y, z, box.intersect(cell)), BooleanOp.OR);
					continue;
				}
				AABB clip = box.intersect(cell);
				if (clip.getXsize() >= 0.999 && clip.getYsize() >= 0.999 && clip.getZsize() >= 0.999) {
					full = true;
					break;
				}
				shape = Shapes.joinUnoptimized(shape, local(clip, x, y, z), BooleanOp.OR);
			}
			if (full) {
				break;
			}
		}
		if (full) {
			shape = Shapes.block();
		}
		for (AABB hole : holes) {
			if (hole.intersects(cell)) {
				shape = Shapes.joinUnoptimized(shape, local(hole.intersect(cell), x, y, z), BooleanOp.ONLY_FIRST);
			}
		}
		shape = shape.optimize();
		return shape.isEmpty() ? EMPTY : shape;
	}

	/** A sloped brush, sampled on an 8x8x8 grid inside the cell and merged into runs along X. */
	private static VoxelShape voxelise(BspMap.Brush brush, int x, int y, int z, AABB clip) {
		VoxelShape shape = Shapes.empty();
		double step = 1.0 / SUBCELLS;
		for (int j = 0; j < SUBCELLS; j++) {
			for (int k = 0; k < SUBCELLS; k++) {
				int runStart = -1;
				for (int i = 0; i <= SUBCELLS; i++) {
					boolean inside = false;
					if (i < SUBCELLS) {
						double cx = x + (i + 0.5) * step, cy = y + (j + 0.5) * step, cz = z + (k + 0.5) * step;
						if (clip.contains(cx, cy, cz)) {
							Vec3 src = Units.toSrc(new Vec3(cx, cy, cz));
							inside = brush.containsSrc(src.x, src.y, src.z);
						}
					}
					if (inside && runStart < 0) {
						runStart = i;
					} else if (!inside && runStart >= 0) {
						shape = Shapes.joinUnoptimized(shape,
							Shapes.box(runStart * step, j * step, k * step, i * step, (j + 1) * step, (k + 1) * step), BooleanOp.OR);
						runStart = -1;
					}
				}
			}
		}
		return shape;
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
