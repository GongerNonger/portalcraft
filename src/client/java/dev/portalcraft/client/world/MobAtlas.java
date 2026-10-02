package dev.portalcraft.client.world;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import dev.portalcraft.PortalCraft;
import dev.portalcraft.client.mixin.RenderSetupAccessor;
import dev.portalcraft.client.mixin.RenderTypeAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The entity textures the host needs (a pig's, a sheep's wool, armour layers, other players'
 * skins), packed on the CPU into one MOB_ATLAS_SIZE square as they are first met, shelf by shelf,
 * and sent whole when it changes (rarely: once per new texture). A model's UVs are remapped into
 * its texture's rectangle. When the atlas is full it starts over.
 */
final class MobAtlas {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	private static final int SIZE = WorldFormat.MOB_ATLAS_SIZE;
	/** No room or no pixels: don't try that texture again until the atlas starts over. */
	private static final float[] MISSING = new float[0];

	private final int[] pixels = new int[SIZE * SIZE];
	private final Map<Identifier, float[]> rects = new HashMap<>();
	private int shelfX, shelfY, shelfHeight;
	private boolean dirty;
	private int sentGeneration = Integer.MIN_VALUE;
	private static @Nullable Method bindingLocation;

	/** The texture a model's render type samples (Sampler0), or null. */
	static @Nullable Identifier textureOf(RenderType renderType) {
		Object binding = ((RenderSetupAccessor) (Object) ((RenderTypeAccessor) renderType).portalcraft$state()).portalcraft$textures().get("Sampler0");
		if (binding == null) {
			return null;
		}
		try {
			if (bindingLocation == null) {
				bindingLocation = binding.getClass().getDeclaredMethod("location");
				bindingLocation.setAccessible(true);
			}
			return (Identifier) bindingLocation.invoke(binding);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/** {u0, v0, uScale, vScale} of `texture` in the atlas, adding it if new; null if it can't be had. */
	float @Nullable [] rect(Minecraft minecraft, Identifier texture) {
		float[] r = this.rects.get(texture);
		if (r != null) {
			return r == MISSING ? null : r;
		}
		NativeImage image = null;
		boolean owned = false;
		AbstractTexture loaded = minecraft.getTextureManager().getTexture(texture);
		if (loaded instanceof DynamicTexture dynamic && dynamic.getPixels() != null) {
			image = dynamic.getPixels(); // downloaded skins
		} else {
			Resource resource = minecraft.getResourceManager().getResource(texture).orElse(null);
			if (resource != null) {
				try (var in = resource.open()) {
					image = NativeImage.read(in);
					owned = true;
				} catch (java.io.IOException e) {
					image = null;
				}
			}
		}
		if (image == null) {
			this.rects.put(texture, MISSING);
			return null;
		}
		try {
			int w = image.getWidth(), h = image.getHeight();
			if (w > SIZE || h > SIZE) {
				this.rects.put(texture, MISSING);
				return null;
			}
			if (this.shelfX + w > SIZE) { // next shelf
				this.shelfX = 0;
				this.shelfY += this.shelfHeight;
				this.shelfHeight = 0;
			}
			if (this.shelfY + h > SIZE) { // full: start over (this frame's earlier mobs may flicker once)
				LOG.info("PortalCraft: mob atlas full ({} textures); starting over", this.rects.size());
				this.rects.clear();
				java.util.Arrays.fill(this.pixels, 0);
				this.shelfX = this.shelfY = this.shelfHeight = 0;
			}
			int x0 = this.shelfX, y0 = this.shelfY;
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) {
					this.pixels[(y0 + y) * SIZE + x0 + x] = WorldFormat.argbToRgba(image.getPixel(x, y));
				}
			}
			this.shelfX += w;
			this.shelfHeight = Math.max(this.shelfHeight, h);
			r = new float[] {(float) x0 / SIZE, (float) y0 / SIZE, (float) w / SIZE, (float) h / SIZE};
			this.rects.put(texture, r);
			this.dirty = true;
			LOG.info("PortalCraft: mob atlas + {} ({}x{} at {},{})", texture, w, h, x0, y0);
			return r;
		} finally {
			if (owned) {
				image.close();
			}
		}
	}

	/** Sends the atlas if it changed, or the mapping is new. */
	void flush() {
		if (this.rects.isEmpty()) {
			return;
		}
		if (this.dirty || this.sentGeneration != WorldLink.generation()) {
			WorldLink.writeMobAtlas(this.pixels);
			this.dirty = false;
			this.sentGeneration = WorldLink.generation();
		}
	}
}
