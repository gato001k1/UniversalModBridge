package dev.umb.objbridge.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Non-core client render states must never probe the legacy tile provider. */
class LegacyBlockEntityCoreCaptureTest {
    @Test
    void captureIsCoreOnly() {
        assertTrue(LegacyBlockEntityRenderer.shouldCaptureCore(true));
        assertFalse(LegacyBlockEntityRenderer.shouldCaptureCore(false));
    }
}
