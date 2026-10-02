package dev.portalcraft.client.world;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.QuadInstance;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.portalcraft.PortalCraft;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Meshes the blocks around the player with Minecraft's own block renderer (models, tint, ambient
 * occlusion and face shading baked into the vertex colour, since the host has no lighting for
 * them) and publishes the result into the host's world mapping, where Portal draws it in its own
 * 3D pass. Render thread only, except {@link #markDirty}.
 *
 * <p>Each 16^3 section is meshed on its own and kept; whenever one changes, the combined mesh of
 * every section within {@link #RANGE_BLOCKS} of the player goes into the free mesh slot.
 *
 * <p>Adapted from SkyCraft's {@code WorldExporter} (chasmlol/SkyCraft, MIT): the dirty-section
 * queue fed by a {@code LevelExtractor} mixin and the {@code BlockQuadOutput} quad collector.
 */
public final class WorldExporter {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	/** Meshing time per frame. Empty sections (most of a void world) cost only a lookup. */
	private static final long MESH_NANOS_PER_FRAME = 3_000_000L;
	/** Sections within this many blocks of the player are published. */
	private static final double RANGE_BLOCKS = 128.0;
	/** While a backlog is being meshed (link, chunk loads), publish at most this often. */
	private static final long BULK_PUBLISH_MS = 250;
	private static final long SWEEP_MS = 5000;

	private static final LongLinkedOpenHashSet DIRTY = new LongLinkedOpenHashSet();
	private static final Long2ObjectOpenHashMap<SectionMesh> SECTIONS = new Long2ObjectOpenHashMap<>();
	private static final MeshBuilder MESH = new MeshBuilder();

	private static int sentGeneration = Integer.MIN_VALUE;
	private static ClientLevel sentLevel;
	private static WorldAtlas atlas;
	private static ModelBlockRenderer blockRenderer;
	private static FluidRenderer fluidRenderer;
	private static boolean needPublish;
	private static long lastPublishMs;
	private static long lastSweepMs;
	private static long playerSection = Long.MIN_VALUE;
	private static int publishes;
	private static boolean overBudgetLogged;
	private static boolean failureLogged;

	/** One section's mesh: packed vertices (WorldFormat.VERTEX_INTS ints each). */
	private record SectionMesh(int[] solid, int solidCount, int[] translucent, int translucentCount) {
		int vertices() {
			return this.solidCount + this.translucentCount;
		}
	}

	private record Near(double distance2, SectionMesh mesh) {
	}

	private WorldExporter() {
	}

	/** From LevelExtractorMixin: a block in this section (or its light) changed, or it loaded. */
	public static void markDirty(int sx, int sy, int sz, boolean playerChanged) {
		long key = SectionPos.asLong(sx, sy, sz);
		synchronized (DIRTY) {
			if (playerChanged) {
				DIRTY.addAndMoveToFirst(key); // a block the player just placed or broke: first
			} else {
				DIRTY.add(key);
			}
		}
	}

	/** Once per frame while linked and the world mapping is open: the blocks, then the entities. */
	public static void frame(Minecraft minecraft) {
		try {
			run(minecraft);
		} catch (RuntimeException e) {
			if (!failureLogged) {
				failureLogged = true;
				LOG.error("PortalCraft: world export failed (logged once)", e);
			}
		}
		EntityExporter.frame(minecraft);
	}

	private static void run(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null || minecraft.gui.overlay() != null) {
			return; // no world, or resources are reloading
		}
		if (sentGeneration != WorldLink.generation() || sentLevel != level || atlas == null || atlas.stale(minecraft)) {
			if (!reset(minecraft, level)) {
				return;
			}
		}
		boolean backlog = meshDirtySections(minecraft, level);

		Vec3 eye = minecraft.player.position();
		long now = System.currentTimeMillis();
		if (now - lastSweepMs > SWEEP_MS) {
			lastSweepMs = now;
			sweepUnloaded(level);
		}
		long section = SectionPos.asLong(minecraft.player.blockPosition());
		if (section != playerSection) {
			playerSection = section;
			needPublish = true; // what's in range changed
		}
		if (needPublish && (!backlog || now - lastPublishMs >= BULK_PUBLISH_MS)) {
			if (publish(eye)) {
				needPublish = false;
				lastPublishMs = now;
			}
		}
	}

	/** A new mapping, level or atlas: send the atlas and mesh everything again. False if not ready. */
	private static boolean reset(Minecraft minecraft, ClientLevel level) {
		boolean newAtlas = atlas == null || atlas.stale(minecraft);
		if (newAtlas) {
			long t0 = System.nanoTime();
			WorldAtlas built = WorldAtlas.build(minecraft, AtlasIds.BLOCKS);
			if (built == null) {
				return false;
			}
			atlas = built;
			LOG.info("PortalCraft: block atlas is {}x{} (built in {} ms)", atlas.width, atlas.height, (System.nanoTime() - t0) / 1_000_000);
		}
		if (newAtlas || sentGeneration != WorldLink.generation()) {
			if (atlas.fitsMapping()) {
				WorldLink.writeAtlas(atlas.width, atlas.height, atlas.pixels);
				LOG.info("PortalCraft: sent the {}x{} block atlas to the host", atlas.width, atlas.height);
			} else {
				LOG.warn("PortalCraft: block atlas {}x{} is bigger than the mapping's {}x{}; not sending it", atlas.width, atlas.height,
					WorldFormat.ATLAS_MAX_W, WorldFormat.ATLAS_MAX_H);
			}
		}
		sentGeneration = WorldLink.generation();
		sentLevel = level;
		blockRenderer = new ModelBlockRenderer(minecraft.options.ambientOcclusion().get(), true, minecraft.getBlockColors());
		fluidRenderer = new FluidRenderer(minecraft.getModelManager().getFluidStateModelSet());
		SECTIONS.clear();
		overBudgetLogged = false;
		needPublish = true; // an empty mesh at once, so the host drops anything stale

		// Everything already loaded, nearest first; later chunk loads mark themselves dirty.
		int radius = minecraft.options.getEffectiveRenderDistance() + 1;
		BlockPos p = minecraft.player.blockPosition();
		int pcx = SectionPos.blockToSectionCoord(p.getX()), pcy = SectionPos.blockToSectionCoord(p.getY()), pcz = SectionPos.blockToSectionCoord(p.getZ());
		LongArrayList keys = new LongArrayList();
		for (int cx = pcx - radius; cx <= pcx + radius; cx++) {
			for (int cz = pcz - radius; cz <= pcz + radius; cz++) {
				LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					if (!sections[i].hasOnlyAir()) {
						keys.add(SectionPos.asLong(cx, chunk.getSectionYFromSectionIndex(i), cz));
					}
				}
			}
		}
		keys.sort((a, b) -> Long.compare(sectionDistance(a, pcx, pcy, pcz), sectionDistance(b, pcx, pcy, pcz)));
		synchronized (DIRTY) {
			DIRTY.clear();
			for (int i = 0; i < keys.size(); i++) {
				DIRTY.add(keys.getLong(i));
			}
		}
		LOG.info("PortalCraft: world export reset: {} non-empty sections to mesh", keys.size());
		return true;
	}

	private static long sectionDistance(long key, int x, int y, int z) {
		long dx = SectionPos.x(key) - x, dy = SectionPos.y(key) - y, dz = SectionPos.z(key) - z;
		return dx * dx + dy * dy + dz * dz;
	}

	/** Meshes dirty sections for up to a few ms. Returns true if some are still waiting. */
	private static boolean meshDirtySections(Minecraft minecraft, ClientLevel level) {
		long deadline = System.nanoTime() + MESH_NANOS_PER_FRAME;
		while (System.nanoTime() < deadline) {
			long key;
			synchronized (DIRTY) {
				if (DIRTY.isEmpty()) {
					return false;
				}
				key = DIRTY.removeFirstLong();
			}
			meshSection(minecraft, level, key);
		}
		synchronized (DIRTY) {
			return !DIRTY.isEmpty();
		}
	}

	private static void meshSection(Minecraft minecraft, ClientLevel level, long key) {
		int sx = SectionPos.x(key), sy = SectionPos.y(key), sz = SectionPos.z(key);
		LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, ChunkStatus.FULL, false);
		int index = level.getSectionIndexFromSectionY(sy);
		LevelChunkSection section = chunk == null || index < 0 || index >= chunk.getSections().length ? null : chunk.getSections()[index];
		if (section == null || section.hasOnlyAir()) {
			if (SECTIONS.remove(key) != null) {
				needPublish = true;
			}
			return;
		}
		int ox = SectionPos.sectionToBlockCoord(sx), oy = SectionPos.sectionToBlockCoord(sy), oz = SectionPos.sectionToBlockCoord(sz);
		BlockStateModelSet models = minecraft.getModelManager().getBlockStateModelSet();
		MESH.begin(ox, oy, oz);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int y = 0; y < 16; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					BlockState state = section.getBlockState(x, y, z);
					if (state.isAir()) {
						continue;
					}
					pos.set(ox + x, oy + y, oz + z);
					FluidState fluid = state.getFluidState();
					if (!fluid.isEmpty()) {
						fluidRenderer.tesselate(level, pos.immutable(), MESH, state, fluid);
					}
					if (state.getRenderShape() == RenderShape.MODEL) {
						BlockPos at = pos.immutable();
						blockRenderer.tesselateBlock(MESH, x, y, z, level, at, state, models.get(state), state.getSeed(at));
					}
				}
			}
		}
		if (MESH.solid.count() == 0 && MESH.translucent.count() == 0) {
			if (SECTIONS.remove(key) != null) {
				needPublish = true;
			}
			return;
		}
		SECTIONS.put(key, new SectionMesh(MESH.solid.toArray(), MESH.solid.count(), MESH.translucent.toArray(), MESH.translucent.count()));
		needPublish = true;
	}

	/** Drops the meshes of sections whose chunk Minecraft unloaded (it doesn't draw them either). */
	private static void sweepUnloaded(ClientLevel level) {
		var it = SECTIONS.long2ObjectEntrySet().iterator();
		while (it.hasNext()) {
			long key = it.next().getLongKey();
			if (level.getChunkSource().getChunk(SectionPos.x(key), SectionPos.z(key), ChunkStatus.FULL, false) == null) {
				it.remove();
				needPublish = true;
			}
		}
	}

	/** Writes the combined mesh in range into the free slot and makes it the newest. False if no slot is free. */
	private static boolean publish(Vec3 player) {
		int slot = WorldLink.freeSlot();
		if (slot < 0) {
			return false; // the host is between frames on both slots; try next frame
		}
		long t0 = System.nanoTime();
		double range2 = RANGE_BLOCKS * RANGE_BLOCKS;
		List<Near> near = new ArrayList<>();
		for (Long2ObjectMap.Entry<SectionMesh> e : SECTIONS.long2ObjectEntrySet()) {
			double d2 = distanceToSection2(player, e.getLongKey());
			if (d2 <= range2) {
				near.add(new Near(d2, e.getValue()));
			}
		}
		near.sort((a, b) -> Double.compare(a.distance2(), b.distance2()));
		int n = near.size();
		SectionMesh[] sorted = new SectionMesh[n];
		int[] counts = new int[n];
		for (int i = 0; i < n; i++) {
			sorted[i] = near.get(i).mesh();
			counts[i] = sorted[i].vertices();
		}
		int fit = WorldFormat.fitting(counts, n, WorldFormat.MAX_VERTICES);
		if (fit < n && !overBudgetLogged) {
			overBudgetLogged = true;
			LOG.warn("PortalCraft: world mesh over the {}-vertex budget: publishing the nearest {} of {} sections (logged once)",
				WorldFormat.MAX_VERTICES, fit, n);
		}
		// Solid and cutout first; then translucent, farthest section first so blending stacks better.
		int solid = 0;
		for (int i = 0; i < fit; i++) {
			WorldLink.writeVertices(slot, solid, sorted[i].solid(), sorted[i].solidCount());
			solid += sorted[i].solidCount();
		}
		int translucent = 0;
		for (int i = fit - 1; i >= 0; i--) {
			WorldLink.writeVertices(slot, solid + translucent, sorted[i].translucent(), sorted[i].translucentCount());
			translucent += sorted[i].translucentCount();
		}
		WorldLink.publish(slot, solid, translucent);
		publishes++;
		if (publishes <= 10 || publishes % 200 == 0) {
			LOG.info("PortalCraft: world mesh #{}: {} solid + {} translucent vertices from {} sections (slot {}, {} ms; {} sections meshed in all)",
				publishes, solid, translucent, fit, slot, (System.nanoTime() - t0) / 100_000 / 10.0, SECTIONS.size());
		}
		return true;
	}

	/** Squared distance from the player to the nearest point of a section's box. */
	private static double distanceToSection2(Vec3 p, long key) {
		double x0 = SectionPos.sectionToBlockCoord(SectionPos.x(key)), y0 = SectionPos.sectionToBlockCoord(SectionPos.y(key)),
			z0 = SectionPos.sectionToBlockCoord(SectionPos.z(key));
		double dx = p.x - Math.clamp(p.x, x0, x0 + 16), dy = p.y - Math.clamp(p.y, y0, y0 + 16), dz = p.z - Math.clamp(p.z, z0, z0 + 16);
		return dx * dx + dy * dy + dz * dz;
	}

	/**
	 * Collects one section's block quads (and fluid faces) as triangles in host space, split into
	 * solid/cutout and translucent. Positions come in section-relative; the section origin is
	 * added here, in double, before the conversion to Source units.
	 */
	private static final class MeshBuilder implements BlockQuadOutput, FluidRenderer.Output, VertexConsumer {
		final WorldFormat.Vertices solid = new WorldFormat.Vertices(8192);
		final WorldFormat.Vertices translucent = new WorldFormat.Vertices(1024);
		private int ox, oy, oz;
		// fluid quad assembly: x, y, z, u, v, colour per corner
		private final float[] fq = new float[4 * 5];
		private final int[] fc = new int[4];
		private int fqCount;
		private boolean fluidTranslucent;

		void begin(int ox, int oy, int oz) {
			this.ox = ox;
			this.oy = oy;
			this.oz = oz;
			this.solid.clear();
			this.translucent.clear();
			this.fqCount = 0;
		}

		// ---- block quads ----
		@Override
		public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
			boolean isTranslucent = quad.materialInfo().layer().translucent();
			WorldFormat.Vertices out = isTranslucent ? this.translucent : this.solid;
			for (int k : WorldFormat.QUAD_TRIANGLES) {
				Vector3fc p = quad.position(k);
				long uv = quad.packedUV(k);
				out.add(
					this.ox + (double) (x + p.x()), this.oy + (double) (y + p.y()), this.oz + (double) (z + p.z()),
					WorldFormat.d3dColor(instance.getColor(k), isTranslucent), UVPair.unpackU(uv), UVPair.unpackV(uv)
				);
			}
		}

		// ---- fluids: FluidRenderer emits quads of 4 vertices, section-relative, colour already shaded ----
		@Override
		public VertexConsumer getBuilder(ChunkSectionLayer layer) {
			this.fluidTranslucent = layer.translucent();
			return this;
		}

		@Override
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny, float nz) {
			int o = this.fqCount * 5;
			this.fq[o] = x;
			this.fq[o + 1] = y;
			this.fq[o + 2] = z;
			this.fq[o + 3] = u;
			this.fq[o + 4] = v;
			this.fc[this.fqCount] = color;
			if (++this.fqCount < 4) {
				return;
			}
			this.fqCount = 0;
			WorldFormat.Vertices out = this.fluidTranslucent ? this.translucent : this.solid;
			for (int k : WorldFormat.QUAD_TRIANGLES) {
				int b = k * 5;
				out.add(this.ox + (double) this.fq[b], this.oy + (double) this.fq[b + 1], this.oz + (double) this.fq[b + 2],
					WorldFormat.d3dColor(this.fc[k], this.fluidTranslucent), this.fq[b + 3], this.fq[b + 4]);
			}
		}

		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			throw new UnsupportedOperationException("WorldExporter only takes whole fluid vertices");
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setLineWidth(float width) {
			return this;
		}
	}
}
