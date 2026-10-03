package dev.portalcraft;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/** Shooting the gun: find the surface, fit a 1x2 oval on it, and replace the player's old portal of that colour. */
public final class PortalPlacement {
	public static final double RANGE = 128.0;

	private PortalPlacement() {
	}

	public static void fire(ServerPlayer player, PortalColor color) {
		if (PortalGunEvents.FIRE.invoker().onFire(player, color)) {
			return; // a host game fires its own portal (and the light follows what it places)
		}
		if (dev.portalcraft.host.HostCollision.active()) {
			return; // inside Portal: Portal fires the real portal; Minecraft's own shot would only add a second trail and sound
		}
		if (player.getMainHandItem().is(PortalCraft.PORTAL_GUN)) {
			PortalGunItem.setLastFired(player, player.getMainHandItem(), color);
		}
		ServerLevel level = player.level();
		Vec3 eye = player.getEyePosition();
		Vec3 end = eye.add(player.getViewVector(1.0F).scale(RANGE));
		BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
		level.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ILLUSIONER_CAST_SPELL, SoundSource.PLAYERS, 0.5F,
			color == PortalColor.PRIMARY ? 1.6F : 1.3F);
		if (hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		trail(level, eye, hit.getLocation(), color);

		Direction face = hit.getDirection();
		Direction up = face.getAxis().isHorizontal() ? Direction.UP : player.getDirection();
		BlockPos[] span = fit(level, hit, face, up);
		if (span == null) {
			fizzleAt(level, hit.getLocation());
			return;
		}

		Vec3 centre = Vec3.atCenterOf(span[0]).add(Vec3.atCenterOf(span[1])).scale(0.5)
			.add(face.getUnitVec3().scale(0.5 + PortalEntity.SURFACE_OFFSET));

		List<? extends PortalEntity> mine = level.getEntities(PortalCraft.PORTAL, p -> player.getUUID().equals(p.owner()));
		PortalEntity other = null;
		PortalEntity old = null;
		for (PortalEntity p : mine) {
			if (p.color() == color) {
				old = p;
			} else {
				other = p;
			}
		}
		if (other != null && other.face() == face && other.position().distanceToSqr(centre) < 1.9 * 1.9) {
			// Would overlap the other end of the pair.
			fizzleAt(level, centre);
			return;
		}
		if (old != null) {
			old.discard();
		}

		PortalEntity portal = PortalCraft.PORTAL.create(level, EntitySpawnReason.TRIGGERED);
		if (portal == null) {
			return;
		}
		portal.setup(player.getUUID(), color, face, up, centre);
		level.addFreshEntity(portal);
		if (other != null) {
			portal.linkTo(other);
			other.linkTo(portal);
		}
		level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F,
			color == PortalColor.PRIMARY ? 1.2F : 0.9F);
	}

	/** Two blocks in a row along `up`, both solid on `face` with open space in front. Prefers the half the shot landed in. */
	private static BlockPos @Nullable [] fit(Level level, BlockHitResult hit, Direction face, Direction up) {
		BlockPos pos = hit.getBlockPos();
		double along = hit.getLocation().subtract(Vec3.atCenterOf(pos)).dot(up.getUnitVec3());
		Direction first = along >= 0 ? up : up.getOpposite();
		for (Direction d : new Direction[] {first, first.getOpposite()}) {
			BlockPos a = d == up ? pos : pos.relative(d);
			BlockPos b = d == up ? pos.relative(d) : pos;
			if (open(level, a, face) && open(level, b, face)) {
				return new BlockPos[] {a, b};
			}
		}
		return null;
	}

	private static boolean open(Level level, BlockPos pos, Direction face) {
		BlockPos front = pos.relative(face);
		return level.getBlockState(pos).isFaceSturdy(level, pos, face)
			&& level.getBlockState(front).getCollisionShape(level, front).isEmpty();
	}

	private static void fizzleAt(ServerLevel level, Vec3 at) {
		level.playSound(null, at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.4F, 2.0F);
	}

	private static void trail(ServerLevel level, Vec3 from, Vec3 to, PortalColor color) {
		DustParticleOptions dust = new DustParticleOptions(color.rgb, 1.2F);
		Vec3 step = to.subtract(from);
		int n = (int) Math.min(64, step.length() * 2);
		for (int i = 2; i < n; i++) {
			Vec3 p = from.add(step.scale(i / (double) n));
			level.sendParticles(dust, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
		}
	}
}
