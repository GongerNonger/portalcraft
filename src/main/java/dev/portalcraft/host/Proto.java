package dev.portalcraft.host;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import net.minecraft.world.phys.Vec3;

/** Java mirror of protocol/portalcraft_protocol.h. Positions here are still in host units. */
public final class Proto {
	public static final int HOST_PORT = 27515;
	public static final int MC_PORT = 27516;
	public static final int HOST_STATE_SIZE = 292;
	public static final int MC_STATE_SIZE = 76;

	public static final int HOST_IN_GAME = 1;
	public static final int HOST_FOREGROUND = 1 << 1;
	public static final int HOST_DRIVING = 1 << 2;
	/** A scripted scene has the host's player (its camera, or frozen): follow it, take no input. */
	public static final int HOST_SCRIPTED = 1 << 3;
	/** The host's player stands on a moving lift: the host owns its height, follow it. */
	public static final int HOST_RIDING = 1 << 4;

	public static final int PORTAL_EXISTS = 1;
	public static final int PORTAL_ACTIVE = 1 << 1;
	public static final int PORTAL_LINKED = 1 << 2;

	public static final int MC_READY = 1;
	/** HostState.teleportKind: a shove only places the player, an impulse also sets its velocity. */
	public static final int MOVE_SHOVE = 0, MOVE_IMPULSE = 1, MOVE_TELEPORT = 2;
	/** McFlags kMcScreen: a screen is open, so the host frees its mouse and sends the cursor. */
	public static final int MC_SCREEN = 1 << 1;

	private Proto() {
	}

	public record HostPortal(int flags, Vec3 origin, Vec3 angles) {
		public boolean linked() {
			return (flags & PORTAL_LINKED) != 0;
		}
	}

	/**
	 * HostState's cross fields: the portal crossings Minecraft hasn't applied yet, as one rigid move
	 * from where Minecraft is once it has applied up to `base` (host units, row-major rotation).
	 */
	public record Crossing(int base, double[] rot, Vec3 move) {
		public Vec3 point(Vec3 p) {
			return dir(p).add(move);
		}

		public Vec3 dir(Vec3 v) {
			return new Vec3(rot[0] * v.x + rot[1] * v.y + rot[2] * v.z, rot[3] * v.x + rot[4] * v.y + rot[5] * v.z,
				rot[6] * v.x + rot[7] * v.y + rot[8] * v.z);
		}
	}

	public record HostState(
		int seq, int flags, String map, float yaw, float pitch, Vec3 origin, Vec3 velocity,
		int teleportSeq, Vec3 teleportOrigin, Vec3 teleportVelocity, byte[] keys, int mouse, int wheel, int teleportKind, HostPortal[] portals,
		float cursorX, float cursorY, @org.jspecify.annotations.Nullable Crossing crossing
	) {
		public boolean inGame() {
			return (flags & HOST_IN_GAME) != 0;
		}

		public boolean foreground() {
			return (flags & HOST_FOREGROUND) != 0;
		}

		public boolean scripted() {
			return (flags & HOST_SCRIPTED) != 0;
		}

		public boolean riding() {
			return (flags & HOST_RIDING) != 0;
		}

		public boolean keyDown(int scancode) {
			return scancode >= 0 && scancode < 256 && (keys[scancode >> 3] & (1 << (scancode & 7))) != 0;
		}
	}

	public static HostState readHostState(ByteBuffer b) {
		b.order(ByteOrder.LITTLE_ENDIAN);
		if (b.remaining() != HOST_STATE_SIZE || b.get(0) != 'P' || b.get(1) != 'C' || b.get(2) != 'H' || b.get(3) != '3') {
			return null;
		}
		b.position(4);
		int seq = b.getInt();
		int flags = b.getInt();
		byte[] mapBytes = new byte[64];
		b.get(mapBytes);
		int len = 0;
		while (len < 64 && mapBytes[len] != 0) {
			len++;
		}
		String map = new String(mapBytes, 0, len, StandardCharsets.US_ASCII);
		float yaw = b.getFloat();
		float pitch = b.getFloat();
		Vec3 origin = vec(b);
		Vec3 velocity = vec(b);
		int teleportSeq = b.getInt();
		Vec3 tpOrigin = vec(b);
		Vec3 tpVelocity = vec(b);
		byte[] keys = new byte[32];
		b.get(keys);
		int mouse = b.get() & 0xFF;
		int wheel = b.get(); // signed, wrapping
		int teleportKind = b.get() & 0xFF; // TeleportKind
		b.position(b.position() + 1);
		HostPortal[] portals = new HostPortal[2];
		for (int i = 0; i < 2; i++) {
			portals[i] = new HostPortal(b.getInt(), vec(b), vec(b));
		}
		float cursorX = b.getFloat(), cursorY = b.getFloat();
		int crossBase = b.getInt();
		boolean crossValid = b.getInt() != 0;
		double[] rot = new double[9];
		for (int i = 0; i < 9; i++) {
			rot[i] = b.getFloat();
		}
		Vec3 move = vec(b);
		return new HostState(seq, flags, map, yaw, pitch, origin, velocity, teleportSeq, tpOrigin, tpVelocity, keys, mouse, wheel, teleportKind, portals, cursorX,
			cursorY, crossValid ? new Crossing(crossBase, rot, move) : null);
	}

	/** One solid host entity (protocol HostEntity); positions in host units. */
	public record HostEntity(int index, int solid, int flags, Vec3 origin, Vec3 angles, Vec3 mins, Vec3 maxs, String model) {
	}

	public record HostEntities(int seq, java.util.List<HostEntity> entities) {
	}

	public static final int HOST_ENTITY_SIZE = 108;

	public static HostEntities readHostEntities(ByteBuffer b) {
		b.order(ByteOrder.LITTLE_ENDIAN);
		if (b.remaining() < 12 || b.get(0) != 'P' || b.get(1) != 'C' || b.get(2) != 'E' || b.get(3) != '1') {
			return null;
		}
		int seq = b.getInt(4);
		int count = b.getInt(8);
		if (count < 0 || 12 + (long) count * HOST_ENTITY_SIZE > b.remaining()) {
			return null;
		}
		java.util.List<HostEntity> out = new java.util.ArrayList<>(count);
		byte[] name = new byte[56];
		for (int i = 0; i < count; i++) {
			b.position(12 + i * HOST_ENTITY_SIZE);
			int index = b.getShort() & 0xFFFF;
			int solid = b.get() & 0xFF;
			int flags = b.get() & 0xFF;
			Vec3 origin = vec(b), angles = vec(b), mins = vec(b), maxs = vec(b);
			b.get(name);
			int len = 0;
			while (len < name.length && name[len] != 0) {
				len++;
			}
			out.add(new HostEntity(index, solid, flags, origin, angles, mins, maxs, new String(name, 0, len, StandardCharsets.US_ASCII)));
		}
		return new HostEntities(seq, out);
	}

	public static ByteBuffer writeMcState(int seq, int flags, int teleportAck, Vec3 origin, Vec3 velocity, boolean onGround,
		boolean sneaking, boolean holdingGun, int cameraMode, Vec3 tickPrevious, Vec3 tickCurrent, int tickSeq, float cameraDistance) {
		ByteBuffer b = ByteBuffer.allocate(MC_STATE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'P').put((byte) 'C').put((byte) 'M').put((byte) '3');
		b.putInt(seq).putInt(flags).putInt(teleportAck);
		putVec(b, origin);
		putVec(b, velocity);
		b.put((byte) (onGround ? 1 : 0)).put((byte) (sneaking ? 1 : 0)).put((byte) (holdingGun ? 1 : 0)).put((byte) cameraMode);
		putVec(b, tickPrevious);
		putVec(b, tickCurrent);
		b.putInt(tickSeq);
		b.putFloat(cameraDistance);
		return b.flip();
	}

	private static Vec3 vec(ByteBuffer b) {
		return new Vec3(b.getFloat(), b.getFloat(), b.getFloat());
	}

	private static void putVec(ByteBuffer b, Vec3 v) {
		b.putFloat((float) v.x).putFloat((float) v.y).putFloat((float) v.z);
	}
}
