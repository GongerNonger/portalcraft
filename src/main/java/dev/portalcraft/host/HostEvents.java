package dev.portalcraft.host;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import net.minecraft.world.phys.Vec3;

/**
 * Minecraft's blasts and hits, for the host's own physics: an explosion becomes a host blast at
 * the same place and size (cubes thrown, turrets tipped over), a projectile or punch that lands on
 * one of the host's live entities becomes a hit on that entity, pushing it along the hit.
 *
 *   "PCB1" x y z radius damage           a blast (host units; damage drives the push)
 *   "PCI1" index x y z fx fy fz damage   a hit on host entity `index` at a point, with a force
 *
 * Explosions are sent from the server thread that finds them (HostLink.send is safe for that).
 * Hits need LiveEntities, which the client thread owns: they queue here, and the client drains
 * them every tick (drainHits).
 */
public final class HostEvents {
	/** How hard a blast pushes, per Minecraft explosion power (TNT is 4). */
	private static final float BLAST_DAMAGE_PER_POWER = 30.0F;
	/** Host physics force per unit of a projectile's speed (host units/s): an arrow shoves a cube. */
	private static final float HIT_FORCE_PER_SPEED = 4.0F;

	private record Hit(Vec3 point, Vec3 velocity, float damage) {
	}

	private static final java.util.concurrent.ConcurrentLinkedQueue<Hit> HITS = new java.util.concurrent.ConcurrentLinkedQueue<>();

	private HostEvents() {
	}

	/** Something struck the host's geometry (any thread): checked against host entities on the client. */
	public static void queueHit(Vec3 point, Vec3 velocity, float damage) {
		if (HostCollision.active() && HITS.size() < 64) {
			HITS.add(new Hit(point, velocity, damage));
		}
	}

	/** Client thread, once a tick: sends the queued hits that landed on host entities. */
	public static void drainHits() {
		for (Hit h; (h = HITS.poll()) != null;) {
			hit(h.point(), h.velocity(), h.damage());
		}
	}

	/** An explosion of `power` (Minecraft's radius field) at `center` (Minecraft coordinates). */
	public static void explosion(Vec3 center, float power) {
		if (!HostCollision.active()) {
			return;
		}
		Vec3 at = Units.toSrc(center);
		float radius = (float) (power * 2.0 * Units.PER_BLOCK); // Minecraft hurts entities out to twice the power
		ByteBuffer b = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'P').put((byte) 'C').put((byte) 'B').put((byte) '1');
		b.putFloat((float) at.x).putFloat((float) at.y).putFloat((float) at.z).putFloat(radius).putFloat(power * BLAST_DAMAGE_PER_POWER);
		HostLink.send(b.flip());
	}

	/**
	 * Something moving at `velocity` (blocks/tick) struck the host's geometry at `point` (Minecraft
	 * coordinates): if a host entity is there, it gets a hit. Returns true if one was.
	 */
	public static boolean hit(Vec3 point, Vec3 velocity, float damage) {
		if (!HostCollision.active()) {
			return false;
		}
		int index = LiveEntities.entityAt(point, 0.15);
		if (index < 0) {
			return false; // the map itself: nothing to push
		}
		Vec3 at = Units.toSrc(point);
		Vec3 force = Units.velocityToSrc(velocity).scale(HIT_FORCE_PER_SPEED);
		ByteBuffer b = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
		b.put((byte) 'P').put((byte) 'C').put((byte) 'I').put((byte) '1');
		b.putInt(index);
		b.putFloat((float) at.x).putFloat((float) at.y).putFloat((float) at.z);
		b.putFloat((float) force.x).putFloat((float) force.y).putFloat((float) force.z);
		b.putFloat(damage);
		HostLink.send(b.flip());
		return true;
	}
}
