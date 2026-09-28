package dev.umb.hostagent.automation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/** Pure line protocol helpers; one request and one response per UTF-8 line. */
public final class AutomationProtocol {
    private AutomationProtocol() {}
    public static JsonObject parse(String line) {
        return JsonParser.parseString(line).getAsJsonObject();
    }
    public static JsonObject ok(Object id, com.google.gson.JsonElement result) {
        JsonObject o = new JsonObject();
        if (id instanceof Number n) o.addProperty("id", n);
        else o.addProperty("id", String.valueOf(id));
        o.addProperty("ok", true); o.add("result", result); return o;
    }
    public static JsonObject error(Object id, String message) {
        JsonObject o = new JsonObject();
        if (id instanceof Number n) o.addProperty("id", n);
        else o.addProperty("id", String.valueOf(id));
        o.addProperty("ok", false); o.addProperty("error", message); return o;
    }
}
