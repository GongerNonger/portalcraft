package dev.portalcraft.mixin;

import dev.portalcraft.host.HostAim;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Placed blocks break quickly while a host map is loaded (see HostAim.breakProgress). Both sides
 * ask this: the client's MultiPlayerGameMode for its mining progress, and the server's
 * ServerPlayerGameMode to check the break the client reports, so they agree on the speed.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {
	@Inject(method = "getDestroyProgress", at = @At("RETURN"), cancellable = true)
	private void portalcraft$quickBreak(Player player, BlockGetter level, BlockPos pos, CallbackInfoReturnable<Float> cir) {
		float progress = cir.getReturnValueF();
		float quick = HostAim.breakProgress(progress);
		if (quick != progress) {
			cir.setReturnValue(quick);
		}
	}
}
