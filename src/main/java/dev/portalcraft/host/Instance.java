package dev.portalcraft.host;

/**
 * Which PortalCraft pair this Minecraft is half of (host/portal/src/instance.h). Normally there is
 * one, instance 0, and everything here is the protocol's own constant. A Portal started with
 * {@code -pcinstance N} starts its Minecraft with PORTALCRAFT_INSTANCE=N (or run one yourself with
 * -Dportalcraft.instance=N): its link ports are 10*N higher and the host's shared memory carries a
 * _N suffix, so two pairs can run on one PC without hearing each other.
 */
public final class Instance {
	private static final int MAX = 9;
	public static final int NUMBER = parse(System.getProperty("portalcraft.instance", System.getenv("PORTALCRAFT_INSTANCE")));
	public static final int HOST_PORT = Proto.HOST_PORT + 10 * NUMBER;
	public static final int MC_PORT = Proto.MC_PORT + 10 * NUMBER;

	private Instance() {
	}

	/** "Local\\PortalCraft_World_v1" as it is, or with _2 after it for instance 2. */
	public static String named(String base) {
		return NUMBER > 0 ? base + "_" + NUMBER : base;
	}

	/**
	 * The hl2.exe that started this Minecraft (PORTALCRAFT_HOST_PID, set with the instance), or 0.
	 * With two Portals running, "is some hl2.exe alive" no longer says whether ours is.
	 */
	public static long hostPid() {
		try {
			return NUMBER > 0 ? Long.parseLong(System.getenv("PORTALCRAFT_HOST_PID")) : 0;
		} catch (NumberFormatException e) {
			return 0; // unset (started by hand): only the link says whether Portal is there
		}
	}

	static int parse(String text) {
		try {
			int n = text == null ? 0 : Integer.parseInt(text.trim());
			return n > 0 && n <= MAX ? n : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}
}
