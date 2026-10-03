package dev.portalcraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.portalcraft.PortalCraft;
import dev.portalcraft.client.gun.GunAnimation;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The portal gun's kick when it fires and its float in a long fall, on the first-person item's pose just before it's drawn; and,
 * inside a host game, the hand and what it holds lit by the host's light instead of Minecraft's.
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class FirstPersonGunRecoilMixin {
	@ModifyVariable(method = "submitArmWithItem", at = @At("HEAD"), argsOnly = true)
	private int portalcraft$hostLight(int lightCoords) {
		int level = dev.portalcraft.client.HostDriver.handLightLevel();
		if (level < 0) {
			return lightCoords;
		}
		// The gun's own shot lights it for a moment: a few levels up, fading with the flash.
		level = Math.min(15, level + Math.round(7.0F * GunAnimation.flash()));
		return level << 20; // sky light `level`, no block light: neutral, at the world's noon
	}

	@Inject(method = "submitArmWithItem", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void portalcraft$recoil(PlayerRenderState playerState, FirstPersonHandsAndItemsRenderState state, float partialTicks, float xRot,
		InteractionHand hand, float attack, ItemStack itemStack, float inverseArmHeight, PoseStack poseStack, SubmitNodeCollector collector, int lightCoords,
		CallbackInfo ci) {
		if (hand == InteractionHand.MAIN_HAND && itemStack.is(PortalCraft.PORTAL_GUN)) {
			GunAnimation.applyFall(poseStack);
			GunAnimation.applyRecoil(poseStack);
		}
	}
}
