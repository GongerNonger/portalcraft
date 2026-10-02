package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.junit.jupiter.api.Test;

/** HostState's wire layout, as protocol/portalcraft_protocol.h lays it out (PCH2, 236 bytes). */
class ProtoTest {
	private static ByteBuffer hostState() {
		ByteBuffer b = ByteBuffer.allocate(Proto.HOST_STATE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'P').put((byte) 'C').put((byte) 'H').put((byte) '2');
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
		b.put((byte) 0); // pad
		for (int i = 0; i < 2; i++) {
			b.putInt(i + 1).putFloat(i).putFloat(i).putFloat(i).putFloat(0).putFloat(90).putFloat(0);
		}
		b.putFloat(0.25F).putFloat(0.75F); // cursor
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
	}

	@Test
	void rejectsOtherLayouts() {
		ByteBuffer b = hostState();
		b.put(3, (byte) '1'); // PCH1
		assertNull(Proto.readHostState(b));
		assertNull(Proto.readHostState(ByteBuffer.allocate(228).order(ByteOrder.LITTLE_ENDIAN)));
	}
}
