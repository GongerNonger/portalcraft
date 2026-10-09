package dev.portalcraft.net;

import dev.portalcraft.PortalCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server → client, every couple of seconds, to everyone in a world a Portal is linked to: the map
 * that Portal is in. A friend's Minecraft has its own Portal load the same one, so they are in the
 * chamber the host is in without typing anything (each Portal's chamber is its own copy; what they
 * share is the Minecraft world laid over it, which has one place per map).
 */
public record HostMapPayload(String map) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<HostMapPayload> TYPE = new CustomPacketPayload.Type<>(PortalCraft.id("host_map"));
	public static final StreamCodec<ByteBuf, HostMapPayload> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(64), HostMapPayload::map, HostMapPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
