package dev.umb.hostagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.umb.bridge.api.GlEmulationSession;

/**
 * Bug #25 gate: the mesh sampler must reach past its first-12 window for small untextured
 * quads (LCD segments), and must sample per GUI shape instead of once per JVM (the HUD used
 * to consume the single sample before any GUI opened).
 */
public final class LegacyGuiPainterTest {
    private static GlEmulationSession.Mesh quadMesh(String texture, boolean texturing,
            float x0, float y0, float x1, float y1, int color) {
        GlEmulationSession session = new GlEmulationSession(true);
        if (texturing) {
            session.enable(3553);
        } else {
            session.disable(3553);
        }
        if (texture != null) {
            session.bindTexture(texture);
        }
        session.begin(GlEmulationSession.GL_QUADS);
        session.color(color);
        session.vertex(x0, y0, 0);
        session.vertex(x1, y0, 0);
        session.vertex(x1, y1, 0);
        session.vertex(x0, y1, 0);
        session.end();
        return session.seal();
    }

    @Test
    public void smallUntexturedQuadIsFlagged() {
        GlEmulationSession.Draw draw = quadMesh(null, false, 10, 10, 18, 22, 0xFFFF0000)
                .draws.get(0);
        assertTrue(LegacyGuiPainter.isSmallUntextured(draw));
    }

    @Test
    public void texturedQuadIsNotFlagged() {
        GlEmulationSession.Draw draw = quadMesh("hbm:gui/flagged-textured", true,
                10, 10, 18, 22, 0xFFFF0000).draws.get(0);
        assertFalse(LegacyGuiPainter.isSmallUntextured(draw));
    }

    @Test
    public void fullscreenQuadIsNotFlagged() {
        GlEmulationSession.Draw draw = quadMesh(null, false, 0, 0, 854, 480, 0xFFFFFFFF)
                .draws.get(0);
        assertFalse(LegacyGuiPainter.isSmallUntextured(draw));
    }

    @Test
    public void degenerateQuadIsNotFlagged() {
        GlEmulationSession session = new GlEmulationSession(true);
        session.disable(3553);
        session.begin(GlEmulationSession.GL_QUADS);
        session.vertex(10, 10, 0);
        session.vertex(10, 10, 0);
        session.vertex(10, 10, 0);
        session.vertex(10, 10, 0);
        assertFalse(LegacyGuiPainter.isSmallUntextured(session.seal().draws.get(0)));
    }

    @Test
    public void samplingIsPerGuiShape() {
        assertTrue(LegacyGuiPainter.beginSample(
                quadMesh("hbm:gui/shape-a-per-gui", true, 0, 0, 176, 166, 0xFFFFFFFF)));
        assertTrue(LegacyGuiPainter.beginSample(
                quadMesh("hbm:gui/shape-b-per-gui", true, 0, 0, 176, 166, 0xFFFFFFFF)));
        assertFalse(LegacyGuiPainter.beginSample(
                quadMesh("hbm:gui/shape-a-per-gui", true, 0, 0, 176, 166, 0xFFFFFFFF)));
    }

    @Test
    public void sampleKeyUsesFirstModTexture() {
        assertEquals("hbm:gui/shape-key-mod",
                LegacyGuiPainter.sampleKey(
                        quadMesh("hbm:gui/shape-key-mod", true, 0, 0, 10, 10, 0xFFFFFFFF)));
    }

    // --- Line primitives (cockpit HUD white box: a GL_LINE_LOOP frame was painted as a fill) ---

    @Test
    public void lineSegmentsFollowGlPrimitiveRules() {
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {0, 1, 2, 3},
                LegacyGuiPainter.lineSegments(LegacyGuiPainter.GL_LINES, 5));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {0, 1, 1, 2},
                LegacyGuiPainter.lineSegments(LegacyGuiPainter.GL_LINE_STRIP, 3));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {0, 1, 1, 2, 2, 3, 3, 0},
                LegacyGuiPainter.lineSegments(LegacyGuiPainter.GL_LINE_LOOP, 4));
        assertEquals(0, LegacyGuiPainter.lineSegments(LegacyGuiPainter.GL_LINE_LOOP, 1).length);
        assertFalse(LegacyGuiPainter.isLineMode(GlEmulationSession.GL_QUADS));
        assertFalse(LegacyGuiPainter.isLineMode(GlEmulationSession.GL_TRIANGLE_STRIP));
    }

    @Test
    public void lineLoopFrameCoversOnlyItsOutline() {
        // MCHeli DrawCameraRot: a 42x22 LINE_LOOP frame centred at (213.5, 180).
        float[][] p = {{192.5f, 169f}, {234.5f, 169f}, {234.5f, 191f}, {192.5f, 191f}};
        int[] seg = LegacyGuiPainter.lineSegments(LegacyGuiPainter.GL_LINE_LOOP, 4);
        boolean[][] covered = new boolean[480][854];
        int cells = 0;
        for (int s = 0; s < seg.length; s += 2) {
            float[] a = p[seg[s]], b = p[seg[s + 1]];
            for (int[] r : LegacyGuiPainter.lineRects(a[0], a[1], b[0], b[1], 427, 240)) {
                assertTrue(r[2] - r[0] == 1 || r[3] - r[1] == 1, "segments are one unit thick");
                for (int y = r[1]; y < r[3]; y++) for (int x = r[0]; x < r[2]; x++) {
                    if (!covered[y][x]) cells++;
                    covered[y][x] = true;
                }
            }
        }
        assertFalse(covered[180][213], "interior of the frame stays see-through");
        assertTrue(covered[169][213] && covered[191][213] && covered[180][192] && covered[180][234]);
        assertEquals(2 * 43 + 2 * 21, cells, "exactly the perimeter");
    }

    @Test
    public void slantedLineIsSteppedNotItsBoundingBox() {
        java.util.List<int[]> rects = LegacyGuiPainter.lineRects(10f, 10f, 30f, 20f, 427, 240);
        assertEquals(21, rects.size());
        for (int[] r : rects) {
            assertEquals(1, r[2] - r[0]);
            assertEquals(1, r[3] - r[1]);
        }
    }

    @Test
    public void lineRectsAreClippedToThePanel() {
        java.util.List<int[]> rects = LegacyGuiPainter.lineRects(-5f, 3f, 5f, 3f, 4, 10);
        assertEquals(1, rects.size());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new int[] {0, 3, 4, 4}, rects.get(0));
        assertTrue(LegacyGuiPainter.lineRects(50f, 50f, 60f, 50f, 40, 40).isEmpty());
    }
}
