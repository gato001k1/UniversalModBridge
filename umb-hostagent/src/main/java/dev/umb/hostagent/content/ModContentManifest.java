package dev.umb.hostagent.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Manifest reader for multi-mod content. Every record remains independently addressable. */
public final class ModContentManifest {
    private final List<ModContentRecord> records;

    public ModContentManifest(List<ModContentRecord> records) {
        if (records == null || records.isEmpty()) throw new IllegalArgumentException("manifest has no mods");
        Map<String, ModContentRecord> byNs = new LinkedHashMap<>();
        for (ModContentRecord r : records) {
            String ns = LegacyIds.sanitizeNamespace(r.namespace());
            if (ns == null || ns.isEmpty()) throw new IllegalArgumentException("invalid namespace: " + r.namespace());
            if (byNs.put(ns, new ModContentRecord(ns, r.snapshot(), r.lang(), r.blockShapes(),
                    r.guiProfile(), r.jar(), r.recipes(), r.era(), r.basePack())) != null)
                throw new IllegalArgumentException("duplicate manifest namespace owner: " + ns);
        }
        this.records = List.copyOf(byNs.values());
    }

    public List<ModContentRecord> records() { return records; }

    public static ModContentManifest load(Path file) throws IOException {
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Path base = file.toAbsolutePath().normalize().getParent();
            return parse(JsonParser.parseReader(r), base);
        }
    }

    public static ModContentManifest parse(JsonElement root) {
        return parse(root, null);
    }

    /**
     * Relative paths resolve against {@code base} = the manifest file's own directory (the same
     * rule ObjBridgeManifest uses), never the process cwd - the 26.2 client's cwd is its per-window
     * game dir, so cwd-relative paths silently pointed nowhere. {@code base == null} keeps paths as
     * given (unit tests with synthetic JSON).
     */
    public static ModContentManifest parse(JsonElement root, Path base) {
        if (root == null || !root.isJsonObject()) throw new IllegalArgumentException("manifest root is not an object");
        JsonElement mods = root.getAsJsonObject().get("mods");
        if (mods == null || !mods.isJsonArray()) throw new IllegalArgumentException("manifest requires mods[]");
        List<ModContentRecord> out = new ArrayList<>();
        for (JsonElement e : mods.getAsJsonArray()) {
            if (!e.isJsonObject()) throw new IllegalArgumentException("manifest mod entry is not an object");
            JsonObject o = e.getAsJsonObject();
            out.add(new ModContentRecord(req(o, "namespace"), path(o, "snapshot", true, base), langPath(o, base),
                    path(o, "blockShapes", false, base), path(o, "guiProfile", false, base), path(o, "jar", false, base),
                    path(o, "recipes", false, base), era(o), path(o, "basePack", false, base)));
        }
        return new ModContentManifest(out);
    }

    /**
     * Optional modding-era tag (added for the 1.16.5 lane). Absent or blank means the original
     * 1.7.10 universe - every pre-era record and test keeps working unchanged. Only an explicit
     * non-1.7.10 value routes anywhere else (see BridgeRouter).
     */
    private static String era(JsonObject o) {
        JsonElement e = o.get("era");
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || e.getAsString().isBlank()) {
            return "1.7.10";
        }
        return e.getAsString().trim();
    }

    private static String req(JsonObject o, String k) {        JsonElement e = o.get(k);
        if (e == null || !e.isJsonPrimitive() || e.getAsString().isBlank()) throw new IllegalArgumentException("manifest missing " + k);
        return e.getAsString();
    }
    private static Path path(JsonObject o, String k, boolean required, Path base) {
        JsonElement e = o.get(k);
        if (e == null || e.isJsonNull()) { if (required) throw new IllegalArgumentException("manifest missing " + k); return null; }
        Path p = Path.of(e.getAsString());
        return base == null || p.isAbsolute() ? p : base.resolve(p).normalize();
    }

    /**
     * Explicit "lang", else derived from "assets" as {@code <assets>/assets/<ns>/lang/en_US.lang}
     * (the path the scalar launcher always used). Without this the manifest path loaded 0 lang
     * entries and every legacy item showed its raw key (live 2026-09-23: lang entries=0).
     */
    private static Path langPath(JsonObject o, Path base) {
        Path explicit = path(o, "lang", false, base);
        if (explicit != null) return explicit;
        Path assets = path(o, "assets", false, base);
        if (assets == null) return null;
        String ns = req(o, "namespace");
        Path dir = assets.resolve("assets").resolve(ns).resolve("lang");
        for (String name : new String[] {"en_US.lang", "en_us.lang"}) {
            Path f = dir.resolve(name);
            if (Files.isRegularFile(f)) return f;
        }
        return null;
    }
}
