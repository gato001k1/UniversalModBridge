package dev.umb.objbridge.entity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Door-live follow-up (GL-state ops): one draw's full replay plan. Pure matrix/const
 * evaluation (no atlas, no world) - the headless both-sides proof: Left and Right draw
 * prefixes off the same channel map evaluate mirrored, carry the same clip scope, and
 * the composed totals match {@link DynamicOps#poseFor} exactly.
 */
class FramePlanTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    private static JsonObject c(double v) {
        return json("{\"k\":\"const\",\"v\":" + v + "}");
    }

    private static JsonObject ch(String key) {
        return json("{\"k\":\"channel\",\"key\":\"" + key + "\"}");
    }

    private static DynamicOps.Op op(String gl, String group, JsonObject... args) {
        return new DynamicOps.Op("render", gl, List.of(args), group, false);
    }

    private static final String DOOR = "test.Track.eval.DOOR[1]";

    /** HBM vehicle-door Left prefix shape: rot90, clip pair, push, slide -clamp(ch*3).
     *  Like the real extraction, the slide translate stays unpopped inside the prefix. */
    private static List<DynamicOps.Op> leftPrefix() {
        List<DynamicOps.Op> ops = new ArrayList<>();
        ops.add(op("glCullFace", "Left", c(0.0)));
        ops.add(op("glRotated", "Left", c(90.0), c(0.0), c(1.0), c(0.0)));
        ops.add(op("glClipEnable", "Left", c(12288.0), c(1.0)));
        ops.add(op("glClipPlane", "Left", c(12288.0), c(1.0), c(0.0), c(0.0), c(3.4375)));
        ops.add(op("glPushMatrix", "Left"));
        ops.add(op("glTranslated", "Left",
                json("{\"k\":\"op\",\"o\":\"dneg\",\"a\":[{\"k\":\"op\",\"o\":\"dmul\",\"a\":["
                        + "{\"k\":\"channel\",\"key\":\"" + DOOR + "\"},{\"k\":\"const\",\"v\":3.0}]}]}"),
                c(0.0), c(0.0)));
        return ops;
    }

    /** Same with +clamp slide (the Right draw). */
    private static List<DynamicOps.Op> rightPrefix() {
        List<DynamicOps.Op> ops = new ArrayList<>();
        ops.add(op("glCullFace", "Right", c(0.0)));
        ops.add(op("glRotated", "Right", c(90.0), c(0.0), c(1.0), c(0.0)));
        ops.add(op("glClipEnable", "Right", c(12288.0), c(1.0)));
        ops.add(op("glClipPlane", "Right", c(12288.0), c(1.0), c(0.0), c(0.0), c(3.4375)));
        ops.add(op("glPushMatrix", "Right"));
        ops.add(op("glTranslated", "Right",
                json("{\"k\":\"op\",\"o\":\"dmul\",\"a\":["
                        + "{\"k\":\"channel\",\"key\":\"" + DOOR + "\"},{\"k\":\"const\",\"v\":3.0}]}"),
                c(0.0), c(0.0)));
        return ops;
    }

    @Test
    void bothDrawsMirrorWithSameClipScope() {
        Map<String, Double> values = Map.of(DOOR, 0.45);
        DynamicOps.FramePlan left = DynamicOps.plan(leftPrefix(), values, 0f, 0L);
        DynamicOps.FramePlan right = DynamicOps.plan(rightPrefix(), values, 0f, 0L);
        // Totals match poseFor exactly (the refactor changed no matrix).
        assertEquals(DynamicOps.poseFor(leftPrefix(), values, 0f, 0L), left.total());
        assertEquals(DynamicOps.poseFor(rightPrefix(), values, 0f, 0L), right.total());
        // Mirrored slides: 0.45*3 = 1.35 each way. The translate sits post-rot90 in
        // draw space, so JOML post-multiply puts Left at draw-local (0,0,+1.35) and
        // Right at (0,0,-1.35) - exactly the live diag poses at DOOR=0.45 (+-1.4).
        Vector4f o = new Vector4f(0, 0, 0, 1);
        Vector4f l = new Vector4f(o);
        left.total().transform(l);
        Vector4f r = new Vector4f(o);
        right.total().transform(r);
        assertEquals(0.0, l.x, 1e-4);
        assertEquals(1.35, l.z, 1e-4);
        assertEquals(0.0, r.x, 1e-4);
        assertEquals(-1.35, r.z, 1e-4);
        // Cull off on both (HBM disables it for this render).
        assertNotNull(left.cull());
        assertEquals(0.0, left.cull(), 1e-9);
        assertEquals(0.0, right.cull(), 1e-9);
        // One active clip each, same call-site frame (rot90). The bound shifts by
        // each draw's own slide: left (-1.35) tightens to 3.4375-1.35 = 2.0875,
        // right (+1.35) loosens to 3.4375+1.35 = 4.7875 (fully kept).
        assertEquals(1, left.clips().size());
        assertEquals(1, right.clips().size());
        assertEquals(12288, left.clips().get(0).cap());
        double[] wantL = {1.0, 0.0, 0.0, 2.0875};
        double[] wantR = {1.0, 0.0, 0.0, 4.7875};
        for (int i = 0; i < 4; i++) {
            assertEquals(wantL[i], left.clips().get(0).eq()[i], 1e-4);
            assertEquals(wantR[i], right.clips().get(0).eq()[i], 1e-4);
        }
    }

    @Test
    void clipStaysOffWithoutEnable() {
        List<DynamicOps.Op> ops = new ArrayList<>();
        ops.add(op("glClipPlane", "Q", c(12288.0), c(1.0), c(0.0), c(0.0), c(3.4375)));
        ops.add(op("glTranslated", "Q", c(0.0), c(0.0), c(0.0)));
        DynamicOps.FramePlan p = DynamicOps.plan(ops, Map.of(), 0f, 0L);
        assertTrue(p.clips().isEmpty(), "no enable means no clipping (fail open)");
        assertNull(p.cull(), "no cull op means default pipeline");
    }

    @Test
    void cullOnSelectsCullingPipeline() {
        List<DynamicOps.Op> ops = new ArrayList<>();
        ops.add(op("glCullFace", "Q", c(1.0)));
        ops.add(op("glTranslated", "Q", c(0.0), c(0.0), c(0.0)));
        DynamicOps.FramePlan p = DynamicOps.plan(ops, Map.of(), 0f, 0L);
        assertNotNull(p.cull());
        assertEquals(1.0, p.cull(), 1e-9);
        assertEquals(new Matrix4f(), p.total(), "cull op never moves the matrix");
    }
}
