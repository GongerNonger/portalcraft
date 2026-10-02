package dev.portalcraft.client.mixin;

import java.util.Map;

import net.minecraft.client.renderer.rendertype.RenderSetup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A render setup's texture bindings by sampler name ("Sampler0" is the model's texture). */
@Mixin(RenderSetup.class)
public interface RenderSetupAccessor {
	@Accessor("textures")
	Map<String, Object> portalcraft$textures();
}
