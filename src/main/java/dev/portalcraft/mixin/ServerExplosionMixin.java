package dev.portalcraft.mixin;

import dev.portalcraft.host.HostEvents;
import net.minecraft.world.level.ServerExplosion;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Every Minecraft explosion (TNT, creepers, beds, crystals) is also a blast in the host (HostEvents). */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private Vec3 center;

	@Shadow
	@Final
	private float radius;

	@Inject(method = "explode", at = @At("HEAD"))
	private void portalcraft$hostBlast(CallbackInfoReturnable<Integer> cir) {
		HostEvents.explosion(this.center, this.radius);
	}
}
