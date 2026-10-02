package dev.portalcraft.client.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import dev.portalcraft.client.HostDriver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While linked, the keyboard is the host's and the mouse belongs to the host window. */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {
	@Inject(method = "isKeyDown", at = @At("HEAD"), cancellable = true)
	private static void portalcraft$isKeyDown(int key, CallbackInfoReturnable<Boolean> cir) {
		if (HostDriver.linked()) {
			cir.setReturnValue(HostDriver.isKeyDown(key));
		}
	}

	@Inject(method = "grabMouse", at = @At("HEAD"), cancellable = true)
	private static void portalcraft$grabMouse(Window window, double xpos, double ypos, CallbackInfo ci) {
		if (HostDriver.linked()) {
			ci.cancel();
		}
	}

	@Inject(method = "releaseMouse", at = @At("HEAD"), cancellable = true)
	private static void portalcraft$releaseMouse(Window window, double xpos, double ypos, CallbackInfo ci) {
		if (HostDriver.linked()) {
			ci.cancel();
		}
	}
}
