package dev.umb.hostagent;

import dev.umb.hostagent.content.ModContentManifest;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ManifestContentTest {
    @Test void parsesTwoIndependentContentRecords() {
        ModContentManifest m = ModContentManifest.parse(new com.google.gson.Gson().fromJson(
                "{\"mods\":[{\"namespace\":\"HBM\",\"snapshot\":\"h.json\",\"blockShapes\":\"h-shapes.json\"},"
                + "{\"namespace\":\"mcheli\",\"snapshot\":\"m.json\",\"guiProfile\":\"m-gui.json\"}]}" ,
                com.google.gson.JsonElement.class));
        assertEquals(2, m.records().size());
        assertEquals("hbm", m.records().get(0).namespace());
        assertEquals(Path.of("m-gui.json"), m.records().get(1).guiProfile());
    }

    @Test void duplicateNamespaceFailsNamingOwner() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () ->
                ModContentManifest.parse(new com.google.gson.Gson().fromJson(
                        "{\"mods\":[{\"namespace\":\"a\",\"snapshot\":\"1\"},{\"namespace\":\"A\",\"snapshot\":\"2\"}]}" ,
                        com.google.gson.JsonElement.class)));
        assertTrue(e.getMessage().contains("duplicate manifest namespace owner"));
    }

    @Test void eraDefaultsTo1710AndParsesExplicit() {
        ModContentManifest m = ModContentManifest.parse(new com.google.gson.Gson().fromJson(
                "{\"mods\":[{\"namespace\":\"hbm\",\"snapshot\":\"h.json\"},"
                + "{\"namespace\":\"ironchest\",\"snapshot\":\"i.json\",\"era\":\"1.16.5\"},"
                + "{\"namespace\":\"blank\",\"snapshot\":\"b.json\",\"era\":\"\"}]}" ,
                com.google.gson.JsonElement.class));
        assertEquals("1.7.10", m.records().get(0).era());
        assertEquals("1.16.5", m.records().get(1).era());
        assertEquals("1.7.10", m.records().get(2).era());
    }
}
