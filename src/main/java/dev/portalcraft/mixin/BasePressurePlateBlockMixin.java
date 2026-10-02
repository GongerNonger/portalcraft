package dev.portalcraft.mixin;

import dev.portalcraft.host.HostPlates;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pressure plates count the host's props on them along with Minecraft's entities (HostPlates). */
@Mixin(BasePressurePlateBlock.class)
public abstract class BasePressurePlateBlockMixin {
	@Inject(method = "getEntityCount", at = @At("RETURN"), cancellable = true)
	private static void portalcraft$hostProps(Level level, AABB box, Class<? extends Entity> type, CallbackInfoReturnable<Integer> cir) {
		int props = HostPlates.countIn(box, type);
		if (props > 0) {
			cir.setReturnValue(cir.getReturnValueI() + props);
		}
	}
}
