package dev.portalcraft.host;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import dev.portalcraft.PortalCraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The map's prop_statics (BSP game lump "sprp") as convex brushes. Like the engine, a solid prop
 * collides with its model's first VCollide solid; a model without one (or whose .phy we can't
 * read) falls back to the studio hull box, which is also what SOLID_BBOX props use. Either way
 * the shape is in the prop's frame, so it's rotated by the prop's angles into an oriented brush.
 * Dynamic props (doors, elevators, cubes) are entities, not in this lump.
 */
public final class StaticProps {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	private static final int LUMP_GAME_LUMP = 35;
	private static final int SPRP = 0x73707270; // 'sprp'
	private static final int GAMELUMP_COMPRESSED = 1;
	private static final int SOLID_NONE = 0;
	private static final int SOLID_VPHYSICS = 6;
	/** IVP stores hulls shrunk by its collision margin and adds it back when colliding. */
	static final double COLLISION_MARGIN = 0.25;
	private static final int IDST = 0x54534449; // "IDST"

	/** One prop_static. Angles are Source (pitch, yaw, roll) in degrees; leaves are the BSP leaves vbsp put it in. */
	public record Prop(String model, Vec3 origin, Vec3 angles, int solid, int flags, int skin, int[] leaves) {
	}

	/** Every prop, the brushes of the solid ones, and how each solid prop got its shape. */
	public record Result(List<Prop> props, List<BspMap.Brush> brushes, int solid, int viaPhy, int viaBox, int failed) {
	}

	record Shape(List<Phy.Hull> hulls, boolean fromPhy) {
	}

	private StaticProps() {
	}

	/** The "sprp" lump's props; empty if the map has none. */
	public static List<Prop> parse(ByteBuffer bsp) throws IOException {
		ByteBuffer b = bsp.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		int lumpOffset = b.getInt(8 + LUMP_GAME_LUMP * 16);
		int lumpLength = b.getInt(8 + LUMP_GAME_LUMP * 16 + 4);
		if (lumpLength < 4) {
			return List.of();
		}
		int count = b.getInt(lumpOffset);
		for (int i = 0; i < count; i++) {
			// dgamelump_t: id(i) flags(u16) version(u16) fileofs(i) filelen(i); fileofs is from the start of the BSP.
			int o = lumpOffset + 4 + i * 16;
			if (b.getInt(o) != SPRP) {
				continue;
			}
			if ((b.getShort(o + 4) & GAMELUMP_COMPRESSED) != 0) {
				throw new IOException("compressed static prop lump");
			}
			return props(b, b.getShort(o + 6) & 0xFFFF, b.getInt(o + 8), b.getInt(o + 12));
		}
		return List.of();
	}

	/** dict(count, char[128]...) leaves(count, u16...) props(count, StaticPropLump_t...). */
	private static List<Prop> props(ByteBuffer b, int version, int offset, int length) throws IOException {
		int end = offset + length;
		int o = offset;
		int dictCount = b.getInt(o);
		o += 4;
		String[] names = new String[dictCount];
		for (int i = 0; i < dictCount; i++, o += 128) {
			int len = 0;
			while (len < 128 && b.get(o + len) != 0) {
				len++;
			}
			byte[] raw = new byte[len];
			b.get(o, raw);
			names[i] = new String(raw, StandardCharsets.ISO_8859_1);
		}
		int leafCount = b.getInt(o);
		int[] leaves = new int[leafCount];
		for (int i = 0; i < leafCount; i++) {
			leaves[i] = b.getShort(o + 4 + i * 2) & 0xFFFF;
		}
		o += 4 + 2 * leafCount;
		int propCount = b.getInt(o);
		o += 4;
		// v4: 56 bytes; v5 adds forcedFadeScale; v6 min/max DX level; v7 a diffuse modulation colour.
		int entrySize = switch (version) {
			case 4 -> 56;
			case 5 -> 60;
			case 6 -> 64;
			case 7 -> 68;
			default -> propCount == 0 ? 0 : (end - o) / propCount;
		};
		if (propCount < 0 || entrySize < 56 || o + (long) propCount * entrySize > end) {
			throw new IOException("static prop lump v" + version + ": " + propCount + " props of " + entrySize + " bytes don't fit");
		}
		List<Prop> out = new ArrayList<>(propCount);
		for (int i = 0; i < propCount; i++, o += entrySize) {
			// origin(3f) angles(3f) propType(u16) firstLeaf(u16) leafCount(u16) solid(u8) flags(u8) skin(i) ...
			Vec3 origin = new Vec3(b.getFloat(o), b.getFloat(o + 4), b.getFloat(o + 8));
			Vec3 angles = new Vec3(b.getFloat(o + 12), b.getFloat(o + 16), b.getFloat(o + 20));
			int type = b.getShort(o + 24) & 0xFFFF;
			if (type >= dictCount) {
				throw new IOException("static prop " + i + " uses model " + type + " of " + dictCount);
			}
			int first = b.getShort(o + 26) & 0xFFFF;
			int count = b.getShort(o + 28) & 0xFFFF;
			if (first + count > leafCount) {
				throw new IOException("static prop " + i + " uses leaves past the leaf array");
			}
			int[] mine = Arrays.copyOfRange(leaves, first, first + count);
			out.add(new Prop(names[type], origin, angles, b.get(o + 30) & 0xFF, b.get(o + 31) & 0xFF, b.getInt(o + 32), mine));
		}
		return out;
	}

	/** Brushes for every solid static prop, reading each model's collision once. */
	public static Result load(ByteBuffer bsp, GameFiles files) throws IOException {
		long t0 = System.nanoTime();
		List<Prop> props = parse(bsp);
		Map<String, @Nullable Shape> shapes = new HashMap<>();
		List<BspMap.Brush> brushes = new ArrayList<>();
		int solid = 0, viaPhy = 0, viaBox = 0, failed = 0;
		for (Prop prop : props) {
			if (prop.solid() == SOLID_NONE) {
				continue;
			}
			solid++;
			String key = prop.solid() == SOLID_VPHYSICS ? prop.model() : "box:" + prop.model();
			Shape shape = shapes.containsKey(key) ? shapes.get(key) : shape(files, prop.model(), prop.solid() == SOLID_VPHYSICS);
			shapes.put(key, shape);
			int before = brushes.size();
			if (shape != null) {
				Vec3[] axes = Units.angleVectors(prop.angles());
				for (Phy.Hull hull : shape.hulls()) {
					BspMap.Brush brush = brush(hull, prop.origin(), axes, shape.fromPhy() ? COLLISION_MARGIN : 0.0);
					if (brush != null) {
						brushes.add(brush);
					}
				}
			}
			if (shape == null || brushes.size() == before) {
				failed++;
			} else if (shape.fromPhy()) {
				viaPhy++;
			} else {
				viaBox++;
			}
		}
		LOG.info("PortalCraft: {} static props, {} solid: {} via .phy, {} via hull box, {} failed; {} brushes from {} models in {} ms",
			props.size(), solid, viaPhy, viaBox, failed, brushes.size(), shapes.size(), (System.nanoTime() - t0) / 1_000_000);
		return new Result(props, brushes, solid, viaPhy, viaBox, failed);
	}

	static @Nullable Shape shape(GameFiles files, String model, boolean vphysics) {
		String base = model.endsWith(".mdl") ? model.substring(0, model.length() - 4) : model;
		if (vphysics) {
			try {
				byte[] phy = files.read(base + ".phy");
				if (phy != null) {
					return new Shape(Phy.firstSolid(phy), true);
				}
				LOG.debug("PortalCraft: {} has no .phy; using its hull box", model);
			} catch (IOException | RuntimeException e) {
				LOG.warn("PortalCraft: can't read {}.phy ({}); using its hull box", base, e.toString());
			}
		}
		try {
			byte[] mdl = files.read(base + ".mdl");
			if (mdl == null) {
				LOG.warn("PortalCraft: static prop model {} not found", model);
				return null;
			}
			return new Shape(List.of(hullBox(mdl)), false);
		} catch (IOException | RuntimeException e) {
			LOG.warn("PortalCraft: can't read {} ({})", model, e.toString());
			return null;
		}
	}

	/** studiohdr_t's hull_min/hull_max (at 104 and 116) as an 8-point, 12-triangle hull. */
	private static Phy.Hull hullBox(byte[] mdl) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(mdl).order(ByteOrder.LITTLE_ENDIAN);
		if (mdl.length < 128 || b.getInt(0) != IDST) {
			throw new IOException("not a studio model");
		}
		double[] lo = {b.getFloat(104), b.getFloat(108), b.getFloat(112)};
		double[] hi = {b.getFloat(116), b.getFloat(120), b.getFloat(124)};
		if (!(lo[0] < hi[0] && lo[1] < hi[1] && lo[2] < hi[2])) {
			throw new IOException("empty hull box");
		}
		double[] points = new double[24];
		for (int i = 0; i < 8; i++) {
			points[i * 3] = (i & 1) == 0 ? lo[0] : hi[0];
			points[i * 3 + 1] = (i & 2) == 0 ? lo[1] : hi[1];
			points[i * 3 + 2] = (i & 4) == 0 ? lo[2] : hi[2];
		}
		return new Phy.Hull(points, BOX_TRIANGLES);
	}

	/** Two triangles per face of the corner-indexed box above; winding doesn't matter. */
	private static final int[] BOX_TRIANGLES = {
		0, 2, 4, 2, 6, 4, 1, 3, 5, 3, 7, 5, // -x, +x
		0, 1, 4, 1, 5, 4, 2, 3, 6, 3, 7, 6, // -y, +y
		0, 1, 2, 1, 3, 2, 4, 5, 6, 5, 7, 6 // -z, +z
	};

	/**
	 * A model-space hull placed at origin with Source AngleMatrix axes (forward, -right, up as its
	 * x, y, z), as outward planes from its triangles pushed out by margin. Null if it's flat.
	 */
	static BspMap.@Nullable Brush brush(Phy.Hull hull, Vec3 origin, Vec3[] axes, double margin) {
		Vec3 f = axes[0], r = axes[1], u = axes[2];
		double[] p = hull.points();
		int n = p.length / 3;
		if (n < 4) {
			return null;
		}
		double[] w = new double[p.length];
		double cx = 0, cy = 0, cz = 0;
		double[] lo = {1e18, 1e18, 1e18};
		double[] hi = {-1e18, -1e18, -1e18};
		for (int i = 0; i < n; i++) {
			double x = p[i * 3], y = p[i * 3 + 1], z = p[i * 3 + 2];
			w[i * 3] = origin.x + f.x * x - r.x * y + u.x * z;
			w[i * 3 + 1] = origin.y + f.y * x - r.y * y + u.y * z;
			w[i * 3 + 2] = origin.z + f.z * x - r.z * y + u.z * z;
			cx += w[i * 3];
			cy += w[i * 3 + 1];
			cz += w[i * 3 + 2];
			for (int k = 0; k < 3; k++) {
				lo[k] = Math.min(lo[k], w[i * 3 + k]);
				hi[k] = Math.max(hi[k], w[i * 3 + k]);
			}
		}
		cx /= n;
		cy /= n;
		cz /= n;
		int[] t = hull.triangles();
		List<double[]> planes = new ArrayList<>();
		boolean sloped = false;
		for (int i = 0; i + 2 < t.length; i += 3) {
			int a = t[i] * 3, b = t[i + 1] * 3, c = t[i + 2] * 3;
			double ux = w[b] - w[a], uy = w[b + 1] - w[a + 1], uz = w[b + 2] - w[a + 2];
			double vx = w[c] - w[a], vy = w[c + 1] - w[a + 1], vz = w[c + 2] - w[a + 2];
			double nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
			double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 1e-6) {
				continue;
			}
			nx /= len;
			ny /= len;
			nz /= len;
			double d = nx * w[a] + ny * w[a + 1] + nz * w[a + 2];
			if (nx * cx + ny * cy + nz * cz > d) {
				nx = -nx;
				ny = -ny;
				nz = -nz;
				d = -d;
			}
			if (!duplicate(planes, nx, ny, nz, d)) {
				planes.add(new double[] {nx, ny, nz, d});
				sloped |= Math.abs(nx) < 0.9999 && Math.abs(ny) < 0.9999 && Math.abs(nz) < 0.9999;
			}
		}
		if (planes.size() < 4) {
			return null;
		}
		float[] out = new float[planes.size() * 4];
		for (int i = 0; i < planes.size(); i++) {
			double[] q = planes.get(i);
			out[i * 4] = (float) q[0];
			out[i * 4 + 1] = (float) q[1];
			out[i * 4 + 2] = (float) q[2];
			out[i * 4 + 3] = (float) (q[3] + margin);
		}
		Vec3 a = Units.toMc(new Vec3(lo[0] - margin, lo[1] - margin, lo[2] - margin));
		Vec3 c = Units.toMc(new Vec3(hi[0] + margin, hi[1] + margin, hi[2] + margin));
		return new BspMap.Brush(new AABB(a, c), out, sloped);
	}

	private static boolean duplicate(List<double[]> planes, double nx, double ny, double nz, double d) {
		for (double[] q : planes) {
			if (q[0] * nx + q[1] * ny + q[2] * nz > 0.9999 && Math.abs(q[3] - d) < 0.05) {
				return true;
			}
		}
		return false;
	}
}
