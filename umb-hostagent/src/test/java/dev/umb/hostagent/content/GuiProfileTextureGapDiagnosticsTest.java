package dev.umb.hostagent.content;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NOTEXTURE-GAP lane: gates {@link GuiProfile#diagnoseNoTextureSkips(Path)} — the read-only
 * diagnostic used to classify the 87 real {@code noTextureSkipped} rects by cause (see
 * {@code research/out/legacy/guimap-notes/NOTEXTURE-GAP.md}). Never consulted by
 * {@link GuiProfile#load}/{@link GuiProfile#parseString} — a rect landing in
 * {@link GuiProfile#rectsNoTextureSkipped} must also show up here, and vice versa, but the
 * diagnostic itself must never change {@code rectsNoTextureSkipped} or any other counter.
 */
class GuiProfileTextureGapDiagnosticsTest {

    private static final String CONTAINER_CLASS = "com.example.inventory.container.ContainerFakeMachine";

    private static String json(int textureBindIndex, int backgroundTexturesLength) {
        StringBuilder textures = new StringBuilder("[");
        for (int i = 0; i < backgroundTexturesLength; i++) {
            if (i > 0) textures.append(',');
            textures.append("""
                {"path": "modid:textures/gui/fake_%d.png", "resolved": false,
                 "reason": "bindTexture argument is ?(result of GuiFake.getTexture())"}
                """.formatted(i));
        }
        textures.append(']');
        return """
            {
              "guis": [{
                "className": "com.example.gui.GuiFakeMachine",
                "kind": "GuiContainer",
                "container": {"className": "%s", "confidence": "exact"},
                "size": {"xSize": 200, "ySize": 150, "confidence": "exact"},
                "backgroundTextures": %s,
                "backgroundDrawRects": [
                  {
                    "args": [
                      {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":7},
                      {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":80},
                      {"kind":"const","classification":"CONST","value":176},
                      {"kind":"const","classification":"CONST","value":58},
                      {"kind":"const","classification":"CONST","value":18},
                      {"kind":"const","classification":"CONST","value":18}
                    ],
                    "dynamic": false, "conditional": {"guarded": false}, "textureBindIndex": %d
                  }
                ],
                "foregroundLabels": []
              }]
            }
            """.formatted(CONTAINER_CLASS, textures, textureBindIndex);
    }

    @Test
    void recordsOneEntryPerNoTextureSkippedRectMatchingTheOrdinaryCounterExactly(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("gui-profile.json");
        // textureBindIndex 0 points at a backgroundTextures[0] entry that itself has resolved:false
        // - the "result of X.y()" accessor-call shape this lane closed for the real turret family.
        Files.writeString(file, json(0, 1));

        GuiProfile p = GuiProfile.load(file);
        assertEquals(1, p.rectsNoTextureSkipped, "ordinary parse must still count the skip normally");

        List<GuiProfile.TextureGapRecord> gaps = GuiProfile.diagnoseNoTextureSkips(file);
        assertEquals(1, gaps.size(), "the diagnostic must find exactly the same one skipped rect");
        GuiProfile.TextureGapRecord g = gaps.get(0);
        assertEquals("com.example.gui.GuiFakeMachine", g.guiClassName);
        assertEquals(CONTAINER_CLASS, g.containerClassName);
        assertEquals(0, g.rectIndex);
        assertEquals(0, g.textureBindIndex);
        assertEquals(1, g.texturesLength);
        assertNotNull(g.rawTexture, "textureBindIndex 0 is in range, so the raw texture entry must be included");
        assertFalse(g.rawTexture.get("resolved").getAsBoolean());
        assertNotNull(g.rawRect);
    }

    @Test
    void anOutOfRangeTextureBindIndexIsRecordedWithANullRawTexture(@TempDir Path tmp) throws Exception {
        // textureBindIndex -1 ("never bound one of its own") with zero backgroundTextures entries -
        // the real GUITurretHIMARS/GUITurretArty shape (see NOTEXTURE-GAP.md's honest remainder).
        Path file = tmp.resolve("gui-profile.json");
        Files.writeString(file, json(-1, 0));

        List<GuiProfile.TextureGapRecord> gaps = GuiProfile.diagnoseNoTextureSkips(file);
        assertEquals(1, gaps.size());
        GuiProfile.TextureGapRecord g = gaps.get(0);
        assertEquals(-1, g.textureBindIndex);
        assertEquals(0, g.texturesLength);
        assertNull(g.rawTexture, "an out-of-range index has no raw texture entry to report");
    }

    @Test
    void aFullyResolvedProfileHasNoTextureGapRecordsAtAll(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("gui-profile.json");
        String resolvedJson = """
            {
              "guis": [{
                "className": "com.example.gui.GuiFakeMachine",
                "kind": "GuiContainer",
                "container": {"className": "%s", "confidence": "exact"},
                "size": {"xSize": 200, "ySize": 150, "confidence": "exact"},
                "backgroundTextures": [
                  {"path": "modid:textures/gui/fake.png", "resolved": true, "existsInJar": true,
                   "assetPath": "assets/modid/textures/gui/fake.png", "sheetWidth": 256, "sheetHeight": 256}
                ],
                "backgroundDrawRects": [
                  {
                    "args": [
                      {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":7},
                      {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":80},
                      {"kind":"const","classification":"CONST","value":176},
                      {"kind":"const","classification":"CONST","value":58},
                      {"kind":"const","classification":"CONST","value":18},
                      {"kind":"const","classification":"CONST","value":18}
                    ],
                    "dynamic": false, "conditional": {"guarded": false}, "textureBindIndex": 0
                  }
                ],
                "foregroundLabels": []
              }]
            }
            """.formatted(CONTAINER_CLASS);
        Files.writeString(file, resolvedJson);

        assertEquals(0, GuiProfile.load(file).rectsNoTextureSkipped);
        assertTrue(GuiProfile.diagnoseNoTextureSkips(file).isEmpty());
    }

    @Test
    void anUnreadableFileYieldsAnEmptyListRatherThanThrowing() {
        List<GuiProfile.TextureGapRecord> gaps = GuiProfile.diagnoseNoTextureSkips(Path.of("no/such/gui-profile.json"));
        assertTrue(gaps.isEmpty());
    }
}
