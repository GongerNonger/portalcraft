package dev.portalcraft.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.SkinTextureDownloader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Minecraft's own clean-up of a downloaded skin (64x32 legacy skins to 64x64), for LocalSkin. */
@Mixin(SkinTextureDownloader.class)
public interface SkinTextureDownloaderInvoker {
	@Invoker("processLegacySkin")
	static NativeImage portalcraft$processLegacySkin(NativeImage image, String url) {
		throw new AssertionError();
	}
}
