package dev.portalcraft.client.world;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.NativeImage;
import dev.portalcraft.client.mixin.SpriteContentsAccessor;
import dev.portalcraft.client.mixin.TextureAtlasAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * One of Minecraft's texture atlases (blocks, items) as one RGBA8 image, rows top-down, at the
 * atlas's own size and in the GPU atlas's layout, so a BakedQuad's packed UVs index it directly.
 * Built on the CPU from each sprite's first animation frame; the animated ones (water, lava, fire,
 * a nether portal) are then kept on the frame Minecraft shows, tick by tick ({@link #animate}).
 *
 * <p>Adapted from SkyCraft's {@code SkyAtlas} (chasmlol/SkyCraft, MIT), one atlas per image.
 */
final class WorldAtlas {
	/** AtlasIds.BLOCKS or AtlasIds.ITEMS. */
	final Identifier id;
	final int width, height;
	/** One int per pixel whose little-endian bytes are R, G, B, A. */
	final int[] pixels;
	/** The atlas's sprite map: a new object after every resource reload. */
	private final Object sprites;
	/** The sprites with more than one frame, and the frame of each that {@link #pixels} holds. */
	private final List<Animated> animated = new ArrayList<>();
	private static boolean animationFailureLogged;

	/** A sprite's frames in play order (which picture of its image, for how many ticks) and where it sits. */
	private static final class Animated {
		final TextureAtlasSprite sprite;
		final int[] index, time;
		final int rowSize, total;
		int shown = -1;

		Animated(TextureAtlasSprite sprite, int[] index, int[] time, int rowSize) {
			this.sprite = sprite;
			this.index = index;
			this.time = time;
			this.rowSize = Math.max(1, rowSize);
			int total = 0;
			for (int t : time) {
				total += Math.max(1, t);
			}
			this.total = total;
		}

		int frameAt(long tick) {
			int t = (int) Math.floorMod(tick, (long) this.total);
			for (int i = 0; i < this.index.length; i++) {
				t -= Math.max(1, this.time[i]);
				if (t < 0) {
					return this.index[i];
				}
			}
			return this.index[0];
		}
	}

	private WorldAtlas(Identifier id, TextureAtlas atlas) {
		TextureAtlasAccessor a = (TextureAtlasAccessor) atlas;
		this.id = id;
		this.width = a.portalcraft$width();
		this.height = a.portalcraft$height();
		this.sprites = a.portalcraft$sprites();
		this.pixels = new int[this.width * this.height];
		for (TextureAtlasSprite sprite : a.portalcraft$sprites().values()) {
			this.copy(sprite, 0);
			if (sprite.contents().isAnimated()) {
				Animated frames = frames(sprite);
				if (frames != null && frames.index.length > 1) {
					this.animated.add(frames);
				}
			}
		}
	}

	/**
	 * A sprite's play order, out of Minecraft's own record of it (SpriteContents.animatedTexture:
	 * frames of (index, time), laid out frameRowSize to a row of the source image). Those are
	 * private with no accessor a mixin can reach (the frame record is a private nested class), so by
	 * reflection; if a version moves them, the sprite just stays on its first frame.
	 */
	private static Animated frames(TextureAtlasSprite sprite) {
		try {
			Field textureField = SpriteContents.class.getDeclaredField("animatedTexture");
			textureField.setAccessible(true);
			Object texture = textureField.get(sprite.contents());
			if (texture == null) {
				return null;
			}
			Field framesField = texture.getClass().getDeclaredField("frames");
			Field rowField = texture.getClass().getDeclaredField("frameRowSize");
			framesField.setAccessible(true);
			rowField.setAccessible(true);
			List<?> frames = (List<?>) framesField.get(texture);
			int[] index = new int[frames.size()], time = new int[frames.size()];
			for (int i = 0; i < index.length; i++) {
				Object frame = frames.get(i);
				Method indexOf = frame.getClass().getDeclaredMethod("index"), timeOf = frame.getClass().getDeclaredMethod("time");
				indexOf.setAccessible(true);
				timeOf.setAccessible(true);
				index[i] = (Integer) indexOf.invoke(frame);
				time[i] = (Integer) timeOf.invoke(frame);
			}
			return new Animated(sprite, index, time, rowField.getInt(texture));
		} catch (ReflectiveOperationException | RuntimeException e) {
			if (!animationFailureLogged) {
				animationFailureLogged = true;
				org.slf4j.LoggerFactory.getLogger("portalcraft").warn("PortalCraft: can't read sprite animations; water and lava stay still", e);
			}
			return null;
		}
	}

	/**
	 * Puts every animated sprite on the frame it shows at game tick {@code tick}. Returns the
	 * rectangles of {@link #pixels} that changed, four ints each (x, y, width, height); empty if none.
	 */
	int[] animate(long tick) {
		int[] rects = new int[this.animated.size() * 4];
		int n = 0;
		for (Animated a : this.animated) {
			int frame = a.frameAt(tick);
			if (frame == a.shown) {
				continue;
			}
			a.shown = frame;
			int[] rect = this.copy(a.sprite, frame, a.rowSize);
			if (rect != null) {
				System.arraycopy(rect, 0, rects, n, 4);
				n += 4;
			}
		}
		return n == rects.length ? rects : java.util.Arrays.copyOf(rects, n);
	}

	/** The atlas {@code id} now, or null while it isn't stitched (during a resource reload). */
	static WorldAtlas build(Minecraft minecraft, Identifier id) {
		TextureAtlas atlas = minecraft.getAtlasManager().getAtlasOrThrow(id);
		TextureAtlasAccessor a = (TextureAtlasAccessor) atlas;
		if (a.portalcraft$sprites().isEmpty() || a.portalcraft$width() <= 0 || a.portalcraft$height() <= 0) {
			return null;
		}
		return new WorldAtlas(id, atlas);
	}

	/** True if the game's atlas was rebuilt (resource reload) since this copy was made. */
	boolean stale(Minecraft minecraft) {
		Map<?, ?> now = ((TextureAtlasAccessor) minecraft.getAtlasManager().getAtlasOrThrow(this.id)).portalcraft$sprites();
		return now != this.sprites;
	}

	/** True if this fits the block atlas region. */
	boolean fitsMapping() {
		return this.width <= WorldFormat.ATLAS_MAX_W && this.height <= WorldFormat.ATLAS_MAX_H;
	}

	/**
	 * Copies one frame of a sprite to where its UVs point. Sprites sit inside a padded cell
	 * ({@code getX/getY} is the cell corner; the image starts {@code padding} in), and the padding
	 * gets the nearest edge pixel, as Minecraft does, so filtering doesn't bleed in neighbours.
	 */
	private void copy(TextureAtlasSprite sprite, int frame) {
		this.copy(sprite, frame, 1);
	}

	/** ... frame {@code frame} of a source image with {@code rowSize} frames to a row. Returns the rectangle written, or null. */
	private int[] copy(TextureAtlasSprite sprite, int frame, int rowSize) {
		NativeImage image = ((SpriteContentsAccessor) sprite.contents()).portalcraft$originalImage();
		int w = Math.min(sprite.contents().width(), image.getWidth());
		int h = Math.min(sprite.contents().height(), image.getHeight());
		if (w <= 0 || h <= 0) {
			return null;
		}
		int fx = (frame % rowSize) * w, fy = (frame / rowSize) * h;
		if (fx + w > image.getWidth() || fy + h > image.getHeight()) {
			return null; // not a frame this image has
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
				this.pixels[ty * this.width + tx] = WorldFormat.argbToRgba(image.getPixel(fx + Math.clamp(x, 0, w - 1), fy + sy));
			}
		}
		int x0 = Math.max(0, imageX - pad), y0 = Math.max(0, imageY - pad);
		return new int[] {x0, y0, Math.min(this.width, imageX + w + pad) - x0, Math.min(this.height, imageY + h + pad) - y0};
	}
}
