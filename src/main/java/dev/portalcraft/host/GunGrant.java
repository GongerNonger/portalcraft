package dev.portalcraft.host;

import dev.portalcraft.PortalCraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Steve's portal gun inside a host game follows the host's: none until the host's player picks
 * one up, the one that fires blue only while that is what the host has, and the full one in its
 * place (the same slot) when the host's gun is upgraded. Nothing is ever taken away: a world that
 * already has a gun in it keeps it.
 */
public final class GunGrant {
	private static final String BLUE_ONLY_NAME = "Portal Gun (blue only)";

	private GunGrant() {
	}

	public static ItemStack gun(boolean blueOnly) {
		ItemStack stack = new ItemStack(PortalCraft.PORTAL_GUN);
		if (blueOnly) {
			stack.set(DataComponents.ITEM_NAME, Component.literal(BLUE_ONLY_NAME));
		}
		return stack;
	}

	public static boolean blueOnly(ItemStack stack) {
		Component name = stack.get(DataComponents.ITEM_NAME);
		return stack.is(PortalCraft.PORTAL_GUN) && name != null && BLUE_ONLY_NAME.equals(name.getString());
	}

	/** level: Proto.HostState.gunLevel (-1 unknown: an older plugin, which gets the gun from the start as before; 0 none; 1 blue only; 2 both). */
	public static void sync(ServerPlayer player, int level) {
		Inventory inventory = player.getInventory();
		boolean any = false;
		int blueSlot = -1;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(PortalCraft.PORTAL_GUN)) {
				any = true;
				if (blueSlot < 0 && blueOnly(stack)) {
					blueSlot = i;
				}
			}
		}
		if (level == 0) {
			return;
		}
		if (level == 2 && blueSlot >= 0) {
			inventory.setItem(blueSlot, gun(false));
		} else if (!any) {
			inventory.add(gun(level == 1));
		}
	}
}
