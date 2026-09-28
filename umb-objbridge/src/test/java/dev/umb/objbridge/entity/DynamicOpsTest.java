package dev.umb.objbridge.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.umb.objbridge.ObjBridge;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicOpsTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static final String SIDECAR = """
            {"schema":"umb.renderer-dynamic-ops.v1","bounds":{},
             "renderers":{
               "test.Radar":{
                 "teClass":"test.TE","partialArg":4,
                 "ops":[
                   {"method":"func_147500_a","op":"glPushMatrix","args":[]},
                   {"method":"func_147500_a","op":"glRotatef",
                    "args":[{"k":"const","v":180.0},{"k":"const","v":0.0},
                            {"k":"const","v":1.0},{"k":"const","v":0.0}]},
                   {"method":"func_147500_a","op":"glPushMatrix","args":[],"group":"Dish"},
                   {"method":"func_147500_a","op":"glRotatef","group":"Dish",
                    "args":[{"k":"const","v":180.0},{"k":"const","v":0.0},
                            {"k":"const","v":1.0},{"k":"const","v":0.0}]},
                   {"method":"func_147500_a","op":"glRotatef","group":"Dish",
                    "args":[{"k":"op","o":"fadd","a":[
                                {"k":"field","key":"TE.prev","hops":["prev"],"desc":"F"},
                                {"k":"op","o":"fmul","a":[
                                    {"k":"op","o":"fsub","a":[
                                        {"k":"field","key":"TE.cur","hops":["cur"],"desc":"F"},
                                        {"k":"field","key":"TE.prev","hops":["prev"],"desc":"F"}]},
                                    {"k":"partial"}]}]},
                            {"k":"const","v":0.0},{"k":"const","v":-1.0},{"k":"const","v":0.0}]},
                   {"method":"renderInventory","op":"glScaled","group":"Dish",
                    "args":[{"k":"const","v":3.0},{"k":"const","v":3.0},{"k":"const","v":3.0}]}
                 ],
                 "fields":[{"key":"TE.prev","hops":["prev"]}],
                 "resolvedArgs":5,"skippedArgs":{}},
                "test.Plain":{
                  "teClass":"test.TE2","partialArg":-1,
                  "ops":[{"method":"func_147500_a","op":"glRotatef","group":"Base",
                          "args":[{"k":"const","v":90.0},{"k":"const","v":0.0},
                                  {"k":"const","v":1.0},{"k":"const","v":0.0}]}],
                  "fields":[],"resolvedArgs":4,"skippedArgs":{}},
                "test.RadarHelper":{
                  "teClass":"test.TE","partialArg":-1,
                  "ops":[{"method":"func_147500_a","op":"glTranslated","group":"Antenna",
                          "args":[{"k":"channel","key":"test.Track.eval.DOOR[1]"},
                                  {"k":"const","v":0.0},{"k":"const","v":0.0}]}],
                  "fields":[],"resolvedArgs":1,"skippedArgs":{}},
                "test.Prefixed":{
                  "teClass":"test.TE3","partialArg":-1,
                  "ops":[],
                  "fields":[],"resolvedArgs":0,"skippedArgs":{},
                  "prefix":{"translate":[0.5,0.0,0.5],"metaBase":10,
                            "facing":{"2":[90.0,0.0,1.0,0.0],"3":[180.0,0.0,1.0,0.0]}}},
                "test.RadarTwin":{
                  "teClass":"test.TE","partialArg":-1,
                  "bound":true,
                  "ops":[{"method":"func_147500_a","op":"glRotatef","group":"TwinDish",
                          "args":[{"k":"const","v":45.0},{"k":"const","v":0.0},
                                  {"k":"const","v":1.0},{"k":"const","v":0.0}]}],
                  "fields":[],"resolvedArgs":1,"skippedArgs":{}}},
              "blocks":{}}""";

    private static Path writeSidecar(Path dir) throws Exception {
        Path transforms = dir.resolve("renderer-transforms.json");
        Files.writeString(transforms, "{\"renderers\":{}}", StandardCharsets.UTF_8);
        Path sidecar = dir.resolve("renderer-dynamic-ops.json");
        Files.writeString(sidecar, SIDECAR, StandardCharsets.UTF_8);
        Path map = dir.resolve("map.json");
        Files.writeString(map, "{\"blocks\":[]}", StandardCharsets.UTF_8);
        return dir;
    }

    @Test
    void evaluatesCallsAndOpsWithExactNarrowing(@TempDir Path tmp) {
        Map<String, Double> noValues = Map.of();
        assertEquals(3.0, DynamicOps.eval(json("{\"k\":\"const\",\"v\":3.0}"), noValues, 0f, 0L));
        assertEquals(0.5, DynamicOps.eval(
                json("{\"k\":\"op\",\"o\":\"fmul\",\"a\":[{\"k\":\"const\",\"v\":2.0},{\"k\":\"partial\"}]}"),
                noValues, 0.25f, 0L));
        assertEquals(180.0, DynamicOps.eval(
                json("{\"k\":\"call\",\"o\":\"java/lang/Math\",\"m\":\"toDegrees\","
                        + "\"a\":[{\"k\":\"const\",\"v\":" + Math.PI + "}]}"),
                noValues, 0f, 0L), 1e-9);
        assertEquals(2.0, DynamicOps.eval(
                json("{\"k\":\"call\",\"o\":\"net/minecraft/util/MathHelper\",\"m\":\"clamp_double\","
                        + "\"a\":[{\"k\":\"const\",\"v\":5.0},{\"k\":\"const\",\"v\":0.0},"
                        + "{\"k\":\"const\",\"v\":2.0}]}"),
                noValues, 0f, 0L));
        // Integer division truncates like the JVM, it does not produce 2.5.
        assertEquals(2.0, DynamicOps.eval(
                json("{\"k\":\"op\",\"o\":\"idiv\",\"a\":[{\"k\":\"const\",\"v\":5.0},"
                        + "{\"k\":\"const\",\"v\":2.0}]}"),
                noValues, 0f, 0L));
        // Division by zero is a skip, never an infinity leaking into a matrix.
        assertNull(DynamicOps.eval(
                json("{\"k\":\"op\",\"o\":\"idiv\",\"a\":[{\"k\":\"const\",\"v\":5.0},"
                        + "{\"k\":\"const\",\"v\":0.0}]}"),
                noValues, 0f, 0L));
        // (byte)257 wraps to 1, exactly like i2b on the JVM.
        assertEquals(1.0, DynamicOps.eval(
                json("{\"k\":\"op\",\"o\":\"i2b\",\"a\":[{\"k\":\"const\",\"v\":257.0}]}"),
                noValues, 0f, 0L));
        // Unknown call/owner/role and missing field are skips, never guesses.
        assertNull(DynamicOps.eval(
                json("{\"k\":\"call\",\"o\":\"mod/Engine\",\"m\":\"foo\",\"a\":[]}"), noValues, 0f, 0L));
        assertNull(DynamicOps.eval(
                json("{\"k\":\"field\",\"key\":\"TE.missing\",\"hops\":[\"missing\"],\"desc\":\"F\"}"),
                noValues, 0f, 0L));
        assertNull(DynamicOps.eval(json("{\"k\":\"pos0\"}"), noValues, 0f, 0L));
        assertNull(DynamicOps.eval(json("{\"k\":\"worldTime\",\"total\":false}"), noValues, 0f, 0L));
        assertEquals(7.0, DynamicOps.eval(json("{\"k\":\"nowMillis\"}"), noValues, 0f, 7L));
        // Door-live lane: server-evaluated animation channels arrive as ordinary synced
        // values under their full channel key; absent means "no animation", never a zero.
        assertEquals(2.5, DynamicOps.eval(
                json("{\"k\":\"channel\",\"key\":\"test.Track.eval.DOOR[1]\"}"),
                Map.of("test.Track.eval.DOOR[1]", 2.5), 0f, 0L));
        assertNull(DynamicOps.eval(
                json("{\"k\":\"channel\",\"key\":\"test.Track.eval.DOOR[1]\"}"), noValues, 0f, 0L));
        assertNull(DynamicOps.eval(json("{\"k\":\"channel\"}"), noValues, 0f, 0L));
    }

    @Test
    void poseReplaysPrefixWithPushPopScoping(@TempDir Path tmp) throws Exception {
        writeSidecar(tmp);
        ObjBridge.configure(tmp, tmp.resolve("map.json"), tmp.resolve("renderer-transforms.json"));
        DynamicOps.resetForTests();
        List<DynamicOps.Draw> draws = DynamicOps.draws("test.Radar");
        assertEquals(1, draws.size());
        assertEquals("Dish", draws.get(0).group());
        Map<String, Double> values = Map.of("TE.prev", 10.0, "TE.cur", 20.0);
        Matrix4f got = DynamicOps.poseFor(draws.get(0).ops(), values, 0.5f, 0L);
        // angle = 10 + (20-10)*0.5 = 15; prefix = push, rotate180, rotate15, (translate skipped: no Dish translate here)
        Matrix4f want = new Matrix4f();
        want.rotate((float) Math.toRadians(180.0), new Vector3f(0, 1, 0));
        want.rotate((float) Math.toRadians(15.0), new Vector3f(0, -1, 0));
        assertEquals(want, got);
    }

    @Test
    void anchoredOpsSkipSoBerKeepsBlockPositioning(@TempDir Path tmp) throws Exception {
        writeSidecar(tmp);
        ObjBridge.configure(tmp, tmp.resolve("map.json"), tmp.resolve("renderer-transforms.json"));
        DynamicOps.resetForTests();
        // A prefix whose only dynamic op is world-anchored has no dynamic pose.
        assertFalse(DynamicOps.hasDynamicPose("test.Plain", Map.of(), 0f, 0L));
        assertTrue(DynamicOps.hasDynamicPose("test.Radar", Map.of("TE.prev", 1.0, "TE.cur", 2.0), 0f, 0L));
        // ...but goes quiet again when a field is absent: honest absence per frame.
        assertFalse(DynamicOps.hasDynamicPose("test.Radar", Map.of("TE.prev", 1.0), 0f, 0L));
    }

    @Test
    void drawsUnionCoversSameTeHelperRenderers(@TempDir Path tmp) throws Exception {
        writeSidecar(tmp);
        ObjBridge.configure(tmp, tmp.resolve("map.json"), tmp.resolve("renderer-transforms.json"));
        DynamicOps.resetForTests();
        // Door-live lane: motion living in a same-TE helper row (test.RadarHelper) is visible
        // through the bound TESR's class (test.Radar), whose own row has no channel op.
        assertEquals("test.TE", DynamicOps.teClassOf("test.Radar"));
        assertEquals("test.TE", DynamicOps.teClassOf("test.RadarHelper"));
        assertEquals("test.TE2", DynamicOps.teClassOf("test.Plain"));
        assertNull(DynamicOps.teClassOf("test.Nope"));
        assertEquals(1, DynamicOps.draws("test.Radar").size());
        List<DynamicOps.Draw> union = DynamicOps.drawsUnion("test.Radar");
        assertEquals(2, union.size());
        assertEquals("Dish", union.get(0).group());
        assertEquals("Antenna", union.get(1).group());
        // A second BOUND TESR for the same TE (another variant's renderer) is never
        // unioned - only unbound helpers are (the turret scatter: a bound twin's draws
        // replayed over the wrong mesh).
        assertTrue(DynamicOps.isBound("test.RadarTwin"));
        assertFalse(DynamicOps.isBound("test.Radar"));
        assertFalse(DynamicOps.isBound("test.RadarHelper"));
        assertFalse(DynamicOps.isBound("test.Nope"));
        for (DynamicOps.Draw d : union) {
            assertFalse("TwinDish".equals(d.group()));
        }
        // The helper's channel op makes the union dynamic exactly when its value is synced.
        assertFalse(DynamicOps.hasDynamicPose("test.Radar", Map.of(), 0f, 0L));
        assertTrue(DynamicOps.hasDynamicPose("test.Radar",
                Map.of("test.Track.eval.DOOR[1]", 2.5), 0f, 0L));
        // The union never leaks across TE classes.
        assertFalse(DynamicOps.hasDynamicPose("test.Plain",
                Map.of("test.Track.eval.DOOR[1]", 2.5), 0f, 0L));
        assertTrue(DynamicOps.drawsUnion("test.Nope").isEmpty());
    }

    @Test
    void prefixMatrixComposesTranslateThenFacing(@TempDir Path tmp) throws Exception {
        writeSidecar(tmp);
        ObjBridge.configure(tmp, tmp.resolve("map.json"), tmp.resolve("renderer-transforms.json"));
        DynamicOps.resetForTests();
        // Door-live round 3: centering translate then the meta-selected facing rotate,
        // exactly the legacy call order (glTranslated before glRotated right-multiplies).
        Matrix4f want = new Matrix4f();
        want.translate(0.5f, 0.0f, 0.5f);
        want.rotateY((float) Math.toRadians(90.0));
        assertEquals(want, DynamicOps.prefixMatrix("test.Prefixed", 12));
        Matrix4f want180 = new Matrix4f();
        want180.translate(0.5f, 0.0f, 0.5f);
        want180.rotateY((float) Math.toRadians(180.0));
        assertEquals(want180, DynamicOps.prefixMatrix("test.Prefixed", 13));
        // Unknown meta, missing case, missing row: no prefix, never half-applied.
        assertNull(DynamicOps.prefixMatrix("test.Prefixed", null));
        assertNull(DynamicOps.prefixMatrix("test.Prefixed", 10));
        assertNull(DynamicOps.prefixMatrix("test.Radar", 12));
        assertNull(DynamicOps.prefixMatrix(null, 12));
    }

    @Test
    void missingOrMalformedSidecarIsAnEmptyStaticFallback(@TempDir Path tmp) throws Exception {
        Path map = tmp.resolve("map.json");
        Files.writeString(map, "{\"blocks\":[]}", StandardCharsets.UTF_8);
        Path transforms = tmp.resolve("renderer-transforms.json");
        Files.writeString(transforms, "{\"renderers\":{}}", StandardCharsets.UTF_8);
        ObjBridge.configure(tmp, map, transforms);
        DynamicOps.resetForTests();
        assertTrue(DynamicOps.forRenderer("test.Radar").isEmpty());
        assertTrue(DynamicOps.draws("test.Radar").isEmpty());
        // A malformed sibling is tolerated the same way (never throws out of the reader).
        Files.writeString(tmp.resolve("renderer-dynamic-ops.json"), "not json{{{",
                StandardCharsets.UTF_8);
        ObjBridge.configure(tmp, map, transforms);
        DynamicOps.resetForTests();
        assertTrue(DynamicOps.forRenderer("test.Radar").isEmpty());
    }
}
