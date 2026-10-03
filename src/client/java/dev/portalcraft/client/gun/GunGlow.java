package dev.portalcraft.client.gun;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.portalcraft.GunLight;
import dev.portalcraft.client.HostDriver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Item tint source portalcraft:gun_glow: the light the gun's glowing parts throw on the surfaces
 * around them, worked out on the client every frame.
 *
 * A tint multiplies, so a model that wants a surface lit paints it `gain` times its real colour:
 * the tint 1/gain then shows it as it is, and anything up to white lights it, by up to `gain` times
 * what the room's light gives it. Zone 1 is near the tube, zone 2 further out; both glow steadily
 * in the colour of the gun's light (so the claws and collar say which portal is up) and surge with
 * a shot. The glow is real light added to the surface, so it is worth more tint where the room is
 * dark: the gun lights itself in a dark chamber and barely shows it in a bright one. Zone 3 is the
 * white shell, too bright to be lit this way (gain 1): it takes a wash of the colour instead.
 */
public record GunGlow(int zone, float gain) implements ItemTintSource {
	public static final MapCodec<GunGlow> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
		Codec.INT.fieldOf("zone").forGetter(GunGlow::zone),
		Codec.FLOAT.optionalFieldOf("gain", 1.0F).forGetter(GunGlow::gain)
	).apply(i, GunGlow::new));

	/** Light added to a surface, as a share of its own colour: at rest, and more at a shot's peak. */
	private static final float NEAR_REST = 0.10F, NEAR_SHOT = 0.70F, FAR_REST = 0.04F, FAR_SHOT = 0.35F;

	@Override
	public int calculate(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity owner) {
		var data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		Integer set = data == null ? null : data.getColor(0);
		int rgb = set == null ? GunLight.BLUE : set & 0xFFFFFF;
		boolean mine = owner != null && owner == Minecraft.getInstance().player;
		float shot = mine ? GunAnimation.flash() : 0.0F;
		if (zone == 3 || gain <= 1.0F) {
			float wash = 0.06F + 0.39F * shot;
			return 0xFF000000 | blend(0xFFFFFF, rgb, wash);
		}
		float light = zone == 1 ? NEAR_REST + (NEAR_SHOT - NEAR_REST) * shot : FAR_REST + (FAR_SHOT - FAR_REST) * shot;
		// What the room's light already gives the surface (the hand is drawn at that level): the glow
		// is added on top, so it needs more tint the darker the room.
		float room = mine ? roomLight() : 1.0F;
		float unlit = 1.0F / gain, add = light / (gain * room);
		int out = 0xFF000000;
		for (int shift = 16; shift >= 0; shift -= 8) {
			float c = ((rgb >> shift) & 0xFF) / 255.0F;
			out |= Math.round(255.0F * Math.min(1.0F, unlit + add * c)) << shift;
		}
		return out;
	}

	/** How bright Minecraft draws light level `handLightLevel` (its lightmap's curve), 1 at full light. */
	private static float roomLight() {
		int level = HostDriver.handLightLevel();
		if (level < 0) {
			return 1.0F;
		}
		float b = Math.min(15, level + Math.round(7.0F * GunAnimation.flash())) / 15.0F;
		return Math.max(0.08F, b / (4.0F - 3.0F * b));
	}

	private static int blend(int a, int b, float t) {
		int out = 0;
		for (int shift = 16; shift >= 0; shift -= 8) {
			int ca = (a >> shift) & 0xFF, cb = (b >> shift) & 0xFF;
			out |= Math.round(ca + (cb - ca) * t) << shift;
		}
		return out;
	}

	@Override
	public MapCodec<GunGlow> type() {
		return MAP_CODEC;
	}
}
