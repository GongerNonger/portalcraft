package dev.portalcraft;

/** The two ends of a portal pair. Left click fires PRIMARY, right click fires SECONDARY. */
public enum PortalColor {
	PRIMARY(0x2A8CFF),
	SECONDARY(0xFF8A1E);

	public final int rgb;

	PortalColor(int rgb) {
		this.rgb = rgb;
	}

	public PortalColor other() {
		return this == PRIMARY ? SECONDARY : PRIMARY;
	}

	public static PortalColor byId(int id) {
		return id == 1 ? SECONDARY : PRIMARY;
	}
}
