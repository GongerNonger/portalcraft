package dev.portalcraft.client.mixin;

import dev.portalcraft.client.world.WorldExporter;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change, chunk load and light update ends in this one method: mark the 16^3 section
 * for re-meshing into the host's world mesh (pattern from SkyCraft, MIT).
 */
@Mixin(LevelExtractor.class)
public abstract class LevelExtractorMixin {
	@Inject(method = "setSectionDirty(IIIZ)V", at = @At("HEAD"))
	private void portalcraft$sectionDirty(int sectionX, int sectionY, int sectionZ, boolean playerChanged, CallbackInfo ci) {
		WorldExporter.markDirty(sectionX, sectionY, sectionZ, playerChanged);
	}
}
