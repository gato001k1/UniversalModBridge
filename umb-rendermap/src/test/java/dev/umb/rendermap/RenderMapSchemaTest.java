package dev.umb.rendermap;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A light schema check on the real generated {@code hbm-render-map.json}: every row
 * {@link DynamicVariantResolver} added ({@code confidence:"inferred-dynamic"}) must carry the new
 * fields the schema promises (`resolvedBy`, `damage`) on top of every v1 field, and its
 * model/texture paths must exist on disk under {@code research/out/legacy/hbm-assets}. Self-skips
 * if the file has not been generated in this checkout (same pattern as
 * {@code TexturePickTest} in umb-objbridge), so it never fails a build that has not run
 * {@code tools/windows/run-rendermap.ps1} yet.
 */
class RenderMapSchemaTest {

    private static final Path MAP = Path.of("research/out/legacy/rendermap/hbm-render-map.json");
    private static final Path ASSET_ROOT = Path.of("research/out/legacy/hbm-assets");

    @Test
    void everyInferredDynamicRowCarriesResolvedByAndDamageAndRealAssetPaths() throws Exception {
        if (!Files.exists(MAP)) return; // not generated in this checkout - nothing to check
        JsonObject root;
        try (var r = Files.newBufferedReader(MAP, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(r).getAsJsonObject();
        }
        assertTrue(root.has("meta"));
        assertTrue(root.getAsJsonObject("meta").has("schemaVersion"));
        assertTrue(root.getAsJsonObject("meta").get("schemaVersion").getAsInt() >= 2);

        int checked = 0;
        for (var e : root.getAsJsonArray("items")) {
            JsonObject row = e.getAsJsonObject();
            if (!"inferred-dynamic".equals(str(row, "confidence"))) continue;
            checked++;
            assertNotNull(str(row, "id"), "row missing id");
            assertNotNull(str(row, "resolvedBy"), str(row, "id") + " missing resolvedBy");
            // Universality mandate: variant rows must be DERIVED from bytecode by the
            // damage-zero-branch discovery, never hand-maintained per-item overrides (the
            // old enum-table/array-table call sites are deleted; see DynamicVariantResolver).
            assertTrue(str(row, "resolvedBy").startsWith("damage-zero-branch (auto-discovered"),
                    str(row, "id") + " resolvedBy is not a derived branch: " + str(row, "resolvedBy"));
            assertTrue(row.has("damage") && !row.get("damage").isJsonNull(), str(row, "id") + " missing damage");
            // required v1 fields still present (schema stayed backward compatible)
            for (String k : new String[]{"className", "iconName", "rendererClass", "models", "textures",
                    "groups", "dynamic", "confidence"}) {
                assertTrue(row.has(k), str(row, "id") + " missing v1 field " + k);
            }
            assertTrue(row.getAsJsonArray("models").size() > 0, str(row, "id") + " has no model");
            assertTrue(row.getAsJsonArray("textures").size() > 0, str(row, "id") + " has no texture");
            for (String key : new String[]{"models", "textures"}) {
                for (var m : row.getAsJsonArray(key)) {
                    String assetPath = str(m.getAsJsonObject(), "assetPath");
                    assertNotNull(assetPath, str(row, "id") + " " + key + " entry has no assetPath");
                    assertTrue(Files.exists(ASSET_ROOT.resolve(assetPath)),
                            str(row, "id") + ": " + assetPath + " does not exist on disk");
                }
            }
        }
        assertTrue(checked >= 1, "expected at least one derived damage-zero-branch variant row, found " + checked);
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : null;
    }
}
