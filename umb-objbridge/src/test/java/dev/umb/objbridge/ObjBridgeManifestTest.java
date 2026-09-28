package dev.umb.objbridge;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ObjBridgeManifestTest {
    @Test
    void loadsTwoOwnedMapsAndKeepsTheirRootsDistinct() throws Exception {
        Path root = Files.createTempDirectory("umb-manifest-");
        Path hmap = root.resolve("hbm.json");
        Path mmap = root.resolve("mcheli.json");
        Files.writeString(hmap, "{\"items\":[{\"id\":\"hbm:reactor\"}]}");
        Files.writeString(mmap, "{\"items\":[{\"id\":\"mcheli:heli\"}]}");
        Path manifest = root.resolve("manifest.json");
        Files.writeString(manifest, "{\"mods\":[" +
                "{\"namespace\":\"hbm\",\"rendermap\":\"hbm.json\",\"assets\":\"hbm-assets\"}," +
                "{\"namespace\":\"mcheli\",\"rendermap\":\"mcheli.json\",\"assets\":\"mcheli-assets\"}]}" );

        var mods = ObjBridgeManifest.load(manifest);
        assertEquals(2, mods.size());
        assertEquals("hbm", mods.get(0).mod().namespace());
        assertEquals("mcheli:heli", mods.get(1).renderMap().items().get(0).id());
        assertEquals(root.resolve("mcheli-assets"), mods.get(1).mod().assetsRoot());
    }

    @Test
    void rejectsRowOwnedByAnotherManifestMod() throws Exception {
        Path root = Files.createTempDirectory("umb-manifest-bad-");
        Files.writeString(root.resolve("map.json"), "{\"items\":[{\"id\":\"hbm:foreign\"}]}");
        Path manifest = root.resolve("manifest.json");
        Files.writeString(manifest, "{\"mods\":[{\"namespace\":\"mcheli\",\"rendermap\":\"map.json\",\"assets\":\"assets\"}]}" );
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> ObjBridgeManifest.load(manifest));
        assertTrue(ex.getMessage().contains("mcheli") && ex.getMessage().contains("hbm:foreign"));
    }

    @Test
    void acceptsMixedCaseLegacyNamespace() throws Exception {
        Path root = Files.createTempDirectory("umb-manifest-railcraft-");
        Files.writeString(root.resolve("railcraft.json"),
                "{\"blocks\":[{\"id\":\"Railcraft:tile.railcraft.detector\"}]}" );
        Path manifest = root.resolve("manifest.json");
        Files.writeString(manifest, "{\"mods\":[{\"namespace\":\"Railcraft\","
                + "\"rendermap\":\"railcraft.json\",\"assets\":\"assets\"}]}" );

        var mods = ObjBridgeManifest.load(manifest);
        assertEquals("Railcraft", mods.get(0).mod().namespace());
        assertEquals("Railcraft:tile.railcraft.detector",
                mods.get(0).renderMap().blocks().get(0).id());
    }
}
