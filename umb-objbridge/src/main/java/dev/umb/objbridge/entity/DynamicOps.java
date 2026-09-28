package dev.umb.objbridge.entity;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.umb.objbridge.ObjBridge;
import dev.umb.objbridge.ObjBridgeManifest;
import dev.umb.objbridge.ObjLog;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Moving-parts lane: runtime side of {@code renderer-dynamic-ops.json} (emitted by
 * umb-rendermap's {@code DynamicOpSidecar} next to {@code renderer-transforms.json}).
 *
 * <p>Loading mirrors the item-display sidecar: no manifest or launcher change, the file is
 * derived as a sibling of each mod's transforms path ({@code ObjBridge.loadedMods()}), merged
 * across mods by renderer class, tolerant-absent (no file means the static pose path below,
 * byte-identical to before this lane existed). Malformed JSON never throws out of here.</p>
 *
 * <p>Evaluation ({@link #poseFor}) replays one group's op prefix with OpenGL fixed-function
 * semantics (right-multiply, push/pop scoping - the same rules {@code TransformComposer}
 * documents), substituting per-frame values: synced tile fields, {@code partialTicks}, and
 * the same-JVM wall clock. World-anchor positionals ({@code pos0/1/2}), world time and
 * anything without a value skip their whole op (counted at extract time; the BER positions
 * by block itself, exactly like today's static path which also skips them).</p>
 */
public final class DynamicOps {

    /** One op with possibly-symbolic args, as emitted (null arg = unresolvable). */
    public record Op(String method, String gl, List<JsonObject> args, String group, boolean anchored) {
    }

    private static volatile Map<String, List<Op>> BY_RENDERER;
    private static volatile Map<String, String> TE_OF_RENDERER;
    private static volatile Map<String, TesrPrefix> PREFIX_OF_RENDERER;
    private static volatile Map<String, Boolean> BOUND_RENDERERS;

    private DynamicOps() {
    }

    /** All ops for one TESR class, in sidecar order; empty when absent. Never null. */
    public static List<Op> forRenderer(String rendererClass) {
        Map<String, List<Op>> all = all();
        if (rendererClass == null) return List.of();
        List<Op> out = all.get(rendererClass);
        return out != null ? out : List.of();
    }

    /**
     * Per-draw op runs the BER should evaluate: the sidecar stores one flat op list per
     * renderer (ungrouped chronological ops plus one full prefix copy per renderPart draw),
     * so each maximal run of consecutive same-group records replays, from identity, to
     * exactly the matrix its draw call saw. Draws use world-pose methods (a {@code WORLD}
     * method when the TESR has any, else the remaining TE-bearing entry methods);
     * inventory/hand contexts never reach a block-entity renderer.
     */
    public static List<Draw> draws(String rendererClass) {
        List<Op> ops = forRenderer(rendererClass);
        boolean hasWorld = false;
        for (Op o : ops) {
            if (o.group != null && isWorldMethod(o.method)) {
                hasWorld = true;
                break;
            }
        }
        List<Draw> out = new ArrayList<>();
        String runGroup = null;
        String runMethod = null;
        List<Op> run = null;
        for (Op o : ops) {
            boolean use;
            if (o.group == null) {
                use = false;
            } else if (hasWorld) {
                use = isWorldMethod(o.method);
            } else {
                use = !isExcludedMethod(o.method);
            }
            if (!use || !o.group.equals(runGroup) || !o.method.equals(runMethod)) {
                if (run != null && !run.isEmpty()) out.add(new Draw(runGroup, runMethod, run));
                runGroup = use ? o.group : null;
                runMethod = use ? o.method : null;
                run = use ? new ArrayList<>() : null;
            }
            if (use) run.add(o);
        }
        if (run != null && !run.isEmpty()) out.add(new Draw(runGroup, runMethod, run));
        return out;
    }

    /** One renderPart draw: its group plus the op prefix replayed to its matrix. */
    public record Draw(String group, String method, List<Op> ops) {
    }

    /**
     * Door-live round 3: a TESR-side base matrix (centering translate plus one
     * meta-selected facing rotate), or null. Applied by the BER around dispatched-helper
     * draws so they replay under the exact matrix the helper assumed (block center +
     * facing), instead of the bare entry pose (block corner, no facing) that turned the
     * whole door into one edge-on slab. Both-or-nothing when a facing map exists: a
     * translate without its facing (or a facing for an unknown meta) is skipped, never
     * half-applied. Unknown metas (null, or a case the switch never names) yield null.
     */
    public record TesrPrefix(double[] translate, Integer metaBase, Map<Integer, double[]> facing) {
    }

    /**
     * The TESR base matrix for one renderer class and block meta, or null (no prefix row,
     * unknown meta, missing case, or a non-Y facing axis). Composes translate-then-rotate
     * exactly like the legacy call order (glTranslated before glRotated right-multiplies).
     */
    public static Matrix4f prefixMatrix(String rendererClass, Integer meta) {
        TesrPrefix p = prefixOf(rendererClass);
        if (p == null) return null;
        double[] t = p.translate();
        Map<Integer, double[]> facing = p.facing() != null ? p.facing() : Map.of();
        double[] rotate = null;
        if (!facing.isEmpty()) {
            if (meta == null || p.metaBase() == null) return null;
            rotate = facing.get(meta - p.metaBase());
            if (rotate == null || rotate.length != 4) return null;
            if (rotate[1] != 0.0 || rotate[3] != 0.0
                    || (rotate[2] != 1.0 && rotate[2] != -1.0)) return null;
        }
        Matrix4f m = new Matrix4f();
        if (t != null && t.length == 3) {
            m.translate((float) t[0], (float) t[1], (float) t[2]);
        }
        if (rotate != null) {
            m.rotateY((float) Math.toRadians(rotate[0] * rotate[2]));
        }
        if (t == null && rotate == null) return null;
        return m;
    }

    private static TesrPrefix prefixOf(String rendererClass) {
        if (rendererClass == null) return null;
        teOfAll();
        Map<String, TesrPrefix> m = PREFIX_OF_RENDERER;
        return m != null ? m.get(rendererClass) : null;
    }

    /**
     * Door-live lane: draws for one TESR class PLUS every helper renderer bound to the SAME
     * tile-entity class (the block's own row first, then helpers in sidecar order). A block's
     * visual motion can live in a helper reached by interface dispatch rather than in the
     * bound TESR - the door's slide curves sit in {@code RenderVehicleDoor}, while the map
     * binds {@code RenderDoorGeneric}, whose row has no channel op at all (live 2026-09-24:
     * the core synced fine and the BER silently took the static path forever). Helpers are
     * renderers NO block binds as its TESR (sidecar {@code bound} flag); a second bound
     * TESR for the same TE is another variant's renderer and is never unioned (live
     * 2026-09-24: RenderTurretFriendly's draws replayed over the chekhov mesh = scattered
     * turret). Channel keys are unique per origin and poses replay per draw, so the union
     * is sound; group and mesh filtering at the call site still applies per draw.
     */
    public static List<Draw> drawsUnion(String rendererClass) {
        List<Draw> out = new ArrayList<>(draws(rendererClass));
        String te = teClassOf(rendererClass);
        if (te == null) return out;
        Map<String, List<Op>> all = all();
        for (Map.Entry<String, List<Op>> e : all.entrySet()) {
            if (e.getKey().equals(rendererClass)) continue;
            if (!te.equals(teOf(e.getKey()))) continue;
            if (isBound(e.getKey())) continue;
            for (Draw d : draws(e.getKey())) out.add(d);
        }
        return out;
    }

    /** True when some joined block binds this renderer as its TESR (never a union helper). */
    public static boolean isBound(String rendererClass) {
        if (rendererClass == null) return false;
        teOfAll();
        Map<String, Boolean> m = BOUND_RENDERERS;
        return m != null && Boolean.TRUE.equals(m.get(rendererClass));
    }

    /** The sidecar's TE class for one renderer row, or null when the row says none. */
    public static String teClassOf(String rendererClass) {
        if (rendererClass == null) return null;
        teOfAll();
        Map<String, String> m = TE_OF_RENDERER;
        return m != null ? m.get(rendererClass) : null;
    }

    private static String teOf(String rendererClass) {
        Map<String, String> m = TE_OF_RENDERER;
        return m != null ? m.get(rendererClass) : null;
    }

    private static boolean isWorldMethod(String method) {
        if (method == null) return false;
        String m = method.toLowerCase(Locale.ROOT);
        return m.equals("func_147500_a") || m.contains("tileentityat") || m.contains("dorender");
    }

    private static boolean isExcludedMethod(String method) {
        if (method == null) return true;
        String m = method.toLowerCase(Locale.ROOT);
        return m.contains("inventory") || m.contains("firstperson") || m.contains("thirdperson")
                || m.contains("common") || m.contains("setupinv") || m.contains("modtable");
    }

    /** True when at least one draw has a fully evaluable, non-anchored, live op. */
    public static boolean hasDynamicPose(String rendererClass, Map<String, Double> values,
                                         float partial, long nowMillis) {
        for (Draw d : drawsUnion(rendererClass)) {            for (Op o : d.ops) {
                if (!o.anchored && isLive(o) && evaluable(o, values, partial, nowMillis)) return true;
            }
        }
        return false;
    }

    /**
     * True when the op reads at least one live per-frame input (a synced tile field,
     * {@code partialTicks}, or the wall clock). Constant-only prefixes stay on the static
     * path even though they would evaluate: switching them would rebake their quads under a
     * different grounding for zero visual gain.
     */
    static boolean isLive(Op o) {
        for (JsonObject a : o.args) {
            if (a != null && readsLiveInput(a)) return true;
        }
        return false;
    }

    private static boolean readsLiveInput(JsonObject e) {
        if (e == null || !e.has("k") || e.get("k").isJsonNull()) return false;
        switch (e.get("k").getAsString()) {
            case "field":
            case "partial":
            case "nowMillis":
            case "channel":
                return true;
            default:
                break;
        }
        if (e.has("a") && e.get("a").isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray("a")) {
                if (x != null && x.isJsonObject() && readsLiveInput(x.getAsJsonObject())) return true;
            }
        }
        return false;
    }

    private static boolean evaluable(Op o, Map<String, Double> values, float partial, long nowMillis) {
        if (o.anchored) return false;
        for (JsonObject a : o.args) {
            if (a == null) return false;
            if (eval(a, values, partial, nowMillis) == null) return false;
        }
        return true;
    }

    /**
     * Turret follow-up: a draw replays fully or not at all. {@link #poseFor} skips
     * unevaluable ops inside a replay, which draws the group at a half-evaluated pose
     * (live 2026-09-24: turret barrels scattered around the core while their yaw fields
     * had no synced values). A draw with any unevaluable non-anchored op (anchored
     * positioning is the BER's own job and never counts) falls through to the static
     * remainder instead - an honest rest pose beats a fabricated intermediate.
     */
    public static boolean isFullyEvaluable(List<Op> prefix, Map<String, Double> values,
                                           float partial, long nowMillis) {
        for (Op o : prefix) {
            if (o.anchored) continue;
            for (JsonObject a : o.args) {
                if (a == null) return false;
                if (eval(a, values, partial, nowMillis) == null) return false;
            }
        }
        return true;
    }

    /**
     * One clip-plane call site inside a draw prefix: its equation plus the composed
     * draw-space matrix in effect where legacy called {@code glClipPlane} (the plane
     * lives in that frame; the replay maps it into mesh space per frame).
     */
    public record ClipCall(int cap, double[] eq, Matrix4f pre) {}

    /**
     * One draw's full replay plan: the composed matrix (identical to {@link #poseFor}),
     * the last cull-face state in the prefix (null = untouched, 0.0 = off, else on),
     * and the clip calls with their call-site frames.
     */
    public record FramePlan(Matrix4f total, Double cull, List<ActiveClip> clips) {}

    /**
     * Door-live follow-up (GL-state ops): replays one group's prefix to a matrix AND
     * collects cull-face toggles plus clip-plane call sites. Matrix handling is exactly
     * {@link #poseFor}'s (which delegates here); GL-state ops never move the matrix,
     * they only record constant state other ops cannot see.
     */
    public static FramePlan plan(List<Op> prefix, Map<String, Double> values, float partial,
                                 long nowMillis) {
        Matrix4f cur = new Matrix4f();
        Deque<Matrix4f> stack = new ArrayDeque<>();
        Double cull = null;
        Map<Integer, Double> clipEnable = null;
        List<ClipCall> clipCalls = null;
        for (Op o : prefix) {
            String gl = o.gl;
            if ("glPushMatrix".equals(gl)) {
                stack.push(new Matrix4f(cur));
                continue;
            }
            if ("glPopMatrix".equals(gl)) {
                if (!stack.isEmpty()) cur = stack.pop();
                continue;
            }
            if ("glCullFace".equals(gl)) {
                Double v = constArg(o, 0, values, partial, nowMillis);
                if (v != null) cull = v;
                continue;
            }
            if ("glClipEnable".equals(gl)) {
                Double cap = constArg(o, 0, values, partial, nowMillis);
                Double on = constArg(o, 1, values, partial, nowMillis);
                if (cap != null && on != null) {
                    if (clipEnable == null) clipEnable = new LinkedHashMap<>();
                    clipEnable.put((int) Math.round(cap), on);
                }
                continue;
            }
            if ("glClipPlane".equals(gl)) {
                double[] eq = constArgs(o, 1, 4, values, partial, nowMillis);
                Double cap = constArg(o, 0, values, partial, nowMillis);
                if (eq != null && cap != null) {
                    if (clipCalls == null) clipCalls = new ArrayList<>();
                    clipCalls.add(new ClipCall((int) Math.round(cap), eq, new Matrix4f(cur)));
                }
                continue;
            }
            if (o.anchored) continue;
            float[] a = new float[o.args.size()];
            boolean ok = true;
            for (int i = 0; i < o.args.size(); i++) {
                Double v = o.args.get(i) == null ? null
                        : eval(o.args.get(i), values, partial, nowMillis);
                if (v == null) {
                    ok = false;
                    break;
                }
                a[i] = v.floatValue();
            }
            if (!ok) continue;
            if ((gl.startsWith("glTranslate")) && a.length >= 3) {
                cur.translate(a[0], a[1], a[2]);
            } else if ((gl.startsWith("glScale")) && a.length >= 3) {
                cur.scale(a[0], a[1], a[2]);
            } else if ((gl.startsWith("glRotate")) && a.length >= 4) {
                cur.rotate((float) Math.toRadians(a[0]), new Vector3f(a[1], a[2], a[3]));
            }
        }
        List<ActiveClip> active = new ArrayList<>();
        if (clipCalls != null) {
            for (ClipCall c : clipCalls) {
                Double on = clipEnable == null ? null : clipEnable.get(c.cap);
                if (on == null || on == 0.0) continue;
                double[] local = ClipPlanes.toLocal(c.pre, cur, c.eq);
                if (local != null) active.add(new ActiveClip(c.cap, local));
            }
        }
        return new FramePlan(cur, cull, active);
    }

    /** One clip plane mapped into mesh space, ready to clip baked quads. */
    public record ActiveClip(int cap, double[] eq) {}
    private static Double constArg(Op o, int index, Map<String, Double> values, float partial,
                                   long nowMillis) {
        if (index < 0 || index >= o.args.size() || o.args.get(index) == null) return null;
        return eval(o.args.get(index), values, partial, nowMillis);
    }

    /** {@code count} evaluated arguments starting at {@code from}, or null. */
    private static double[] constArgs(Op o, int from, int count, Map<String, Double> values,
                                      float partial, long nowMillis) {
        if (from < 0 || from + count > o.args.size()) return null;
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            if (o.args.get(from + i) == null) return null;
            Double v = eval(o.args.get(from + i), values, partial, nowMillis);
            if (v == null) return null;
            out[i] = v;
        }
        return out;
    }

    /**
     * Replays one group's prefix to a matrix. Ops with anchored or unevaluable args are
     * skipped (the extract-time counts already account for them); push/pop scope normally.
     */
    public static Matrix4f poseFor(List<Op> prefix, Map<String, Double> values, float partial,
                                   long nowMillis) {
        return plan(prefix, values, partial, nowMillis).total();
    }

    /** Evaluates one argument expression; null means "skip this op this frame". Never throws. */
    public static Double eval(JsonObject e, Map<String, Double> values, float partial, long nowMillis) {
        try {
            return evalInner(e, values, partial, nowMillis);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Double evalInner(JsonObject e, Map<String, Double> values, float partial,
                                    long nowMillis) {
        if (e == null || !e.has("k")) return null;
        switch (e.get("k").getAsString()) {
            case "const":
                return e.has("v") && !e.get("v").isJsonNull() ? e.get("v").getAsDouble() : null;
            case "field": {
                if (!e.has("key") || e.get("key").isJsonNull()) return null;
                return values.get(e.get("key").getAsString());
            }
            case "channel": {
                // Server-evaluated animation channels arrive as ordinary synced values under
                // their channel key (see DynFieldChannel) - absent means "no animation".
                if (!e.has("key") || e.get("key").isJsonNull()) return null;
                return values.get(e.get("key").getAsString());
            }
            case "partial":
                return (double) partial;
            case "nowMillis":
                return (double) nowMillis;
            case "pos0":
            case "pos1":
            case "pos2":
            case "worldTime":
                return null;
            case "call":
                return evalCall(e, values, partial, nowMillis);
            case "op":
                return evalOp(e, values, partial, nowMillis);
            default:
                return null;
        }
    }

    private static Double num(JsonObject e, String k, Map<String, Double> values, float partial,
                              long nowMillis) {
        if (!e.has(k) || e.get(k).isJsonNull() || !e.get(k).isJsonObject()) return null;
        return eval(e.getAsJsonObject(k), values, partial, nowMillis);
    }

    private static List<Double> argList(JsonObject e, Map<String, Double> values, float partial,
                                        long nowMillis) {
        List<Double> out = new ArrayList<>();
        if (!e.has("a") || !e.get("a").isJsonArray()) return null;
        for (JsonElement x : e.getAsJsonArray("a")) {
            if (x == null || x.isJsonNull() || !x.isJsonObject()) return null;
            Double v = eval(x.getAsJsonObject(), values, partial, nowMillis);
            if (v == null) return null;
            out.add(v);
        }
        return out;
    }

    private static Double evalCall(JsonObject e, Map<String, Double> values, float partial,
                                   long nowMillis) {
        if (!e.has("o") || e.get("o").isJsonNull() || !e.has("m") || e.get("m").isJsonNull()) {
            return null;
        }
        String o = e.get("o").getAsString();
        String m = e.get("m").getAsString();
        List<Double> a = argList(e, values, partial, nowMillis);
        if (a == null) return null;
        boolean math = "java/lang/Math".equals(o);
        boolean helper = "net/minecraft/util/MathHelper".equals(o);
        if (math && a.size() == 1) {
            double x = a.get(0);
            switch (m) {
                case "toDegrees": return Math.toDegrees(x);
                case "toRadians": return Math.toRadians(x);
                case "sin": return Math.sin(x);
                case "cos": return Math.cos(x);
                case "sqrt": return Math.sqrt(x);
                case "abs": return Math.abs(x);
                default: return null;
            }
        }
        if (math && a.size() == 2) {
            double x = a.get(0), y = a.get(1);
            switch (m) {
                case "min": return Math.min(x, y);
                case "max": return Math.max(x, y);
                default: return null;
            }
        }
        if (helper && a.size() == 3) {
            double x = a.get(0), lo = a.get(1), hi = a.get(2);
            switch (m) {
                case "clamp_float":
                case "func_76131_a":
                case "clamp_double":
                case "func_151237_a":
                case "clamp_int":
                case "func_76125_a":
                    return Math.min(Math.max(x, lo), hi);
                default: return null;
            }
        }
        return null;
    }

    private static Double evalOp(JsonObject e, Map<String, Double> values, float partial,
                                 long nowMillis) {
        if (!e.has("o") || e.get("o").isJsonNull()) return null;
        String op = e.get("o").getAsString();
        List<Double> a = argList(e, values, partial, nowMillis);
        if (a == null) return null;
        try {
            switch (op) {
                case "fadd":
                case "dadd": return a.get(0) + a.get(1);
                case "fsub":
                case "dsub": return a.get(0) - a.get(1);
                case "fmul":
                case "dmul": return a.get(0) * a.get(1);
                case "fdiv":
                case "ddiv": return a.get(0) / a.get(1);
                case "frem":
                case "drem": return a.get(0) % a.get(1);
                case "iadd": return (double) ((int) (double) a.get(0) + (int) (double) a.get(1));
                case "isub": return (double) ((int) (double) a.get(0) - (int) (double) a.get(1));
                case "imul": return (double) ((int) (double) a.get(0) * (int) (double) a.get(1));
                case "idiv": {
                    int d = (int) (double) a.get(1);
                    if (d == 0) return null;
                    return (double) ((int) (double) a.get(0) / d);
                }
                case "irem": {
                    int d = (int) (double) a.get(1);
                    if (d == 0) return null;
                    return (double) ((int) (double) a.get(0) % d);
                }
                case "ishl": return (double) ((int) (double) a.get(0) << (int) (double) a.get(1));
                case "ishr": return (double) ((int) (double) a.get(0) >> (int) (double) a.get(1));
                case "iushr": return (double) ((int) (double) a.get(0) >>> (int) (double) a.get(1));
                case "iand": return (double) ((int) (double) a.get(0) & (int) (double) a.get(1));
                case "ior": return (double) ((int) (double) a.get(0) | (int) (double) a.get(1));
                case "ixor": return (double) ((int) (double) a.get(0) ^ (int) (double) a.get(1));
                case "ladd":
                case "lsub":
                case "lmul":
                case "ldiv":
                case "lrem":
                case "land":
                case "lor":
                case "lxor":
                case "lshl":
                case "lshr":
                case "lushr": {
                    long x = (long) (double) a.get(0), y = (long) (double) a.get(1);
                    switch (op) {
                        case "ladd": return (double) (x + y);
                        case "lsub": return (double) (x - y);
                        case "lmul": return (double) (x * y);
                        case "ldiv": return y == 0 ? null : (double) (x / y);
                        case "lrem": return y == 0 ? null : (double) (x % y);
                        case "land": return (double) (x & y);
                        case "lor": return (double) (x | y);
                        case "lxor": return (double) (x ^ y);
                        case "lshl": return (double) (x << y);
                        case "lshr": return (double) (x >> y);
                        default: return (double) (x >>> y);
                    }
                }
                case "ineg": return (double) (-(int) (double) a.get(0));
                case "fneg":
                case "dneg": return -a.get(0);
                case "lneg": return (double) (-(long) (double) a.get(0));
                case "i2l":
                case "i2d": return (double) (int) (double) a.get(0);
                case "f2l": return (double) (long) (float) (double) a.get(0);
                case "f2d": return (double) (float) (double) a.get(0);
                case "l2d": return (double) (long) (double) a.get(0);
                case "d2l": return (double) (long) (double) a.get(0);
                case "i2f": return (double) (float) (int) (double) a.get(0);
                case "f2i": return (double) (int) (float) (double) a.get(0);
                case "i2b": return (double) (byte) (int) (double) a.get(0);
                case "i2c": return (double) (char) (int) (double) a.get(0);
                case "i2s": return (double) (short) (int) (double) a.get(0);
                case "l2i": return (double) (int) (long) (double) a.get(0);
                case "l2f": return (double) (float) (long) (double) a.get(0);
                case "d2i": return (double) (int) (double) a.get(0);
                case "d2f": return (double) (float) (double) a.get(0);
                default: return null;
            }
        } catch (IndexOutOfBoundsException ex) {
            return null;
        }
    }

    // ---------------------------------------------------------------- loading

    private static Map<String, List<Op>> all() {
        Map<String, List<Op>> cached = BY_RENDERER;
        if (cached != null) return cached;
        synchronized (DynamicOps.class) {
            if (BY_RENDERER != null) return BY_RENDERER;
            Map<String, List<Op>> out = new LinkedHashMap<>();
            Map<String, String> te = new LinkedHashMap<>();
            Map<String, TesrPrefix> prefix = new LinkedHashMap<>();
            Map<String, Boolean> bound = new LinkedHashMap<>();
            try {
                for (ObjBridgeManifest.Loaded mod : ObjBridge.loadedMods()) {
                    Path t = mod.mod().transformsPath();
                    Path sidecar = t == null ? null : t.resolveSibling("renderer-dynamic-ops.json");
                    if (sidecar == null || !Files.isRegularFile(sidecar)) continue;
                    merge(out, te, prefix, bound, sidecar);
                }
            } catch (Throwable t) {
                ObjLog.error("dynamic ops load", t, 3);
            }
            ObjLog.line("dynamic ops: renderer classes=" + out.size());
            BY_RENDERER = out;
            TE_OF_RENDERER = te;
            PREFIX_OF_RENDERER = prefix;
            BOUND_RENDERERS = bound;
            return out;
        }
    }

    /** Ensures the renderer->TE-class index is loaded (populated with {@link #all()}). */
    private static void teOfAll() {
        if (TE_OF_RENDERER == null) all();
    }

    private static void merge(Map<String, List<Op>> out, Map<String, String> te,
                              Map<String, TesrPrefix> prefix, Map<String, Boolean> bound,
                              Path sidecar)
            throws Exception {
        String text = Files.readString(sidecar, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        JsonObject renderers = root.has("renderers") && root.get("renderers").isJsonObject()
                ? root.getAsJsonObject("renderers") : new JsonObject();
        for (Map.Entry<String, JsonElement> e : renderers.entrySet()) {
            if (e.getValue() == null || !e.getValue().isJsonObject()) continue;
            JsonObject r = e.getValue().getAsJsonObject();
            String teClass = str(r, "teClass");
            if (teClass != null && !teClass.isEmpty()) te.putIfAbsent(e.getKey(), teClass);
            TesrPrefix p = parsePrefix(r);
            if (p != null) prefix.putIfAbsent(e.getKey(), p);
            if (r.has("bound") && !r.get("bound").isJsonNull()
                    && r.get("bound").getAsBoolean()) {
                bound.put(e.getKey(), Boolean.TRUE);
            }
            if (!r.has("ops") || !r.get("ops").isJsonArray()) continue;
            List<Op> ops = out.computeIfAbsent(e.getKey(), k -> new ArrayList<>());
            for (JsonElement oe : r.getAsJsonArray("ops")) {
                if (oe == null || !oe.isJsonObject()) continue;
                JsonObject o = oe.getAsJsonObject();
                String method = str(o, "method");
                String gl = str(o, "op");
                if (method == null || gl == null) continue;
                List<JsonObject> args = new ArrayList<>();
                if (o.has("args") && o.get("args").isJsonArray()) {
                    for (JsonElement ae : o.getAsJsonArray("args")) {
                        args.add(ae != null && ae.isJsonObject() ? ae.getAsJsonObject() : null);
                    }
                }
                ops.add(new Op(method, gl, args, str(o, "group"),
                        o.has("anchored") && !o.get("anchored").isJsonNull()
                                && o.get("anchored").getAsBoolean()));
            }
        }
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull() || !e.isJsonPrimitive()) ? null : e.getAsString();
    }

    /** Parses one renderer row's TESR caller prefix; null when absent or malformed. */
    static TesrPrefix parsePrefix(JsonObject r) {
        if (r == null || !r.has("prefix") || !r.get("prefix").isJsonObject()) return null;
        try {
            JsonObject p = r.getAsJsonObject("prefix");
            double[] translate = null;
            if (p.has("translate") && p.get("translate").isJsonArray()) {
                JsonArray t = p.getAsJsonArray("translate");
                if (t.size() != 3) return null;
                translate = new double[3];
                for (int i = 0; i < 3; i++) {
                    if (!t.get(i).isJsonPrimitive()) return null;
                    translate[i] = t.get(i).getAsDouble();
                }
            }
            Integer metaBase = null;
            if (p.has("metaBase") && p.get("metaBase").isJsonPrimitive()) {
                metaBase = Integer.valueOf(p.get("metaBase").getAsInt());
            }
            Map<Integer, double[]> facing = new LinkedHashMap<>();
            if (p.has("facing") && p.get("facing").isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : p.getAsJsonObject("facing").entrySet()) {
                    int key;
                    try {
                        key = Integer.parseInt(e.getKey());
                    } catch (NumberFormatException n) {
                        return null;
                    }
                    if (e.getValue() == null || !e.getValue().isJsonArray()
                            || e.getValue().getAsJsonArray().size() != 4) return null;
                    double[] rot = new double[4];
                    for (int i = 0; i < 4; i++) {
                        JsonElement v = e.getValue().getAsJsonArray().get(i);
                        if (!v.isJsonPrimitive()) return null;
                        rot[i] = v.getAsDouble();
                    }
                    facing.put(key, rot);
                }
            }
            if (translate == null && facing.isEmpty()) return null;
            return new TesrPrefix(translate, metaBase, facing);
        } catch (Throwable t) {
            return null;
        }
    }

    /** For tests only. */
    static void resetForTests() {
        BY_RENDERER = null;
        TE_OF_RENDERER = null;
        PREFIX_OF_RENDERER = null;
        BOUND_RENDERERS = null;
    }
}

