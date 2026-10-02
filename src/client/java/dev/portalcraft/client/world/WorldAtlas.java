package dev.portalcraft.client.world;

import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import dev.portalcraft.client.mixin.SpriteContentsAccessor;
import dev.portalcraft.client.mixin.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;

/**
 * Minecraft's block atlas as one RGBA8 image, rows top-down, at the atlas's own size and in the
 * GPU atlas's layout, so a BakedQuad's packed UVs index it directly. Built on the CPU from each
 * sprite's first animation frame; animated sprites (water, lava, fire) stay on that frame.
 *
 * <p>Adapted from SkyCraft's {@code SkyAtlas} (chasmlol/SkyCraft, MIT), blocks atlas only.
 */
final class WorldAtlas {
	final int width, height;
	/** One int per pixel whose little-endian bytes are R, G, B, A. */
	final int[] pixels;
	/** The atlas's sprite map: a new object after every resource reload. */
	private final Object sprites;

	private WorldAtlas(TextureAtlas atlas) {
		TextureAtlasAccessor a = (TextureAtlasAccessor) atlas;
		this.width = a.portalcraft$width();
		this.height = a.portalcraft$height();
		this.sprites = a.portalcraft$sprites();
		this.pixels = new int[this.width * this.height];
		for (TextureAtlasSprite sprite : a.portalcraft$sprites().values()) {
			this.copy(sprite);
		}
	}

	/** The block atlas now, or null while it isn't stitched (during a resource reload). */
	static WorldAtlas build(Minecraft minecraft) {
		TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
		TextureAtlasAccessor a = (TextureAtlasAccessor) atlas;
		if (a.portalcraft$sprites().isEmpty() || a.portalcraft$width() <= 0 || a.portalcraft$height() <= 0) {
			return null;
		}
		return new WorldAtlas(atlas);
	}

	/** True if the game's block atlas was rebuilt (resource reload) since this copy was made. */
	boolean stale(Minecraft minecraft) {
		Map<?, ?> now = ((TextureAtlasAccessor) minecraft.getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS)).portalcraft$sprites();
		return now != this.sprites;
	}

	boolean fitsMapping() {
		return this.width <= WorldFormat.ATLAS_MAX_W && this.height <= WorldFormat.ATLAS_MAX_H;
	}

	/**
	 * Copies a sprite's first frame to where its UVs point. Sprites sit inside a padded cell
	 * ({@code getX/getY} is the cell corner; the image starts {@code padding} in), and the padding
	 * gets the nearest edge pixel, as Minecraft does, so filtering doesn't bleed in neighbours.
	 */
	private void copy(TextureAtlasSprite sprite) {
		NativeImage image = ((SpriteContentsAccessor) sprite.contents()).portalcraft$originalImage();
		int w = Math.min(sprite.contents().width(), image.getWidth());
		int h = Math.min(sprite.contents().height(), image.getHeight());
		if (w <= 0 || h <= 0) {
			return;
		}
		int imageX = Math.round(sprite.getU0() * this.width), imageY = Math.round(sprite.getV0() * this.height);
		int pad = Math.max(0, Math.min(imageX - sprite.getX(), imageY - sprite.getY()));
		for (int y = -pad; y < h + pad; y++) {
			int ty = imageY + y;
			if (ty < 0 || ty >= this.height) {
				continue;
			}
			int sy = Math.clamp(y, 0, h - 1);
			for (int x = -pad; x < w + pad; x++) {
				int tx = imageX + x;
				if (tx < 0 || tx >= this.width) {
					continue;
				}
				this.pixels[ty * this.width + tx] = WorldFormat.argbToRgba(image.getPixel(Math.clamp(x, 0, w - 1), sy));
			}
		}
	}
}
