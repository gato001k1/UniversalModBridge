package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;

import dev.umb.bridge.api.LegacyClientTileBridge;

/** Verifies the parent-delegated client-tile handoff used across the legacy/host loaders. */
class LegacyClientTileBridgeTest {
    @AfterEach
    void clearProvider() {
        LegacyClientTileBridge.install(null);
    }

    @Test
    void opaqueProviderSurvivesSharedBoundary() {
        assertFalse(LegacyClientTileBridge.available());
        Object tile = new Object();
        LegacyClientTileBridge.install((x, y, z) -> x == 4 && y == 5 && z == 6 ? tile : null);
        assertTrue(LegacyClientTileBridge.available());
        assertEquals(tile, LegacyClientTileBridge.tileAt(4, 5, 6));
        assertEquals(null, LegacyClientTileBridge.tileAt(4, 5, 7));
    }

    @Test
    void providerExceptionsAreContained() {
        LegacyClientTileBridge.install((x, y, z) -> { throw new IllegalStateException("test"); });
        assertEquals(null, LegacyClientTileBridge.tileAt(0, 0, 0));
    }

    @Test
    void providersAreScopedByEraAndExposeDiagnostics() {
        Object tile = new Object();
        LegacyClientTileBridge.install("1.7.10", new LegacyClientTileBridge.Provider() {
            @Override public Object tileAt(int x, int y, int z) {
                return x == 1 && y == 2 && z == 3 ? tile : null;
            }
            @Override public String diagnose(String dimension, int x, int y, int z) {
                return "tiles=1 requested=" + x + "," + y + "," + z;
            }
        });
        assertTrue(LegacyClientTileBridge.available("1.7.10"));
        assertFalse(LegacyClientTileBridge.available("1.12.2"));
        assertSame(tile, LegacyClientTileBridge.tileAt("1.7.10", 1, 2, 3));
        assertEquals(null, LegacyClientTileBridge.tileAt("1.12.2", 1, 2, 3));
        assertTrue(LegacyClientTileBridge.diagnose("1.7.10", "overworld", 1, 2, 3)
                .contains("tiles=1"));
        assertTrue(LegacyClientTileBridge.diagnose("1.12.2", "overworld", 1, 2, 3)
                .contains("provider=not-installed"));
    }

    @Test
    void childLegacyLoaderSeesTheParentBoundaryClass() throws Exception {
        try (URLClassLoader child = new URLClassLoader(new URL[0],
                LegacyClientTileBridge.class.getClassLoader())) {
            assertSame(LegacyClientTileBridge.class,
                    Class.forName(LegacyClientTileBridge.class.getName(), false, child));
        }
    }
}
