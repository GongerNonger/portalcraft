package dev.portalcraft.client.mixin;

import com.mojang.blaze3d.platform.Window;
import dev.portalcraft.client.HostDriver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The host game has the real focus while linked; act focused so Minecraft doesn't pause. */
@Mixin(Window.class)
public abstract class WindowMixin {
	@Inject(method = "isFocused", at = @At("HEAD"), cancellable = true)
	private void portalcraft$focused(CallbackInfoReturnable<Boolean> cir) {
		if (HostDriver.linked()) {
			cir.setReturnValue(true);
		}
	}
}
