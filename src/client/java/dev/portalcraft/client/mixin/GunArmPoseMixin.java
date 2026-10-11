package dev.portalcraft.client.mixin;

import dev.portalcraft.PortalCraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The portal gun is worn on the arm, not held like a tool: in third person the arm is raised and
 * follows the look, as for a drawn bow, and the gun's model lies along it (its
 * thirdperson display). Inside a host game this is the Steve the host draws, too.
 */
@Mixin(AvatarRenderer.class)
public abstract class GunArmPoseMixin {
	@Inject(
		method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void portalcraft$gunOnTheArm(Avatar avatar, ItemStack itemInHand, InteractionHand hand, CallbackInfoReturnable<HumanoidModel.ArmPose> cir) {
		if (hand == InteractionHand.MAIN_HAND && itemInHand.is(PortalCraft.PORTAL_GUN)) {
			cir.setReturnValue(HumanoidModel.ArmPose.BOW_AND_ARROW);
		}
	}
}
