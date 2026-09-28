package dev.umb.hostagent;

import dev.umb.bridge.api.GlEmulationSession;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Converts the data-only legacy GL-EMU mesh into the native 26.2 GUI layer. */
final class LegacyHudPainter {
    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_BLEND = 3042;
    private static final int GL_ALPHA_TEST = 3008;

    private LegacyHudPainter() {}

    static boolean shouldPaint(GlEmulationSession.Draw draw, int width, int height) {
        if (draw == null || draw.vertices == null || draw.vertices.isEmpty()) return false;
        GlEmulationSession.State state = draw.state;
        if (state == null) return true;
        int alpha = (state.color >>> 24) & 255;
        if (state.enabledCaps.contains(GL_ALPHA_TEST)
                && alpha <= Math.round(state.alphaReference * 255.0F)) return false;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (GlEmulationSession.Vertex vertex : draw.vertices) {
            minX = Math.min(minX, vertex.x); minY = Math.min(minY, vertex.y);
            maxX = Math.max(maxX, vertex.x); maxY = Math.max(maxY, vertex.y);
        }
        boolean fullScreen = width > 0 && height > 0
                && minX <= width * 0.05F && minY <= height * 0.05F
                && maxX >= width * 0.95F && maxY >= height * 0.95F;
        boolean textured = state.enabledCaps.contains(GL_TEXTURE_2D)
                && draw.texture != null && !draw.texture.isEmpty();
        // The host already owns full-screen vanilla overlays (portal, vignette, helmet, etc.).
        // Legacy overlay meshes are allowed to contribute HUD geometry, but an untextured quad
        // covering the whole viewport is never a HUD primitive: replaying it can turn the world
        // white when the legacy blend state is incomplete or non-standard. Textured HUD assets
        // remain eligible and are replayed through the native GUI layer.
        return !(fullScreen && !textured);
    }

    static void paint(GuiGraphicsExtractor gui, GlEmulationSession.Mesh mesh) {
        if (gui == null || mesh == null || mesh.draws.isEmpty()) return;
        int drawn = 0;
        gui.nextStratum();
        for (GlEmulationSession.Draw draw : mesh.draws) {
            if (draw == null || draw.vertices == null || draw.vertices.isEmpty()) continue;
            if (!shouldPaint(draw, gui.guiWidth(), gui.guiHeight())) continue;
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            int color = draw.state == null ? 0xFFFFFFFF : draw.state.color;
            for (GlEmulationSession.Vertex vertex : draw.vertices) {
                if (vertex.x < minX) minX = vertex.x;
                if (vertex.y < minY) minY = vertex.y;
                if (vertex.x > maxX) maxX = vertex.x;
                if (vertex.y > maxY) maxY = vertex.y;
                // The draw snapshot is the authoritative GL color. Vertex colors are retained
                // for geometry diagnostics but must not replace glColor4f state here.
            }
            if (!Float.isFinite(minX) || !Float.isFinite(minY)) continue;
            int left = Math.max(0, Math.min(gui.guiWidth(), (int) Math.floor(minX)));
            int top = Math.max(0, Math.min(gui.guiHeight(), (int) Math.floor(minY)));
            int right = Math.max(left + 1, Math.min(gui.guiWidth(), (int) Math.ceil(maxX)));
            int bottom = Math.max(top + 1, Math.min(gui.guiHeight(), (int) Math.ceil(maxY)));
            if (right <= 0 || bottom <= 0 || left >= gui.guiWidth() || top >= gui.guiHeight()) continue;
            boolean textureEnabled = draw.state != null
                    && draw.state.enabledCaps.contains(GL_TEXTURE_2D);
            boolean blended = draw.state != null && draw.state.enabledCaps.contains(GL_BLEND);
            if (!blended) color = color | 0xFF000000;
            boolean textured = false;
            if (textureEnabled && draw.texture != null && !draw.texture.isEmpty()) {
                try {
                    net.minecraft.resources.Identifier id =
                            net.minecraft.resources.Identifier.tryParse(draw.texture);
                    if (id != null) {
                        gui.blit(id, left, top, right - left, bottom - top,
                                0.0F, 0.0F, 1.0F, 1.0F);
                        textured = true;
                    }
                } catch (Throwable ignored) {
                    // A legacy-only resource stays a solid color rather than aborting HUD render.
                }
            }
            if (!textured) gui.fill(left, top, right, bottom, color);
            drawn++;
        }
        if (drawn > 0) AgentLog.line("[HUD-BRIDGE] legacy overlay draws=" + drawn
                + " vertices=" + mesh.vertexCount());
    }
}
