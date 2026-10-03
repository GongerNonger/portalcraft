package dev.portalcraft.host;

import java.util.List;

import dev.portalcraft.mixin.PressurePlateInvoker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.PressurePlateBlock;
import net.minecraft.world.level.block.WeightedPressurePlateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The host's props press Minecraft's pressure plates (as SkyCraft's NPCs press them): a cube on
 * a wooden or weighted plate, a turret on any plate (turrets count as mobs, so stone plates too).
 * A plate counts the props in its touch box with the entities (BasePressurePlateBlockMixin). It
 * only looks when something steps on it, which props never do in Minecraft, so every server tick
 * the plates under each prop are pressed here; once pressed, a plate checks itself again on its
 * own ticks and lets go when the prop has gone.
 */
public final class HostPlates {
	private HostPlates() {
	}

	/** END_LEVEL_TICK, server thread. */
	public static void tick(ServerLevel level) {
		if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD || !HostCollision.active()) {
			return;
		}
		List<LiveEntities.Prop> props = LiveEntities.props();
		for (LiveEntities.Prop prop : props) {
			AABB b = prop.bounds();
			// A plate is the bottom quarter of its cell: look in the cells at the prop's base.
			int x0 = Mth.floor(b.minX), x1 = Mth.floor(b.maxX), z0 = Mth.floor(b.minZ), z1 = Mth.floor(b.maxZ);
			int y0 = Mth.floor(b.minY - 0.1), y1 = Mth.floor(b.minY + 0.3);
			for (BlockPos pos : BlockPos.betweenClosed(x0, y0, z0, x1, y1, z1)) {
				BlockState state = level.getBlockState(pos);
				if (state.getBlock() instanceof BasePressurePlateBlock plate && signal(state) == 0) {
					((PressurePlateInvoker) plate).portalcraft$checkPressed(null, level, pos.immutable(), state, 0);
				}
			}
		}
	}

	/** How many props are in a plate's touch box, as BasePressurePlateBlock.getEntityCount counts entities of `type`. */
	public static int countIn(AABB box, Class<? extends Entity> type) {
		if (!HostCollision.active()) {
			return 0;
		}
		boolean mobsOnly = LivingEntity.class.isAssignableFrom(type);
		int n = 0;
		for (LiveEntities.Prop prop : LiveEntities.props()) {
			if ((!mobsOnly || prop.mob()) && prop.bounds().intersects(box)) {
				n++;
			}
		}
		return n;
	}

	private static int signal(BlockState state) {
		if (state.hasProperty(PressurePlateBlock.POWERED)) {
			return state.getValue(PressurePlateBlock.POWERED) ? 15 : 0;
		}
		return state.hasProperty(WeightedPressurePlateBlock.POWER) ? state.getValue(WeightedPressurePlateBlock.POWER) : 0;
	}
}
