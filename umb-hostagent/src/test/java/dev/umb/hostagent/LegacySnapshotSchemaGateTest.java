package dev.umb.hostagent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.umb.hostagent.content.LegacySnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Build gate for the on-disk contract consumed by LegacySnapshot. */
class LegacySnapshotSchemaGateTest {

    private static final List<Fixture> ERAS = List.of(
            new Fixture("research/out/legacy/hbm-snapshot.json", "hbm"),
            new Fixture("research/out/legacy-1165/ironchest-1165-snapshot.json", "ironchest"),
            new Fixture("research/out/legacy-1122/ironchest-1122-snapshot.json", "ironchest1122"));

    private static final Set<String> BLOCK_FIELDS = Set.of(
            "id", "numericId", "className", "unlocalizedName", "displayName", "error",
            "material", "mapColor", "hardness", "resistance", "unbreakable", "lightValue",
            "lightOpacity", "opaqueCube", "renderAsNormalBlock", "creativeTab", "harvestTool",
            "harvestLevel", "stepSound", "slipperiness", "textureName", "hasTileEntity",
            "tileEntityClass", "renderType", "tickRandomly", "icons", "sides", "iconRows",
            "itemBlockClass", "subBlocks");
    private static final Set<String> ITEM_FIELDS = Set.of(
            "id", "numericId", "className", "unlocalizedName", "displayName", "error",
            "creativeTab", "textureName", "iconName", "maxStackSize", "maxDamage", "hasSubtypes",
            "isBlockItem", "isFood", "subItems", "subItemsTotal", "truncated");

    @Test
    void everyEraLoadsWithNonEmptyRegistriesAndRequiredIds() throws Exception {
        for (Fixture fixture : ERAS) {
            Path path = Path.of(fixture.path);
            Assumptions.assumeTrue(Files.isRegularFile(path),
                    "legacy snapshot corpus not present in this checkout: " + path);
            JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray blocks = requiredArray(root, "blocks", fixture.path);
            JsonArray items = requiredArray(root, "items", fixture.path);
            assertFalse(blocks.isEmpty(), "empty blocks snapshot: " + fixture.path);
            assertFalse(items.isEmpty(), "empty items snapshot: " + fixture.path);
            for (JsonElement element : blocks) requireId(element, fixture.path, "blocks");
            for (JsonElement element : items) requireId(element, fixture.path, "items");

            LegacySnapshot snapshot = LegacySnapshot.load(path, fixture.namespace);
            assertFalse(snapshot.blocks.isEmpty(), "LegacySnapshot loaded no blocks: " + fixture.path);
            assertFalse(snapshot.items.isEmpty(), "LegacySnapshot loaded no items: " + fixture.path);
        }
    }

    @Test
    void twelveTwoUsesTheFullCrossEraRecordShape() throws Exception {
        Path path = Path.of("research/out/legacy-1122/ironchest-1122-snapshot.json");
        Assumptions.assumeTrue(Files.isRegularFile(path),
                "1.12.2 legacy snapshot not present in this checkout: " + path);
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
        for (JsonElement element : root.getAsJsonArray("blocks")) assertFields(element, BLOCK_FIELDS, "blocks");
        for (JsonElement element : root.getAsJsonArray("items")) assertFields(element, ITEM_FIELDS, "items");
    }

    private static JsonArray requiredArray(JsonObject root, String key, String path) {
        assertTrue(root.has(key) && root.get(key).isJsonArray(), path + " missing array " + key);
        return root.getAsJsonArray(key);
    }

    private static void requireId(JsonElement element, String path, String kind) {
        assertTrue(element.isJsonObject(), path + " has non-object " + kind + " record");
        JsonObject object = element.getAsJsonObject();
        assertTrue(object.has("id") && object.get("id").isJsonPrimitive()
                        && !object.get("id").getAsString().isBlank(),
                path + " has a " + kind + " record without a non-empty id: " + object);
        assertFalse(object.has("name"), path + " retains legacy name field: " + object);
    }

    private static void assertFields(JsonElement element, Set<String> fields, String kind) {
        JsonObject object = element.getAsJsonObject();
        for (String field : fields) assertTrue(object.has(field), kind + " record missing field " + field + ": " + object);
    }

    private record Fixture(String path, String namespace) {}
}
