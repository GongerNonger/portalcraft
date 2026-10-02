package dev.portalcraft.client.world;

import java.util.Arrays;

/**
 * The pure parts of the world mapping's format (protocol/portalcraft_protocol.h, "world"): sizes,
 * offsets, slot choice, vertex packing, colour conversion and the dropped-item math. No Minecraft
 * types, so it can be unit tested.
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
	/** Where the PCW1 mapping ended; the PCW2 regions follow. */
	public static final long PCW1_BYTES = MESH_OFFSET + SLOTS * SLOT_BYTES;
	public static final int ITEM_ATLAS_MAX_W = 2048; // kWorldItemAtlasMaxW
	public static final int ITEM_ATLAS_MAX_H = 2048; // kWorldItemAtlasMaxH
	public static final int ITEM_ATLAS_MAX_PIXELS = 1024 * 1024; // kWorldItemAtlasMaxPixels
	public static final long ITEM_ATLAS_OFFSET = PCW1_BYTES; // kWorldItemAtlasOffset
	public static final long ITEM_ATLAS_BYTES = (long) ITEM_ATLAS_MAX_PIXELS * 4;
	public static final int ENTITY_MAX_VERTICES = 65536; // kWorldEntityMaxVertices
	public static final long ENTITY_OFFSET = ITEM_ATLAS_OFFSET + ITEM_ATLAS_BYTES; // kWorldEntityOffset
	public static final long ENTITY_SLOT_BYTES = (long) ENTITY_MAX_VERTICES * VERTEX_BYTES; // kWorldEntitySlotBytes
	public static final long TOTAL_BYTES = ENTITY_OFFSET + SLOTS * ENTITY_SLOT_BYTES; // kWorldBytes
	public static final int NO_SLOT = 0xFFFFFFFF;

	// WorldHeader field offsets (static_asserts in the protocol header)
	public static final long H_MAGIC = 0, H_ATLAS_W = 4, H_ATLAS_H = 8, H_ATLAS_SEQ = 12, H_MESH_SEQ = 16, H_FRONT = 20, H_READING = 24,
		H_SLOT_SOLID = 28, H_SLOT_TRANSLUCENT = 36;
	public static final long H_ITEM_ATLAS_W = 44, H_ITEM_ATLAS_H = 48, H_ITEM_ATLAS_SEQ = 52, H_ENTITY_SEQ = 56, H_ENTITY_FRONT = 60,
		H_ENTITY_READING = 64, H_ENTITY_BLOCK_SOLID = 68, H_ENTITY_BLOCK_TRANSLUCENT = 76, H_ENTITY_ITEM_SOLID = 84,
		H_ENTITY_ITEM_TRANSLUCENT = 92;
	public static final long HEADER_STRUCT_BYTES = 100; // sizeof(WorldHeader)

	/** ItemEntityRenderer.ITEM_MIN_HOVER_HEIGHT: how far a dropped item's model floats off the ground. */
	public static final float ITEM_MIN_HOVER = 0.0625F;

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

	/** True if an atlas of this size fits the item atlas region (and the host's texture limits). */
	public static boolean itemAtlasFits(int width, int height) {
		return width > 0 && height > 0 && width <= ITEM_ATLAS_MAX_W && height <= ITEM_ATLAS_MAX_H
			&& (long) width * height <= ITEM_ATLAS_MAX_PIXELS;
	}

	/** A dropped item's bob height in blocks, as ItemEntityRenderer.submit computes it. */
	public static float itemBob(float ageInTicks, float bobOffset) {
		return (float) Math.sin(ageInTicks / 10.0F + bobOffset) * 0.1F + 0.1F;
	}

	/**
	 * Directional shading for entity faces, which the host doesn't light: Minecraft's block face
	 * shades (up 1.0, down 0.5, north/south 0.8, east/west 0.6) blended by the normal's squared
	 * components, so a spinning item's faces brighten and darken as they turn.
	 */
	public static float shade(float nx, float ny, float nz) {
		float len2 = nx * nx + ny * ny + nz * nz;
		if (!(len2 > 1e-12F)) {
			return 1.0F;
		}
		float s = (nx * nx * 0.6F + nz * nz * 0.8F + ny * ny * (ny > 0 ? 1.0F : 0.5F)) / len2;
		return Math.clamp(s, 0.0F, 1.0F);
	}

	/** An ARGB colour with its RGB scaled by {@code shade} (0..1), alpha kept. */
	public static int shadeArgb(int argb, float shade) {
		int r = Math.round(((argb >>> 16) & 0xFF) * shade), g = Math.round(((argb >>> 8) & 0xFF) * shade), b = Math.round((argb & 0xFF) * shade);
		return (argb & 0xFF000000) | (Math.clamp(r, 0, 255) << 16) | (Math.clamp(g, 0, 255) << 8) | Math.clamp(b, 0, 255);
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

		/** Drops the vertices after the first {@code count} (to undo an entity that didn't fit). */
		public void truncate(int count) {
			this.count = Math.clamp(count, 0, this.count);
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
