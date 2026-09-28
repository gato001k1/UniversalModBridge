package dev.umb.objbridge.transform;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reader for the bounded, context-oriented sidecar emitted by rendermap extraction. */
public final class ItemDisplayTransforms {
    private final Map<String, Map<String, List<TransformOp>>> byClass;

    private ItemDisplayTransforms(Map<String, Map<String, List<TransformOp>>> byClass) {
        this.byClass = Collections.unmodifiableMap(byClass);
    }

    public static ItemDisplayTransforms empty() { return new ItemDisplayTransforms(Map.of()); }

    public static ItemDisplayTransforms read(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root = new com.google.gson.Gson().fromJson(json, JsonObject.class);
        Map<String, Map<String, List<TransformOp>>> out = new HashMap<>();
        JsonElement rs = root == null ? null : root.get("renderers");
        if (rs != null && rs.isJsonObject()) {
            for (Map.Entry<String, JsonElement> re : rs.getAsJsonObject().entrySet()) {
                if (!re.getValue().isJsonObject()) continue;
                Map<String, List<TransformOp>> contexts = new HashMap<>();
                for (Map.Entry<String, JsonElement> ce : re.getValue().getAsJsonObject().entrySet()) {
                    if (!ce.getValue().isJsonArray()) continue;
                    List<TransformOp> ops = new ArrayList<>();
                    for (JsonElement e : ce.getValue().getAsJsonArray())
                        if (e.isJsonObject()) ops.add(parse(e.getAsJsonObject()));
                    contexts.put(ce.getKey(), List.copyOf(ops));
                }
                out.put(re.getKey(), Map.copyOf(contexts));
            }
        }
        return new ItemDisplayTransforms(out);
    }

    public boolean hasClass(String name) { return name != null && byClass.containsKey(name); }
    public int classCount() { return byClass.size(); }
    public List<TransformOp> forContext(String name, String context) {
        if (name == null || context == null) return List.of();
        return byClass.getOrDefault(name, Map.of()).getOrDefault(context, List.of());
    }
    public Float composedScale(String name, String context) {
        List<TransformOp> ops = forContext(name, context);
        if (ops.isEmpty()) return null;
        // A context with an unresolved glScale/glScaled is not an exact scale.  Treating the
        // skipped operation as identity makes a dynamic GUI branch look like scale 1 and then
        // lets the relative-perspective ratio enlarge the icon.  The bounded extractor records
        // the branch for auditability, but the consumer must use the fit fallback until the
        // runtime value is proven.
        if (ops.stream().anyMatch(o -> o.isScale() && o.hasUnresolvedArg())) return null;
        float scale = ModelScale.uniformScale(TransformComposer.compose(ops).representative());
        return Float.isFinite(scale) && scale > 1.0e-4f ? scale : null;
    }

    private static TransformOp parse(JsonObject o) {
        float[] a = new float[o.has("args") && o.get("args").isJsonArray() ? o.getAsJsonArray("args").size() : 0];
        for (int i = 0; i < a.length; i++) {
            JsonElement e = o.getAsJsonArray("args").get(i);
            a[i] = e == null || e.isJsonNull() ? Float.NaN : Float.parseFloat(e.getAsString());
        }
        boolean dynamic = o.has("dynamic") && o.get("dynamic").getAsBoolean();
        String note = o.has("note") ? o.get("note").getAsString() : null;
        return new TransformOp(o.has("method") ? o.get("method").getAsString() : null,
                o.has("op") ? o.get("op").getAsString() : null, a, dynamic, note);
    }
}
