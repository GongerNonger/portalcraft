package dev.portalcraft.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

/**
 * A Source game's file search path, enough to find models: the game folder's loose files, its
 * `<game>_pak_dir.vpk`, then HL2's shared `hl2_misc_dir.vpk` and loose files. Portal keeps no
 * models in its other paks (sound, textures), so those are never opened.
 */
public final class GameFiles {
	/** Opened paks by dir-file path; parsing hl2_misc's 19k-entry tree each map load would dominate. */
	private static final ConcurrentHashMap<Path, Vpk> OPEN = new ConcurrentHashMap<>();

	private final List<Path> looseFirst;
	private final List<Vpk> paks;
	private final List<Path> looseLast;

	public GameFiles(List<Path> looseFirst, List<Vpk> paks, List<Path> looseLast) {
		this.looseFirst = looseFirst;
		this.paks = paks;
		this.looseLast = looseLast;
	}

	/** The search path for a map at `<game>/maps/<name>.bsp`, e.g. `Portal/portal/maps`. */
	public static GameFiles forMap(Path bsp) throws IOException {
		Path maps = bsp.toAbsolutePath().getParent();
		Path game = maps == null ? null : maps.getParent();
		if (game == null || maps.getFileName() == null || !maps.getFileName().toString().equalsIgnoreCase("maps")) {
			return new GameFiles(List.of(), List.of(), List.of());
		}
		List<Vpk> paks = new ArrayList<>();
		Path pak = game.resolve(game.getFileName() + "_pak_dir.vpk");
		if (Files.isRegularFile(pak)) {
			paks.add(pak(pak));
		}
		Path hl2 = game.resolveSibling("hl2");
		Path misc = hl2.resolve("hl2_misc_dir.vpk");
		if (Files.isRegularFile(misc)) {
			paks.add(pak(misc));
		}
		return new GameFiles(List.of(game), paks, Files.isDirectory(hl2) ? List.of(hl2) : List.of());
	}

	private static Vpk pak(Path dirFile) throws IOException {
		Vpk open = OPEN.get(dirFile);
		if (open == null) {
			open = Vpk.open(dirFile);
			OPEN.put(dirFile, open);
		}
		return open;
	}

	/** The file at a game-relative path (`models/props/x.phy`), or null if nothing has it. */
	public byte @Nullable [] read(String path) throws IOException {
		String rel = Vpk.normalise(path);
		byte[] loose = readLoose(looseFirst, rel);
		if (loose != null) {
			return loose;
		}
		for (Vpk pak : paks) {
			byte[] bytes = pak.read(rel);
			if (bytes != null) {
				return bytes;
			}
		}
		return readLoose(looseLast, rel);
	}

	private static byte @Nullable [] readLoose(List<Path> dirs, String rel) throws IOException {
		for (Path dir : dirs) {
			Path file = dir.resolve(rel);
			if (Files.isRegularFile(file)) {
				return Files.readAllBytes(file);
			}
		}
		return null;
	}
}
