package dev.umb.objbridge.entity;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LegacyBlockVisualTest {
    @Test
    void shapeOnlyNativeTwinResolvesThroughItsBaseLegacyId() {
        LegacyBlockVisual base = new LegacyBlockVisual(
                "hbm:tile.machine_flare", "test.Renderer", "hbm:models/machine.obj",
                "hbm:textures/models/machine.png", List.of("Body"));
        Map<String, LegacyBlockVisual> visuals = Map.of(base.blockId(), base);

        assertEquals(base, LegacyBlockVisual.find(visuals,
                "hbm:tile.machine_flare_13", "hbm:tile.machine_flare"));
        assertEquals(base, LegacyBlockVisual.find(visuals,
                "hbm:tile.machine_flare", "hbm:tile.machine_flare"));
        assertNull(LegacyBlockVisual.find(visuals,
                "hbm:tile.unknown_13", "hbm:tile.unknown"));
    }
}
