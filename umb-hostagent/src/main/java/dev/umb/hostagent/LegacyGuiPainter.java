package dev.umb.hostagent;

import dev.umb.bridge.api.GlEmulationSession;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Replays captured legacy GUI quads with their own UV rectangle, preserving atlas fidelity. */
public final class LegacyGuiPainter {
    private static final int GL_TEXTURE_2D = 3553;
    private static final int GL_BLEND = 3042;
    private static final int GL_ALPHA_TEST = 3008;
    private static final int GL_QUADS = GlEmulationSession.GL_QUADS;
    private static final int VIRTUAL_TEX = 4096;

    private LegacyGuiPainter() {
    }

    public static int paint(GuiGraphicsExtractor gui, GlEmulationSession.Mesh mesh,
                     int left, int top, int width, int height) {
        if (gui == null || mesh == null || mesh.draws.isEmpty()) return 0;
        boolean sampling = beginSample(mesh);
        String era = meshEra(mesh);
        int painted = 0;
        gui.nextStratum();
        int di = -1;
        for (GlEmulationSession.Draw draw : mesh.draws) {
            di++;
            SampleDraw sample = sampling && di < SAMPLE_DRAWS ? new SampleDraw() : null;
            if (sample != null) sample.header(di, draw);
            if (!shouldPaint(draw, width, height)) {
                endSample(sample, "SKIP-unpaintable");
                continue;
            }
            int color = draw.state == null ? 0xFFFFFFFF : draw.state.color;
            if (draw.state != null && !draw.state.enabledCaps.contains(GL_BLEND)) {
                color |= 0xFF000000;
            }
            boolean textured = draw.state != null
                    && draw.state.enabledCaps.contains(GL_TEXTURE_2D)
                    && draw.texture != null && !draw.texture.isEmpty();
            boolean anyBlit = false;
            for (int i = 0; i + 3 < draw.vertices.size(); i += 4) {
                GlEmulationSession.Vertex a = draw.vertices.get(i);
                GlEmulationSession.Vertex b = draw.vertices.get(i + 1);
                GlEmulationSession.Vertex c = draw.vertices.get(i + 2);
                GlEmulationSession.Vertex d = draw.vertices.get(i + 3);
                float minX = min(a.x, b.x, c.x, d.x), maxX = max(a.x, b.x, c.x, d.x);
                float minY = min(a.y, b.y, c.y, d.y), maxY = max(a.y, b.y, c.y, d.y);
                if (!finite(minX, minY, maxX, maxY) || maxX <= minX || maxY <= minY) {
                    if (sample != null) sample.skipped++;
                    continue;
                }
                // A centered string or a gauge at the panel edge may legitimately straddle the
                // panel boundary.  Dropping that whole glyph/quad made otherwise-correct HBM
                // foregrounds look truncated.  Clip in panel coordinates and carry the same
                // normalized crop into the UV rectangle; this is independent of texture lookup.
                float clipMinX = Math.max(0.0F, minX), clipMinY = Math.max(0.0F, minY);
                float clipMaxX = Math.min((float) width, maxX), clipMaxY = Math.min((float) height, maxY);
                if (clipMaxX <= clipMinX || clipMaxY <= clipMinY) {
                    if (sample != null) sample.skipped++;
                    continue;
                }
                float u0 = min(a.u, b.u, c.u, d.u), u1 = max(a.u, b.u, c.u, d.u);
                float v0 = min(a.v, b.v, c.v, d.v), v1 = max(a.v, b.v, c.v, d.v);
                float tx0 = (clipMinX - minX) / (maxX - minX);
                float tx1 = (clipMaxX - minX) / (maxX - minX);
                float ty0 = (clipMinY - minY) / (maxY - minY);
                float ty1 = (clipMaxY - minY) / (maxY - minY);
                float cu0 = u0 + (u1 - u0) * tx0, cu1 = u0 + (u1 - u0) * tx1;
                float cv0 = v0 + (v1 - v0) * ty0, cv1 = v0 + (v1 - v0) * ty1;
                int x = left + (int) Math.floor(clipMinX);
                int y = top + (int) Math.floor(clipMinY);
                int w = Math.max(1, (int) Math.ceil(clipMaxX - clipMinX));
                int h = Math.max(1, (int) Math.ceil(clipMaxY - clipMinY));
                if (textured) {
                    try {
                        net.minecraft.resources.Identifier id =
                                LegacyGuiTextureResolver.resolve(draw.texture, era);
                        if (id != null && LegacyGuiTextureSupply.ensure(id)) {
                            // Tinted blit: legacy text/labels are white glyphs coloured by
                            // glColor (e.g. red labels); an untinted blit shows them white.
                            // UVs go through a fixed virtual texture size so fractional
                            // legacy UVs keep their precision.
                            int ru = Math.round(cu0 * VIRTUAL_TEX), rv = Math.round(cv0 * VIRTUAL_TEX);
                            int rw = Math.max(1, Math.round((cu1 - cu0) * VIRTUAL_TEX));
                            int rh = Math.max(1, Math.round((cv1 - cv0) * VIRTUAL_TEX));
                            gui.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id,
                                    x, y, ru, rv, w, h, rw, rh, VIRTUAL_TEX, VIRTUAL_TEX, color);
                            anyBlit = true;
                            painted++;
                            if (sample != null) sample.blitted(id.toString());
                            continue;
                        } else if (sample != null) {
                            sample.unresolvable = true;
                        }
                    } catch (Throwable ignored) {
                        // A missing/unparseable legacy bind gets the same bounded color fallback.
                        if (sample != null) sample.resolveFailed = true;
                    }
                }
                gui.fill(x, y, x + w, y + h, color);
                painted++;
                if (sample != null) sample.filled(color);
            }
            // Non-quad legacy primitives are not common in GuiContainer art. Keep them visible as
            // a bounded color approximation rather than smearing an unrelated atlas region.
            // (Skipped when the draw was already blitted: font glyphs arrive as 4-vertex
            // triangle strips, and filling their bounds on top buried every letter in a box.)
            if (!anyBlit && draw.mode != GL_QUADS && draw.vertices.size() >= 2) {
                float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
                float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
                for (GlEmulationSession.Vertex v : draw.vertices) {
                    minX = Math.min(minX, v.x); minY = Math.min(minY, v.y);
                    maxX = Math.max(maxX, v.x); maxY = Math.max(maxY, v.y);
                }
                if (finite(minX, minY, maxX, maxY) && minX >= 0 && minY >= 0
                        && maxX <= width && maxY <= height) {
                    gui.fill(left + (int) minX, top + (int) minY,
                            left + Math.max((int) minX + 1, (int) Math.ceil(maxX)),
                            top + Math.max((int) minY + 1, (int) Math.ceil(maxY)), color);
                    painted++;
                    if (sample != null) sample.filled(color);
                }
            }
            endSample(sample, null);
        }
        endSampleBatch(sampling);
        return painted;
    }

    private static final int SAMPLE_DRAWS = 12;
    private static boolean meshSampled = false;
    private static StringBuilder meshSampleOut = null;

    /** Logs a bounded summary of the first mesh to diagnose texture and UV failures. */
    static synchronized boolean beginSample(GlEmulationSession.Mesh mesh) {
        if (meshSampled || mesh == null || mesh.draws.isEmpty()) return false;
        meshSampled = true;
        meshSampleOut = new StringBuilder(8192);
        meshSampleOut.append("[UMB-GUI] mesh sample draws=").append(mesh.draws.size());
        return true;
    }

    private static final class SampleDraw {
        final StringBuilder head = new StringBuilder(512);
        int blits;
        int fills;
        int skipped;
        String blitId;
        boolean unresolvable;
        boolean resolveFailed;
        int fillColor;
        boolean fillSeen;

        void header(int di, GlEmulationSession.Draw d) {
            head.append(" | #").append(di).append(" mode=").append(d.mode)
                    .append(" tex=").append(d.texture);
            int n = d.vertices == null ? -1 : d.vertices.size();
            head.append(" n=").append(n);
            if (d.vertices != null) {
                int show = Math.min(d.vertices.size(), 4);
                for (int k = 0; k < show; k++) {
                    GlEmulationSession.Vertex v = d.vertices.get(k);
                    head.append(k == 0 ? " v=[" : ",").append(v == null ? "null"
                            : v.x + "," + v.y + "," + v.u + "," + v.v);
                }
                head.append(']');
            }
            head.append(" caps=").append(d.state == null ? "null" : d.state.enabledCaps)
                    .append(" color=").append(d.state == null ? "null"
                            : Integer.toHexString(d.state.color));
        }

        void blitted(String id) {
            blits++;
            if (blitId == null) blitId = id;
        }

        void filled(int color) {
            fills++;
            if (!fillSeen) {
                fillSeen = true;
                fillColor = color;
            }
        }

        String outcome(String early) {
            if (early != null) return early;
            StringBuilder o = new StringBuilder(128);
            o.append("blitx").append(blits);
            if (blitId != null) o.append('(').append(blitId).append(')');
            o.append(" fillx").append(fills);
            if (fillSeen) o.append('(').append(Integer.toHexString(fillColor)).append(')');
            o.append(" skipx").append(skipped);
            if (unresolvable) o.append(" UNRESOLVABLE");
            if (resolveFailed) o.append(" RESOLVE-FAILED");
            return o.toString();
        }
    }

    static synchronized void endSample(SampleDraw sample, String early) {
        if (sample == null || meshSampleOut == null) return;
        meshSampleOut.append(sample.head).append(" => ").append(sample.outcome(early));
    }

    static synchronized void endSampleBatch(boolean sampling) {
        if (!sampling || meshSampleOut == null) return;
        AgentLog.line(meshSampleOut.toString());
        meshSampleOut = null;
    }

    /**
     * Era of the GUI behind this mesh, for private-namespace texture routing (vanilla sheets
     * and stitched atlases differ per era). One GUI belongs to one mod, so the first
     * mod-namespaced bind in the mesh identifies it; unowned namespaces belong to the
     * default 1.7.10 side, exactly like bridge routing itself.
     */
    private static String meshEra(GlEmulationSession.Mesh mesh) {
        try {
            int scans = Math.min(mesh.draws.size(), 8);
            for (int i = 0; i < scans; i++) {
                GlEmulationSession.Draw d = mesh.draws.get(i);
                if (d == null || d.texture == null) continue;
                String raw = d.texture.trim();
                int colon = raw.indexOf(':');
                if (colon <= 0) continue;
                String ns = raw.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
                if (ns.equals("minecraft") || ns.startsWith("umbvanilla")
                        || ns.equals("umbatlas")) {
                    continue;
                }
                try {
                    dev.umb.bridge.api.LegacyBridge bridge =
                            dev.umb.hostagent.content.UmbBridgeHost.get();
                    if (bridge instanceof dev.umb.hostagent.content.BridgeRouter) {
                        return ((dev.umb.hostagent.content.BridgeRouter) bridge).eraFor(raw);
                    }
                } catch (Throwable ignored) {
                    // Fall through to the default below.
                }
                return "1.7.10";
            }
        } catch (Throwable ignored) {
            // Fall through to the default below.
        }
        return "1.7.10";
    }

    private static boolean shouldPaint(GlEmulationSession.Draw draw, int width, int height) {        if (draw == null || draw.vertices == null || draw.vertices.isEmpty()) return false;
        if (draw.state != null && draw.state.enabledCaps.contains(GL_ALPHA_TEST)
                && ((draw.state.color >>> 24) & 255) <= Math.round(draw.state.alphaReference * 255.0F)) {
            return false;
        }
        return width > 0 && height > 0;
    }

    private static float min(float... v) { float out = Float.POSITIVE_INFINITY; for (float x : v) out = Math.min(out, x); return out; }
    private static float max(float... v) { float out = Float.NEGATIVE_INFINITY; for (float x : v) out = Math.max(out, x); return out; }
    private static boolean finite(float... v) { for (float x : v) if (!Float.isFinite(x)) return false; return true; }
}
