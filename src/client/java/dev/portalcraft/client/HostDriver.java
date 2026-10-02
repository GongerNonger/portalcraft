package dev.portalcraft.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.mojang.blaze3d.platform.InputConstants;
import dev.portalcraft.PortalCraft;
import dev.portalcraft.client.world.WorldExporter;
import dev.portalcraft.client.world.WorldLink;
import dev.portalcraft.host.BspMap;
import dev.portalcraft.host.HostCollision;
import dev.portalcraft.host.HostLink;
import dev.portalcraft.host.LiveEntities;
import dev.portalcraft.host.Proto;
import dev.portalcraft.host.Units;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
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
	/** HostState.mouse bit i is Minecraft mouse button MOUSE_BUTTONS[i]. */
	private static final int[] MOUSE_BUTTONS = {InputConstants.MOUSE_BUTTON_LEFT, InputConstants.MOUSE_BUTTON_RIGHT, InputConstants.MOUSE_BUTTON_MIDDLE};
	/** The host's mouse buttons Minecraft currently has down, as HostState.mouse bits. */
	private static int buttons;
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
				OverlayLink.close();
				WorldLink.close();
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
		List<LiveEntities.Moved> moved = LiveEntities.update(HostLink.entities());
		matchHostWindowSize(minecraft);

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
			minecraft.getTutorial().setStep(TutorialSteps.NONE);
			giveGun(minecraft, player);
			player.refreshDimensions(); // host-sized hull (see AvatarDimensionsMixin)
			LOG.info("PortalCraft: player hull {} x {} blocks", player.getBbWidth(), player.getBbHeight());
		}

		carry(player, moved);
		String devGive = HostLink.takeDevGive();
		if (devGive != null) {
			giveDev(minecraft, player, devGive);
		}
		Vec3 devGoto = HostLink.takeDevGoto();
		if (devGoto != null) {
			teleport(minecraft, player, Units.toMc(devGoto), Vec3.ZERO);
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
			applyMouse(minecraft, player, s.mouse());
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

		// The placed blocks, for the host to draw in its own 3D pass.
		if (ready && WorldLink.open()) {
			WorldExporter.frame(minecraft);
		}
	}

	/**
	 * Standing on something the host moved (a lift, a moving panel, a button going down): move
	 * with it, the way the host would carry its own player.
	 */
	private static void carry(LocalPlayer player, List<LiveEntities.Moved> moved) {
		if (moved.isEmpty()) {
			return;
		}
		AABB feet = player.getBoundingBox();
		feet = new AABB(feet.minX, feet.minY - 0.2, feet.minZ, feet.maxX, feet.minY + 0.1, feet.maxZ);
		for (LiveEntities.Moved m : moved) {
			if (m.before().intersects(feet) && m.delta().lengthSqr() < 4.0) {
				player.setPos(player.position().add(m.delta()));
				return;
			}
		}
	}

	/** Dev: give a full stack of an item by id (tools/fake_mc.py --give minecraft:stone). */
	private static void giveDev(Minecraft minecraft, LocalPlayer player, String id) {
		var server = minecraft.getSingleplayerServer();
		var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(net.minecraft.resources.Identifier.tryParse(id));
		if (server == null || item.isEmpty()) {
			LOG.warn("PortalCraft: dev give: no item {}", id);
			return;
		}
		var uuid = player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp != null) {
				sp.getInventory().add(new ItemStack(item.get(), item.get().getDefaultMaxStackSize()));
				LOG.info("PortalCraft: dev give {}", id);
			}
		});
	}

	/** Steve starts with the portal gun; while he holds it, clicks fire the host's real portals. */
	private static void giveGun(Minecraft minecraft, LocalPlayer player) {
		var server = minecraft.getSingleplayerServer();
		if (server == null) {
			return;
		}
		var uuid = player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp != null && !sp.getInventory().contains(new ItemStack(PortalCraft.PORTAL_GUN))) {
				sp.getInventory().add(new ItemStack(PortalCraft.PORTAL_GUN));
			}
		});
	}

	/** Render the overlay at the host's resolution so the HUD is sharp and the right size. */
	private static void matchHostWindowSize(Minecraft minecraft) {
		int w = OverlayLink.hostWidth(), h = OverlayLink.hostHeight();
		var window = minecraft.getWindow();
		if (w <= 0 || h <= 0 || w > OverlayLink.MAX_W || h > OverlayLink.MAX_H || window.isExclusiveFullscreen()) {
			return;
		}
		if (window.getWidth() != w || window.getHeight() != h) {
			LOG.info("PortalCraft: matching the host's {}x{}", w, h);
			window.setWindowed(w, h);
		}
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
			if (Minecraft.getInstance().player != null) {
				Minecraft.getInstance().player.refreshDimensions(); // host-sized hull
			}
			LOG.info("PortalCraft: loaded {} ({} solid brushes) in {} ms", file, map.brushes.size(), (System.nanoTime() - t0) / 1_000_000);
		} catch (Exception e) {
			failedMap = name;
			HostCollision.clear();
			LOG.warn("PortalCraft: can't read {} ({}). Set -Dportalcraft.mapsDir to the game's maps folder.", file, e.toString());
		}
	}

	private static void teleport(Minecraft minecraft, LocalPlayer player, Vec3 pos, Vec3 velocity) {
		boolean far = player.position().distanceToSqr(pos) > 0.25;
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
		if (far) {
			LOG.info("PortalCraft: host moved the player to {} (velocity {})", pos, velocity);
		}
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

	/**
	 * Mouse clicks are Minecraft's (attack, use, pick block) unless Steve holds the portal gun: the
	 * host fires its own gun then, so Minecraft gets none and lets go of any it had down.
	 */
	private static void applyMouse(Minecraft minecraft, LocalPlayer player, int wanted) {
		if (player.getMainHandItem().is(PortalCraft.PORTAL_GUN)) {
			wanted = 0;
		} else if (minecraft.gui.screen() != null) {
			wanted &= buttons; // screens get no cursor from the host: let go, but don't click
		}
		for (int bit = 0; bit < MOUSE_BUTTONS.length; bit++) {
			int mask = 1 << bit;
			boolean down = (wanted & mask) != 0;
			if (down != ((buttons & mask) != 0)) {
				if (down) {
					readyToClick(minecraft);
				}
				buttons ^= mask;
				click(minecraft, MOUSE_BUTTONS[bit], down);
			}
		}
	}

	/**
	 * Vanilla's MouseHandler.onButton grabs an ungrabbed mouse on the first click, and grabMouse sets
	 * missTime to 10000, so that click's attack does nothing; a held attack also only keeps
	 * breaking while the mouse is grabbed. InputConstantsMixin leaves the OS cursor alone while
	 * linked, so grab Minecraft's side up front and drop the grab's cooldown. Vanilla's own
	 * 10-tick cooldown after a survival miss is kept.
	 */
	private static void readyToClick(Minecraft minecraft) {
		if (!minecraft.mouseHandler.isMouseGrabbed()) {
			minecraft.mouseHandler.grabMouse();
		}
		if (minecraft.missTime > 10) {
			minecraft.missTime = 0;
		}
	}

	private static void click(Minecraft minecraft, int button, boolean down) {
		minecraft.mouseHandler.onButton(minecraft.getWindow().handle(), new MouseButtonInfo(button, modifiers()), down ? 1 : 0);
	}

	private static void releaseAll(Minecraft minecraft) {
		for (int sc = 1; sc < KEYS.length; sc++) {
			if (KEYS[sc]) {
				KEYS[sc] = false;
				press(minecraft, sc, false);
			}
		}
		for (int bit = 0; bit < MOUSE_BUTTONS.length; bit++) {
			if ((buttons & (1 << bit)) != 0) {
				buttons &= ~(1 << bit);
				click(minecraft, MOUSE_BUTTONS[bit], false);
			}
		}
	}

	private static int modifiers() {
		int mods = 0;
		if (KEYS[225]) mods |= 0x0001; // SDL_KMOD_LSHIFT
		if (KEYS[229]) mods |= 0x0002; // SDL_KMOD_RSHIFT
		if (KEYS[224]) mods |= 0x0040; // SDL_KMOD_LCTRL
		if (KEYS[228]) mods |= 0x0080; // SDL_KMOD_RCTRL
		if (KEYS[226]) mods |= 0x0100; // SDL_KMOD_LALT
		return mods;
	}

	private static void press(Minecraft minecraft, int scancode, boolean down) {
		int mods = modifiers();
		int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) mods, true);
		minecraft.keyboardHandler.keyPress(minecraft.getWindow().handle(), down ? 1 : 0, new KeyEvent(scancode, keycode, mods));
	}
}
