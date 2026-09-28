package dev.umb.hostagent.content;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Converts extracted legacy recipes to 26.2 datapack recipe JSON. */
public final class RecipeGenerator {
    public record Counts(int total, int owned, int vanillaSkipped, int unknownClass, int unresolvableItem) { }
    private RecipeGenerator() { }

    public static Counts generate(LegacySnapshot snapshot, String namespace, Path out) throws IOException {
        if (out.getParent() != null) Files.createDirectories(out.getParent());
        JsonObject root = new JsonObject();
        int total = 0, owned = 0, skipped = 0, unknown = 0, unresolved = 0;
        Map<String, Integer> ids = new LinkedHashMap<>();
        for (LegacyRecipe r : snapshot.recipes) {
            total++;
            if (r.type == null || r.output == null) { unknown++; continue; }
            String outputId = resolve(r.output, namespace);
            if (outputId == null) { unresolved++; continue; }
            if (outputId.startsWith("minecraft:")) { skipped++; continue; }
            JsonObject json = new JsonObject();
            if ("smelting".equals(r.type)) {
                String in = resolve(r.input, namespace);
                if (in == null) { unresolved++; continue; }
                json.addProperty("type", "minecraft:smelting");
                json.add("ingredient", item(in));
                json.add("result", result(r.output, outputId));
                json.addProperty("experience", r.xp);
                json.addProperty("cookingtime", 200);
            } else {
                JsonArray arr = new JsonArray();
                boolean bad = false;
                for (JsonElement e : r.items) {
                    if (e == null || e.isJsonNull()) { arr.add(new JsonObject()); continue; }
                    if (e.isJsonObject() && e.getAsJsonObject().has("ore")) {
                        JsonObject tag = new JsonObject(); tag.addProperty("tag", oreTag(e.getAsJsonObject().get("ore").getAsString())); arr.add(tag);
                    } else {
                        String id = resolve(e.isJsonObject() ? e.getAsJsonObject() : null, namespace);
                        if (id == null) { bad = true; break; }
                        arr.add(item(id));
                    }
                }
                if (bad) { unresolved++; continue; }
                if ("shaped".equals(r.type)) {
                    json.addProperty("type", "minecraft:crafting_shaped");
                    JsonArray pattern = new JsonArray();
                    for (int y = 0; y < r.height; y++) { StringBuilder row = new StringBuilder(); for (int x = 0; x < r.width; x++) row.append((char)('A' + y * r.width + x)); pattern.add(row.toString()); }
                    json.add("pattern", pattern);
                    JsonObject key = new JsonObject(); for (int i = 0; i < arr.size(); i++) key.add(String.valueOf((char)('A' + i)), arr.get(i));
                    json.add("key", key);
                } else { json.addProperty("type", "minecraft:crafting_shapeless"); json.add("ingredients", arr); }
                json.add("result", result(r.output, outputId));
            }
            String base = LegacyIds.pathOf(outputId); if (base == null) base = "recipe_" + total;
            int n = ids.getOrDefault(base, 0) + 1; ids.put(base, n);
            String name = base + (n == 1 ? "" : "_" + n);
            root.add(name, json);
            owned++;
        }
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(root), StandardCharsets.UTF_8);
        return new Counts(total, owned, skipped, unknown, unresolved);
    }

    private static JsonObject item(String id) { JsonObject o = new JsonObject(); o.addProperty("item", id); return o; }
    /** Forge ore names have no namespace; common material families map to conventional c: tags. */
    private static String oreTag(String raw) {
        String s = raw == null ? "" : raw.trim();
        String[] families = {"ingot", "dust", "nugget", "gem", "ore", "block", "plate", "gear", "rod", "stick", "coal", "crop", "seed", "fiber", "string"};
        for (String family : families) if (s.regionMatches(true, 0, family, 0, family.length()) && s.length() > family.length())
            return "c:" + family + "s/" + LegacyIds.sanitizePath(s.substring(family.length()));
        return "c:forge_ore/" + LegacyIds.sanitizePath(s);
    }
    private static JsonObject result(JsonObject raw, String id) { JsonObject o = item(id); if (raw.has("count")) o.add("count", raw.get("count")); return o; }
    private static String resolve(JsonObject raw, String ns) {
        if (raw == null || !raw.has("item")) return null;
        String legacy = raw.get("item").getAsString(); int meta = raw.has("meta") ? raw.get("meta").getAsInt() : 0;
        Item item = Registrar.LEGACY_VARIANT_ITEMS.get(legacy + "@" + meta);
        if (item == null) item = Registrar.LEGACY_VARIANT_ITEMS.get(legacy + "@0");
        if (item == null && legacy.indexOf(':') > 0) try { item = BuiltInRegistries.ITEM.getValue(Identifier.parse(legacy)); } catch (RuntimeException ignored) { }
        return item == null ? null : String.valueOf(BuiltInRegistries.ITEM.getKey(item));
    }
}
