package dev.portalcraft.client.mixin;

import dev.portalcraft.client.HostDriver;
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
}
