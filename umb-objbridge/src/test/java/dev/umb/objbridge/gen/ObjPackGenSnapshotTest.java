package dev.umb.objbridge.gen;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code ObjPackGen.Snapshot} only records each item's base legacy id, but a render-map row
 * resolved to a per-metadata-variant id (see {@code dev.umb.rendermap.DynamicVariantResolver} in
 * umb-rendermap) must not be rejected as "not-in-snapshot". {@code Snapshot.collectItemVariants}
 * mirrors umb-hostagent's {@code LegacyIds.variantId} rule (read-only) from the snapshot's own
 * {@code subItems[]} so those ids are recognised too.
 */
class ObjPackGenSnapshotTest {

    private Path write(String json) throws Exception {
        Path f = Files.createTempFile("snap", ".json");
        Files.writeString(f, json);
        return f;
    }

    @Test
    void readableVariantIdsAreAddedWhenEverySubItemHasADistinctUnlocalizedName() throws Exception {
        Path f = write("""
                { "items": [ { "id": "hbm:item.battery_pack", "unlocalizedName": "item.battery_pack",
                    "subItems": [
                      { "damage": 0, "unlocalizedName": "item.battery_pack.battery_redstone" },
                      { "damage": 1, "unlocalizedName": "item.battery_pack.battery_lead" }
                    ] } ], "blocks": [] }
                """);
        ObjPackGen.Snapshot snap = ObjPackGen.Snapshot.read(f);
        assertTrue(snap.itemIds().contains("hbm:item.battery_pack.battery_redstone"));
        assertTrue(snap.itemIds().contains("hbm:item.battery_pack.battery_lead"));
        // itemIds is a superset used only to accept ids as valid: collect() also keeps the plain
        // base id (harmless - once readable, no resolver targets it, and DynamicVariantResolver
        // removes any pre-existing render-map row keyed by it).
    }

    @Test
    void numericSuffixIdsAreAddedWhenNamesCollideWithTheBase() throws Exception {
        Path f = write("""
                { "items": [ { "id": "hbm:item.gear_large", "unlocalizedName": "item.gear_large",
                    "subItems": [
                      { "damage": 0, "unlocalizedName": "item.gear_large" },
                      { "damage": 1, "unlocalizedName": "item.gear_large" }
                    ] } ], "blocks": [] }
                """);
        ObjPackGen.Snapshot snap = ObjPackGen.Snapshot.read(f);
        assertTrue(snap.itemIds().contains("hbm:item.gear_large"));   // meta 0 keeps the base id
        assertTrue(snap.itemIds().contains("hbm:item.gear_large_1"));
    }

    @Test
    void singleSubItemDoesNotTriggerExpansion() throws Exception {
        Path f = write("""
                { "items": [ { "id": "hbm:item.acetylene_torch", "unlocalizedName": "item.acetylene_torch",
                    "subItems": [ { "damage": 0, "unlocalizedName": "item.acetylene_torch" } ] } ],
                  "blocks": [] }
                """);
        ObjPackGen.Snapshot snap = ObjPackGen.Snapshot.read(f);
        assertEquals(1, snap.itemIds().size());
        assertTrue(snap.itemIds().contains("hbm:item.acetylene_torch"));
    }

}
