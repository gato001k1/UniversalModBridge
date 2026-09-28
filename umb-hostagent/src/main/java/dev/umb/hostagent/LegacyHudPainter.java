package dev.umb.hostagent;

import dev.umb.bridge.api.GlEmulationSession;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

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

    /**
     * Paints a HUD overlay mesh through {@link LegacyGuiPainter}'s texture and blit
     * logic instead of this class's old fill approximation. The old path fell back
     * to a solid fill whenever a texture failed to resolve, and legacy HUD quads
     * commonly carry a white tint ahead of the bind meant to color them, so an
     * unresolved bind painted a solid white box. {@link #shouldPaint} stays as the
     * guard against full-screen fills.
     *
     * <p>No skip-if-unchanged cache here: the GUI layer redraws every frame, so
     * skipping a static frame would make the HUD flicker. Per-texture decode and
     * upload are already cached per texture id, so a steady frame only pays map
     * lookups.</p>
     */
    static void paint(GuiGraphicsExtractor gui, GlEmulationSession.Mesh mesh) {
        if (gui == null || mesh == null || mesh.draws.isEmpty()) return;
        logUnresolvedDrawsOnce(mesh);
        int drawn = LegacyGuiPainter.paint(gui, mesh, 0, 0, gui.guiWidth(), gui.guiHeight(), true);
        if (drawn > 0) AgentLog.line("[HUD-BRIDGE] legacy overlay draws=" + drawn
                + " vertices=" + mesh.vertexCount());
    }

    private static final int DIAG_MAX_DRAWS = 40;
    private static volatile boolean diagnosticLogged;

    /**
     * One-shot (per JVM) log of the draws that will not become texture blits:
     * bound texture string, resolved id, whether the texture supply can serve
     * it, GL state, color, and bounds. Enough to tell apart "texture never
     * bound", "bound but texturing never enabled" (legacy HUD code often
     * assumes ambient texturing), and "bound but missing from the pack".
     */
    private static void logUnresolvedDrawsOnce(GlEmulationSession.Mesh mesh) {
        if (diagnosticLogged) return;
        synchronized (LegacyHudPainter.class) {
            if (diagnosticLogged) return;
            diagnosticLogged = true;
        }
        String era = LegacyGuiPainter.meshEra(mesh);
        StringBuilder out = new StringBuilder(4096);
        out.append("[HUD-DIAG] first HUD mesh draws=").append(mesh.draws.size()).append(" era=").append(era);
        int shown = 0;
        for (int i = 0; i < mesh.draws.size() && shown < DIAG_MAX_DRAWS; i++) {
            GlEmulationSession.Draw draw = mesh.draws.get(i);
            if (draw == null) continue;
            boolean textureCap = draw.state != null && draw.state.enabledCaps.contains(GL_TEXTURE_2D);
            boolean blendCap = draw.state != null && draw.state.enabledCaps.contains(GL_BLEND);
            int color = draw.state == null ? 0xFFFFFFFF : draw.state.color;
            Identifier resolved = null;
            boolean supplyOk = false;
            try {
                resolved = LegacyGuiTextureResolver.resolve(draw.texture, era);
                supplyOk = resolved != null && LegacyGuiTextureSupply.ensure(resolved);
            } catch (Throwable ignored) {
                // Diagnostic-only lookup; a resolver/supply failure is itself part of the finding.
            }
            boolean willBlit = textureCap && draw.texture != null && !draw.texture.isEmpty() && supplyOk;
            if (willBlit) continue;
            float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
            if (draw.vertices != null) {
                for (GlEmulationSession.Vertex v : draw.vertices) {
                    if (v == null) continue;
                    minX = Math.min(minX, v.x); minY = Math.min(minY, v.y);
                    maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y);
                }
            }
            out.append("\n  #").append(i)
                    .append(" tex=").append(draw.texture)
                    .append(" resolved=").append(resolved)
                    .append(" supplyOk=").append(supplyOk)
                    .append(" GL_TEXTURE_2D=").append(textureCap)
                    .append(" GL_BLEND=").append(blendCap)
                    .append(" color=0x").append(Integer.toHexString(color))
                    .append(" mode=").append(draw.mode)
                    .append(" verts=").append(draw.vertices == null ? 0 : draw.vertices.size())
                    .append(" bounds=[").append(minX).append(',').append(minY)
                    .append(" - ").append(maxX).append(',').append(maxY).append(']');
            shown++;
        }
        if (shown == 0) {
            out.append("\n  (every draw in this mesh resolved to a real texture blit)");
        }
        AgentLog.line(out.toString());
    }
}
