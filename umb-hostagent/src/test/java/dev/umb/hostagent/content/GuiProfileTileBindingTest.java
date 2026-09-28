package dev.umb.hostagent.content;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TILE-FIELD-SNAPSHOT lane gate: {@link GuiProfile}'s consumption of the {@code fieldRequirement}/
 * {@code skipCondition}/{@code guardNeeds} data GUARD-EXPRESSIONS.md and TILE-FIELD-REQUIREMENTS.md
 * describe — the mechanism that lifts real drawability from 29/818 to (per the real-corpus check in
 * {@code GuiProfileRectsTest}) 224/818. Fixtures use non-mod synthetic class names
 * ({@code com.example.*}), per the gate requirement every prior lane in this area follows.
 */
class GuiProfileTileBindingTest {

    private static final String CONTAINER_CLASS = "com.example.container.ContainerFakeMachine";
    private static final String TE_CLASS = "com.example.tileentity.TileEntityFakeMachine";
    private static final String GUI_CLASS = "com.example.gui.GUIFakeMachine";

    private static String json(String rectJson) {
        return """
            {
              "guis": [{
                "className": "%s",
                "kind": "GuiContainer",
                "container": {"className": "%s", "confidence": "exact", "syncBindings": []},
                "size": {"xSize": 176, "ySize": 166, "confidence": "exact"},
                "backgroundTextures": [
                  {"path": "example:textures/gui/fake.png", "resolved": true, "existsInJar": true,
                   "assetPath": "assets/example/textures/gui/fake.png", "sheetWidth": 256, "sheetHeight": 256}
                ],
                "backgroundDrawRects": [%s],
                "foregroundLabels": []
              }]
            }
            """.formatted(GUI_CLASS, CONTAINER_CLASS, rectJson);
    }

    private static String fieldRequirement(String fieldName, String desc) {
        return """
            {"reachedVia": "gui", "tileEntityClass": "%s",
             "hops": [{"ownerClass": "%s", "fieldName": "%s", "desc": "%s", "kind": "field"}]}
            """.formatted(TE_CLASS, TE_CLASS, fieldName, desc);
    }

    private static GuiProfile.GuiEntry parse(String rectJson) {
        GuiProfile p = GuiProfile.parseString(json(rectJson));
        return p.lookup(CONTAINER_CLASS);
    }

    // ---------------------------------------------------------------- 1. linear expr from a snapshot

    @Test
    void linearExpressionOverATileFieldNumeratorAndConstDivisorEvaluatesFromASnapshot() {
        // The real GUIStorageDrum-shaped case: w = amount * 79 / capacity (here: const divisor for
        // the simplest first cut, the field-divisor case is covered separately below).
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"stateLinear","classification":"STATE_LINEAR",
                 "stateBinding": {
                   "source": {"ownerClass":"%s","fieldName":"amount","fieldDesc":"I",
                              "fieldRequirement": %s},
                   "multiplier": 2.0, "divisor": {"kind":"const","value":100.0},
                   "offset": 1, "sign": 1}},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": true, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """.formatted(TE_CLASS, fieldRequirement("amount", "I"));

        GuiProfile.GuiEntry e = parse(rect);
        assertEquals(1, e.rects.size(), "a rect whose numerator resolves via fieldRequirement must be drawable");
        GuiProfile.Rect r = e.rects.get(0);
        assertNull(r.wExpr, "the OLDER sync mechanism must not also claim this slot");
        assertTrue(r.wTileExpr != null, "the NEW tile-snapshot mechanism must bind it");
        assertEquals(1, e.tileFieldRefs.size());
        assertEquals("amount", e.tileFieldRefs.get(0).key);

        Map<String, Double> snapshot = new HashMap<>();
        snapshot.put("amount", 40.0);
        Integer w = r.wTileExpr.evaluate(snapshot::get, 0, 0);
        assertEquals(1 + (int) (40.0 * 2.0 / 100.0), w); // 1 + 0 = 1 (integer truncation, same as SyncExpr's own convention)

        snapshot.put("amount", 4000.0);
        w = r.wTileExpr.evaluate(snapshot::get, 0, 0);
        assertEquals(81, w); // 1 + (4000*2/100)=80 -> 81
    }

    @Test
    void fieldDivisorResolvesTooNotJustTheNumerator() {
        // The real GUITurretSentry/GUIMachineRTG shape: divisor is ALSO a tile field (power/getMaxPower,
        // heat/heatMax) - architecturally unbindable via sync registers (SYNC-BINDING.md), but a live
        // per-tick snapshot has no such problem since it reads the real object every tick.
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"stateLinear","classification":"STATE_LINEAR",
                 "stateBinding": {
                   "source": {"ownerClass":"%s","fieldName":"heat","fieldDesc":"I", "fieldRequirement": %s},
                   "multiplier": 51.0,
                   "divisor": {"kind":"field","ownerClass":"%s","fieldName":"heatMax","fieldDesc":"I",
                               "fieldRequirement": %s},
                   "offset": 0, "sign": 1}},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": true, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """.formatted(TE_CLASS, fieldRequirement("heat", "I"), TE_CLASS, fieldRequirement("heatMax", "I"));

        GuiProfile.GuiEntry e = parse(rect);
        assertEquals(1, e.rects.size());
        assertEquals(2, e.tileFieldRefs.size(), "both the numerator and the field divisor must be requested");
        GuiProfile.Rect r = e.rects.get(0);

        Map<String, Double> snapshot = new HashMap<>();
        snapshot.put("heat", 300.0);
        snapshot.put("heatMax", 600.0);
        assertEquals(25, r.wTileExpr.evaluate(snapshot::get, 0, 0)); // 300*51/600 = 25.5 -> 25
    }

    // ---------------------------------------------------------------- 2. divisor of zero

    @Test
    void divisorFieldReadingZeroYieldsAZeroTermRatherThanCrashing() {
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"stateLinear","classification":"STATE_LINEAR",
                 "stateBinding": {
                   "source": {"ownerClass":"%s","fieldName":"amount","fieldDesc":"I", "fieldRequirement": %s},
                   "multiplier": 79.0,
                   "divisor": {"kind":"field","ownerClass":"%s","fieldName":"capacity","fieldDesc":"I",
                               "fieldRequirement": %s},
                   "offset": 1, "sign": 1}},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": true, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """.formatted(TE_CLASS, fieldRequirement("amount", "I"), TE_CLASS, fieldRequirement("capacity", "I"));

        GuiProfile.GuiEntry e = parse(rect);
        GuiProfile.Rect r = e.rects.get(0);

        Map<String, Double> snapshot = new HashMap<>();
        snapshot.put("amount", 500.0);
        snapshot.put("capacity", 0.0); // the machine has not initialised its capacity yet
        Integer w = r.wTileExpr.evaluate(snapshot::get, 0, 0);
        assertEquals(1, w, "a zero divisor must yield a zero ratio term, never throw or produce garbage");
    }

    // ---------------------------------------------------------------- 3. hover guard from mouse position

    @Test
    void hoverGuardOverAPanelRelativeBoxIsEvaluatedFromTheLiveMousePosition() {
        // The real GUITurretBase-shaped guard: skip unless the mouse is inside a panel-relative box.
        String skipCondition = """
            {"op":"OR","operands":[
              {"op":"COMPARE","compareOp":"LT","left":{"kind":"panelOrigin","axis":"guiTop","delta":98},
                                                  "right":{"kind":"mouse","axis":"y"}},
              {"op":"COMPARE","compareOp":"GE","left":{"kind":"panelOrigin","axis":"guiTop","delta":80},
                                                  "right":{"kind":"mouse","axis":"y"}},
              {"op":"COMPARE","compareOp":"LE","left":{"kind":"panelOrigin","axis":"guiLeft","delta":25},
                                                  "right":{"kind":"mouse","axis":"x"}},
              {"op":"COMPARE","compareOp":"GT","left":{"kind":"panelOrigin","axis":"guiLeft","delta":7},
                                                  "right":{"kind":"mouse","axis":"x"}}
            ]}
            """;
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":7},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":80},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":18},
                {"kind":"const","classification":"CONST","value":18}
              ],
              "dynamic": false,
              "conditional": {"guarded": true,
                "guardDescription": "hover test",
                "skipCondition": %s,
                "guardNeeds": {"level": "tileFieldsAndMouse", "needsMethodCall": false}},
              "textureBindIndex": 0
            }
            """.formatted(skipCondition);

        GuiProfile.GuiEntry e = parse(rect);
        assertEquals(1, e.rects.size(), "a hover-only guard (no fieldCondition) must still resolve via skipCondition");
        GuiProfile.Rect r = e.rects.get(0);
        assertTrue(r.tileGuard != null);

        Map<String, Double> noFieldsNeeded = Map.of();
        int guiLeft = 10, guiTop = 20; // panel box in absolute terms: x in (17,32], y in (100,118)
        // Inside the hover box.
        assertFalse(r.tileGuard.shouldSkip(noFieldsNeeded::get, 20, 105, guiLeft, guiTop), "mouse inside the box must draw");
        // Outside the hover box (mouse way off to the top-left).
        assertTrue(r.tileGuard.shouldSkip(noFieldsNeeded::get, 0, 0, guiLeft, guiTop), "mouse outside the box must skip");
    }

    // ---------------------------------------------------------------- 4. absent snapshot skips

    @Test
    void anAbsentTileFieldSkipsRatherThanDrawingAZeroOrStaleValue() {
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"stateLinear","classification":"STATE_LINEAR",
                 "stateBinding": {
                   "source": {"ownerClass":"%s","fieldName":"amount","fieldDesc":"I", "fieldRequirement": %s},
                   "multiplier": 1.0, "divisor": {"kind":"const","value":1.0},
                   "offset": 0, "sign": 1}},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": true, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """.formatted(TE_CLASS, fieldRequirement("amount", "I"));

        GuiProfile.GuiEntry e = parse(rect);
        GuiProfile.Rect r = e.rects.get(0);

        // The tile was removed, or no snapshot has arrived yet - the lookup reports every key absent.
        java.util.function.Function<String, Double> emptySnapshot = key -> null;
        Integer w = r.wTileExpr.evaluate(emptySnapshot, 0, 0);
        assertNull(w, "an absent tile field must yield null (skip), never a fabricated 0");
    }

    @Test
    void anAbsentGuardFieldAlsoSkipsRatherThanDrawing() {
        String skipCondition = """
            {"op":"COMPARE","compareOp":"NE",
             "left":{"kind":"tileField","needsMethodCall":false,"fieldRequirement": %s},
             "right":{"kind":"const","value":0}}
            """.formatted(fieldRequirement("sceneNumber", "I"));
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":16},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": false,
              "conditional": {"guarded": true, "guardDescription": "x",
                "skipCondition": %s,
                "guardNeeds": {"level": "tileFieldsOnly", "needsMethodCall": false}},
              "textureBindIndex": 0
            }
            """.formatted(skipCondition);

        GuiProfile.GuiEntry e = parse(rect);
        GuiProfile.Rect r = e.rects.get(0);
        java.util.function.Function<String, Double> emptySnapshot = key -> null;
        assertTrue(r.tileGuard.shouldSkip(emptySnapshot, 0, 0, 0, 0),
                "an undecidable guard (its own field absent this frame) must count as skip, never as safe-to-draw");
    }

    // ---------------------------------------------------------------- 5. clamp to the texture region

    @Test
    void computedWidthExceedingTheTextureRegionClampsRatherThanOverflowingThePanel() {
        assertEquals(10, GuiProfile.clampToPanel(50, 10, 176), "well within bounds - unchanged");
        assertEquals(6, GuiProfile.clampToPanel(170, 40, 176), "overshoots the panel edge - clamped to what remains");
        assertEquals(0, GuiProfile.clampToPanel(200, 40, 176), "starts entirely past the panel - clamps to zero, never negative");
        assertEquals(0, GuiProfile.clampToPanel(50, -5, 176), "a live value that went negative also clamps to zero");
    }

    // ---------------------------------------------------------------- distrust of a both-const skipCondition leaf

    @Test
    void aBothConstSkipConditionLeafOutsideFieldConditionIsNeverTrustedAsATautology() {
        // GAUGE-RENDER.md's own documented extractor artifact: a bare single-operand IFxx test can
        // come back through skipCondition as {const,const} even though the real operand is a live,
        // mutable tile field (fieldCondition, tried first, is unaffected - this only matters when
        // skipCondition is the sole source, e.g. as one branch of a genuine multi-frame OR).
        String skipCondition = """
            {"op":"OR","operands":[
              {"op":"COMPARE","compareOp":"NE","left":{"kind":"const","value":0},"right":{"kind":"const","value":0}},
              {"op":"COMPARE","compareOp":"EQ","left":{"kind":"mouse","axis":"x"},"right":{"kind":"const","value":999}}
            ]}
            """;
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":16},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": false,
              "conditional": {"guarded": true, "guardDescription": "x",
                "skipCondition": %s,
                "guardNeeds": {"level": "tileFieldsAndMouse", "needsMethodCall": false}},
              "textureBindIndex": 0
            }
            """.formatted(skipCondition);

        GuiProfile.GuiEntry e = parse(rect);
        assertEquals(1, e.rects.size(), "sanity: this rect must not be mistaken for the background-dup shape");
        GuiProfile.Rect r = e.rects.get(0);
        java.util.function.Function<String, Double> noFields = key -> null;
        // The genuinely resolvable mouse branch firing true is enough on its own to skip.
        assertTrue(r.tileGuard.shouldSkip(noFields, 999, 0, 0, 0), "the real (mouse) branch resolving true must skip");
        // With the mouse branch false and the OTHER (distrusted, const-const) branch unknown, the
        // whole OR must stay undecided rather than confidently resolving to "false" (which would
        // have meant "safe to draw") - the distrust rule's entire point is to never let a
        // possibly-wrong compile-time-folded leaf grant a pass the real bytecode might not have.
        assertTrue(r.tileGuard.shouldSkip(noFields, 1, 0, 0, 0),
                "with the distrusted branch unknown and the real branch false, the guard must stay undecided - skip");
    }
}
