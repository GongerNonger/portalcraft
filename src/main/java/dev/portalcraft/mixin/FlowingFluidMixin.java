package dev.portalcraft.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import dev.portalcraft.host.HostFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Water and lava see the host's walls and floors (see HostFluids for what counts as solid).
 *
 * <p>canPassThroughWall is FlowingFluid's one gate for a fluid crossing into a neighbouring cell:
 * falling (spread, isWaterHole), spreading sideways (getSpread), the slope search for the nearest
 * drop (getSlopeDistance), and which neighbours feed a cell (getNewLiquid, including the fluid
 * above). Its target is always sourcePos.relative(direction). Server only.
 *
 * <p>getNewLiquid turns a cell between two sources into a source only over something solid; over a
 * host floor that's AIR to Minecraft, so infinite water pools wouldn't form on Portal's floors.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {
	@Inject(method = "canPassThroughWall", at = @At("HEAD"), cancellable = true)
	private static void portalcraft$hostWall(Direction direction, BlockGetter level, BlockPos sourcePos, BlockState sourceState, BlockPos targetPos,
		BlockState targetState, CallbackInfoReturnable<Boolean> cir) {
		if (HostFluids.blocks(sourcePos, targetPos, direction)) {
			cir.setReturnValue(false);
		}
	}

	@ModifyExpressionValue(method = "getNewLiquid", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isSolid()Z"))
	private boolean portalcraft$hostFloorIsSolid(boolean solid, @Local(argsOnly = true) BlockPos pos) {
		return solid || HostFluids.floored(pos);
	}
}
