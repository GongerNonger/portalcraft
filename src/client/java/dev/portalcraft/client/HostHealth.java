package dev.portalcraft.client;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import dev.portalcraft.PortalCraft;
import dev.portalcraft.host.HostLink;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minecraft owns the player's health (as in SkyCraft). The host refunds whatever hurts its own
 * player (turrets, energy balls, toxic water, crushers) and sends it here ("PCU1"), where Steve takes it,
 * armor and all. When Steve dies, of that or of anything Minecraft's own (a fall, lava, TNT), the
 * host is told ("PCZ1") and kills its player, so Portal's own death and checkpoint reload follow.
 */
final class HostHealth {
	private static final Logger LOG = LoggerFactory.getLogger("portalcraft");
	/** Minecraft health per point of Portal health: a full Chell (100) is a full Steve (20). */
	private static final float PER_PORTAL_HEALTH = 20.0F / 100.0F;
	/** Hurts are batched this long (ticks): Minecraft's invulnerability after a hit would eat most of a turret's burst. */
	private static final int BATCH_TICKS = 10;
	private static final ResourceKey<DamageType> TEST = ResourceKey.create(Registries.DAMAGE_TYPE, PortalCraft.id("test"));
	/** Portal's outright kills: the same death, past armor, so the armor isn't worn to nothing on the way. */
	private static final ResourceKey<DamageType> TEST_KILL = ResourceKey.create(Registries.DAMAGE_TYPE, PortalCraft.id("test_kill"));

	private static float pending;
	private static boolean kill;
	private static int sinceApplied = BATCH_TICKS;
	/** Steve's death was reported and he hasn't been alive since. */
	private static boolean dead;
	/** Starts anywhere, so a restarted Minecraft's first death isn't taken for one the host already handled. */
	private static int deathSeq = (int) System.nanoTime();

	private HostHealth() {
	}

	/** Once a client tick while linked: takes the host's hurts and applies them in batches. */
	static void tick(Minecraft minecraft, LocalPlayer player) {
		if (!player.isDeadOrDying()) {
			dead = false;
		}
		for (HostLink.Hurt h; (h = HostLink.takeHurt()) != null;) {
			if (h.kill()) {
				kill = true;
			} else if (h.damage() > 0.0F && h.damage() < 1.0e6F) {
				pending += h.damage() * PER_PORTAL_HEALTH;
			}
		}
		sinceApplied++;
		if (!kill && (pending <= 0.0F || sinceApplied < BATCH_TICKS)) {
			return;
		}
		var server = minecraft.getSingleplayerServer();
		float damage = kill ? Float.MAX_VALUE : pending;
		boolean killing = kill;
		pending = 0.0F;
		kill = false;
		sinceApplied = 0;
		if (server == null || player.isDeadOrDying()) {
			return;
		}
		var uuid = player.getUUID();
		server.execute(() -> {
			ServerPlayer sp = server.getPlayerList().getPlayer(uuid);
			if (sp == null || !sp.isAlive()) {
				return;
			}
			var type = sp.level().registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(killing ? TEST_KILL : TEST);
			sp.setInvulnerableTime(0); // a batch is already half a second of hits
			sp.hurtServer(sp.level(), new DamageSource(type), damage);
			if (killing) {
				LOG.info("PortalCraft: the host's player died; so does Steve");
			}
		});
	}

	/** Steve just died: the host's player dies too. Sent three times (UDP); the host keeps one. */
	static void died() {
		if (dead) {
			return; // still the same death (dying lasts a couple of ticks before the respawn)
		}
		dead = true;
		deathSeq++;
		for (int i = 0; i < 3; i++) {
			ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
			b.put((byte) 'P').put((byte) 'C').put((byte) 'Z').put((byte) '1').putInt(deathSeq);
			HostLink.send(b.flip());
		}
		pending = 0.0F;
		kill = false;
		LOG.info("PortalCraft: Steve died; telling the host (#{})", deathSeq);
	}
}
