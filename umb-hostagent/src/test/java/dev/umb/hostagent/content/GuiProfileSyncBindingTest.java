package dev.umb.hostagent.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SYNC-BINDING lane gate: {@link GuiProfile}'s consumption of umb-guimap schemaVersion 4's
 * {@code container.syncBindings} / {@code conditional.fieldCondition}. See
 * {@code research/out/legacy/guimap-notes/SYNC-BINDING.md} for the real-corpus numbers this backs -
 * exactly ONE real rect (HBM's {@code GUIRtgFurnace} dualCookTime gauge) clears every bar below;
 * these tests prove the MECHANISM with synthetic fixtures shaped like the real winning case AND the
 * real still-blocked cases (an unbindable field-divisor, a resolvable guard whose OTHER args stay
 * unresolved), so the mechanism is verified end-to-end even though the real jar exercises only a
 * sliver of it.
 */
class GuiProfileSyncBindingTest {

    private static final String CONTAINER_CLASS = "com.hbm.inventory.container.ContainerFakeRtgFurnace";
    private static final String TE_CLASS = "com.hbm.tileentity.machine.TileEntityFakeRtgFurnace";

    private static String json(String syncBindings, String rectJson) {
        return """
            {
              "guis": [{
                "className": "com.hbm.inventory.gui.GUIFakeRtgFurnace",
                "kind": "GuiContainer",
                "container": {"className": "%s", "confidence": "exact", "syncBindings": %s},
                "size": {"xSize": 200, "ySize": 150, "confidence": "exact"},
                "backgroundTextures": [
                  {"path": "hbm:textures/gui/fake.png", "resolved": true, "existsInJar": true,
                   "assetPath": "assets/hbm/textures/gui/fake.png", "sheetWidth": 256, "sheetHeight": 256}
                ],
                "backgroundDrawRects": [%s],
                "foregroundLabels": []
              }]
            }
            """.formatted(CONTAINER_CLASS, syncBindings, rectJson);
    }

    private static String binding(int syncIndex, String field, boolean server, boolean client, Boolean agree) {
        String agreeJson = agree == null ? "null" : agree.toString();
        return """
            {"syncIndex": %d, "field": {"ownerClass": "%s", "fieldName": "%s"},
             "serverRoute": %s, "clientRoute": %s, "agree": %s}
            """.formatted(syncIndex, TE_CLASS, field, server, client, agreeJson);
    }

    @Test
    void boundNumeratorWithConstDivisorIsDrawnLiveNotFrozen() {
        // The real GUIRtgFurnace shape: w = dualCookTime * 24 / 1000 + 1, unguarded, dualCookTime
        // bound at sync index 0 with both routes agreeing.
        String bindings = "[" + binding(0, "dualCookTime", true, true, true) + "]";
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"stateLinear","classification":"STATE_LINEAR",
                 "stateBinding": {"source": {"ownerClass":"%s","fieldName":"dualCookTime"},
                                   "multiplier": 24.0, "divisor": {"kind":"const","value":1000.0},
                                   "offset": 1, "sign": 1}},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": true, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """.formatted(TE_CLASS);
        GuiProfile p = GuiProfile.parseString(json(bindings, rect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);

        assertEquals(1, e.rects.size(), "the bound STATE_LINEAR rect must now be drawable");
        GuiProfile.Rect r = e.rects.get(0);
        assertTrue(r.hasDynamicArg());
        assertNull(r.uExpr);
        assertNull(r.vExpr);
        assertNull(r.hExpr);
        assertEquals(0, r.wExpr.numeratorSyncIndex);
        assertEquals(24.0, r.wExpr.multiplier);
        assertFalse(r.wExpr.divisorIsSyncIndex);
        assertEquals(1000.0, r.wExpr.divisorConst);
        assertEquals(1, r.wExpr.offset);
        assertEquals(1, r.wExpr.sign);

        assertEquals(1, p.rectsDrawable);
        assertEquals(1, p.rectsSyncBoundDrawable);
        assertEquals(0, p.rectsUnbindableSkipped);

        // evaluate() at a few live register values - the exact formula umb-guimap extracted.
        assertEquals(1 + (int) (0 * 24.0 / 1000.0), r.wExpr.evaluate(i -> 0));
        assertEquals(1 + (int) (500 * 24.0 / 1000.0), r.wExpr.evaluate(i -> 500));
        assertEquals(13, r.wExpr.evaluate(i -> 500)); // 500*24/1000=12, +1 offset = 13
    }

    @Test
    void boundNumeratorWithUnboundFieldDivisorStaysUnbindable() {
        // The real GUIMachineRTG shape: heat is synced (id 0) but heatMax is NOT (no container in
        // the real corpus ever syncs it) - must stay unbindable, never assume a divisor value.
        String bindings = "[" + binding(0, "heat", true, true, true) + "]";
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"stateLinear","classification":"STATE_LINEAR",
                 "stateBinding": {"source": {"ownerClass":"%s","fieldName":"heat"},
                                   "multiplier": 51.0,
                                   "divisor": {"kind":"field","ownerClass":"%s","fieldName":"heatMax"},
                                   "offset": 0, "sign": 1}},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": true, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """.formatted(TE_CLASS, TE_CLASS);
        GuiProfile p = GuiProfile.parseString(json(bindings, rect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);

        assertEquals(0, e.rects.size(), "an unbound field divisor must never be assumed/guessed");
        assertEquals(1, p.rectsUnbindableSkipped);
        assertEquals(0, p.rectsSyncBoundDrawable);
    }

    @Test
    void disagreementBetweenRoutesExcludesTheFieldFromBindingRatherThanGuessing() {
        String bindings = "[" + binding(0, "dualCookTime", true, true, false) + "]"; // agree=false
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"stateLinear","classification":"STATE_LINEAR",
                 "stateBinding": {"source": {"ownerClass":"%s","fieldName":"dualCookTime"},
                                   "multiplier": 24.0, "divisor": {"kind":"const","value":1000.0},
                                   "offset": 1, "sign": 1}},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": true, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """.formatted(TE_CLASS);
        GuiProfile p = GuiProfile.parseString(json(bindings, rect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);

        assertEquals(0, e.rects.size(), "a disagreeing two-route binding must never be used to bind a rect");
        assertEquals(1, p.rectsUnbindableSkipped);
    }

    @Test
    void serverOnlyBindingWithNoClientRouteObservationIsStillUsable() {
        // Only the server route is architecturally required (see buildSyncFieldMap's javadoc) -
        // a container with no func_75137_b override at all must still bind.
        String bindings = "[" + binding(0, "dualCookTime", true, false, null) + "]";
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"stateLinear","classification":"STATE_LINEAR",
                 "stateBinding": {"source": {"ownerClass":"%s","fieldName":"dualCookTime"},
                                   "multiplier": 1.0, "divisor": {"kind":"const","value":1.0},
                                   "offset": 0, "sign": 1}},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": true, "conditional": {"guarded": false}, "textureBindIndex": 0
            }
            """.formatted(TE_CLASS);
        GuiProfile p = GuiProfile.parseString(json(bindings, rect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);
        assertEquals(1, e.rects.size(), "server-route-only evidence is still architecturally sufficient");
    }

    @Test
    void guardResolvesButAnotherArgStaysUnresolvedSoTheRectStaysSkipped() {
        // The real HBM GUIMachineTurbofan shape: "if (afterburner <= 0) return;" DOES resolve (its
        // field is bound), but the rect's own v argument is separately UNRESOLVED (arithmetic this
        // pass doesn't model) - the rect must land in unbindable, no longer in guarded, and never
        // get drawn. This is the concrete, real, still-honest remainder SYNC-BINDING.md reports.
        String bindings = "[" + binding(1, "afterburner", true, true, true) + "]";
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"dynamic","classification":"UNRESOLVED","desc":"?(arithmetic)","reason":"arithmetic"},
                {"kind":"const","classification":"CONST","value":16},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": true,
              "conditional": {"guarded": true, "guardDescription": "IFLE getfield afterburner",
                "fieldCondition": {"source": {"ownerClass":"%s","fieldName":"afterburner"}, "skipOp":"LE","skipValue":0}},
              "textureBindIndex": 0
            }
            """.formatted(TE_CLASS);
        GuiProfile p = GuiProfile.parseString(json(bindings, rect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);

        assertEquals(0, e.rects.size());
        assertEquals(0, p.rectsGuardedSkipped, "the guard itself DID resolve - it must not be counted as an unresolved guard");
        assertEquals(1, p.rectsUnbindableSkipped, "a separately-unresolved arg still blocks drawing");
    }

    @Test
    void guardedRectWithFullyResolvedArgsAndABoundGuardIsDrawnWithALiveSyncGuard() {
        // Synthetic best-case: proves the full pipe end-to-end (a shape the real HBM corpus does not
        // happen to contain, since every real fieldCondition-bearing rect there also has at least
        // one other unresolved arg - see the test above and SYNC-BINDING.md).
        String bindings = "[" + binding(1, "afterburner", true, true, true) + "]";
        String rect = """
            {
              "args": [
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiLeft","delta":53},
                {"kind":"position","classification":"PANEL_RELATIVE","base":"guiTop","delta":36},
                {"kind":"const","classification":"CONST","value":176},
                {"kind":"const","classification":"CONST","value":0},
                {"kind":"const","classification":"CONST","value":16},
                {"kind":"const","classification":"CONST","value":16}
              ],
              "dynamic": false,
              "conditional": {"guarded": true, "guardDescription": "IFLE getfield afterburner",
                "fieldCondition": {"source": {"ownerClass":"%s","fieldName":"afterburner"}, "skipOp":"LE","skipValue":0}},
              "textureBindIndex": 0
            }
            """.formatted(TE_CLASS);
        GuiProfile p = GuiProfile.parseString(json(bindings, rect));
        GuiProfile.GuiEntry e = p.lookup(CONTAINER_CLASS);

        assertEquals(1, e.rects.size(), "a fully-resolved rect guarded by ONE bound-field condition must be drawn (with a live guard)");
        GuiProfile.Rect r = e.rects.get(0);
        assertEquals(1, r.guard.syncIndex);
        assertEquals("LE", r.guard.skipOp);
        assertEquals(0, r.guard.skipValue);
        assertTrue(r.guard.shouldSkip(i -> 0), "afterburner<=0 must skip");
        assertFalse(r.guard.shouldSkip(i -> 5), "afterburner>0 must draw");
        assertEquals(1, p.rectsSyncGuardEvaluatedDrawable);
        assertEquals(0, p.rectsGuardedSkipped);
    }
}
