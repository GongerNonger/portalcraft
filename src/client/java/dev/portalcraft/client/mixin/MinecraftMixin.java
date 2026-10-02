package dev.portalcraft.client.mixin;

import dev.portalcraft.client.FrameExporter;
import dev.portalcraft.client.HostDriver;
import dev.portalcraft.client.OverlayLink;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Inject(method = "runTick", at = @At("HEAD"))
	private void portalcraft$frame(boolean advanceGameTime, CallbackInfo ci) {
		HostDriver.frame((Minecraft) (Object) this);
	}

	/** Right after Minecraft rendered its frame (hand + HUD only while linked): send it to the host. */
	@Inject(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;render()V", shift = At.Shift.AFTER)
	)
	private void portalcraft$afterRender(boolean advanceGameTime, CallbackInfo ci) {
		if (HostDriver.linked() && OverlayLink.open()) {
			FrameExporter.capture((Minecraft) (Object) this);
		}
	}
}
