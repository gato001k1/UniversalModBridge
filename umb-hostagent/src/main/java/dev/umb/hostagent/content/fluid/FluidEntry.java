package dev.umb.hostagent.content.fluid;

import com.google.gson.JsonObject;

/** Pure-data representation of one Forge 1.7.10 FluidRegistry entry. */
public final class FluidEntry {
    public final String name;
    public final int density;
    public final boolean gaseous;
    public final int temperature;
    public final int viscosity;
    public final int luminosity;
    public final String iconName;
    public final String blockId;

    private FluidEntry(String name, int density, boolean gaseous, int temperature,
                       int viscosity, int luminosity, String iconName, String blockId) {
        this.name = name;
        this.density = density;
        this.gaseous = gaseous;
        this.temperature = temperature;
        this.viscosity = viscosity;
        this.luminosity = luminosity;
        this.iconName = iconName;
        this.blockId = blockId;
    }

    public static FluidEntry from(JsonObject o) {
        String name = str(o, "name");
        if (name == null) return null;
        return new FluidEntry(name, integer(o, "density", 1000), bool(o, "gaseous", false),
                integer(o, "temperature", 300), integer(o, "viscosity", 1000),
                integer(o, "luminosity", 0), str(o, "iconName"), str(o, "blockId"));
    }

    public boolean belongsTo(String namespace) {
        return blockId != null && namespace != null
                && blockId.regionMatches(true, 0, namespace, 0, namespace.length())
                && blockId.length() > namespace.length()
                && blockId.charAt(namespace.length()) == ':';
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || o.get(key).isJsonNull()) return null;
        String v = o.get(key).getAsString();
        return v == null || v.isEmpty() ? null : v;
    }
    private static int integer(JsonObject o, String key, int def) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsInt() : def; }
        catch (RuntimeException ex) { return def; }
    }
    private static boolean bool(JsonObject o, String key, boolean def) {
        try { return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsBoolean() : def; }
        catch (RuntimeException ex) { return def; }
    }
}
