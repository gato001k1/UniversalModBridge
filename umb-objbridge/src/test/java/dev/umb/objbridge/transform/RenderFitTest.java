package dev.umb.objbridge.transform;

import com.google.gson.JsonParser;
import dev.umb.objbridge.bake.Fit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFitTest {

    private static RendererTransforms rt(String json) {
        return RendererTransforms.of(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void unknownClassFallsBackToLegacyAutoFit() {
        RendererTransforms transforms = rt("{ \"renderers\": {} }");
        RenderFit.Outcome o = RenderFit.forPath(transforms, "com.example.Unknown", PathClass.WORLD, true);
        assertFalse(o.resolved());
        assertTrue(o.fit().auto(), "unresolved must fall back to the legacy auto-fit Fit, per the brief");
        assertEquals(1.0f, o.fit().size(), 0f);
    }

    @Test
    void nullClassFallsBackToLegacyAutoFit() {
        RendererTransforms transforms = rt("{ \"renderers\": {} }");
        RenderFit.Outcome o = RenderFit.forPath(transforms, null, PathClass.WORLD, true);
        assertFalse(o.resolved());
        assertTrue(o.fit().auto());
    }

    @Test
    void knownClassWithNoOpsForThePathIsResolvedAsIdentityNotAFallback() {
        // this is the Tsar Bomba / large radar / anvil shape: the class IS in the file, it just has no
        // ops classified into WORLD (e.g. only renderInventory ops), and that absence means "authored
        // coordinates pass through unscaled" - which is a resolved, meaningful answer, not a guess.
        RendererTransforms transforms = rt("""
                { "renderers": { "com.example.Radar": [
                    {"method":"renderInventory","op":"glScaled","args":[3,3,3],"dynamic":false}
                ]}}
                """);
        RenderFit.Outcome o = RenderFit.forPath(transforms, "com.example.Radar", PathClass.WORLD, true);
        assertTrue(o.resolved());
        assertTrue(o.identity());
        assertFalse(o.fit().auto());
        assertEquals(0, o.opsUsed());
        assertEquals(Fit.IDENTITY.length, o.fit().linear().length);
        assertEquals(1f, o.fit().linear()[0], 0f);
        assertEquals(0f, o.fit().linear()[1], 0f);
        assertEquals(1f, o.fit().linear()[4], 0f);
        assertEquals(1f, o.fit().linear()[8], 0f);
    }

    @Test
    void knownClassWithAScaleOpIsResolvedWithThatScale() {
        RendererTransforms transforms = rt("""
                { "renderers": { "com.example.Thing": [
                    {"method":"func_147500_a","op":"glScaled","args":[2,2,2],"dynamic":false}
                ]}}
                """);
        RenderFit.Outcome o = RenderFit.forPath(transforms, "com.example.Thing", PathClass.WORLD, true);
        assertTrue(o.resolved());
        assertFalse(o.identity());
        assertEquals(1, o.opsUsed());
        assertEquals(2f, o.fit().linear()[0], 1e-5f);
        assertEquals(2f, o.fit().linear()[4], 1e-5f);
        assertEquals(2f, o.fit().linear()[8], 1e-5f);
    }

    @Test
    void dynamicSkipCountAndPushesArePassedThrough() {
        RendererTransforms transforms = rt("""
                { "renderers": { "com.example.Dyn": [
                    {"method":"func_147500_a","op":"glPushMatrix","args":[],"dynamic":false},
                    {"method":"func_147500_a","op":"glTranslated","args":[null,null,null],
                     "dynamicArgs":[0,1,2],"dynamic":true},
                    {"method":"func_147500_a","op":"glPopMatrix","args":[],"dynamic":false}
                ]}}
                """);
        RenderFit.Outcome o = RenderFit.forPath(transforms, "com.example.Dyn", PathClass.WORLD, true);
        assertTrue(o.resolved());
        assertEquals(1, o.dynamicSkipped());
        assertEquals(1, o.pushes());
        assertFalse(o.unbalanced());
    }
}
