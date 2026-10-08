package dev.portalcraft.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/**
 * The invariant of MoverRide, played through: a mover going at its own speed in the host, Minecraft's
 * copy of it late and arriving in uneven steps, Steve carried with the copy, and the host placing
 * its player from what Minecraft tells it. What the host's player does on the mover has to be what
 * Steve did on his copy, whatever the lag.
 */
class MoverRideTest {
	private static final double HOST_TICK = 0.015, MC_TICK = 0.05;

	/** A lift or a cart: where it really is at time t (units). */
	private static Vec3 real(Vec3 speed, double t) {
		return new Vec3(100, -200, 300).add(speed.scale(t));
	}

	/** Minecraft's copy at time t: the real one `lag` seconds ago, as of the last of its 16 updates a second. */
	private static Vec3 copy(Vec3 speed, double t, double lag) {
		return real(speed, Math.floor((t - lag) * 16.0) / 16.0);
	}

	/** One Minecraft tick of Steve's own doing on the mover (units), by tick number. */
	private interface Doing {
		Vec3 step(int tick);
	}

	/**
	 * Twenty-five Minecraft ticks on a mover; at every host tick the host's player is placed from the
	 * newest step told. Returns the largest distance between the host player's place on the real
	 * mover and Steve's place on his copy at the step it was placed from.
	 */
	private static double ride(Vec3 speed, double lag, Doing doing) {
		MoverRide ride = new MoverRide();
		Vec3 steve = copy(speed, 0.0, lag).add(10, 20, 0); // standing on it, 10 and 20 from its origin
		ride.attach(7, copy(speed, 0.0, lag));
		Vec3 told = steve, toldMover = ride.origin(), own = new Vec3(10, 20, 0);
		double worst = 0.0;
		int tick = 0;
		for (double t = 0.0; t < 25 * MC_TICK; t += HOST_TICK) {
			while ((tick + 1) * MC_TICK <= t) {
				tick++;
				steve = steve.add(ride.follow(copy(speed, tick * MC_TICK, lag))); // the start of the tick: carried
				Vec3 step = doing.step(tick);
				steve = steve.add(step); // the physics step: his own doing
				own = own.add(step);
				told = steve;
				toldMover = ride.origin();
			}
			Vec3 placed = MoverRide.place(real(speed, t), told, toldMover);
			worst = Math.max(worst, placed.subtract(real(speed, t)).distanceTo(own));
		}
		return worst;
	}

	@Test
	void standingStillTheHostsPlayerDoesNotMoveOnTheMover() {
		for (double lag : new double[] {0.0, 0.04, 0.11, 0.3}) {
			assertEquals(0.0, ride(new Vec3(0, 0, 128), lag, tick -> Vec3.ZERO), 1e-9, "a lift at 128, copy " + lag + " s late");
			assertEquals(0.0, ride(new Vec3(-50, 12, 0), lag, tick -> Vec3.ZERO), 1e-9, "a cart at 50, copy " + lag + " s late");
		}
	}

	@Test
	void aJumpOnARisingLiftIsTheSameJumpAsOnTheGround() {
		// Up 12, 9, 6, 3, then down the same: 30 units over the floor and back onto it.
		double[] jump = {12, 9, 6, 3, 0, -3, -6, -9, -12};
		Doing jumping = tick -> tick >= 5 && tick < 5 + jump.length ? new Vec3(0, 0, jump[tick - 5]) : Vec3.ZERO;
		for (double lag : new double[] {0.0, 0.07, 0.25}) {
			assertEquals(0.0, ride(new Vec3(0, 0, 128), lag, jumping), 1e-9, "copy " + lag + " s late");
			assertEquals(0.0, ride(new Vec3(0, 0, -128), lag, jumping), 1e-9, "going down, copy " + lag + " s late");
		}
	}

	@Test
	void walkingAndJumpingOnACartKeepsItsSpeed() {
		Doing walkAndHop = tick -> new Vec3(2.5, 0, tick == 8 ? 10 : tick == 9 ? -10 : 0);
		assertEquals(0.0, ride(new Vec3(30, -40, 0), 0.12, walkAndHop), 1e-9);
	}

	@Test
	void theCopyMovesInStepsButHeIsCarriedByAllOfIt() {
		MoverRide ride = new MoverRide();
		Vec3 speed = new Vec3(0, 0, 128);
		ride.attach(7, copy(speed, 0.0, 0.1));
		Vec3 carried = Vec3.ZERO;
		boolean still = false;
		for (int tick = 1; tick <= 40; tick++) {
			Vec3 by = ride.follow(copy(speed, tick * MC_TICK, 0.1));
			still |= by.z == 0.0;
			carried = carried.add(by);
		}
		assertTrue(still, "a tick with no update");
		assertEquals(copy(speed, 40 * MC_TICK, 0.1).z - copy(speed, 0.0, 0.1).z, carried.z, 1e-9);
		// ... and its speed over the last ticks, for leaving it in mid-air: about 6.4 units a tick.
		assertEquals(6.4, ride.velocity().z, 1.7);
	}

	@Test
	void aCopyThatJumpsCarriesNobody() {
		MoverRide ride = new MoverRide();
		ride.attach(7, new Vec3(0, 0, 0));
		assertEquals(8.0, ride.follow(new Vec3(0, 0, 8)).z, 1e-9);
		assertEquals(Vec3.ZERO, ride.follow(new Vec3(0, 0, 8 + MoverRide.MAX_STEP + 1)));
		assertFalse(ride.riding());
		assertEquals(0, ride.index());
	}

	@Test
	void anotherMoverStartsItsOwnSpeed() {
		MoverRide ride = new MoverRide();
		ride.attach(7, Vec3.ZERO);
		ride.follow(new Vec3(4, 0, 0));
		assertEquals(4.0, ride.velocity().x, 1e-9);
		ride.attach(7, new Vec3(4, 0, 0)); // still on it: nothing forgotten
		assertEquals(4.0, ride.velocity().x, 1e-9);
		ride.attach(9, new Vec3(50, 0, 0));
		assertEquals(Vec3.ZERO, ride.velocity());
		assertEquals(new Vec3(-40, 2, 0), ride.offset(new Vec3(10, 2, 0)));
		ride.detach();
		assertEquals(Vec3.ZERO, ride.follow(new Vec3(60, 0, 0))); // on nothing: carried by nothing
	}
}
