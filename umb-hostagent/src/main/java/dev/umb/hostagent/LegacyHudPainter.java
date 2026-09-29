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
     * Paints a HUD overlay mesh by delegating to {@link LegacyGuiPainter}'s texture-resolution,
     * UV-clipping and tinted-blit logic (deploys #77-#79) instead of this class's own, older
     * single-color-fill-or-whole-bbox-blit approximation. That older path is the concrete,
     * mechanistic source of the "white while flying" report: any HUD quad whose texture failed
     * to resolve here fell back to {@code gui.fill(...,draw.state.color)}, and legacy overlay
     * quads commonly carry a neutral white tint (glColor4f(1,1,1,1)) ahead of the texture bind
     * that was meant to color them - so an unresolved bind painted a solid white box, not a
     * transparent/absent one. {@link #shouldPaint} (unchanged, still covers full-screen-fill and
     * alpha-test rejection) remains the safety net against ever whiting out the whole viewport;
     * {@link LegacyGuiPainter} is only asked to replay the individual bounded quads it allows
     * through, now with the same real texture atlas + tint fidelity the GUI capture path has.
     *
     * <p>No frame-to-frame "skip if unchanged" cache here: this is submitted once per rendered
     * frame into an immediate-mode GUI layer that is fully redrawn every frame (nothing persists
     * from a frame whose paint call was skipped) - skipping the actual blit/fill submission on a
     * visually-static frame would make the HUD flicker/disappear on every such frame, a strict
     * regression, not an optimization. The real per-frame cost - texture decode/GPU upload for
     * each bound legacy asset - is already cached at the correct granularity (per texture id,
     * not per frame) in {@link LegacyGuiTextureSupply#ensure}: a steady-state HUD frame only
     * pays a cheap map lookup per bound texture, never a re-decode/re-upload.</p>
     */
    static void paint(GuiGraphicsExtractor gui, GlEmulationSession.Mesh mesh) {
        if (gui == null || mesh == null || mesh.draws.isEmpty()) return;
        logUnresolvedDrawsOnce(mesh);
        int drawn = LegacyGuiPainter.paint(gui, mesh, 0, 0, gui.guiWidth(), gui.guiHeight(), true);
        // Painted every frame; log only when the draw count changes (a per-frame line filled the
        // capped host log within minutes and hid every later diagnostic).
        if (drawn > 0 && drawn != lastLoggedDraws) {
            lastLoggedDraws = drawn;
            AgentLog.line("[HUD-BRIDGE] legacy overlay draws=" + drawn
                    + " vertices=" + mesh.vertexCount());
        }
    }

    private static int lastLoggedDraws = -1;

    private static final int DIAG_MAX_DRAWS = 40;
    private static volatile boolean diagnosticLogged;

    /**
     * separate from {@link LegacyGuiPainter}'s own one-shot mesh sample, which the GUI-capture
     * path (R-menu etc.) typically consumes first in a live round, before the player ever mounts
     * a vehicle, leaving the HUD path's own draws never logged. Logs every draw that will NOT
     * resolve to a real texture blit (the ones that fall back to {@link #shouldPaint}'s
     * bounded-color fill): its bound texture string, the {@link LegacyGuiTextureResolver}-resolved
     * id, whether {@link LegacyGuiTextureSupply#ensure} can actually serve it, GL_TEXTURE_2D/
     * GL_BLEND state, color, and bounds - everything needed to tell apart "texture never bound",
     * code commonly assumes ambient texturing rather than enabling it itself), and "texture bound
     * and enabled but genuinely missing from the generated resource pack".
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
