package dev.portalcraft.client.world;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.portalcraft.PortalCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the dropped items around the player into the host's world mapping every render frame,
 * as one triangle mesh in host space (protocol: WorldHeader, entity slots), which Portal draws in
 * its own 3D pass after the blocks. Render thread only.
 *
 * <p>The geometry is Minecraft's own: each item is run through {@link ItemEntityRenderer}'s
 * stack-count logic with its bob, spin and ground transform, into a {@link SubmitNodeCollector}
 * that keeps the item quads instead of drawing them. So block items are their block model at the
 * ground scale (0.25), flat items are their extruded model (two-sided, with edges) at 0.5, and a
 * stack shows the same 1-5 copies as in Minecraft. Quads textured from the block atlas and the item
 * atlas go to separate ranges; the item atlas is sent once, like the block atlas.
 *
 * <p>Items whose model is a special renderer (chests, shields, heads, banners) fall back to a flat
 * quad of their particle sprite. Lighting is face shading only, matching the blocks (J8).
 *
 * <p>Adapted in part from SkyCraft's {@code WorldExporter.exportEntities/addItem/iconUv}
 * (chasmlol/SkyCraft, MIT): which entities to take, the interpolation, the icon fallback.
 */
public final class EntityExporter {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	/** Items within this many blocks of the player are published. */
	private static final double RANGE_BLOCKS = 64.0;
	private static final int FULL_BRIGHT = 0xF000F0;

	private static final ItemCapture CAPTURE = new ItemCapture();
	private static final ItemEntityRenderState STATE = new ItemEntityRenderState();
	private static final RandomSource RANDOM = RandomSource.create();
	private static final RandomSource PARTICLE_RANDOM = RandomSource.create();

	private static WorldAtlas itemAtlas;
	private static int sentGeneration = Integer.MIN_VALUE;
	private static boolean publishedEmpty;
	private static boolean overBudgetLogged;
	private static boolean failureLogged;
	private static boolean atlasTooBigLogged;
	private static int publishes;

	private record Near(double distance2, ItemEntity item) {
	}

	private EntityExporter() {
	}

	/** Once per render frame while linked and the world mapping is open (from WorldExporter.frame). */
	static void frame(Minecraft minecraft) {
		try {
			run(minecraft);
		} catch (RuntimeException e) {
			if (!failureLogged) {
				failureLogged = true;
				LOG.error("PortalCraft: entity export failed (logged once)", e);
			}
		}
	}

	private static void run(Minecraft minecraft) {
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null || minecraft.gui.overlay() != null) {
			return; // no world, or resources are reloading
		}
		if (!sendItemAtlas(minecraft)) {
			return;
		}
		float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);

		Vec3 player = minecraft.player.getPosition(partial);
		double range2 = RANGE_BLOCKS * RANGE_BLOCKS;
		List<Near> near = new ArrayList<>();
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof ItemEntity item && !item.isInvisible()) {
				double d2 = item.getPosition(partial).distanceToSqr(player);
				if (d2 <= range2) {
					near.add(new Near(d2, item));
				}
			}
		}
		near.sort((a, b) -> Double.compare(a.distance2(), b.distance2()));

		CAPTURE.clear();
		int taken = 0;
		for (Near n : near) {
			int[] before = CAPTURE.counts();
			addItem(minecraft, n.item(), partial);
			if (CAPTURE.total() > WorldFormat.ENTITY_MAX_VERTICES) {
				CAPTURE.truncate(before); // nearest first: keep what fitted, drop the rest
				if (!overBudgetLogged) {
					overBudgetLogged = true;
					LOG.warn("PortalCraft: entity mesh over the {}-vertex budget: publishing the nearest {} of {} items (logged once)",
						WorldFormat.ENTITY_MAX_VERTICES, taken, near.size());
				}
				break;
			}
			taken++;
		}
		publish(taken);
	}

	/** Sends the item atlas when it, or the mapping, is new. False if it isn't stitched yet. */
	private static boolean sendItemAtlas(Minecraft minecraft) {
		boolean newAtlas = itemAtlas == null || itemAtlas.stale(minecraft);
		if (!newAtlas && sentGeneration == WorldLink.generation()) {
			return true;
		}
		if (newAtlas) {
			long t0 = System.nanoTime();
			WorldAtlas built = WorldAtlas.build(minecraft, AtlasIds.ITEMS);
			if (built == null) {
				return false;
			}
			itemAtlas = built;
			LOG.info("PortalCraft: item atlas is {}x{} (built in {} ms)", itemAtlas.width, itemAtlas.height, (System.nanoTime() - t0) / 1_000_000);
		}
		if (WorldFormat.itemAtlasFits(itemAtlas.width, itemAtlas.height)) {
			WorldLink.writeItemAtlas(itemAtlas.width, itemAtlas.height, itemAtlas.pixels);
			LOG.info("PortalCraft: sent the {}x{} item atlas to the host", itemAtlas.width, itemAtlas.height);
		} else if (!atlasTooBigLogged) {
			atlasTooBigLogged = true; // block-atlas items still draw; flat items don't
			LOG.warn("PortalCraft: item atlas {}x{} is bigger than the mapping's {} pixels; not sending it", itemAtlas.width, itemAtlas.height,
				WorldFormat.ITEM_ATLAS_MAX_PIXELS);
		}
		sentGeneration = WorldLink.generation();
		publishedEmpty = false; // a new mapping holds no entity mesh: publish even an empty one
		return true;
	}

	/**
	 * One dropped item the way ItemEntityRenderer.submit draws it: resting on its model's lowest
	 * point plus the hover height, bobbing, spinning, and repeated by stack size.
	 */
	private static void addItem(Minecraft minecraft, ItemEntity item, float partial) {
		ItemStack stack = item.getItem();
		if (stack.isEmpty()) {
			return;
		}
		STATE.extractItemGroupRenderState(item, stack, minecraft.getItemModelResolver());
		if (STATE.item.isEmpty()) {
			return;
		}
		float age = item.tickCount + partial; // EntityRenderState.ageInTicks
		AABB box = STATE.item.getModelBoundingBox();
		PoseStack pose = new PoseStack();
		pose.translate(0.0F, WorldFormat.itemBob(age, item.bobOffs) - (float) box.minY + WorldFormat.ITEM_MIN_HOVER, 0.0F);
		pose.rotate(Axis.YP, ItemEntity.getSpin(age, item.bobOffs));
		PoseStack.Pose base = pose.last().copy(); // submitMultipleFromCount leaves flat stacks' offset on the stack
		CAPTURE.begin(item.getPosition(partial));
		ItemEntityRenderer.submitMultipleFromCount(pose, CAPTURE, FULL_BRIGHT, STATE, RANDOM, box);
		if (!CAPTURE.capturedAny()) {
			PARTICLE_RANDOM.setSeed(0);
			Material.Baked material = STATE.item.pickParticleMaterial(PARTICLE_RANDOM);
			if (material != null) {
				CAPTURE.icon(base, material.sprite());
			}
		}
	}

	/** Writes the captured mesh into the free entity slot and makes it the newest. */
	private static void publish(int items) {
		int total = CAPTURE.total();
		if (total == 0 && publishedEmpty) {
			return; // the host already has the empty mesh
		}
		int slot = WorldLink.entityFreeSlot();
		if (slot < 0) {
			return; // the host is between frames on both slots; next frame
		}
		int at = 0;
		for (WorldFormat.Vertices range : CAPTURE.ranges) {
			WorldLink.writeEntityVertices(slot, at, range.data(), range.count());
			at += range.count();
		}
		WorldLink.publishEntities(slot, CAPTURE.blockSolid.count(), CAPTURE.blockTranslucent.count(), CAPTURE.itemSolid.count(),
			CAPTURE.itemTranslucent.count());
		publishedEmpty = total == 0;
		if (total > 0 && (++publishes <= 3 || publishes % 2000 == 0)) {
			LOG.info("PortalCraft: entity mesh #{}: {} items, {} + {} block-atlas and {} + {} item-atlas vertices (slot {})", publishes, items,
				CAPTURE.blockSolid.count(), CAPTURE.blockTranslucent.count(), CAPTURE.itemSolid.count(), CAPTURE.itemTranslucent.count(), slot);
		}
	}

	/**
	 * A submit collector that turns item submits into vertices in host space, split by atlas and
	 * by blending, and ignores everything else (models with entity textures, text, shadows).
	 */
	private static final class ItemCapture implements SubmitNodeCollector {
		final WorldFormat.Vertices blockSolid = new WorldFormat.Vertices(4096);
		final WorldFormat.Vertices blockTranslucent = new WorldFormat.Vertices(256);
		final WorldFormat.Vertices itemSolid = new WorldFormat.Vertices(8192);
		final WorldFormat.Vertices itemTranslucent = new WorldFormat.Vertices(256);
		/** In slot order. */
		final WorldFormat.Vertices[] ranges = {this.blockSolid, this.blockTranslucent, this.itemSolid, this.itemTranslucent};
		private final Vector3f p = new Vector3f();
		private final Vector3f n = new Vector3f();
		private double ox, oy, oz;
		private boolean captured;

		void clear() {
			for (WorldFormat.Vertices r : this.ranges) {
				r.clear();
			}
		}

		int total() {
			int t = 0;
			for (WorldFormat.Vertices r : this.ranges) {
				t += r.count();
			}
			return t;
		}

		int[] counts() {
			return new int[] {this.blockSolid.count(), this.blockTranslucent.count(), this.itemSolid.count(), this.itemTranslucent.count()};
		}

		void truncate(int[] counts) {
			for (int i = 0; i < this.ranges.length; i++) {
				this.ranges[i].truncate(counts[i]);
			}
		}

		/** The next submits belong to an entity whose origin (Minecraft coordinates) is {@code at}. */
		void begin(Vec3 at) {
			this.ox = at.x;
			this.oy = at.y;
			this.oz = at.z;
			this.captured = false;
		}

		boolean capturedAny() {
			return this.captured;
		}

		private WorldFormat.Vertices range(TextureAtlasSprite sprite, boolean translucent) {
			Identifier atlas = sprite.atlasLocation();
			if (atlas.equals(TextureAtlas.LOCATION_BLOCKS)) {
				return translucent ? this.blockTranslucent : this.blockSolid;
			}
			if (atlas.equals(TextureAtlas.LOCATION_ITEMS)) {
				return translucent ? this.itemTranslucent : this.itemSolid;
			}
			return null; // another atlas: the host doesn't have it
		}

		private void vertex(WorldFormat.Vertices out, Matrix4f m, float x, float y, float z, int color, float u, float v) {
			m.transformPosition(x, y, z, this.p);
			out.add(this.ox + this.p.x, this.oy + this.p.y, this.oz + this.p.z, color, u, v);
		}

		@Override
		public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords, int overlayCoords, int outlineColor,
			int[] tintLayers, ItemQuads quads, ItemStackRenderState.FoilType foilType) {
			if (outlineColor != 0) {
				return; // the glowing-effect outline pass, not geometry
			}
			PoseStack.Pose pose = poseStack.last();
			Matrix4f m = pose.pose();
			for (BakedQuad quad : quads.all()) {
				BakedQuad.MaterialInfo material = quad.materialInfo();
				boolean translucent = material.itemRenderType().hasBlending();
				WorldFormat.Vertices out = this.range(material.sprite(), translucent);
				if (out == null) {
					continue;
				}
				int tint = material.isTinted() && material.tintIndex() < tintLayers.length ? tintLayers[material.tintIndex()] : -1;
				pose.transformNormal(quad.direction().getStepX(), quad.direction().getStepY(), quad.direction().getStepZ(), this.n);
				int color = WorldFormat.d3dColor(WorldFormat.shadeArgb(tint, WorldFormat.shade(this.n.x, this.n.y, this.n.z)), translucent);
				for (int k : WorldFormat.QUAD_TRIANGLES) {
					var position = quad.position(k);
					long uv = quad.packedUV(k);
					this.vertex(out, m, position.x(), position.y(), position.z(), color, UVPair.unpackU(uv), UVPair.unpackV(uv));
				}
				this.captured = true;
			}
		}

		/** A flat 0.5-block quad of {@code sprite}, standing on the pose's origin (two-sided: the host doesn't cull). */
		void icon(PoseStack.Pose pose, TextureAtlasSprite sprite) {
			WorldFormat.Vertices out = this.range(sprite, false);
			if (out == null) {
				return;
			}
			Matrix4f m = pose.pose();
			float[] xs = {-0.25F, 0.25F, 0.25F, -0.25F}, ys = {0.0F, 0.0F, 0.5F, 0.5F};
			float[] us = {sprite.getU0(), sprite.getU1(), sprite.getU1(), sprite.getU0()}, vs = {sprite.getV1(), sprite.getV1(), sprite.getV0(), sprite.getV0()};
			pose.transformNormal(0.0F, 0.0F, 1.0F, this.n);
			int color = WorldFormat.d3dColor(WorldFormat.shadeArgb(-1, WorldFormat.shade(this.n.x, this.n.y, this.n.z)), false);
			for (int k : WorldFormat.QUAD_TRIANGLES) {
				this.vertex(out, m, xs[k], ys[k], 0.0F, color, us[k], vs[k]);
			}
		}

		// ---- everything else is ignored ----
		@Override
		public OrderedSubmitNodeCollector order(int order) {
			return this;
		}

		@Override
		public void submitShadow(PoseStack poseStack, float radius, List<EntityRenderState.ShadowPiece> pieces) {
		}

		@Override
		public void submitNameTag(PoseStack poseStack, @Nullable Vec3 nameTagAttachment, int offset, Component name, boolean seeThrough, int lightCoords,
			CameraRenderState camera) {
		}

		@Override
		public void submitText(PoseStack poseStack, float x, float y, FormattedCharSequence string, boolean dropShadow, Font.DisplayMode displayMode,
			int lightCoords, int color, int backgroundColor, int outlineColor) {
		}

		@Override
		public void submitTextBackground(PoseStack poseStack, float x0, float y0, float x1, float y1, int color, Font.DisplayMode displayMode,
			int lightCoords) {
		}

		@Override
		public void submitFlame(PoseStack poseStack, EntityRenderState renderState, Quaternionf rotation) {
		}

		@Override
		public void submitLeash(PoseStack poseStack, EntityRenderState.LeashState leashState) {
		}

		@Override
		public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
			int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) {
		}

		@Override
		public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords,
			int overlayCoords, int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
		}

		@Override
		public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
		}

		@Override
		public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintLayers, int lightCoords,
			int overlayCoords, int outlineColor) {
		}

		@Override
		public void submitBreakingBlockModel(PoseStack poseStack, List<BlockStateModelPart> parts, int progress, boolean isBlockTranslucent) {
		}

		@Override
		public void submitShapeOutline(PoseStack poseStack, VoxelShape shape, RenderType renderType, int color, float width, boolean afterTerrain) {
		}

		@Override
		public void submitCustomGeometry(PoseStack poseStack, RenderType renderType, SubmitNodeCollector.CustomGeometryRenderer customGeometryRenderer) {
		}

		@Override
		public void submitQuadParticleGroup(QuadParticleRenderState particles) {
		}

		@Override
		public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
		}
	}
}
