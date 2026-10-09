package dev.portalcraft.client;

import java.util.Optional;

import dev.portalcraft.host.Instance;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;
import org.lwjgl.sdl.SDLVideo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft started by Portal (the plugin launches it with PORTALCRAFT_STARTED_BY_HOST=1, as
 * SkyCraft's plugin starts its Minecraft with Skyrim): no window and no music, and it goes again
 * when that Portal closes, saved and shut down the normal way. A hidden Minecraft with no Portal
 * to link to quits after 10 minutes, so it can't block the next start. Started any other way (gradle
 * runClient for development) none of this applies. -Dportalcraft.showWindow=true keeps the window.
 */
final class HostLifecycle {
	private static final Logger LOG = LoggerFactory.getLogger("portalcraft");
	static final boolean STARTED_BY_HOST = Boolean.getBoolean("portalcraft.startedByHost")
		|| "1".equals(System.getenv("PORTALCRAFT_STARTED_BY_HOST"));
	private static final boolean SHOW_WINDOW = Boolean.getBoolean("portalcraft.showWindow");
	private static final long NEVER_LINKED_QUIT_MS = 10 * 60 * 1000;
	private static final long HOST_GONE_QUIT_MS = 5000;
	private static final long STARTED_AT = System.currentTimeMillis();

	private static boolean hidden, everLinked, quitting;
	private static long hostPid, hostGoneSince, nextCheck, lastLinkedAt;
	/** Portal's plugin speaks ~66 times a second in a level (its menu is one too); this long quiet, it's gone. */
	private static final long PORTAL_QUIET_MS = 15000;

	private HostLifecycle() {
	}

	/** As soon as the client is up: a Minecraft Portal started stays out of sight from the start. */
	static void started(Minecraft minecraft) {
		if (!STARTED_BY_HOST) {
			return;
		}
		LOG.info("PortalCraft: started by Portal{}", SHOW_WINDOW ? " (window kept: portalcraft.showWindow)" : "; hiding the window");
		hide(minecraft);
		minecraft.options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
		minecraft.options.pauseOnLostFocus = false;
		minecraft.getMusicManager().stopPlaying();
	}

	/**
	 * A friend's world to play in instead of one's own: PORTALCRAFT_JOIN (or -Dportalcraft.join) is
	 * its address, "host:port". Whenever this Minecraft is in no world (at its title screen: just
	 * started, or dropped), it connects there, every few seconds until it is in. (Minecraft's own
	 * --quickPlayMultiplayer did nothing here: the client sat at its title screen.)
	 */
	private static final String JOIN = System.getProperty("portalcraft.join", System.getenv("PORTALCRAFT_JOIN"));
	private static long nextJoin;
	private static int joinLogs;

	private static void joinFriend(Minecraft minecraft) {
		if (JOIN == null || JOIN.isBlank() || minecraft.level != null || minecraft.gui.overlay() != null) {
			return;
		}
		long now = System.currentTimeMillis();
		// From whatever screen it is at, not only the title screen: a new Minecraft folder starts on
		// its first-run screens, and a dropped connection leaves the "disconnected" one up.
		// (Not from the screens a connection under way goes through, though: tried again from those
		// eight seconds in, each attempt cut the one before it off, and the host saw "lost connection".)
		var screen = minecraft.gui.screen();
		boolean idle = screen == null || screen instanceof net.minecraft.client.gui.screens.TitleScreen
			|| screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen || screen.getClass().getSimpleName().contains("Onboarding");
		if (now < nextJoin || !idle || minecraft.getConnection() != null) {
			return;
		}
		// A long wait before the next try: logging in takes longer than a few seconds, the screen it
		// shows meanwhile is closed by the host driver (so the screen can't say an attempt is still
		// going), and a second attempt drops the first.
		nextJoin = now + 45000;
		if (screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen && joinLogs < 12) {
			try { // why the last try ended: the screen knows, and nothing else logs it
				var f = net.minecraft.client.gui.screens.DisconnectedScreen.class.getDeclaredField("details");
				f.setAccessible(true);
				LOG.info("PortalCraft: the last try to join ended: {}", ((net.minecraft.network.DisconnectionDetails) f.get(screen)).reason().getString());
			} catch (ReflectiveOperationException | RuntimeException e) {
				LOG.info("PortalCraft: the last try to join ended (reason unreadable: {})", e.toString());
			}
		}
		if (joinLogs++ < 5) {
			LOG.info("PortalCraft: not in a world (screen: {})", minecraft.gui.screen() == null ? "none" : minecraft.gui.screen().getClass().getSimpleName());
		}
		String address = JOIN.trim();
		if (!net.minecraft.client.multiplayer.resolver.ServerAddress.isValidAddress(address)) {
			LOG.warn("PortalCraft: PORTALCRAFT_JOIN is not an address: {}", address);
			return;
		}
		LOG.info("PortalCraft: joining the world at {}", address);
		var data = new net.minecraft.client.multiplayer.ServerData("PortalCraft", address, net.minecraft.client.multiplayer.ServerData.Type.OTHER);
		net.minecraft.client.gui.screens.ConnectScreen.startConnecting(new net.minecraft.client.gui.screens.TitleScreen(), minecraft,
			net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(address), data, false, null);
	}

	/** The map the world's host is in (HostMapPayload), in a friend's world; null in one's own. */
	private static volatile String hostMap;
	private static long nextFollow;

	static void hostIsIn(String map) {
		hostMap = map;
	}

	/**
	 * In a friend's world: our own Portal is sent to the map theirs is in ("PCG1" + its name; the
	 * plugin loads it). Asked again every twenty seconds for as long as the two differ: a level
	 * takes a while to load, and one that isn't installed here never will.
	 */
	private static void followHost(Minecraft minecraft) {
		String want = hostMap;
		if (want == null || minecraft.getSingleplayerServer() != null || minecraft.level == null) {
			return;
		}
		var own = dev.portalcraft.host.HostLink.current();
		if (own == null || (own.inGame() && want.equalsIgnoreCase(own.map()))) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now < nextFollow || !want.matches("[A-Za-z0-9_]{1,60}")) {
			return;
		}
		nextFollow = now + 20000;
		LOG.info("PortalCraft: the host is in {}; ours is {}: asking our Portal to load it", want, own.inGame() ? "in " + own.map() : "at its menu");
		byte[] name = want.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		var b = java.nio.ByteBuffer.allocate(4 + name.length);
		b.put((byte) 'P').put((byte) 'C').put((byte) 'G').put((byte) '1').put(name);
		dev.portalcraft.host.HostLink.send(b.flip());
	}

	/** Every client tick. */
	static void tick(Minecraft minecraft, boolean linked) {
		joinFriend(minecraft);
		followHost(minecraft);
		if (!STARTED_BY_HOST || quitting) {
			return;
		}
		hide(minecraft);
		long now = System.currentTimeMillis();
		if (linked) {
			everLinked = true;
			lastLinkedAt = now;
		}
		if (now < nextCheck) {
			return;
		}
		nextCheck = now + 1000;
		if (hostPid == 0 || !ProcessHandle.of(hostPid).map(ProcessHandle::isAlive).orElse(false)) {
			hostPid = findPortal().orElse(0L);
		}
		// Portal is there if its hl2.exe is, or if it spoke lately: an hl2.exe run as administrator
		// (Minecraft not) doesn't show its path to us, so the process alone can't be trusted to see it.
		boolean portalThere = hostPid != 0 || now - lastLinkedAt < PORTAL_QUIET_MS;
		if (portalThere) {
			hostGoneSince = 0;
		} else if (!everLinked) {
			// Portal isn't even running (it crashed, or was closed before a level loaded).
			if (now - STARTED_AT > NEVER_LINKED_QUIT_MS) {
				LOG.warn("PortalCraft: started by Portal, but no Portal has linked in {} minutes; quitting", NEVER_LINKED_QUIT_MS / 60000);
				quit(minecraft);
			}
		} else {
			if (hostGoneSince == 0) {
				hostGoneSince = now;
			} else if (now - hostGoneSince > HOST_GONE_QUIT_MS) {
				LOG.info("PortalCraft: Portal has closed; saving and quitting");
				quit(minecraft);
			}
		}
	}

	private static void hide(Minecraft minecraft) {
		if (!SHOW_WINDOW && !hidden) {
			hidden = true;
			SDLVideo.SDL_HideWindow(minecraft.getWindow().handle());
		}
	}

	private static void quit(Minecraft minecraft) {
		quitting = true;
		minecraft.stop();
	}

	/** Portal's hl2.exe, if it's running. */
	private static Optional<Long> findPortal() {
		if (Instance.NUMBER > 0) {
			// One of two pairs: only the hl2.exe that started us counts, never the other pair's.
			return ProcessHandle.of(Instance.hostPid()).filter(ProcessHandle::isAlive).map(ProcessHandle::pid);
		}
		return ProcessHandle.allProcesses()
			.filter(p -> p.info().command().map(c -> c.toLowerCase(java.util.Locale.ROOT).endsWith("\\hl2.exe")).orElse(false))
			.map(ProcessHandle::pid)
			.findFirst();
	}
}
