package dev.portalcraft.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.portalcraft.PortalCraft;
import dev.portalcraft.host.HostLink;
import dev.portalcraft.host.Proto;
import net.minecraft.client.renderer.FirstPersonHandsAndItemsRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.FirstPersonHandsAndItemsRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * One portal gun on screen: while the host draws its own (Portal's, which animates as it fires),
 * the Minecraft portal gun in Steve's hand isn't drawn over it (HostState kHostGun).
 */
@Mixin(FirstPersonHandsAndItemsRenderer.class)
public abstract class HandItemMixin {
	@Inject(method = "submitArmWithItem", at = @At("HEAD"), cancellable = true)
	private void portalcraft$hostDrawsTheGun(PlayerRenderState player, FirstPersonHandsAndItemsRenderState state, float a, float b, InteractionHand hand,
		float c, ItemStack stack, float d, PoseStack pose, SubmitNodeCollector out, int light, CallbackInfo ci) {
		Proto.HostState s = HostLink.current();
		if (s != null && s.hostDrawsGun() && stack.is(PortalCraft.PORTAL_GUN)) {
			ci.cancel();
		}
	}
}
