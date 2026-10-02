package dev.portalcraft.client.world;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.portalcraft.PortalCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.MovingBlockRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.ItemEntityRenderer;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.entity.state.ItemEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.QuadParticleRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.UvMapping;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.data.AtlasIds;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
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
 * <p>In third person (F5) the player's own avatar goes in too, in its own ranges: Steve posed by
 * his AvatarRenderer (walk cycle, head turn, sneak, arm swing) with the skin as a third texture,
 * and what he holds. Those vertices are relative to his feet; the host adds where it draws its
 * player, so the body sits exactly under the host's camera. Armour and capes aren't sent yet.
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
	private static final CameraRenderState AVATAR_CAMERA = new CameraRenderState();
	/** The skin texture last sent, and into which mapping. */
	private static Identifier sentSkin;
	private static int sentSkinGeneration = Integer.MIN_VALUE;
	private static boolean avatarFailureLogged;
	private static boolean avatarLogged;

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
		addAvatar(minecraft, partial);
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

	/**
	 * The player himself, when Minecraft's camera is in third person: run through his own
	 * AvatarRenderer into the avatar ranges, relative to his feet.
	 */
	private static void addAvatar(Minecraft minecraft, float partial) {
		LocalPlayer player = minecraft.player;
		if (minecraft.options.getCameraType().isFirstPerson() || player == null) {
			return;
		}
		try {
			EntityRenderer<? super LocalPlayer, ?> renderer = minecraft.getEntityRenderDispatcher().getRenderer(player);
			if (!(renderer instanceof AvatarRenderer<?> avatarRenderer)) {
				return;
			}
			if (!sendSkin(minecraft, player.getSkin().body().texturePath())) {
				return;
			}
			@SuppressWarnings({"unchecked", "rawtypes"})
			EntityRenderer<LocalPlayer, EntityRenderState> raw = (EntityRenderer) renderer;
			EntityRenderState state = raw.createRenderState(player, partial);
			Vec3 offset = raw.getRenderOffset(state); // the sneak drop
			PoseStack pose = new PoseStack();
			pose.translate(offset.x, offset.y, offset.z);
			int[] before = CAPTURE.counts();
			CAPTURE.beginAvatar(avatarRenderer.getModel());
			raw.submit(state, pose, CAPTURE, AVATAR_CAMERA);
			CAPTURE.endAvatar();
			if (CAPTURE.total() > WorldFormat.ENTITY_MAX_VERTICES) {
				CAPTURE.truncate(before);
			}
			if (!avatarLogged) {
				avatarLogged = true;
				int[] c = CAPTURE.counts();
				LOG.info("PortalCraft: third person: avatar mesh {} skin + {} held vertices", c[4], c[5] + c[6] + c[7] + c[8]);
			}
		} catch (RuntimeException e) {
			CAPTURE.endAvatar();
			if (!avatarFailureLogged) {
				avatarFailureLogged = true;
				LOG.error("PortalCraft: avatar export failed (logged once)", e);
			}
		}
	}

	/** Sends the player's skin when it, or the mapping, is new. False if it can't be read. */
	private static boolean sendSkin(Minecraft minecraft, Identifier texture) {
		if (texture.equals(sentSkin) && sentSkinGeneration == WorldLink.generation()) {
			return true;
		}
		NativeImage image = null;
		boolean owned = false;
		AbstractTexture loaded = minecraft.getTextureManager().getTexture(texture);
		if (loaded instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
			image = dynamic.getPixels(); // a downloaded skin
		} else {
			Resource resource = minecraft.getResourceManager().getResource(texture).orElse(null);
			if (resource != null) {
				try (var in = resource.open()) {
					image = NativeImage.read(in);
					owned = true;
				} catch (java.io.IOException e) {
					LOG.warn("PortalCraft: can't read the skin {}: {}", texture, e.toString());
				}
			}
		}
		if (image == null) {
			if (!texture.equals(sentSkin)) {
				sentSkin = texture;
				sentSkinGeneration = Integer.MIN_VALUE;
				LOG.warn("PortalCraft: no pixels for the skin {}; third person shows no body", texture);
			}
			return false;
		}
		try {
			int w = image.getWidth(), h = image.getHeight();
			if (!WorldFormat.skinFits(w, h)) {
				LOG.warn("PortalCraft: skin {} is {}x{}, bigger than the mapping's skin region", texture, w, h);
				return false;
			}
			int[] pixels = new int[w * h];
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					pixels[y * w + x] = WorldFormat.argbToRgba(image.getPixel(x, y));
				}
			}
			WorldLink.writeSkin(w, h, pixels);
			sentSkin = texture;
			sentSkinGeneration = WorldLink.generation();
			LOG.info("PortalCraft: sent the {}x{} skin {} to the host", w, h, texture);
			return true;
		} finally {
			if (owned) {
				image.close();
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
		WorldLink.publishEntities(slot, CAPTURE.counts());
		publishedEmpty = total == 0;
		if (total > 0 && (++publishes <= 3 || publishes % 2000 == 0)) {
			LOG.info("PortalCraft: entity mesh #{}: {} items, {} + {} block-atlas and {} + {} item-atlas vertices (slot {})", publishes, items,
				CAPTURE.blockSolid.count(), CAPTURE.blockTranslucent.count(), CAPTURE.itemSolid.count(), CAPTURE.itemTranslucent.count(), slot);
		}
	}

	/**
	 * A submit collector that turns item submits into vertices in host space, split by atlas and
	 * by blending, and ignores everything else (models with entity textures, text, shadows). While
	 * an avatar is being captured, items go to the avatar ranges and the avatar's own model is
	 * kept too, skin-textured.
	 */
	private static final class ItemCapture implements SubmitNodeCollector {
		final WorldFormat.Vertices blockSolid = new WorldFormat.Vertices(4096);
		final WorldFormat.Vertices blockTranslucent = new WorldFormat.Vertices(256);
		final WorldFormat.Vertices itemSolid = new WorldFormat.Vertices(8192);
		final WorldFormat.Vertices itemTranslucent = new WorldFormat.Vertices(256);
		final WorldFormat.Vertices avatarSkin = new WorldFormat.Vertices(2048);
		final WorldFormat.Vertices avatarBlockSolid = new WorldFormat.Vertices(256);
		final WorldFormat.Vertices avatarBlockTranslucent = new WorldFormat.Vertices(64);
		final WorldFormat.Vertices avatarItemSolid = new WorldFormat.Vertices(1024);
		final WorldFormat.Vertices avatarItemTranslucent = new WorldFormat.Vertices(64);
		/** In slot order (WorldFormat.H_ENTITY_RANGES). */
		final WorldFormat.Vertices[] ranges = {this.blockSolid, this.blockTranslucent, this.itemSolid, this.itemTranslucent, this.avatarSkin,
			this.avatarBlockSolid, this.avatarBlockTranslucent, this.avatarItemSolid, this.avatarItemTranslucent};
		private final Vector3f p = new Vector3f();
		private final Vector3f n = new Vector3f();
		private final SkinQuads skinQuads = new SkinQuads();
		private double ox, oy, oz;
		private boolean captured;
		/** The avatar's body model while one is being captured, else null. */
		private @Nullable Model<?> avatarModel;

		void beginAvatar(Model<?> model) {
			this.begin(Vec3.ZERO); // relative to the feet
			this.avatarModel = model;
		}

		void endAvatar() {
			this.avatarModel = null;
		}

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
			int[] counts = new int[this.ranges.length];
			for (int i = 0; i < counts.length; i++) {
				counts[i] = this.ranges[i].count();
			}
			return counts;
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
			boolean avatar = this.avatarModel != null;
			if (atlas.equals(TextureAtlas.LOCATION_BLOCKS)) {
				return avatar ? (translucent ? this.avatarBlockTranslucent : this.avatarBlockSolid) : translucent ? this.blockTranslucent : this.blockSolid;
			}
			if (atlas.equals(TextureAtlas.LOCATION_ITEMS)) {
				return avatar ? (translucent ? this.avatarItemTranslucent : this.avatarItemSolid) : translucent ? this.itemTranslucent : this.itemSolid;
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

		/**
		 * Model cubes arrive as quads of four posed vertices (ModelPart.Cube.compile); each becomes
		 * two triangles, face-shaded like the items. Origin is the avatar's feet.
		 */
		private static final class SkinQuads implements VertexConsumer {
			WorldFormat.Vertices out;
			private final float[] xyz = new float[12], uv = new float[8];
			private int corner, color = -1;
			private float nx, ny, nz;

			@Override
			public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny,
				float nz) {
				this.addVertex(x, y, z).setColor(color).setUv(u, v).setNormal(nx, ny, nz);
			}

			@Override
			public VertexConsumer addVertex(float x, float y, float z) {
				int c = this.corner;
				this.xyz[c * 3] = x;
				this.xyz[c * 3 + 1] = y;
				this.xyz[c * 3 + 2] = z;
				return this;
			}

			@Override
			public VertexConsumer setColor(int r, int g, int b, int a) {
				return this.setColor(a << 24 | r << 16 | g << 8 | b);
			}

			@Override
			public VertexConsumer setColor(int color) {
				this.color = color;
				return this;
			}

			@Override
			public VertexConsumer setUv(float u, float v) {
				this.uv[this.corner * 2] = u;
				this.uv[this.corner * 2 + 1] = v;
				return this;
			}

			/** The last attribute of each vertex: completes it, and every fourth one a quad. */
			@Override
			public VertexConsumer setNormal(float x, float y, float z) {
				this.nx = x;
				this.ny = y;
				this.nz = z;
				if (++this.corner == 4) {
					this.corner = 0;
					int shaded = WorldFormat.d3dColor(WorldFormat.shadeArgb(this.color, WorldFormat.shade(this.nx, this.ny, this.nz)), false);
					for (int k : WorldFormat.QUAD_TRIANGLES) {
						this.out.add(this.xyz[k * 3], this.xyz[k * 3 + 1], this.xyz[k * 3 + 2], shaded, this.uv[k * 2], this.uv[k * 2 + 1]);
					}
				}
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
			public VertexConsumer setLineWidth(float width) {
				return this;
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

		/** Keeps the avatar's body (skin-textured); other models (armour, cape, parrots) aren't sent. */
		@Override
		public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
			int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) {
			if (this.avatarModel == null || model != this.avatarModel || outlineColor != 0) {
				return;
			}
			model.setupAnim(state);
			this.skinQuads.out = this.avatarSkin;
			model.renderToBuffer(poseStack, this.skinQuads, lightCoords, overlayCoords, tintedColor);
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
