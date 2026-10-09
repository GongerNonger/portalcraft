package dev.portalcraft.host;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.portalcraft.net.PropPosePayload;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Portal's loose props (cubes, cores, turrets) kept in one place for friends in the same world.
 * Every player's Portal has its own copy of each; one of them counts:
 *
 * <ul>
 * <li>while someone's gun holds a prop, that player's Portal (their Minecraft sends where it is);
 * <li>otherwise the Portal of the world's host (its Minecraft is the server, and sends them all).
 * </ul>
 *
 * Everyone else's Portal is told to put its copy there ("PCT1" to the plugin), when it is more
 * than a little off. Each Portal's own buttons and doors then answer to the cube lying on them, so
 * a door one player's cube opens is open for all. Props are told apart by entity index, which is
 * the same in every Portal for what a map starts with (the same map, spawned in the same order).
 */
public final class SharedProps {
	private static final int HOLD_TICKS = 15; // a holder not heard from for this long has let go
	private static final double OFF_UNITS = 1.5, OFF_DEGREES = 4.0;

	private record Hold(UUID player, int tick) {
	}

	private record Told(PropPosePayload pose, long at) {
	}

	/** Server: who holds what (by entity index). */
	private static final Map<Integer, Hold> HELD = new ConcurrentHashMap<>();
	/** Client: where the props are, as last told. */
	private static final Map<Integer, Told> TOLD = new ConcurrentHashMap<>();

	private SharedProps() {
	}

	// ---- server ----------------------------------------------------------------------------------

	/** A player's gun holds this prop: everyone else is told where it is. */
	public static void heldBy(ServerPlayer holder, PropPosePayload pose, MinecraftServer server) {
		HELD.put(pose.index(), new Hold(holder.getUUID(), server.getTickCount()));
		var out = new PropPosePayload(pose.index(), pose.x(), pose.y(), pose.z(), pose.pitch(), pose.yaw(), pose.roll(), PropPosePayload.HELD);
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (player != holder && ServerPlayNetworking.canSend(player, PropPosePayload.TYPE)) {
				ServerPlayNetworking.send(player, out);
			}
		}
	}

	/** Every other server tick: the host's Portal's props, to everyone, but for those a friend holds. */
	public static void serverTick(MinecraftServer server) {
		if (server.getTickCount() % 2 != 0 || server.getPlayerList().getPlayerCount() < 2) {
			return;
		}
		Proto.HostState host = HostLink.current();
		if (host == null || !host.inGame()) {
			return;
		}
		int tick = server.getTickCount();
		HELD.values().removeIf(h -> tick - h.tick() > HOLD_TICKS);
		for (LiveEntities.LoosePose p : LiveEntities.loose()) {
			if (HELD.containsKey(p.index())) {
				continue; // a friend has it: theirs counts
			}
			byte flags = (byte) (PropPosePayload.FROM_HOST | (p.carried() ? PropPosePayload.HELD : 0));
			var out = new PropPosePayload(p.index(), (float) p.origin().x, (float) p.origin().y, (float) p.origin().z, (float) p.angles().x, (float) p.angles().y,
				(float) p.angles().z, flags);
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (ServerPlayNetworking.canSend(player, PropPosePayload.TYPE)) {
					ServerPlayNetworking.send(player, out);
				}
			}
		}
	}

	// ---- client ----------------------------------------------------------------------------------

	/** A prop's place, as the server has it. */
	public static void told(PropPosePayload pose) {
		TOLD.put(pose.index(), new Told(pose, System.currentTimeMillis()));
	}

	/**
	 * Once a client tick: our own Portal's copies put where they were told to be. Not the one our
	 * own gun holds, and, for the world's host, not what its own Portal said in the first place.
	 */
	public static void clientTick(boolean hostOfTheWorld) {
		long now = System.currentTimeMillis();
		TOLD.values().removeIf(t -> now - t.at() > 1000);
		for (Told t : TOLD.values()) {
			PropPosePayload p = t.pose();
			if (hostOfTheWorld && (p.flags() & PropPosePayload.FROM_HOST) != 0) {
				continue;
			}
			LiveEntities.LoosePose own = LiveEntities.looseAt(p.index());
			if (own == null || own.carried()) {
				continue; // not a prop here (another map, or not spawned yet), or in our own hands
			}
			double off = own.origin().distanceTo(new Vec3(p.x(), p.y(), p.z()));
			double turned = Math.max(turn(own.angles().x, p.pitch()), Math.max(turn(own.angles().y, p.yaw()), turn(own.angles().z, p.roll())));
			if (off < OFF_UNITS && turned < OFF_DEGREES) {
				continue;
			}
			ByteBuffer b = ByteBuffer.allocate(4 + 4 + 36).order(ByteOrder.LITTLE_ENDIAN);
			b.put((byte) 'P').put((byte) 'C').put((byte) 'T').put((byte) '1').putInt(p.index());
			b.putFloat(p.x()).putFloat(p.y()).putFloat(p.z()).putFloat(p.pitch()).putFloat(p.yaw()).putFloat(p.roll());
			b.putFloat(0.0F).putFloat(0.0F).putFloat(0.0F);
			HostLink.send(b.flip());
		}
	}

	private static double turn(double a, double b) {
		double d = Math.abs(a - b) % 360.0;
		return d > 180.0 ? 360.0 - d : d;
	}
}
