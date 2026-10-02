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
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.ParticlesRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.state.BlockState;
import java.util.SortedSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
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
 * <p>The player's own avatar goes in too, in its own ranges: Steve posed by
 * his AvatarRenderer (walk cycle, head turn, sneak, arm swing) with the skin as a third texture,
 * and what he holds. Those vertices are relative to his feet; the host adds where it draws its
 * player, so the body sits exactly under the host's camera. Armour and capes aren't sent yet.
 *
 * <p>Also every other non-living entity near the player, through its own renderer: primed TNT,
 * falling blocks, block and item displays, thrown items and fireworks show as their block or item
 * models (mobs need their own textures, which the host doesn't have yet). Particles go in through
 * the particle engine's own extraction, debris from the block atlas and the rest from the particle
 * atlas (sent once, like the item atlas), and the crack over a block being broken as a box around
 * its shape, textured from the destroy stages (the crack strip).
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
	private static WorldAtlas particleAtlas;
	private static int sentParticleGeneration = Integer.MIN_VALUE;
	private static int sentCracksGeneration = Integer.MIN_VALUE;
	private static final ParticlesRenderState PARTICLES = new ParticlesRenderState();
	/** Mob and other players' textures, packed for the host. */
	static final MobAtlas MOBS = new MobAtlas();
	private static boolean otherFailureLogged;
	private static final java.util.Set<Object> LOGGED_TYPES = new java.util.HashSet<>();

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
		addOthers(minecraft, level, partial, player, range2);
		addParticles(minecraft, partial);
		addCracks(minecraft, level, player, range2);
		addAvatar(minecraft, partial);
		MOBS.flush();
		publish(taken);
	}

	/** Fills the camera state renderers face things towards (thrown items, name tags) from Minecraft's camera. */
	private static void syncCamera(Minecraft minecraft) {
		var camera = minecraft.gameRenderer.mainCamera();
		AVATAR_CAMERA.pos = camera.position();
		AVATAR_CAMERA.orientation.set(camera.rotation());
		AVATAR_CAMERA.initialized = true;
	}

	/** Primed TNT, falling blocks, displays, thrown items: anything else that draws blocks or items. */
	private static void addOthers(Minecraft minecraft, ClientLevel level, float partial, Vec3 player, double range2) {
		syncCamera(minecraft);
		for (Entity e : level.entitiesForRendering()) {
			if (e instanceof ItemEntity || e.isInvisible() || e == minecraft.player) {
				continue; // items have their own path; the player is the avatar
			}
			Vec3 at = e.getPosition(partial);
			if (at.distanceToSqr(player) > range2) {
				continue;
			}
			int[] before = CAPTURE.counts();
			if (e instanceof PrimedTnt tnt) {
				addTnt(tnt, at, partial);
				continue;
			}
			try {
				@SuppressWarnings({"unchecked", "rawtypes"})
				EntityRenderer<Entity, EntityRenderState> renderer = (EntityRenderer) minecraft.getEntityRenderDispatcher().getRenderer(e);
				EntityRenderState state = renderer.createRenderState(e, partial);
				Vec3 offset = renderer.getRenderOffset(state);
				PoseStack pose = new PoseStack();
				pose.translate(offset.x, offset.y, offset.z);
				CAPTURE.begin(at);
				renderer.submit(state, pose, CAPTURE, AVATAR_CAMERA);
				if (LOGGED_TYPES.add(e.getType())) {
					int got = CAPTURE.total() - java.util.Arrays.stream(before).sum();
					LOG.info("PortalCraft: exporting {} ({}): {} vertices", e.getType(), renderer.getClass().getSimpleName(), got);
				}
			} catch (RuntimeException ex) {
				CAPTURE.truncate(before);
				if (!otherFailureLogged) {
					otherFailureLogged = true;
					LOG.warn("PortalCraft: couldn't export a {} (logged once): {}", e.getType(), ex.toString());
				}
			}
			if (CAPTURE.total() > WorldFormat.ENTITY_MAX_VERTICES) {
				CAPTURE.truncate(before);
				return;
			}
		}
	}

	/**
	 * Primed TNT, drawn from its block model the way TntRenderer poses it (the swell before it
	 * goes off). Its renderer's block submit arrives without model parts under Fabric's renderer,
	 * so we take the parts from the block model ourselves, as for falling blocks.
	 */
	private static void addTnt(PrimedTnt tnt, Vec3 at, float partial) {
		PoseStack pose = new PoseStack();
		pose.translate(0.0F, 0.5F, 0.0F);
		float fuse = tnt.getFuse() - partial + 1.0F;
		if (fuse < 10.0F) {
			float scale = 1.0F + net.minecraft.client.renderer.entity.TntRenderer.getSwellAmount(fuse);
			pose.scale(scale, scale, scale);
		}
		pose.translate(-0.5F, -0.5F, -0.5F);
		CAPTURE.begin(at);
		CAPTURE.blockState(pose, tnt.getBlockState(), net.minecraft.client.renderer.entity.TntRenderer.isLit(fuse));
	}

	/**
	 * The particle engine's own extraction (positions relative to Minecraft's camera, quads facing
	 * it), into the capture, which keeps block-, item- and particle-atlas quads. Culled to a box
	 * around the camera rather than its frustum: Portal also looks through portals.
	 */
	private static void addParticles(Minecraft minecraft, float partial) {
		if (!sendParticleAtlas(minecraft)) {
			return;
		}
		var camera = minecraft.gameRenderer.mainCamera();
		Vec3 cam = camera.position();
		Frustum around = new Frustum(new org.joml.Matrix4f(), new org.joml.Matrix4f().ortho(-48, 48, -48, 48, -48, 48));
		around.prepare(cam.x, cam.y, cam.z);
		int[] before = CAPTURE.counts();
		try {
			PARTICLES.reset();
			minecraft.particleEngine.extract(PARTICLES, around, camera, partial);
			CAPTURE.begin(cam);
			PARTICLES.submit(CAPTURE, AVATAR_CAMERA);
		} catch (RuntimeException ex) {
			CAPTURE.truncate(before);
			if (!otherFailureLogged) {
				otherFailureLogged = true;
				LOG.warn("PortalCraft: couldn't export particles (logged once): {}", ex.toString());
			}
		}
		if (CAPTURE.total() > WorldFormat.ENTITY_MAX_VERTICES) {
			CAPTURE.truncate(before);
		}
	}

	/** The crack over each block being broken (the player's own included), at its destroy stage. */
	private static void addCracks(Minecraft minecraft, ClientLevel level, Vec3 player, double range2) {
		if (!sendCracks(minecraft)) {
			return;
		}
		Long2ObjectMap<SortedSet<BlockDestructionProgress>> progress = level.destructionProgress();
		for (Long2ObjectMap.Entry<SortedSet<BlockDestructionProgress>> entry : progress.long2ObjectEntrySet()) {
			SortedSet<BlockDestructionProgress> set = entry.getValue();
			if (set == null || set.isEmpty()) {
				continue;
			}
			int stage = set.last().getProgress();
			BlockPos pos = BlockPos.of(entry.getLongKey());
			if (stage < 0 || stage >= WorldFormat.CRACK_STAGES || Vec3.atCenterOf(pos).distanceToSqr(player) > range2) {
				continue;
			}
			BlockState state = level.getBlockState(pos);
			VoxelShape shape = state.getShape(level, pos);
			if (shape.isEmpty()) {
				continue;
			}
			for (AABB box : shape.toAabbs()) {
				CAPTURE.crackBox(box.move(pos).inflate(0.002), pos, stage);
			}
		}
	}

	/** Sends the particle atlas when it, or the mapping, is new. False if it isn't there yet. */
	private static boolean sendParticleAtlas(Minecraft minecraft) {
		boolean newAtlas = particleAtlas == null || particleAtlas.stale(minecraft);
		if (!newAtlas && sentParticleGeneration == WorldLink.generation()) {
			return true;
		}
		if (newAtlas) {
			WorldAtlas built = WorldAtlas.build(minecraft, AtlasIds.PARTICLES);
			if (built == null) {
				return false;
			}
			particleAtlas = built;
		}
		if (WorldFormat.particleAtlasFits(particleAtlas.width, particleAtlas.height)) {
			WorldLink.writeParticleAtlas(particleAtlas.width, particleAtlas.height, particleAtlas.pixels);
			LOG.info("PortalCraft: sent the {}x{} particle atlas to the host", particleAtlas.width, particleAtlas.height);
		} else {
			LOG.warn("PortalCraft: particle atlas {}x{} doesn't fit the mapping; particles from it won't show", particleAtlas.width,
				particleAtlas.height);
		}
		sentParticleGeneration = WorldLink.generation();
		return true;
	}

	/** Sends the ten destroy-stage textures side by side, once per mapping. False if they can't be read. */
	private static boolean sendCracks(Minecraft minecraft) {
		if (sentCracksGeneration == WorldLink.generation()) {
			return true;
		}
		NativeImage[] stages = new NativeImage[WorldFormat.CRACK_STAGES];
		try {
			for (int i = 0; i < stages.length; i++) {
				Identifier id = Identifier.withDefaultNamespace("textures/block/destroy_stage_" + i + ".png");
				Resource resource = minecraft.getResourceManager().getResource(id).orElse(null);
				if (resource == null) {
					return false;
				}
				try (var in = resource.open()) {
					stages[i] = NativeImage.read(in);
				}
			}
			int w = stages[0].getWidth(), h = stages[0].getHeight(), strip = w * stages.length;
			if (strip > WorldFormat.CRACK_MAX_W || h > WorldFormat.CRACK_MAX_H) {
				LOG.warn("PortalCraft: destroy stages are {}x{}, too big for the crack strip", w, h);
				sentCracksGeneration = WorldLink.generation();
				return false;
			}
			int[] pixels = new int[strip * h];
			for (int i = 0; i < stages.length; i++) {
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int px = Math.min(x, stages[i].getWidth() - 1), py = Math.min(y, stages[i].getHeight() - 1);
						pixels[y * strip + i * w + x] = WorldFormat.argbToRgba(stages[i].getPixel(px, py));
					}
				}
			}
			WorldLink.writeCracks(strip, h, pixels);
			sentCracksGeneration = WorldLink.generation();
			LOG.info("PortalCraft: sent the {}x{} crack strip to the host", strip, h);
			return true;
		} catch (java.io.IOException e) {
			LOG.warn("PortalCraft: can't read the destroy stages: {}", e.toString());
			sentCracksGeneration = WorldLink.generation();
			return false;
		} finally {
			for (NativeImage image : stages) {
				if (image != null) {
					image.close();
				}
			}
		}
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
	 * The player himself: run through his own AvatarRenderer into the avatar ranges, relative to
	 * his feet. Sent in first person too: the host draws him in its views through portals always,
	 * and in its main view only in third person.
	 */
	private static void addAvatar(Minecraft minecraft, float partial) {
		LocalPlayer player = minecraft.player;
		if (player == null) {
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
				LOG.info("PortalCraft: avatar mesh {} skin + {} held + {} armour vertices", c[4], c[5] + c[6] + c[7] + c[8], c[14] + c[15]);
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
		final WorldFormat.Vertices particleSolid = new WorldFormat.Vertices(1024);
		final WorldFormat.Vertices particleTranslucent = new WorldFormat.Vertices(1024);
		final WorldFormat.Vertices crack = new WorldFormat.Vertices(64);
		final WorldFormat.Vertices mobSolid = new WorldFormat.Vertices(4096);
		final WorldFormat.Vertices mobTranslucent = new WorldFormat.Vertices(256);
		final WorldFormat.Vertices avatarMobSolid = new WorldFormat.Vertices(512);
		final WorldFormat.Vertices avatarMobTranslucent = new WorldFormat.Vertices(64);
		/** In slot order (WorldFormat.H_ENTITY_RANGES). */
		final WorldFormat.Vertices[] ranges = {this.blockSolid, this.blockTranslucent, this.itemSolid, this.itemTranslucent, this.avatarSkin,
			this.avatarBlockSolid, this.avatarBlockTranslucent, this.avatarItemSolid, this.avatarItemTranslucent, this.particleSolid,
			this.particleTranslucent, this.crack, this.mobSolid, this.mobTranslucent, this.avatarMobSolid, this.avatarMobTranslucent};
		private final ParticleQuads particleQuads = new ParticleQuads();
		private final List<BlockStateModelPart> movingParts = new ArrayList<>();
		private final RandomSource movingRandom = RandomSource.create();
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
			if (atlas.equals(TextureAtlas.LOCATION_PARTICLES)) {
				return translucent ? this.particleTranslucent : this.particleSolid;
			}
			return null; // another atlas: the host doesn't have it
		}

		/** The range for quads textured from `atlas` (particles name their atlas, not a sprite). */
		private WorldFormat.Vertices range(Identifier atlas, boolean translucent) {
			if (atlas.equals(TextureAtlas.LOCATION_BLOCKS)) {
				return translucent ? this.blockTranslucent : this.blockSolid;
			}
			if (atlas.equals(TextureAtlas.LOCATION_ITEMS)) {
				return translucent ? this.itemTranslucent : this.itemSolid;
			}
			if (atlas.equals(TextureAtlas.LOCATION_PARTICLES)) {
				return translucent ? this.particleTranslucent : this.particleSolid;
			}
			return null;
		}

		/**
		 * The six faces of `box` (Minecraft coordinates) with the crack of `stage`, its texture
		 * projected per face the way Minecraft's crumbling decal is, one block per tile.
		 */
		void crackBox(AABB box, BlockPos block, int stage) {
			float s0 = (float) stage / WorldFormat.CRACK_STAGES, sw = 1.0F / WorldFormat.CRACK_STAGES;
			double[][] faces = {
				{box.minX, box.maxY, box.minZ, box.maxX, box.maxY, box.minZ, box.maxX, box.maxY, box.maxZ, box.minX, box.maxY, box.maxZ}, // up
				{box.minX, box.minY, box.maxZ, box.maxX, box.minY, box.maxZ, box.maxX, box.minY, box.minZ, box.minX, box.minY, box.minZ}, // down
				{box.minX, box.minY, box.minZ, box.maxX, box.minY, box.minZ, box.maxX, box.maxY, box.minZ, box.minX, box.maxY, box.minZ}, // north
				{box.maxX, box.minY, box.maxZ, box.minX, box.minY, box.maxZ, box.minX, box.maxY, box.maxZ, box.maxX, box.maxY, box.maxZ}, // south
				{box.minX, box.minY, box.maxZ, box.minX, box.minY, box.minZ, box.minX, box.maxY, box.minZ, box.minX, box.maxY, box.maxZ}, // west
				{box.maxX, box.minY, box.minZ, box.maxX, box.minY, box.maxZ, box.maxX, box.maxY, box.maxZ, box.maxX, box.maxY, box.minZ}, // east
			};
			float[] us = new float[4], vs = new float[4];
			for (int f = 0; f < faces.length; f++) {
				double[] q = faces[f];
				for (int k = 0; k < 4; k++) {
					double x = q[k * 3] - block.getX(), y = q[k * 3 + 1] - block.getY(), z = q[k * 3 + 2] - block.getZ();
					double a = f < 4 ? x : z, b = f < 2 ? z : 1.0 - y; // the face's own two axes, within the block
					us[k] = s0 + sw * (float) Math.clamp(a, 0.0, 1.0);
					vs[k] = (float) Math.clamp(b, 0.0, 1.0);
				}
				for (int k : WorldFormat.QUAD_TRIANGLES) {
					this.crack.add(q[k * 3], q[k * 3 + 1], q[k * 3 + 2], 0xFFFFFFFF, us[k], vs[k]);
				}
			}
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
			private double ox, oy, oz;
			private float u0, v0, us = 1.0F, vs = 1.0F;

			/** Quads go to `out`, offset by the origin, UVs into `rect` ({u0, v0, uScale, vScale}; null: as is). */
			void start(WorldFormat.Vertices out, double ox, double oy, double oz, float @Nullable [] rect) {
				this.out = out;
				this.ox = ox;
				this.oy = oy;
				this.oz = oz;
				this.corner = 0;
				if (rect == null) {
					this.u0 = this.v0 = 0.0F;
					this.us = this.vs = 1.0F;
				} else {
					this.u0 = rect[0];
					this.v0 = rect[1];
					this.us = rect[2];
					this.vs = rect[3];
				}
			}

			/** OverlayTexture: v < 8 is the red hurt tint, u the white flash (creepers, TNT). */
			private int overlay = net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY;

			@Override
			public void addVertex(float x, float y, float z, int color, float u, float v, int overlayCoords, int lightCoords, float nx, float ny,
				float nz) {
				this.overlay = overlayCoords;
				this.addVertex(x, y, z).setColor(color).setUv(u, v).setNormal(nx, ny, nz);
			}

			private int overlaid(int argb) {
				int white = this.overlay & 0xFFFF, red = this.overlay >>> 16;
				int r = argb >>> 16 & 0xFF, g = argb >>> 8 & 0xFF, b = argb & 0xFF;
				if (red < 8) { // hurt: Minecraft mixes in its overlay red
					r = r + Math.round((255 - r) * 0.55F);
					g = Math.round(g * 0.45F);
					b = Math.round(b * 0.45F);
				}
				if (white > 0) {
					float f = Math.min(1.0F, white / 15.0F);
					r += Math.round((255 - r) * f);
					g += Math.round((255 - g) * f);
					b += Math.round((255 - b) * f);
				}
				return argb & 0xFF000000 | r << 16 | g << 8 | b;
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
					int shaded = WorldFormat.d3dColor(this.overlaid(WorldFormat.shadeArgb(this.color, WorldFormat.shade(this.nx, this.ny, this.nz))), false);
					for (int k : WorldFormat.QUAD_TRIANGLES) {
						this.out.add(this.ox + this.xyz[k * 3], this.oy + this.xyz[k * 3 + 1], this.oz + this.xyz[k * 3 + 2], shaded,
							this.u0 + this.uv[k * 2] * this.us, this.v0 + this.uv[k * 2 + 1] * this.vs);
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

		/**
		 * The avatar's body (skin-textured, feet-relative), and every other entity model: mobs,
		 * their layers (saddles, wool, armour), other players. Those are textured from the mob
		 * atlas, their UVs moved into their texture's place in it. The avatar's own armour and cape
		 * aren't sent yet.
		 */
		@Override
		public <S> void submitModel(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords, int overlayCoords,
			int tintedColor, @Nullable UvMapping uvMapping, int outlineColor) {
			if (outlineColor != 0) {
				return;
			}
			if (this.avatarModel != null && model == this.avatarModel) {
				model.setupAnim(state);
				this.skinQuads.start(this.avatarSkin, 0, 0, 0, null);
				model.renderToBuffer(poseStack, this.skinQuads, lightCoords, overlayCoords, tintedColor);
				return;
			}
			Identifier texture = MobAtlas.textureOf(renderType);
			float[] rect = texture == null ? null : MOBS.rect(Minecraft.getInstance(), texture);
			if (rect == null) {
				return;
			}
			model.setupAnim(state);
			boolean blend = renderType.hasBlending();
			if (this.avatarModel != null) { // the avatar's armour, cape, elytra: feet-relative like its body
				this.skinQuads.start(blend ? this.avatarMobTranslucent : this.avatarMobSolid, 0, 0, 0, rect);
			} else {
				this.skinQuads.start(blend ? this.mobTranslucent : this.mobSolid, this.ox, this.oy, this.oz, rect);
			}
			model.renderToBuffer(poseStack, this.skinQuads, lightCoords, overlayCoords, tintedColor);
			this.captured = true;
		}

		@Override
		public <S> void submitCrumblingOverlay(Model<? super S> model, S state, PoseStack poseStack, RenderType renderType, int lightCoords,
			int overlayCoords, int tintedColor, ModelFeatureRenderer.CrumblingOverlay crumblingOverlay) {
		}

		/** A block state's model at the pose (TNT); `flash` whitens it like TNT's fuse blink. */
		void blockState(PoseStack poseStack, BlockState state, boolean flash) {
			BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
			this.movingParts.clear();
			this.movingRandom.setSeed(42L);
			model.collectParts(this.movingRandom, this.movingParts);
			int from = this.blockSolid.count();
			this.blockParts(poseStack, this.movingParts, new int[0], false);
			if (flash) {
				this.blockSolid.brighten(from, 0.55F);
			}
		}

		/** A falling block: its block model, at the pose. */
		@Override
		public void submitMovingBlock(PoseStack poseStack, MovingBlockRenderState movingBlockRenderState, int outlineColor) {
			if (outlineColor != 0) {
				return;
			}
			BlockState state = movingBlockRenderState.blockState;
			BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
			this.movingParts.clear();
			this.movingRandom.setSeed(state.getSeed(movingBlockRenderState.randomSeedPos));
			model.collectParts(this.movingRandom, this.movingParts);
			this.blockParts(poseStack, this.movingParts, new int[0], false);
		}

		/** Primed TNT, block displays, minecart contents: the block model's quads at the pose. */
		@Override
		public void submitBlockModel(PoseStack poseStack, RenderType renderType, List<BlockStateModelPart> parts, int[] tintLayers, int lightCoords,
			int overlayCoords, int outlineColor) {
			if (outlineColor == 0) {
				this.blockParts(poseStack, parts, tintLayers, renderType.hasBlending());
			}
		}

		private static final Direction[] FACES_AND_INSIDE = {Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST,
			null};

		private void blockParts(PoseStack poseStack, List<BlockStateModelPart> parts, int[] tintLayers, boolean translucent) {
			PoseStack.Pose pose = poseStack.last();
			Matrix4f m = pose.pose();
			for (BlockStateModelPart part : parts) {
				for (Direction side : FACES_AND_INSIDE) {
					for (BakedQuad quad : part.getQuads(side)) {
						BakedQuad.MaterialInfo material = quad.materialInfo();
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
			}
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

		/** Particles, layer by layer: each layer names its atlas and whether it blends. */
		@Override
		public void submitQuadParticleGroup(QuadParticleRenderState particles) {
			for (var layer : particles.layers()) {
				WorldFormat.Vertices out = this.range(layer.textureAtlasLocation(), layer.translucent());
				if (out == null) {
					continue;
				}
				this.particleQuads.start(out, this.ox, this.oy, this.oz, layer.translucent());
				particles.buildLayer(layer, this.particleQuads);
			}
		}

		/** Particle quads: four corners (camera-relative) with uv and colour; two triangles each. */
		private static final class ParticleQuads implements VertexConsumer {
			private WorldFormat.Vertices out;
			private double ox, oy, oz;
			private boolean translucent;
			private final double[] xyz = new double[12];
			private final float[] uv = new float[8];
			private final int[] colors = new int[4];
			private int corner;

			void start(WorldFormat.Vertices out, double ox, double oy, double oz, boolean translucent) {
				this.out = out;
				this.ox = ox;
				this.oy = oy;
				this.oz = oz;
				this.translucent = translucent;
				this.corner = 0;
			}

			@Override
			public VertexConsumer addVertex(float x, float y, float z) {
				this.xyz[this.corner * 3] = this.ox + x;
				this.xyz[this.corner * 3 + 1] = this.oy + y;
				this.xyz[this.corner * 3 + 2] = this.oz + z;
				return this;
			}

			@Override
			public VertexConsumer setUv(float u, float v) {
				this.uv[this.corner * 2] = u;
				this.uv[this.corner * 2 + 1] = v;
				return this;
			}

			@Override
			public VertexConsumer setColor(int r, int g, int b, int a) {
				return this.setColor(a << 24 | r << 16 | g << 8 | b);
			}

			@Override
			public VertexConsumer setColor(int color) {
				this.colors[this.corner] = WorldFormat.d3dColor(color, this.translucent);
				return this;
			}

			/** setLight is each particle vertex's last call (QuadParticleRenderState.renderVertex). */
			@Override
			public VertexConsumer setUv2(int u, int v) {
				if (++this.corner == 4) {
					this.corner = 0;
					for (int k : WorldFormat.QUAD_TRIANGLES) {
						this.out.add(this.xyz[k * 3], this.xyz[k * 3 + 1], this.xyz[k * 3 + 2], this.colors[k], this.uv[k * 2], this.uv[k * 2 + 1]);
					}
				}
				return this;
			}

			@Override
			public VertexConsumer setUv1(int u, int v) {
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

		@Override
		public void submitGizmoPrimitives(DrawableGizmoPrimitives.Group group, CameraRenderState camera, boolean onTop) {
		}
	}
}
