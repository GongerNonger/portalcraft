package dev.portalcraft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.Level;

/**
 * Right click fires the secondary portal. Left click (primary) arrives as a FirePayload from the
 * client. The gun's light shows the colour of the portal it last fired, as Portal's does: the
 * colour is the item's custom model data (colour 0), which its model tints the light with.
 */
public class PortalGunItem extends Item {
	/** The light's blue and orange (the in-hand model's tint, items/portal_gun.json). */
	public static final int BLUE = 0x2A8CFF, ORANGE = 0xFF8A1E;

	public PortalGunItem(Item.Properties properties) {
		super(properties);
	}

	/** The gun last fired `color`: its light shows it. */
	public static void setLastFired(ItemStack stack, PortalColor color) {
		int rgb = color == PortalColor.PRIMARY ? BLUE : ORANGE;
		var now = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (now == null || now.getColor(0) == null || now.getColor(0) != rgb) {
			stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(List.of(), List.of(), List.of(), List.of(rgb)));
		}
	}

	/** The light changing colour isn't a new item in the hand: no lowering and raising it again. */
	@Override
	public boolean allowComponentsUpdateAnimation(Player player, InteractionHand hand, ItemStack oldStack, ItemStack newStack) {
		return false;
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
