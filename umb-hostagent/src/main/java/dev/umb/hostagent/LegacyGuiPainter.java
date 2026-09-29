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
        return paint(gui, mesh, left, top, width, height, false);
    }

    /**
     * @param fullscreenGuard when true, applies {@link LegacyHudPainter#shouldPaint} instead of
     *        this class's own (narrower, alpha-test-only) {@link #shouldPaint} per draw: an
     *        untextured quad covering the whole {@code width}x{@code height} viewport is dropped
     *        before painting. The host already owns full-screen vanilla overlays (portal,
     *        vignette, helmet); replaying an incomplete legacy blend state as an opaque
     *        full-screen fill turns the world white. A bounded {@code GuiContainer} panel (the
     *        original call site below) never legitimately spans the whole viewport, so it never
     *        needed this guard; a full-viewport HUD overlay capture always does.
     */
    public static int paint(GuiGraphicsExtractor gui, GlEmulationSession.Mesh mesh,
                     int left, int top, int width, int height, boolean fullscreenGuard) {
        if (gui == null || mesh == null || mesh.draws.isEmpty()) return 0;
        boolean sampling = beginSample(mesh);
        String era = meshEra(mesh);
        int painted = 0;
        gui.nextStratum();
        int di = -1;
        int sampledSmall = 0;
        for (GlEmulationSession.Draw draw : mesh.draws) {
            di++;
            SampleDraw sample = null;
            if (sampling) {
                if (di < SAMPLE_DRAWS) {
                    sample = new SampleDraw();
                } else if (sampledSmall < SAMPLE_SMALL_UNTEXTURED
                        && isSmallUntextured(draw)) {
                    sample = new SampleDraw();
                    sampledSmall++;
                    sample.head.append(" SMALL-UNTEX");
                }
            }
            if (sample != null) sample.header(di, draw);
            boolean paintable = fullscreenGuard
                    ? LegacyHudPainter.shouldPaint(draw, width, height)
                    : shouldPaint(draw, width, height);
            if (!paintable) {
                endSample(sample, "SKIP-unpaintable");
                continue;
            }
            int color = draw.state == null ? 0xFFFFFFFF : draw.state.color;
            if (draw.state != null && !draw.state.enabledCaps.contains(GL_BLEND)) {
                color |= 0xFF000000;
            }
            if (isLineMode(draw.mode)) {
                // Lines are outlines: HUD frames, brackets, pitch ladders. Grouping their vertices
                // as quads or filling their bounds turned a LINE_LOOP frame into a solid box.
                int lines = paintLines(gui, draw, left, top, width, height, color);
                painted += lines;
                if (sample != null && lines > 0) sample.filled(color);
                endSample(sample, null);
                continue;
            }
            boolean wantsTexture = draw.state != null && draw.state.enabledCaps.contains(GL_TEXTURE_2D);
            boolean textured = wantsTexture && draw.texture != null && !draw.texture.isEmpty();
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
                } else if (wantsTexture && sample != null) {
                    sample.unresolvable = true;
                }
                if (wantsTexture) continue;
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

    static final int GL_LINES = GlEmulationSession.GL_LINES;
    static final int GL_LINE_LOOP = GlEmulationSession.GL_LINE_LOOP;
    static final int GL_LINE_STRIP = GlEmulationSession.GL_LINE_STRIP;

    static boolean isLineMode(int mode) {
        return mode == GL_LINES || mode == GL_LINE_LOOP || mode == GL_LINE_STRIP;
    }

    /**
     * Vertex index pairs of a legacy line primitive: GL_LINES pairs (0,1),(2,3)...; GL_LINE_STRIP
     * joins consecutive vertices; GL_LINE_LOOP also closes the last vertex back to the first.
     */
    static int[] lineSegments(int mode, int vertexCount) {
        int n = Math.max(0, vertexCount);
        if (mode == GL_LINES) {
            int[] out = new int[(n / 2) * 2];
            for (int i = 0; i < out.length; i++) out[i] = i;
            return out;
        }
        if (n < 2 || (mode != GL_LINE_STRIP && mode != GL_LINE_LOOP)) return new int[0];
        int segments = mode == GL_LINE_LOOP && n > 2 ? n : n - 1;
        int[] out = new int[segments * 2];
        for (int s = 0; s < segments; s++) {
            out[s * 2] = s;
            out[s * 2 + 1] = (s + 1) % n;
        }
        return out;
    }

    /**
     * One-unit-wide rectangles {x0,y0,x1,y1} (panel coordinates, end exclusive) covering the
     * segment, clipped to the panel. Axis-aligned segments are one rectangle; slanted ones are
     * stepped one unit at a time (DDA), so a diagonal never becomes its bounding box.
     */
    static java.util.List<int[]> lineRects(float x0, float y0, float x1, float y1, int width, int height) {
        java.util.List<int[]> out = new java.util.ArrayList<int[]>();
        if (!finite(x0, y0, x1, y1)) return out;
        int ax = (int) Math.floor(x0), ay = (int) Math.floor(y0);
        int bx = (int) Math.floor(x1), by = (int) Math.floor(y1);
        if (ax == bx || ay == by) {
            addClipped(out, Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx) + 1, Math.max(ay, by) + 1,
                    width, height);
            return out;
        }
        int steps = Math.max(Math.abs(bx - ax), Math.abs(by - ay));
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            int px = (int) Math.floor(x0 + (x1 - x0) * t), py = (int) Math.floor(y0 + (y1 - y0) * t);
            addClipped(out, px, py, px + 1, py + 1, width, height);
        }
        return out;
    }

    private static void addClipped(java.util.List<int[]> out, int x0, int y0, int x1, int y1,
                                   int width, int height) {
        int cx0 = Math.max(0, x0), cy0 = Math.max(0, y0);
        int cx1 = Math.min(width, x1), cy1 = Math.min(height, y1);
        if (cx1 > cx0 && cy1 > cy0) out.add(new int[] {cx0, cy0, cx1, cy1});
    }

    private static int paintLines(GuiGraphicsExtractor gui, GlEmulationSession.Draw draw,
                                  int left, int top, int width, int height, int color) {
        int[] segments = lineSegments(draw.mode, draw.vertices.size());
        int painted = 0;
        for (int s = 0; s + 1 < segments.length; s += 2) {
            GlEmulationSession.Vertex a = draw.vertices.get(segments[s]);
            GlEmulationSession.Vertex b = draw.vertices.get(segments[s + 1]);
            for (int[] r : lineRects(a.x, a.y, b.x, b.y, width, height)) {
                gui.fill(left + r[0], top + r[1], left + r[2], top + r[3], color);
                painted++;
            }
        }
        return painted;
    }

    private static final int SAMPLE_DRAWS = 12;
    /**
     * first-12 window). Capped per mesh so one pathological GUI cannot flood the log.
     */
    private static final int SAMPLE_SMALL_UNTEXTURED = 48;
    /** One sample per distinct mesh shape (was: first mesh of the JVM, usually the HUD). */
    private static final int MAX_SAMPLED_SHAPES = 24;
    private static final java.util.Set<String> sampledShapes = new java.util.LinkedHashSet<>();

    /** A small untextured quad like an LCD segment: bounded, panel-plausible, worth logging. */
    static boolean isSmallUntextured(GlEmulationSession.Draw draw) {
        if (draw == null || draw.vertices == null || draw.vertices.size() < 4) return false;
        boolean textured = draw.state != null
                && draw.state.enabledCaps.contains(GL_TEXTURE_2D)
                && draw.texture != null && !draw.texture.isEmpty();
        if (textured) return false;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (GlEmulationSession.Vertex v : draw.vertices) {
            if (v == null || !Float.isFinite(v.x) || !Float.isFinite(v.y)) return false;
            if (v.x < minX) minX = v.x;
            if (v.y < minY) minY = v.y;
            if (v.x > maxX) maxX = v.x;
            if (v.y > maxY) maxY = v.y;
        }
        float w = maxX - minX, h = maxY - minY;
        return w > 0.0F && h > 0.0F && w <= 32.0F && h <= 32.0F;
    }

    /**
     * Mesh-shape-keyed diagnostic (was: first mesh of the JVM, which the HUD always consumed
     * before any GUI was ever sampled): records one mesh's per-draw data plus what paint()
     * decided, keyed by GUI art so every new GUI shape gets exactly one live log line. Bounded
     * by shape count; live logs stay quiet afterwards.
     */
    static synchronized boolean beginSample(GlEmulationSession.Mesh mesh) {
        if (mesh == null || mesh.draws.isEmpty()) return false;
        String key = sampleKey(mesh);
        if (sampledShapes.contains(key) || sampledShapes.size() >= MAX_SAMPLED_SHAPES) {
            return false;
        }
        sampledShapes.add(key);
        meshSampleOut = new StringBuilder(32768);
        meshSampleOut.append("[UMB-GUI] mesh sample key=").append(key)
                .append(" draws=").append(mesh.draws.size());
        return true;
    }

    /** GUI identity for sampling: first mod-namespaced bind, else the draw/vertex shape. */
    static String sampleKey(GlEmulationSession.Mesh mesh) {
        try {
            int scans = Math.min(mesh.draws.size(), 8);
            for (int i = 0; i < scans; i++) {
                GlEmulationSession.Draw d = mesh.draws.get(i);
                if (d == null || d.texture == null) continue;
                String raw = d.texture.trim();
                if (raw.length() > 160) raw = raw.substring(0, 160);
                int colon = raw.indexOf(':');
                if (colon <= 0) continue;
                String ns = raw.substring(0, colon).toLowerCase(java.util.Locale.ROOT);
                if (ns.equals("minecraft") || ns.startsWith("umbvanilla")
                        || ns.equals("umbatlas")) {
                    continue;
                }
                return d.texture;
            }
        } catch (Throwable ignored) {
            // Fall through to the shape fallback below.
        }
        int verts = 0;
        try {
            int scans = Math.min(mesh.draws.size(), 32);
            for (int i = 0; i < scans; i++) {
                GlEmulationSession.Draw d = mesh.draws.get(i);
                if (d != null && d.vertices != null) verts += d.vertices.size();
            }
        } catch (Throwable ignored) {
        }
        return "shape:" + mesh.draws.size() + "v" + verts;
    }

    private static StringBuilder meshSampleOut = null;

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
    static String meshEra(GlEmulationSession.Mesh mesh) {
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
