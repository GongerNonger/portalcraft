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
		PayloadTypeRegistry.serverboundPlay().register(dev.portalcraft.net.HostEffectPayload.TYPE, dev.portalcraft.net.HostEffectPayload.CODEC);

		// What a friend's own Portal did to their Steve (HostEffectPayload). Only in a world a Portal
		// is linked to, and only ever to the player who sent it.
		ServerPlayNetworking.registerGlobalReceiver(dev.portalcraft.net.HostEffectPayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			if (dev.portalcraft.host.HostLink.current() == null || !player.isAlive()) {
				return;
			}
			switch (payload.kind()) {
				case dev.portalcraft.net.HostEffectPayload.RESET_FALL -> player.resetFallDistance();
				case dev.portalcraft.net.HostEffectPayload.HURT, dev.portalcraft.net.HostEffectPayload.KILL -> {
					boolean killing = payload.kind() == dev.portalcraft.net.HostEffectPayload.KILL;
					float damage = killing ? Float.MAX_VALUE : payload.amount();
					if (!(damage > 0.0F)) {
						return;
					}
					var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DAMAGE_TYPE, id(killing ? "test_kill" : "test"));
					var type = player.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE).getOrThrow(key);
					player.setInvulnerableTime(0);
					player.hurtServer(player.level(), new net.minecraft.world.damagesource.DamageSource(type), damage);
				}
				default -> {
				}
			}
		});

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

		// Friends follow the host's Portal from map to map (HostMapPayload): told every two seconds.
		PayloadTypeRegistry.clientboundPlay().register(dev.portalcraft.net.HostMapPayload.TYPE, dev.portalcraft.net.HostMapPayload.CODEC);
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(server -> {
			var host = dev.portalcraft.host.HostLink.current();
			if (server.getTickCount() % 40 != 0 || host == null || !host.inGame() || host.map().isEmpty() || server.getPlayerList().getPlayerCount() < 2) {
				return;
			}
			var payload = new dev.portalcraft.net.HostMapPayload(host.map());
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				if (ServerPlayNetworking.canSend(player, dev.portalcraft.net.HostMapPayload.TYPE)) {
					ServerPlayNetworking.send(player, payload);
				}
			}
		});

		// Everyone who joins has the portal gun. (The Minecraft that hosts the world used to hand it
		// to its own player from the client side, which a friend joining that world never got.)
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			ServerPlayer player = handler.getPlayer();
			server.execute(() -> {
				// (Only in a world whose Minecraft a Portal is linked to: in plain Minecraft the gun is crafted.)
				if (dev.portalcraft.host.HostLink.current() == null) {
					return;
				}
				if (!player.getInventory().contains(new net.minecraft.world.item.ItemStack(PORTAL_GUN))) {
					player.getInventory().add(new net.minecraft.world.item.ItemStack(PORTAL_GUN));
				}
			});
		});

		// Holding the gun, left click shoots instead of mining.
		AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) ->
			player.getItemInHand(hand).is(PORTAL_GUN) ? InteractionResult.FAIL : InteractionResult.PASS
		);

		// The host's cubes and turrets press Minecraft's pressure plates.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(dev.portalcraft.host.HostPlates::tick);
		// Arrows stuck in a host prop come loose when it moves.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(dev.portalcraft.host.HostArrows::tick);
		// Minecraft's things (items, TNT, mobs, arrows) go through Portal's portals.
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(dev.portalcraft.host.HostPortalTransit::tick);
		// Inside a host map Steve is the host player's size (HostScale).
		net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_LEVEL_TICK.register(dev.portalcraft.host.HostScale::tick);
	}
}
