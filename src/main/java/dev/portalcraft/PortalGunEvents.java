package dev.portalcraft;

import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.EventFactory;
import net.minecraft.server.level.ServerPlayer;

/**
 * Hook for host-game bridges (Portal, HL2, ...). A bridge that returns true from FIRE takes over
 * the shot, e.g. by making the host game fire its own real portal, and PortalCraft does nothing.
 */
public final class PortalGunEvents {
	private PortalGunEvents() {
	}

	@FunctionalInterface
	public interface Fire {
		boolean onFire(ServerPlayer player, PortalColor color);
	}

	public static final Event<Fire> FIRE = EventFactory.createArrayBacked(Fire.class, listeners -> (player, color) -> {
		for (Fire listener : listeners) {
			if (listener.onFire(player, color)) {
				return true;
			}
		}
		return false;
	});
}
