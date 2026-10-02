package dev.portalcraft.mixin;

import dev.portalcraft.host.HostCollision;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Inside a host map Steve is as wide as the host's player (Portal's hull is 32 units = 0.8 blocks,
 * Minecraft's 0.6). Otherwise Minecraft lets him stand 4 units closer to walls than the host
 * allows, and the host's physics shoves him back out every tick.
 */
@Mixin(Avatar.class)
public abstract class AvatarDimensionsMixin {
	private static final float HOST_WIDTH = 0.8F;

	@Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
	private void portalcraft$hostWidth(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
		EntityDimensions d = cir.getReturnValue();
		if (HostCollision.active() && d.width() < HOST_WIDTH && (pose == Pose.STANDING || pose == Pose.CROUCHING)) {
			cir.setReturnValue(new EntityDimensions(HOST_WIDTH, d.height(), d.eyeHeight(), d.attachments(), d.fixed()));
		}
	}
}
