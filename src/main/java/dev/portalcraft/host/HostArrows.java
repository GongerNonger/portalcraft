package dev.portalcraft.host;

import dev.portalcraft.mixin.ArrowInvoker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.level.entity.EntityTypeTest;

/**
 * Arrows (and tridents) stuck in a host prop come loose when it moves, as an arrow in a block a
 * piston pushed does. Vanilla only asks an arrow whether what it's stuck in is still there when
 * the block at its spot changes, and at a host prop that's air before and after; so every few
 * ticks the stuck ones are asked here. Their check (shouldFall) sees the host's moving geometry.
 */
public final class HostArrows {
	private static final int EVERY_TICKS = 4;
	private static int ticks;

	private HostArrows() {
	}

	/** END_LEVEL_TICK, server thread. */
	public static void tick(ServerLevel level) {
		if (level.dimension() != net.minecraft.world.level.Level.OVERWORLD || !HostCollision.active() || ++ticks % EVERY_TICKS != 0) {
			return;
		}
		for (AbstractArrow arrow : level.getEntities(EntityTypeTest.forClass(AbstractArrow.class), a -> ((ArrowInvoker) a).portalcraft$isInGround())) {
			ArrowInvoker stuck = (ArrowInvoker) arrow;
			if (stuck.portalcraft$shouldFall()) {
				stuck.portalcraft$startFalling();
			}
		}
	}
}
