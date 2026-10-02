package dev.portalcraft.client.world;

import java.util.Arrays;

/**
 * The pure parts of the world mapping's format (protocol/portalcraft_protocol.h, "world"): sizes,
 * slot choice, vertex packing and colour conversion. No Minecraft types, so it can be unit tested.
 */
public final class WorldFormat {
	public static final int ATLAS_MAX_W = 2048;
	public static final int ATLAS_MAX_H = 2048;
	public static final long HEADER_BYTES = 4096;
	public static final long ATLAS_OFFSET = 4096;
	public static final long ATLAS_BYTES = (long) ATLAS_MAX_W * ATLAS_MAX_H * 4;
	public static final int MAX_VERTICES = 196608; // kWorldMaxVertices
	public static final int VERTEX_BYTES = 24;
	/** A vertex as ints: x, y, z (float bits), colour, u, v (float bits). */
	public static final int VERTEX_INTS = VERTEX_BYTES / 4;
	public static final long MESH_OFFSET = ATLAS_OFFSET + ATLAS_BYTES;
	public static final long SLOT_BYTES = (long) MAX_VERTICES * VERTEX_BYTES;
	public static final int SLOTS = 2;
	public static final long TOTAL_BYTES = MESH_OFFSET + SLOTS * SLOT_BYTES;
	public static final int NO_SLOT = 0xFFFFFFFF;

	/** A Minecraft quad's corners as two triangles. */
	public static final int[] QUAD_TRIANGLES = {0, 1, 2, 0, 2, 3};

	/** Source units per block, as in {@code dev.portalcraft.host.Units}. */
	private static final double PER_BLOCK = 40.0;

	private WorldFormat() {
	}

	/** The mesh slot Minecraft may write now (neither {@code front} nor {@code reading}), or -1. */
	public static int freeSlot(int front, int reading) {
		for (int slot = 0; slot < SLOTS; slot++) {
			if (slot != front && slot != reading) {
				return slot;
			}
		}
		return -1;
	}

	/**
	 * Minecraft's vertex colour (ARGB) as a D3DCOLOR, which has the same 0xAARRGGBB layout. Solid
	 * and cutout quads get alpha 255: the texture's alpha does the cutout.
	 */
	public static int d3dColor(int argb, boolean translucent) {
		return translucent ? argb : argb | 0xFF000000;
	}

	/** An ARGB pixel as the little-endian int whose bytes are R, G, B, A (the mapping's RGBA8). */
	public static int argbToRgba(int argb) {
		return (argb & 0xFF00FF00) | ((argb >>> 16) & 0xFF) | ((argb & 0xFF) << 16);
	}

	/** Sequence counters skip 0, which means "nothing yet". */
	public static int nextSeq(int seq) {
		int next = seq + 1;
		return next == 0 ? 1 : next;
	}

	/**
	 * A growable list of packed vertices. Positions are Minecraft block coordinates going in and
	 * host space (Source units, Z up) coming out, the same conversion as {@code Units.toSrc}.
	 */
	public static final class Vertices {
		private int[] data;
		private int count;

		public Vertices() {
			this(1024);
		}

		public Vertices(int capacity) {
			this.data = new int[Math.max(1, capacity) * VERTEX_INTS];
		}

		public void clear() {
			this.count = 0;
		}

		public int count() {
			return this.count;
		}

		public int[] data() {
			return this.data;
		}

		public void add(double mcX, double mcY, double mcZ, int color, float u, float v) {
			int o = this.count * VERTEX_INTS;
			if (o + VERTEX_INTS > this.data.length) {
				this.data = Arrays.copyOf(this.data, Math.max(this.data.length * 2, o + VERTEX_INTS));
			}
			this.data[o] = Float.floatToRawIntBits((float) (mcX * PER_BLOCK));
			this.data[o + 1] = Float.floatToRawIntBits((float) (-mcZ * PER_BLOCK));
			this.data[o + 2] = Float.floatToRawIntBits((float) (mcY * PER_BLOCK));
			this.data[o + 3] = color;
			this.data[o + 4] = Float.floatToRawIntBits(u);
			this.data[o + 5] = Float.floatToRawIntBits(v);
			this.count++;
		}

		/** A copy trimmed to the vertices in use. */
		public int[] toArray() {
			return Arrays.copyOf(this.data, this.count * VERTEX_INTS);
		}
	}

	/**
	 * How many of the given meshes (vertex counts, nearest first) fit in {@code budget} vertices,
	 * taking them in order and stopping at the first that doesn't fit.
	 */
	public static int fitting(int[] vertexCounts, int count, int budget) {
		long total = 0;
		for (int i = 0; i < count; i++) {
			total += vertexCounts[i];
			if (total > budget) {
				return i;
			}
		}
		return count;
	}
}
