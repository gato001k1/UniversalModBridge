package dev.umb.objbridge.entity;

import dev.umb.objbridge.ObjBridge;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyEntityVisualTest {
    @Test
    void countsObjAndModelBaseBindingsAndSkipsMissingRows() throws Exception {
        Path root = Files.createTempDirectory("umb-entity-map-");
        Path map = root.resolve("map.json");
        Files.writeString(map, "{\"entities\":[" +
                "{\"entityClass\":\"a.Obj\",\"models\":[{\"path\":\"hbm:models/a.obj\"}],\"entityTexture\":[{\"path\":\"hbm:textures/models/a.png\"}]} ," +
                "{\"entityClass\":\"a.Box\",\"javaModels\":[{\"parts\":[{\"box\":{\"x\":0,\"y\":0,\"z\":0,\"w\":1,\"h\":1,\"d\":1}}]}],\"entityTexture\":[{\"path\":\"hbm:textures/models/a.png\"}]} ," +
                "{\"entityClass\":\"a.Skip\"}]}" );
        ObjBridge.configure(root, map, null);
        LegacyEntityVisual.clear();
        assertEquals(3, LegacyEntityVisual.loadedCount());
        assertEquals(2, LegacyEntityVisual.drawableCount());
        assertEquals(1, LegacyEntityVisual.skippedCount());
        assertEquals(1, LegacyEntityVisual.all().get("a.Box").boxes().size());
    }
}
