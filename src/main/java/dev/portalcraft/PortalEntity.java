package dev.portalcraft;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * One end of a portal pair: a 1x2 oval lying flat on a block face. The entity's position is the
 * centre of the oval, nudged a hair off the surface. FACE is the surface normal (pointing out of
 * the wall), UP is the oval's long axis.
 */
public class PortalEntity extends Entity {
	public static final double HALF_WIDTH = 0.5;
	public static final double HALF_HEIGHT = 1.0;
	public static final double SURFACE_OFFSET = 0.01;
	private static final int COOLDOWN_TICKS = 10;
	private static final double MAX_SPEED = 10.0;

	private static final EntityDataAccessor<Byte> COLOR = SynchedEntityData.defineId(PortalEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Byte> FACE = SynchedEntityData.defineId(PortalEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Byte> UP = SynchedEntityData.defineId(PortalEntity.class, EntityDataSerializers.BYTE);
	private static final EntityDataAccessor<Boolean> LINKED = SynchedEntityData.defineId(PortalEntity.class, EntityDataSerializers.BOOLEAN);

	private @Nullable UUID owner;
	private @Nullable UUID partner;

	public PortalEntity(EntityType<? extends PortalEntity> type, Level level) {
		super(type, level);
		this.noPhysics = true;
	}

	@Override
	protected void defineSynchedData(SynchedEntityData.Builder builder) {
		builder.define(COLOR, (byte) 0);
		builder.define(FACE, (byte) Direction.NORTH.get3DDataValue());
		builder.define(UP, (byte) Direction.UP.get3DDataValue());
		builder.define(LINKED, false);
	}

	public void setup(UUID owner, PortalColor color, Direction face, Direction up, Vec3 centre) {
		this.owner = owner;
		this.entityData.set(COLOR, (byte) color.ordinal());
		this.entityData.set(FACE, (byte) face.get3DDataValue());
		this.entityData.set(UP, (byte) up.get3DDataValue());
		this.setPos(centre);
	}

	public PortalColor color() {
		return PortalColor.byId(this.entityData.get(COLOR));
	}

	public Direction face() {
		return Direction.from3DDataValue(this.entityData.get(FACE));
	}

	public Direction up() {
		return Direction.from3DDataValue(this.entityData.get(UP));
	}

	public boolean isLinked() {
		return this.entityData.get(LINKED);
	}

	public @Nullable UUID owner() {
		return this.owner;
	}

	public Vec3 normal() {
		return this.face().getUnitVec3();
	}

	public Vec3 upVec() {
		return this.up().getUnitVec3();
	}

	/** normal x up. Together with normal and up this makes the portal's local frame. */
	public Vec3 rightVec() {
		return this.normal().cross(this.upVec());
	}

	/** Thin box covering the oval, reaching a little way out in front of the surface. */
	public AABB mouth(double depth) {
		Vec3 c = this.position();
		Vec3 half = abs(this.upVec().scale(HALF_HEIGHT)).add(abs(this.rightVec().scale(HALF_WIDTH)));
		Vec3 n = this.normal();
		AABB box = new AABB(c.subtract(half), c.add(half));
		return box.expandTowards(n.scale(depth)).expandTowards(n.scale(-0.05));
	}

	/** How far the hole behind a wall portal goes into the wall, and behind a floor or ceiling one. */
	public static final double HOLE_DEPTH_WALL = 1.0, HOLE_DEPTH_FLOOR = 2.0;

	/** The hole cut behind the oval (PortalHoles): its 1x2 outline, from the surface into the blocks. */
	public AABB hole() {
		Vec3 c = this.position().subtract(this.normal().scale(SURFACE_OFFSET));
		Vec3 half = abs(this.upVec().scale(HALF_HEIGHT)).add(abs(this.rightVec().scale(HALF_WIDTH)));
		double depth = this.face().getAxis().isHorizontal() ? HOLE_DEPTH_WALL : HOLE_DEPTH_FLOOR;
		return new AABB(c.subtract(half), c.add(half)).expandTowards(this.normal().scale(-depth));
	}

	/**
	 * Whether a box fits through the hole (across and along the oval), so it can walk into the
	 * portal and go through when its centre crosses the surface. One that doesn't fit (too far off
	 * to the side, feet below the oval) goes through on touching it instead, as before.
	 */
	public boolean fitsThrough(AABB box) {
		Vec3 d = box.getCenter().subtract(this.position());
		Vec3 r = this.rightVec(), u = this.upVec();
		double halfAcross = Math.abs(r.x) * box.getXsize() / 2 + Math.abs(r.y) * box.getYsize() / 2 + Math.abs(r.z) * box.getZsize() / 2;
		double halfAlong = Math.abs(u.x) * box.getXsize() / 2 + Math.abs(u.y) * box.getYsize() / 2 + Math.abs(u.z) * box.getZsize() / 2;
		return Math.abs(d.dot(r)) + halfAcross <= HALF_WIDTH + 1.0E-3 && Math.abs(d.dot(u)) + halfAlong <= HALF_HEIGHT + 1.0E-3;
	}

	/**
	 * Whether a box's centre, moving by `velocity` this tick, crosses the oval's surface inside it,
	 * or is behind it in the hole already (it went in while the portals were cooling down).
	 */
	public boolean centreCrosses(AABB box, Vec3 velocity) {
		Vec3 n = this.normal();
		Vec3 now = box.getCenter().subtract(this.position()), next = now.add(velocity);
		double depth = this.face().getAxis().isHorizontal() ? HOLE_DEPTH_WALL : HOLE_DEPTH_FLOOR;
		if (next.dot(n) >= 0.0 || now.dot(n) < -depth) {
			return false; // still in front of it, or nowhere near its hole
		}
		return Math.abs(next.dot(this.rightVec())) <= HALF_WIDTH && Math.abs(next.dot(this.upVec())) <= HALF_HEIGHT;
	}

	/** True if this box is inside the oval's outline, judged by its centre. */
	public boolean isInFront(AABB box) {
		Vec3 d = box.getCenter().subtract(this.position());
		boolean wall = this.face().getAxis().isHorizontal();
		double across = Math.abs(d.dot(this.rightVec()));
		if (across > HALF_WIDTH) {
			return false;
		}
		if (wall) {
			double feet = box.minY - (this.getY() - HALF_HEIGHT);
			return feet > -0.5 && feet < 0.6;
		}
		return Math.abs(d.dot(this.upVec())) <= HALF_HEIGHT;
	}

	/** Whether a box moving by velocity this tick goes into the portal. */
	public boolean isEntering(AABB box, Vec3 velocity) {
		if (!this.isInFront(box)) {
			return false;
		}
		AABB swept = box.expandTowards(velocity).inflate(0.02);
		if (!swept.intersects(this.mouth(0.0))) {
			return false;
		}
		double into = velocity.dot(this.normal());
		// Floor portals swallow anything resting on them; walls and ceilings need movement into them.
		return this.face() == Direction.UP ? into <= 0.0 : into < -1.0E-3;
	}

	@Override
	public void tick() {
		super.tick();
		PortalHoles.update(this); // both sides: the client moves its player against the hole too
		if (!(this.level() instanceof ServerLevel level)) {
			return;
		}
		if (this.tickCount % 20 == 0 && !this.isSupported()) {
			this.fizzle();
			return;
		}
		PortalEntity other = this.partner(level);
		this.entityData.set(LINKED, other != null);
		if (other == null) {
			return;
		}
		// Players are handled from their own client (see EnterPortalPayload) so momentum is exact.
		List<Entity> entering = level.getEntities(this, this.mouth(1.5), e ->
			!(e instanceof Player) && !(e instanceof PortalEntity) && !e.isPassenger() && !e.isVehicle() && !e.isOnPortalCooldown()
		);
		for (Entity e : entering) {
			if (this.isEntering(e.getBoundingBox(), e.getDeltaMovement())) {
				this.teleport(e, e.getDeltaMovement(), other);
			}
		}
	}

	public @Nullable PortalEntity partner(ServerLevel level) {
		if (this.partner == null) {
			return null;
		}
		return level.getEntity(this.partner) instanceof PortalEntity p && p.isAlive() ? p : null;
	}

	public void linkTo(@Nullable PortalEntity other) {
		this.partner = other == null ? null : other.getUUID();
		this.entityData.set(LINKED, other != null);
	}

	/** Sends an entity out of `exit` with its momentum rotated into the exit's frame. */
	public void teleport(Entity e, Vec3 velocity, PortalEntity exit) {
		if (!(this.level() instanceof ServerLevel level)) {
			return;
		}
		Vec3 newVelocity = this.carry(velocity, exit);
		if (newVelocity.length() > MAX_SPEED) {
			newVelocity = newVelocity.normalize().scale(MAX_SPEED);
		}
		Vec3 look = this.carry(e.getViewVector(1.0F), exit);
		float yaw = (float) (Mth.atan2(-look.x, look.z) * Mth.RAD_TO_DEG);
		float pitch = (float) (Math.asin(Mth.clamp(-look.y, -1.0, 1.0)) * Mth.RAD_TO_DEG);
		if (exit.face().getAxis().isVertical()) {
			// Coming out of a floor or ceiling: keep the old heading, just level the view a bit.
			yaw = e.getYRot();
			pitch = Mth.clamp(pitch, -60.0F, 60.0F);
		}

		Vec3 target = exit.exitPosition(e);
		e.setPortalCooldown(COOLDOWN_TICKS);
		e.teleport(new TeleportTransition(level, target, newVelocity, yaw, pitch, TeleportTransition.DO_NOTHING));
		e.resetFallDistance();
		level.playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.4F);
		level.playSound(null, target.x, target.y, target.z, SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.PLAYERS, 0.6F, 1.6F);
	}

	/**
	 * Maps a vector from this portal's frame into the exit's: going in along -normal comes out along
	 * +exitNormal, up stays up, and right flips so the whole thing is a rotation, not a mirror.
	 */
	public Vec3 carry(Vec3 v, PortalEntity exit) {
		double n = v.dot(this.normal());
		double u = v.dot(this.upVec());
		double r = v.dot(this.rightVec());
		return exit.normal().scale(-n).add(exit.upVec().scale(u)).add(exit.rightVec().scale(-r));
	}

	/** Where an entity's feet go when it leaves through this portal. */
	public Vec3 exitPosition(Entity e) {
		Vec3 c = this.position();
		float w = e.getBbWidth();
		float h = e.getBbHeight();
		return switch (this.face()) {
			case UP -> c.add(0.0, 0.02, 0.0);
			case DOWN -> c.add(0.0, -h - 0.02, 0.0);
			default -> c.add(this.normal().scale(w / 2.0 + 0.05)).add(0.0, -HALF_HEIGHT + 0.01, 0.0);
		};
	}

	/** Both blocks behind the oval must still be solid on this face. */
	public boolean isSupported() {
		Direction face = this.face();
		Vec3 behind = this.position().subtract(this.normal().scale(0.5));
		Vec3 half = this.upVec().scale(0.5);
		for (Vec3 p : new Vec3[] {behind.add(half), behind.subtract(half)}) {
			BlockPos pos = BlockPos.containing(p);
			if (!this.level().getBlockState(pos).isFaceSturdy(this.level(), pos, face)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public void onRemoval(Entity.RemovalReason reason) {
		super.onRemoval(reason);
		PortalHoles.remove(this);
	}

	@Override
	public void onClientRemoval() {
		super.onClientRemoval();
		PortalHoles.remove(this);
	}

		public void fizzle() {
		this.level().playSound(null, this.getX(), this.getY(), this.getZ(), SoundEvents.FIRE_EXTINGUISH, SoundSource.PLAYERS, 0.5F, 1.8F);
		this.discard();
	}

	@Override
	protected void readAdditionalSaveData(ValueInput input) {
		this.owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
		this.partner = input.read("partner", UUIDUtil.CODEC).orElse(null);
		this.entityData.set(COLOR, input.getByteOr("color", (byte) 0));
		this.entityData.set(FACE, input.getByteOr("face", (byte) Direction.NORTH.get3DDataValue()));
		this.entityData.set(UP, input.getByteOr("up", (byte) Direction.UP.get3DDataValue()));
	}

	@Override
	protected void addAdditionalSaveData(ValueOutput output) {
		output.storeNullable("owner", UUIDUtil.CODEC, this.owner);
		output.storeNullable("partner", UUIDUtil.CODEC, this.partner);
		output.putByte("color", this.entityData.get(COLOR));
		output.putByte("face", this.entityData.get(FACE));
		output.putByte("up", this.entityData.get(UP));
	}

	@Override
	public final boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
		return false;
	}

	@Override
	public PushReaction getPistonPushReaction() {
		return PushReaction.IGNORE_ENTITY;
	}

	@Override
	public boolean isIgnoringBlockTriggers() {
		return true;
	}

	public boolean canBeUsedBy(ServerPlayer player) {
		return player.position().distanceToSqr(this.position()) < 16.0;
	}

	private static Vec3 abs(Vec3 v) {
		return new Vec3(Math.abs(v.x), Math.abs(v.y), Math.abs(v.z));
	}
}
