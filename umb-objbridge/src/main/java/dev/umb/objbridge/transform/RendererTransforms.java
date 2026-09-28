package dev.umb.objbridge.transform;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tolerant reader for {@code research/out/legacy/rendermap/renderer-transforms.json}: {@code
 * {"renderers": {"<fully.qualified.RendererClass>": [ {method,op,args[],dynamicArgs[],dynamic,note} ] }}}.
 *
 * <p>No net.minecraft imports, so it unit-tests off the game classpath - same shape of class as
 * {@link dev.umb.objbridge.map.RenderMap}.
 */
public final class RendererTransforms {

    private final Map<String, List<TransformOp>> byClass;

    private RendererTransforms(Map<String, List<TransformOp>> byClass) {
        this.byClass = Collections.unmodifiableMap(byClass);
    }

    /** Empty list when the class is absent from the file - not an error, just "no data". */
    public List<TransformOp> forClass(String className) {
        if (className == null) return List.of();
        return byClass.getOrDefault(className, List.of());
    }

    public boolean hasClass(String className) {
        return className != null && byClass.containsKey(className);
    }

    public int classCount() { return byClass.size(); }

    public static RendererTransforms read(Path file) throws IOException {
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonObject root = new Gson().fromJson(r, JsonObject.class);
            return of(root == null ? new JsonObject() : root);
        }
    }

    public static RendererTransforms of(JsonObject root) {
        Map<String, List<TransformOp>> out = new HashMap<>();
        JsonElement re = root == null ? null : root.get("renderers");
        if (re != null && re.isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : re.getAsJsonObject().entrySet()) {
                if (e.getValue() == null || !e.getValue().isJsonArray()) continue;
                List<TransformOp> ops = new ArrayList<>();
                for (JsonElement oe : e.getValue().getAsJsonArray()) {
                    if (oe != null && oe.isJsonObject()) ops.add(parseOp(oe.getAsJsonObject()));
                }
                out.put(e.getKey(), List.copyOf(ops));
            }
        }
        return new RendererTransforms(out);
    }

    private static TransformOp parseOp(JsonObject o) {
        String method = str(o, "method");
        String op = str(o, "op");
        JsonArray a = (o.has("args") && o.get("args").isJsonArray()) ? o.getAsJsonArray("args") : new JsonArray();
        float[] args = new float[a.size()];
        for (int i = 0; i < a.size(); i++) {
            JsonElement v = a.get(i);
            args[i] = (v == null || v.isJsonNull()) ? Float.NaN : v.getAsFloat();
        }
        boolean dynamic = o.has("dynamic") && !o.get("dynamic").isJsonNull() && o.get("dynamic").getAsBoolean();
        String note = str(o, "note");
        return new TransformOp(method, op, args, dynamic, note);
    }

    private static String str(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }
}
