package dev.portalcraft.mixin;

import dev.portalcraft.host.HostPortalTransit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileDeflection;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A projectile hitting the inside of a Portal portal's hole goes through the portal instead
 * (HostPortalTransit): the hit is skipped, so it keeps its speed.
 */
@Mixin(Projectile.class)
public abstract class ProjectileTransitMixin {
	@Inject(method = "hitTargetOrDeflectSelf", at = @At("HEAD"), cancellable = true)
	private void portalcraft$throughHostPortal(HitResult hit, CallbackInfoReturnable<ProjectileDeflection> cir) {
		if (HostPortalTransit.projectileHit((Projectile) (Object) this, hit)) {
			cir.setReturnValue(ProjectileDeflection.NONE);
		}
	}
}
