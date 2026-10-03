package dev.portalcraft.client.gun;

import com.mojang.serialization.MapCodec;
import dev.portalcraft.GunLight;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Item model property portalcraft:fire_frame: which frame of the gun's firing animation to show (0
 * at rest, else frame + 1). For the gun this client's player holds, from GunAnimation's clock, at
 * the frame rate; for anyone else's, from the keyframe the gun itself carries (custom model data
 * float 0, one a game tick), stretched over the same frames.
 */
public record FireFrame() implements RangeSelectItemModelProperty {
	public static final MapCodec<FireFrame> MAP_CODEC = MapCodec.unit(new FireFrame());

	@Override
	public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
		if (owner != null && owner.asLivingEntity() == Minecraft.getInstance().player) {
			return GunAnimation.frameValue();
		}
		var data = stack.get(DataComponents.CUSTOM_MODEL_DATA);
		Float key = data == null ? null : data.getFloat(0);
		if (key == null || key < 1.0F) {
			return 0.0F;
		}
		return 1.0F + Math.round((key - 1.0F) * (GunAnimation.FRAMES - 1) / (float) (GunLight.FIRE_TICKS - 1));
	}

	@Override
	public MapCodec<FireFrame> type() {
		return MAP_CODEC;
	}
}
