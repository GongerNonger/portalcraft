package dev.portalcraft.mixin;

import dev.portalcraft.host.HostEvents;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arrows, snowballs, eggs, pearls and tridents that hit the host's geometry: if a host entity was
 * there (a cube, a turret), it gets the hit (HostEvents). Server side only, so each lands once.
 */
@Mixin(Projectile.class)
public abstract class ProjectileHitMixin {
	@Inject(method = "onHitBlock", at = @At("HEAD"))
	private void portalcraft$hostHit(BlockHitResult hitResult, CallbackInfo ci) {
		Projectile self = (Projectile) (Object) this;
		if (!self.level().isClientSide()) {
			HostEvents.queueHit(hitResult.getLocation(), self.getDeltaMovement(), 4.0F);
		}
	}
}
