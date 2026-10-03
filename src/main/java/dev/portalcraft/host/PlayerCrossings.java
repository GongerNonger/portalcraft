package dev.portalcraft.host;

import java.util.ArrayDeque;
import java.util.Iterator;

import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Steve through the host's portals, inside Minecraft's own physics step.
 *
 * Portal teleports its player once per server tick at most, and only once our playback of
 * Minecraft's steps reaches the portal, a tick or so behind. Minecraft steps 20 times a second: in
 * an infinite fall near top speed one step is longer than the whole loop between the two portals,
 * so waiting for Portal left Minecraft's player further below the floor portal every loop until he
 * fell out of the bottom of its hole. So Minecraft carries its own player through the moment his
 * centre crosses a linked portal inside the oval, as many times as the step needs, the way Portal
 * moves Chell: the centre goes through, the hull stays upright, the velocity turns with the portal.
 *
 * Portal still does its own teleport (it owns the camera, the view turn and the smoothing), so the
 * positions sent to it are "unfolded": as if Minecraft hadn't yet made the crossings Portal hasn't
 * matched. Playing those back, Portal's player crosses its portal where Steve did and Portal
 * teleports it; the plugin counts that as the match (HostState.crossMatched) and from then on the
 * crossing is no longer unfolded. All in host units; positions are the player's feet.
 */
public final class PlayerCrossings {
	/** A step carries through at most this many portals (a fall at top speed needs two). */
	private static final int MAX_PER_STEP = 4;
	/** Unmatched crossings kept; past this Portal has lost track and they're forgotten. */
	private static final int MAX_PENDING = 16;

	private record Crossing(int index, int portal, HostPortalTransit.Frame in, HostPortalTransit.Frame out, double halfHeight) {
		/** Undoes the carry (Portal hasn't made it yet): back out of `in`... from `out`. */
		Vec3 unfoldFeet(Vec3 feet) {
			Vec3 centre = feet.add(0.0, 0.0, halfHeight);
			return carryCentre(out, in, centre).subtract(0.0, 0.0, halfHeight);
		}

		Vec3 unfoldDir(Vec3 v) {
			return in.carry(out, v);
		}
	}

	private static final ArrayDeque<Crossing> PENDING = new ArrayDeque<>();
	private static int count;
	private static int matched;

	private PlayerCrossings() {
	}

	/** McState.crossCount: crossings made since the link (re)started. */
	public static int count() {
		return count;
	}

	/** McState.crossMatchedEcho: the match count the unfolded positions are relative to. */
	public static int matched() {
		return matched;
	}

	/** McState.crossPortal: the portal (0 blue, 1 orange) Steve went in through on crossing `index`. */
	public static int portalOf(int index) {
		for (Crossing c : PENDING) {
			if (c.index() == index) {
				return c.portal();
			}
		}
		return 0xFF;
	}

	/** A fresh link, a respawn, a new map: nothing pending, counting from zero (the host follows). */
	public static void reset() {
		PENDING.clear();
		count = 0;
		matched = 0;
	}

	/**
	 * The host placed Steve itself (a level start, a teleport): where it put him is where he is, so
	 * nothing is left to unfold. The host takes the echo as the new match count.
	 */
	public static void forgetPending() {
		PENDING.clear();
		matched = count;
	}

	/** HostState.crossMatched: Portal has made the crossings up to `m` itself. */
	public static void matched(int m) {
		if (m > count) {
			m = count; // from before our reset: the host hasn't caught up with it yet
		}
		if (m < matched) {
			return;
		}
		matched = m;
		PENDING.removeIf(c -> c.index() <= matched);
	}

	/** Result of a step that went through portals: where Steve is now, and how he's moving. */
	public record Carried(Vec3 feet, Vec3 previousFeet, Vec3 velocity, int crossings) {
	}

	/**
	 * Minecraft just moved Steve from `previousFeet` to `feet` (velocity `velocity`, all host units;
	 * `halfHeight` is half his hull). If his centre went in through a linked portal inside its oval,
	 * carries all three through, again for each further portal the rest of the step crosses. Null if
	 * no portal was crossed.
	 */
	public static @Nullable Carried step(Proto.HostPortal[] portals, Vec3 previousFeet, Vec3 feet, Vec3 velocity, double halfHeight) {
		if (portals.length < 2 || portals[0] == null || portals[1] == null || !portals[0].linked() || !portals[1].linked()) {
			return null;
		}
		HostPortalTransit.Frame[] frames = {new HostPortalTransit.Frame(portals[0]), new HostPortalTransit.Frame(portals[1])};
		Vec3 up = new Vec3(0.0, 0.0, halfHeight);
		Vec3 from = previousFeet.add(up), to = feet.add(up), prev = previousFeet.add(up);
		Vec3 v = velocity;
		int made = 0;
		int skip = -1; // the portal we just came out of: we're on its front, leaving it
		while (made < MAX_PER_STEP) {
			int hit = -1;
			double best = 2.0;
			for (int i = 0; i < 2; i++) {
				if (i == skip) {
					continue;
				}
				double d0 = frames[i].forward(from), d1 = frames[i].forward(to);
				if (d0 < 0.0 || d1 >= 0.0) {
					continue; // didn't go in through its front
				}
				double t = d0 / (d0 - d1);
				if (t < best && frames[i].inOval(from.add(to.subtract(from).scale(t)))) {
					best = t;
					hit = i;
				}
			}
			if (hit < 0) {
				break;
			}
			HostPortalTransit.Frame in = frames[hit], out = frames[1 - hit];
			Vec3 crossing = from.add(to.subtract(from).scale(best));
			from = carryCentre(in, out, crossing);
			to = carryCentre(in, out, to);
			prev = carryCentre(in, out, prev);
			v = out.carry(in, v);
			skip = 1 - hit;
			made++;
			count++;
			PENDING.addLast(new Crossing(count, hit, in, out, halfHeight));
			while (PENDING.size() > MAX_PENDING) {
				PENDING.removeFirst();
			}
		}
		if (made == 0) {
			return null;
		}
		return new Carried(to.subtract(up), prev.subtract(up), v, made);
	}

	/** Feet as the host should see them: back through the crossings it hasn't matched yet. */
	public static Vec3 unfold(Vec3 feet) {
		for (Iterator<Crossing> it = PENDING.descendingIterator(); it.hasNext();) {
			feet = it.next().unfoldFeet(feet);
		}
		return feet;
	}

	/** A velocity as the host should see it. */
	public static Vec3 unfoldDir(Vec3 v) {
		for (Iterator<Crossing> it = PENDING.descendingIterator(); it.hasNext();) {
			v = it.next().unfoldDir(v);
		}
		return v;
	}

	/** A point behind `in` comes out the same distance in front of `out`, mirrored across, up kept. */
	static Vec3 carryCentre(HostPortalTransit.Frame in, HostPortalTransit.Frame out, Vec3 point) {
		Vec3 local = in.local(point);
		return out.world(new Vec3(-local.x, local.y, -local.z));
	}
}
