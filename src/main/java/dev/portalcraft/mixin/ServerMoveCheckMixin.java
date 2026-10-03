package dev.portalcraft.mixin;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The server's other check on a player's move, beside "moved wrongly" (LivingEntityAirMixin): a
 * move that ends touching a block the player wasn't touching before is refused, and the client is
 * put back where it was, with nothing logged. Steve comes out of a host portal with his hull upright
 * and his centre just past its plane, so half of him is still in the wall around it, as Portal's own
 * player is: the server sent him back to the portal he had gone in through, a few ticks later
 * ("the blue portal grabs me and pulls me back"), or, in a fast fall, back under the floor portal,
 * from where he fell out of the map. Inside a host map the move is taken as sent.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerMoveCheckMixin {
	@Inject(method = "isEntityCollidingWithAnythingNew", at = @At("HEAD"), cancellable = true)
	private void portalcraft$hostMovesAreTaken(CallbackInfoReturnable<Boolean> cir) {
		if (dev.portalcraft.host.HostCollision.active()) {
			cir.setReturnValue(false);
		}
	}
}
