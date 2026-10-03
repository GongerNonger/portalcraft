package dev.portalcraft.client.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
		// PCW2 appends after the PCW1 layout, which stays where it was.
		assertEquals(16781312L, WorldFormat.MESH_OFFSET);
		assertEquals(26218496L, WorldFormat.PCW1_BYTES);
		assertEquals(26218496L, WorldFormat.ITEM_ATLAS_OFFSET); // kWorldItemAtlasOffset
		assertEquals(1024L * 1024 * 4, WorldFormat.ITEM_ATLAS_BYTES); // kWorldItemAtlasBytes
		assertEquals(30412800L, WorldFormat.ENTITY_OFFSET); // kWorldEntityOffset
		assertEquals(65536L * 24, WorldFormat.ENTITY_SLOT_BYTES); // kWorldEntitySlotBytes
		assertEquals(33558528L, WorldFormat.PCW2_BYTES); // kWorldPcw2Bytes
		assertEquals(WorldFormat.ENTITY_OFFSET + 2 * WorldFormat.ENTITY_SLOT_BYTES, WorldFormat.PCW2_BYTES);
		assertEquals(33558528L, WorldFormat.SKIN_OFFSET); // kWorldSkinOffset
		assertEquals(33820672L, WorldFormat.PCW3_BYTES); // kWorldPcw3Bytes
		assertEquals(38014976L, WorldFormat.CRACK_OFFSET); // kWorldCrackOffset
		assertEquals(38146048L, WorldFormat.PCW4_BYTES); // kWorldPcw4Bytes
		assertEquals(42340352L, WorldFormat.TOTAL_BYTES); // kWorldBytes
	}

	@Test
	void headerOffsetsMatchProtocol() {
		// PCW1 fields, unchanged
		assertEquals(12, WorldFormat.H_ATLAS_SEQ);
		assertEquals(20, WorldFormat.H_FRONT);
		assertEquals(24, WorldFormat.H_READING);
		assertEquals(28, WorldFormat.H_SLOT_SOLID);
		assertEquals(36, WorldFormat.H_SLOT_TRANSLUCENT);
		// PCW2 fields: offsetof(WorldHeader, ...) as static_asserted in the header
		assertEquals(44, WorldFormat.H_ITEM_ATLAS_W);
		assertEquals(48, WorldFormat.H_ITEM_ATLAS_H);
		assertEquals(52, WorldFormat.H_ITEM_ATLAS_SEQ);
		assertEquals(56, WorldFormat.H_ENTITY_SEQ);
		assertEquals(60, WorldFormat.H_ENTITY_FRONT);
		assertEquals(64, WorldFormat.H_ENTITY_READING);
		assertEquals(68, WorldFormat.H_ENTITY_BLOCK_SOLID);
		assertEquals(76, WorldFormat.H_ENTITY_BLOCK_TRANSLUCENT);
		assertEquals(84, WorldFormat.H_ENTITY_ITEM_SOLID);
		assertEquals(92, WorldFormat.H_ENTITY_ITEM_TRANSLUCENT);
		// PCW3 fields
		assertEquals(100, WorldFormat.H_SKIN_W);
		assertEquals(108, WorldFormat.H_SKIN_SEQ);
		assertEquals(112, WorldFormat.H_AVATAR_SKIN);
		assertEquals(144, WorldFormat.H_AVATAR_ITEM_TRANSLUCENT);
		// PCW4 fields
		assertEquals(152, WorldFormat.H_PARTICLE_ATLAS_W);
		assertEquals(172, WorldFormat.H_CRACK_SEQ);
		assertEquals(176, WorldFormat.H_PARTICLE_SOLID);
		assertEquals(192, WorldFormat.H_CRACK);
		// PCW5 fields
		assertEquals(200, WorldFormat.H_MOB_ATLAS_W);
		assertEquals(212, WorldFormat.H_MOB_SOLID);
		assertEquals(220, WorldFormat.H_MOB_TRANSLUCENT);
		assertEquals(228, WorldFormat.H_AVATAR_MOB_SOLID);
		assertEquals(244, WorldFormat.HEADER_STRUCT_BYTES); // sizeof(WorldHeader)
		assertEquals(WorldFormat.H_AVATAR_MOB_TRANSLUCENT + 2 * 4, WorldFormat.HEADER_STRUCT_BYTES); // two slots per count
		assertTrue(WorldFormat.HEADER_STRUCT_BYTES <= WorldFormat.HEADER_BYTES);
	}

	@Test
	void mappingFitsTheAddressBudget() {
		// hl2.exe is 32-bit: the overlay (42.2 MB) plus this must stay well under 100 MB of views.
		// PCW4's particle atlas and crack strip took it from 32 to 36.4 MB, PCW5's mob atlas to 40.4.
		assertTrue(WorldFormat.TOTAL_BYTES <= 44L << 20, "world mapping " + WorldFormat.TOTAL_BYTES);
		assertEquals(0, WorldFormat.MOB_ATLAS_OFFSET % 4096);
		assertEquals(0, WorldFormat.PARTICLE_ATLAS_OFFSET % 4096);
		assertEquals(0, WorldFormat.CRACK_OFFSET % 4096);
		// Every region is page aligned, so the views map without surprises.
		assertEquals(0, WorldFormat.ITEM_ATLAS_OFFSET % 4096);
		assertEquals(0, WorldFormat.ENTITY_OFFSET % 4096);
		assertEquals(0, WorldFormat.ENTITY_SLOT_BYTES % 4096);
	}

	@Test
	void entityBudgetHoldsTypicalDrops() {
		// A full stack (5 copies) of a flat item with ~150 quads, and of a block (6 quads).
		int toolStack = 5 * 150 * 6, blockStack = 5 * 6 * 6;
		assertTrue(WorldFormat.ENTITY_MAX_VERTICES >= 14 * toolStack, "14 full stacks of tools");
		assertTrue(WorldFormat.ENTITY_MAX_VERTICES >= 300 * blockStack, "300 full stacks of blocks");
	}

	@Test
	void itemAtlasFitsVanillaAndOneDoubling() {
		assertTrue(WorldFormat.itemAtlasFits(1024, 512)); // vanilla 26.3 (with PortalCraft's gun)
		assertTrue(WorldFormat.itemAtlasFits(1024, 1024));
		assertTrue(WorldFormat.itemAtlasFits(2048, 512));
		assertFalse(WorldFormat.itemAtlasFits(2048, 1024));
		assertFalse(WorldFormat.itemAtlasFits(4096, 256)); // too wide for the texture cap
		assertFalse(WorldFormat.itemAtlasFits(0, 512));
	}

	@Test
	void itemBobMatchesItemEntityRenderer() {
		// sin(age / 10 + bobOffset) * 0.1 + 0.1: between 0 and 0.2 blocks, period 20*pi ticks.
		assertEquals(0.1F, WorldFormat.itemBob(0.0F, 0.0F), 1e-6F);
		assertEquals(0.2F, WorldFormat.itemBob((float) (5 * Math.PI), 0.0F), 1e-5F);
		assertEquals(0.0F, WorldFormat.itemBob((float) (15 * Math.PI), 0.0F), 1e-5F);
		assertEquals(WorldFormat.itemBob(3.0F, 1.5F), WorldFormat.itemBob(3.0F + (float) (20 * Math.PI), 1.5F), 1e-4F);
		for (float t = 0; t < 200; t += 0.37F) {
			float b = WorldFormat.itemBob(t, 2.0F);
			assertTrue(b >= -1e-6F && b <= 0.2F + 1e-6F);
		}
	}

	@Test
	void shadeLikeBlockFaces() {
		assertEquals(1.0F, WorldFormat.shade(0, 1, 0), 1e-6F);
		assertEquals(0.5F, WorldFormat.shade(0, -1, 0), 1e-6F);
		assertEquals(0.8F, WorldFormat.shade(0, 0, -1), 1e-6F);
		assertEquals(0.6F, WorldFormat.shade(1, 0, 0), 1e-6F);
		assertEquals(0.6F, WorldFormat.shade(4, 0, 0), 1e-6F); // not normalised going in
		assertEquals(0.7F, WorldFormat.shade(1, 0, 1), 1e-6F); // halfway round a spin
		assertEquals(1.0F, WorldFormat.shade(0, 0, 0), 1e-6F); // degenerate: unshaded
	}

	@Test
	void shadeKeepsAlpha() {
		assertEquals(0x80404040, WorldFormat.shadeArgb(0x80808080, 0.5F));
		assertEquals(0xFF808080, WorldFormat.shadeArgb(-1, 0.5F)); // 127.5 rounds up
		assertEquals(0xFF29527A, WorldFormat.shadeArgb(0xFF336699, 0.8F)); // 0x33*0.8=40.8, 0x66*0.8=81.6, 0x99*0.8=122.4
		assertEquals(0xFFFFFFFF, WorldFormat.shadeArgb(-1, 1.0F));
		assertEquals(0xFF000000, WorldFormat.shadeArgb(-1, 0.0F));
	}

	@Test
	void truncateUndoesAnEntity() {
		WorldFormat.Vertices v = new WorldFormat.Vertices(2);
		for (int i = 0; i < 5; i++) {
			v.add(i, 0, 0, -1, 0, 0);
		}
		v.truncate(3);
		assertEquals(3, v.count());
		v.truncate(10); // never grows
		assertEquals(3, v.count());
		v.add(9, 0, 0, -1, 0, 0);
		assertEquals(4, v.count());
		assertEquals(9.0F * (float) dev.portalcraft.host.Units.PER_BLOCK, Float.intBitsToFloat(v.data()[3 * WorldFormat.VERTEX_INTS]));
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
