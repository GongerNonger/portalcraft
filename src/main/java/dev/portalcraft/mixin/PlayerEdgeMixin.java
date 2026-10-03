package dev.portalcraft.mixin;

import dev.portalcraft.host.HostCollision;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Sneaking, Minecraft won't let a player walk off an edge. A host portal in the floor is an edge
 * to it, so a crouched Steve stopped at the rim and stood there; Portal's crouched player walks
 * in and falls through. Over a portal's opening the guard is off; everywhere else it stays.
 */
@Mixin(Player.class)
public abstract class PlayerEdgeMixin {
	@Inject(method = "maybeBackOffFromEdge", at = @At("HEAD"), cancellable = true)
	private void portalcraft$walkIntoPortals(Vec3 delta, MoverType moverType, CallbackInfoReturnable<Vec3> cir) {
		if (!HostCollision.active()) {
			return;
		}
		AABB to = ((Player) (Object) this).getBoundingBox().move(delta.x, 0.0, delta.z).expandTowards(0.0, -0.25, 0.0);
		for (AABB hole : HostCollision.holes()) {
			if (hole.intersects(to)) {
				cir.setReturnValue(delta);
				return;
			}
		}
	}
}
