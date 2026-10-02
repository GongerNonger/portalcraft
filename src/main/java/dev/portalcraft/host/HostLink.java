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
		ByteBuffer buf = ByteBuffer.allocate(1024);
		while (channel != null) {
			try {
				buf.clear();
				channel.receive(buf);
				buf.flip();
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
