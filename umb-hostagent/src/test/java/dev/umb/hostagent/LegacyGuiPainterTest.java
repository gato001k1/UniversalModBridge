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
}
