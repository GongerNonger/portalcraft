package dev.portalcraft.client.world;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

import dev.portalcraft.host.HostLink;
import dev.portalcraft.host.Units;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Steve's blocks as solid ground for the host's physics (as SkyCraft makes its NPCs stand on and
 * bump into Minecraft's blocks): the collision boxes of the real blocks around the player, full
 * cubes merged into runs along x, sent as "PCS1" count, then count x (mins xyz, maxs xyz) in host
 * units. The host makes them static physics objects, so cubes rest on a block ledge, turrets
 * stand on it and energy balls bounce off it. Sent when they change, and every 2 s regardless
 * (the host may have restarted or loaded a level).
 */
public final class BlockSolids {
	/** Chunks either side of the player's, and sections above and below. */
	private static final int CHUNK_RADIUS = 2, SECTION_RADIUS = 2;
	/** Most boxes per packet (24 bytes each): the nearest are kept. */
	private static final int MAX_BOXES = 512;
	private static final int SCAN_TICKS = 10, RESEND_TICKS = 40;

	private static float[] sent = new float[0];
	private static int ticks, sinceSent;

	private BlockSolids() {
	}

	/** Once a client tick while linked. */
	public static void tick(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null || ++ticks % SCAN_TICKS != 0) {
			return;
		}
		sinceSent += SCAN_TICKS;
		float[] boxes = scan(level, minecraft.player.blockPosition());
		if (Arrays.equals(boxes, sent) && sinceSent < RESEND_TICKS) {
			return;
		}
		sent = boxes;
		sinceSent = 0;
		int count = boxes.length / 6;
		ByteBuffer b = ByteBuffer.allocate(8 + boxes.length * 4).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'P').put((byte) 'C').put((byte) 'S').put((byte) '1').putInt(count);
		for (float f : boxes) {
			b.putFloat(f);
		}
		HostLink.send(b.flip());
	}

	private static float[] scan(ClientLevel level, BlockPos at) {
		FloatArrayList out = new FloatArrayList();
		int pcx = SectionPos.blockToSectionCoord(at.getX()), pcy = SectionPos.blockToSectionCoord(at.getY()),
			pcz = SectionPos.blockToSectionCoord(at.getZ());
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		// Nearest chunks first, so the cap drops the far ones.
		for (int ring = 0; ring <= CHUNK_RADIUS; ring++) {
			for (int cx = pcx - ring; cx <= pcx + ring; cx++) {
				for (int cz = pcz - ring; cz <= pcz + ring; cz++) {
					if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) != ring) {
						continue;
					}
					LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
					if (chunk == null) {
						continue;
					}
					for (int sy = pcy - SECTION_RADIUS; sy <= pcy + SECTION_RADIUS; sy++) {
						int index = chunk.getSectionIndexFromSectionY(sy);
						if (index < 0 || index >= chunk.getSections().length) {
							continue;
						}
						LevelChunkSection section = chunk.getSections()[index];
						if (section.hasOnlyAir()) {
							continue;
						}
						scanSection(level, section, cx << 4, sy << 4, cz << 4, pos, out);
						if (out.size() >= MAX_BOXES * 6) {
							return Arrays.copyOf(out.toFloatArray(), MAX_BOXES * 6);
						}
					}
				}
			}
		}
		return out.toFloatArray();
	}

	private static void scanSection(ClientLevel level, LevelChunkSection section, int ox, int oy, int oz, BlockPos.MutableBlockPos pos,
		FloatArrayList out) {
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				int runStart = -1;
				for (int x = 0; x <= 16; x++) {
					boolean full = false;
					if (x < 16) {
						BlockState state = section.getBlockState(x, y, z);
						if (!state.isAir()) {
							pos.set(ox + x, oy + y, oz + z);
							VoxelShape shape = state.getCollisionShape(level, pos);
							full = !shape.isEmpty() && Block.isShapeFullBlock(shape);
							if (!full && !shape.isEmpty()) {
								for (AABB box : shape.toAabbs()) {
									add(out, box.move(pos.getX(), pos.getY(), pos.getZ()));
								}
							}
						}
					}
					if (full && runStart < 0) {
						runStart = x;
					} else if (!full && runStart >= 0) {
						add(out, new AABB(ox + runStart, oy + y, oz + z, ox + x, oy + y + 1, oz + z + 1));
						runStart = -1;
					}
				}
			}
		}
	}

	private static void add(FloatArrayList out, AABB box) {
		Vec3 a = Units.toSrc(new Vec3(box.minX, box.minY, box.minZ)), b = Units.toSrc(new Vec3(box.maxX, box.maxY, box.maxZ));
		out.add((float) Math.min(a.x, b.x));
		out.add((float) Math.min(a.y, b.y));
		out.add((float) Math.min(a.z, b.z));
		out.add((float) Math.max(a.x, b.x));
		out.add((float) Math.max(a.y, b.y));
		out.add((float) Math.max(a.z, b.z));
	}
}
