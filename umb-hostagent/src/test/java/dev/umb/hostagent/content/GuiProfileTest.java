package dev.umb.hostagent.content;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task B gate for {@link GuiProfile}: pure-data parsing/lookup against a synthetic
 * gui-profile.json (schema verified against the real file while building this), plus the exact
 * real-data assertion the lead's brief named: "GUIMachinePUREX's container resolves to a
 * 176x256 panel (not 176x166)".
 */
class GuiProfileTest {

    private static final String JSON = """
        {
          "meta": {"schemaVersion": 1},
          "coverage": {},
          "guis": [
            {
              "className": "com.hbm.inventory.gui.GUIMachinePUREX",
              "superClass": "com.hbm.inventory.gui.GuiInfoContainer",
              "kind": "GuiContainer",
              "container": {"className": "com.hbm.inventory.container.ContainerMachinePUREX", "confidence": "exact"},
              "size": {"xSize": 176, "ySize": 256, "confidence": "exact"},
              "backgroundTextures": [
                {"path": "hbm:textures/gui/processing/gui_purex.png", "existsInJar": true,
                 "assetPath": "assets/hbm/textures/gui/processing/gui_purex.png", "sheetWidth": 256, "sheetHeight": 256}
              ]
            },
            {
              "className": "com.hbm.inventory.gui.GUINoTexture",
              "kind": "GuiContainer",
              "container": {"className": "com.hbm.inventory.container.ContainerNoTexture", "confidence": "exact"},
              "size": {"xSize": 176, "ySize": 200, "confidence": "exact"},
              "backgroundTextures": [
                {"path": "hbm:textures/gui/missing.png", "existsInJar": false}
              ]
            },
            {
              "className": "com.hbm.inventory.gui.GUIUnresolvedSize",
              "kind": "GuiContainer",
              "container": {"className": "com.hbm.inventory.container.ContainerUnresolvedSize", "confidence": "exact"},
              "size": {"confidence": "unresolved"},
              "backgroundTextures": []
            },
            {
              "className": "com.hbm.wiaj.GuiWorldInAJar",
              "kind": "GuiScreen",
              "container": {"confidence": "none"},
              "size": {"confidence": "unresolved"},
              "backgroundTextures": []
            }
          ]
        }
        """;

    @Test
    void looksUpAKnownGuiByItsContainerClassAndResolvesRealSizeAndTexture() {
        GuiProfile p = GuiProfile.parseString(JSON);
        GuiProfile.GuiEntry e = p.lookup("com.hbm.inventory.container.ContainerMachinePUREX");
        assertNotNull(e);
        assertTrue(e.sizeExact);
        assertEquals(176, e.xSize);
        assertEquals(256, e.ySize);
        assertNotNull(e.texture);
        assertEquals("hbm", e.texture.namespace);
        assertEquals("textures/gui/processing/gui_purex.png", e.texture.path);
        assertEquals("assets/hbm/textures/gui/processing/gui_purex.png", e.texture.assetPath);
        assertEquals(256, e.texture.sheetWidth);
        assertEquals(256, e.texture.sheetHeight);
    }

    @Test
    void aGuiScreenWithNoContainerIsNeverKeyed() {
        GuiProfile p = GuiProfile.parseString(JSON);
        assertEquals(3, p.size(), "only the 3 container-paired GUIs are keyed - the raw GuiScreen is not");
        assertNull(p.lookup("com.hbm.wiaj.GuiWorldInAJar"), "GuiWorldInAJar has no container class to be keyed by");
    }

    @Test
    void unknownContainerClassResolvesToNull() {
        GuiProfile p = GuiProfile.parseString(JSON);
        assertNull(p.lookup("com.hbm.inventory.container.ContainerNeverOpened"));
        assertNull(p.lookup(null));
    }

    @Test
    void unresolvedSizeFallsBackTo176x166AndIsCountedOncePerContainerClass() {
        GuiProfile p = GuiProfile.parseString(JSON);
        GuiProfile.GuiEntry e = p.lookup("com.hbm.inventory.container.ContainerUnresolvedSize");
        assertNotNull(e);
        assertFalse(e.sizeExact);
        assertEquals(GuiProfile.FALLBACK_X, e.xSize);
        assertEquals(GuiProfile.FALLBACK_Y, e.ySize);
        assertNull(e.texture);

        assertEquals(1, p.sizeFallbackCount);
        p.lookup("com.hbm.inventory.container.ContainerUnresolvedSize"); // second lookup, same GUI
        assertEquals(1, p.sizeFallbackCount, "logged/counted only ONCE per GUI, not per lookup");
    }

    @Test
    void noResolvableTextureLeavesTheEntryWithANullTexture() {
        GuiProfile p = GuiProfile.parseString(JSON);
        GuiProfile.GuiEntry e = p.lookup("com.hbm.inventory.container.ContainerNoTexture");
        assertNotNull(e);
        assertTrue(e.sizeExact);
        assertEquals(200, e.ySize);
        assertNull(e.texture, "existsInJar=false must not be treated as a resolved texture");
        assertEquals(1, p.textureFallbackCount);
    }

    @Test
    void aMissingFileBehavesLikeEmpty() {
        GuiProfile p = GuiProfile.load(Paths.get("no/such/gui-profile.json"));
        assertEquals(0, p.size());
        assertNull(p.lookup("anything"));
    }

    /**
     * The exact assertion named in the lead's brief: GUIMachinePUREX's container resolves to a
     * 176x256 panel, not the pre-Task-B hardcoded 176x166 - against the REAL, lead-verified
     * research/out/legacy/gui-profile.json (not a synthetic fixture). Skips (does not fail) if the
     * file is not present in this checkout, matching every other real-data test in this suite's
     * tolerant style.
     */
    @Test
    void realGuiProfileJsonResolvesGuiMachinePurexTo176x256() {
        Path real = Paths.get("research/out/legacy/gui-profile.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(java.nio.file.Files.isRegularFile(real),
                "research/out/legacy/gui-profile.json not present in this checkout");
        GuiProfile p = GuiProfile.load(real);
        GuiProfile.GuiEntry e = p.lookup("com.hbm.inventory.container.ContainerMachinePUREX");
        assertNotNull(e, "GUIMachinePUREX's container must be present in the real extraction");
        assertTrue(e.sizeExact);
        assertEquals(176, e.xSize);
        assertEquals(256, e.ySize, "GUIMachinePUREX is 176x256, not the hardcoded 176x166");
    }
}
