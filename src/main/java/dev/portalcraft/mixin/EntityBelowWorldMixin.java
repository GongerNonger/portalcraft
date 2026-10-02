package dev.portalcraft.mixin;

import dev.portalcraft.host.HostCollision;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Host maps can sit far below Minecraft's world floor; the host's own geometry is the floor then. */
@Mixin(Entity.class)
public abstract class EntityBelowWorldMixin {
	@Inject(method = "onBelowWorld", at = @At("HEAD"), cancellable = true)
	private void portalcraft$noVoidDeath(CallbackInfo ci) {
		if (HostCollision.active()) {
			ci.cancel();
		}
	}
}
