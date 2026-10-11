package dev.portalcraft.client.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import dev.portalcraft.host.HostLink;
import dev.portalcraft.host.Units;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Minecraft's light-emitting blocks (lava, torches, glowstone, redstone) as the host's own dynamic
 * lights, so they light Portal's walls and floors. WorldExporter reports each meshed section's
 * emitters; four times a second the ones near the player are merged per 3-block cell (a lava pool
 * is one light, not hundreds) and the 16 brightest-and-nearest go to the host ("PCL1": count, then
 * per light x, y, z in host units, r, g, b, Minecraft light level). Colours follow what the block
 * looks like, since Minecraft's light has none.
 *
 * <p>Adapted from SkyCraft's {@code BlockLights} and {@code BlockLightColors} (chasmlol/SkyCraft,
 * MIT): per-section emitters, cell merging, the colour table.
 */
public final class LightExporter {
	private static final int CELL_BLOCKS = 3;
	private static final int MAX_LIGHTS = 16;
	private static final double RANGE_BLOCKS = 72.0;
	private static final long UPDATE_MS = 250;

	/** Section key -> its emitters, each packed x | y << 4 | z << 8 (within the section) | level << 12, and an RGB. */
	private static final Long2ObjectOpenHashMap<int[]> SECTIONS = new Long2ObjectOpenHashMap<>();
	private static long nextSend;
	private static boolean sentEmpty;

	private LightExporter() {
	}

	/** WorldExporter: section {@code key}'s emitters (pairs of packed position+level, RGB), or none. */
	static void section(long key, int[] emitters) {
		if (emitters == null || emitters.length == 0) {
			SECTIONS.remove(key);
		} else {
			SECTIONS.put(key, emitters);
		}
	}

	static void clear() {
		SECTIONS.clear();
	}

	static int pack(int x, int y, int z, int level) {
		return x | y << 4 | z << 8 | level << 12;
	}

	private record Cluster(long cell, double[] sum, int[] rgb) {
	}

	/** Once per render frame (from WorldExporter.frame): send the lights near the player, 4 times a second. */
	static void frame(Minecraft minecraft) {
		long now = System.currentTimeMillis();
		if (minecraft.player == null) {
			return;
		}
		// Glowing particles come and go by the frame: while there are any (and once more after the
		// last, to put their lights out) the lights are sent as often as the screen is drawn, up to
		// 30 times a second. The blocks' lights alone, four times a second as before.
		boolean sparks = !SPARKS.isEmpty();
		if (now < nextSend && !((sparks || hadSparks) && now >= nextSparkSend)) {
			if (now - sparksSince > 100) {
				SPARKS.clear(); // (not sent this frame: not kept for the next either)
			}
			return;
		}
		nextSparkSend = now + SPARK_MS;
		Vec3 player = minecraft.player.position();
		if (now < nextSend && blockLights != null) {
			send(withSparks(blockLights, player));
			return;
		}
		nextSend = now + UPDATE_MS;
		double range2 = RANGE_BLOCKS * RANGE_BLOCKS;
		Long2ObjectOpenHashMap<Cluster> cells = new Long2ObjectOpenHashMap<>();
		for (Long2ObjectMap.Entry<int[]> e : SECTIONS.long2ObjectEntrySet()) {
			long key = e.getLongKey();
			int ox = net.minecraft.core.SectionPos.sectionToBlockCoord(net.minecraft.core.SectionPos.x(key));
			int oy = net.minecraft.core.SectionPos.sectionToBlockCoord(net.minecraft.core.SectionPos.y(key));
			int oz = net.minecraft.core.SectionPos.sectionToBlockCoord(net.minecraft.core.SectionPos.z(key));
			int[] list = e.getValue();
			for (int i = 0; i + 1 < list.length; i += 2) {
				int p = list[i];
				int x = ox + (p & 15), y = oy + (p >> 4 & 15), z = oz + (p >> 8 & 15), level = p >> 12 & 15;
				double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
				if ((cx - player.x) * (cx - player.x) + (cy - player.y) * (cy - player.y) + (cz - player.z) * (cz - player.z) > range2) {
					continue;
				}
				long cell = net.minecraft.core.BlockPos.asLong(Math.floorDiv(x, CELL_BLOCKS), Math.floorDiv(y, CELL_BLOCKS), Math.floorDiv(z, CELL_BLOCKS));
				Cluster c = cells.computeIfAbsent(cell, k -> new Cluster(k, new double[5], new int[3]));
				double w = level;
				c.sum[0] += cx * w;
				c.sum[1] += cy * w;
				c.sum[2] += cz * w;
				c.sum[3] += w;
				c.sum[4] = Math.max(c.sum[4], level);
				int rgb = list[i + 1];
				c.rgb[0] += (rgb >> 16 & 0xFF) * level;
				c.rgb[1] += (rgb >> 8 & 0xFF) * level;
				c.rgb[2] += (rgb & 0xFF) * level;
			}
		}
		blockLights = new ArrayList<>(cells.values());
		send(withSparks(blockLights, player));
	}

	// ---- glowing particles -----------------------------------------------------------------------
	private static final long SPARK_MS = 33;
	private static final int SPARK_CELL_BLOCKS = 2;
	/** How bright a particles' light is against a block's of the same level (at a block's brightness a burst of sparks whited out the wall behind it). */
	private static final double SPARK_GAIN = 0.22;
	/** This frame's glowing particles, merged per 2-block cell: the same sums as a block cluster's. */
	private static final Long2ObjectOpenHashMap<Cluster> SPARKS = new Long2ObjectOpenHashMap<>();
	private static List<Cluster> blockLights;
	private static boolean hadSparks;
	private static long nextSparkSend, sparksSince;

	/**
	 * EntityExporter: a glowing particle (or the flames on something burning) at a Minecraft position,
	 * of colour rgb, weighing its size on screen times how opaque it is. Its cell's light grows with
	 * what is in it: one spark a faint glow a few blocks wide, a firework's burst a bright one.
	 */
	static void spark(double x, double y, double z, int rgb, double weight) {
		if (!(weight > 0.0)) {
			return;
		}
		if (SPARKS.isEmpty()) {
			sparksSince = System.currentTimeMillis();
		}
		long cell = net.minecraft.core.BlockPos.asLong(Math.floorDiv((int) Math.floor(x), SPARK_CELL_BLOCKS), Math.floorDiv((int) Math.floor(y), SPARK_CELL_BLOCKS),
			Math.floorDiv((int) Math.floor(z), SPARK_CELL_BLOCKS));
		Cluster c = SPARKS.computeIfAbsent(cell, k -> new Cluster(k, new double[5], new int[3]));
		c.sum[0] += x * weight;
		c.sum[1] += y * weight;
		c.sum[2] += z * weight;
		c.sum[3] += weight;
		// (kept as the weight's own sums: turned into a level, and the colour sums scaled to match, when sent)
		c.rgb[0] += (int) ((rgb >> 16 & 0xFF) * weight * 256.0);
		c.rgb[1] += (int) ((rgb >> 8 & 0xFF) * weight * 256.0);
		c.rgb[2] += (int) ((rgb & 0xFF) * weight * 256.0);
	}

	/** The blocks' lights and this frame's sparks together; the sparks are taken (the next frame brings its own). */
	private static List<Cluster> withSparks(List<Cluster> blocks, Vec3 player) {
		List<Cluster> all = new ArrayList<>(blocks);
		for (Cluster c : SPARKS.values()) {
			double w = c.sum[3];
			double level = Math.clamp(1.5 + 1.5 * (Math.log1p(w * 30.0) / Math.log(2.0)), 1.5, 8.0);
			// in a block cluster's terms: position and colour sums weighted by the level, sum[3] the weight, sum[4] the level
			Cluster light = new Cluster(c.cell, new double[] {c.sum[0] / w * level, c.sum[1] / w * level, c.sum[2] / w * level, level, level},
				new int[] {(int) (c.rgb[0] / 256.0 / w * level * SPARK_GAIN), (int) (c.rgb[1] / 256.0 / w * level * SPARK_GAIN), (int) (c.rgb[2] / 256.0 / w * level * SPARK_GAIN)});
			all.add(light);
		}
		hadSparks = !SPARKS.isEmpty();
		SPARKS.clear();
		all.sort((a, b) -> Double.compare(score(b, player), score(a, player)));
		return all;
	}

	private static void send(List<Cluster> all) {
		int n = Math.min(MAX_LIGHTS, all.size());
		if (n == 0 && sentEmpty) {
			return;
		}
		ByteBuffer out = ByteBuffer.allocate(8 + n * 16).order(ByteOrder.LITTLE_ENDIAN);
		out.put((byte) 'P').put((byte) 'C').put((byte) 'L').put((byte) '1').putInt(n);
		for (int i = 0; i < n; i++) {
			Cluster c = all.get(i);
			Vec3 at = Units.toSrc(new Vec3(c.sum[0] / c.sum[3], c.sum[1] / c.sum[3], c.sum[2] / c.sum[3]));
			out.putFloat((float) at.x).putFloat((float) at.y).putFloat((float) at.z);
			out.put((byte) Math.min(255, c.rgb[0] / c.sum[3])).put((byte) Math.min(255, c.rgb[1] / c.sum[3])).put((byte) Math.min(255, c.rgb[2] / c.sum[3]));
			out.put((byte) c.sum[4]);
		}
		HostLink.send(out.flip());
		sentEmpty = n == 0;
	}

	private static double score(Cluster c, Vec3 player) {
		double x = c.sum[0] / c.sum[3] - player.x, y = c.sum[1] / c.sum[3] - player.y, z = c.sum[2] / c.sum[3] - player.z;
		return c.sum[4] / (1.0 + Math.sqrt(x * x + y * y + z * z) / 8.0);
	}

	/** The colour a light-emitting block casts (0xRRGGBB): flames warm, lava deep orange, soul fire cyan. */
	static int color(BlockState state) {
		String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		if (id.contains("soul")) {
			return rgb(90, 210, 255);
		}
		if (id.contains("lava") || id.equals("magma_block")) {
			return rgb(255, 105, 25);
		}
		if (id.contains("redstone_lamp")) {
			return rgb(255, 215, 150);
		}
		if (id.contains("redstone")) {
			return rgb(255, 55, 30);
		}
		if (id.contains("torch") || id.contains("candle")) {
			return rgb(255, 185, 105);
		}
		if (id.contains("lantern") && !id.contains("sea") || id.contains("jack_o_lantern") || id.contains("campfire") || id.equals("fire")) {
			return rgb(255, 165, 80);
		}
		if (id.contains("furnace") || id.equals("smoker")) {
			return rgb(255, 150, 60);
		}
		if (id.equals("glowstone") || id.contains("ochre_froglight")) {
			return rgb(255, 215, 140);
		}
		if (id.equals("sea_lantern") || id.equals("beacon") || id.equals("end_rod")) {
			return rgb(205, 235, 255);
		}
		if (id.contains("verdant_froglight")) {
			return rgb(170, 255, 150);
		}
		if (id.contains("pearlescent_froglight")) {
			return rgb(255, 190, 240);
		}
		if (id.contains("amethyst")) {
			return rgb(190, 130, 255);
		}
		if (id.contains("crying_obsidian") || id.equals("nether_portal") || id.equals("respawn_anchor")) {
			return rgb(150, 65, 255);
		}
		return rgb(255, 235, 200);
	}

	private static int rgb(int r, int g, int b) {
		return r << 16 | g << 8 | b;
	}
}
