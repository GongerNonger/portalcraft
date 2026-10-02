package dev.portalcraft.client;

import java.nio.file.Files;
import java.nio.file.Path;

import dev.portalcraft.PortalCraft;
import dev.portalcraft.host.BspMap;
import dev.portalcraft.host.HostCollision;
import dev.portalcraft.host.HostLink;
import dev.portalcraft.host.Proto;
import dev.portalcraft.host.Units;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.sdl.SDLKeyboard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft's half of playing inside a host game. Each tick: follow the host camera, take the
 * host's keyboard, apply the host's teleports, and keep the host's walls as collision. Each frame:
 * report where the player is so the host can put its own player there.
 */
public final class HostDriver {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	private static final Path MAPS = Path.of(System.getProperty("portalcraft.mapsDir",
		System.getenv().getOrDefault("PORTALCRAFT_MAPS", "D:/SteamLibrary/steamapps/common/Portal/portal/maps")));

	private static final boolean[] KEYS = new boolean[256];
	private static boolean linked;
	private static boolean resync;
	private static int teleportAck;
	private static int seq;
	private static String failedMap = "";

	private HostDriver() {
	}

	public static boolean linked() {
		return linked;
	}

	public static boolean isKeyDown(int scancode) {
		return scancode >= 0 && scancode < KEYS.length && KEYS[scancode];
	}

	/** START_CLIENT_TICK: before the player moves this tick. */
	public static void tick(Minecraft minecraft) {
		Proto.HostState s = HostLink.current();
		if (s == null || !s.inGame()) {
			if (linked) {
				// Keep the map's walls: the host is probably just restarting, and without them the
				// player would fall out of the world while it's gone.
				LOG.info("PortalCraft: host gone, releasing control");
				releaseAll(minecraft);
				linked = false;
			}
			return;
		}
		if (!linked) {
			LOG.info("PortalCraft: host linked ({})", s.map());
			linked = true;
			resync = true; // either side may have restarted: start from where the host's player is now
		}
		loadMap(s.map());
		HostCollision.setPortals(s.portals());

		// Losing the link (host restarting) makes Minecraft pause itself; linked again, carry on.
		if (minecraft.gui.screen() instanceof PauseScreen) {
			minecraft.gui.setScreen(null);
		}

		LocalPlayer player = minecraft.player;
		if (player == null) {
			return;
		}
		if (player.isDeadOrDying()) {
			if (minecraft.gui.screen() != null) {
				minecraft.gui.setScreen(null);
			}
			player.respawn();
			resync = true;
			return;
		}
		if (resync) {
			// Just linked, or back from a respawn: stand where the host's player is now.
			resync = false;
			teleport(minecraft, player, Units.toMc(s.origin()), Vec3.ZERO);
			teleportAck = s.teleportSeq();
		}

		float yaw = Units.yawToMc(s.yaw());
		player.setYRot(yaw);
		player.setXRot(s.pitch());
		player.setYHeadRot(yaw);

		if (s.teleportSeq() == 0) {
			teleportAck = 0; // a fresh host session that hasn't placed the player yet
		} else if (s.teleportSeq() != teleportAck) {
			teleport(minecraft, player, Units.toMc(s.teleportOrigin()), Units.velocityToMc(s.teleportVelocity()));
			teleportAck = s.teleportSeq();
		}

		if (s.foreground()) {
			applyKeys(minecraft, s);
		} else {
			releaseAll(minecraft);
		}
	}

	/** Once per render frame: tell the host where Minecraft's player is. */
	public static void frame(Minecraft minecraft) {
		if (!linked) {
			return;
		}
		LocalPlayer player = minecraft.player;
		boolean ready = player != null && minecraft.level != null;
		Vec3 pos = Vec3.ZERO, vel = Vec3.ZERO;
		if (ready) {
			float partial = minecraft.getDeltaTracker().getGameTimeDeltaPartialTick(true);
			pos = Units.toSrc(player.getPosition(partial));
			// Real motion this tick. deltaMovement is already scaled down by ground friction, which
			// would hand the host about half the walking speed (and weak flings through portals).
			vel = Units.velocityToSrc(player.position().subtract(player.xo, player.yo, player.zo));
		}
		HostLink.send(Proto.writeMcState(++seq, ready ? Proto.MC_READY : 0, teleportAck, pos, vel,
			ready && player.onGround(), ready && player.isShiftKeyDown(), ready && player.getMainHandItem().is(PortalCraft.PORTAL_GUN)));
	}

	private static void loadMap(String name) {
		if (name.equals(HostCollision.mapName()) || name.equals(failedMap)) {
			return;
		}
		Path file = MAPS.resolve(name + ".bsp");
		try {
			long t0 = System.nanoTime();
			BspMap map = BspMap.load(file, name);
			HostCollision.setMap(map);
			LOG.info("PortalCraft: loaded {} ({} solid brushes) in {} ms", file, map.brushes.size(), (System.nanoTime() - t0) / 1_000_000);
		} catch (Exception e) {
			failedMap = name;
			HostCollision.clear();
			LOG.warn("PortalCraft: can't read {} ({}). Set -Dportalcraft.mapsDir to the game's maps folder.", file, e.toString());
		}
	}

	private static void teleport(Minecraft minecraft, LocalPlayer player, Vec3 pos, Vec3 velocity) {
		player.setPos(pos);
		player.setDeltaMovement(velocity);
		player.resetFallDistance();
		var server = minecraft.getSingleplayerServer();
		if (server != null) {
			var uuid = player.getUUID();
			server.execute(() -> {
				ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
				if (sp != null) {
					sp.teleport(new TeleportTransition(sp.level(), pos, velocity, sp.getYRot(), sp.getXRot(), TeleportTransition.DO_NOTHING));
					sp.resetFallDistance();
				}
			});
		}
		LOG.info("PortalCraft: host moved the player to {} (velocity {})", pos, velocity);
	}

	private static void applyKeys(Minecraft minecraft, Proto.HostState s) {
		for (int sc = 1; sc < KEYS.length; sc++) {
			boolean down = s.keyDown(sc);
			if (down != KEYS[sc]) {
				KEYS[sc] = down;
				press(minecraft, sc, down);
			}
		}
	}

	private static void releaseAll(Minecraft minecraft) {
		for (int sc = 1; sc < KEYS.length; sc++) {
			if (KEYS[sc]) {
				KEYS[sc] = false;
				press(minecraft, sc, false);
			}
		}
	}

	private static void press(Minecraft minecraft, int scancode, boolean down) {
		int mods = 0;
		if (KEYS[225]) mods |= 0x0001; // SDL_KMOD_LSHIFT
		if (KEYS[229]) mods |= 0x0002; // SDL_KMOD_RSHIFT
		if (KEYS[224]) mods |= 0x0040; // SDL_KMOD_LCTRL
		if (KEYS[228]) mods |= 0x0080; // SDL_KMOD_RCTRL
		if (KEYS[226]) mods |= 0x0100; // SDL_KMOD_LALT
		int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) mods, true);
		minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), down ? 1 : 0, new KeyEvent(scancode, keycode, mods));
	}
}
