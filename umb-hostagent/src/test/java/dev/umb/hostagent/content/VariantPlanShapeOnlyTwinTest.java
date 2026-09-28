package dev.umb.hostagent.content;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shape-variant lane (research/out/legacy/multiblock-notes/SHAPE-VARIANT-LANE.md): closes the gap
 * MULTIBLOCK-LANE.md section 4 flagged - a real multiblock controller's out-of-cell shape lives
 * exclusively on metadata the mod's own placement code writes at runtime (e.g. a "core is now
 * active" tier), never on a {@code subBlocks} entry, so {@link VariantPlan#addBlocks} used to
 * never register a twin for it at all and the shape fix from the previous lane stayed dormant on
 * the real corpus.
 *
 * <p>In package {@code dev.umb.hostagent.content} (not {@code dev.umb.hostagent}, unlike
 * {@code VariantPlanTest}) specifically so it can call the same package-private
 * {@link TestSupport#ensureBootstrapped()} {@link BlockShapesTest} uses before ever building a
 * real {@link net.minecraft.world.phys.shapes.VoxelShape} - {@link VariantPlan#addShapeOnlyTwins}
 * now does exactly that.</p>
 */
class VariantPlanShapeOnlyTwinTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
    }

    // hbm:tile.reactor_core - a single meta-0 subBlocks entry (no real subBlocks variant group at
    // all), but block-shapes.json-style data giving meta 6 a genuinely different, out-of-cell box.
    // hbm:tile.multi_variant - a REAL 3-entry subBlocks group (metas 0,1,2) sharing one
    // unlocalizedName (numeric ids, exactly like the real hbm:tile.bobblehead case), PLUS an
    // out-of-cell shape at meta 8 that is NOT in subBlocks, and a meta 1 shape that genuinely
    // differs from meta 0's too (proving coveredMetas suppresses a twin there even though the
    // geometry alone would otherwise qualify).
    // hbm:tile.uniform - one shape shared by every meta 0..15 (the ordinary case: nothing to add).
    private static final String SNAPSHOT_JSON = """
        {
          "blocks": [
            {"id":"hbm:tile.reactor_core","unlocalizedName":"tile.reactor_core",
             "displayName":"Reactor Core","hardness":5.0,"resistance":10.0,
             "creativeTab":"tabBlocks","stepSound":"stone",
             "subBlocks":[{"meta":0,"unlocalizedName":"tile.reactor_core","displayName":"Reactor Core"}]},
            {"id":"hbm:tile.multi_variant","unlocalizedName":"tile.multi_variant",
             "displayName":"Multi Variant","hardness":5.0,"resistance":10.0,
             "creativeTab":"tabBlocks","stepSound":"stone",
             "subBlocks":[
               {"meta":0,"unlocalizedName":"tile.multi_variant","displayName":"Multi Variant"},
               {"meta":1,"unlocalizedName":"tile.multi_variant","displayName":"Multi Variant"},
               {"meta":2,"unlocalizedName":"tile.multi_variant","displayName":"Multi Variant"}]},
            {"id":"hbm:tile.uniform","unlocalizedName":"tile.uniform","displayName":"Uniform",
             "hardness":3.0,"resistance":5.0,"creativeTab":"tabBlocks","stepSound":"stone",
             "subBlocks":[{"meta":0,"unlocalizedName":"tile.uniform","displayName":"Uniform"}]}
          ],
          "items": []
        }
        """;

    private static final String SHAPES_JSON = """
        {
          "blocks": [
            {"id":"hbm:tile.reactor_core","metaGroups":[
               {"metas":[0],"collisionAabb":[0,0,0,1,1,1],"isFullCube":false},
               {"metas":[6],"collisionAabb":[0,0,0,1,4,1],"isFullCube":false}
            ]},
            {"id":"hbm:tile.multi_variant","metaGroups":[
               {"metas":[0],"collisionAabb":[0,0,0,1,1,1],"isFullCube":false},
               {"metas":[1],"collisionAabb":[0,0,0,1,0.5,1],"isFullCube":false},
               {"metas":[2],"collisionAabb":[0,0,0,1,1,1],"isFullCube":false},
               {"metas":[8],"collisionAabb":[0,0,0,1,4,1],"isFullCube":false}
            ]},
            {"id":"hbm:tile.uniform","metaGroups":[
               {"metas":[0,1,2,3,4,5,6,7,8,9,10,11,12,13,14,15],
                "collisionAabb":[0,0,0,1,0.75,1],"isFullCube":false}
            ]}
          ]
        }
        """;

    private static VariantPlan plan() {
        return VariantPlan.build(LegacySnapshot.parseString(SNAPSHOT_JSON, "hbm"), LangTable.empty(),
                BlockShapeProfile.parseString(SHAPES_JSON));
    }

    private static VariantPlan.BlockEntry block(VariantPlan p, String legacyId) {
        for (VariantPlan.BlockEntry e : p.blocks) if (e.legacyId.equals(legacyId)) return e;
        return null;
    }

    // ------------------------------------------------------------ the core behaviour

    @Test
    void metaOutsideSubBlocksWithADifferentShapeGetsAShapeOnlyTwin() {
        VariantPlan p = plan();
        VariantPlan.BlockEntry twin = block(p, "hbm:tile.reactor_core_6");
        assertNotNull(twin, "meta 6 genuinely differs from meta 0's shape and is not a subBlocks entry");
        assertTrue(twin.shapeOnly, "must be flagged so Registrar/BootstrapProbe skip item registration");
        assertEquals(6, twin.meta);
        assertTrue(twin.variant);
        assertNull(twin.displayName, "no synthetic display name for a shape-only twin");
        assertEquals("hbm:tile.reactor_core@6", twin.legacyKey());
    }

    @Test
    void shapeOnlyTwinDoesNotRegisterUnderAConflictingIdOrDuplicateTheBase() {
        VariantPlan p = plan();
        int count = 0;
        for (VariantPlan.BlockEntry e : p.blocks) {
            if (e.base.id.equals("hbm:tile.reactor_core")) count++;
        }
        // base (meta 0) + the one shape-only twin (meta 6), nothing else invented
        assertEquals(2, count);
        assertNotNull(block(p, "hbm:tile.reactor_core"), "the v0 id must stay valid");
    }

    @Test
    void coveredSubBlocksMetaNeverBecomesAShapeOnlyTwinEvenWhenItsShapeAlsoDiffers() {
        VariantPlan p = plan();
        // meta 1 is a REAL subBlocks entry (numeric id, since all three share one
        // unlocalizedName) whose shape ALSO differs from meta 0's - it must stay a normal,
        // player-placeable variant, not get relabelled shapeOnly or duplicated.
        VariantPlan.BlockEntry v1 = block(p, "hbm:tile.multi_variant_1");
        assertNotNull(v1);
        assertFalse(v1.shapeOnly, "a real subBlocks entry must never be marked shapeOnly");
        assertTrue(v1.variant);
        assertEquals("Multi Variant", v1.displayName);

        // meta 2's shape matches meta 0's exactly - still registered (it's a real subBlocks
        // choice regardless of geometry), also not shapeOnly.
        VariantPlan.BlockEntry v2 = block(p, "hbm:tile.multi_variant_2");
        assertNotNull(v2);
        assertFalse(v2.shapeOnly);
    }

    @Test
    void subBlocksAndShapeOnlyTwinsCoexistWithoutIdCollisions() {
        VariantPlan p = plan();
        VariantPlan.BlockEntry twin = block(p, "hbm:tile.multi_variant_8");
        assertNotNull(twin, "meta 8 is out-of-cell and not in subBlocks -> shape-only twin expected");
        assertTrue(twin.shapeOnly);
        assertEquals(8, twin.meta);

        int count = 0;
        for (VariantPlan.BlockEntry e : p.blocks) {
            if (e.base.id.equals("hbm:tile.multi_variant")) count++;
        }
        // base(0) + variant(1) + variant(2) + shapeOnly(8) - exactly four, no extras
        assertEquals(4, count);
        assertEquals(List.of(), p.duplicatePaths());
    }

    @Test
    void noTwinWhenEveryMetaSharesTheSameExtractedShape() {
        VariantPlan p = plan();
        for (int meta = 1; meta <= 15; meta++) {
            assertNull(block(p, "hbm:tile.uniform_" + meta),
                    "meta " + meta + " has the identical dedup group as meta 0 - nothing to add");
        }
        int count = 0;
        for (VariantPlan.BlockEntry e : p.blocks) {
            if (e.base.id.equals("hbm:tile.uniform")) count++;
        }
        assertEquals(1, count, "only the base entry - a uniform shape never needed a twin");
    }

    @Test
    void shapeOnlyKeysExposesExactlyTheShapeOnlyEntries() {
        VariantPlan p = plan();
        Set<String> keys = p.shapeOnlyKeys();
        assertTrue(keys.contains("hbm:tile.reactor_core@6"));
        assertTrue(keys.contains("hbm:tile.multi_variant@8"));
        assertFalse(keys.contains("hbm:tile.multi_variant@1"), "a real subBlocks key must never appear here");
        assertFalse(keys.contains("hbm:tile.multi_variant@0"));
        assertEquals(2, keys.size());

        // every shapeOnly key must also show up in legacyKeyToPath (BootstrapProbe walks that map)
        for (String key : keys) {
            assertTrue(p.legacyKeyToPath().containsKey(key), key + " missing from legacyKeyToPath()");
        }
    }

    // ------------------------------------------------------------ regression: the 2-arg overload

    @Test
    void theTwoArgOverloadNeverProducesShapeOnlyTwins() {
        // dev.umb.packgen.PackGen calls VariantPlan.build(snap, lang) - the SAME two-arg method
        // this lane's predecessor already relied on - and must see byte-identical output to
        // before this lane, since it has no resource-pack entry to emit for a twin with no
        // display name/icon (see the class javadoc). This is the regression guard for that.
        VariantPlan p = VariantPlan.build(LegacySnapshot.parseString(SNAPSHOT_JSON, "hbm"), LangTable.empty());
        assertEquals(0, p.blockShapeOnlyVariants);
        assertNull(block(p, "hbm:tile.reactor_core_6"));
        assertNull(block(p, "hbm:tile.multi_variant_8"));
        assertTrue(p.shapeOnlyKeys().isEmpty());

        // the real subBlocks-driven entries are completely unaffected by shapes being absent
        VariantPlan.BlockEntry v1 = block(p, "hbm:tile.multi_variant_1");
        assertNotNull(v1);
        assertFalse(v1.shapeOnly);
        assertEquals("Multi Variant", v1.displayName);
    }

    // ------------------------------------------------------------ real corpus (adversarial check)

    /**
     * The exact real-data claim SHAPE-VARIANT-LANE.md makes: rebuilding the plan against the
     * REAL research/out/legacy/hbm-snapshot.json + block-shapes.json (not a synthetic fixture)
     * gives {@code hbm:tile.launch_pad_large}, {@code hbm:tile.machine_centrifuge} and
     * {@code hbm:tile.rbmk_autoloader} a real, registered shape-only twin at their genuine
     * out-of-cell metadata (12-15, verified directly against block-shapes.json while writing this
     * test), closing MULTIBLOCK-LANE.md section 4's "currently dormant" finding at the plan level
     * (Registrar/BootstrapProbe's own live-registry numbers are the end-to-end proof; this is the
     * fast, always-on unit-level guard that the plan step itself keeps doing its job).
     */
    @Test
    void realCorpusGivesTheThreeNamedMultiblocksAShapeOnlyTwin() throws Exception {
        Path snapPath = Paths.get("research/out/legacy/hbm-snapshot.json");
        Path shapesPath = Paths.get("research/out/legacy/block-shapes.json");
        Assumptions.assumeTrue(Files.isRegularFile(snapPath), "hbm-snapshot.json not present in this checkout");
        Assumptions.assumeTrue(Files.isRegularFile(shapesPath), "block-shapes.json not present in this checkout");

        LegacySnapshot snap = LegacySnapshot.load(snapPath, "hbm");
        BlockShapeProfile shapes = BlockShapeProfile.load(shapesPath);
        VariantPlan p = VariantPlan.build(snap, LangTable.empty(), shapes);

        assertTrue(p.blockShapeOnlyVariants > 0,
                "the real corpus has genuine out-of-cell metas outside subBlocks - this must not read 0");

        assertShapeOnlyTwinExists(p, "hbm:tile.launch_pad_large", 12);
        assertShapeOnlyTwinExists(p, "hbm:tile.machine_centrifuge", 12);
        assertShapeOnlyTwinExists(p, "hbm:tile.rbmk_autoloader", 12);

        assertEquals(List.of(), p.duplicatePaths(), "no id collision introduced across the whole real corpus");
    }

    private static void assertShapeOnlyTwinExists(VariantPlan p, String baseId, int meta) {
        String key = baseId + "@" + meta;
        assertTrue(p.shapeOnlyKeys().contains(key),
                baseId + " meta " + meta + " must be a registered shape-only twin (was it re-classified as"
                        + " a subBlocks variant, or does block-shapes.json no longer disagree with meta 0?)");
    }
}
