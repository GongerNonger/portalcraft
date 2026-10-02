package dev.portalcraft.mixin;

import dev.portalcraft.host.HostAim;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Block raycasts see the host's walls. BlockGetter.clip asks ClipContext.getBlockShape for each
 * cell along the ray, on the client (the crosshair pick, Entity.pick) and the server alike (line
 * of sight, projectiles). FALLDAMAGE_RESETTING is left alone: it asks "is this water or a
 * ladder", not "is this solid".
 */
@Mixin(ClipContext.class)
public abstract class ClipContextMixin {
	@Shadow
	@Final
	private ClipContext.Block block;

	@Inject(method = "getBlockShape", at = @At("RETURN"), cancellable = true)
	private void portalcraft$addHostShape(BlockState state, BlockGetter level, BlockPos pos, CallbackInfoReturnable<VoxelShape> cir) {
		if (this.block != ClipContext.Block.FALLDAMAGE_RESETTING) {
			VoxelShape shape = cir.getReturnValue();
			VoxelShape withHost = HostAim.withHost(shape, pos);
			if (withHost != shape) {
				cir.setReturnValue(withHost);
			}
		}
	}
}
