package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.umb.legacy.api.EntityEntry;
import dev.umb.legacy.api.FluidEntry;
import dev.umb.legacy.api.ModEntry;
import dev.umb.legacy.api.NamedEntry;
import dev.umb.legacy.api.RegistrySnapshot;
import dev.umb.legacy.api.StageResult;
import dev.umb.legacy.api.TabEntry;
import dev.umb.legacy.api.TileEntityEntry;
import dev.umb.legacy.boot.Json;

/** The snapshot writer has to survive mod-supplied strings, so it gets a hostile fixture. */
class JsonSnapshotTest {

    @Test
    void escapesEverythingJsonCannotCarryRaw() {
        assertEquals("\"a\\\"b\"", Json.escape("a\"b"));
        assertEquals("\"a\\\\b\"", Json.escape("a\\b"));
        assertEquals("\"l1\\nl2\"", Json.escape("l1\nl2"));
        assertEquals("\"t\\tt\"", Json.escape("t\tt"));
        assertEquals("\"\\u0000\"", Json.escape("\u0000"));
        assertEquals("null", Json.escape(null));
        // the FML registry discriminators are control characters and DO show up in registry keys
        assertEquals("\"\\u0001\"", Json.escape("\u0001"));
    }

    @Test
    void writesEveryRegistrySectionAndTheCounts() {
        Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        counts.put("blocksByNs:hbm", 971);
        counts.put("itemsByNs:hbm", 2779);

        RegistrySnapshot s = new RegistrySnapshot("1.7.10", "10.13.4.1614", "7.99.40.1614", "SERVER",
                Arrays.asList(new ModEntry("hbm", "Hbm's \"Nuclear\" Tech", "1.0.27", "HBM.jar", "LOADED")),
                Arrays.asList(new NamedEntry("hbm:block\u0001x", 3000, "com.hbm.blocks.BlockA", "tile.a", "tabHbm")),
                Arrays.asList(new NamedEntry("minecraft:stone", 1, "net.minecraft.block.Block", "tile.stone", null)),
                Arrays.asList(new TabEntry(0, "tabHbm", "com.hbm.tabs.T", "itemGroup.tabHbm", "hbm")),
                Arrays.asList(new TileEntityEntry("hbm:reactor", "com.hbm.tileentity.TileEntityReactor", "hbm")),
                Arrays.asList("ingotUranium", "dustLead"),
                Arrays.asList(new FluidEntry("uf6", "com.hbm.fluid.F", "fluid.uf6", 0, 3000, 300, 1000, true)),
                Arrays.asList(new EntityEntry("hbm", "entity_nuke", "com.hbm.entity.EntityNuke", 107, 64, 1, true)),
                counts);

        List<StageResult> stages = Arrays.asList(
                StageResult.ok("CONSTRUCTING", 1200),
                new StageResult("PREINIT", false, 90, "java.lang.NullPointerException", "boom", "at x\nat y"));

        String json = Json.snapshot(s, stages, Collections.singletonMap("javaVersion", "25.0.4"));

        assertTrue(json.startsWith("{\n"), json.substring(0, Math.min(20, json.length())));
        assertTrue(json.contains("\"mc\": \"1.7.10\""));
        assertTrue(json.contains("\"forge\": \"10.13.4.1614\""));
        assertTrue(json.contains("\"side\": \"SERVER\""));
        assertTrue(json.contains("\"javaVersion\": \"25.0.4\""));
        assertTrue(json.contains("\"blocksByNs:hbm\": 971"));
        assertTrue(json.contains("\"itemsByNs:hbm\": 2779"));
        assertTrue(json.contains("Hbm's \\\"Nuclear\\\" Tech"));
        assertTrue(json.contains("hbm:block\\u0001x"));
        assertTrue(json.contains("\"creativeTabs\": ["));
        assertTrue(json.contains("\"tileEntities\": ["));
        assertTrue(json.contains("\"fluids\": ["));
        assertTrue(json.contains("\"oreDictionary\": ["));
        assertTrue(json.contains("\"entities\": ["));
        assertTrue(json.contains("\"modEntityId\": 107"));
        assertTrue(json.contains("\"stage\": \"PREINIT\", \"ok\": false"));
        assertTrue(json.contains("\"gaseous\": true"));
        // a failed stage must not smuggle a raw newline into the document
        assertFalse(json.contains("at x\nat y"));
        assertBalanced(json);
    }

    /** Cheap structural check: braces/brackets balance outside of strings. */
    private static void assertBalanced(String json) {
        int curly = 0;
        int square = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"': inString = true; break;
                case '{': curly++; break;
                case '}': curly--; break;
                case '[': square++; break;
                case ']': square--; break;
                default: break;
            }
            assertTrue(curly >= 0 && square >= 0, "unbalanced at " + i);
        }
        assertEquals(0, curly, "unbalanced braces");
        assertEquals(0, square, "unbalanced brackets");
        assertFalse(inString, "unterminated string");
    }
}
