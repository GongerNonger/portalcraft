package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/** HostState's wire layout, as protocol/portalcraft_protocol.h lays it out (PCH7, 344 bytes). */
class ProtoTest {
	private static ByteBuffer hostState() {
		ByteBuffer b = ByteBuffer.allocate(Proto.HOST_STATE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'P').put((byte) 'C').put((byte) 'H').put((byte) '7');
		b.putInt(7).putInt(1); // seq, flags (in game)
		b.put(new byte[64]); // map
		b.putFloat(90.0F).putFloat(-10.0F); // yaw, pitch
		b.putFloat(1).putFloat(2).putFloat(3); // origin
		b.putFloat(4).putFloat(5).putFloat(6); // velocity
		b.putInt(42); // teleportSeq
		b.putFloat(7).putFloat(8).putFloat(9); // teleportOrigin
		b.putFloat(10).putFloat(11).putFloat(12); // teleportVelocity
		byte[] keys = new byte[32];
		keys[26 >> 3] |= (byte) (1 << (26 & 7)); // W
		b.put(keys);
		b.put((byte) 3); // mouse
		b.put((byte) -2); // wheel
		b.put((byte) Proto.MOVE_IMPULSE); // teleportKind
		b.put((byte) 0x83); // shots: the third, orange
		for (int i = 0; i < 2; i++) {
			b.putInt(i + 1).putFloat(i).putFloat(i).putFloat(i).putFloat(0).putFloat(90).putFloat(0);
		}
		b.putFloat(0.25F).putFloat(0.75F); // cursor
		b.putInt(41).putInt(1); // crossBase, crossValid
		for (float r : new float[] {-1, 0, 0, 0, -1, 0, 0, 0, 1}) {
			b.putFloat(r); // a half turn about z
		}
		b.putFloat(100).putFloat(0).putFloat(0); // crossMove
		b.putInt(5); // crossMatched
		b.putFloat(0.25F).putFloat(0.5F).putFloat(0.125F); // handLight
		b.putInt(2); // gunEffect: holding
		b.putFloat(40).putFloat(0).putFloat(21); // baseVelocity
		b.putFloat(18.0F); // playerCentre: ducked
		b.putInt(57).putFloat(-64).putFloat(128).putFloat(900.5F); // moverIndex, moverOrigin
		return b.flip();
	}

	@Test
	void readsEveryField() {
		ByteBuffer b = hostState();
		assertEquals(Proto.HOST_STATE_SIZE, b.remaining());
		Proto.HostState s = Proto.readHostState(b);
		assertNotNull(s);
		assertEquals(42, s.teleportSeq());
		assertEquals(9.0, s.teleportOrigin().z, 1e-6);
		assertEquals(12.0, s.teleportVelocity().z, 1e-6);
		assertEquals(3, s.mouse());
		assertEquals(-2, s.wheel());
		assertEquals(Proto.MOVE_IMPULSE, s.teleportKind());
		assertEquals(0.75F, s.cursorY(), 1e-6);
		assertEquals(true, s.keyDown(26));
		assertEquals(false, s.keyDown(4));
		assertNotNull(s.crossing());
		assertEquals(41, s.crossing().base());
		assertEquals(5, s.crossMatched());
		assertEquals(0x83, s.shots());
		assertEquals(0.5, s.handLight().y, 1e-6);
		assertEquals(2, s.gunEffect());
		assertEquals(40.0, s.baseVelocity().x, 1e-6);
		assertEquals(21.0, s.baseVelocity().z, 1e-6);
		assertEquals(18.0F, s.playerCentre(), 1e-6);
		assertEquals(true, s.overMover());
		assertEquals(57, s.moverIndex());
		assertEquals(900.5, s.moverOrigin().z, 1e-6);
		assertEquals(90.0, s.crossing().point(new net.minecraft.world.phys.Vec3(10, 0, 5)).x, 1e-6);
		assertEquals(5.0, s.crossing().point(new net.minecraft.world.phys.Vec3(10, 0, 5)).z, 1e-6);
	}

	@Test
	void writesMcStateWithEachCrossingsCentre() {
		// McState (PCM5, 120 bytes): the four crossCentre floats follow crossPortal, then the mover.
		net.minecraft.world.phys.Vec3 at = new net.minecraft.world.phys.Vec3(1, 2, 3);
		ByteBuffer b = Proto.writeMcState(9, Proto.MC_READY, 4, at, at, true, true, false, 0, at, at, 12, 0.0F, 6, 5,
			new byte[] {0, 1, (byte) 0xFF, 1}, new float[] {36.0F, 18.0F, 0.0F, 36.0F}, 0, at);
		b.order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(Proto.MC_STATE_SIZE, b.remaining());
		assertEquals(120, b.remaining());
		assertEquals('5', b.get(3));
		assertEquals(6, b.getInt(76)); // crossCount
		assertEquals(1, b.get(85)); // crossPortal[1]
		assertEquals(36.0F, b.getFloat(88), 1e-6);
		assertEquals(18.0F, b.getFloat(92), 1e-6);
		assertEquals(36.0F, b.getFloat(100), 1e-6);
	}

	@Test
	void rejectsOtherLayouts() {
		ByteBuffer b = hostState();
		b.put(3, (byte) '6'); // PCH6
		assertNull(Proto.readHostState(b));
		assertNull(Proto.readHostState(ByteBuffer.allocate(228).order(ByteOrder.LITTLE_ENDIAN)));
	}

	/** McState's wire layout (PCM5, 120 bytes): the mover is the last sixteen. */
	@Test
	void writesTheMoverAfterEverythingElse() {
		net.minecraft.world.phys.Vec3 zero = net.minecraft.world.phys.Vec3.ZERO;
		ByteBuffer b = Proto.writeMcState(1, Proto.MC_READY, 0, zero, zero, true, false, true, 0, zero, zero, 9, 0.0F, 3, 2, new byte[] {0, 1, 0, 1}, new float[4], 57,
			new net.minecraft.world.phys.Vec3(-64, 128, 900.5));
		b.order(ByteOrder.LITTLE_ENDIAN);
		assertEquals(Proto.MC_STATE_SIZE, b.remaining());
		assertEquals('5', b.get(3));
		assertEquals(1, b.get(87)); // the last of crossPortal, where PCM4 ended
		assertEquals(57, b.getInt(104));
		assertEquals(900.5F, b.getFloat(116), 1e-6);
	}
}
