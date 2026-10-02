package dev.portalcraft.mixin;

import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** An arrow's own "has what I'm stuck in gone?" check, for HostArrows. */
@Mixin(AbstractArrow.class)
public interface ArrowInvoker {
	@Invoker("isInGround")
	boolean portalcraft$isInGround();

	@Invoker("shouldFall")
	boolean portalcraft$shouldFall();

	@Invoker("startFalling")
	void portalcraft$startFalling();
}
