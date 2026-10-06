package dev.portalcraft.host;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Portal's air physics for Steve, where Portal's puzzles need them: after he leaves a portal (until
 * he lands), and whenever he is falling. Minecraft's player falls under twice Portal's gravity and
 * loses 9% of its horizontal speed every tick in the air, which left momentum flings some 37%
 * short of the ledges Portal's chambers are built around (docs/PORTAL_MECHANICS.md). In Portal air:
 * Portal's gravity (sv_gravity 600 units/s^2), no air drag, its 3500 units/s speed cap, and air
 * control that can steer but not speed Steve up.
 *
 * Every descent is Portal's, from its highest point: a fall of any height then reaches a portal at
 * exactly the speed Portal's chambers were built for. (It used to start only three blocks into a
 * fall: by then Steve was a third faster than Portal's player, the acceleration halved in mid-air,
 * and he carried the extra into every fling.) The way up stays Minecraft's, so a jump is as high
 * and as quick off the ground as ever; it comes down a little slower.
 * Applied by LivingEntityAirMixin to the local player only (whose movement the client runs).
 */
public final class PortalAir {
	/** Portal's sv_gravity, 600 units/s^2, in blocks/tick^2. */
	public static final double GRAVITY = 600.0 / Units.PER_BLOCK / 400.0;
	/** Portal's sv_maxvelocity, 3500 units/s, in blocks/tick. */
	public static final double MAX_SPEED = 3500.0 / Units.PER_BLOCK / 20.0;

	/** Air control may bring Steve up to this from a near standstill: 240 units/s, in blocks/tick. */
	static final double STANDSTILL_CONTROL = 240.0 / Units.VELOCITY;

	private static volatile boolean flung;
	private static int flungTicks, flingLogs;

	private PortalAir() {
	}

	/** Steve just came out of a portal (or was thrown): Portal air until he lands. */
	public static void startFling() {
		if (!flung && flingLogs++ < 10) {
			org.slf4j.LoggerFactory.getLogger("portalcraft").info("PortalCraft: Portal air (out of a portal with speed, until landing)");
		}
		flung = true;
		flungTicks = 0;
	}

	/** Once per client tick: a fling ends on the ground, in water or on a ladder (or after 30 s). */
	public static void tick(Player player) {
		if (flung && (player.onGround() || player.isInWater() || player.onClimbable() || player.isFallFlying() || ++flungTicks > 600)) {
			flung = false;
		}
	}

	public static boolean flung() {
		return flung;
	}

	/** True while `player`, the one this client moves, should fly by Portal's rules. */
	public static boolean active(Player player) {
		return HostCollision.active() && player.level().isClientSide() && !player.isInWater() && !player.onClimbable() && !player.isFallFlying()
			&& !player.getAbilities().flying && (flung || falling(player.onGround(), player.getDeltaMovement().y));
	}

	/** In the air and on the way down. */
	static boolean falling(boolean onGround, double velocityY) {
		return !onGround && velocityY < 0.0;
	}

	/** Funneling reaches for floor portals this far off to the side (blocks), and this far below. */
	private static final double FUNNEL_RADIUS = 60.0 / Units.PER_BLOCK, FUNNEL_DEPTH = 400.0 / Units.PER_BLOCK;

	/** Funneling closes the gap to the portal's middle no faster than in this many ticks. */
	private static final double FUNNEL_TICKS = 4.0;

	/**
	 * Portal's funneling (sv_player_funnel_into_portals): falling onto a floor portal, the player
	 * is steered into it, so a fall aimed roughly right goes through instead of clipping the rim.
	 * Once per client tick, before the move: blends Steve's horizontal velocity toward the one
	 * that lands him on the nearest linked floor portal below. Only while he isn't steering (as in
	 * Portal), and never faster than closing the gap in FUNNEL_TICKS: a jump across a floor portal,
	 * a moment from landing and 60 units off its middle, was thrown at it at 500 units/s and sailed
	 * over.
	 */
	public static void funnel(Player player, Proto.HostPortal[] portals) {
		Vec3 v = player.getDeltaMovement();
		if (!HostCollision.active() || portals == null || player.onGround() || v.y > -0.3 || player.isInWater() || player.getAbilities().flying || player.isFallFlying()
			|| player.xxa != 0.0F || player.zza != 0.0F) {
			return;
		}
		Vec3 at = player.position();
		Vec3 best = null;
		double bestDistance = FUNNEL_RADIUS;
		for (Proto.HostPortal p : portals) {
			if (p == null || !p.linked() || Units.angleVectors(p.angles())[0].z < 0.9) {
				continue; // only floor portals (facing up)
			}
			Vec3 c = Units.toMc(p.origin());
			double below = at.y - c.y;
			double side = Math.hypot(c.x - at.x, c.z - at.z);
			if (below > 0.0 && below < FUNNEL_DEPTH && side < bestDistance) {
				best = c;
				bestDistance = side;
			}
		}
		if (best == null) {
			return;
		}
		double ticks = Math.max(FUNNEL_TICKS, (at.y - best.y) / -v.y);
		double wantX = (best.x - at.x) / ticks, wantZ = (best.z - at.z) / ticks;
		player.setDeltaMovement(v.x + (wantX - v.x) * 0.5, v.y, v.z + (wantZ - v.z) * 0.5);
	}

	/**
	 * After an air step: no faster than Portal allows, and no horizontal speed gained from air
	 * control beyond what Steve had before the step (`before`, blocks/tick). Returns the velocity.
	 */
	public static Vec3 limit(Vec3 velocity, double horizontalBefore) {
		double h = Math.sqrt(velocity.x * velocity.x + velocity.z * velocity.z);
		double hMax = Math.max(horizontalBefore, STANDSTILL_CONTROL); // a little air control from a near standstill
		if (h > hMax && h > 1e-9) {
			velocity = new Vec3(velocity.x * hMax / h, velocity.y, velocity.z * hMax / h);
		}
		double speed = velocity.length();
		if (speed > MAX_SPEED) {
			velocity = velocity.scale(MAX_SPEED / speed);
		}
		return velocity;
	}
}
