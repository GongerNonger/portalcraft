package dev.portalcraft.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** A plate's own "is anything on me" check, for HostPlates to run when a host prop lands on it. */
@Mixin(BasePressurePlateBlock.class)
public interface PressurePlateInvoker {
	@Invoker("checkPressed")
	void portalcraft$checkPressed(Entity entity, Level level, BlockPos pos, BlockState state, int oldSignal);
}
