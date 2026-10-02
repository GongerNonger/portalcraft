package dev.portalcraft.client.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import dev.portalcraft.client.HostDriver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Minecraft throttles itself when unfocused; while linked it reports every frame, so keep it at full rate. */
@Mixin(FramerateLimitTracker.class)
public abstract class FramerateLimitTrackerMixin {
	@Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
	private void portalcraft$fullRate(CallbackInfoReturnable<Integer> cir) {
		if (HostDriver.linked()) {
			cir.setReturnValue(144);
		}
	}
}
