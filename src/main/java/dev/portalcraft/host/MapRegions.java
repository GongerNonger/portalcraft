package dev.portalcraft.host;

import java.util.List;
import java.util.Locale;

/**
 * Every host map gets its own stretch of the Minecraft world, so what Steve builds in one chamber
 * stays in that chamber: a map's Source origin sits {@link #SPACING} blocks along x from the last
 * one's (Units adds it to every position). Portal 1's maps are listed in order; others (mods,
 * Portal with RTX's extras) get a slot from their name.
 */
public final class MapRegions {
	/** Blocks between maps: Source maps are at most 32768 units (820 blocks) across. */
	public static final double SPACING = 4096.0;

	private static final List<String> MAPS = List.of(
		"testchmb_a_00", "testchmb_a_01", "testchmb_a_02", "testchmb_a_03", "testchmb_a_04", "testchmb_a_05", "testchmb_a_06",
		"testchmb_a_07", "testchmb_a_08", "testchmb_a_09", "testchmb_a_10", "testchmb_a_11", "testchmb_a_13", "testchmb_a_14",
		"testchmb_a_15", "escape_00", "escape_01", "escape_02", "testchmb_a_08_advanced", "testchmb_a_09_advanced",
		"testchmb_a_10_advanced", "testchmb_a_11_advanced", "testchmb_a_13_advanced", "testchmb_a_14_advanced", "background1",
		"background2");
	/** Slots past Portal 1's, for maps it doesn't have. */
	private static final int EXTRA_SLOTS = 64;

	private MapRegions() {
	}

	public static int index(String map) {
		String name = map.toLowerCase(Locale.ROOT);
		int i = MAPS.indexOf(name);
		return i >= 0 ? i : MAPS.size() + Math.floorMod(name.hashCode(), EXTRA_SLOTS);
	}

	/** Where `map`'s origin is in the Minecraft world, along x (blocks). */
	public static double offsetX(String map) {
		return index(map) * SPACING;
	}
}
