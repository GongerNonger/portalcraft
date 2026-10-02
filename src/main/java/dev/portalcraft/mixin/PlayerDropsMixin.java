package dev.portalcraft.mixin;

import dev.portalcraft.host.HostCollision;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a host map is loaded, a broken block always drops, whatever is in hand: every Minecraft
 * block there is one the player placed (in a void world with nothing to mine a pickaxe from), so
 * breaking stone by hand gives it back instead of losing it. Pairs with BlockStateBaseMixin.
 */
@Mixin(Player.class)
public abstract class PlayerDropsMixin {
	@Inject(method = "hasCorrectToolForDrops", at = @At("HEAD"), cancellable = true)
	private void portalcraft$alwaysDrop(BlockState state, CallbackInfoReturnable<Boolean> cir) {
		if (HostCollision.active()) {
			cir.setReturnValue(true);
		}
	}
}
