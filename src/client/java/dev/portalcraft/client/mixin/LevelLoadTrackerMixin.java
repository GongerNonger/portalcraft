package dev.portalcraft.client.mixin;

import dev.portalcraft.host.HostLink;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Joining the world and respawning wait for the player's chunk section to be compiled for drawing.
 * With a host game linked Minecraft draws no world (LevelRendererMixin), so it never is: every join
 * and every respawn sat 30 s on the loading screen until the wait timed out. The host draws the
 * world, so the section is as ready as it needs to be.
 */
@Mixin(targets = "net.minecraft.client.multiplayer.LevelLoadTracker$WaitingForPlayerChunk")
public abstract class LevelLoadTrackerMixin {
	@Inject(method = "isReady", at = @At("HEAD"), cancellable = true)
	private void portalcraft$hostDrawsTheWorld(CallbackInfoReturnable<Boolean> cir) {
		if (HostLink.current() != null) {
			cir.setReturnValue(true);
		}
	}
}
