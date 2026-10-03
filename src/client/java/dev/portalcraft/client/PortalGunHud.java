package dev.portalcraft.client;

import dev.portalcraft.PortalColor;
import dev.portalcraft.PortalCraft;
import dev.portalcraft.PortalEntity;
import dev.portalcraft.host.HostLink;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.Entity;

/**
 * The portal gun's crosshair, as Portal draws it: a blue arc on the left and an orange one on the
 * right of the crosshair, thick when that portal of yours is placed and faint when it isn't, so you
 * can see at a glance which end is out there. Shown while you hold the gun. Inside Portal (a host
 * linked), Portal draws its own and this stays out of the way.
 */
final class PortalGunHud {
	private static final int BLUE = 0x2A8CFF, ORANGE = 0xFF8A1E;
	private static final int RADIUS = 7;

	private PortalGunHud() {
	}

	static void extract(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.level == null || HostLink.current() != null || !mc.player.getMainHandItem().is(PortalCraft.PORTAL_GUN)
			|| !mc.options.getCameraType().isFirstPerson()) {
			return;
		}
		boolean blue = false, orange = false;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e instanceof PortalEntity p && p.ownedBy(mc.player.getUUID())) {
				if (p.color() == PortalColor.PRIMARY) {
					blue = true;
				} else {
					orange = true;
				}
			}
		}
		int cx = g.guiWidth() / 2, cy = g.guiHeight() / 2;
		arc(g, cx, cy, 120, 240, BLUE, blue);
		arc(g, cx, cy, -60, 60, ORANGE, orange);
	}

	/** An arc of the ring from `fromDeg` to `toDeg` (0 is right, counter-clockwise on screen). */
	private static void arc(GuiGraphicsExtractor g, int cx, int cy, int fromDeg, int toDeg, int rgb, boolean placed) {
		int argb = (placed ? 0xFF000000 : 0x80000000) | rgb;
		for (int deg = fromDeg; deg <= toDeg; deg += 4) {
			double a = Math.toRadians(deg);
			for (int r = placed ? RADIUS - 1 : RADIUS; r <= RADIUS; r++) {
				int x = cx + (int) Math.round(Math.cos(a) * r), y = cy - (int) Math.round(Math.sin(a) * r);
				g.fill(x, y, x + 1, y + 1, argb);
			}
		}
	}
}
