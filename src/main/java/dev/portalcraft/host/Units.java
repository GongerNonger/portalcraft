package dev.portalcraft.host;

import net.minecraft.world.phys.Vec3;

/**
 * Source <-> Minecraft. Source is Z-up in units (inches); 1 block = 40 units, which makes the
 * 72-unit Portal player exactly 1.8 blocks tall.
 */
public final class Units {
	public static final double PER_BLOCK = 40.0;
	/** units/s per block/tick */
	public static final double VELOCITY = PER_BLOCK * 20.0;

	/**
	 * Where the current host map's Source origin is in the Minecraft world, along x (blocks; see
	 * MapRegions). Positions carry it, velocities and directions don't. Whole blocks, so the block
	 * grid stays where it is on the map.
	 */
	private static volatile double offsetX;

	private Units() {
	}

	public static double offsetX() {
		return offsetX;
	}

	/** A new host map: from now on its positions are in its own stretch of the world. */
	public static void setOffsetX(double blocks) {
		offsetX = Math.rint(blocks);
	}

	public static Vec3 toMc(Vec3 src) {
		return new Vec3(src.x / PER_BLOCK + offsetX, src.z / PER_BLOCK, -src.y / PER_BLOCK);
	}

	public static Vec3 toSrc(Vec3 mc) {
		return new Vec3((mc.x - offsetX) * PER_BLOCK, -mc.z * PER_BLOCK, mc.y * PER_BLOCK);
	}

	public static Vec3 velocityToMc(Vec3 src) {
		return new Vec3(src.x / VELOCITY, src.z / VELOCITY, -src.y / VELOCITY);
	}

	public static Vec3 velocityToSrc(Vec3 mc) {
		return new Vec3(mc.x * VELOCITY, -mc.z * VELOCITY, mc.y * VELOCITY);
	}

	/** Source yaw is counter-clockwise from +X; Minecraft yaw is clockwise from +Z (south). */
	public static float yawToMc(float srcYaw) {
		return -srcYaw - 90.0F;
	}

	/** Source AngleVectors for (pitch, yaw, roll) in degrees: {forward, right, up}, in Source axes. */
	public static Vec3[] angleVectors(Vec3 angles) {
		double p = Math.toRadians(angles.x), y = Math.toRadians(angles.y), r = Math.toRadians(angles.z);
		double sp = Math.sin(p), cp = Math.cos(p), sy = Math.sin(y), cy = Math.cos(y), sr = Math.sin(r), cr = Math.cos(r);
		Vec3 forward = new Vec3(cp * cy, cp * sy, -sp);
		Vec3 right = new Vec3(-sr * sp * cy + cr * sy, -sr * sp * sy - cr * cy, -sr * cp);
		Vec3 up = new Vec3(cr * sp * cy + sr * sy, cr * sp * sy - sr * cy, cr * cp);
		return new Vec3[] {forward, right, up};
	}
}
