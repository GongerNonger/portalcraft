package dev.portalcraft.client.skin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import com.mojang.blaze3d.platform.NativeImage;
import dev.portalcraft.PortalCraft;
import dev.portalcraft.client.mixin.SkinTextureDownloaderInvoker;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.ClientAsset;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Your own skin when playing offline (the dev client, or any session without one): put the skin
 * PNG at config/portalcraft-skin.png (config/portalcraft-skin-slim.png for the slim model). A signed-in
 * player's real skin is left alone unless the file is there. 64x32 legacy skins are cleaned up the
 * way Minecraft cleans up a downloaded one.
 */
public final class LocalSkin {
	private static final Logger LOG = LoggerFactory.getLogger("portalcraft");
	private static final Identifier TEXTURE = PortalCraft.id("local_skin");

	private static boolean tried;
	private static @Nullable PlayerModelType model;

	private LocalSkin() {
	}

	/** `skin` with the local skin's body and model, or null if there's no local skin. */
	public static @Nullable PlayerSkin apply(PlayerSkin skin) {
		if (!tried) {
			tried = true;
			load();
		}
		if (model == null) {
			return null;
		}
		return new PlayerSkin(new ClientAsset.DownloadedTexture(TEXTURE, "file:portalcraft-skin"), skin.cape(), skin.elytra(), model, false);
	}

	private static void load() {
		Path config = FabricLoader.getInstance().getConfigDir();
		Path wide = config.resolve("portalcraft-skin.png"), slim = config.resolve("portalcraft-skin-slim.png");
		Path file = Files.isRegularFile(slim) ? slim : Files.isRegularFile(wide) ? wide : null;
		if (file == null) {
			return;
		}
		try (InputStream in = Files.newInputStream(file)) {
			NativeImage image = SkinTextureDownloaderInvoker.portalcraft$processLegacySkin(NativeImage.read(in), file.toString());
			Minecraft.getInstance().getTextureManager().register(TEXTURE, new DynamicTexture(() -> "PortalCraft local skin", image));
			model = file == slim ? PlayerModelType.SLIM : PlayerModelType.WIDE;
			LOG.info("PortalCraft: playing with the skin in {}", file);
		} catch (IOException | RuntimeException e) {
			LOG.warn("PortalCraft: couldn't use the skin in {}: {}", file, e.toString());
		}
	}
}
