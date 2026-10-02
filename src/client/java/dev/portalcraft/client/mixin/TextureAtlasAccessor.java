package dev.portalcraft.client.mixin;

import java.util.Map;

import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The atlas's sprites and size, for copying it to the host (WorldAtlas; accessor as in SkyCraft, MIT). */
@Mixin(TextureAtlas.class)
public interface TextureAtlasAccessor {
	@Accessor("texturesByName")
	Map<Identifier, TextureAtlasSprite> portalcraft$sprites();

	@Accessor("width")
	int portalcraft$width();

	@Accessor("height")
	int portalcraft$height();
}
