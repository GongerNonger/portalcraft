package dev.portalcraft;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.level.Level;

/**
 * Right click fires the secondary portal. Left click (primary) arrives as a FirePayload from the
 * client. The gun's light shows the colour of the portal it last fired, as Portal's does: the
 * colour is the item's custom model data (colour 0), which its model tints the light with.
 */
public class PortalGunItem extends Item {
	/** The light's blue and orange (the in-hand model's tint, items/portal_gun.json). */
	public static final int BLUE = GunLight.BLUE, ORANGE = GunLight.ORANGE;

	public PortalGunItem(Item.Properties properties) {
		super(properties);
	}

	/** How long a shot shows on the gun, in ticks: its flash, and the firing animation's keyframes. */
	private static final int FLASH_TICKS = Math.max(GunLight.FLASH_TICKS, GunLight.FIRE_TICKS);

	/** A flash in progress: the colour it settles to, and the game time it started. */
	private record Flash(int rgb, long start) {
	}

	private static final Map<UUID, Flash> FLASHES = new ConcurrentHashMap<>();

	/**
	 * The gun fired `color`: like Portal's, its light flares white-hot and settles to that colour
	 * over FLASH_TICKS (inventoryTick). Every shot flashes, the same colour again included.
	 */
	public static void setLastFired(ServerPlayer player, ItemStack stack, PortalColor color) {
		int rgb = color == PortalColor.PRIMARY ? BLUE : ORANGE;
		FLASHES.put(player.getUUID(), new Flash(rgb, player.level().getGameTime()));
		light(stack, rgb, 0);
	}

	/**
	 * The gun's colours `ticks` into a shot of `rgb` (FLASH_TICKS or more: at rest), as the item's
	 * custom model data: colours [0] the light itself, [1] and [2] what it throws on the gun around
	 * it (GunLight.spill), and float [0] the firing animation's keyframe. Models without those tints
	 * or keyframes just ignore them.
	 */
	private static void light(ItemStack stack, int rgb, long ticks) {
		List<Integer> colours = List.of(GunLight.flash(rgb, ticks), GunLight.spill(rgb, ticks, true), GunLight.spill(rgb, ticks, false),
			GunLight.shell(rgb, ticks));
		List<Float> frame = List.of((float) GunLight.keyframe(ticks));
		var now = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		if (now == null || !now.colors().equals(colours) || !now.floats().equals(frame)) {
			stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(frame, List.of(), List.of(), colours));
		}
	}

	/** Plays out the holder's flash on the gun in their main hand. */
	@Override
	public void inventoryTick(ItemStack stack, ServerLevel level, Entity entity, EquipmentSlot slot) {
		if (slot != EquipmentSlot.MAINHAND) {
			return;
		}
		Flash flash = FLASHES.get(entity.getUUID());
		if (flash == null) {
			return;
		}
		long ticks = level.getGameTime() - flash.start();
		if (ticks >= FLASH_TICKS || ticks < 0) {
			FLASHES.remove(entity.getUUID(), flash);
			light(stack, flash.rgb(), FLASH_TICKS);
		} else {
			light(stack, flash.rgb(), ticks);
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
