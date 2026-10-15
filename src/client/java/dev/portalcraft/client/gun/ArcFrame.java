package dev.portalcraft.client.gun;

import com.mojang.serialization.MapCodec;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * Item model property portalcraft:arc_frame: which shape of the crackle between the gun's prongs to
 * show while it holds something (tools/make_gun_arcs.py), 0 to 5, a new one about 16 times a second
 * and not in order, so it flickers rather than turns.
 */
public record ArcFrame() implements RangeSelectItemModelProperty {
	public static final MapCodec<ArcFrame> MAP_CODEC = MapCodec.unit(new ArcFrame());
	private static final int[] ORDER = {0, 3, 1, 4, 2, 5, 1, 3, 0, 5, 2, 4};

	@Override
	public float get(ItemStack stack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
		return ORDER[(int) ((System.nanoTime() / 62_000_000L) % ORDER.length)];
	}

	@Override
	public MapCodec<ArcFrame> type() {
		return MAP_CODEC;
	}
}
