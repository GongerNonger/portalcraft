package dev.portalcraft.mixin;

import dev.portalcraft.host.HostAim;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A cell holding host geometry is AIR to Minecraft, and BlockPlaceContext places INTO a clicked
 * block that can be replaced. Clicking a host wall would then put the block inside the wall.
 * Instead it goes in front of the wall (see HostAim.placeInNeighbour for the half-cell rule).
 * Runs on the client's prediction and the server's real placement alike.
 */
@Mixin(BlockPlaceContext.class)
public abstract class BlockPlaceContextMixin {
	@Shadow
	protected boolean replaceClicked;

	@Inject(
		method = "<init>(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/phys/BlockHitResult;)V",
		at = @At("TAIL")
	)
	private void portalcraft$inFrontOfHostWall(Level level, Player player, InteractionHand hand, ItemStack stack, BlockHitResult hit, CallbackInfo ci) {
		if (this.replaceClicked && HostAim.isHostCell(hit.getBlockPos()) && HostAim.placeInNeighbour(hit.getBlockPos(), hit.getDirection(), hit.getLocation())) {
			this.replaceClicked = false;
		}
	}
}
