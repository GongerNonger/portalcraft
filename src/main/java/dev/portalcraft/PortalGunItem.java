package dev.portalcraft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

/** Right click fires the secondary portal. Left click (primary) arrives as a FirePayload from the client. */
public class PortalGunItem extends Item {
	public PortalGunItem(Item.Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult use(Level level, Player player, InteractionHand hand) {
		if (player instanceof ServerPlayer serverPlayer) {
			PortalPlacement.fire(serverPlayer, PortalColor.SECONDARY);
		}
		player.getCooldowns().addCooldown(player.getItemInHand(hand), 4);
		return InteractionResult.SUCCESS;
	}
}
