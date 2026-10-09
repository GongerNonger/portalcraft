package dev.portalcraft.net;

import dev.portalcraft.PortalCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Where one of Portal's loose props is (host units and angles), by its entity index. Playing with
 * friends every Portal has its own copy of each cube; this is how they are kept in one place.
 * Client → server from whoever's gun holds the prop; server → clients for every prop, from the
 * player holding it, or else from the world's host, whose Portal is the one that counts for a prop
 * nobody holds (dev.portalcraft.host.SharedProps).
 */
public record PropPosePayload(int index, float x, float y, float z, float pitch, float yaw, float roll, byte flags) implements CustomPacketPayload {
	/** Someone's gun holds it. */
	public static final byte HELD = 1;
	/** It comes from the host's own Portal (the host's Minecraft has nothing to do with it). */
	public static final byte FROM_HOST = 2;

	public static final CustomPacketPayload.Type<PropPosePayload> TYPE = new CustomPacketPayload.Type<>(PortalCraft.id("prop_pose"));
	public static final StreamCodec<ByteBuf, PropPosePayload> CODEC = StreamCodec.of(
		(b, p) -> b.writeInt(p.index).writeFloat(p.x).writeFloat(p.y).writeFloat(p.z).writeFloat(p.pitch).writeFloat(p.yaw).writeFloat(p.roll).writeByte(p.flags),
		b -> new PropPosePayload(b.readInt(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readFloat(), b.readByte()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
