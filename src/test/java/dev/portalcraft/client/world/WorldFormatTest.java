package dev.portalcraft.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import dev.portalcraft.host.Units;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** The world mapping's pure format: must match protocol/portalcraft_protocol.h. */
class WorldFormatTest {
	@Test
	void layoutMatchesProtocol() {
		assertEquals(24, WorldFormat.VERTEX_BYTES); // sizeof(WorldVertex)
		assertEquals(4096L + 2048L * 2048 * 4, WorldFormat.MESH_OFFSET); // kWorldMeshOffset
		assertEquals(196608L * 24, WorldFormat.SLOT_BYTES); // kWorldSlotBytes
		assertEquals(WorldFormat.MESH_OFFSET + 2 * WorldFormat.SLOT_BYTES, WorldFormat.TOTAL_BYTES); // kWorldBytes
	}

	@Test
	void freeSlotAvoidsFrontAndReading() {
		assertEquals(1, WorldFormat.freeSlot(0, WorldFormat.NO_SLOT));
		assertEquals(0, WorldFormat.freeSlot(1, WorldFormat.NO_SLOT));
		assertEquals(1, WorldFormat.freeSlot(0, 0));
		assertEquals(-1, WorldFormat.freeSlot(0, 1));
		assertEquals(-1, WorldFormat.freeSlot(1, 0));
	}

	@Test
	void colourIsD3dArgbWithOpaqueSolids() {
		assertEquals(0xFF336699, WorldFormat.d3dColor(0x00336699, false));
		assertEquals(0x80336699, WorldFormat.d3dColor(0x80336699, true));
		assertEquals(0xFFFFFFFF, WorldFormat.d3dColor(-1, false));
	}

	@Test
	void atlasPixelsAreRgbaBytes() {
		int packed = WorldFormat.argbToRgba(0x80112233); // A=80 R=11 G=22 B=33
		ByteBuffer b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(packed);
		assertEquals(0x11, b.get(0) & 0xFF);
		assertEquals(0x22, b.get(1) & 0xFF);
		assertEquals(0x33, b.get(2) & 0xFF);
		assertEquals(0x80, b.get(3) & 0xFF);
	}

	@Test
	void verticesAreHostSpaceLikeUnits() {
		WorldFormat.Vertices v = new WorldFormat.Vertices(1);
		v.add(1.5, 64.0, -3.25, 0xFF102030, 0.25F, 0.75F);
		v.add(-410.0, 2.0, 410.0, 0x7F000000, 1.0F, 0.0F); // grows past the initial capacity
		assertEquals(2, v.count());
		int[] d = v.toArray();
		assertEquals(2 * WorldFormat.VERTEX_INTS, d.length);
		Vec3 src = Units.toSrc(new Vec3(1.5, 64.0, -3.25));
		assertEquals((float) src.x, Float.intBitsToFloat(d[0]));
		assertEquals((float) src.y, Float.intBitsToFloat(d[1]));
		assertEquals((float) src.z, Float.intBitsToFloat(d[2]));
		assertEquals(0xFF102030, d[3]);
		assertEquals(0.25F, Float.intBitsToFloat(d[4]));
		assertEquals(0.75F, Float.intBitsToFloat(d[5]));
		Vec3 src2 = Units.toSrc(new Vec3(-410.0, 2.0, 410.0));
		assertEquals((float) src2.y, Float.intBitsToFloat(d[7]));
	}

	@Test
	void quadIsTwoTrianglesSharingTheDiagonal() {
		assertEquals(6, WorldFormat.QUAD_TRIANGLES.length);
		assertEquals("[0, 1, 2, 0, 2, 3]", java.util.Arrays.toString(WorldFormat.QUAD_TRIANGLES));
	}

	@Test
	void budgetTakesNearestPrefix() {
		int[] counts = {100, 200, 300, 50};
		assertEquals(4, WorldFormat.fitting(counts, 4, 650));
		assertEquals(3, WorldFormat.fitting(counts, 4, 600));
		assertEquals(2, WorldFormat.fitting(counts, 4, 599)); // stops at the first that doesn't fit
		assertEquals(0, WorldFormat.fitting(counts, 4, 99));
		assertEquals(0, WorldFormat.fitting(counts, 0, 10));
	}

	@Test
	void seqSkipsZero() {
		assertEquals(1, WorldFormat.nextSeq(0));
		assertEquals(1, WorldFormat.nextSeq(-1));
		assertEquals(6, WorldFormat.nextSeq(5));
	}
}
