package dev.portalcraft;

import dev.portalcraft.net.EnterPortalPayload;
import dev.portalcraft.net.FirePayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;

public class PortalCraft implements ModInitializer {
	public static final String MOD_ID = "portalcraft";

	public static final ResourceKey<Item> PORTAL_GUN_KEY = ResourceKey.create(Registries.ITEM, id("portal_gun"));
	public static final Item PORTAL_GUN = Registry.register(
		BuiltInRegistries.ITEM, PORTAL_GUN_KEY, new PortalGunItem(new Item.Properties().stacksTo(1).setId(PORTAL_GUN_KEY))
	);

	public static final ResourceKey<EntityType<?>> PORTAL_KEY = ResourceKey.create(Registries.ENTITY_TYPE, id("portal"));
	public static final EntityType<PortalEntity> PORTAL = Registry.register(
		BuiltInRegistries.ENTITY_TYPE,
		PORTAL_KEY,
		EntityType.Builder.<PortalEntity>of(PortalEntity::new, MobCategory.MISC)
			.sized(0.25F, 0.25F)
			.noLootTable()
			.fireImmune()
			.clientTrackingRange(10)
			.updateInterval(2)
			.build(PORTAL_KEY)
	);

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(output -> output.accept(PORTAL_GUN));

		PayloadTypeRegistry.serverboundPlay().register(FirePayload.TYPE, FirePayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(EnterPortalPayload.TYPE, EnterPortalPayload.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(FirePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.getMainHandItem().is(PORTAL_GUN) && !player.getCooldowns().isOnCooldown(player.getMainHandItem())) {
				player.getCooldowns().addCooldown(player.getMainHandItem(), 4);
				PortalPlacement.fire(player, PortalColor.byId(payload.color()));
			}
		});

		ServerPlayNetworking.registerGlobalReceiver(EnterPortalPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (player.isOnPortalCooldown() || player.isPassenger()) {
				return;
			}
			if (player.level().getEntity(payload.portalId()) instanceof PortalEntity portal && portal.canBeUsedBy(player)) {
				PortalEntity exit = portal.partner(player.level());
				if (exit != null) {
					portal.teleport(player, payload.velocity(), exit);
				}
			}
		});

		// Holding the gun, left click shoots instead of mining.
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
			player.getItemInHand(hand).is(PORTAL_GUN) ? InteractionResult.FAIL : InteractionResult.PASS
		);
	}
}
