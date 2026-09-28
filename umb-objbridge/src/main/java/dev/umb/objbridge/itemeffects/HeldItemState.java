package dev.umb.objbridge.itemeffects;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Tolerant reader for {@code held-item-states.json}; missing data is an honest empty state. */
public record HeldItemState(Map<String, ItemState> items) {
    public record ItemState(String id, String renderer, String status, List<ModelState> models) { }
    public record ModelState(String obj, List<String> parts, Map<String, ContextState> contexts) { }
    public record ContextState(List<JsonObject> staticOps, List<Dependency> nbt, List<JsonObject> animations) { }
    public record Dependency(String field, String op, double scale, String note) { }

    public static HeldItemState read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) return new HeldItemState(Map.of());
        JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
        Map<String, ItemState> out = new LinkedHashMap<>();
        for (JsonElement e : array(root, "items")) {
            if (!e.isJsonObject()) continue;
            JsonObject i = e.getAsJsonObject();
            String id = string(i, "id");
            List<ModelState> models = new ArrayList<>();
            for (JsonElement me : array(i, "models")) models.add(model(me));
            out.put(id, new ItemState(id, string(i, "renderer"), string(i, "status"), List.copyOf(models)));
        }
        return new HeldItemState(Collections.unmodifiableMap(out));
    }

    private static ModelState model(JsonElement e) {
        JsonObject o = e.getAsJsonObject();
        Map<String, ContextState> contexts = new LinkedHashMap<>();
        JsonObject cs = object(o, "contexts");
        for (var entry : cs.entrySet()) {
            JsonObject c = entry.getValue().getAsJsonObject();
            List<JsonObject> stat = new ArrayList<>();
            for (JsonElement op : array(c, "static")) if (op.isJsonObject()) stat.add(op.getAsJsonObject());
            List<Dependency> deps = new ArrayList<>();
            for (JsonElement d : array(c, "nbt")) {
                JsonObject x = d.getAsJsonObject();
                deps.add(new Dependency(string(x, "field"), string(x, "op"), number(x, "scale", 1), string(x, "note")));
            }
            List<JsonObject> anim = new ArrayList<>();
            for (JsonElement a : array(c, "animations")) if (a.isJsonObject()) anim.add(a.getAsJsonObject());
            contexts.put(entry.getKey(), new ContextState(List.copyOf(stat), List.copyOf(deps), List.copyOf(anim)));
        }
        return new ModelState(string(o, "obj"), strings(o, "parts"), Map.copyOf(contexts));
    }

    private static JsonArray array(JsonObject o, String k) { JsonElement e=o.get(k); return e!=null&&e.isJsonArray()?e.getAsJsonArray():new JsonArray(); }
    private static JsonObject object(JsonObject o, String k) { JsonElement e=o.get(k); return e!=null&&e.isJsonObject()?e.getAsJsonObject():new JsonObject(); }
    private static String string(JsonObject o, String k) { JsonElement e=o.get(k); return e==null||e.isJsonNull()?null:e.getAsString(); }
    private static double number(JsonObject o, String k, double d) { try { return o.has(k)?o.get(k).getAsDouble():d; } catch (RuntimeException ex) { return d; } }
    private static List<String> strings(JsonObject o, String k) { List<String> out=new ArrayList<>(); for(JsonElement e:array(o,k)) if(e.isJsonPrimitive()) out.add(e.getAsString()); return out; }
}
