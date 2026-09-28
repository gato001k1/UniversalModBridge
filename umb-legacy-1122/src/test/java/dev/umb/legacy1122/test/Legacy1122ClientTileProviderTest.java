package dev.umb.legacy1122.test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import dev.umb.bridge.api.LegacyClientTileBridge;
import dev.umb.legacy1122.legacyside.Legacy1122ClientTileProvider;

class Legacy1122ClientTileProviderTest {
    @Test void providerIsEraScopedAndCoordinateBased() {
        Map<String, Object> tiles = new HashMap<>();
        Legacy1122ClientTileProvider.install(tiles);
        try {
            assertTrue(LegacyClientTileBridge.available("1.12.2"));
            assertTrue(LegacyClientTileBridge.diagnose("1.12.2", "overworld", 1, 2, 3)
                    .contains("provider=present"));
            assertNull(LegacyClientTileBridge.tileAt("1.12.2", 1, 2, 3));
            assertNull(LegacyClientTileBridge.tileAt("1.7.10", 1, 2, 3));
        } finally { Legacy1122ClientTileProvider.uninstall(); }
        assertNull(LegacyClientTileBridge.tileAt("1.12.2", 1, 2, 3));
    }
}
