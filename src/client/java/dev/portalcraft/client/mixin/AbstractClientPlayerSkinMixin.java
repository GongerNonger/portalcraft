package dev.portalcraft.client.mixin;

import dev.portalcraft.client.skin.LocalSkin;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.PlayerSkin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The player this client plays shows LocalSkin's skin, when there is one. */
@Mixin(AbstractClientPlayer.class)
public abstract class AbstractClientPlayerSkinMixin {
	@Inject(method = "getSkin", at = @At("RETURN"), cancellable = true)
	private void portalcraft$localSkin(CallbackInfoReturnable<PlayerSkin> cir) {
		if ((Object) this instanceof LocalPlayer) {
			PlayerSkin skin = LocalSkin.apply(cir.getReturnValue());
			if (skin != null) {
				cir.setReturnValue(skin);
			}
		}
	}
}
