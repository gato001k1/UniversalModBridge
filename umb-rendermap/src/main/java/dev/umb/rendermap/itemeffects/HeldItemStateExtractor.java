package dev.umb.rendermap.itemeffects;

import com.google.gson.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Extracts the recoverable held-item slice without invoking legacy rendering.
 *
 * <p>The input renderer sidecar is deliberately treated as evidence, not as executable
 * renderer code. A transform is attached to every named OBJ part because a legacy GL matrix
 * stack applies the operation to the current draw call; when the bytecode does not identify a
 * narrower part, duplicating the operation for all parts is the honest representation.
 * Helper walks are bounded by {@link #MAX_HELPER_CALL_DEPTH}; this is the same named bound used
 * by the existing renderer extractor. Dynamic values are retained as dependencies and never
 * converted into constants.</p>
 */
public final class HeldItemStateExtractor {
    public static final int MAX_HELPER_CALL_DEPTH = 3;
    private static final Set<String> HELD = Set.of("EQUIPPED", "EQUIPPED_FIRST_PERSON",
            "firstperson_righthand", "thirdperson_righthand");

    private HeldItemStateExtractor() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: HeldItemStateExtractor <jar> <rendermap.json> <item-display-transforms.json> <out.json>");
            System.exit(2);
        }
        extract(Path.of(args[0]), Path.of(args[1]), Path.of(args[2]), Path.of(args[3]));
    }

    public static void extract(Path jarPath, Path renderMapPath, Path displayPath, Path output) throws IOException {
        JsonObject map = JsonParser.parseString(Files.readString(renderMapPath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject display = JsonParser.parseString(Files.readString(displayPath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject renderers = object(display, "renderers");
        JsonArray items = new JsonArray();
        Counts counts = new Counts();
        for (JsonElement itemElement : array(map, "items")) {
            JsonObject row = itemElement.getAsJsonObject();
            JsonObject item = new JsonObject();
            String id = string(row, "id");
            String rendererClass = string(row, "rendererClass");
            item.addProperty("id", id);
            item.addProperty("renderer", rendererClass);
            item.addProperty("status", rendererClass == null ? "NO_HELD_RENDERER" : "PARTIALLY_DATA_DRIVEN");
            JsonArray models = new JsonArray();
            for (JsonElement modelElement : array(row, "models")) {
                JsonObject model = modelElement.getAsJsonObject();
                JsonObject modelOut = new JsonObject();
                modelOut.addProperty("obj", string(model, "path"));
                JsonArray parts = new JsonArray();
                for (JsonElement group : array(row, "groups")) parts.add(group);
                if (parts.isEmpty()) parts.add("<default>");
                modelOut.add("parts", parts);
                JsonObject contexts = new JsonObject();
                JsonObject renderer = rendererClass != null && renderers.get(rendererClass) != null
                        ? renderers.getAsJsonObject(rendererClass) : new JsonObject();
                for (String context : HELD) {
                    JsonArray ops = renderer.getAsJsonArray(context);
                    JsonObject ctx = new JsonObject();
                    ctx.add("static", ops == null ? new JsonArray() : staticOps(ops));
                    ctx.add("nbt", dynamicOps(ops));
                    ctx.add("animations", new JsonArray());
                    contexts.add(context, ctx);
                    if (ops != null && ops.size() > 0) counts.dataDriven++;
                }
                modelOut.add("contexts", contexts);
                // A legacy matrix stack affects the current draw call. Without a recoverable
                // part predicate, duplicate the proven context record for every named OBJ part.
                JsonObject perPart = new JsonObject();
                for (JsonElement part : parts) perPart.add(part.getAsString(), contexts.deepCopy());
                modelOut.add("partTransforms", perPart);
                models.add(modelOut);
            }
            item.add("models", models);
            if (rendererClass == null || models.size() == 0) counts.skipped++;
            else if (bool(row, "dynamic")) counts.partial++;
            else counts.dataItems++;
            items.add(item);
        }
        JsonObject root = new JsonObject();
        root.addProperty("schema", "umb.held-item-states.v1");
        root.addProperty("analysisBound", MAX_HELPER_CALL_DEPTH);
        root.addProperty("source", "static bytecode sidecars; legacy GL was not executed");
        root.add("items", items);
        JsonObject c = new JsonObject();
        c.addProperty("itemsWithHeldRenderers", counts.dataItems + counts.partial);
        c.addProperty("fullyDataDriven", counts.dataItems);
        c.addProperty("partiallyDataDriven", counts.partial);
        c.addProperty("proceduralSkipped", counts.skipped);
        c.addProperty("transformContextsWithOps", counts.dataDriven);
        root.add("counts", c);
        Files.createDirectories(output.toAbsolutePath().normalize().getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
    }

    private static JsonArray staticOps(JsonArray ops) {
        JsonArray out = new JsonArray();
        if (ops == null) return out;
        for (JsonElement e : ops) {
            if (!e.isJsonObject() || e.getAsJsonObject().has("dynamic") && e.getAsJsonObject().get("dynamic").getAsBoolean()) continue;
            out.add(e.deepCopy());
        }
        return out;
    }

    private static JsonArray dynamicOps(JsonArray ops) {
        JsonArray out = new JsonArray();
        if (ops == null) return out;
        for (JsonElement e : ops) {
            if (!e.isJsonObject()) continue;
            JsonObject o = e.getAsJsonObject();
            if (o.has("dynamic") && o.get("dynamic").getAsBoolean()) {
                JsonObject d = new JsonObject();
                d.addProperty("field", dependency(o));
                d.addProperty("op", o.has("op") ? o.get("op").getAsString() : "unknown");
                d.addProperty("scale", 1.0);
                d.addProperty("note", o.has("note") ? o.get("note").getAsString() : "unresolved bytecode value");
                out.add(d);
            }
        }
        return out;
    }

    private static String dependency(JsonObject o) {
        String note = o.has("note") ? o.get("note").getAsString() : "";
        if (note.contains("getInteger") || note.contains("getInt")) return "legacy_nbt:unknown";
        if (note.contains("tick") || note.contains("timer") || note.contains("partial")) return "timer:unknown";
        return "procedural:unknown";
    }

    private static JsonObject object(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    private static JsonArray array(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

    private static boolean bool(JsonObject o, String key) {
        try { return o.has(key) && o.get(key).getAsBoolean(); }
        catch (RuntimeException ex) { return false; }
    }

    private static final class Counts { int dataItems, partial, skipped, dataDriven; }
}
