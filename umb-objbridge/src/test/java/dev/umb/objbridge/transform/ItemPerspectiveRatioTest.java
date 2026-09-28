package dev.umb.objbridge.transform;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ItemPerspectiveRatioTest {

    private static RendererTransforms rt(String json) {
        return RendererTransforms.of(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void commonOnlyClassHasNoInvFpTpButBucketsStillReportCommon() {
        RendererTransforms transforms = rt("""
                { "renderers": { "com.example.CommonOnly": [
                    {"method":"renderCommon","op":"glScaled","args":[0.5,0.5,0.5],"dynamic":false}
                ]}}
                """);
        ItemPerspectiveRatio.Buckets b = ItemPerspectiveRatio.buckets(transforms, "com.example.CommonOnly");
        assertNull(b.inv());
        assertNull(b.fp());
        assertNull(b.tp());
        assertEquals(0.5f, b.common(), 1e-6f);
        assertEquals(1, b.found());
        // effective values all fall back to common
        assertEquals(0.5f, b.invEffective(), 1e-6f);
        assertEquals(0.5f, b.fpEffective(), 1e-6f);
        assertEquals(0.5f, b.tpEffective(), 1e-6f);
        // a single data point carries no RATIO information relative to its own mean - multiplier is 1.0
        assertEquals(1.0f, ItemPerspectiveRatio.ratioMultiplier(b.invEffective(), b.baseline()), 1e-6f);
    }

    @Test
    void commonSubstitutesOnlyForTheMissingBucketsNotTheOnesWithRealData() {
        // inv is differentiated (0.9); fp/tp are not, so they fall back to common (0.6).
        RendererTransforms transforms = rt("""
                { "renderers": { "com.example.Mixed": [
                    {"method":"renderInventory","op":"glScaled","args":[0.9,0.9,0.9],"dynamic":false},
                    {"method":"renderCommon","op":"glScaled","args":[0.6,0.6,0.6],"dynamic":false}
                ]}}
                """);
        ItemPerspectiveRatio.Buckets b = ItemPerspectiveRatio.buckets(transforms, "com.example.Mixed");
        assertEquals(0.9f, b.invEffective(), 1e-6f, "inv has its own data - common must not override it");
        assertEquals(0.6f, b.fpEffective(), 1e-6f, "fp has none - falls back to common");
        assertEquals(0.6f, b.tpEffective(), 1e-6f, "tp has none - falls back to common");
        // baseline is the geometric mean of the RAW found values only (inv, common) - common is not
        // double-counted once for itself and again as the fp/tp substitute.
        float expectedBaseline = (float) Math.sqrt(0.9 * 0.6);
        assertEquals(expectedBaseline, b.baseline(), 1e-5f);
    }

    @Test
    void ratioMultiplierIsClampedToTheDocumentedRange() {
        assertEquals(ItemPerspectiveRatio.RATIO_MAX,
                ItemPerspectiveRatio.ratioMultiplier(100.0f, 1.0f), 1e-6f);
        assertEquals(ItemPerspectiveRatio.RATIO_MIN,
                ItemPerspectiveRatio.ratioMultiplier(0.0001f, 1.0f), 1e-6f);
        assertEquals(1.0f, ItemPerspectiveRatio.ratioMultiplier(null, 1.0f), 0f, "no data == no-op");
        assertEquals(1.0f, ItemPerspectiveRatio.ratioMultiplier(1.0f, 0.0f), 0f, "invalid baseline == no-op");
    }

    @Test
    void unknownClassHasNoBucketsAtAll() {
        RendererTransforms transforms = rt("{ \"renderers\": {} }");
        ItemPerspectiveRatio.Buckets b = ItemPerspectiveRatio.buckets(transforms, "com.example.Unknown");
        assertEquals(0, b.found());
    }
}
