package dev.portalcraft.client.gun;

import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.portalcraft.PortalCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The portal gun's animations on this client's own clock, so they run at the frame rate instead of
 * in game ticks sent over the network: which pose the model shows (FireFrame, for item models that
 * have the frames), and the whole gun's motion, eased between the animation's frames every rendered
 * frame (applyRecoil, from the resource pack's portalcraft:gun_recoil.json). Portal's own
 * sequences, 30 frames a second: firing, picking an object up (and holding it), letting it go.
 */
public final class GunAnimation {
	private static final Logger LOG = LoggerFactory.getLogger("portalcraft");
	private static final double FPS = 30.0;
	/** How much of Portal's motion the gun takes: its viewmodel sits further from the eye than Steve's hand. */
	private static final float RECOIL_STRENGTH = 0.6F;

	/** A sequence: its frame count, where its poses start in FireFrame's values, its name in gun_recoil.json. */
	public enum Sequence {
		FIRE(16, 0, 0, "frames"), PICKUP(12, 0, 100, "pickup"),
		/**
		 * Portal's release opens on the claws snapped shut and blends that away from the held pose;
		 * poses can't blend, so it starts at the frame whose claws are as they are held (wide open)
		 * and closes from there.
		 */
		RELEASE(21, 11, 200, "release");

		public final int frames;
		final int first;
		final int base;
		final String key;

		Sequence(int frames, int first, int base, String key) {
			this.frames = frames;
			this.first = first;
			this.base = base;
			this.key = key;
		}
	}

	/** Firing's frame count (FireFrame stretches another player's tick keyframes over it). */
	public static final int FRAMES = Sequence.FIRE.frames;

	private record Kick(float tx, float ty, float tz, float rx, float ry, float rz) {
	}

	private static final Kick REST = new Kick(0, 0, 0, 0, 0, 0);
	private static volatile Sequence playing = Sequence.FIRE;
	private static volatile long startedAt = Long.MIN_VALUE;
	private static volatile long shotAt = Long.MIN_VALUE;
	private static volatile boolean holding;
	private static Map<Sequence, List<Kick>> kicks;

	private GunAnimation() {
	}

	private static void play(Sequence sequence) {
		playing = sequence;
		startedAt = System.nanoTime();
	}

	/** The gun this client holds just fired. */
	public static void shot() {
		shotAt = System.nanoTime();
		if (!holding) {
			play(Sequence.FIRE);
		}
	}

	/** It picked an object up (true) or let it go (false). */
	public static void holding(boolean now) {
		if (now == holding) {
			return;
		}
		holding = now;
		play(now ? Sequence.PICKUP : Sequence.RELEASE);
	}

	/**
	 * Frames into the sequence playing (fractional): past its end, its last frame while an object is
	 * held (pickup's open claws are the holding pose), else -1 (at rest).
	 */
	private static double frames() {
		long at = startedAt;
		if (at == Long.MIN_VALUE) {
			return -1.0;
		}
		Sequence s = playing;
		double f = s.first + (System.nanoTime() - at) / 1.0e9 * FPS;
		if (f < s.first) {
			return -1.0;
		}
		if (f < s.frames) {
			return f;
		}
		return holding && s == Sequence.PICKUP ? s.frames - 1 : -1.0;
	}

	/** How strongly the shot's flash still lights the gun: 1 as it fires, easing to 0 over about a quarter second. */
	public static float flash() {
		long at = shotAt;
		if (at == Long.MIN_VALUE) {
			return 0.0F;
		}
		double left = 1.0 - (System.nanoTime() - at) / 0.27e9;
		return left <= 0.0 || left > 1.0 ? 0.0F : (float) (left * left);
	}

	/** FireFrame's value: 0 at rest, else the sequence's base + the current frame + 1. */
	public static float frameValue() {
		double f = frames();
		return f < 0.0 ? 0.0F : (float) (playing.base + 1 + (int) f);
	}

	/**
	 * The whole gun's motion, on the pose the first-person item is about to be drawn with (before its
	 * own display transform): nothing at rest, or with a pack that has no data for the sequence.
	 */
	public static void applyRecoil(PoseStack pose) {
		double f = frames();
		if (f < 0.0) {
			return;
		}
		List<Kick> k = kicks().getOrDefault(playing, List.of());
		if (k.isEmpty()) {
			return;
		}
		int i = Math.min((int) f, k.size() - 1);
		Kick a = k.get(i), b = i + 1 < k.size() ? k.get(i + 1) : holding && playing == Sequence.PICKUP ? a : REST;
		float t = (float) (f - i), s = RECOIL_STRENGTH;
		pose.translate(s * lerp(a.tx, b.tx, t), s * lerp(a.ty, b.ty, t), s * lerp(a.tz, b.tz, t));
		float rad = (float) (Math.PI / 180.0) * s;
		pose.mulPose(new org.joml.Matrix4f().rotationXYZ(rad * lerp(a.rx, b.rx, t), rad * lerp(a.ry, b.ry, t), rad * lerp(a.rz, b.rz, t)));
	}

	/** Falling this fast (blocks a tick) the gun starts to lift, and this fast it is fully up. */
	private static final float FALL_FROM = 0.55F, FALL_FULL = 1.4F;
	private static float fall, landing;
	private static long fallClock;

	/**
	 * A long fall, on the same pose: the gun floats up and back toward the chest, nose up, and
	 * shivers in the wind, more the faster Steve falls; landing, it dips and settles. Portal's
	 * viewmodel has no sequence for this (its gun only lags behind the view), so this one is ours.
	 * Eased on the frame clock, like the rest.
	 */
	public static void applyFall(PoseStack pose) {
		var player = Minecraft.getInstance().player;
		if (player == null) {
			return;
		}
		long now = System.nanoTime();
		float dt = fallClock == 0L ? 0.0F : Math.min(0.1F, (now - fallClock) / 1.0e9F);
		fallClock = now;
		float down = player.onGround() ? 0.0F : (float) -player.getDeltaMovement().y;
		float want = Math.max(0.0F, Math.min(1.0F, (down - FALL_FROM) / (FALL_FULL - FALL_FROM)));
		if (player.onGround() && fall > 0.25F && landing < fall) {
			landing = fall; // just landed: the dip is as deep as the fall was fast
		}
		fall += (want - fall) * Math.min(1.0F, dt * (want > fall ? 3.0F : 9.0F)); // up slowly, down at once
		landing -= landing * Math.min(1.0F, dt * 7.0F);
		if (fall < 0.002F && landing < 0.002F) {
			return;
		}
		float e = fall * fall * (3.0F - 2.0F * fall); // eased
		double t = now / 1.0e9;
		float shiver = e * (float) (Math.sin(t * 57.0) * 0.6 + Math.sin(t * 91.0) * 0.4);
		pose.translate(0.012F * e + 0.003F * shiver, 0.085F * e - 0.07F * landing + 0.004F * shiver, 0.05F * e);
		float rad = (float) (Math.PI / 180.0);
		pose.mulPose(new org.joml.Matrix4f().rotationXYZ(rad * (13.0F * e - 9.0F * landing + 0.9F * shiver), rad * 2.0F * e, rad * (-6.0F * e + 1.2F * shiver)));
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/**
	 * {"fps": 30, "frames": [{"t": [blocks], "r": [degrees]}, ...], "pickup": [...], "release": [...]}:
	 * read once (packs change at a restart).
	 */
	private static Map<Sequence, List<Kick>> kicks() {
		if (kicks != null) {
			return kicks;
		}
		Map<Sequence, List<Kick>> out = new EnumMap<>(Sequence.class);
		Resource resource = Minecraft.getInstance().getResourceManager().getResource(PortalCraft.id("gun_recoil.json")).orElse(null);
		if (resource != null) {
			try (Reader in = resource.openAsReader()) {
				JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
				for (Sequence s : Sequence.values()) {
					List<Kick> list = new ArrayList<>();
					if (root.has(s.key)) {
						for (var e : root.getAsJsonArray(s.key)) {
							JsonArray t = e.getAsJsonObject().getAsJsonArray("t"), r = e.getAsJsonObject().getAsJsonArray("r");
							list.add(new Kick(t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat(), r.get(0).getAsFloat(),
								r.get(1).getAsFloat(), r.get(2).getAsFloat()));
						}
					}
					out.put(s, List.copyOf(list));
				}
				LOG.info("PortalCraft: gun motion: {} fire, {} pickup, {} release frames", out.get(Sequence.FIRE).size(), out.get(Sequence.PICKUP).size(),
					out.get(Sequence.RELEASE).size());
			} catch (java.io.IOException | RuntimeException e) {
				LOG.warn("PortalCraft: can't read gun_recoil.json: {}", e.toString());
				out.clear();
			}
		}
		kicks = out;
		return kicks;
	}
}
