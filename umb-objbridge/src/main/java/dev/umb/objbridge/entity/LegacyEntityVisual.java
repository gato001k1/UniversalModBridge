package dev.umb.objbridge.entity;

import com.google.gson.*;
import dev.umb.objbridge.ObjBridge;
import dev.umb.objbridge.map.RenderMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Data-only entity render binding read from the existing rendermap entity rows. */
public record LegacyEntityVisual(String entityClass, String model, String texture, List<String> groups,
                                 List<Box> boxes) {
    public record Box(float x, float y, float z, float w, float h, float d) { }
    private static volatile Map<String, LegacyEntityVisual> CACHE = Map.of();
    private static volatile int loaded;
    private static volatile int drawable;
    private static volatile int skipped;

    public static Map<String, LegacyEntityVisual> all() {
        Map<String, LegacyEntityVisual> got = CACHE;
        if (!got.isEmpty()) return got;
        synchronized (LegacyEntityVisual.class) {
            if (!CACHE.isEmpty()) return CACHE;
            Map<String, LegacyEntityVisual> out = new LinkedHashMap<>();
            for (Path path : ObjBridge.entityRenderMapPaths()) read(path, out);
            loaded = out.size();
            drawable = (int) out.values().stream().filter(v -> v.texture != null && (v.model != null || !v.boxes.isEmpty())).count();
            skipped = loaded - drawable;
            CACHE = Map.copyOf(out);
            return CACHE;
        }
    }

    public static int loadedCount() { all(); return loaded; }
    public static int drawableCount() { all(); return drawable; }
    public static int skippedCount() { all(); return skipped; }

    public static void clear() { synchronized (LegacyEntityVisual.class) { CACHE = Map.of(); } }

    private static void read(Path path, Map<String, LegacyEntityVisual> out) {
        if (path == null || !Files.isRegularFile(path)) return;
        try {
            JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            JsonElement rows = root.get("entities");
            if (rows == null || !rows.isJsonArray()) return;
            for (JsonElement e : rows.getAsJsonArray()) {
                if (!e.isJsonObject()) continue;
                JsonObject o = e.getAsJsonObject();
                String cls = str(o, "entityClass");
                if (cls == null) continue;
                List<RenderMap.Asset> models = assets(o, "models");
                List<RenderMap.Asset> textures = assets(o, "textures");
                if (textures.isEmpty()) textures = assets(o, "entityTexture");
                String model = firstModel(models);
                String texture = firstTexture(textures);
                List<String> groups = strings(o, "groups");
                out.putIfAbsent(cls, new LegacyEntityVisual(cls, model, texture, groups, javaBoxes(o)));
            }
        } catch (Exception ignored) {
            // A malformed optional extraction is counted by the aggregate skip diagnostics.
        }
    }

    private static List<Box> javaBoxes(JsonObject o) {
        List<Box> out = new ArrayList<>(); JsonElement e = o.get("javaModels");
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement model : e.getAsJsonArray()) if (model.isJsonObject()) {
            JsonElement parts = model.getAsJsonObject().get("parts");
            if (parts == null || !parts.isJsonArray()) continue;
            for (JsonElement part : parts.getAsJsonArray()) if (part.isJsonObject()) {
                JsonObject b = part.getAsJsonObject().getAsJsonObject("box");
                JsonObject rp = part.getAsJsonObject().getAsJsonObject("rotationPoint");
                if (b == null) continue;
                out.add(new Box(number(b,"x") + (rp == null ? 0 : number(rp,"x")),
                        number(b,"y") + (rp == null ? 0 : number(rp,"y")),
                        number(b,"z") + (rp == null ? 0 : number(rp,"z")),
                        number(b,"w"), number(b,"h"), number(b,"d")));
            }
        }
        return List.copyOf(out);
    }
    private static float number(JsonObject o, String key) { JsonElement e=o.get(key); return e==null?0:e.getAsFloat(); }

    private static String firstModel(List<RenderMap.Asset> a) {
        return a.stream().map(RenderMap.Asset::path).filter(Objects::nonNull)
                .filter(x -> x.toLowerCase(Locale.ROOT).endsWith(".obj")).findFirst().orElse(null);
    }
    private static String firstTexture(List<RenderMap.Asset> a) {
        return a.stream().map(RenderMap.Asset::path).filter(Objects::nonNull)
                .filter(x -> !dev.umb.objbridge.map.TexturePick.isEffect(x)).findFirst().orElse(null);
    }
    private static List<RenderMap.Asset> assets(JsonObject o, String key) {
        List<RenderMap.Asset> out = new ArrayList<>(); JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return out;
        for (JsonElement x : e.getAsJsonArray()) if (x.isJsonObject()) {
            JsonObject a = x.getAsJsonObject(); String p = str(a, "path");
            if (p != null) out.add(new RenderMap.Asset(p, str(a, "assetPath")));
        }
        return out;
    }
    private static List<String> strings(JsonObject o, String key) {
        List<String> out = new ArrayList<>(); JsonElement e = o.get(key);
        if (e != null && e.isJsonArray()) for (JsonElement x : e.getAsJsonArray()) if (x.isJsonPrimitive()) out.add(x.getAsString());
        return List.copyOf(out);
    }
    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key); return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
