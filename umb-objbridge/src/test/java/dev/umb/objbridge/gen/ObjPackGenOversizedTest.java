package dev.umb.objbridge.gen;

import com.google.gson.JsonParser;
import dev.umb.objbridge.transform.RendererTransforms;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ObjPackGen.oversized}/{@code itemDef} - the pack-generation-time half of the
 * {@code "oversized_in_gui"} wiring (see {@code dev.umb.objbridge.item.ObjTransforms}'s class docs for
 * the javap evidence this is a real, data-driven, top-level client-item JSON property in 26.2). Uses
 * synthetic {@link RendererTransforms} so it needs no real data checked into the repo; the REAL-data
 * cross-check lives in {@code dev.umb.objbridge.InventoryFixVerificationTest}.
 */
class ObjPackGenOversizedTest {

    private static RendererTransforms rt(String json) {
        return RendererTransforms.of(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void nullRendererClassIsNeverOversized() {
        Map<String, Integer> counts = new HashMap<>();
        assertFalse(ObjPackGen.oversized(rt("{\"renderers\":{}}"), null, counts));
        assertEquals(1, counts.get("no-renderer-class"));
    }

    @Test
    void classUnknownToTransformsIsNeverOversized() {
        Map<String, Integer> counts = new HashMap<>();
        assertFalse(ObjPackGen.oversized(rt("{\"renderers\":{}}"), "com.example.Unknown", counts));
        assertEquals(1, counts.get("unknown-to-transforms"));
    }

    @Test
    void knownClassWithNoPerspectiveOpsIsNeverOversized() {
        RendererTransforms t = rt("""
                { "renderers": { "com.example.WorldOnly": [
                    {"method":"func_147500_a","op":"glScaled","args":[2,2,2],"dynamic":false}
                ]}}
                """);
        Map<String, Integer> counts = new HashMap<>();
        assertFalse(ObjPackGen.oversized(t, "com.example.WorldOnly", counts));
        assertEquals(1, counts.get("no-perspective-ops"));
    }

    @Test
    void aRendererThatScalesInventoryWellUpIsOversized() {
        // mirrors RenderRadarLarge$1's real shape: renderInventory scales up hard, only a much smaller
        // shared common step otherwise - see InventoryFixVerificationTest for the real-data version.
        RendererTransforms t = rt("""
                { "renderers": { "com.example.BigInSlot": [
                    {"method":"renderInventory","op":"glScaled","args":[3,3,3],"dynamic":false},
                    {"method":"renderCommonWithStack","op":"glScaled","args":[0.5,0.5,0.5],"dynamic":false}
                ]}}
                """);
        Map<String, Integer> counts = new HashMap<>();
        assertTrue(ObjPackGen.oversized(t, "com.example.BigInSlot", counts));
        assertTrue(counts.isEmpty(), "a def that IS oversized bumps no skip-reason counter");
    }

    @Test
    void aRendererThatScalesEverythingEquallyStaysUnderThreshold() {
        // single data point (common only) - no ratio information, multiplier is always 1.0, so the
        // derived GUI scale is exactly vanilla's 0.625, comfortably under the 1.0 threshold.
        RendererTransforms t = rt("""
                { "renderers": { "com.example.CommonOnly": [
                    {"method":"renderCommon","op":"glScaled","args":[0.5,0.5,0.5],"dynamic":false}
                ]}}
                """);
        Map<String, Integer> counts = new HashMap<>();
        assertFalse(ObjPackGen.oversized(t, "com.example.CommonOnly", counts));
        assertEquals(1, counts.get("under-threshold"));
    }

    @Test
    void itemDefOmitsOversizedFieldWhenFalseAndIncludesItWhenTrue() {
        String plain = ObjPackGen.itemDef("hbm:models/x.obj", "hbm:models/x");
        assertFalse(plain.contains("oversized_in_gui"));
        assertTrue(plain.contains("\"type\": \"umb:obj\""));

        String big = ObjPackGen.itemDef("hbm:models/x.obj", "hbm:models/x", true);
        assertTrue(big.contains("\"oversized_in_gui\": true"));
        assertTrue(big.contains("\"model\":"), "the model object must still be present alongside it");

        String small = ObjPackGen.itemDef("hbm:models/x.obj", "hbm:models/x", false);
        assertFalse(small.contains("oversized_in_gui"));
        assertEquals(plain, small, "explicit false must render identically to the 2-arg overload");
    }

    @Test
    void itemDefProducesParseableJson() {
        String json = ObjPackGen.itemDef("hbm:models/x.obj", "hbm:models/x", true);
        com.google.gson.JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(o.get("oversized_in_gui").getAsBoolean());
        assertEquals("umb:obj", o.getAsJsonObject("model").get("type").getAsString());
    }
}
