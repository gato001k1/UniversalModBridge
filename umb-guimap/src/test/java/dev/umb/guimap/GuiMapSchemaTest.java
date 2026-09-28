package dev.umb.guimap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A light schema check on the real generated {@code gui-profile.json} (same self-skip pattern as
 * umb-rendermap's {@code RenderMapSchemaTest} / umb-objbridge's {@code TexturePickTest}): every
 * GUI row must carry the fields the schema promises, every resolved background texture that
 * claims to exist in the jar must be reachable at its assetPath, and every drawTexturedModalRect
 * row must be classified either static or dynamic. Self-skips if {@code tools/run-guimap.ps1}
 * has not been run in this checkout.
 */
class GuiMapSchemaTest {

    private static final Path OUT = Path.of("research/out/legacy/gui-profile.json");

    @Test
    void everyGuiRowCarriesTheSchemaFieldsAndResolvedAssetsExistOnDisk() throws Exception {
        if (!Files.exists(OUT)) return; // not generated in this checkout - nothing to check
        JsonObject root;
        try (var r = Files.newBufferedReader(OUT, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        assertTrue(root.has("meta"));
        assertTrue(root.getAsJsonObject("meta").has("schemaVersion"));
        assertTrue(root.has("coverage"));
        assertTrue(root.has("guis"));

        int checked = 0;
        for (var e : root.getAsJsonArray("guis")) {
            JsonObject row = e.getAsJsonObject();
            checked++;
            for (String k : new String[]{"className", "superClass", "kind", "size", "container"}) {
                assertTrue(row.has(k), row.get("className") + " missing field " + k);
            }
            JsonObject size = row.getAsJsonObject("size");
            assertTrue(size.has("confidence"), row.get("className") + " size missing confidence");
            JsonObject container = row.getAsJsonObject("container");
            assertTrue(container.has("confidence"), row.get("className") + " container missing confidence");

            for (String key : new String[]{"backgroundTextures"}) {
                if (!row.has(key)) continue;
                for (var t : row.getAsJsonArray(key)) {
                    JsonObject to = t.getAsJsonObject();
                    assertTrue(to.has("resolved"));
                    if (to.get("resolved").getAsBoolean() && to.has("existsInJar") && to.get("existsInJar").getAsBoolean()) {
                        assertTrue(to.has("assetPath"), row.get("className") + " resolved+exists texture missing assetPath");
                    }
                }
            }
            for (String key : new String[]{"backgroundDrawRects", "foregroundDrawRects"}) {
                if (!row.has(key)) continue;
                for (var rc : row.getAsJsonArray(key)) {
                    JsonObject rco = rc.getAsJsonObject();
                    assertTrue(rco.has("dynamic"), row.get("className") + " draw rect missing dynamic flag");
                    assertEquals(6, rco.getAsJsonArray("args").size());
                }
            }
        }
        assertTrue(checked > 0, "expected at least one GUI row in " + OUT);

        JsonObject cov = root.getAsJsonObject("coverage");
        assertEquals(checked, cov.get("totalGuiClasses").getAsInt());
    }
}
