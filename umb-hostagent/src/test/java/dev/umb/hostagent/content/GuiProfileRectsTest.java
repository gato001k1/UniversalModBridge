package dev.umb.hostagent.content;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SCREEN-RENDER lane (GENERALIZATION-PLAN.md GAP 2) gate for {@link GuiProfile}'s new
 * rect/label extraction: {@link GuiProfile.GuiEntry#rects}/{@link GuiProfile.GuiEntry#labels}.
 * Three cases the lead's brief named explicitly:
 * <ol>
 *   <li>a panel-relative rect landing at the right absolute pixel</li>
 *   <li>a rect correctly skipped as unbindable (a STATE_LINEAR arg - no sync-index mapping exists)</li>
 *   <li>the background rect not being drawn twice</li>
 * </ol>
 * Plus a real-data cross-check against the actual generated {@code gui-profile.json} (self-skips,
 * same tolerant style as the rest of this suite, if the file is not present in this checkout).
 */
class GuiProfileRectsTest {

    private static final String CONTAINER_CLASS = "com.hbm.inventory.container.ContainerFakeMachine";

    /** One background-shape rect (the full-panel blit) followed by one real, drawable rect, at a
     *  known 200x150 panel size. */
    private static String twoRectJson(String secondRectArgs) {
        return """
            {
              "guis": [{
                "className": "com.hbm.inventory.gui.GUIFakeMachine",
                "kind": "GuiContainer",
                "container": {"className": "%s", "confidence": "exact"},
                "size": {"xSize": 200, "ySize": 150, "confidence": "exact"},
                "backgroundTextures": [
                  {"path": "hbm:textures/gui/fake.png", "resolved": true, "existsInJar": true,
                   "assetPath": "assets/hbm/textures/gui/fake.png", "sheetWidth": 256, "sheetHeight": 256}
                ],
                "backgroundDrawRects": [
                  {
                    "args": [
                      {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":0},
                      {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":0},
                      {"kind":"const","classification":"CONST","value":0},
                      {"kind":"const","classification":"CONST","value":0},
                      {"kind":"const","classification":"CONST","value":200},
                      {"kind":"const","classification":"CONST","value":150}
                    ],
                    "dynamic": false, "conditional": {"guarded": false}, "textureBindIndex": 0
                  },
                  %s
                ],
                "foregroundLabels": []
              }]
            }
            """.formatted(CONTAINER_CLASS, secondRectArgs);
    }

    @Test
    void panelRelativeRectResolvesToTheRightOffsetFromLeftPosTopPos() {
        String secondRect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":10},
                {"kind":"const","classification":"CONST","value":18},
                {"kind":"const","classification":"CONST","value":18}
              ],
              "dynamic": false, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """;
        GuiProfile p = GuiProfile.parseString(twoRectJson(secondRect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);

        // The background-shape rect (index 0 in the JSON) must NOT appear - only the real one.
        assertEquals(1, e.rects.size(), "only the non-background rect should survive");
        GuiProfile.Rect r = e.rects.get(0);
        assertEquals(53, r.dx);
        assertEquals(36, r.dy);
        assertEquals(176, r.u);
        assertEquals(10, r.v);
        assertEquals(18, r.w);
        assertEquals(18, r.h);
        assertEquals(0, r.textureIndex);

        // Prove the resulting absolute pixel lands inside the panel for a representative
        // leftPos/topPos (leftPos+dx, topPos+dy, ...+w/h must stay within the 200x150 panel that
        // was extracted for THIS gui - exactly the "lands at the right absolute pixel" claim).
        int leftPos = 40, topPos = 30; // arbitrary screen placement, as AbstractContainerScreen centers it
        int absX = leftPos + r.dx, absY = topPos + r.dy;
        assertEquals(93, absX);
        assertEquals(66, absY);
        assertTrue(absX - leftPos >= 0 && absX - leftPos + r.w <= e.xSize, "rect must fit inside the panel horizontally");
        assertTrue(absY - topPos >= 0 && absY - topPos + r.h <= e.ySize, "rect must fit inside the panel vertically");

        assertEquals(1, p.rectsDrawable);
        assertEquals(1, p.rectsBackgroundDupSkipped);
    }

    @Test
    void stateLinearRectIsSkippedAsUnbindableRatherThanDrawnFrozen() {
        // The classic 1.7.10 progress-bar shape: w is `this.machine.progress` (STATE_LINEAR) - no
        // per-mod-free sync-index mapping exists on ContainerHandle today (see GuiProfile.buildRects
        // javadoc), so this must be skipped, not drawn as if progress were always 0.
        String secondRect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"stateLinear","classification":"STATE_LINEAR",
                 "stateBinding": {"source": {"ownerClass":"x","fieldName":"progress","origin":"tileEntityViaGui","originPath":"this.machine.progress"},
                                   "multiplier":1.0,"divisor":{"kind":"const","value":1.0},"offset":0,"sign":1}},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": true, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """;
        GuiProfile p = GuiProfile.parseString(twoRectJson(secondRect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);

        assertEquals(0, e.rects.size(), "a STATE_LINEAR rect must be skipped, never drawn with a guessed value");
        assertEquals(1, p.rectsUnbindableSkipped);
        assertEquals(0, p.rectsDrawable);
    }

    @Test
    void guardedRectIsSkippedAsUnbindableConditional() {
        String secondRect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":18},
                {"kind":"const","classification":"CONST","value":18}
              ],
              "dynamic": false,
              "conditional": {"guarded": true, "guardDescription": "IFEQ result of TileEntityFake.isReady()"},
              "textureBindIndex": 0
            }
            """;
        GuiProfile p = GuiProfile.parseString(twoRectJson(secondRect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);

        assertEquals(0, e.rects.size(), "a guarded rect must be skipped - evaluating the guard would mean running legacy bytecode");
        assertEquals(1, p.rectsGuardedSkipped);
    }

    @Test
    void backgroundShapedRectIsNeverDrawnTwiceRegardlessOfListPosition() {
        // Same background-shape rect appears TWICE (a defensive check: even if it were not
        // uniquely first, every occurrence matching the shape must be skipped by shape, not index).
        String secondRect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":0},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":0},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":200},
                {"kind":"const","classification":"CONST","value":150}
              ],
              "dynamic": false, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """;
        GuiProfile p = GuiProfile.parseString(twoRectJson(secondRect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);

        assertEquals(0, e.rects.size(), "the background art must never be blitted a second time via the rect list");
        assertEquals(2, p.rectsBackgroundDupSkipped);
    }

    @Test
    void rectReferencingAnUnresolvedTextureBindIsSkipped() {
        String secondRect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":18},
                {"kind":"const","classification":"CONST","value":18}
              ],
              "dynamic": false, "conditional": {"guarded": false}, "textureBindIndex": 3
            }
            """;
        GuiProfile p = GuiProfile.parseString(twoRectJson(secondRect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);

        assertEquals(0, e.rects.size(), "a rect whose bound texture never resolved must not fall back to guessing the primary texture");
        assertEquals(1, p.rectsNoTextureSkipped);
    }

    @Test
    void secondBindTextureIsUsedByTheRectThatActuallyBoundIt() {
        String json = """
            {
              "guis": [{
                "className": "com.hbm.inventory.gui.GUIFakeTwoTex",
                "kind": "GuiContainer",
                "container": {"className": "com.hbm.inventory.container.ContainerFakeTwoTex", "confidence": "exact"},
                "size": {"xSize": 200, "ySize": 150, "confidence": "exact"},
                "backgroundTextures": [
                  {"path": "hbm:textures/gui/a.png", "resolved": true, "existsInJar": true,
                   "assetPath": "assets/hbm/textures/gui/a.png", "sheetWidth": 256, "sheetHeight": 256},
                  {"path": "hbm:textures/gui/b.png", "resolved": true, "existsInJar": true,
                   "assetPath": "assets/hbm/textures/gui/b.png", "sheetWidth": 128, "sheetHeight": 128}
                ],
                "backgroundDrawRects": [
                  {
                    "args": [
                      {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":10},
                      {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":10},
                      {"kind":"const","classification":"CONST","value":0},
                      {"kind":"const","classification":"CONST","value":0},
                      {"kind":"const","classification":"CONST","value":20},
                      {"kind":"const","classification":"CONST","value":20}
                    ],
                    "dynamic": false, "conditional": {"guarded": false}, "textureBindIndex": 1
                  }
                ],
                "foregroundLabels": []
              }]
            }
            """;
        GuiProfile p = GuiProfile.parseString(json);
        GuiProfile.GuiEntry e = p.lookup("com.hbm.inventory.container.ContainerFakeTwoTex");
        assertEquals(1, e.rects.size());
        GuiProfile.Rect r = e.rects.get(0);
        assertEquals(1, r.textureIndex);
        assertEquals("b.png", e.textures[r.textureIndex].path.substring(e.textures[r.textureIndex].path.lastIndexOf('/') + 1));
        assertEquals(128, e.textures[r.textureIndex].sheetWidth);
    }

    @Test
    void translatedForegroundLabelWithConstXYIsDrawableAndUnresolvedCenteredTitleIsSkipped() {
        String json = """
            {
              "guis": [{
                "className": "com.hbm.inventory.gui.GUIFakeLabels",
                "kind": "GuiContainer",
                "container": {"className": "com.hbm.inventory.container.ContainerFakeLabels", "confidence": "exact"},
                "size": {"xSize": 176, "ySize": 166, "confidence": "exact"},
                "backgroundTextures": [],
                "backgroundDrawRects": [],
                "foregroundLabels": [
                  {"dynamic": false, "translated": true, "text": "container.inventory",
                   "x": {"kind":"const","classification":"CONST","value":8},
                   "y": {"kind":"const","classification":"CONST","value":72}},
                  {"dynamic": false, "translated": true, "text": "container.centeredTitle",
                   "x": {"kind":"dynamic","classification":"UNRESOLVED","desc":"?(arithmetic)","reason":"arithmetic"},
                   "y": {"kind":"const","classification":"CONST","value":6}}
                ]
              }]
            }
            """;
        GuiProfile p = GuiProfile.parseString(json);
        GuiProfile.GuiEntry e = p.lookup("com.hbm.inventory.container.ContainerFakeLabels");
        assertEquals(1, e.labels.size(), "only the fully-CONST-positioned label should survive");
        GuiProfile.Label l = e.labels.get(0);
        assertTrue(l.translated);
        assertEquals("container.inventory", l.text);
        assertEquals(8, l.x);
        assertEquals(72, l.y);
        assertEquals(1, p.labelsUnresolvedSkipped);
        assertEquals(1, p.labelsDrawable);
    }

    /**
     * Real-data cross-check: every currently-drawable rect in the whole real corpus must fit
     * inside its own GUI's extracted panel size - the "prove the N you claim actually land inside
     * the panel" adversarial check the lead's brief asked for. Self-skips if the real file is not
     * present in this checkout (same tolerant style as GuiProfileTest).
     */
    @Test
    void everyDrawableRectInTheRealCorpusFitsInsideItsOwnPanel() throws Exception {
        Path real = Paths.get("research/out/legacy/gui-profile.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(real),
                "research/out/legacy/gui-profile.json not present in this checkout");
        GuiProfile p = GuiProfile.load(real);
        int checked = 0;
        for (GuiProfile.GuiEntry e : p.entries()) {
            for (GuiProfile.Rect r : e.rects) {
                checked++;
                assertTrue(r.dx >= 0 && r.dy >= 0, e.guiClassName + " rect has a negative panel offset");
                assertTrue(r.dx + r.w <= e.xSize, e.guiClassName + " rect overflows the panel width");
                assertTrue(r.dy + r.h <= e.ySize, e.guiClassName + " rect overflows the panel height");
                assertTrue(r.textureIndex >= 0 && r.textureIndex < e.textures.length
                                && e.textures[r.textureIndex] != null,
                        e.guiClassName + " rect references an unresolved texture index");
            }
        }
        assertTrue(checked > 0, "expected at least one real drawable rect across the corpus");
        int labelsChecked = 0;
        for (GuiProfile.GuiEntry e : p.entries()) {
            for (GuiProfile.Label l : e.labels) {
                labelsChecked++;
                assertTrue(l.text != null && !l.text.isEmpty(), e.guiClassName + " drawable label has no text");
            }
        }
        assertTrue(labelsChecked > 0, "expected at least one real drawable label across the corpus");

        // SYNC-BINDING lane: at least the one real winning case (GUIRtgFurnace's dualCookTime gauge)
        // must have made it all the way through the pipeline into an actually-drawable, sync-bound
        // rect - not just present in container.syncBindings.
        GuiProfile.GuiEntry rtgFurnace = p.lookup("com.hbm.inventory.container.ContainerRtgFurnace");
        assertTrue(rtgFurnace != null, "ContainerRtgFurnace must be resolvable in the real corpus");
        boolean hasSyncBoundRect = rtgFurnace.rects.stream().anyMatch(GuiProfile.Rect::hasDynamicArg);
        assertTrue(hasSyncBoundRect, "GUIRtgFurnace's dualCookTime gauge must be drawn as a live sync-bound rect");

        // The electric-furnace power bar animates its destination Y coordinate, not only its
        // texture source/size. It must survive parsing as a tile-bound rect.
        GuiProfile.GuiEntry electric = p.lookup("com.hbm.inventory.container.ContainerElectricFurnace");
        assertTrue(electric != null, "ContainerElectricFurnace must be resolvable in the real corpus");
        assertTrue(electric.rects.stream().anyMatch(r -> r.yTileExpr != null && r.hasDynamicArg()),
                "electric-furnace power bar must retain its dynamic tile-field Y position");

        // TILE-FIELD-SNAPSHOT lane: the adversarial "prove the new mechanism actually engages on
        // the real corpus" check, same spirit as the sync-bound assertion above - not just present
        // in some synthetic fixture.
        assertTrue(p.rectsTileBoundDrawable > 0,
                "at least one real rect must be drawn via a live per-tile-entity snapshot binding");
        assertTrue(p.rectsTileGuardEvaluatedDrawable > 0,
                "at least one real rect must be drawn via a live per-tile-entity guard");
        boolean anyGuiHasTileFieldRefs = p.entries().stream().anyMatch(e -> !e.tileFieldRefs.isEmpty());
        assertTrue(anyGuiHasTileFieldRefs, "at least one real GUI must request a non-empty tile-field snapshot");

        System.out.println("[GuiProfileRectsTest] real-corpus drawable rects checked=" + checked
                + " rectsTotal=" + p.rectsTotal + " backgroundDupSkipped=" + p.rectsBackgroundDupSkipped
                + " guardedSkipped=" + p.rectsGuardedSkipped + " unbindableSkipped=" + p.rectsUnbindableSkipped
                + " noTextureSkipped=" + p.rectsNoTextureSkipped + " outOfPanelSkipped=" + p.rectsOutOfPanelSkipped
                + " drawable=" + p.rectsDrawable + " syncBoundDrawable=" + p.rectsSyncBoundDrawable
                + " syncGuardEvaluatedDrawable=" + p.rectsSyncGuardEvaluatedDrawable
                + " tileBoundDrawable=" + p.rectsTileBoundDrawable
                + " tileGuardEvaluatedDrawable=" + p.rectsTileGuardEvaluatedDrawable);
        System.out.println("[GuiProfileRectsTest] real-corpus drawable labels checked=" + labelsChecked
                + " labelsTotal=" + p.labelsTotal + " labelsUnresolvedSkipped=" + p.labelsUnresolvedSkipped
                + " labelsDrawable=" + p.labelsDrawable);
    }
}
