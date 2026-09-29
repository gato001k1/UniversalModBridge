package dev.umb.hostagent.content;

import dev.umb.bridge.api.StackData;
import dev.umb.hostagent.HostAgent;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2-vanilla-bridge gate: {@link VanillaItemBridge} + its wiring into {@link LegacyStackConv}.
 *
 * items -- {@code toLegacy(minecraft:coal)} produced {@code StackData.EMPTY}, which is exactly why
 * "ERROR LegacyStackConv.toLegacy: no legacy id for native item minecraft:oak_log/coal_block/iron_ore").
 * {@link #coalRoundTripsNativeLegacyNative()} below is that exact regression, pinned down: run
 * against the pre-fix code (LegacyStackConv without the VanillaItemBridge lookups) it fails with
 * "expected: <false> but was: <true>" on the isEmpty() assertion -- confirmed manually before
 * wiring the fix in (see g2-integration-progress.md for the transcript).
 */
class VanillaItemBridgeTest {

    private static final Path SNAPSHOT = Paths.get("research/out/legacy/hbm-snapshot.json");
    private static final String HBM_TEST_ID = "hbm:test_vanilla_bridge_marker";

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
        Assumptions.assumeTrue(Files.isRegularFile(SNAPSHOT),
                "hbm-snapshot.json not present in this checkout: " + SNAPSHOT);
        // Force a REAL build from the actual snapshot regardless of whatever state an earlier
        // test class in this same JVM (JUnit --scan-class-path runs every test class in one JVM)
        // already left VanillaItemBridge in -- see resetForTest()'s javadoc.
        HostAgent.configure(SNAPSHOT, null, "hbm");
        VanillaItemBridge.resetForTest();
        VanillaItemBridge.ensureBuilt();

        Items.COAL.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        Items.CHARCOAL.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        Items.IRON_ORE.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        Items.NETHERITE_INGOT.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        Items.NETHERITE_SCRAP.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);

        // A legacy-registered (HBM-side) item, exactly like Registrar.run() would populate from a
        // real snapshot -- Items.NETHERITE_SCRAP stands in for it so the "hbm:* still works unchanged"
        // assertion below exercises Registrar's own maps, not VanillaItemBridge's.
        Registrar.LEGACY_ITEMS.put(HBM_TEST_ID, Items.NETHERITE_SCRAP);
        Registrar.LEGACY_VARIANT_ITEMS.put(HBM_TEST_ID + "@0", Items.NETHERITE_SCRAP);
        // see resetLegacyVariantKeyCacheForTest()'s javadoc: an earlier test class in this same
        // JVM may already have cached Registrar's reverse lookup without this fixture entry.
        Registrar.resetLegacyVariantKeyCacheForTest();
    }

    @Test
    void identitySetSizeIsPinnedSoASnapshotOrRegistryRegressionIsCaught() {
        // 26.2 jar upgrade that renames/removes one of the 26.2 ids VanillaItemBridge depends on
        // -- this fails LOUDLY instead of silently mis-converting items.
        assertEquals(315, VanillaItemBridge.totalCount, "total minecraft:* records in the snapshot");
        assertEquals(237, VanillaItemBridge.identityCount, "ids that are STILL the same string on 26.2");
        assertEquals(64, VanillaItemBridge.renamedCount, "base ids that needed RENAME_TABLE for their primary variant");
        assertEquals(14, VanillaItemBridge.unmappedCount, "ids left deliberately unmapped (see VanillaItemBridge javadoc)");
    }

    @Test
    void coalRoundTripsNativeLegacyNative() {
        // match (the 1.7.10 id string is still "minecraft:coal" on 26.2), no RENAME_TABLE entry
        // needed for damage 0.
        ItemStack native1 = new ItemStack(Items.COAL, 37);

        StackData legacy = LegacyStackConv.toLegacy(native1);
        assertTrue(!legacy.isEmpty(), "minecraft:coal must now cross into the legacy universe");
        assertEquals("minecraft:coal", legacy.legacyId);
        assertEquals(37, legacy.count);
        assertEquals(0, legacy.damage);

        ItemStack native2 = LegacyStackConv.toNative(legacy);
        assertSame(Items.COAL, native2.getItem(), "round trip must land back on the exact same native item");
        assertEquals(37, native2.getCount(), "count must survive the round trip");
    }

    @Test
    void charcoalRoundTripsThroughTheRenameTable() {
        // minecraft:coal@1 (Charcoal in 1.7.10) is exactly the RENAME_TABLE case: the base id
        // "minecraft:coal" is NOT charcoal's native id on 26.2 -- it needed its own row.
        ItemStack native1 = new ItemStack(Items.CHARCOAL, 5);

        StackData legacy = LegacyStackConv.toLegacy(native1);
        assertTrue(!legacy.isEmpty());
        assertEquals("minecraft:coal", legacy.legacyId, "1.7.10 charcoal is metadata on the coal item, not its own id");
        assertEquals(1, legacy.damage);

        ItemStack native2 = LegacyStackConv.toNative(legacy);
        assertSame(Items.CHARCOAL, native2.getItem());
        assertEquals(5, native2.getCount());
    }

    @Test
    void ironOreRoundTripsNativeLegacyNative() {
        // identity match (still "minecraft:iron_ore" on 26.2 -- the ORE BLOCK's id never changed;
        // only what it DROPS when mined/smelted changed, which is irrelevant to stack conversion).
        ItemStack native1 = new ItemStack(Items.IRON_ORE, 12);

        StackData legacy = LegacyStackConv.toLegacy(native1);
        assertTrue(!legacy.isEmpty());
        assertEquals("minecraft:iron_ore", legacy.legacyId);
        assertEquals(12, legacy.count);

        ItemStack native2 = LegacyStackConv.toNative(legacy);
        assertSame(Items.IRON_ORE, native2.getItem());
        assertEquals(12, native2.getCount());
    }

    @Test
    void hbmItemStillRoundTripsUnchangedThroughRegistrarNotVanillaItemBridge() {
        ItemStack native1 = new ItemStack(Items.NETHERITE_SCRAP, 3);

        StackData legacy = LegacyStackConv.toLegacy(native1);
        assertTrue(!legacy.isEmpty());
        assertEquals(HBM_TEST_ID, legacy.legacyId);
        assertEquals(3, legacy.count);

        ItemStack native2 = LegacyStackConv.toNative(legacy);
        assertSame(Items.NETHERITE_SCRAP, native2.getItem());
        assertEquals(3, native2.getCount());
    }

    @Test
    void anItemWithNoMappingYieldsEmptyOnTheLegacySideAndOnTheNativeSide() {
        // Netherite postdates 1.7.10 by years -- it can never appear in the snapshot, so it stays
        // unmapped by both Registrar and VanillaItemBridge regardless of snapshot content.
        ItemStack native1 = new ItemStack(Items.NETHERITE_INGOT, 1);

        StackData legacy = LegacyStackConv.toLegacy(native1);
        assertTrue(legacy.isEmpty(), "an item neither Registrar nor VanillaItemBridge knows must convert to EMPTY");

        ItemStack backAgain = LegacyStackConv.toNative(StackData.EMPTY);
        assertTrue(backAgain.isEmpty(), "converting StackData.EMPTY must never fabricate a real item");

        // The actual "does not erase a real held item" guard lives in HostPlayerImpl -- see
        // HostPlayerImplInventoryRoundTripTest.pushingBackAnUnconvertibleEmptyDoesNotEraseARealUnmappableItem,
        // which now uses this exact item for that reason.
        assertEquals(false, LegacyStackConv.isKnownLegacyItem(Items.NETHERITE_INGOT));
    }

    @Test
    void vanillaBridgeNeverRegressesAKnownHbmMapping() {
        // Registrar is consulted BEFORE VanillaItemBridge in both toLegacy and toNative -- a
        // modded namespace never collides with "minecraft:", but this pins the ordering anyway.
        StackData s = LegacyStackConv.toLegacy(new ItemStack(Items.NETHERITE_SCRAP, 1));
        assertEquals(HBM_TEST_ID, s.legacyId);
    }
}
