package dev.portalcraft.mixin;

import dev.portalcraft.host.HostCollision;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Minecraft puts a player who doesn't fit standing into a crouch, and one who doesn't fit crouched
 * into its crawl (the swimming pose, 0.6 blocks high). Inside a host map a cube's corner a hair
 * inside Steve's hull was enough: pushing a cube by its corner he dropped flat and "swam" along the
 * floor, with a hull a quarter of his height. Here the question is asked of the middle of his hull
 * only (a tenth of a block in from its sides), so a real low ceiling still keeps him crouched, and
 * he is never made to crawl: Portal has no such pose.
 */
@Mixin(Player.class)
public abstract class PlayerPoseMixin {
	@Inject(method = "canPlayerFitWithinBlocksAndEntitiesWhen", at = @At("HEAD"), cancellable = true)
	private void portalcraft$noCrawling(Pose pose, CallbackInfoReturnable<Boolean> cir) {
		if (!HostCollision.active()) {
			return;
		}
		if (pose == Pose.CROUCHING || pose == Pose.SWIMMING) {
			cir.setReturnValue(true);
			return;
		}
		Player self = (Player) (Object) this;
		cir.setReturnValue(self.level().noCollision(self, self.getDimensions(pose).makeBoundingBox(self.position()).deflate(0.1, 1.0E-7, 0.1)));
	}
}
