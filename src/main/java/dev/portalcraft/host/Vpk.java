package dev.portalcraft.host;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Read-only Valve pak (VPK v1/v2). The `_dir.vpk` holds the directory tree, each file's preload
 * bytes, and any data stored in the dir file itself; the rest lives in `_000.vpk`, `_001.vpk`, ...
 * The whole dir file stays in memory: Portal's are well under a megabyte.
 */
public final class Vpk {
	private static final int SIGNATURE = 0x55AA1234;
	/** Archive index meaning "in the dir file, after the tree". */
	private static final int DIR_ARCHIVE = 0x7FFF;

	private record Entry(int preloadOffset, int preloadLength, int archive, long offset, int length) {
	}

	private final Path dirFile;
	private final ByteBuffer dir;
	private final int dataStart;
	private final Map<String, Entry> entries;

	private Vpk(Path dirFile, ByteBuffer dir, int dataStart, Map<String, Entry> entries) {
		this.dirFile = dirFile;
		this.dir = dir;
		this.dataStart = dataStart;
		this.entries = entries;
	}

	public static Vpk open(Path dirFile) throws IOException {
		ByteBuffer b = ByteBuffer.wrap(Files.readAllBytes(dirFile)).order(ByteOrder.LITTLE_ENDIAN);
		if (b.getInt(0) != SIGNATURE) {
			throw new IOException(dirFile + " is not a VPK");
		}
		int version = b.getInt(4);
		int treeSize = b.getInt(8);
		int headerSize = switch (version) {
			case 1 -> 12;
			case 2 -> 28;
			default -> throw new IOException(dirFile + ": unsupported VPK version " + version);
		};
		Map<String, Entry> entries = new HashMap<>();
		b.position(headerSize);
		// Tree: extension { path { name { entry } "" } "" } "", with " " standing for "none".
		for (String ext; !(ext = string(b)).isEmpty(); ) {
			for (String path; !(path = string(b)).isEmpty(); ) {
				for (String name; !(name = string(b)).isEmpty(); ) {
					b.getInt(); // CRC
					int preload = b.getShort() & 0xFFFF;
					int archive = b.getShort() & 0xFFFF;
					long offset = b.getInt() & 0xFFFFFFFFL;
					int length = b.getInt();
					b.getShort(); // terminator, 0xFFFF
					String full = (path.equals(" ") ? "" : path + "/") + name + (ext.equals(" ") ? "" : "." + ext);
					entries.put(full.toLowerCase(), new Entry(b.position(), preload, archive, offset, length));
					b.position(b.position() + preload);
				}
			}
		}
		return new Vpk(dirFile, b, headerSize + treeSize, entries);
	}

	public int size() {
		return entries.size();
	}

	public boolean contains(String path) {
		return entries.containsKey(normalise(path));
	}

	/** The file's bytes (preload + archive data), or null if this pak doesn't have it. */
	public byte @Nullable [] read(String path) throws IOException {
		Entry e = entries.get(normalise(path));
		if (e == null) {
			return null;
		}
		byte[] out = new byte[e.preloadLength + e.length];
		dir.get(e.preloadOffset, out, 0, e.preloadLength);
		if (e.length > 0) {
			if (e.archive == DIR_ARCHIVE) {
				dir.get((int) (dataStart + e.offset), out, e.preloadLength, e.length);
			} else {
				String base = dirFile.getFileName().toString();
				Path archive = dirFile.resolveSibling(base.substring(0, base.length() - "dir.vpk".length()) + String.format("%03d.vpk", e.archive));
				try (FileChannel ch = FileChannel.open(archive, StandardOpenOption.READ)) {
					ByteBuffer dst = ByteBuffer.wrap(out, e.preloadLength, e.length);
					long at = e.offset;
					while (dst.hasRemaining()) {
						int n = ch.read(dst, at);
						if (n < 0) {
							throw new IOException(archive + " ends before " + path);
						}
						at += n;
					}
				}
			}
		}
		return out;
	}

	static String normalise(String path) {
		String p = path.replace('\\', '/').toLowerCase();
		while (p.startsWith("/")) {
			p = p.substring(1);
		}
		return p;
	}

	private static String string(ByteBuffer b) {
		int start = b.position();
		int end = start;
		while (b.get(end) != 0) {
			end++;
		}
		b.position(end + 1);
		return new String(b.array(), start, end - start, StandardCharsets.ISO_8859_1);
	}
}
