package dev.portalcraft.net;

import dev.portalcraft.PortalCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client → server: the player clicked with the gun. Left click can't reach the server any other way. */
public record FirePayload(byte color) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<FirePayload> TYPE = new CustomPacketPayload.Type<>(PortalCraft.id("fire"));
	public static final StreamCodec<ByteBuf, FirePayload> CODEC = StreamCodec.composite(ByteBufCodecs.BYTE, FirePayload::color, FirePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
