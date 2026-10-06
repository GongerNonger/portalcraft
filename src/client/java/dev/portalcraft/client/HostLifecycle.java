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

	/** Every client tick. */
	static void tick(Minecraft minecraft, boolean linked) {
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
