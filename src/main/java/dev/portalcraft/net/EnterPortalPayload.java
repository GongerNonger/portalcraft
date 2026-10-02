package dev.portalcraft.net;

import dev.portalcraft.PortalCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;

/**
 * Client → server: the local player is about to enter a portal. Sent before the client's own collision
 * stops it against the wall or floor, so the velocity here is the real pre-impact momentum.
 */
public record EnterPortalPayload(int portalId, Vec3 velocity) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<EnterPortalPayload> TYPE = new CustomPacketPayload.Type<>(PortalCraft.id("enter_portal"));
	public static final StreamCodec<ByteBuf, EnterPortalPayload> CODEC = StreamCodec.composite(
		ByteBufCodecs.VAR_INT, EnterPortalPayload::portalId,
		Vec3.STREAM_CODEC, EnterPortalPayload::velocity,
		EnterPortalPayload::new
	);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
