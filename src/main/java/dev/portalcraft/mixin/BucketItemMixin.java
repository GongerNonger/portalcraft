package dev.portalcraft.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.portalcraft.host.HostFluids;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * A bucket emptied against host geometry pours into the cell BlockPlaceContextMixin would put a
 * block in (HostFluids.bucketTarget). Vanilla always pours into the neighbour of the face hit,
 * which for Portal's floor (its surface is rarely on a block boundary) leaves the source hanging
 * up to a block above the floor. Runs on the client's prediction and the server alike.
 *
 * <p>placePos is BucketItem.use's third BlockPos local (after pos and directionOffsetPos); every
 * later use (emptyContents, the fish of a fish bucket, the advancement) reads it.
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {
	@ModifyVariable(method = "use", at = @At("STORE"), ordinal = 2)
	private BlockPos portalcraft$pourOnHostSurface(BlockPos placePos, @Local BlockHitResult hitResult, @Local BlockState clicked) {
		if (!clicked.isAir()) {
			return placePos;
		}
		return HostFluids.bucketTarget(hitResult.getBlockPos(), hitResult.getDirection(), hitResult.getLocation(), placePos);
	}
}
