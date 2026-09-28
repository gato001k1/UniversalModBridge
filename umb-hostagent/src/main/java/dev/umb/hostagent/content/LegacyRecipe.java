package dev.umb.hostagent.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;

/** Pure-data recipe record extracted from the isolated 1.7.10 universe. */
public final class LegacyRecipe {
    public String type;
    public String className;
    public int width;
    public int height;
    public final List<JsonElement> items = new ArrayList<>();
    public JsonObject output;
    public JsonObject input;
    public float xp;

    static LegacyRecipe crafting(JsonObject o) {
        LegacyRecipe r = new LegacyRecipe();
        r.type = LegacySnapshot.str(o, "type");
        r.className = LegacySnapshot.str(o, "className");
        r.width = LegacySnapshot.i(o, "width", 0);
        r.height = LegacySnapshot.i(o, "height", 0);
        for (JsonElement e : LegacySnapshot.arr(o, "items")) r.items.add(e);
        JsonElement out = o.get("output");
        r.output = out != null && out.isJsonObject() ? out.getAsJsonObject() : null;
        return r;
    }

    static LegacyRecipe smelting(JsonObject o) {
        LegacyRecipe r = new LegacyRecipe();
        r.type = "smelting";
        JsonElement in = o.get("input"), out = o.get("output");
        r.input = in != null && in.isJsonObject() ? in.getAsJsonObject() : null;
        r.output = out != null && out.isJsonObject() ? out.getAsJsonObject() : null;
        JsonElement xp = o.get("xp");
        if (xp != null && xp.isJsonPrimitive()) try { r.xp = xp.getAsFloat(); } catch (RuntimeException ignored) { }
        return r;
    }
}
