package dev.umb.hostagent;

import dev.umb.hostagent.content.BlockRec;
import dev.umb.hostagent.content.ItemRec;
import dev.umb.hostagent.content.LegacySnapshot;
import dev.umb.hostagent.content.TabRec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnapshotParseTest {

    private static final String JSON = """
        {
          "creativeTabs": [
            {"index":0,"label":"buildingBlocks","translatedLabel":"itemGroup.buildingBlocks"},
            {"index":12,"label":"tabParts","translatedLabel":"itemGroup.tabParts",
             "iconItemId":"hbm:item.ingot_uranium","iconMeta":0}
          ],
          "blocks": [
            {"id":"hbm:tile.#undef","unlocalizedName":"tile.#undef","displayName":"tile.#undef.name",
             "material":"net.minecraft.block.material.Material","mapColor":6,
             "hardness":"Infinity","resistance":"Infinity","lightValue":15,"opaqueCube":false,
             "creativeTab":null,"stepSound":"stone",
             "icons":[{"meta":0,"metas":[0],"sides":["hbm:code","hbm:code","hbm:code","hbm:code","hbm:code","hbm:code"]}]},
            {"id":"hbm:tile.block_c4","unlocalizedName":"tile.block_c4","displayName":"Block of C-4",
             "mapColor":4,"hardness":2.0,"resistance":6.0,"lightValue":0,"opaqueCube":true,
             "creativeTab":"tabBlocks","stepSound":"stone","harvestTool":"pickaxe",
             "icons":[{"meta":1,"sides":["a","a","a","a","a","a"]},
                      {"meta":0,"sides":["hbm:block_c4","hbm:block_c4_front","hbm:block_c4","hbm:block_c4","hbm:block_c4","hbm:block_c4"]}]},
            {"id":null,"displayName":"junk"},
            {"id":"minecraft:stone","displayName":"Stone"}
          ],
          "items": [
            {"id":"hbm:item.ingot_uranium","unlocalizedName":"item.ingot_uranium","displayName":"Uranium Ingot",
             "maxStackSize":64,"maxDamage":0,"creativeTab":"tabParts","iconName":"hbm:ingot_uranium"},
            {"id":"hbm:tile.block_c4","unlocalizedName":"tile.block_c4","displayName":"Block of C-4",
             "maxStackSize":64,"isBlockItem":"hbm:tile.block_c4","iconName":"hbm:block_c4"},
            {"id":"hbm:item.ajr_boots","displayName":"Steel Ranger Boots","maxStackSize":1,
             "maxDamage":1950,"creativeTab":"combat","iconName":"hbm:ajr_boots"}
          ]
        }
        """;

    @Test
    void filtersToNamespaceAndDropsNullIds() {
        LegacySnapshot s = LegacySnapshot.parseString(JSON, "hbm");
        assertEquals(2, s.blocks.size(), "minecraft:stone and the null-id record must be dropped");
        assertEquals(3, s.items.size());
        assertEquals(2, s.tabs.size());
    }

    @Test
    void infinityStringMeansUnbreakable() {
        LegacySnapshot s = LegacySnapshot.parseString(JSON, "hbm");
        BlockRec undef = s.blocks.get(0);
        assertEquals("hbm:tile.#undef", undef.id);
        assertTrue(undef.unbreakable, "\"Infinity\" hardness must map to unbreakable");
        assertTrue(Float.isInfinite(undef.hardness));

        BlockRec c4 = s.blocks.get(1);
        assertFalse(c4.unbreakable);
        assertEquals(2.0f, c4.hardness, 0.0001f);
        assertEquals(6.0f, c4.resistance, 0.0001f);
    }

    @Test
    void metaZeroIconRowWins() {
        LegacySnapshot s = LegacySnapshot.parseString(JSON, "hbm");
        BlockRec c4 = s.blocks.get(1);
        assertNotNull(c4.sides);
        assertEquals("hbm:block_c4", c4.sides[0]);
        assertEquals("hbm:block_c4_front", c4.sides[1]);
        assertFalse(c4.allSidesEqual());
        assertEquals(2, c4.distinctSideIcons().size());

        BlockRec undef = s.blocks.get(0);
        assertTrue(undef.allSidesEqual());
    }

    @Test
    void itemFieldsAndBlockItemLink() {
        LegacySnapshot s = LegacySnapshot.parseString(JSON, "hbm");
        ItemRec ingot = s.items.get(0);
        assertNull(ingot.isBlockItem);
        assertEquals(64, ingot.clampedStackSize());
        assertEquals("hbm:ingot_uranium", ingot.icon());

        ItemRec blockItem = s.items.get(1);
        assertEquals("hbm:tile.block_c4", blockItem.isBlockItem);

        ItemRec boots = s.items.get(2);
        assertEquals(1950, boots.maxDamage);
        assertEquals(1, boots.clampedStackSize());
    }

    @Test
    void truncatedItemCarriesItsObservedTotal() {
        LegacySnapshot s = LegacySnapshot.parseString("""
            {"items":[{"id":"hbm:item.ammo_standard","unlocalizedName":"item.ammo_standard",
              "hasSubtypes":true,"truncated":true,"subItemsTotal":95,
              "subItems":[{"damage":0,"unlocalizedName":"item.ammo_standard.stone"}]}]}
            """, "hbm");
        ItemRec ammo = s.items.get(0);
        assertTrue(ammo.truncated);
        assertEquals(95, ammo.subItemsTotal);
    }

    @Test
    void vanillaTabLabelsAreRecognised() {
        LegacySnapshot s = LegacySnapshot.parseString(JSON, "hbm");
        TabRec vanilla = s.tabs.get(0);
        TabRec custom = s.tabs.get(1);
        assertTrue(vanilla.isVanillaLabel());
        assertFalse(custom.isVanillaLabel());
        assertEquals("hbm:item.ingot_uranium", custom.iconItemId);
    }

    @Test
    void malformedJsonYieldsEmptySnapshotNotAnException() {
        LegacySnapshot s = LegacySnapshot.parseString("[]", "hbm");
        assertEquals(0, s.blocks.size());
        assertEquals(0, s.items.size());
        assertEquals(0, s.tabs.size());
    }

    /**
     * laneCasing / Bug 1: the snapshot stores the legacy id VERBATIM (e.g. "IronChest:..."), same
     * as the mod actually registered it - so the namespace filter must accept either the raw
     * legacy modid or an already-sanitized 26.2 namespace and match the SAME records, since both
     * name the same mod. The ids returned must stay untouched either way.
     */
    private static final String MIXED_CASE_JSON = """
        {
          "blocks": [
            {"id":"IronChest:BlockIronChest","unlocalizedName":"tile.chest","displayName":"Iron Chest"}
          ],
          "items": [
            {"id":"IronChest:itemChestChanger","unlocalizedName":"item.changer","displayName":"Chest Changer"}
          ]
        }
        """;

    @Test
    void namespaceFilterIsCaseInsensitiveButIdsStayVerbatim() {
        LegacySnapshot viaRaw = LegacySnapshot.parseString(MIXED_CASE_JSON, "IronChest");
        LegacySnapshot viaSanitized = LegacySnapshot.parseString(MIXED_CASE_JSON, "ironchest");
        assertEquals(1, viaRaw.blocks.size());
        assertEquals(1, viaSanitized.blocks.size());
        assertEquals(1, viaRaw.items.size());
        assertEquals(1, viaSanitized.items.size());
        // the legacy id itself is never lowercased by the reader - only membership testing folds case
        assertEquals("IronChest:BlockIronChest", viaRaw.blocks.get(0).id);
        assertEquals("IronChest:BlockIronChest", viaSanitized.blocks.get(0).id);

        // a namespace that is merely a PREFIX of the real one (or vice versa) must not match
        LegacySnapshot viaPrefix = LegacySnapshot.parseString(MIXED_CASE_JSON, "iron");
        assertEquals(0, viaPrefix.blocks.size());
        assertEquals(0, viaPrefix.items.size());
    }
}
