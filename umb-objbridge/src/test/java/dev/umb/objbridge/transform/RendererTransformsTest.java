package dev.umb.objbridge.transform;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RendererTransformsTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    @Test
    void parsesArgsAndMarksDynamicSlotsAsNaN() {
        RendererTransforms rt = RendererTransforms.of(json("""
                { "renderers": {
                    "com.example.Foo": [
                      {"method":"renderInventory","op":"glScaled","args":[3,3,3],"dynamic":false},
                      {"method":"func_147500_a","op":"glTranslated","args":[null,1.5,null],
                       "dynamicArgs":[0,2],"dynamic":true,"note":"arithmetic"},
                      {"method":"func_147500_a","op":"glPushMatrix","args":[],"dynamic":false}
                    ]
                }}
                """));
        List<TransformOp> ops = rt.forClass("com.example.Foo");
        assertEquals(3, ops.size());

        TransformOp scale = ops.get(0);
        assertEquals("glScaled", scale.op());
        assertEquals(3f, scale.args()[0], 0f);
        assertFalse(scale.dynamic());
        assertFalse(scale.hasUnresolvedArg());

        TransformOp translate = ops.get(1);
        assertTrue(translate.dynamic());
        assertTrue(Float.isNaN(translate.args()[0]));
        assertEquals(1.5f, translate.args()[1], 0f);
        assertTrue(Float.isNaN(translate.args()[2]));
        assertTrue(translate.hasUnresolvedArg());
        assertEquals("arithmetic", translate.note());

        TransformOp push = ops.get(2);
        assertTrue(push.isPush());
        assertEquals(0, push.args().length);
    }

    @Test
    void unknownClassReturnsEmptyNotNull() {
        RendererTransforms rt = RendererTransforms.of(json("{ \"renderers\": {} }"));
        assertEquals(List.of(), rt.forClass("com.example.Missing"));
        assertFalse(rt.hasClass("com.example.Missing"));
        assertEquals(0, rt.classCount());
    }

    @Test
    void nullOrMissingRootDoesNotThrow() {
        RendererTransforms rt = RendererTransforms.of(null);
        assertEquals(0, rt.classCount());
        assertEquals(List.of(), rt.forClass(null));
    }

    @Test
    void readsTheRealShippedFileAndFindsTheKnownExamples() throws IOException {
        Path p = Path.of("research/out/legacy/rendermap/renderer-transforms.json");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(p), "renderer-transforms.json not present");
        RendererTransforms rt = RendererTransforms.read(p);
        assertTrue(rt.classCount() > 600, "expected ~632 renderer classes, got " + rt.classCount());
        assertTrue(rt.hasClass("com.hbm.render.tileentity.RenderNukeTsar"));
        assertTrue(rt.hasClass("com.hbm.render.tileentity.RenderRadarLarge"));

        // the measured root cause: the Tsar Bomba's TESR has zero scale ops on its world-draw path
        List<TransformOp> world = TransformComposer.filter(
                rt.forClass("com.hbm.render.tileentity.RenderNukeTsar"), PathClass.WORLD);
        assertFalse(world.isEmpty(), "RenderNukeTsar must have func_147500_a ops");
        assertTrue(world.stream().noneMatch(TransformOp::isScale),
                "RenderNukeTsar's world-draw path must apply no scale - that IS the bug");
    }
}
