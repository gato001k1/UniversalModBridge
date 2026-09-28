package dev.umb.hostagent.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * ironchest-visuals lane: the {@code containerId} key for eras where several ContainerTypes
 * share one Container class (1.16.5 IronChest: 8 types, 1 class, 8 sizes). Pure-data
 * parsing/lookup against a synthetic profile in the same schema the era extractor emits.
 */
class GuiProfileContainerIdTest {

    private static final String JSON = """
        {
          "meta": {"generator": "test"},
          "guis": [
            {
              "className": "com.progwml6.ironchest.client.screen.IronChestScreen",
              "container": {"className": "com.progwml6.ironchest.common.inventory.IronChestContainer",
                            "confidence": "exact", "containerId": "ironchest:iron_chest"},
              "size": {"xSize": 184, "ySize": 222, "confidence": "exact"},
              "backgroundTextures": [
                {"path": "ironchest:textures/gui/iron_container.png", "existsInJar": true,
                 "assetPath": "assets/ironchest/textures/gui/iron_container.png",
                 "sheetWidth": 256, "sheetHeight": 256}
              ]
            },
            {
              "className": "com.progwml6.ironchest.client.screen.IronChestScreen",
              "container": {"className": "com.progwml6.ironchest.common.inventory.IronChestContainer",
                            "confidence": "exact", "containerId": "ironchest:gold_chest"},
              "size": {"xSize": 184, "ySize": 276, "confidence": "exact"},
              "backgroundTextures": [
                {"path": "ironchest:textures/gui/gold_container.png", "existsInJar": true,
                 "assetPath": "assets/ironchest/textures/gui/gold_container.png",
                 "sheetWidth": 256, "sheetHeight": 276}
              ]
            }
          ]
        }
        """;

    @Test
    void idLookupPrefersTheMatchingVariantRow() {
        GuiProfile p = GuiProfile.parseString(JSON);
        GuiProfile.GuiEntry iron = p.lookup(
                "com.progwml6.ironchest.common.inventory.IronChestContainer", "ironchest:iron_chest");
        assertNotNull(iron);
        assertEquals(184, iron.xSize);
        assertEquals(222, iron.ySize);
        assertNotNull(iron.texture);
        assertEquals("ironchest:textures/gui/iron_container.png",
                iron.texture.namespace + ":" + iron.texture.path);

        GuiProfile.GuiEntry gold = p.lookup(
                "com.progwml6.ironchest.common.inventory.IronChestContainer", "ironchest:gold_chest");
        assertNotNull(gold);
        assertEquals(276, gold.ySize);
        assertEquals("ironchest:textures/gui/gold_container.png",
                gold.texture.namespace + ":" + gold.texture.path);
    }

    @Test
    void unknownIdFallsBackToTheClassRow() {
        GuiProfile p = GuiProfile.parseString(JSON);
        // First class row wins the class map (same first-wins rule as duplicate 1.7.10
        // containers); an id that matches nothing must not invent a row.
        GuiProfile.GuiEntry e = p.lookup(
                "com.progwml6.ironchest.common.inventory.IronChestContainer", "ironchest:diamond_chest");
        assertNotNull(e);
        assertEquals(222, e.ySize);
    }

    @Test
    void nullAndMalformedIdsBehaveLikeTheClassOnlyLookup() {
        GuiProfile p = GuiProfile.parseString(JSON);
        GuiProfile.GuiEntry byNull = p.lookup(
                "com.progwml6.ironchest.common.inventory.IronChestContainer", null);
        GuiProfile.GuiEntry byBlank = p.lookup(
                "com.progwml6.ironchest.common.inventory.IronChestContainer", "  ");
        GuiProfile.GuiEntry byGarbage = p.lookup(
                "com.progwml6.ironchest.common.inventory.IronChestContainer", "not-an-id");
        assertNotNull(byNull);
        assertNotNull(byBlank);
        assertNotNull(byGarbage);
        assertEquals(byNull.ySize, byBlank.ySize);
        assertEquals(byNull.ySize, byGarbage.ySize);
    }

    @Test
    void containerIdOfAcceptsOnlyWellFormedNsPathTitles() {
        assertEquals("ironchest:iron_chest",
                UmbMenuProvider.containerIdOf(new TitleHandle("ironchest:iron_chest")));
        assertNull(UmbMenuProvider.containerIdOf(new TitleHandle("hbm:tile:extra:colons")));
        assertNull(UmbMenuProvider.containerIdOf(new TitleHandle(":noprefix")));
        assertNull(UmbMenuProvider.containerIdOf(new TitleHandle("nosuffix:")));
        assertNull(UmbMenuProvider.containerIdOf(new TitleHandle("no-colons")));
        assertNull(UmbMenuProvider.containerIdOf(new TitleHandle("")));
        assertNull(UmbMenuProvider.containerIdOf(null));
    }

    /** Minimal ContainerHandle: only title() matters for the id extraction. */
    private static final class TitleHandle implements dev.umb.bridge.api.ContainerHandle {
        private final String title;

        TitleHandle(String title) {
            this.title = title;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public int slotCount() {
            return 0;
        }

        @Override
        public dev.umb.bridge.api.SlotData[] slots() {
            return new dev.umb.bridge.api.SlotData[0];
        }

        @Override
        public void setSlot(int index, dev.umb.bridge.api.StackData stack) {
        }

        @Override
        public dev.umb.bridge.api.StackData takeSlot(int index, int amount) {
            return null;
        }

        @Override
        public boolean canPlace(int index, dev.umb.bridge.api.StackData stack) {
            return false;
        }

        @Override
        public int[] syncData() {
            return new int[0];
        }

        @Override
        public void close() {
        }
    }
}
