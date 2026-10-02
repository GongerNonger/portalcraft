package dev.portalcraft.client;

import java.lang.foreign.MemorySegment;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.portalcraft.PortalCraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies Minecraft's main render target (hand + HUD + screens on a transparent background, since
 * the level isn't drawn while linked) back from the GPU and hands it to the host through
 * {@link OverlayLink}. The copy is asynchronous: a frame goes into one of a few staging buffers and
 * is shipped once the GPU reports the copy done, usually a frame later. (Approach from SkyCraft, MIT.)
 */
public final class FrameExporter {
	private static final Logger LOG = LoggerFactory.getLogger(PortalCraft.MOD_ID);
	private static final int STAGING = 3;
	private static final int FREE = 0, PENDING = 1, READY = 2;
	/** GpuBuffer usage: MAP_READ | COPY_DST */
	private static final int READBACK = 9;

	private static final Staging[] staging = new Staging[STAGING];
	private static long nextFrameId = 1;
	private static boolean loggedFormat;

	private static final class Staging {
		GpuBuffer buffer;
		int width;
		int height;
		volatile int state = FREE;
		long frameId;
	}

	private FrameExporter() {
	}

	/**
	 * Only in-game layers belong over the host's frame: the HUD, and inventories, chat or the pause
	 * menu on top of it. Loading and title screens paint an opaque background and would hide the
	 * host entirely, so while one is up we send nothing and the host stops drawing us.
	 */
	public static boolean inGameLayer(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.level == null || minecraft.gui.overlay() != null) {
			return false;
		}
		Screen screen = minecraft.gui.screen();
		return screen == null || screen instanceof AbstractContainerScreen<?> || screen instanceof ChatScreen || screen instanceof PauseScreen;
	}

	/** After GameRenderer.render(): queue this frame's readback and ship the newest finished one. */
	public static void capture(Minecraft minecraft) {
		shipReadyFrames();
		if (!inGameLayer(minecraft)) {
			return;
		}

		RenderTarget target = minecraft.gameRenderer.mainRenderTarget();
		GpuTexture color = target.getColorTexture();
		if (color == null || target.width > OverlayLink.MAX_W || target.height > OverlayLink.MAX_H) {
			return;
		}
		if (!loggedFormat) {
			loggedFormat = true;
			LOG.info("PortalCraft: overlay capture {}x{} format {}", target.width, target.height, color.getFormat());
		}

		Staging slot = null;
		for (int i = 0; i < STAGING && slot == null; i++) {
			if (staging[i] == null) {
				staging[i] = new Staging();
			}
			if (staging[i].state == FREE) {
				slot = staging[i];
			}
		}
		if (slot == null) {
			return; // every staging buffer still in flight: skip this frame
		}
		long bytes = (long) target.width * target.height * 4L;
		if (slot.buffer == null || slot.width != target.width || slot.height != target.height) {
			if (slot.buffer != null) {
				slot.buffer.close();
			}
			slot.buffer = RenderSystem.getDevice().createBuffer(() -> "PortalCraft overlay readback", READBACK, bytes);
			slot.width = target.width;
			slot.height = target.height;
		}
		final Staging captured = slot;
		captured.state = PENDING;
		captured.frameId = nextFrameId++;
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(color, captured.buffer, 0L, () -> captured.state = READY, 0);
	}

	private static void shipReadyFrames() {
		Staging newest = null;
		for (Staging s : staging) {
			if (s != null && s.state == READY && (newest == null || s.frameId > newest.frameId)) {
				newest = s;
			}
		}
		if (newest == null) {
			return;
		}
		try (GpuBufferSlice.MappedView view = newest.buffer.map(true, false)) {
			OverlayLink.publish(MemorySegment.ofBuffer(view.data()), newest.width, newest.height);
		}
		for (Staging s : staging) {
			if (s != null && s.state == READY && s.frameId <= newest.frameId) {
				s.state = FREE;
			}
		}
	}
}
