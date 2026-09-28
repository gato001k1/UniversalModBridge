package dev.umb.legacy1165.legacyside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;

import dev.umb.bridge.api.EntityRenderCapture;

import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;

/**
 * Contract tests for the 1.16.5 TESR capture seam. The recording buffer itself is shared with
 * the entity runner and covered by {@link LegacyEntityRenderCapture1165ClientTest}.
 */
class LegacyTileRenderCapture1165ClientTest {

    @Test
    void nullTileReturnsEmptyCapture() {
        EntityRenderCapture result = LegacyTileRenderCapture1165Client.capture(null, 0.0F);
        assertTrue(result.draws.isEmpty());
        assertEquals("1165-tesr-tile-null", result.stateKey);
    }

    @Test
    void missingDispatcherIsReportedNotThrown() throws Exception {
        Field field = LegacyTileRenderCapture1165Client.class.getDeclaredField("dispatcher");
        field.setAccessible(true);
        TileEntityRendererDispatcher saved = (TileEntityRendererDispatcher) field.get(null);
        try {
            LegacyTileRenderCapture1165Client.install(null);
            assertEquals("tesr-dispatcher-null", LegacyTileRenderCapture1165Client.unavailableReason());
        } finally {
            LegacyTileRenderCapture1165Client.install(saved);
        }
    }
}
