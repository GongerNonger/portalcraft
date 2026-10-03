package dev.portalcraft.client;

import dev.portalcraft.PortalColor;
import dev.portalcraft.PortalCraft;
import dev.portalcraft.PortalEntity;
import dev.portalcraft.host.HostLink;
import dev.portalcraft.net.EnterPortalPayload;
import dev.portalcraft.net.FirePayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.event.client.player.ClientPreAttackCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public class PortalCraftClient implements ClientModInitializer {
	/** Ticks to wait after entering before checking again, so we don't bounce straight back. */
	private static int enterCooldown;
	private static int fireCooldown;

	@Override
	public void onInitializeClient() {
		EntityRendererRegistry.register(PortalCraft.PORTAL, PortalRenderer::new);
		// The gun's firing animation frame, for item models that have the poses (FireFrame).
		net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperties.ID_MAPPER.put(PortalCraft.id("fire_frame"),
			dev.portalcraft.client.gun.FireFrame.MAP_CODEC);
		// The light the gun's glow throws on the gun itself, for item models that mark the surfaces (GunGlow).
		net.minecraft.client.color.item.ItemTintSources.ID_MAPPER.put(PortalCraft.id("gun_glow"), dev.portalcraft.client.gun.GunGlow.MAP_CODEC);
		// Right click with the gun (the secondary portal): the animation starts with the click.
		net.fabricmc.fabric.api.event.player.UseItemCallback.EVENT.register((player, level, hand) -> {
			if (level.isClientSide() && HostLink.current() == null && player.getItemInHand(hand).is(PortalCraft.PORTAL_GUN)
				&& !player.getCooldowns().isOnCooldown(player.getItemInHand(hand))) {
				dev.portalcraft.client.gun.GunAnimation.shot();
			}
			return net.minecraft.world.InteractionResult.PASS;
		});

		// Left click with the gun fires the primary portal instead of attacking or mining.
		ClientPreAttackCallback.EVENT.register((client, player, clickCount) -> {
			if (!player.getMainHandItem().is(PortalCraft.PORTAL_GUN)) {
				// A punch on one of the host's live entities (a cube, a turret) shoves it there.
				if (clickCount > 0 && client.hitResult instanceof net.minecraft.world.phys.BlockHitResult hit
					&& hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && dev.portalcraft.host.HostAim.isHostCell(hit.getBlockPos())) {
					dev.portalcraft.host.HostEvents.queueHit(hit.getLocation(), player.getLookAngle().scale(1.5), 2.0F);
				}
				return false;
			}
			if (clickCount > 0 && fireCooldown == 0) {
				fireCooldown = 4;
				if (HostLink.current() == null) {
					dev.portalcraft.client.gun.GunAnimation.shot(); // inside a host game its own shots drive it
				}
				ClientPlayNetworking.send(new FirePayload((byte) PortalColor.PRIMARY.ordinal()));
			}
			return true;
		});

		ClientTickEvents.START_CLIENT_TICK.register(PortalCraftClient::checkPortals);

		// Playing inside a host game (Portal): listen for its plugin.
		HostLink.start();
		ClientTickEvents.START_CLIENT_TICK.register(HostDriver::tick);
		ClientTickEvents.END_CLIENT_TICK.register(HostDriver::tickEnd);
		// The portal gun's crosshair: which of your portals are out there (Portal's HUD).
		net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry.attachElementAfter(
			net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements.CROSSHAIR, PortalCraft.id("portal_gun_crosshair"), PortalGunHud::extract);
		// Started by Portal: hidden, and gone again when Portal closes.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.CLIENT_STARTED.register(HostLifecycle::started);
		ClientTickEvents.END_CLIENT_TICK.register(client -> HostLifecycle.tick(client, HostLink.current() != null));
	}

	/**
	 * Runs before the player moves this tick. If the move would carry the player into a linked
	 * portal, tell the server now, with the velocity from before the wall or floor stops us.
	 */
	private static void checkPortals(Minecraft client) {
		if (fireCooldown > 0) {
			fireCooldown--;
		}
		if (enterCooldown > 0) {
			enterCooldown--;
			return;
		}
		LocalPlayer player = client.player;
		if (player == null || client.level == null || player.isPassenger() || player.isSpectator()) {
			return;
		}
		Vec3 velocity = player.getDeltaMovement();
		AABB box = player.getBoundingBox();
		for (PortalEntity portal : client.level.getEntitiesOfClass(PortalEntity.class, box.inflate(3.0), PortalEntity::isLinked)) {
			// As in Portal: one that fits through the hole walks in and goes through as its centre
			// crosses the surface; one that doesn't (off to the side) goes through on touching it.
			boolean through = portal.fitsThrough(box) ? portal.centreCrosses(box, velocity) : portal.isEntering(box, velocity);
			if (through) {
				enterCooldown = 10;
				ClientPlayNetworking.send(new EnterPortalPayload(portal.getId(), velocity));
				return;
			}
		}
	}
}
