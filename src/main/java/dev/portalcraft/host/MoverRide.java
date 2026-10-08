package dev.portalcraft.host;

import net.minecraft.world.phys.Vec3;

/**
 * Steve on something of the host's that moves: a lift, a light-rail platform. Host units throughout.
 *
 * INVARIANT: while he is on a mover, Steve's place is told and kept relative to that mover, each
 * side using its own knowledge of where the mover is. Minecraft has the mover at {@link #origin()}
 * (its copy, from the entity stream: ~16 times a second, a tick or more stale) and Steve at P; it
 * sends both, and the host writes its player at {@link #place}: where the mover is this tick, plus
 * P - origin. Steve stands on that same stale copy and is moved by exactly what the copy moves
 * ({@link #follow}), so P - origin doesn't depend on the lag at all:
 * <ul>
 * <li>standing still it is constant, and the host's player doesn't move on the mover;</li>
 * <li>in a jump it is Minecraft's own jump and nothing else, because he is carried with the copy
 * until he lands (as Source keeps the ground entity's velocity as base velocity from take-off): he
 * clears a rising lift's floor by what he clears the ground by and comes down on it, and off a
 * moving cart he keeps the cart's speed;</li>
 * <li>where the frames meet (onto a mover, or off one) nothing is converted: one step is told in
 * one frame, the next in the other, and the host plays the step between them.</li>
 * </ul>
 * Before this, Portal owned his height on a lift (no jumping: left to Minecraft for a jump, the copy
 * came up 8 or 16 units into him between two ticks and he fell through it), and on a sideways
 * platform his world position was played back as it was, a lag behind the platform and uneven.
 */
public final class MoverRide {
	/** A copy that went further than this in one update wasn't moving: it was put somewhere else (units). */
	public static final double MAX_STEP = 64.0;
	/** How many ticks of carrying {@link #velocity} looks back over: several updates of the stream. */
	private static final int RECENT = 8;

	private int index;
	private Vec3 origin = Vec3.ZERO;
	private final Vec3[] recent = new Vec3[RECENT];
	private int ticks;

	/** True while Steve is on a mover (standing on it, or in the air since he last did). */
	public boolean riding() {
		return index != 0;
	}

	/** The host entity he is on (HostEntity index), or 0. */
	public int index() {
		return index;
	}

	/** Where Minecraft has that entity: the frame his place is told in. */
	public Vec3 origin() {
		return origin;
	}

	/** Steve now stands on host entity `index`, which Minecraft has at `at`. */
	public void attach(int index, Vec3 at) {
		if (index != this.index) {
			forget();
		}
		this.index = index;
		this.origin = at;
	}

	public void detach() {
		index = 0;
		forget();
	}

	/**
	 * Once a tick while riding: Minecraft's copy of the mover is now at `now`. Returns how far it
	 * went, which is how far Steve goes with it (the whole of it, on the ground or in the air: that
	 * is what keeps his place on the mover his own doing). A copy that jumped ({@link #MAX_STEP})
	 * carries nobody: he is let go where he is.
	 */
	public Vec3 follow(Vec3 now) {
		Vec3 by = now.subtract(origin);
		origin = now;
		if (index == 0) {
			return Vec3.ZERO;
		}
		if (by.lengthSqr() > MAX_STEP * MAX_STEP) {
			detach();
			return Vec3.ZERO;
		}
		recent[ticks++ % RECENT] = by;
		return by;
	}

	/**
	 * How fast the mover has been carrying him (units a tick), over the last few ticks: the copy
	 * moves in steps, none in one tick and two in another. For leaving the mover's frame in mid-air,
	 * when its speed becomes his own (Source: base velocity).
	 */
	public Vec3 velocity() {
		Vec3 sum = Vec3.ZERO;
		int n = Math.min(ticks, RECENT);
		for (int i = 0; i < n; i++) {
			sum = sum.add(recent[i]);
		}
		return n == 0 ? Vec3.ZERO : sum.scale(1.0 / n);
	}

	/** Steve's place on the mover: `position` less where Minecraft has the mover. */
	public Vec3 offset(Vec3 position) {
		return position.subtract(origin);
	}

	/**
	 * What the host does with a place told on a mover (plugin.cpp inFrame): `told` was Steve's place
	 * when Minecraft had the mover at `then`; the mover is at `now`.
	 */
	public static Vec3 place(Vec3 now, Vec3 told, Vec3 then) {
		return now.add(told.subtract(then));
	}

	private void forget() {
		ticks = 0;
		java.util.Arrays.fill(recent, null);
	}
}
