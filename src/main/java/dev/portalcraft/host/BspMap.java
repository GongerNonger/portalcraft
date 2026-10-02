package dev.portalcraft.host;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.portalcraft.PortalCraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the solid brushes out of a Source (VBSP v19-21) map: the world, plus brush entities that
 * stand still and block players (func_brush, func_wall, ...). Each brush is a convex volume: the
 * intersection of its sides' half-spaces. vbsp adds axial bevel planes to every brush, so the
 * six axial sides give its exact bounding box; brushes with any other side are "sloped" and get
 * voxelised by {@link HostCollision}. Solid prop_statics are appended as more convex brushes
 * ({@link StaticProps}), their models read from the game folder the map sits in.
 */
public final class BspMap {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	// bspflags.h
	private static final int CONTENTS_SOLID = 0x1;
	private static final int CONTENTS_WINDOW = 0x2;
	private static final int CONTENTS_GRATE = 0x8;
	private static final int CONTENTS_MOVEABLE = 0x4000;
	private static final int CONTENTS_PLAYERCLIP = 0x10000;
	private static final int MASK_PLAYERSOLID = CONTENTS_SOLID | CONTENTS_WINDOW | CONTENTS_GRATE | CONTENTS_MOVEABLE | CONTENTS_PLAYERCLIP;

	private static final int LUMP_ENTITIES = 0;
	private static final int LUMP_PLANES = 1;
	private static final int LUMP_NODES = 5;
	private static final int LUMP_LEAFS = 10;
	private static final int LUMP_MODELS = 14;
	private static final int LUMP_LEAFBRUSHES = 17;
	private static final int LUMP_BRUSHES = 18;
	private static final int LUMP_BRUSHSIDES = 19;

	/** Brush entities that are solid where the map compiled them: the fallback until the host streams live entities. */
	private static final Set<String> SOLID_BRUSH_ENTITIES = Set.of("func_brush", "func_wall", "func_wall_toggle", "func_detail_blocker");

	/** One convex brush. Planes are Source-space (normal, dist): inside where dot(n, p) <= dist. */
	public record Brush(AABB mcBox, float[] planes, boolean sloped) {
		public boolean containsSrc(double x, double y, double z) {
			for (int i = 0; i < planes.length; i += 4) {
				if (planes[i] * x + planes[i + 1] * y + planes[i + 2] * z > planes[i + 3] + 0.01) {
					return false;
				}
			}
			return true;
		}

		/**
		 * This brush moved from its own space to origin with Source AngleMatrix axes {forward, right,
		 * up} (local x, y, z map to forward, -right, up). The box comes from the brush's corners.
		 */
		public @Nullable Brush transformed(Vec3 origin, Vec3[] axes) {
			Vec3 f = axes[0], r = axes[1], u = axes[2];
			int n = planes.length / 4;
			float[] out = new float[planes.length];
			boolean slopedNow = false;
			for (int i = 0; i < n; i++) {
				double nx = planes[i * 4], ny = planes[i * 4 + 1], nz = planes[i * 4 + 2];
				double wx = f.x * nx - r.x * ny + u.x * nz;
				double wy = f.y * nx - r.y * ny + u.y * nz;
				double wz = f.z * nx - r.z * ny + u.z * nz;
				out[i * 4] = (float) wx;
				out[i * 4 + 1] = (float) wy;
				out[i * 4 + 2] = (float) wz;
				out[i * 4 + 3] = (float) (planes[i * 4 + 3] + wx * origin.x + wy * origin.y + wz * origin.z);
				slopedNow |= Math.abs(wx) < 0.9999 && Math.abs(wy) < 0.9999 && Math.abs(wz) < 0.9999;
			}
			double[] lo = {1e18, 1e18, 1e18};
			double[] hi = {-1e18, -1e18, -1e18};
			int corners = 0;
			for (int a = 0; a < n; a++) {
				for (int b = a + 1; b < n; b++) {
					for (int c = b + 1; c < n; c++) {
						double[] p = intersect(out, a, b, c);
						if (p != null && insideAll(out, p)) {
							corners++;
							for (int k = 0; k < 3; k++) {
								lo[k] = Math.min(lo[k], p[k]);
								hi[k] = Math.max(hi[k], p[k]);
							}
						}
					}
				}
			}
			if (corners < 4) {
				return null;
			}
			Vec3 lowMc = Units.toMc(new Vec3(lo[0], lo[1], lo[2]));
			Vec3 highMc = Units.toMc(new Vec3(hi[0], hi[1], hi[2]));
			return new Brush(new AABB(lowMc, highMc), out, slopedNow);
		}

		private static double @Nullable [] intersect(float[] p, int a, int b, int c) {
			double ax = p[a * 4], ay = p[a * 4 + 1], az = p[a * 4 + 2], ad = p[a * 4 + 3];
			double bx = p[b * 4], by = p[b * 4 + 1], bz = p[b * 4 + 2], bd = p[b * 4 + 3];
			double cx = p[c * 4], cy = p[c * 4 + 1], cz = p[c * 4 + 2], cd = p[c * 4 + 3];
			double det = ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx);
			if (Math.abs(det) < 1e-9) {
				return null;
			}
			// Cramer's rule for n_a.p = d_a, n_b.p = d_b, n_c.p = d_c
			double x = (ad * (by * cz - bz * cy) - ay * (bd * cz - bz * cd) + az * (bd * cy - by * cd)) / det;
			double y = (ax * (bd * cz - bz * cd) - ad * (bx * cz - bz * cx) + az * (bx * cd - bd * cx)) / det;
			double z = (ax * (by * cd - bd * cy) - ay * (bx * cd - bd * cx) + ad * (bx * cy - by * cx)) / det;
			return new double[] {x, y, z};
		}

		private static boolean insideAll(float[] p, double[] q) {
			for (int i = 0; i < p.length; i += 4) {
				if (p[i] * q[0] + p[i + 1] * q[1] + p[i + 2] * q[2] > p[i + 3] + 0.05) {
					return false;
				}
			}
			return true;
		}
	}

	public final String name;
	public final Path file;
	/** The world and static props: never move. */
	public final List<Brush> brushes;
	/** Solid brush entities where the map compiled them; used until the host streams live ones. */
	public final List<Brush> bakedEntityBrushes;
	/** Every brush model ("*N", N >= 1) in its own space, placed by the host's live origin/angles. */
	public final Map<Integer, List<Brush>> models;

	private BspMap(String name, Path file, List<Brush> brushes, List<Brush> bakedEntityBrushes, Map<Integer, List<Brush>> models) {
		this.name = name;
		this.file = file;
		this.brushes = brushes;
		this.bakedEntityBrushes = bakedEntityBrushes;
		this.models = models;
	}

	public static BspMap load(Path file, String name) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != 0x50534256) { // "VBSP"
			throw new IOException(file + " is not a Source BSP");
		}
		int version = b.getInt(4);
		int[][] lumps = new int[64][2];
		for (int i = 0; i < 64; i++) {
			lumps[i][0] = b.getInt(8 + i * 16);
			lumps[i][1] = b.getInt(8 + i * 16 + 4);
		}

		// Planes: normal(3f) dist(f) type(i) = 20 bytes.
		int planeCount = lumps[LUMP_PLANES][1] / 20;
		float[] planes = new float[planeCount * 4];
		for (int i = 0; i < planeCount; i++) {
			int o = lumps[LUMP_PLANES][0] + i * 20;
			for (int k = 0; k < 4; k++) {
				planes[i * 4 + k] = b.getFloat(o + k * 4);
			}
		}

		// Which brushes belong to which model: walk each model's node tree down to its leaves.
		// A brush entity's brushes are stored relative to its "origin" key; the world's are absolute.
		int modelCount = lumps[LUMP_MODELS][1] / 48;
		Map<Integer, BrushEntity> entities = brushEntities(b, lumps[LUMP_ENTITIES]);
		List<Brush> out = new ArrayList<>();
		List<Brush> baked = new ArrayList<>();
		Map<Integer, List<Brush>> models = new HashMap<>();
		int leafSize = version <= 19 ? 56 : 32;
		for (int m = 0; m < modelCount; m++) {
			int headNode = b.getInt(lumps[LUMP_MODELS][0] + m * 48 + 36);
			BitSet mine = new BitSet();
			collectBrushes(b, lumps, headNode, leafSize, mine);
			BrushEntity entity = entities.get(m);
			boolean bakeIt = m != 0 && entity != null && SOLID_BRUSH_ENTITIES.contains(entity.classname());
			List<Brush> local = new ArrayList<>();
			for (int i = mine.nextSetBit(0); i >= 0; i = mine.nextSetBit(i + 1)) {
				Brush brush = readBrush(b, lumps, planes, i, NO_SHIFT);
				if (brush == null) {
					continue;
				}
				if (m == 0) {
					out.add(brush);
				} else {
					local.add(brush);
					if (bakeIt) {
						baked.add(readBrush(b, lumps, planes, i, entity.origin()));
					}
				}
			}
			if (m != 0 && !local.isEmpty()) {
				models.put(m, local);
			}
		}
		try {
			out.addAll(StaticProps.load(b, GameFiles.forMap(file)).brushes());
		} catch (IOException | RuntimeException e) {
			LOG.warn("PortalCraft: no static props for {} ({})", name, e.toString());
		}
		return new BspMap(name, file, out, baked, models);
	}

	/** Brush i from the brush lump, shifted by `shift` (Source units); null if not player-solid. */
	private static @Nullable Brush readBrush(ByteBuffer b, int[][] lumps, float[] planes, int i, double[] shift) {
		int o = lumps[LUMP_BRUSHES][0] + i * 12;
		int first = b.getInt(o);
		int count = b.getInt(o + 4);
		int contents = b.getInt(o + 8);
		if ((contents & MASK_PLAYERSOLID) == 0) {
			return null;
		}
		double[] lo = {-1e9, -1e9, -1e9};
		double[] hi = {1e9, 1e9, 1e9};
		float[] sides = new float[count * 4];
		boolean sloped = false;
		for (int s = 0; s < count; s++) {
			int planeIndex = b.getShort(lumps[LUMP_BRUSHSIDES][0] + (first + s) * 8) & 0xFFFF;
			float nx = planes[planeIndex * 4], ny = planes[planeIndex * 4 + 1], nz = planes[planeIndex * 4 + 2];
			float d = (float) (planes[planeIndex * 4 + 3] + nx * shift[0] + ny * shift[1] + nz * shift[2]);
			sides[s * 4] = nx;
			sides[s * 4 + 1] = ny;
			sides[s * 4 + 2] = nz;
			sides[s * 4 + 3] = d;
			int axis = Math.abs(nx) > 0.9999f ? 0 : Math.abs(ny) > 0.9999f ? 1 : Math.abs(nz) > 0.9999f ? 2 : -1;
			if (axis < 0) {
				sloped = true;
				continue;
			}
			float sign = axis == 0 ? nx : axis == 1 ? ny : nz;
			if (sign > 0) {
				hi[axis] = Math.min(hi[axis], d);
			} else {
				lo[axis] = Math.max(lo[axis], -d);
			}
		}
		if (lo[0] >= hi[0] || lo[1] >= hi[1] || lo[2] >= hi[2] || hi[0] - lo[0] > 32768) {
			return null; // degenerate or unbounded
		}
		Vec3 a = Units.toMc(new Vec3(lo[0], lo[1], lo[2]));
		Vec3 c = Units.toMc(new Vec3(hi[0], hi[1], hi[2]));
		return new Brush(new AABB(a, c), sides, sloped);
	}

	private static void collectBrushes(ByteBuffer b, int[][] lumps, int node, int leafSize, BitSet out) {
		ArrayList<Integer> stack = new ArrayList<>();
		stack.add(node);
		int nodeCount = lumps[LUMP_NODES][1] / 32;
		int leafCount = lumps[LUMP_LEAFS][1] / leafSize;
		BitSet seenNodes = new BitSet();
		while (!stack.isEmpty()) {
			int n = stack.removeLast();
			if (n >= 0) {
				if (n >= nodeCount || seenNodes.get(n)) {
					continue;
				}
				seenNodes.set(n);
				int o = lumps[LUMP_NODES][0] + n * 32;
				stack.add(b.getInt(o + 4));
				stack.add(b.getInt(o + 8));
			} else {
				int leaf = -1 - n;
				if (leaf >= leafCount) {
					continue;
				}
				int o = lumps[LUMP_LEAFS][0] + leaf * leafSize;
				int firstLeafBrush = b.getShort(o + 24) & 0xFFFF;
				int numLeafBrushes = b.getShort(o + 26) & 0xFFFF;
				for (int k = 0; k < numLeafBrushes; k++) {
					out.set(b.getShort(lumps[LUMP_LEAFBRUSHES][0] + (firstLeafBrush + k) * 2) & 0xFFFF);
				}
			}
		}
	}

	private static final double[] NO_SHIFT = {0, 0, 0};

	private record BrushEntity(String classname, double[] origin) {
	}

	/** "model" "*N" -> the entity using it, from the entity lump's text. */
	private static Map<Integer, BrushEntity> brushEntities(ByteBuffer b, int[] lump) {
		byte[] raw = new byte[lump[1]];
		b.get(lump[0], raw);
		String text = new String(raw, StandardCharsets.ISO_8859_1);
		Map<Integer, BrushEntity> out = new HashMap<>();
		int i = 0;
		while ((i = text.indexOf('{', i)) >= 0) {
			int end = text.indexOf('}', i);
			if (end < 0) {
				break;
			}
			String block = text.substring(i + 1, end);
			String model = value(block, "model");
			String cls = value(block, "classname");
			if (model != null && model.startsWith("*") && cls != null) {
				try {
					out.put(Integer.parseInt(model.substring(1)), new BrushEntity(cls, parseVector(value(block, "origin"))));
				} catch (NumberFormatException ignored) {
				}
			}
			i = end + 1;
		}
		return out;
	}

	private static double[] parseVector(String text) {
		if (text == null) {
			return NO_SHIFT;
		}
		String[] parts = text.trim().split("[ \t]+");
		if (parts.length != 3) {
			return NO_SHIFT;
		}
		return new double[] {Double.parseDouble(parts[0]), Double.parseDouble(parts[1]), Double.parseDouble(parts[2])};
	}

	private static String value(String block, String key) {
		String needle = "\"" + key + "\" \"";
		int k = block.indexOf(needle);
		if (k < 0) {
			return null;
		}
		int start = k + needle.length();
		int end = block.indexOf('"', start);
		return end < 0 ? null : block.substring(start, end);
	}
}
