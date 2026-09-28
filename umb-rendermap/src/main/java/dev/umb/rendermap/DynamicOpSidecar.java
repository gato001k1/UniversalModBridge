package dev.umb.rendermap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Moving-parts lane: emits {@code renderer-dynamic-ops.json} beside the existing sidecars.
 *
 * <p>For every renderer class in the jar, every method with a TileEntity-typed parameter is
 * replayed through {@link DynamicOpResolver} (bounded, honest skips with per-reason counts),
 * and the results are joined to render-map blocks ({@code id} + {@code tesrClass} read as
 * plain JSON - this class never touches another lane's parser) so the runtime can look up,
 * per block id, exactly which tile-entity fields to sync. New file only: the existing three
 * outputs are written exactly as before.</p>
 */
public final class DynamicOpSidecar {

    private DynamicOpSidecar() {
    }

    static String effectiveTeClass(String rendererParameter, String mapTeClass) {
        return mapTeClass != null ? mapTeClass : rendererParameter;
    }

    public static void write(Path outPath, JarIndex jar, Path rendermapPath) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("schema", "umb.renderer-dynamic-ops.v1");
        JsonObject bounds = new JsonObject();
        bounds.addProperty("maxExprDepth", DynamicOpResolver.MAX_EXPR_DEPTH);
        bounds.addProperty("maxExprNodes", DynamicOpResolver.MAX_EXPR_NODES);
        bounds.addProperty("maxFieldHops", DynamicOpResolver.MAX_FIELD_HOPS);
        bounds.addProperty("maxGroupsPerMethod", DynamicOpResolver.MAX_GROUPS_PER_METHOD);
        bounds.addProperty("maxOpsPerGroup", DynamicOpResolver.MAX_OPS_PER_GROUP);
        bounds.addProperty("maxHelperDepth", RendererTransformExtractor.MAX_HELPER_CALL_DEPTH);
        root.add("bounds", bounds);

        Map<String, PerRenderer> perRenderer = new TreeMap<>();
        int methods = 0;
        if (jar != null) {
            for (ClassNode cn : jar.classes.values()) {
                List<DynamicOpResolver.Resolved> parts = new ArrayList<>();
                for (MethodNode mn : cn.methods) {
                    if (mn.instructions == null || mn.instructions.size() == 0) continue;
                    int teArg = DynamicOpResolver.teArg(mn, jar);
                    if (teArg < 0) continue;
                    int partialArg = DynamicOpResolver.partialArg(mn);
                    Map<Integer, String> posArgs = DynamicOpResolver.posArgs(mn);
                    parts.add(DynamicOpResolver.resolve(cn, mn, jar, teArg, partialArg, posArgs));
                    methods++;
                }
                if (parts.isEmpty()) continue;
                PerRenderer merged = new PerRenderer();
                for (DynamicOpResolver.Resolved r : parts) {
                    if (merged.teClass == null) merged.teClass = r.teClass;
                    merged.partialArg = r.partialArg;
                    merged.ops.addAll(r.ops);
                    for (Map.Entry<String, List<String>> f : r.fields.entrySet()) {
                        merged.fields.putIfAbsent(f.getKey(), f.getValue());
                    }
                    for (Map.Entry<String, DynamicOpResolver.Channel> c : r.channels.entrySet()) {
                        merged.channels.putIfAbsent(c.getKey(), c.getValue());
                    }
                    if (merged.dispatch == null) merged.dispatch = r.dispatch;
                    if (merged.prefix == null) merged.prefix = r.prefix;
                    merged.substituted += r.substituted;
                    merged.resolvedArgs += r.resolvedArgs;
                    for (Map.Entry<String, Integer> e : r.skipped.entrySet()) {
                        merged.skipped.merge(e.getKey(), e.getValue(), Integer::sum);
                    }
                }
                perRenderer.put(JarIndex.dotted(cn.name), merged);
            }
        }

        JsonObject renderers = new JsonObject();
        for (Map.Entry<String, PerRenderer> e : perRenderer.entrySet()) {
            JsonObject r = new JsonObject();
            if (e.getValue().teClass != null) r.addProperty("teClass", e.getValue().teClass);
            r.addProperty("partialArg", e.getValue().partialArg);
            JsonArray ops = new JsonArray();
            for (DynamicOpResolver.Op op : e.getValue().ops) {
                JsonObject o = new JsonObject();
                o.addProperty("method", op.method);
                o.addProperty("op", op.gl);
                if (op.group != null) o.addProperty("group", op.group);
                if (op.anchored) o.addProperty("anchored", true);
                JsonArray args = new JsonArray();
                JsonArray skips = new JsonArray();
                boolean anySkip = false;
                for (DynamicOpResolver.Arg a : op.args) {
                    if (a.expr != null) {
                        args.add(a.expr.toJson());
                        skips.add((String) null);
                    } else {
                        args.add((String) null);
                        skips.add(a.skipReason);
                        anySkip = true;
                    }
                }
                o.add("args", args);
                if (anySkip) o.add("skip", skips);
                ops.add(o);
            }
            r.add("ops", ops);
            JsonArray fields = new JsonArray();
            for (Map.Entry<String, List<String>> f : e.getValue().fields.entrySet()) {
                JsonObject fo = new JsonObject();
                fo.addProperty("key", f.getKey());
                JsonArray hops = new JsonArray();
                for (String h : f.getValue()) hops.add(h);
                fo.add("hops", hops);
                fields.add(fo);
            }
            r.add("fields", fields);
            JsonArray channels = new JsonArray();
            for (DynamicOpResolver.Channel c : e.getValue().channels.values()) {
                channels.add(channelJson(c));
            }
            r.add("channels", channels);
            if (e.getValue().dispatch != null) {
                r.add("dispatch", dispatchJson(e.getValue().dispatch));
            }
            if (e.getValue().prefix != null) {
                r.add("prefix", prefixJson(e.getValue().prefix));
            }
            r.addProperty("resolvedArgs", e.getValue().resolvedArgs);
            JsonObject skipped = new JsonObject();
            for (Map.Entry<String, Integer> s : e.getValue().skipped.entrySet()) {
                skipped.addProperty(s.getKey(), s.getValue());
            }
            r.add("skippedArgs", skipped);
            renderers.add(e.getKey(), r);
        }
        root.add("renderers", renderers);

        // Block join: plain-JSON read of id + tesrClass (+ tileEntityClass for the audit).
        JsonObject blocks = new JsonObject();
        int joined = 0;
        // TESR classes bound by at least one joined block. The runtime unions draws only
        // over UNBOUND same-TE renderers (helpers reached by interface dispatch, never a
        // TESR themselves): unioning a second bound TESR draws another variant's parts at
        // another variant's poses (live 2026-09-24: RenderTurretFriendly's draws replayed
        // over the chekhov mesh = scattered turret).
        Set<String> boundTesrs = new LinkedHashSet<>();
        Map<String, String> blockClasses = new LinkedHashMap<>();
        if (rendermapPath != null) {
            try {
                String text = Files.readString(rendermapPath, StandardCharsets.UTF_8);
                JsonObject map = JsonParser.parseString(text).getAsJsonObject();
                JsonArray rows = map.has("blocks") && map.get("blocks").isJsonArray()
                        ? map.getAsJsonArray("blocks") : new JsonArray();
                for (JsonElement e : rows) {
                    if (e == null || !e.isJsonObject()) continue;
                    JsonObject row = e.getAsJsonObject();
                    String id = str(row, "id");
                    String tesr = str(row, "tesrClass");
                    if (id == null || tesr == null) continue;
                    String cls = str(row, "className");
                    if (cls != null) blockClasses.put(id, cls);
                    PerRenderer pr = perRenderer.get(tesr);
                    if (pr == null) continue;
                    boundTesrs.add(tesr);
                    String mapTe = str(row, "tileEntityClass");
                    JsonObject b = new JsonObject();
                    // The renderer method parameter is sometimes the vanilla TileEntity base
                    // class even when the block row has an exact factory/TE linkage.  The map
                    // row is the authoritative block->TE relation for core filtering; prefer
                    // it whenever present, while retaining the renderer class as the fallback
                    // for rows whose linkage is genuinely absent.
                    String effectiveTe = effectiveTeClass(pr.teClass, mapTe);
                    if (effectiveTe != null) b.addProperty("teClass", effectiveTe);
                    if (mapTe != null) b.addProperty("mapTeClass", mapTe);
                    // Fields AND channels union over every renderer bound to the same TE class:
                    // a block's visual data can live in a helper renderer reached by interface
                    // dispatch (door parts render from IRenderDoors implementations, not from
                    // the bound TESR), and every entry agrees because keys are unique per
                    // origin. The BER evaluates by key, so the union is sound.
                    JsonArray fields = new JsonArray();
                    Set<String> seenFields = new LinkedHashSet<>();
                    JsonArray blockChannels = new JsonArray();
                    Set<String> seenChannels = new LinkedHashSet<>();
                    for (PerRenderer q : perRenderer.values()) {
                        if (pr.teClass == null) {
                            if (q != pr) continue;
                        } else if (!pr.teClass.equals(q.teClass)) {
                            continue;
                        }
                        for (Map.Entry<String, List<String>> f : q.fields.entrySet()) {
                            if (!seenFields.add(f.getKey())) continue;
                            JsonObject fo = new JsonObject();
                            fo.addProperty("key", f.getKey());
                            JsonArray hops = new JsonArray();
                            for (String h : f.getValue()) hops.add(h);
                            fo.add("hops", hops);
                            fields.add(fo);
                        }
                        for (DynamicOpResolver.Channel c : q.channels.values()) {
                            if (!seenChannels.add(c.key)) continue;
                            blockChannels.add(channelJson(c));
                        }
                    }
                    b.add("fields", fields);
                    b.add("channels", blockChannels);
                    // The render dispatch belongs to the bound TESR alone (single dispatcher -
                    // never unioned: every same-TE helper is a dispatch *target*). The caller
                    // prefix rides with it for the same reason.
                    if (pr.dispatch != null) b.add("dispatch", dispatchJson(pr.dispatch));
                    if (pr.prefix != null) b.add("prefix", prefixJson(pr.prefix));
                    blocks.add(id, b);
                    joined++;
                }
            } catch (Exception ex) {
                RendererTransformExtractor.log("  dynamic-ops block join skipped: " + ex);
            }
        }
        root.add("blocks", blocks);
        for (String bound : boundTesrs) {
            JsonElement re = renderers.get(bound);
            if (re != null && re.isJsonObject()) {
                re.getAsJsonObject().addProperty("bound", true);
            }
        }

        // Door-live lane: which joined blocks have state-following collision bounds.
        int liveBounds = 0;
        try {
            LiveBoundsDetector.Result lb = LiveBoundsDetector.detect(jar, blockClasses);
            for (String id : lb.liveBlocks) {
                JsonElement e = blocks.get(id);
                if (e != null && e.isJsonObject()) {
                    e.getAsJsonObject().addProperty("liveBounds", true);
                    liveBounds++;
                } else {
                    // A state-following block with no TESR data still needs its flag: the
                    // host looks blocks up by id, and absence would silently keep it static.
                    JsonObject b = new JsonObject();
                    b.add("fields", new JsonArray());
                    b.add("channels", new JsonArray());
                    b.addProperty("liveBounds", true);
                    blocks.add(id, b);
                    liveBounds++;
                }
            }
            RendererTransformExtractor.log("  live-bounds blocks=" + liveBounds + " " + lb.counts);
        } catch (Throwable t) {
            RendererTransformExtractor.log("WARNING: live-bounds detection failed: " + t);
        }

        int resolvedTotal = 0;
        int substitutedTotal = 0;
        Map<String, Integer> skipTotals = new TreeMap<>();
        for (PerRenderer pr : perRenderer.values()) {
            resolvedTotal += pr.resolvedArgs;
            substitutedTotal += pr.substituted;
            for (Map.Entry<String, Integer> s : pr.skipped.entrySet()) {
                skipTotals.merge(s.getKey(), s.getValue(), Integer::sum);
            }
        }
        RendererTransformExtractor.log("  dynamic ops: renderers=" + perRenderer.size()
                + " methods=" + methods + " blocks=" + joined + " resolvedArgs=" + resolvedTotal
                + " substituted=" + substitutedTotal
                + " skippedArgs=" + skipTotals);

        Files.writeString(outPath,
                new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull() || !e.isJsonPrimitive()) ? null : e.getAsString();
    }

    private static JsonObject channelJson(DynamicOpResolver.Channel c) {
        JsonObject co = new JsonObject();
        co.addProperty("key", c.key);
        co.addProperty("staticOwner", c.staticOwner);
        co.addProperty("staticMethod", c.staticMethod);
        co.addProperty("stringArg", c.stringArg);
        JsonArray hops = new JsonArray();
        for (String h : c.animHops) hops.add(h);
        co.add("animHops", hops);
        if (c.anim != null) co.add("anim", animJson(c.anim));
        return co;
    }

    private static JsonObject animJson(DynamicOpResolver.AnimRecipe a) {
        JsonObject o = new JsonObject();
        o.addProperty("providerOwner", JarIndex.dotted(a.providerOwner));
        o.addProperty("providerMethod", a.providerMethod);
        o.add("receiverHops", strings(a.receiverHops));
        o.add("receiverKinds", strings(a.receiverKinds));
        JsonArray argHops = new JsonArray();
        JsonArray argKinds = new JsonArray();
        for (int i = 0; i < a.argHops.size(); i++) {
            argHops.add(strings(a.argHops.get(i)));
            argKinds.add(strings(a.argKinds.get(i)));
        }
        o.add("argHops", argHops);
        o.add("argKinds", argKinds);
        o.addProperty("clockOwner", JarIndex.dotted(a.clockOwner));
        o.addProperty("clockMethod", a.clockMethod);
        o.addProperty("clockField", a.clockField);
        return o;
    }

    private static JsonObject dispatchJson(DynamicOpResolver.Dispatch d) {
        JsonObject o = new JsonObject();
        o.addProperty("owner", JarIndex.dotted(d.owner));
        o.addProperty("method", d.method);
        o.add("objectHops", strings(d.objectHops));
        o.add("objectKinds", strings(d.objectKinds));
        return o;
    }

    private static JsonObject prefixJson(DynamicOpResolver.TesrPrefix p) {
        JsonObject o = new JsonObject();
        if (p.translate != null) {
            JsonArray t = new JsonArray();
            for (double v : p.translate) t.add(v);
            o.add("translate", t);
        }
        if (p.metaBase != null) o.addProperty("metaBase", p.metaBase.intValue());
        if (p.facing != null && !p.facing.isEmpty()) {
            JsonObject f = new JsonObject();
            for (Map.Entry<Integer, double[]> e : p.facing.entrySet()) {
                JsonArray r = new JsonArray();
                for (double v : e.getValue()) r.add(v);
                f.add(String.valueOf(e.getKey()), r);
            }
            o.add("facing", f);
        }
        return o;
    }

    private static JsonArray strings(List<String> in) {
        JsonArray out = new JsonArray();
        if (in != null) for (String s : in) out.add(s);
        return out;
    }

    private static final class PerRenderer {
        String teClass;
        int partialArg = -1;
        final List<DynamicOpResolver.Op> ops = new ArrayList<>();
        final Map<String, List<String>> fields = new LinkedHashMap<>();
        final Map<String, DynamicOpResolver.Channel> channels = new LinkedHashMap<>();
        DynamicOpResolver.Dispatch dispatch;
        DynamicOpResolver.TesrPrefix prefix;
        int resolvedArgs;
        int substituted;
        final Map<String, Integer> skipped = new TreeMap<>();
    }
}
