package dev.umb.hostagent;

import dev.umb.hostagent.content.ItemRec;
import dev.umb.hostagent.content.LangTable;
import dev.umb.hostagent.content.LegacyIds;
import dev.umb.hostagent.content.LegacySnapshot;
import dev.umb.hostagent.content.SubRec;
import dev.umb.hostagent.content.VariantPlan;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 1.13-style flattening. Every fixture here is a reduction of a real hbm-snapshot.json record,
 * including the three shapes that broke the first design (repeated damage values, all sub-items
 * sharing one unlocalizedName, and no damage-0 sub-item at all).
 */
class VariantPlanTest {

    /**
     * hbm:item.drillbit                 10 named variants, damage 0..9 (readable ids)
     * hbm:item.bolt                     4 variants sharing ONE unlocalizedName, no damage 0
     * hbm:item.blueprints               damage 0 twelve times over (must collapse to one)
     * hbm:item.acetylene_torch          the ordinary case: a single damage-0 sub-item
     * hbm:tile.block_cap                6 named sub-blocks with a per-meta icon row
     * hbm:tile.block_c4                 7 icon ROWS but ONE sub-block: rotations, not variants
     * hbm:tile.bobblehead               sub-blocks 1..3 and no meta 0
     */
    private static final String JSON = """
        {
          "blocks": [
            {"id":"hbm:tile.block_cap","unlocalizedName":"tile.block_cap","displayName":"tile.block_cap",
             "hardness":3.0,"resistance":5.0,"creativeTab":"tabBlocks","stepSound":"stone",
             "icons":[
               {"meta":0,"sides":["hbm:cap_nuka_top","hbm:cap_nuka_top","hbm:cap_nuka","hbm:cap_nuka","hbm:cap_nuka","hbm:cap_nuka"]},
               {"meta":1,"sides":["hbm:cap_q_top","hbm:cap_q_top","hbm:cap_q","hbm:cap_q","hbm:cap_q","hbm:cap_q"]},
               {"meta":2,"sides":["hbm:cap_s_top","hbm:cap_s_top","hbm:cap_s","hbm:cap_s","hbm:cap_s","hbm:cap_s"]}],
             "subBlocks":[
               {"meta":0,"unlocalizedName":"tile.block_cap_nuka","displayName":"Block of Nuka Cola Bottle Caps"},
               {"meta":1,"unlocalizedName":"tile.block_cap_quantum","displayName":"Block of Nuka Cola Quantum Bottle Caps"},
               {"meta":2,"unlocalizedName":"tile.block_cap_sparkle","displayName":"Block of S~Cola Bottle Caps"}]},
            {"id":"hbm:tile.block_c4","unlocalizedName":"tile.block_c4","displayName":"Block of C-4",
             "hardness":2.0,"resistance":6.0,"creativeTab":"tabBlocks","stepSound":"stone",
             "icons":[
               {"meta":0,"sides":["hbm:c4","hbm:c4_front","hbm:c4","hbm:c4","hbm:c4","hbm:c4"]},
               {"meta":1,"sides":["hbm:c4_front","hbm:c4","hbm:c4","hbm:c4","hbm:c4","hbm:c4"]},
               {"meta":2,"sides":["hbm:c4","hbm:c4","hbm:c4","hbm:c4_front","hbm:c4","hbm:c4"]}],
             "subBlocks":[{"meta":0,"unlocalizedName":"tile.block_c4","displayName":"Block of C-4"}]},
            {"id":"hbm:tile.bobblehead","unlocalizedName":"tile.bobblehead","displayName":"Bobblehead",
             "hardness":1.0,"resistance":1.0,"creativeTab":"tabBlocks","stepSound":"stone",
             "icons":[{"meta":0,"sides":["hbm:steel","hbm:steel","hbm:steel","hbm:steel","hbm:steel","hbm:steel"]}],
             "subBlocks":[
               {"meta":1,"unlocalizedName":"tile.bobblehead","displayName":"Bobblehead"},
               {"meta":2,"unlocalizedName":"tile.bobblehead","displayName":"Bobblehead"},
               {"meta":3,"unlocalizedName":"tile.bobblehead","displayName":"Bobblehead"}]}
          ],
          "items": [
            {"id":"hbm:item.drillbit","unlocalizedName":"item.drillbit","displayName":"item.drillbit.name",
             "maxStackSize":1,"hasSubtypes":true,"creativeTab":"tabControl","iconName":"hbm:drillbit_steel",
             "subItems":[
               {"damage":0,"unlocalizedName":"item.drillbit_steel","displayName":"Steel Drillbit","iconName":"hbm:drillbit_steel"},
               {"damage":1,"unlocalizedName":"item.drillbit_hss","displayName":"High-Speed Steel Drillbit","iconName":"hbm:drillbit_hss"}]},
            {"id":"hbm:item.bolt","unlocalizedName":"item.bolt","displayName":"item.bolt.name",
             "maxStackSize":64,"hasSubtypes":true,"creativeTab":"tabParts","iconName":"hbm:bolt",
             "subItems":[
               {"damage":7400,"unlocalizedName":"item.boltntm","displayName":"Tungsten Bolt","iconName":"hbm:bolt_tungsten"},
               {"damage":8200,"unlocalizedName":"item.boltntm","displayName":"Lead Bolt","iconName":"hbm:bolt_lead"},
               {"damage":30,"unlocalizedName":"item.boltntm","displayName":"Steel Bolt","iconName":"hbm:bolt_steel"}]},
            {"id":"hbm:item.blueprints","unlocalizedName":"item.blueprints","displayName":"Blueprints",
             "maxStackSize":1,"hasSubtypes":true,"creativeTab":"tabParts","iconName":"hbm:blueprints",
             "subItems":[
               {"damage":0,"unlocalizedName":"item.blueprints","displayName":"Blueprints","iconName":"hbm:blueprints"},
               {"damage":0,"unlocalizedName":"item.blueprints","displayName":"Blueprints","iconName":"hbm:blueprints"},
               {"damage":0,"unlocalizedName":"item.blueprints","displayName":"Blueprints","iconName":"hbm:blueprints"}]},
            {"id":"hbm:item.acetylene_torch","unlocalizedName":"item.acetylene_torch",
             "displayName":"Acetylene Welding Torch","maxStackSize":1,"creativeTab":"tabControl",
             "iconName":"hbm:acetylene_torch",
             "subItems":[{"damage":0,"unlocalizedName":"item.acetylene_torch",
                          "displayName":"Acetylene Welding Torch","iconName":"hbm:acetylene_torch"}]},
            {"id":"hbm:item.dev_thing","unlocalizedName":"item.dev_thing","displayName":"item.dev_thing.name",
             "maxStackSize":64,"creativeTab":"tabParts","iconName":"hbm:dev_thing","subItems":[]},
            {"id":"hbm:tile.block_cap","unlocalizedName":"tile.block_cap","displayName":"tile.block_cap",
             "isBlockItem":"hbm:tile.block_cap","iconName":"hbm:cap_nuka_top",
             "subItems":[
               {"damage":0,"unlocalizedName":"tile.block_cap_nuka","displayName":"Block of Nuka Cola Bottle Caps"},
               {"damage":1,"unlocalizedName":"tile.block_cap_quantum","displayName":"Block of Nuka Cola Quantum Bottle Caps"},
               {"damage":2,"unlocalizedName":"tile.block_cap_sparkle","displayName":"Block of S~Cola Bottle Caps"}]}
          ]
        }
        """;

    private static VariantPlan plan() {
        return VariantPlan.build(LegacySnapshot.parseString(JSON, "hbm"), LangTable.empty());
    }

    private static VariantPlan.BlockEntry block(VariantPlan p, String legacyId) {
        for (VariantPlan.BlockEntry e : p.blocks) if (e.legacyId.equals(legacyId)) return e;
        return null;
    }

    private static VariantPlan.ItemEntry item(VariantPlan p, String legacyId) {
        for (VariantPlan.ItemEntry e : p.items) if (e.legacyId.equals(legacyId)) return e;
        return null;
    }

    // -------------------------------------------------------- variantId itself

    @Test
    void variantIdPrefersAReadableSuffixFromTheSubItemName() {
        SubRec s = new SubRec();
        s.meta = 3;
        s.unlocalizedName = "item.drillbit_steel";
        assertEquals("hbm:item.drillbit_steel",
                LegacyIds.variantId("hbm:item.drillbit", "item.drillbit", s, true, false));
    }

    @Test
    void variantIdFallsBackToTheDamageValue() {
        SubRec s = new SubRec();
        s.meta = 7400;
        s.unlocalizedName = "item.boltntm";
        // readable=false, because all of item.bolt's sub-items share item.boltntm
        assertEquals("hbm:item.bolt_7400",
                LegacyIds.variantId("hbm:item.bolt", "item.bolt", s, false, false));
    }

    @Test
    void variantIdIsDeterministicAndStable() {
        SubRec s = new SubRec();
        s.meta = 2;
        s.unlocalizedName = "tile.block_cap_sparkle";
        String a = LegacyIds.variantId("hbm:tile.block_cap", "tile.block_cap", s, true, true);
        String b = LegacyIds.variantId("hbm:tile.block_cap", "tile.block_cap", s, true, true);
        assertEquals(a, b);
        assertEquals("hbm:tile.block_cap_sparkle", a);

        // meta 0 stays on the base id for blocks (metaZeroIsBase), so v0 ids remain valid
        SubRec zero = new SubRec();
        zero.meta = 0;
        zero.unlocalizedName = "tile.block_cap_nuka";
        assertEquals("hbm:tile.block_cap",
                LegacyIds.variantId("hbm:tile.block_cap", "tile.block_cap", zero, true, true));
        // ... while items let damage 0 take the readable id (task's drillbit case)
        SubRec zeroItem = new SubRec();
        zeroItem.meta = 0;
        zeroItem.unlocalizedName = "item.drillbit_steel";
        assertEquals("hbm:item.drillbit_steel",
                LegacyIds.variantId("hbm:item.drillbit", "item.drillbit", zeroItem, true, false));
    }

    @Test
    void readableIdsAreAllOrNothingPerGroup() {
        SubRec a = new SubRec();
        a.meta = 0;
        a.unlocalizedName = "item.x_one";
        SubRec b = new SubRec();
        b.meta = 1;
        b.unlocalizedName = "item.x_two";
        assertTrue(LegacyIds.readableIdsUsable("item.x", List.of(a, b)));

        // the item.bolt shape: two sub-items, one shared name -> nobody gets a readable id
        SubRec c = new SubRec();
        c.meta = 2;
        c.unlocalizedName = "item.x_two";
        assertFalse(LegacyIds.readableIdsUsable("item.x", List.of(a, b, c)));

        // a sub-item that just repeats the base name is not readable either
        SubRec d = new SubRec();
        d.meta = 3;
        d.unlocalizedName = "item.x";
        assertFalse(LegacyIds.readableIdsUsable("item.x", List.of(a, d)));
        assertFalse(LegacyIds.readableIdsUsable("item.x", List.of()));
    }

    @Test
    void repeatedDamageValuesCollapse() {
        SubRec a = new SubRec();
        a.meta = 0;
        SubRec b = new SubRec();
        b.meta = 0;
        SubRec c = new SubRec();
        c.meta = 1;
        assertEquals(2, SubRec.distinctByMeta(List.of(a, b, c)).size());
        assertEquals(0, SubRec.distinctByMeta(null).size());
    }

    // ---------------------------------------------------------------- the plan

    @Test
    void namedItemVariantsBecomeReadableIds() {
        VariantPlan p = plan();
        VariantPlan.ItemEntry steel = item(p, "hbm:item.drillbit_steel");
        assertNotNull(steel, "damage 0 keeps the sub-item's readable id");
        assertEquals("item.drillbit_steel", steel.path);
        assertEquals("Steel Drillbit", steel.displayName);
        assertEquals("hbm:drillbit_steel", steel.icon);
        assertTrue(steel.variant);
        assertEquals("hbm:item.drillbit@0", steel.legacyKey());

        assertNotNull(item(p, "hbm:item.drillbit_hss"));
        assertEquals("High-Speed Steel Drillbit", item(p, "hbm:item.drillbit_hss").displayName);

        // the bare base is NOT also registered, because a damage-0 sub-item exists
        assertEquals(null, item(p, "hbm:item.drillbit"));
    }

    @Test
    void collidingItemVariantNamesFallBackToDamageSuffixes() {
        VariantPlan p = plan();
        assertNotNull(item(p, "hbm:item.bolt_7400"));
        assertNotNull(item(p, "hbm:item.bolt_8200"));
        assertNotNull(item(p, "hbm:item.bolt_30"));
        assertEquals("Tungsten Bolt", item(p, "hbm:item.bolt_7400").displayName);
        assertEquals("Lead Bolt", item(p, "hbm:item.bolt_8200").displayName);
        assertEquals(null, item(p, "hbm:item.boltntm"), "the shared name must never be used as an id");
        // no damage-0 sub-item, so the bare base id survives too
        VariantPlan.ItemEntry base = item(p, "hbm:item.bolt");
        assertNotNull(base);
        assertFalse(base.variant);
    }

    @Test
    void plainItemsKeepTheirV0Ids() {
        VariantPlan p = plan();
        VariantPlan.ItemEntry torch = item(p, "hbm:item.acetylene_torch");
        assertNotNull(torch);
        assertFalse(torch.variant);
        assertEquals("item.acetylene_torch", torch.path);
        assertEquals("Acetylene Welding Torch", torch.displayName);
        // an item with no subItems at all still registers
        assertNotNull(item(p, "hbm:item.dev_thing"));
        // damage 0 repeated twelve times over collapses to exactly one entry
        int blueprints = 0;
        for (VariantPlan.ItemEntry e : p.items) {
            if (e.base.id.equals("hbm:item.blueprints")) blueprints++;
        }
        assertEquals(1, blueprints);
    }

    @Test
    void blockMetaZeroKeepsTheBaseIdAndBorrowsTheSubBlockName() {
        VariantPlan p = plan();
        VariantPlan.BlockEntry base = block(p, "hbm:tile.block_cap");
        assertNotNull(base);
        assertFalse(base.variant);
        assertEquals(0, base.meta);
        assertEquals("tile.block_cap", base.path, "the v0 id must stay valid");
        assertEquals("Block of Nuka Cola Bottle Caps", base.displayName);
        assertEquals("hbm:cap_nuka_top", base.sides[0]);

        VariantPlan.BlockEntry q = block(p, "hbm:tile.block_cap_quantum");
        assertNotNull(q);
        assertTrue(q.variant);
        assertEquals(1, q.meta);
        assertEquals("Block of Nuka Cola Quantum Bottle Caps", q.displayName);
        assertEquals("hbm:cap_q_top", q.sides[0], "each variant takes its OWN icons[] row");
        assertEquals("hbm:tile.block_cap@1", q.legacyKey());
    }

    @Test
    void extraIconRowsWithoutSubBlocksAreNotVariants() {
        VariantPlan p = plan();
        // tile.block_c4 has icon rows for meta 0,1,2 but a single sub-block: those rows are
        // rotation states, so flattening them would invent junk blocks
        int c4 = 0;
        for (VariantPlan.BlockEntry e : p.blocks) {
            if (e.base.id.equals("hbm:tile.block_c4")) c4++;
        }
        assertEquals(1, c4);
    }

    @Test
    void blocksWithNoMetaZeroSubBlockStillKeepTheirBase() {
        VariantPlan p = plan();
        assertNotNull(block(p, "hbm:tile.bobblehead"));
        assertNotNull(block(p, "hbm:tile.bobblehead_1"));
        assertNotNull(block(p, "hbm:tile.bobblehead_2"));
        assertNotNull(block(p, "hbm:tile.bobblehead_3"));
        // all sub-blocks share the base unlocalizedName, so ids are numeric
        assertEquals("Bobblehead", block(p, "hbm:tile.bobblehead_2").displayName);
        // and the meta-0 icon row is reused because there is no row for meta 2
        assertEquals("hbm:steel", block(p, "hbm:tile.bobblehead_2").sides[0]);
    }

    // ------------------------------------------------------------ invariants

    @Test
    void noDuplicateRegistryPaths() {
        VariantPlan p = plan();
        assertEquals(List.of(), p.duplicatePaths());

        Set<String> blockPaths = new LinkedHashSet<>();
        for (VariantPlan.BlockEntry e : p.blocks) {
            assertTrue(blockPaths.add(e.path), "duplicate block path " + e.path);
        }
        Set<String> itemPaths = new LinkedHashSet<>();
        for (VariantPlan.ItemEntry e : p.items) {
            assertTrue(itemPaths.add(e.path), "duplicate item path " + e.path);
        }
    }

    @Test
    void buildingThePlanTwiceGivesTheSameIdsInTheSameOrder() {
        List<String> a = new ArrayList<>();
        List<String> b = new ArrayList<>();
        for (VariantPlan.BlockEntry e : plan().blocks) a.add(e.path);
        for (VariantPlan.ItemEntry e : plan().items) a.add(e.path);
        for (VariantPlan.BlockEntry e : plan().blocks) b.add(e.path);
        for (VariantPlan.ItemEntry e : plan().items) b.add(e.path);
        assertEquals(a, b);
    }

    @Test
    void everyDisplayNameIsRealEnglish() {
        VariantPlan p = plan();
        assertEquals(List.of(), p.rawKeyNames);
        for (VariantPlan.BlockEntry e : p.blocks) {
            assertFalse(LangTable.looksLikeRawKey(e.displayName), e.legacyId + " -> " + e.displayName);
        }
        for (VariantPlan.ItemEntry e : p.items) {
            assertFalse(LangTable.looksLikeRawKey(e.displayName), e.legacyId + " -> " + e.displayName);
        }
        // item.dev_thing has neither a real displayName nor a lang row, so it is title-cased
        assertEquals("Dev Thing", item(p, "hbm:item.dev_thing").displayName);
    }

    @Test
    void theLangFileWinsOverARawSnapshotName(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        java.nio.file.Path f = tmp.resolve("en_US.lang");
        java.nio.file.Files.writeString(f,
                "item.dev_thing.name=Developer Widget\nitem.bolt.name=Bolt\n",
                java.nio.charset.StandardCharsets.UTF_8);
        VariantPlan p = VariantPlan.build(LegacySnapshot.parseString(JSON, "hbm"), LangTable.load(f));
        // snapshot displayName was the raw key "item.dev_thing.name" -> the lang row wins
        assertEquals("Developer Widget", item(p, "hbm:item.dev_thing").displayName);
        // and the base unlocalizedName is consulted for a variant with no row of its own
        assertEquals("Bolt", item(p, "hbm:item.bolt").displayName);
    }

    @Test
    void humanizeTurnsKeysIntoTitleCase() {
        assertEquals("Dummy Port Launch Table", LangTable.humanize("tile.dummy_port_launch_table"));
        assertEquals("Drillbit Steel", LangTable.humanize("item.drillbit_steel.name"));
        assertEquals("Undef", LangTable.humanize("tile.#undef"));
        assertEquals("Concrete Colored Light Blue", LangTable.humanize("hbm:tile.concrete_colored.lightBlue"));
        assertEquals("?", LangTable.humanize(null));
    }

    @Test
    void itemRecAndBlockRecOnlyFlattenGroupsBiggerThanOne() {
        LegacySnapshot snap = LegacySnapshot.parseString(JSON, "hbm");
        for (ItemRec it : snap.items) {
            if ("hbm:item.acetylene_torch".equals(it.id)) {
                assertEquals(0, it.variantGroup().size(), "one sub-item is not a variant group");
                assertNotNull(it.primarySub());
            }
            if ("hbm:item.drillbit".equals(it.id)) {
                assertEquals(2, it.variantGroup().size());
            }
        }
    }

    @Test
    void truncatedItemFillsOnlyOmittedNumericDamageSlots() {
        ItemRec ammo = new ItemRec();
        ammo.id = "hbm:item.ammo_standard";
        ammo.unlocalizedName = "item.ammo_standard";
        ammo.hasSubtypes = true;
        ammo.truncated = true;
        ammo.subItemsTotal = 4;
        SubRec zero = new SubRec();
        zero.meta = 0;
        zero.unlocalizedName = "item.ammo_standard.stone";
        SubRec two = new SubRec();
        two.meta = 2;
        two.unlocalizedName = "item.ammo_standard.g12";
        ammo.subItems = List.of(zero, two);

        List<SubRec> group = ammo.variantGroup();
        assertEquals(List.of(0, 2, 1, 3), group.stream().map(s -> s.meta).toList());
        List<String> ids = LegacyIds.variantIds(ammo.id, ammo.unlocalizedName, group, false);
        assertEquals("hbm:item.ammo_standard.stone", ids.get(0));
        assertEquals("hbm:item.ammo_standard.g12", ids.get(1));
        assertEquals("hbm:item.ammo_standard_1", ids.get(2));
        assertEquals("hbm:item.ammo_standard_3", ids.get(3));
    }
}
