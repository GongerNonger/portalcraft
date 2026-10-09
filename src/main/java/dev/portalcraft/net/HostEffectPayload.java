package dev.portalcraft.net;

import dev.portalcraft.PortalCraft;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client → server, from a friend who has joined someone else's world: what their own Portal did to
 * their Steve, for the server to do to its copy of him. The Minecraft that hosts the world reaches
 * into its own server for these; a friend's has no server to reach into, and without this Portal's
 * turrets and crushers couldn't hurt them, and a fall begun before a portal was still counted
 * after it.
 */
public record HostEffectPayload(byte kind, float amount) implements CustomPacketPayload {
	/** Portal hurt its player: `amount` of Minecraft health. */
	public static final byte HURT = 0;
	/** Portal's player died. */
	public static final byte KILL = 1;
	/** He went through a portal (or was put somewhere): the fall so far no longer counts. */
	public static final byte RESET_FALL = 2;

	public static final CustomPacketPayload.Type<HostEffectPayload> TYPE = new CustomPacketPayload.Type<>(PortalCraft.id("host_effect"));
	public static final StreamCodec<ByteBuf, HostEffectPayload> CODEC = StreamCodec.composite(ByteBufCodecs.BYTE, HostEffectPayload::kind,
		ByteBufCodecs.FLOAT, HostEffectPayload::amount, HostEffectPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
