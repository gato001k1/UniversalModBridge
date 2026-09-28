package dev.umb.legacy.legacyside.render;

import dev.umb.bridge.api.EntityRenderCapture;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression for the animated-TESR cache retaining one float payload per frame. */
class LegacyRenderCaptureCacheTest {
    @Test
    void genericJointRotationIsDynamicButHostYawIsNot() {
        assertTrue(LegacyRenderCapture.genericAnimationFieldName("rotpartrotation"));
        assertTrue(LegacyRenderCapture.genericAnimationFieldName("rotationroll"));
        assertTrue(LegacyRenderCapture.genericAnimationFieldName("propellerangle"));
        assertTrue(!LegacyRenderCapture.genericAnimationFieldName("rotationyaw"));
        assertTrue(!LegacyRenderCapture.genericAnimationFieldName("rotationpitch"));
    }

    @Test
    void animatedTesrTenThousandFramesStayUnderByteBudget() {
        String oldBudget = System.getProperty("umb.p4.captureCacheBytes");
        try {
            // The bridge side reserves half of the universal setting for this class loader.
            System.setProperty("umb.p4.captureCacheBytes", String.valueOf(32L * 1024L * 1024L));
            LegacyRenderCapture.clearCaptureCacheForTests();
            for (int frame = 0; frame < 10_000; frame++) {
                float[] vertices = new float[4096];
                vertices[0] = frame;
                EntityRenderCapture.Draw draw = EntityRenderCapture.Draw.owned(
                        "test:tesr", vertices, vertices.length / 8, null);
                EntityRenderCapture capture = new EntityRenderCapture(
                        "test.AnimatedTesr", "frame=" + frame, true, 1, 0, 0,
                        Collections.singletonList(draw));
                LegacyRenderCapture.cacheCaptureForTests("animated-" + frame, capture);
                assertTrue(LegacyRenderCapture.captureCacheBytes() <= 16L * 1024L * 1024L);
            }
            assertTrue(LegacyRenderCapture.captureCacheBytes() <= 16L * 1024L * 1024L);
            assertTrue(LegacyRenderCapture.captureCacheEntries() <= 2048);
        } finally {
            LegacyRenderCapture.clearCaptureCacheForTests();
            if (oldBudget == null) System.clearProperty("umb.p4.captureCacheBytes");
            else System.setProperty("umb.p4.captureCacheBytes", oldBudget);
        }
    }
}
