package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.umb.bridge.api.EntityRenderCapture;
import dev.umb.bridge.api.GlEmulationSession;
import dev.umb.legacy.legacyside.LegacyClientFacade;
import dev.umb.legacy.legacyside.render.LegacyRenderCapture;
import org.junit.jupiter.api.Test;

/**
 * is installed on the facade GameSettings for one capture and restored exactly afterwards.
 */
class CockpitViewCaptureStateTest {
    private static final int GL_CULL_FACE = 2884, GL_LIGHTING = 2896, GL_BLEND = 3042;

    private static GlEmulationSession.Draw quad(GlEmulationSession s) {
        s.begin(GlEmulationSession.GL_QUADS);
        for (int i = 0; i < 4; i++) s.vertex(i & 1, (i >> 1) & 1, 0, 0, 0);
        s.end();
        java.util.List<GlEmulationSession.Draw> draws = s.seal().draws;
        return draws.get(draws.size() - 1);
    }

    @Test
    void entityPassBodyDrawIsCulledLitAndBlendedLikeTheLegacyGlState() {
        // The entity pass starts with GL_CULL_FACE + standard item lighting; a renderer that
        // enables GL_BLEND (glass/body with alpha) must reach the host as a blended, lit draw.
        GlEmulationSession s = new GlEmulationSession(true);
        s.enable(GL_CULL_FACE);
        s.enable(GL_LIGHTING);
        s.enable(GL_BLEND);
        EntityRenderCapture.Draw d = LegacyRenderCapture.sealDraw(quad(s), new float[32], 4);
        assertTrue(d.cull);
        assertTrue(d.blend);
        assertTrue(d.lighting);
    }

    @Test
    void lightingDisabledDrawIsUnlitAndOpaqueDefaultStaysOpaque() {
        GlEmulationSession s = new GlEmulationSession(true);
        s.enable(GL_CULL_FACE);
        s.enable(GL_LIGHTING);
        s.disable(GL_LIGHTING); // RenderHelper.disableStandardItemLighting: beams, markers
        EntityRenderCapture.Draw d = LegacyRenderCapture.sealDraw(quad(s), new float[32], 4);
        assertTrue(d.cull);
        assertFalse(d.blend);
        assertFalse(d.lighting);
    }

    @Test
    void viewerCacheSuffixSeparatesCameraModesOnly() {
        assertEquals("", LegacyRenderCapture.viewerCacheSuffix(LegacyRenderCapture.NOT_RIDDEN_BY_VIEWER));
        assertNotEquals(LegacyRenderCapture.viewerCacheSuffix(0), LegacyRenderCapture.viewerCacheSuffix(1));
        assertNotEquals(LegacyRenderCapture.viewerCacheSuffix(1), LegacyRenderCapture.viewerCacheSuffix(2));
        assertNotEquals("", LegacyRenderCapture.viewerCacheSuffix(0));
    }

    @Test
    void riderCameraModeIsInstalledThenRestoredOnTheFacadeSettings() throws Exception {
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, null);
        Object minecraft = binding.minecraft;
        java.lang.reflect.Field settingsField = minecraft.getClass().getDeclaredField("field_71474_y");
        settingsField.setAccessible(true);
        if (settingsField.get(minecraft) == null) settingsField.set(minecraft, binding.gameSettings);
        Object settings = settingsField.get(minecraft);
        java.lang.reflect.Field view = settings.getClass().getDeclaredField("field_74320_O");
        view.setAccessible(true);
        view.setInt(settings, 0);

        int previous = LegacyRenderCapture.installRiderCameraMode(minecraft, 1);
        try {
            assertEquals(0, previous);
            assertEquals(1, view.getInt(settings), "third-person rider sees thirdPersonView=1");
        } finally {
            LegacyRenderCapture.restoreRiderCameraMode(minecraft, previous);
        }
        assertEquals(0, view.getInt(settings), "shared facade settings restored");

        int none = LegacyRenderCapture.installRiderCameraMode(minecraft,
                LegacyRenderCapture.NOT_RIDDEN_BY_VIEWER);
        assertEquals(Integer.MIN_VALUE, none);
        assertEquals(0, view.getInt(settings), "non-rider captures leave the camera mode alone");
        LegacyRenderCapture.restoreRiderCameraMode(minecraft, none);
        assertEquals(0, view.getInt(settings));
    }
}
