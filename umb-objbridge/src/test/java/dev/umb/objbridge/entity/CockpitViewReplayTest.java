package dev.umb.objbridge.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.minecraft.client.CameraType;
import org.junit.jupiter.api.Test;

/**
 * must not become an opaque cutout wall), the host camera maps onto legacy thirdPersonView, and
 * the camera-aware capture overload is used when present and skipped for older handles.
 */
class CockpitViewReplayTest {

    @Test
    void blendAndCullSelectTheMatchingRenderTypeSlot() {
        assertEquals(LegacyEntityRenderer.SLOT_CUTOUT, LegacyEntityRenderer.renderTypeSlot(false, false));
        assertEquals(LegacyEntityRenderer.SLOT_CUTOUT_CULL, LegacyEntityRenderer.renderTypeSlot(true, false));
        assertEquals(LegacyEntityRenderer.SLOT_TRANSLUCENT, LegacyEntityRenderer.renderTypeSlot(false, true));
        assertEquals(LegacyEntityRenderer.SLOT_TRANSLUCENT_CULL, LegacyEntityRenderer.renderTypeSlot(true, true));
    }

    @Test
    void hostCameraMapsToLegacyThirdPersonView() {
        assertEquals(0, LegacyEntityRenderer.legacyCameraMode(CameraType.FIRST_PERSON));
        assertEquals(1, LegacyEntityRenderer.legacyCameraMode(CameraType.THIRD_PERSON_BACK));
        assertEquals(2, LegacyEntityRenderer.legacyCameraMode(CameraType.THIRD_PERSON_FRONT));
        assertEquals(-1, LegacyEntityRenderer.legacyCameraMode(null));
    }

    @Test
    void unlitNormalIsWorldUp() {
        assertEquals(0f, LegacyEntityRenderer.UNLIT_NORMAL[0]);
        assertEquals(1f, LegacyEntityRenderer.UNLIT_NORMAL[1]);
        assertEquals(0f, LegacyEntityRenderer.UNLIT_NORMAL[2]);
    }

    /** New bridge: both overloads. */
    public static final class ViewerAwareHandle {
        public int lastMode = Integer.MIN_VALUE;
        public Object renderCapture(float partialTick) { lastMode = -100; return "neutral"; }
        public Object renderCapture(float partialTick, int riderCameraMode) {
            lastMode = riderCameraMode;
            return "viewer-" + riderCameraMode;
        }
    }

    /** Older bridge: only the neutral capture. */
    public static final class NeutralOnlyHandle {
        public Object renderCapture(float partialTick) { return "neutral"; }
    }

    @Test
    void riderCaptureUsesTheCameraAwareOverload() throws Exception {
        ViewerAwareHandle handle = new ViewerAwareHandle();
        LegacyEntityCaptureClient.Result r = LegacyEntityCaptureClient.invokeCapture(handle, 0.5f, 0);
        assertNull(r.reason);
        assertEquals("viewer-0", r.capture);
        assertEquals(0, handle.lastMode);
    }

    @Test
    void nonRiderCaptureStaysNeutral() throws Exception {
        ViewerAwareHandle handle = new ViewerAwareHandle();
        LegacyEntityCaptureClient.Result r = LegacyEntityCaptureClient.invokeCapture(handle, 0.5f, -1);
        assertEquals("neutral", r.capture);
        assertEquals(-100, handle.lastMode);
    }

    @Test
    void olderHandleWithoutOverloadFallsBackToNeutral() throws Exception {
        LegacyEntityCaptureClient.Result r =
                LegacyEntityCaptureClient.invokeCapture(new NeutralOnlyHandle(), 0.5f, 1);
        assertNull(r.reason);
        assertEquals("neutral", r.capture);
    }
}
