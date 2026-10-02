package dev.portalcraft.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.portalcraft.PortalColor;
import dev.portalcraft.PortalCraft;
import dev.portalcraft.PortalEntity;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Draws the portal as a flat glowing oval on its surface. Not see-through: that is the host game's job. */
public class PortalRenderer extends EntityRenderer<PortalEntity, PortalRenderer.State> {
	private static final RenderType[] TYPES = {
		RenderTypes.entityTranslucentEmissive(PortalCraft.id("textures/entity/portal_primary.png")),
		RenderTypes.entityTranslucentEmissive(PortalCraft.id("textures/entity/portal_secondary.png")),
	};

	public static class State extends EntityRenderState {
		PortalColor color = PortalColor.PRIMARY;
		Vec3 normal = new Vec3(0, 0, 1);
		Vec3 up = new Vec3(0, 1, 0);
		Vec3 right = new Vec3(1, 0, 0);
		boolean linked;
	}

	public PortalRenderer(EntityRendererProvider.Context context) {
		super(context);
	}

	@Override
	public State createRenderState() {
		return new State();
	}

	@Override
	public void extractRenderState(PortalEntity entity, State state, float partialTicks) {
		super.extractRenderState(entity, state, partialTicks);
		state.color = entity.color();
		state.normal = entity.normal();
		state.up = entity.upVec();
		state.right = entity.rightVec();
		state.linked = entity.isLinked();
	}

	@Override
	protected AABB getBoundingBoxForCulling(PortalEntity entity, float partialTicks) {
		return entity.mouth(0.1).inflate(0.1);
	}

	@Override
	public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
		// An unlinked portal is dimmer, like a closed one.
		int color = state.linked ? 0xFFFFFFFF : 0x99B0B0B0;
		collector.submitCustomGeometry(poseStack, TYPES[state.color.ordinal()], (pose, buffer) -> quad(state, pose, buffer, color));
		super.submit(state, poseStack, collector, camera);
	}

	private static void quad(State s, PoseStack.Pose pose, VertexConsumer buffer, int color) {
		// Seen from the front (looking along -normal), the viewer's right is -right.
		Vec3 r = s.right.scale(-PortalEntity.HALF_WIDTH);
		Vec3 u = s.up.scale(PortalEntity.HALF_HEIGHT);
		vertex(buffer, pose, s, r.scale(-1).subtract(u), 0, 1, color);
		vertex(buffer, pose, s, r.subtract(u), 1, 1, color);
		vertex(buffer, pose, s, r.add(u), 1, 0, color);
		vertex(buffer, pose, s, r.scale(-1).add(u), 0, 0, color);
	}

	private static void vertex(VertexConsumer buffer, PoseStack.Pose pose, State s, Vec3 p, float u, float v, int color) {
		buffer.addVertex(pose, (float) p.x, (float) p.y, (float) p.z)
			.setColor(color)
			.setUv(u, v)
			.setOverlay(OverlayTexture.NO_OVERLAY)
			.setLight(LightCoordsUtil.FULL_BRIGHT)
			.setNormal(pose, (float) s.normal.x, (float) s.normal.y, (float) s.normal.z);
	}
}
