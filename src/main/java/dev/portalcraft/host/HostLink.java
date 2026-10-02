package dev.portalcraft.host;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;

import dev.portalcraft.PortalCraft;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** UDP link to the host plugin. A daemon thread keeps the newest HostState; sends are fire-and-forget. */
public final class HostLink {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	private static final InetSocketAddress HOST = new InetSocketAddress("127.0.0.1", Proto.HOST_PORT);
	private static final long STALE_NANOS = 500_000_000L;

	private static @Nullable DatagramChannel channel;
	private static volatile Proto.@Nullable HostState latest;
	private static volatile Proto.@Nullable HostEntities entities;
	/** Dev: a "PCD1" x y z packet (host units) asks Minecraft to move its player there. */
	private static volatile net.minecraft.world.phys.@Nullable Vec3 devGoto;
	private static volatile long latestAt;

	private HostLink() {
	}

	public static synchronized void start() {
		if (channel != null) {
			return;
		}
		try {
			DatagramChannel ch = DatagramChannel.open(StandardProtocolFamily.INET);
			ch.bind(new InetSocketAddress("127.0.0.1", Proto.MC_PORT));
			channel = ch;
		} catch (IOException e) {
			LOG.warn("PortalCraft: can't listen on 127.0.0.1:{} ({}); host link disabled", Proto.MC_PORT, e.toString());
			return;
		}
		Thread t = new Thread(HostLink::receiveLoop, "PortalCraft host link");
		t.setDaemon(true);
		t.start();
		LOG.info("PortalCraft: listening for a host game on 127.0.0.1:{}", Proto.MC_PORT);
	}

	private static void receiveLoop() {
		ByteBuffer buf = ByteBuffer.allocate(65536);
		while (channel != null) {
			try {
				buf.clear();
				channel.receive(buf);
				buf.flip();
				if (buf.remaining() > 4 && buf.get(0) == 'P' && buf.get(1) == 'C' && buf.get(2) == 'G' && buf.get(3) == '1') {
					byte[] raw = new byte[buf.remaining() - 4];
					buf.position(4);
					buf.get(raw);
					int len = 0;
					while (len < raw.length && raw[len] != 0) {
						len++;
					}
					devGive = new String(raw, 0, len, java.nio.charset.StandardCharsets.US_ASCII);
					continue;
				}
				if (buf.remaining() == 16 && buf.get(2) == 'D') {
					buf.order(java.nio.ByteOrder.LITTLE_ENDIAN);
					devGoto = new net.minecraft.world.phys.Vec3(buf.getFloat(4), buf.getFloat(8), buf.getFloat(12));
					continue;
				}
				if (buf.remaining() >= 4 && buf.get(2) == 'E') {
					Proto.HostEntities e = Proto.readHostEntities(buf.slice());
					if (e != null) {
						entities = e;
					}
					continue;
				}
				Proto.HostState s = Proto.readHostState(buf.slice());
				if (s != null) {
					latest = s;
					latestAt = System.nanoTime();
				}
			} catch (IOException e) {
				LOG.warn("PortalCraft: host link receive failed: {}", e.toString());
				return;
			}
		}
	}

	/** The newest HostState, or null if the host has gone quiet. */
	public static Proto.@Nullable HostState current() {
		Proto.HostState s = latest;
		return s != null && System.nanoTime() - latestAt < STALE_NANOS ? s : null;
	}

	/** Dev: a "PCG1" item-id packet gives Minecraft's player that item (a stack). */
	private static volatile @Nullable String devGive;

	public static @Nullable String takeDevGive() {
		String g = devGive;
		devGive = null;
		return g;
	}

	public static net.minecraft.world.phys.@Nullable Vec3 takeDevGoto() {
		var g = devGoto;
		devGoto = null;
		return g;
	}

	/** The newest solid-entity list from the host, or null before the first one. */
	public static Proto.@Nullable HostEntities entities() {
		return entities;
	}

	public static void send(ByteBuffer packet) {
		DatagramChannel ch = channel;
		if (ch == null) {
			return;
		}
		try {
			ch.send(packet, HOST);
		} catch (IOException ignored) {
			// host not running: nothing listens, nothing to do
		}
	}
}
