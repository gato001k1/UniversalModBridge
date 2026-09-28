package dev.umb.hostagent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import dev.umb.bridge.api.GlEmulationSession;

public final class LegacyHudPainterTest {
    @Test
    public void opaqueUntexturedFullscreenFillIsDropped() {
        GlEmulationSession session = new GlEmulationSession(true);
        session.begin(GlEmulationSession.GL_QUADS);
        session.vertex(0, 0, 0); session.vertex(854, 0, 0);
        session.vertex(854, 480, 0); session.vertex(0, 480, 0);
        GlEmulationSession.Draw draw = session.seal().draws.get(0);
        assertFalse(LegacyHudPainter.shouldPaint(draw, 854, 480));
    }

    @Test
    public void blendedHudPrimitiveRemainsPaintable() {
        GlEmulationSession session = new GlEmulationSession(true);
        session.enable(3042);
        session.blendFunc(770, 771);
        session.begin(GlEmulationSession.GL_QUADS);
        session.vertex(20, 20, 0); session.vertex(120, 20, 0);
        session.vertex(120, 45, 0); session.vertex(20, 45, 0);
        session.end();
        GlEmulationSession.Draw draw = session.seal().draws.get(0);
        assertTrue(LegacyHudPainter.shouldPaint(draw, 854, 480));
    }

    @Test
    public void everyUntexturedFullscreenOverlayIsDroppedEvenWithKnownBlend() {
        GlEmulationSession session = new GlEmulationSession(true);
        session.enable(3042);
        session.blendFunc(770, 771);
        session.begin(GlEmulationSession.GL_QUADS);
        session.vertex(0, 0, 0); session.vertex(854, 0, 0);
        session.vertex(854, 480, 0); session.vertex(0, 480, 0);
        assertFalse(LegacyHudPainter.shouldPaint(session.seal().draws.get(0), 854, 480));
    }

    @Test
    public void glStateIsSnapshottedAndDoesNotLeakBetweenHudDraws() {
        GlEmulationSession session = new GlEmulationSession(true);
        session.enable(3042);
        session.enable(3553);
        session.blendFunc(770, 771);
        session.alphaFunc(516, 0.25F);
        session.depthMask(false);
        session.color(1.0F, 0.0F, 0.0F, 0.5F);
        session.bindTexture("minecraft:hud/first");
        session.begin(GlEmulationSession.GL_QUADS);
        session.vertex(20, 20, 0); session.vertex(120, 20, 0);
        session.vertex(120, 45, 0); session.vertex(20, 45, 0);
        session.end();

        session.disable(3042);
        session.disable(3553);
        session.depthMask(true);
        session.color(0.0F, 1.0F, 0.0F, 1.0F);
        session.bindTexture(null);
        session.begin(GlEmulationSession.GL_QUADS);
        session.vertex(140, 20, 0); session.vertex(240, 20, 0);
        session.vertex(240, 45, 0); session.vertex(140, 45, 0);
        session.end();

        java.util.List<GlEmulationSession.Draw> draws = session.seal().draws;
        GlEmulationSession.State first = draws.get(0).state;
        GlEmulationSession.State second = draws.get(1).state;
        assertTrue(first.enabledCaps.contains(3042));
        assertTrue(first.enabledCaps.contains(3553));
        org.junit.jupiter.api.Assertions.assertEquals(770, first.blendSource);
        org.junit.jupiter.api.Assertions.assertEquals(771, first.blendDestination);
        org.junit.jupiter.api.Assertions.assertEquals(516, first.alphaFunction);
        org.junit.jupiter.api.Assertions.assertEquals(0.25F, first.alphaReference);
        org.junit.jupiter.api.Assertions.assertFalse(second.enabledCaps.contains(3042));
        org.junit.jupiter.api.Assertions.assertFalse(second.enabledCaps.contains(3553));
        org.junit.jupiter.api.Assertions.assertFalse(first.depthMask);
        assertTrue(second.depthMask);
        org.junit.jupiter.api.Assertions.assertEquals(0x80FF0000, first.color);
        org.junit.jupiter.api.Assertions.assertEquals(0xFF00FF00, second.color);
        org.junit.jupiter.api.Assertions.assertEquals("minecraft:hud/first", first.texture);
        org.junit.jupiter.api.Assertions.assertEquals(null, second.texture);
    }

    @Test
    public void opaqueFullscreenWithUnknownBlendFuncIsDropped() {
        GlEmulationSession session = new GlEmulationSession(true);
        session.enable(3042);
        session.blendFunc(770, 770);
        session.begin(GlEmulationSession.GL_QUADS);
        session.vertex(0, 0, 0); session.vertex(854, 0, 0);
        session.vertex(854, 480, 0); session.vertex(0, 480, 0);
        GlEmulationSession.Draw draw = session.seal().draws.get(0);
        assertFalse(LegacyHudPainter.shouldPaint(draw, 854, 480));
    }
}
