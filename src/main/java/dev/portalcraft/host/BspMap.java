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

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Reads the solid brushes out of a Source (VBSP v19-21) map: the world, plus brush entities that
 * stand still and block players (func_brush, func_wall, ...). Each brush is a convex volume: the
 * intersection of its sides' half-spaces. vbsp adds axial bevel planes to every brush, so the
 * six axial sides give its exact bounding box; brushes with any other side are "sloped" and get
 * voxelised by {@link HostCollision}.
 */
public final class BspMap {
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

	/** Brush entities that are solid where the map compiled them. Moving ones come later. */
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
	}

	public final String name;
	public final List<Brush> brushes;

	private BspMap(String name, List<Brush> brushes) {
		this.name = name;
		this.brushes = brushes;
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
		BitSet wanted = new BitSet();
		Map<Integer, double[]> offsets = new HashMap<>();
		for (int m = 0; m < modelCount; m++) {
			BrushEntity entity = entities.get(m);
			if (m != 0 && (entity == null || !SOLID_BRUSH_ENTITIES.contains(entity.classname()))) {
				continue;
			}
			int headNode = b.getInt(lumps[LUMP_MODELS][0] + m * 48 + 36);
			BitSet mine = new BitSet();
			collectBrushes(b, lumps, headNode, version <= 19 ? 56 : 32, mine);
			if (m != 0) {
				for (int i = mine.nextSetBit(0); i >= 0; i = mine.nextSetBit(i + 1)) {
					offsets.put(i, entity.origin());
				}
			}
			wanted.or(mine);
		}

		// Brushes: firstside(i) numsides(i) contents(i). Sides: planenum(u16) texinfo dispinfo bevel (i16 x3).
		List<Brush> out = new ArrayList<>();
		for (int i = wanted.nextSetBit(0); i >= 0; i = wanted.nextSetBit(i + 1)) {
			double[] shift = offsets.getOrDefault(i, NO_SHIFT);
			int o = lumps[LUMP_BRUSHES][0] + i * 12;
			int first = b.getInt(o);
			int count = b.getInt(o + 4);
			int contents = b.getInt(o + 8);
			if ((contents & MASK_PLAYERSOLID) == 0) {
				continue;
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
				continue; // degenerate or unbounded
			}
			Vec3 a = Units.toMc(new Vec3(lo[0], lo[1], lo[2]));
			Vec3 c = Units.toMc(new Vec3(hi[0], hi[1], hi[2]));
			out.add(new Brush(new AABB(a, c), sides, sloped));
		}
		return new BspMap(name, out);
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
