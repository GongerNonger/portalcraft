package dev.portalcraft.client.gun;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

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
 * The portal gun's firing animation on this client's own clock, so it runs at the frame rate
 * instead of in game ticks sent over the network: which pose the model shows (FireFrame, for item
 * models that have the frames), and the whole gun's kick, eased between the animation's frames
 * every rendered frame (applyRecoil, from the resource pack's portalcraft:gun_recoil.json).
 */
public final class GunAnimation {
	private static final Logger LOG = LoggerFactory.getLogger("portalcraft");
	/** Portal's fire1: 16 frames at 30 a second. */
	public static final int FRAMES = 16;
	private static final double FPS = 30.0;
	/** How much of Portal's kick the gun takes: its viewmodel sits further from the eye than Steve's hand. */
	private static final float RECOIL_STRENGTH = 0.6F;

	private record Kick(float tx, float ty, float tz, float rx, float ry, float rz) {
	}

	private static final Kick REST = new Kick(0, 0, 0, 0, 0, 0);
	private static volatile long shotAt = Long.MIN_VALUE;
	private static List<Kick> kicks;

	private GunAnimation() {
	}

	/** The gun this client holds just fired. */
	public static void shot() {
		shotAt = System.nanoTime();
	}

	/** Animation frames since the shot (fractional), or -1 when it's over or there was none. */
	private static double frames() {
		long at = shotAt;
		if (at == Long.MIN_VALUE) {
			return -1.0;
		}
		double f = (System.nanoTime() - at) / 1.0e9 * FPS;
		return f >= 0.0 && f < FRAMES ? f : -1.0;
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

	/** FireFrame's value: 0 at rest, else the current frame + 1 (1 to FRAMES). */
	public static float frameValue() {
		double f = frames();
		return f < 0.0 ? 0.0F : (float) (1 + (int) f);
	}

	/**
	 * The kick, on the pose the first-person item is about to be drawn with (before its own display
	 * transform): nothing at rest, or with a pack that has no recoil data.
	 */
	public static void applyRecoil(PoseStack pose) {
		double f = frames();
		if (f < 0.0) {
			return;
		}
		List<Kick> k = kicks();
		if (k.isEmpty()) {
			return;
		}
		int i = Math.min((int) f, k.size() - 1);
		Kick a = k.get(i), b = i + 1 < k.size() ? k.get(i + 1) : REST;
		float t = (float) (f - i), s = RECOIL_STRENGTH;
		pose.translate(s * lerp(a.tx, b.tx, t), s * lerp(a.ty, b.ty, t), s * lerp(a.tz, b.tz, t));
		float rad = (float) (Math.PI / 180.0) * s;
		pose.mulPose(new org.joml.Matrix4f().rotationXYZ(rad * lerp(a.rx, b.rx, t), rad * lerp(a.ry, b.ry, t), rad * lerp(a.rz, b.rz, t)));
	}

	private static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/** {"fps": 30, "frames": [{"t": [blocks], "r": [degrees]}, ...]}: read once (packs change at a restart). */
	private static List<Kick> kicks() {
		if (kicks != null) {
			return kicks;
		}
		List<Kick> out = new ArrayList<>();
		Resource resource = Minecraft.getInstance().getResourceManager().getResource(PortalCraft.id("gun_recoil.json")).orElse(null);
		if (resource != null) {
			try (Reader in = resource.openAsReader()) {
				JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
				for (var e : root.getAsJsonArray("frames")) {
					JsonArray t = e.getAsJsonObject().getAsJsonArray("t"), r = e.getAsJsonObject().getAsJsonArray("r");
					out.add(new Kick(t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat(), r.get(0).getAsFloat(), r.get(1).getAsFloat(),
						r.get(2).getAsFloat()));
				}
				LOG.info("PortalCraft: gun recoil: {} frames", out.size());
			} catch (java.io.IOException | RuntimeException e) {
				LOG.warn("PortalCraft: can't read gun_recoil.json: {}", e.toString());
				out.clear();
			}
		}
		kicks = List.copyOf(out);
		return kicks;
	}
}
