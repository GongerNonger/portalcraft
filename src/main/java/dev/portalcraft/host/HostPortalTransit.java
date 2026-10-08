package dev.portalcraft.host;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import dev.portalcraft.PortalEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Minecraft's things go through Portal's portals: items, TNT, mobs, falling blocks, arrows,
 * snowballs. Steve goes through natively (Portal teleports its player); everything else would only
 * fall into the hole HostCollision cuts behind a linked portal. Each server tick, whatever crossed a
 * portal's plane inside its opening this tick comes out of the other portal, its position, momentum
 * and facing carried the way Portal carries its own (in along -forward, out along the exit's
 * +forward, up kept, right flipped: a rotation, not a mirror).
 *
 * Fast projectiles cross the plane and hit the hole's back wall in the same tick (an arrow flies 3
 * blocks a tick, the hole is 1.8 deep): their hit is caught first (ProjectileTransitMixin), kept
 * with its speed, and sent through at the end of the tick. Whatever else the back wall stopped
 * gets its speed back from how far it moved.
 */
public final class HostPortalTransit {
	/**
	 * Ticks before the same thing can go through again. One: it comes out moving away from the exit, so
	 * it can't turn straight back in, and at ten (half a second) an arrow dropped into a floor portal
	 * under a ceiling one was past the floor portal again before it was allowed through, and fell out
	 * of the bottom of the hole.
	 */
	private static final int COOLDOWN_TICKS = 1;
	/** Searched this far around a portal (blocks): the fastest thing moves about 4 a tick. */
	private static final double REACH = 5.0;

	/** A projectile whose hit landed in a portal's hole: through `portal` from `point` (host units). */
	private record Pending(int portal, Vec3 point, Vec3 velocity) {
	}

	private static final Map<Entity, Pending> PENDING = new WeakHashMap<>();
	private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("portalcraft");
	private static int logged;

	private HostPortalTransit() {
	}

	/**
	 * A projectile is about to hit something (server thread). If it's the inside of a linked portal's
	 * hole, it goes through instead: returns true, and the hit is skipped.
	 */
	public static boolean projectileHit(Projectile projectile, HitResult hit) {
		if (!(hit instanceof BlockHitResult) || hit.getType() != HitResult.Type.BLOCK || projectile.level().isClientSide()
			|| projectile.level().dimension() != Level.OVERWORLD || !HostCollision.active()) {
			return false;
		}
		Proto.HostPortal[] portals = linkedPair();
		if (portals == null) {
			return false;
		}
		Vec3 point = Units.toSrc(hit.getLocation());
		for (int i = 0; i < 2; i++) {
			Frame f = new Frame(portals[i]);
			double depth = f.forward(point);
			if (depth <= 2.0 && depth >= -HostCollision.holeDepth(portals[i]) - 2.0 && f.inOpening(point)) {
				synchronized (PENDING) {
					PENDING.put(projectile, new Pending(i, point, projectile.getDeltaMovement()));
				}
				return true;
			}
		}
		return false;
	}

	/** END_LEVEL_TICK, server thread. */
	public static void tick(ServerLevel level) {
		if (level.dimension() != Level.OVERWORLD || !HostCollision.active()) {
			return;
		}
		Proto.HostPortal[] portals = linkedPair();
		if (portals == null) {
			synchronized (PENDING) {
				PENDING.clear();
			}
			return;
		}
		Map<Entity, Pending> pending;
		synchronized (PENDING) {
			pending = Map.copyOf(PENDING);
			PENDING.clear();
		}
		pending.forEach((e, p) -> {
			if (e.isAlive() && e.level() == level) {
				transit(level, e, new Frame(portals[p.portal()]), new Frame(portals[1 - p.portal()]), p.point(), p.velocity());
			}
		});
		for (int i = 0; i < 2; i++) {
			Frame in = new Frame(portals[i]), out = new Frame(portals[1 - i]);
			Vec3 o = Units.toMc(portals[i].origin());
			List<Entity> near = level.getEntities((Entity) null, new AABB(o, o).inflate(REACH),
				e -> !(e instanceof Player) && !(e instanceof PortalEntity) && e.isAlive() && !e.isPassenger() && !e.isVehicle() && !e.isOnPortalCooldown());
			for (Entity e : near) {
				Vec3 moved = e.position().subtract(e.xo, e.yo, e.zo);
				Vec3 now = Units.toSrc(e.getBoundingBox().getCenter());
				Vec3 before = Units.toSrc(e.getBoundingBox().getCenter().subtract(moved));
				double d0 = in.forward(before), d1 = in.forward(now);
				if (d0 < 0.0 || d1 >= 0.0) {
					continue; // didn't go in through the front this tick
				}
				Vec3 crossing = before.add(now.subtract(before).scale(d0 / (d0 - d1)));
				if (!in.inOpening(crossing)) {
					continue;
				}
				// The hole's back wall may have stopped it: then how far it went is its speed.
				Vec3 velocity = e.getDeltaMovement();
				if (moved.lengthSqr() > velocity.lengthSqr()) {
					velocity = moved;
				}
				transit(level, e, in, out, now, velocity);
			}
		}
	}

	/** Sends `e` out of `out`: `point` (host units) is where it is relative to `in`, `velocity` in blocks/tick. */
	private static void transit(ServerLevel level, Entity e, Frame in, Frame out, Vec3 point, Vec3 velocity) {
		Vec3 local = in.local(point);
		// In front of the exit by at least half the entity, so it doesn't land in the exit's wall.
		double clear = Math.max(-local.z, Math.max(e.getBbWidth(), e.getBbHeight()) * 0.5 * Units.PER_BLOCK + 2.0);
		Vec3 exitCentre = out.world(new Vec3(-local.x, local.y, clear));
		Vec3 centreToFeet = e.position().subtract(e.getBoundingBox().getCenter());
		Vec3 target = Units.toMc(exitCentre).add(centreToFeet);

		Vec3 v = out.carry(in, Units.velocityToSrc(velocity));
		// Up out of a floor portal at no less than Portal sends its own things: 50 units a second, 225
		// floor to floor. Slower, whatever was dropped into one portal crept over the rim of the other
		// and fell straight back in.
		if (out.forward.z > 0.7071) {
			double least = in.forward.z > 0.7071 ? 225.0 : 50.0;
			if (v.z < least) {
				v = new Vec3(v.x, v.y, least);
			}
		}
		Vec3 newVelocity = Units.velocityToMc(v);
		if (newVelocity.length() > PortalAir.MAX_SPEED) {
			newVelocity = newVelocity.normalize().scale(PortalAir.MAX_SPEED);
		}
		Vec3 look = Units.velocityToMc(out.carry(in, Units.velocityToSrc(e.getViewVector(1.0F))));
		float yaw = (float) (Mth.atan2(-look.x, look.z) * Mth.RAD_TO_DEG);
		float pitch = (float) (Math.asin(Mth.clamp(-look.normalize().y, -1.0, 1.0)) * Mth.RAD_TO_DEG);

		if (logged++ < 10) {
			LOG.info("PortalCraft: {} went through a host portal: out at {} with velocity {}", e.getType().getDescriptionId(), target, newVelocity);
		}
		e.setPortalCooldown(COOLDOWN_TICKS);
		e.teleport(new TeleportTransition(level, target, newVelocity, yaw, pitch, TeleportTransition.DO_NOTHING));
		e.setDeltaMovement(newVelocity);
		e.needsSync = true; // tell clients the new velocity now
		e.resetFallDistance();
	}

	/** Where a point just behind `in` comes out in front of `out` (host units; for tests). */
	static Vec3 carryPoint(Proto.HostPortal in, Proto.HostPortal out, Vec3 point) {
		Vec3 local = new Frame(in).local(point);
		return new Frame(out).world(new Vec3(-local.x, local.y, -local.z));
	}

	/** A direction through `in` and out of `out` (for tests). */
	public static Vec3 carryDirection(Proto.HostPortal in, Proto.HostPortal out, Vec3 v) {
		return new Frame(out).carry(new Frame(in), v);
	}

	/** Blue and orange, both placed and linked, or null. */
	private static Proto.HostPortal @Nullable [] linkedPair() {
		Proto.HostPortal[] portals = HostCollision.portals();
		if (portals.length < 2 || portals[0] == null || portals[1] == null || !portals[0].linked() || !portals[1].linked()) {
			return null;
		}
		return portals;
	}

	/** A portal's frame in host units: forward (out of the surface), right, up (the oval's long axis). */
	static final class Frame {
		final Vec3 origin, forward, right, up;

		Frame(Proto.HostPortal p) {
			Vec3[] axes = Units.angleVectors(p.angles());
			this.origin = p.origin();
			this.forward = axes[0];
			this.right = axes[1];
			this.up = axes[2];
		}

		double forward(Vec3 point) {
			return point.subtract(this.origin).dot(this.forward);
		}

		/**
		 * Whether a point on the portal's plane is in its opening: the 64 x 108 rectangle, not the
		 * oval drawn in it. Portal's portals are rectangles to everything but the eye: what it
		 * teleports is whatever overlaps the rectangle behind the plane (the hole shape built in
		 * CPortalSimulator::MoveTo, PortalSimulation.cpp, from PORTAL_HALF_WIDTH and _HEIGHT scaled
		 * by 0.98), and the hole it opens in the wall is the rectangle too, as is the one
		 * HostCollision cuts for Minecraft. With the oval here, whatever went in through a corner
		 * (an item dropped by the rim, an arrow shot low) was in the hole but never came out of
		 * the other portal. The whole rectangle rather than Portal's 0.98 of it: that test is on
		 * the thing's box, this one on its middle, and a box whose middle is inside the whole
		 * rectangle overlaps the smaller one unless it is under 1.3 units wide.
		 */
		boolean inOpening(Vec3 point) {
			Vec3 rel = point.subtract(this.origin);
			return Math.abs(rel.dot(this.right)) <= HostCollision.PORTAL_HALF_WIDTH && Math.abs(rel.dot(this.up)) <= HostCollision.PORTAL_HALF_HEIGHT;
		}

		/** (right, up, forward) coordinates of a point. */
		Vec3 local(Vec3 point) {
			Vec3 rel = point.subtract(this.origin);
			return new Vec3(rel.dot(this.right), rel.dot(this.up), rel.dot(this.forward));
		}

		Vec3 world(Vec3 local) {
			return this.origin.add(this.right.scale(local.x)).add(this.up.scale(local.y)).add(this.forward.scale(local.z));
		}

		/** A direction through `in` into this portal's frame: -forward in, +forward out, up kept, right flipped. */
		Vec3 carry(Frame in, Vec3 v) {
			double r = v.dot(in.right), u = v.dot(in.up), n = v.dot(in.forward);
			return this.right.scale(-r).add(this.up.scale(u)).add(this.forward.scale(-n));
		}
	}
}
