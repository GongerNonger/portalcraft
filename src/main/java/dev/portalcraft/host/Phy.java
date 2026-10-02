package dev.portalcraft.host;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A model's VCollide (.phy): per solid, an IVP compact surface whose ledge tree ends in convex
 * hulls ("compact ledges"). Points are IVP space, metres with Y down and Z forward; Source is
 * inches, Z up, so source = (x, z, -y) / 0.0254.
 */
public final class Phy {
	private static final double METRES_TO_UNITS = 1.0 / 0.0254;
	private static final int VPHY = 0x59485056; // "VPHY"
	private static final int SURFACE_HEADER = 28;
	private static final int LEDGETREE_NODE = 28;

	/** One convex piece, in model space (Source units): xyz triplets, and triangles over them. */
	public record Hull(double[] points, int[] triangles) {
	}

	private Phy() {
	}

	/** The convex hulls of the first solid, which is all a static prop collides with. */
	public static List<Hull> firstSolid(byte[] file) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN);
		int headerSize = b.getInt(0);
		int solidCount = b.getInt(8);
		if (solidCount < 1 || headerSize < 16 || headerSize + 4 > file.length) {
			throw new IOException("no solids");
		}
		int start = headerSize + 4;
		int size = b.getInt(headerSize);
		check(b, start, size);
		int surface = start;
		if (b.getInt(start) == VPHY) {
			int modelType = b.getShort(start + 6);
			if (modelType != 0) {
				throw new IOException("collide model type " + modelType + " (only polysoups are supported)");
			}
			surface += SURFACE_HEADER;
		}
		// IVP_Compact_Surface: ..., offset_ledgetree_root at +32 (relative to the surface).
		int root = surface + b.getInt(surface + 32);
		List<Hull> hulls = new ArrayList<>();
		List<Integer> stack = new ArrayList<>();
		stack.add(root);
		int end = start + size;
		for (int visited = 0; !stack.isEmpty(); visited++) {
			int node = stack.removeLast();
			if (node < surface || node + LEDGETREE_NODE > end || visited > 65536) {
				throw new IOException("bad ledge tree");
			}
			int right = b.getInt(node);
			if (right == 0) {
				hulls.add(ledge(b, node + b.getInt(node + 4), end));
			} else {
				stack.add(node + LEDGETREE_NODE);
				stack.add(node + right);
			}
		}
		return hulls;
	}

	/** IVP_Compact_Ledge: point offset, client data, flags, triangle count; then 16-byte triangles. */
	private static Hull ledge(ByteBuffer b, int ledge, int end) throws IOException {
		check(b, ledge, 16);
		int pointBase = ledge + b.getInt(ledge);
		int triangleCount = b.getShort(ledge + 12) & 0xFFFF;
		check(b, ledge + 16, triangleCount * 16);
		Map<Integer, Integer> local = new HashMap<>();
		List<double[]> points = new ArrayList<>();
		int[] triangles = new int[triangleCount * 3];
		for (int t = 0; t < triangleCount; t++) {
			int tri = ledge + 16 + t * 16;
			for (int e = 0; e < 3; e++) {
				int index = b.getInt(tri + 4 + e * 4) & 0xFFFF; // edge: start_point_index:16
				Integer mine = local.get(index);
				if (mine == null) {
					int p = pointBase + index * 16;
					if (p < 0 || p + 12 > end) {
						throw new IOException("point outside the solid");
					}
					mine = points.size();
					local.put(index, mine);
					points.add(new double[] {
						b.getFloat(p) * METRES_TO_UNITS,
						b.getFloat(p + 8) * METRES_TO_UNITS,
						-b.getFloat(p + 4) * METRES_TO_UNITS
					});
				}
				triangles[t * 3 + e] = mine;
			}
		}
		double[] flat = new double[points.size() * 3];
		for (int i = 0; i < points.size(); i++) {
			System.arraycopy(points.get(i), 0, flat, i * 3, 3);
		}
		return new Hull(flat, triangles);
	}

	private static void check(ByteBuffer b, int offset, int length) throws IOException {
		if (offset < 0 || length < 0 || offset + length > b.capacity()) {
			throw new IOException("truncated .phy");
		}
	}
}
