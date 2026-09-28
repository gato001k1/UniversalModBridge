package dev.umb.legacy1165.legacyside;

import java.util.List;

import dev.umb.bridge.api.EntityRenderCapture;

import com.mojang.blaze3d.matrix.MatrixStack;

import net.minecraft.client.renderer.tileentity.TileEntityRenderer;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.tileentity.TileEntity;

/**
 * Native-free 1.16.5 capture runner for TileEntityRenderers (TESR).
 *
 * <p>A 1.16.5 TESR writes to an {@code IVertexBuilder} under a {@code MatrixStack} through an
 * {@code IRenderTypeBuffer}, the same data path as entity renderers, so it reuses the entity
 * runner's recording buffer. Renderer lookup goes through a headless
 * {@code TileEntityRendererDispatcher} that the lifecycle fills while mods run their
 * {@code ClientRegistry.bindTileEntityRenderer} calls.</p>
 */
public final class LegacyTileRenderCapture1165Client {
    private static final int FULL_BRIGHT = 15728880;
    /** OverlayTexture.NO_OVERLAY (u=0, v=10). */
    private static final int NO_OVERLAY = 655360;
    private static final String FALLBACK_TEXTURE = "minecraft:missingno";
    private static volatile TileEntityRendererDispatcher dispatcher;
    private static volatile String unavailable = "tesr-dispatcher-not-installed";

    private LegacyTileRenderCapture1165Client() {
    }

    /** Binds the headless dispatcher after lifecycle setup has populated it. */
    public static synchronized void install(TileEntityRendererDispatcher next) {
        dispatcher = next;
        unavailable = next == null ? "tesr-dispatcher-null" : null;
    }

    public static String unavailableReason() {
        String reason = unavailable;
        return reason == null ? "" : reason;
    }

    /**
     * Captures the tile's registered renderer output. Returns an empty capture with a
     * diagnostic reason when the tile has no renderer or no dispatcher is installed.
     */
    public static EntityRenderCapture capture(TileEntity tile, float partialTick) {
        if (tile == null) {
            return EntityRenderCapture.empty("", "1165-tesr-tile-null");
        }
        TileEntityRendererDispatcher current = dispatcher;
        if (current == null) {
            return EntityRenderCapture.empty(tile.getClass().getName(),
                    "1165-tesr-dispatcher-unavailable:" + unavailableReason());
        }
        // Resolved OUTSIDE the try: a renderer.render() throw must not hide which real renderer
        // was actually found (era-1165-client-pass.md - a stateKey that names the renderer even
        // on failure is what let the block-state test-harness gap get diagnosed instead of
        // mistaken for "no renderer registered").
        TileEntityRenderer renderer = current.func_147547_b(tile);
        if (renderer == null) {
            return EntityRenderCapture.empty(tile.getClass().getName(),
                    "1165-tesr-renderer-missing:" + tile.getClass().getName());
        }
        try {
            return captureWith(renderer, tile, partialTick);
        } catch (Throwable failure) {
            return EntityRenderCapture.empty(tile.getClass().getName(),
                    "1165-tesr-threw:" + renderer.getClass().getName() + ":" + reason(failure));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static EntityRenderCapture captureWith(TileEntityRenderer renderer, TileEntity tile,
            float partialTick) {
        MatrixStack stack = new MatrixStack();
        // Textures live inside each RenderType; the buffer resolves them per draw group.
        LegacyEntityRenderCapture1165Client.RecordingBuffer buffer =
                new LegacyEntityRenderCapture1165Client.RecordingBuffer(stack, FALLBACK_TEXTURE);
        stack.func_227860_a_();
        try {
            // func_225616_a_: render(tile, partialTicks, matrixStack, buffer, light, overlay)
            renderer.func_225616_a_(tile, partialTick, stack, buffer, FULL_BRIGHT, NO_OVERLAY);
        } finally {
            stack.func_227865_b_();
        }
        List<EntityRenderCapture.Draw> draws = buffer.finish();
        return new EntityRenderCapture(tile.getClass().getName(),
                "1165-tesr:" + renderer.getClass().getName(), true,
                buffer.matrixOps, buffer.pushes, buffer.pops, draws);
    }

    private static String reason(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        String message = current.getMessage();
        String name = current.getClass().getName();
        return message == null || message.isEmpty() ? name : name + ":" + message;
    }
}
