package dev.portalcraft.client;

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
import java.nio.charset.StandardCharsets;

import dev.portalcraft.PortalCraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The host's overlay mapping (protocol/portalcraft_protocol.h, OverlayHeader), opened through
 * kernel32 with the FFM API. Minecraft writes frames; the host draws them over its own.
 */
public final class OverlayLink {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	private static final String MAPPING = "Local\\PortalCraft_Overlay_v1";
	public static final int MAX_W = 2560;
	public static final int MAX_H = 1440;
	private static final int SLOTS = 3;
	private static final long SLOT_BYTES = (long) MAX_W * MAX_H * 4;
	private static final long HEADER_BYTES = 4096;
	private static final long TOTAL = HEADER_BYTES + SLOTS * SLOT_BYTES;
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;

	// OverlayHeader field offsets
	private static final long HOST_W = 4, HOST_H = 8, FRONT = 12, READING = 16, SEQ = 20, SLOT_W = 24, SLOT_H = 36;
	private static final ValueLayout.OfInt INT = JAVA_INT.withByteAlignment(4);

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
	private static int seq;

	private OverlayLink() {
	}

	public static boolean open() {
		if (!view.equals(MemorySegment.NULL)) {
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
				return false; // host not running yet
			}
			MemorySegment v = (MemorySegment) MAP.invokeExact(h, FILE_MAP_ALL_ACCESS, 0, 0, TOTAL);
			if (v.equals(MemorySegment.NULL)) {
				int ignored = (int) CLOSE.invokeExact(h);
				return false;
			}
			handle = h;
			view = v.reinterpret(TOTAL);
			LOG.info("PortalCraft: overlay mapping open ({} MB)", TOTAL >> 20);
			return true;
		} catch (Throwable t) {
			LOG.warn("PortalCraft: can't open the overlay mapping: {}", t.toString());
			return false;
		}
	}

	/** The host restarted (or went away): its new mapping is a different object, so drop ours. */
	public static void close() {
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
	}

	public static int hostWidth() {
		return view.equals(MemorySegment.NULL) ? 0 : view.get(INT, HOST_W);
	}

	public static int hostHeight() {
		return view.equals(MemorySegment.NULL) ? 0 : view.get(INT, HOST_H);
	}

	/** Copies one RGBA frame into a slot the host isn't reading and makes it the newest. */
	public static void publish(MemorySegment pixels, int width, int height) {
		if (view.equals(MemorySegment.NULL) || width > MAX_W || height > MAX_H) {
			return;
		}
		int front = view.get(INT, FRONT);
		int reading = view.get(INT, READING);
		int slot = 0;
		while (slot == front || slot == reading) {
			slot++;
		}
		long bytes = Math.min((long) width * height * 4, pixels.byteSize());
		MemorySegment.copy(pixels, 0, view, HEADER_BYTES + slot * SLOT_BYTES, bytes);
		view.set(INT, SLOT_W + slot * 4L, width);
		view.set(INT, SLOT_H + slot * 4L, height);
		java.lang.invoke.VarHandle.releaseFence();
		view.set(INT, FRONT, slot);
		view.set(INT, SEQ, ++seq == 0 ? ++seq : seq);
	}
}
