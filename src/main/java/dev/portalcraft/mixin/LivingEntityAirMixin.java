package dev.portalcraft.mixin;

import dev.portalcraft.host.PortalAir;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Portal air (see PortalAir) for the local player: travelInAir skips its drag when
 * shouldDiscardFriction says so, falls by getEffectiveGravity, and is limited to Portal's speeds.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityAirMixin {
	@Unique
	private double portalcraft$horizontalBefore;

	@Inject(method = "shouldDiscardFriction", at = @At("HEAD"), cancellable = true)
	private void portalcraft$noAirDrag(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Player player && PortalAir.active(player)) {
			cir.setReturnValue(true);
		}
	}

	@Inject(method = "getEffectiveGravity", at = @At("RETURN"), cancellable = true)
	private void portalcraft$portalGravity(CallbackInfoReturnable<Double> cir) {
		if ((Object) this instanceof Player player && PortalAir.active(player) && cir.getReturnValueD() > 0.0) {
			cir.setReturnValue(PortalAir.GRAVITY);
		}
	}

	@Inject(method = "travelInAir", at = @At("HEAD"))
	private void portalcraft$beforeAirStep(Vec3 input, CallbackInfo ci) {
		Vec3 v = ((LivingEntity) (Object) this).getDeltaMovement();
		this.portalcraft$horizontalBefore = Math.sqrt(v.x * v.x + v.z * v.z);
	}

	@Inject(method = "travelInAir", at = @At("TAIL"))
	private void portalcraft$afterAirStep(Vec3 input, CallbackInfo ci) {
		LivingEntity self = (LivingEntity) (Object) this;
		if (self instanceof Player player && PortalAir.active(player)) {
			self.setDeltaMovement(PortalAir.limit(self.getDeltaMovement(), this.portalcraft$horizontalBefore));
		}
	}
}
