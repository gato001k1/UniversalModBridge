package dev.umb.objbridge.entity;

import dev.umb.bridge.api.EntityRenderCapture;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** The provider seam must route an era-owned handle, not assume the 1.7.10 raw-tile class. */
class LegacyTileCaptureClientTest {
    @Test
    void eraHandleCaptureReachesTheGenericRenderCaptureSeam() {
        EntityRenderCapture expected = new EntityRenderCapture(
                "dev.umb.legacy1122.legacyside.TileHandle1122", "ironchest", true, 0, 0, 0,
                java.util.List.of(EntityRenderCapture.Draw.owned(
                        "ironchest1122:textures/iron_chest.png",
                        new float[]{0, 0, 0, 0, 0, 0, 1, 0,
                                1, 0, 0, 1, 0, 0, 1, 0,
                                1, 1, 0, 1, 1, 0, 1, 0,
                                0, 1, 0, 0, 1, 0, 1, 0}, 4, null)));
        Object result = LegacyTileCaptureClient.captureClientTile(new EraHandle(expected), 0.5f);
        assertNotNull(result);
        EntityRenderCapture capture = (EntityRenderCapture) result;
        assertEquals(1, capture.draws.size());
        assertEquals(4, capture.vertexCount());
        assertEquals("ironchest1122:textures/iron_chest.png", capture.draws.get(0).texture);
    }

    private static final class EraHandle {
        private final EntityRenderCapture capture;
        EraHandle(EntityRenderCapture capture) { this.capture = capture; }
        public EntityRenderCapture renderCapture(float partialTick) { return capture; }
    }
}
