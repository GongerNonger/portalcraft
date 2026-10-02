package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * How far Minecraft's floor (HostCollision's shapes) is from the map's real floor (the exact brush
 * planes, props included), in Source units. Mostly analysis: it prints the error distribution, the
 * worst brushes, and what a walking player's feet do, and asserts only that flat floors match and
 * that walking the start of the first chamber doesn't bob. Skipped when Portal isn't installed.
 */
class FloorAccuracyTest {
	private static final Path MAPS = Path.of("D:/SteamLibrary/steamapps/common/Portal/portal/maps");
	private static final double PLAYER_HEIGHT = 72.0;
	/** Minecraft's player is 0.6 blocks wide: 12 units each side of its centre. */
	private static final double HALF_WIDTH = 12.0;
	private static final double STEP = 0.6;
	private static final double CELL = 32.0;
	/** Errors at or under this count as a match: well below anything visible. */
	private static final double MATCH = 0.1;
	/**
	 * Sloped brushes are cut into 1/16-block columns, so their sides are only right to within
	 * half a column (1.25 units). Points this close to a rim, and feet whose footprint is this close
	 * to an edge, are judged against everything within that slack.
	 */
	private static final double EDGE = 1.5;

	/** One map's brushes, indexed by 32-unit Source columns, and installed as HostCollision's map. */
	private record Ctx(BspMap map, Set<BspMap.Brush> props, Map<Long, List<BspMap.Brush>> columns, Map<BspMap.Brush, Integer> ids,
		Map<BspMap.Brush, double[]> bounds) {
	}

	private static Ctx ctx;

	private static Ctx use(String name) throws Exception {
		Path file = MAPS.resolve(name + ".bsp");
		assumeTrue(Files.exists(file), "Portal not installed");
		if (ctx != null && ctx.map.name.equals(name)) {
			return ctx;
		}
		BspMap map = BspMap.load(file, name);
		ByteBuffer bsp = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		int propCount = StaticProps.load(bsp, GameFiles.forMap(file)).brushes().size();
		Set<BspMap.Brush> props = Collections.newSetFromMap(new IdentityHashMap<>());
		props.addAll(map.brushes.subList(map.brushes.size() - propCount, map.brushes.size()));
		Map<BspMap.Brush, Integer> ids = new IdentityHashMap<>();
		Map<BspMap.Brush, double[]> bounds = new IdentityHashMap<>();
		Map<Long, List<BspMap.Brush>> columns = new HashMap<>();
		for (int i = 0; i < map.brushes.size(); i++) {
			BspMap.Brush b = map.brushes.get(i);
			ids.put(b, i);
			double[] lo = src(b, true), hi = src(b, false);
			bounds.put(b, new double[] {lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]});
			if (hi[0] - lo[0] > 8192 || hi[1] - lo[1] > 8192) {
				continue;
			}
			for (int cx = (int) Math.floor(lo[0] / CELL); cx <= (int) Math.floor(hi[0] / CELL); cx++) {
				for (int cy = (int) Math.floor(lo[1] / CELL); cy <= (int) Math.floor(hi[1] / CELL); cy++) {
					columns.computeIfAbsent(key(cx, cy), k -> new ArrayList<>()).add(b);
				}
			}
		}
		HostCollision.setMap(map);
		ctx = new Ctx(map, props, columns, ids, bounds);
		return ctx;
	}

	@AfterAll
	static void unload() {
		HostCollision.clear();
		ctx = null;
	}

	// ---------------------------------------------------------------- every walkable column of a map

	@Test
	void firstChamberFloorsMatch() throws Exception {
		Survey s = survey(use("testchmb_a_00"), 4.0, true);
		// Every flat floor, whatever made it (axial or sloped brush, prop hull), is where it really is.
		for (var e : s.byKind.entrySet()) {
			if (e.getKey().endsWith("flat top")) {
				assertTrue(e.getValue().matched() >= 0.99, e.getKey() + ": only " + e.getValue().matched() + " within " + MATCH + " units");
			}
		}
		assertTrue(s.byKind.get(BAND).matched() >= 0.98, "the vault and chamber floor");
	}

	/** How much of other maps' walkable floor is truly sloped, and how well it all matches. */
	@Test
	void otherMapsFloors() throws Exception {
		for (String name : List.of("testchmb_a_08", "escape_00", "escape_02")) {
			Survey s = survey(use(name), 8.0, false);
			for (var e : s.byKind.entrySet()) {
				if (e.getKey().endsWith("flat top")) {
					assertTrue(e.getValue().matched() >= 0.98, name + " " + e.getKey() + ": only " + e.getValue().matched());
				}
			}
		}
	}

	private static final String BAND = "~ z 120..170 (vault, chamber)";

	private static final class Hist {
		final int[] n = new int[BUCKETS.length + 1];
		int holes;

		void add(double d) {
			n[bucket(Math.abs(d))]++;
		}

		int total() {
			int t = 0;
			for (int v : n) {
				t += v;
			}
			return t;
		}

		double matched() {
			return total() == 0 ? 1.0 : (double) n[0] / total();
		}
	}

	private record Survey(Map<String, Hist> byKind) {
	}

	/**
	 * Walkable surfaces on a grid of columns: the exact top of the solid below 72 units of air, vs
	 * the highest top of Minecraft's shapes at that point within 40 below to 20 above it.
	 */
	private static Survey survey(Ctx c, double spacing, boolean details) {
		double[] lo = {1e9, 1e9}, hi = {-1e9, -1e9};
		for (BspMap.Brush b : c.map.brushes) {
			double[] a = src(b, true), z = src(b, false);
			if (z[0] - a[0] > 8192) {
				continue;
			}
			lo[0] = Math.min(lo[0], a[0]);
			lo[1] = Math.min(lo[1], a[1]);
			hi[0] = Math.max(hi[0], z[0]);
			hi[1] = Math.max(hi[1], z[1]);
		}
		Hist all = new Hist();
		Map<String, Hist> byKind = new TreeMap<>();
		Map<Integer, double[]> worst = new HashMap<>(); // brush -> {count, worst d, sum d, x, y, z}
		List<Double> ds = new ArrayList<>();
		int[] steep = new int[4]; // top normal z: > 0.9999, > 0.98, > 0.9, else
		for (double x = Math.floor(lo[0] / spacing) * spacing + 1.3; x < hi[0]; x += spacing) {
			for (double y = Math.floor(lo[1] / spacing) * spacing + 1.7; y < hi[1]; y += spacing) {
				for (Surface s : surfaces(c, x, y)) {
					steep[s.nz > 0.9999 ? 0 : s.nz > 0.98 ? 1 : s.nz > 0.9 ? 2 : 3]++;
					double mc = mcTop(x, y, s.z - 40, s.z + 20);
					String kind = interior(c, x, y, s) ? kind(c, s.brush) + (s.nz > 0.9999 ? " flat top" : " sloped top") : "(within 1.5 of a rim)";
					List<Hist> hs = new ArrayList<>(List.of(all, byKind.computeIfAbsent(kind, k -> new Hist())));
					if (s.z >= 120 && s.z <= 170 && details) {
						hs.add(byKind.computeIfAbsent(BAND, k -> new Hist()));
					}
					if (Double.isNaN(mc)) {
						hs.forEach(h -> h.holes++);
						continue;
					}
					double d = mc - s.z;
					ds.add(d);
					hs.forEach(h -> h.add(d));
					if (Math.abs(d) >= 0.5 && !kind.startsWith("(")) {
						double[] w = worst.computeIfAbsent(c.ids.get(s.brush), q -> new double[6]);
						w[0]++;
						w[2] += d;
						if (Math.abs(d) > Math.abs(w[1])) {
							w[1] = d;
							w[3] = x;
							w[4] = y;
							w[5] = s.z;
						}
					}
				}
			}
		}
		int total = steep[0] + steep[1] + steep[2] + steep[3];
		System.out.printf(Locale.ROOT, "%n== %s: walkable columns every %.0f units: %d surfaces, %d with no Minecraft floor within -40..+20%n", c.map.name, spacing,
			total, all.holes);
		System.out.printf(Locale.ROOT, "  top normal z > 0.9999: %.1f%%, 0.98..0.9999: %.1f%%, 0.9..0.98: %.1f%%, 0.7..0.9: %.1f%%%n", 100.0 * steep[0] / total,
			100.0 * steep[1] / total, 100.0 * steep[2] / total, 100.0 * steep[3] / total);
		printHist("all", all);
		byKind.forEach(FloorAccuracyTest::printHist);
		Collections.sort(ds);
		if (!ds.isEmpty()) {
			System.out.printf(Locale.ROOT, "  mc - true: min %.2f  p1 %.2f  median %.2f  p99 %.2f  max %.2f%n", ds.get(0), ds.get(ds.size() / 100),
				ds.get(ds.size() / 2), ds.get(ds.size() * 99 / 100), ds.get(ds.size() - 1));
		}
		if (details) {
			System.out.println("  worst brushes away from rims (|d| >= 0.5), by columns affected:");
			worst.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue()[0], a.getValue()[0])).limit(12).forEach(e -> {
				BspMap.Brush b = c.map.brushes.get(e.getKey());
				double[] w = e.getValue();
				System.out.printf(Locale.ROOT, "  #%d %s n=%d mean=%+.2f worst=%+.2f at (%.1f %.1f %.1f) box %s%n", e.getKey(), kind(c, b), (int) w[0],
					w[2] / w[0], w[1], w[3], w[4], w[5], box(b));
			});
		}
		return new Survey(byKind);
	}

	// ---------------------------------------------------------------- build cost

	/** Every cell a sloped brush touches, built from cold, twice (the second time warmed up). */
	@Test
	void slopedCellsBuildQuickly() throws Exception {
		Ctx c = use("testchmb_a_00");
		Set<Long> cells = new HashSet<>();
		for (BspMap.Brush b : c.map.brushes) {
			AABB m = b.mcBox();
			if (!b.sloped() || m.getXsize() * m.getYsize() * m.getZsize() > 20_000) {
				continue;
			}
			for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(m.minX, m.minY, m.minZ), BlockPos.containing(m.maxX, m.maxY, m.maxZ))) {
				cells.add(pos.asLong());
			}
		}
		double worstMs = 0;
		for (int pass = 0; pass < 2; pass++) {
			HostCollision.setMap(c.map); // drops the cache
			long worst = 0, t0 = System.nanoTime();
			int boxes = 0, most = 0;
			for (long cell : cells) {
				long t = System.nanoTime();
				VoxelShape s = HostCollision.shapeAt(BlockPos.of(cell));
				worst = Math.max(worst, System.nanoTime() - t);
				int n = s == null ? 0 : s.toAabbs().size();
				boxes += n;
				most = Math.max(most, n);
			}
			worstMs = worst / 1e6;
			System.out.printf(Locale.ROOT, "%n== pass %d: %d cells with sloped brushes built in %d ms (worst cell %.2f ms); %d boxes, at most %d in a cell%n", pass,
				cells.size(), (System.nanoTime() - t0) / 1_000_000, worstMs, boxes, most);
		}
		assertTrue(worstMs < 20, "a cell took " + worstMs + " ms");
	}

	// ---------------------------------------------------------------- walking: the player's box

	@Test
	void walkingFromTheSpawn() throws Exception {
		Ctx c = use("testchmb_a_00");
		Vec3[] starts = {
			new Vec3(-607.8, -346.7, 162), // spawn, in the relaxation vault
			new Vec3(-544.3, -369.3, 162), // the bed
			new Vec3(-400.3, -200.3, 130), // the chamber floor
			new Vec3(-300.3, -400.3, 130),
			new Vec3(-768.3, -128.3, 130), // a chamfered floor panel (#74)
			new Vec3(-450.3, -1054.3, 130), // another (#35)
		};
		double worst = 0;
		for (Vec3 start : starts) {
			for (int dir = 0; dir < 8; dir++) {
				double a = dir * Math.PI / 4;
				Walk w = walk(c, start, Math.cos(a), Math.sin(a), 120, 0.05);
				if (w == null) {
					continue;
				}
				worst = Math.max(worst, w.spurious);
				System.out.printf(Locale.ROOT, "walk from (%.0f %.0f) dir %3d: %3d steps, feet %.2f..%.2f, |mc-true| max %.2f mean %.2f, spurious travel %.2f, %d height changes%n",
					start.x, start.y, dir * 45, w.steps, w.minFeet, w.maxFeet, w.maxErr, w.meanErr, w.spurious, w.changes);
				if (w.spurious >= 0.5) {
					System.out.println("    " + w.trace);
				}
			}
		}
		System.out.printf(Locale.ROOT, "worst spurious vertical travel on these walks: %.2f units%n", worst);
		assertTrue(worst < 0.5, "the feet bob by " + worst + " units walking the vault and chamber");
	}

	/** A grid of start points over the chamber floor (z 60..200), walked 2 blocks along +X and +Y. */
	@Test
	void walkingTheChamberFloor() throws Exception {
		Ctx c = use("testchmb_a_00");
		List<Double> spurious = new ArrayList<>();
		double worst = 0;
		String worstTrace = "";
		for (double x = -1500.3; x <= 400; x += 37) {
			for (double y = -1300.3; y <= 400; y += 37) {
				for (Surface s : surfaces(c, x, y)) {
					if (s.z < 60 || s.z > 200) {
						continue;
					}
					for (int dir = 0; dir < 2; dir++) {
						Walk w = walk(c, new Vec3(x, y, s.z + 2), dir == 0 ? 1 : 0, dir == 0 ? 0 : 1, 40, 0.05);
						if (w == null || w.steps < 10) {
							continue;
						}
						spurious.add(w.spurious);
						if (w.spurious > worst) {
							worst = w.spurious;
							worstTrace = String.format(Locale.ROOT, "(%.0f %.0f %.1f) %s: max err %.2f %s", x, y, s.z, dir == 0 ? "+x" : "+y", w.maxErr, w.trace);
						}
					}
				}
			}
		}
		Collections.sort(spurious);
		long bobbing = spurious.stream().filter(v -> v >= 0.5).count();
		System.out.printf(Locale.ROOT, "%n== chamber walks: %d walks of 2 blocks; spurious travel median %.2f, p90 %.2f, p99 %.2f, max %.2f; %d walks >= 0.5 units%n  worst %s%n",
			spurious.size(), spurious.get(spurious.size() / 2), spurious.get(spurious.size() * 9 / 10), spurious.get(spurious.size() * 99 / 100), worst, bobbing, worstTrace);
		// The voxel grid's worst was 17.6 (a missed hull); its chamfered panels gave 4 per crossing.
		assertTrue(worst < 1.5, "a chamber walk bobbed " + worst + " units");
	}

	/** Up and down the steepest direction of truly sloped floors (top normal z < 0.9999), 1.5 blocks each way. */
	@Test
	void walkingSlopes() throws Exception {
		for (String name : List.of("testchmb_a_00", "escape_00")) {
			Ctx c = use(name);
			List<Surface> starts = new ArrayList<>();
			List<double[]> at = new ArrayList<>();
			double[] lo = {1e9, 1e9}, hi = {-1e9, -1e9};
			for (BspMap.Brush b : c.map.brushes) {
				double[] a = src(b, true), z = src(b, false);
				if (z[0] - a[0] < 8192) {
					lo[0] = Math.min(lo[0], a[0]);
					lo[1] = Math.min(lo[1], a[1]);
					hi[0] = Math.max(hi[0], z[0]);
					hi[1] = Math.max(hi[1], z[1]);
				}
			}
			for (double x = lo[0] + 3.1; x < hi[0]; x += 16) {
				for (double y = lo[1] + 5.3; y < hi[1]; y += 16) {
					for (Surface s : surfaces(c, x, y)) {
						if (s.nz < 0.9999 && interior(c, x, y, s)) {
							starts.add(s);
							at.add(new double[] {x, y});
						}
					}
				}
			}
			List<Double> spurious = new ArrayList<>();
			List<Double> errs = new ArrayList<>();
			int stride = Math.max(1, starts.size() / 120);
			for (int i = 0; i < starts.size(); i += stride) {
				Surface s = starts.get(i);
				double gx = s.nx, gy = s.ny, g = Math.hypot(gx, gy);
				for (int sign = -1; sign <= 1; sign += 2) {
					Walk w = walk(c, new Vec3(at.get(i)[0], at.get(i)[1], s.z + 2), sign * gx / g, sign * gy / g, 30, 0.05);
					if (w != null && w.steps >= 5) {
						spurious.add(w.spurious / w.steps);
						errs.add(w.maxErr);
					}
				}
			}
			Collections.sort(spurious);
			Collections.sort(errs);
			if (spurious.isEmpty()) {
				continue;
			}
			System.out.printf(Locale.ROOT, "%n== %s slope walks: %d (from %d sloped columns); max |err| median %.2f p90 %.2f max %.2f; spurious per tick median %.3f p90 %.3f max %.3f units%n",
				name, spurious.size(), starts.size(), errs.get(errs.size() / 2), errs.get(errs.size() * 9 / 10), errs.get(errs.size() - 1),
				spurious.get(spurious.size() / 2), spurious.get(spurious.size() * 9 / 10), spurious.get(spurious.size() - 1));
			// The voxel grid gave a median 1.4-2 units off and bobbed 0.05-0.44 units a tick.
			assertTrue(errs.get(errs.size() - 1) < 1.0, name + ": feet " + errs.get(errs.size() - 1) + " units off a slope");
			assertTrue(spurious.get(spurious.size() * 9 / 10) < 0.05, name + ": feet bob " + spurious.get(spurious.size() * 9 / 10) + " units a tick on slopes");
		}
	}

	/**
	 * err: how far the feet are from any height the true floor allows (see EDGE). spurious: how much
	 * that error changes over the walk, i.e. up-and-down motion the floor doesn't have; walking
	 * across a panel 2 units too high counts 4.
	 */
	private record Walk(int steps, double minFeet, double maxFeet, double maxErr, double meanErr, double spurious, int changes, String trace) {
	}

	/**
	 * Drops Minecraft's player box at start, then walks it with vanilla's collide() (step-up included),
	 * comparing its feet each grounded tick with the highest true surface under its 24x24-unit footprint.
	 */
	private static Walk walk(Ctx c, Vec3 startSrc, double dx, double dy, int steps, double blocksPerTick) {
		Vec3 feet = Units.toMc(startSrc);
		AABB box = new AABB(feet.x - 0.3, feet.y + 0.5, feet.z - 0.3, feet.x + 0.3, feet.y + 2.3, feet.z + 0.3);
		AABB inner = box.inflate(-1e-4);
		if (!colliders(inner).stream().allMatch(s -> s.toAabbs().stream().noneMatch(inner::intersects))) {
			return null; // starts inside something
		}
		double drop = Shapes.collide(Direction.Axis.Y, box, colliders(box.expandTowards(0, -1.5, 0)), -1.5);
		if (drop <= -1.5 + 1e-6) {
			return null;
		}
		box = box.move(0, drop, 0);
		Vec3 move = Units.toMc(new Vec3(dx, dy, 0)).normalize().scale(blocksPerTick);
		boolean onGround = true;
		double minF = 1e9, maxF = -1e9, maxErr = 0, sumErr = 0, spurious = 0;
		double lastMc = Double.NaN, lastTrue = Double.NaN, lastErr = 0;
		int n = 0, changes = 0;
		StringBuilder trace = new StringBuilder();
		for (int i = 0; i < steps; i++) {
			Vec3 m = new Vec3(move.x, -0.0784, move.z);
			Vec3 before = Units.toSrc(new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2));
			Vec3 r = collide(box, m, onGround);
			if (r.horizontalDistanceSqr() < 0.25 * move.horizontalDistanceSqr()) {
				break; // walked into a wall
			}
			onGround = r.y != m.y && m.y < 0;
			box = box.move(r);
			if (!onGround) {
				continue; // mid-step or falling; compare once landed
			}
			Vec3 p = Units.toSrc(new Vec3((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2));
			double[] here = trueTops(c, p.x, p.y, p.z + 20);
			double t = here[1];
			if (Double.isNaN(t)) {
				break;
			}
			// Anything the box rests on with its edges up to EDGE further in or out is a fair height; so
			// is where it was a tick ago, since vanilla resolves Y before X and Z (stepping off a ledge,
			// the feet stay up for one tick).
			double lo = t, hi = t;
			for (double[] q : new double[][] {here, trueTops(c, before.x, before.y, p.z + 20)}) {
				lo = Double.isNaN(q[0]) ? lo : Math.min(lo, q[0]);
				hi = Double.isNaN(q[2]) ? hi : Math.max(hi, q[2]);
			}
			double err = p.z < lo ? p.z - lo : p.z > hi ? p.z - hi : 0.0;
			n++;
			minF = Math.min(minF, p.z);
			maxF = Math.max(maxF, p.z);
			maxErr = Math.max(maxErr, Math.abs(err));
			sumErr += Math.abs(err);
			if (!Double.isNaN(lastMc)) {
				spurious += Math.abs(err - lastErr);
				if (Math.abs(p.z - lastMc) > 1e-3) {
					changes++;
				}
			}
			if (trace.length() < 900 && (Double.isNaN(lastMc) || Math.abs(p.z - lastMc) > 1e-3 || Math.abs(t - lastTrue) > 1e-3)) {
				trace.append(String.format(Locale.ROOT, "[%d mc %.2f true %.2f] ", i, p.z, t));
			}
			lastMc = p.z;
			lastTrue = t;
			lastErr = err;
		}
		if (n == 0) {
			return null;
		}
		return new Walk(n, minF, maxF, maxErr, sumErr / n, spurious, changes, trace.toString());
	}

	/** Entity.collide, minus entities and the world border. */
	private static Vec3 collide(AABB aabb, Vec3 movement, boolean onGround) {
		Vec3 step = collideWithShapes(movement, aabb, colliders(aabb.expandTowards(movement)));
		boolean xC = movement.x != step.x, yC = movement.y != step.y, zC = movement.z != step.z;
		boolean landed = yC && movement.y < 0.0;
		if ((landed || onGround) && (xC || zC)) {
			AABB grounded = landed ? aabb.move(0.0, step.y, 0.0) : aabb;
			AABB up = grounded.expandTowards(movement.x, STEP, movement.z);
			if (!landed) {
				up = up.expandTowards(0.0, -1.0E-5F, 0.0);
			}
			List<VoxelShape> colliders = colliders(up);
			float skip = (float) step.y;
			TreeSet<Float> candidates = new TreeSet<>();
			for (VoxelShape c : colliders) {
				for (double coord : c.getCoords(Direction.Axis.Y)) {
					float rel = (float) (coord - grounded.minY);
					if (!(rel < 0.0F) && rel != skip) {
						if (rel > STEP) {
							break;
						}
						candidates.add(rel);
					}
				}
			}
			for (float h : candidates) {
				Vec3 s = collideWithShapes(new Vec3(movement.x, h, movement.z), grounded, colliders);
				if (s.horizontalDistanceSqr() > step.horizontalDistanceSqr()) {
					return s.subtract(0.0, aabb.minY - grounded.minY, 0.0);
				}
			}
		}
		return step;
	}

	private static Vec3 collideWithShapes(Vec3 movement, AABB box, List<VoxelShape> shapes) {
		Vec3 done = Vec3.ZERO;
		for (Direction.Axis axis : Direction.axisStepOrder(movement)) {
			double d = movement.get(axis);
			if (d != 0.0) {
				done = done.with(axis, Shapes.collide(axis, box.move(done), shapes, d));
			}
		}
		return done;
	}

	/** What BlockCollisions would hand vanilla for this box: host shapes of every touched cell. */
	private static List<VoxelShape> colliders(AABB box) {
		List<VoxelShape> out = new ArrayList<>();
		int x0 = (int) Math.floor(box.minX - 1e-7), x1 = (int) Math.floor(box.maxX + 1e-7);
		int y0 = (int) Math.floor(box.minY - 1e-7) - 1, y1 = (int) Math.floor(box.maxY + 1e-7);
		int z0 = (int) Math.floor(box.minZ - 1e-7), z1 = (int) Math.floor(box.maxZ + 1e-7);
		for (int x = x0; x <= x1; x++) {
			for (int y = y0; y <= y1; y++) {
				for (int z = z0; z <= z1; z++) {
					BlockPos pos = new BlockPos(x, y, z);
					VoxelShape s = HostCollision.shapeAt(pos);
					if (s != null) {
						out.add(s.move(pos));
					}
				}
			}
		}
		return out;
	}

	// ---------------------------------------------------------------- the true geometry

	/** A walkable top and its plane's normal. */
	private record Surface(double z, BspMap.Brush brush, double nx, double ny, double nz) {
	}

	/** No rim within EDGE: the same surface (same brush, or the same height) continues that far each way. */
	private static boolean interior(Ctx c, double x, double y, Surface s) {
		double[][] offsets = {{EDGE, 0}, {-EDGE, 0}, {0, EDGE}, {0, -EDGE}};
		for (double[] o : offsets) {
			boolean same = false;
			for (Surface n : surfaces(c, x + o[0], y + o[1])) {
				same |= Math.abs(n.z - s.z) < 3 && (n.brush == s.brush || Math.abs(n.z - s.z) < 0.01);
			}
			if (!same) {
				return false;
			}
		}
		return true;
	}

	/** Walkable tops in a column: solid below, 72 units of air above, a ceiling somewhere above that. */
	private static List<Surface> surfaces(Ctx c, double x, double y) {
		List<double[]> iv = new ArrayList<>(); // lo, hi, brush id, top nz, nx, ny
		for (BspMap.Brush b : c.columns.getOrDefault(key((int) Math.floor(x / CELL), (int) Math.floor(y / CELL)), List.of())) {
			double[] r = ray(b, x, y);
			if (r != null) {
				iv.add(new double[] {r[0], r[1], c.ids.get(b), r[2], r[3], r[4]});
			}
		}
		iv.sort((a, b) -> Double.compare(a[0], b[0]));
		List<double[]> merged = new ArrayList<>();
		for (double[] r : iv) {
			double[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
			if (last != null && r[0] <= last[1] + 0.05) {
				if (r[1] > last[1]) {
					System.arraycopy(r, 1, last, 1, r.length - 1);
				}
			} else {
				merged.add(r.clone());
			}
		}
		List<Surface> out = new ArrayList<>();
		for (int i = 0; i + 1 < merged.size(); i++) {
			double[] r = merged.get(i);
			if (merged.get(i + 1)[0] - r[1] >= PLAYER_HEIGHT && r[3] >= 0.7) {
				out.add(new Surface(r[1], c.map.brushes.get((int) r[2]), r[4], r[5], r[3]));
			}
		}
		return out;
	}

	/** The vertical line through (x, y) inside a brush: {zlo, zhi, top plane's nz, nx, ny}, or null. */
	private static double[] ray(BspMap.Brush b, double x, double y) {
		float[] p = b.planes();
		double lo = -1e9, hi = 1e9, topNz = 0, topNx = 0, topNy = 0;
		for (int i = 0; i < p.length; i += 4) {
			double a = p[i] * x + p[i + 1] * y, d = p[i + 3], nz = p[i + 2];
			if (Math.abs(nz) < 1e-6) {
				if (a > d + 1e-3) {
					return null;
				}
			} else if (nz > 0) {
				double z = (d - a) / nz;
				if (z < hi) {
					hi = z;
					topNz = nz;
					topNx = p[i];
					topNy = p[i + 1];
				}
			} else {
				lo = Math.max(lo, (d - a) / nz);
			}
		}
		return lo < hi - 1e-3 ? new double[] {lo, hi, topNz, topNx, topNy} : null;
	}

	private static final double[] HALF_WIDTHS = {HALF_WIDTH - EDGE, HALF_WIDTH, HALF_WIDTH + EDGE};
	/** Sample offsets every 0.5 units, plus each footprint's own edges (shrunk by 1e-4: Minecraft counts any overlap over 1e-7 blocks). */
	private static final double[] OFFSETS;

	static {
		TreeSet<Double> o = new TreeSet<>();
		double max = HALF_WIDTHS[2] - 1e-4;
		for (double v = -Math.floor(max * 2) / 2; v <= max; v += 0.5) {
			o.add(v);
		}
		for (double h : HALF_WIDTHS) {
			o.add(h - 1e-4);
			o.add(-(h - 1e-4));
		}
		OFFSETS = o.stream().mapToDouble(Double::doubleValue).toArray();
	}

	/**
	 * Where a square box centred on (cx, cy) comes to rest dropping from below `start`: the max true
	 * top under it, sampled every 0.5 units, for the footprints HALF_WIDTH - EDGE, HALF_WIDTH and
	 * HALF_WIDTH + EDGE. NaN where there's nothing.
	 */
	private static double[] trueTops(Ctx c, double cx, double cy, double start) {
		double[] best = {Double.NaN, Double.NaN, Double.NaN};
		double w = HALF_WIDTHS[2];
		Set<BspMap.Brush> near = Collections.newSetFromMap(new IdentityHashMap<>());
		for (int bx = (int) Math.floor((cx - w) / CELL); bx <= (int) Math.floor((cx + w) / CELL); bx++) {
			for (int by = (int) Math.floor((cy - w) / CELL); by <= (int) Math.floor((cy + w) / CELL); by++) {
				near.addAll(c.columns.getOrDefault(key(bx, by), List.of()));
			}
		}
		for (BspMap.Brush b : near) {
			double[] k = c.bounds.get(b);
			if (k[3] < cx - w || k[0] > cx + w || k[4] < cy - w || k[1] > cy + w || k[2] > start) {
				continue;
			}
			for (double ox : OFFSETS) {
				double x = cx + ox;
				if (x < k[0] || x > k[3]) {
					continue;
				}
				for (double oy : OFFSETS) {
					double y = cy + oy;
					if (y < k[1] || y > k[4]) {
						continue;
					}
					double[] r = ray(b, x, y);
					if (r == null || r[1] > start) {
						continue;
					}
					double reach = Math.max(Math.abs(ox), Math.abs(oy)) + 1e-4;
					for (int j = 0; j < 3; j++) {
						if (reach <= HALF_WIDTHS[j] + 1e-9 && (Double.isNaN(best[j]) || r[1] > best[j])) {
							best[j] = r[1];
						}
					}
				}
			}
		}
		return best;
	}

	/** Highest top of Minecraft's shapes at the point, between lo and hi (units), or NaN. */
	private static double mcTop(double x, double y, double lo, double hi) {
		Vec3 m = Units.toMc(new Vec3(x, y, 0));
		double ylo = lo / Units.PER_BLOCK, yhi = hi / Units.PER_BLOCK;
		int bx = (int) Math.floor(m.x), bz = (int) Math.floor(m.z);
		double best = Double.NaN;
		for (int by = (int) Math.floor(ylo); by <= (int) Math.floor(yhi); by++) {
			VoxelShape s = HostCollision.shapeAt(new BlockPos(bx, by, bz));
			if (s == null) {
				continue;
			}
			for (AABB a : s.toAabbs()) {
				double top = a.maxY + by;
				if (a.minX + bx < m.x && m.x < a.maxX + bx && a.minZ + bz < m.z && m.z < a.maxZ + bz && top <= yhi && top > ylo
					&& (Double.isNaN(best) || top > best)) {
					best = top;
				}
			}
		}
		return Double.isNaN(best) ? best : best * Units.PER_BLOCK;
	}

	// ---------------------------------------------------------------- reporting

	private static final double[] BUCKETS = {MATCH, 0.5, 1, 2, 3, 5};

	private static int bucket(double d) {
		for (int i = 0; i < BUCKETS.length; i++) {
			if (d <= BUCKETS[i]) {
				return i;
			}
		}
		return BUCKETS.length;
	}

	private static void printHist(String label, Hist h) {
		int n = h.total();
		StringBuilder sb = new StringBuilder(String.format(Locale.ROOT, "  %-36s n=%6d holes=%4d ", label, n, h.holes));
		for (int i = 0; i <= BUCKETS.length; i++) {
			sb.append(String.format(Locale.ROOT, "%s%.1f:%5.1f%% ", i < BUCKETS.length ? "<=" : ">", BUCKETS[Math.min(i, BUCKETS.length - 1)],
				n == 0 ? 0 : 100.0 * h.n[i] / n));
		}
		System.out.println(sb);
	}

	private static String kind(Ctx c, BspMap.Brush b) {
		return (c.props.contains(b) ? "prop" : "world") + (b.sloped() ? "-sloped" : "-axial");
	}

	private static String box(BspMap.Brush b) {
		double[] lo = src(b, true), hi = src(b, false);
		return String.format(Locale.ROOT, "(%.1f %.1f %.1f)..(%.1f %.1f %.1f)", lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
	}

	/** The brush's Source-space bounds. */
	private static double[] src(BspMap.Brush b, boolean min) {
		AABB m = b.mcBox();
		Vec3 a = Units.toSrc(new Vec3(m.minX, m.minY, m.minZ)), c = Units.toSrc(new Vec3(m.maxX, m.maxY, m.maxZ));
		return min ? new double[] {Math.min(a.x, c.x), Math.min(a.y, c.y), Math.min(a.z, c.z)}
			: new double[] {Math.max(a.x, c.x), Math.max(a.y, c.y), Math.max(a.z, c.z)};
	}

	private static long key(int x, int y) {
		return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
	}
}
