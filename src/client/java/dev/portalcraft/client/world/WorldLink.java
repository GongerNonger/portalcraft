package dev.portalcraft.client.world;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

import dev.portalcraft.PortalCraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host's world mapping (protocol/portalcraft_protocol.h, WorldHeader), opened through
 * kernel32 with the FFM API like {@code OverlayLink}. Minecraft writes the block atlas and the
 * placed blocks' mesh; the host draws them in its own 3D pass. Render thread only.
 */
public final class WorldLink {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	private static final String MAPPING = "Local\\PortalCraft_World_v1";
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;

	// WorldHeader field offsets
	private static final long MAGIC = 0, ATLAS_W = 4, ATLAS_H = 8, ATLAS_SEQ = 12, MESH_SEQ = 16, FRONT = 20, READING = 24, SLOT_SOLID = 28,
		SLOT_TRANSLUCENT = 36;
	private static final ValueLayout.OfInt INT = JAVA_INT.withByteAlignment(4);
	private static final ValueLayout.OfInt INT_UNALIGNED = ValueLayout.JAVA_INT_UNALIGNED;
	private static final int MAGIC_PCW1 = 'P' | 'C' << 8 | 'W' << 16 | '1' << 24;

	private static final MethodHandle OPEN;
	private static final MethodHandle MAP;
	private static final MethodHandle UNMAP;
	private static final MethodHandle CLOSE;

	static {
		Linker linker = Linker.nativeLinker();
		SymbolLookup k32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
		OPEN = linker.downcallHandle(k32.find("OpenFileMappingW").orElseThrow(), FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
		MAP = linker.downcallHandle(k32.find("MapViewOfFile").orElseThrow(), FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_LONG));
		UNMAP = linker.downcallHandle(k32.find("UnmapViewOfFile").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
		CLOSE = linker.downcallHandle(k32.find("CloseHandle").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
	}

	private static MemorySegment handle = MemorySegment.NULL;
	private static MemorySegment view = MemorySegment.NULL;
	private static long nextTry;
	/** Bumped on every successful open: a new mapping holds nothing of ours yet. */
	private static int generation;
	private static boolean warnedMagic;

	private WorldLink() {
	}

	public static boolean isOpen() {
		return !view.equals(MemorySegment.NULL);
	}

	public static int generation() {
		return generation;
	}

	/** Opens the mapping if the host has created it; retries at most every 2 s. */
	public static boolean open() {
		if (isOpen()) {
			return true;
		}
		long now = System.currentTimeMillis();
		if (now < nextTry) {
			return false;
		}
		nextTry = now + 2000;
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment h = (MemorySegment) OPEN.invokeExact(FILE_MAP_ALL_ACCESS, 0, arena.allocateFrom(MAPPING, StandardCharsets.UTF_16LE));
			if (h.equals(MemorySegment.NULL)) {
				return false; // host not running yet, or an older plugin without the world pass
			}
			MemorySegment v = (MemorySegment) MAP.invokeExact(h, FILE_MAP_ALL_ACCESS, 0, 0, WorldFormat.TOTAL_BYTES);
			if (v.equals(MemorySegment.NULL)) {
				int ignored = (int) CLOSE.invokeExact(h);
				return false;
			}
			v = v.reinterpret(WorldFormat.TOTAL_BYTES);
			if (v.get(INT, MAGIC) != MAGIC_PCW1) {
				if (!warnedMagic) {
					warnedMagic = true;
					LOG.warn("PortalCraft: world mapping has no PCW1 magic yet; retrying every 2 s");
				}
				int ignored = (int) UNMAP.invokeExact(v);
				ignored = (int) CLOSE.invokeExact(h);
				return false;
			}
			handle = h;
			view = v;
			generation++;
			LOG.info("PortalCraft: world mapping open ({} MB)", WorldFormat.TOTAL_BYTES >> 20);
			return true;
		} catch (Throwable t) {
			LOG.warn("PortalCraft: can't open the world mapping: {}", t.toString());
			return false;
		}
	}

	/** The host restarted (or went away): its new mapping is a different object, so drop ours. */
	public static void close() {
		boolean wasOpen = isOpen();
		try {
			if (!view.equals(MemorySegment.NULL)) {
				int ignored = (int) UNMAP.invokeExact(view);
			}
			if (!handle.equals(MemorySegment.NULL)) {
				int ignored = (int) CLOSE.invokeExact(handle);
			}
		} catch (Throwable ignored) {
		}
		view = MemorySegment.NULL;
		handle = MemorySegment.NULL;
		nextTry = 0;
		if (wasOpen) {
			LOG.info("PortalCraft: world mapping closed");
		}
	}

	/**
	 * Writes the block atlas: size first, then the pixels (ints whose little-endian bytes are
	 * R, G, B, A, rows top-down), then bumps {@code atlasSeq}.
	 */
	public static void writeAtlas(int width, int height, int[] rgba) {
		if (!isOpen() || width > WorldFormat.ATLAS_MAX_W || height > WorldFormat.ATLAS_MAX_H || rgba.length < width * height) {
			return;
		}
		view.set(INT, ATLAS_W, width);
		view.set(INT, ATLAS_H, height);
		MemorySegment.copy(rgba, 0, view, INT_UNALIGNED, WorldFormat.ATLAS_OFFSET, width * height);
		VarHandle.releaseFence();
		view.set(INT, ATLAS_SEQ, WorldFormat.nextSeq(view.get(INT, ATLAS_SEQ)));
	}

	/** The mesh slot Minecraft may write now, or -1 if the host holds both. */
	public static int freeSlot() {
		if (!isOpen()) {
			return -1;
		}
		int front = view.get(INT, FRONT);
		int reading = view.get(INT, READING);
		VarHandle.acquireFence();
		return WorldFormat.freeSlot(front, reading);
	}

	/** Copies {@code vertices} packed vertices into {@code slot}, starting at vertex {@code at}. */
	public static void writeVertices(int slot, int at, int[] data, int vertices) {
		if (!isOpen() || slot < 0 || slot >= WorldFormat.SLOTS || at < 0 || at + vertices > WorldFormat.MAX_VERTICES) {
			return;
		}
		long offset = WorldFormat.MESH_OFFSET + slot * WorldFormat.SLOT_BYTES + (long) at * WorldFormat.VERTEX_BYTES;
		MemorySegment.copy(data, 0, view, INT_UNALIGNED, offset, vertices * WorldFormat.VERTEX_INTS);
	}

	/** Makes {@code slot} the newest mesh: its counts, then {@code front}, then {@code meshSeq}. */
	public static void publish(int slot, int solid, int translucent) {
		if (!isOpen() || slot < 0 || slot >= WorldFormat.SLOTS) {
			return;
		}
		view.set(INT, SLOT_SOLID + slot * 4L, solid);
		view.set(INT, SLOT_TRANSLUCENT + slot * 4L, translucent);
		VarHandle.releaseFence();
		view.set(INT, FRONT, slot);
		VarHandle.releaseFence();
		view.set(INT, MESH_SEQ, WorldFormat.nextSeq(view.get(INT, MESH_SEQ)));
	}
}
