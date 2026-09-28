package dev.umb.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * M8-2: refmap load + fallback per mixin-internals §3.2.
 * JSON shape: { "mappings": { classRef: { reference: remapped } }, "data": { context: { classRef: { reference: remapped } } } }
 * plus bare top-level context blocks for older files. ReferenceMapper in Mixin reads both.
 * Lookup is {@code remap(classRef, reference, context)} — context defaults to "mappings" when null.
 * D4: missing refmap never guesses; {@link #EMPTY} passthrough is the DEFAULT_MAPPER equivalent.
 */
public final class Refmap {

    /** Passthrough mapper when no refmap is present (DEFAULT_MAPPER). */
    public static final Refmap EMPTY = new Refmap(Map.of(), Map.of(), RefmapStatus.ABSENT, null);

    public enum RefmapStatus { PRESENT, ABSENT, MALFORMED }

    private final Map<String, Map<String, String>> mappings; // default context "mappings" — classRef -> (reference -> remapped)
    private final Map<String, Map<String, Map<String, String>>> data; // context -> classRef -> (reference -> remapped)
    private final RefmapStatus status;
    private final String error; // nullable

    private Refmap(Map<String, Map<String, String>> mappings,
                   Map<String, Map<String, Map<String, String>>> data,
                   RefmapStatus status, String error) {
        this.mappings = mappings;
        this.data = data;
        this.status = status;
        this.error = error;
    }

    public RefmapStatus status() { return status; }
    public String error() { return error; }
    public boolean isPresent() { return status == RefmapStatus.PRESENT; }
    public boolean isAbsent() { return status == RefmapStatus.ABSENT; }
    public boolean isMalformed() { return status == RefmapStatus.MALFORMED; }

    /** Returns the remapped reference or the input unchanged if no entry exists (passthrough). */
    public String remap(String classRef, String reference) {
        return remap(classRef, reference, null);
    }

    public String remap(String classRef, String reference, String context) {
        Objects.requireNonNull(reference, "reference");
        if (classRef == null) {
            String v = mappings.getOrDefault("", Map.of()).get(reference);
            if (v != null) return v;
            // search all classRefs when caller didn't scope
            for (Map<String, String> m : mappings.values()) {
                String hit = m.get(reference);
                if (hit != null) return hit;
            }
            return reference;
        }
        // Context-specific data first when context supplied
        if (context != null) {
            Map<String, Map<String, String>> ctx = data.get(context);
            if (ctx != null) {
                Map<String, String> byClass = ctx.get(classRef);
                if (byClass != null) {
                    String hit = byClass.get(reference);
                    if (hit != null) return hit;
                }
            }
        }
        Map<String, String> byClass = mappings.get(classRef);
        if (byClass != null) {
            String hit = byClass.get(reference);
            if (hit != null) return hit;
        }
        // Fallback: search data contexts for classRef even when no explicit context (mirrors Mixin's context fallback)
        for (Map<String, Map<String, String>> ctx : data.values()) {
            Map<String, String> bc = ctx.get(classRef);
            if (bc != null) {
                String hit = bc.get(reference);
                if (hit != null) return hit;
            }
        }
        return reference;
    }

    /** Raw mappings view for diagnostics (unmodifiable). */
    public Map<String, Map<String, String>> mappingsView() { return mappings; }
    public Map<String, Map<String, Map<String, String>>> dataView() { return data; }

    /** Total number of reference->remapped entries across all contexts. */
    public int entryCount() {
        int n = 0;
        for (Map<String, String> m : mappings.values()) n += m.size();
        for (Map<String, Map<String, String>> ctx : data.values()) {
            for (Map<String, String> m : ctx.values()) n += m.size();
        }
        return n;
    }

    // ------------------------------------------------------------------ I/O

    public static Refmap loadFromJar(JarFile jar, String refmapPath) {
        if (refmapPath == null || refmapPath.isBlank()) return EMPTY;
        ZipEntry entry = jar.getEntry(refmapPath);
        if (entry == null) {
            // Try without leading slash
            String alt = refmapPath.startsWith("/") ? refmapPath.substring(1) : "/" + refmapPath;
            entry = jar.getEntry(alt);
        }
        if (entry == null) return EMPTY;
        try (InputStream in = jar.getInputStream(entry)) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(json);
        } catch (IOException e) {
            return new Refmap(Map.of(), Map.of(), RefmapStatus.MALFORMED, "I/O reading refmap " + refmapPath + ": " + e.getMessage());
        }
    }

    public static Refmap loadFromJson(String json) {
        if (json == null || json.isBlank()) return EMPTY;
        return parse(json);
    }

    public static Refmap parse(String json) {
        Objects.requireNonNull(json, "json");
        String trimmed = json.trim();
        if (trimmed.isEmpty()) return new Refmap(Map.of(), Map.of(), RefmapStatus.MALFORMED, "empty refmap JSON");
        try {
            JsonObject root = JsonParser.parseString(trimmed).getAsJsonObject();
            Map<String, Map<String, String>> mappings = new HashMap<>();
            Map<String, Map<String, Map<String, String>>> data = new HashMap<>();

            // Top-level "mappings"
            if (root.has("mappings") && root.get("mappings").isJsonObject()) {
                mappings.putAll(parseClassMap(root.getAsJsonObject("mappings")));
            }
            // Top-level "data": { context: { classRef: { ref: remapped } } }
            if (root.has("data") && root.get("data").isJsonObject()) {
                JsonObject dataObj = root.getAsJsonObject("data");
                for (Map.Entry<String, JsonElement> ctxEntry : dataObj.entrySet()) {
                    if (!ctxEntry.getValue().isJsonObject()) continue;
                    data.put(ctxEntry.getKey(), parseClassMap(ctxEntry.getValue().getAsJsonObject()));
                }
            }
            // Bare top-level context blocks (legacy): any object-valued key that is not "mappings"/"data"
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                String k = e.getKey();
                if ("mappings".equals(k) || "data".equals(k)) continue;
                if (e.getValue().isJsonObject()) {
                    JsonObject maybeClassMap = e.getValue().getAsJsonObject();
                    // Heuristic: if values are objects whose values are strings, it's a class->ref map
                    boolean looksLikeClassMap = true;
                    for (Map.Entry<String, JsonElement> ce : maybeClassMap.entrySet()) {
                        if (!ce.getValue().isJsonObject()) { looksLikeClassMap = false; break; }
                    }
                    if (looksLikeClassMap && !maybeClassMap.isEmpty()) {
                        data.put(k, parseClassMap(maybeClassMap));
                    }
                }
            }

            // Also handle flat mappings at top level with no "mappings" wrapper (rare but seen)
            if (mappings.isEmpty() && data.isEmpty()) {
                // Try to interpret root itself as classMap if it looks like one
                boolean allObjectValues = true;
                for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                    if (!e.getValue().isJsonObject()) { allObjectValues = false; break; }
                }
                if (allObjectValues && !root.isEmpty()) {
                    mappings.putAll(parseClassMap(root));
                }
            }

            RefmapStatus status = (mappings.isEmpty() && data.isEmpty())
                    ? RefmapStatus.ABSENT : RefmapStatus.PRESENT;
            // Normalize to unmodifiable copies
            Map<String, Map<String, String>> m2 = new HashMap<>();
            for (Map.Entry<String, Map<String, String>> e : mappings.entrySet()) {
                m2.put(e.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(e.getValue())));
            }
            Map<String, Map<String, Map<String, String>>> d2 = new HashMap<>();
            for (Map.Entry<String, Map<String, Map<String, String>>> e : data.entrySet()) {
                Map<String, Map<String, String>> inner = new HashMap<>();
                for (Map.Entry<String, Map<String, String>> ce : e.getValue().entrySet()) {
                    inner.put(ce.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(ce.getValue())));
                }
                d2.put(e.getKey(), Collections.unmodifiableMap(inner));
            }
            return new Refmap(Collections.unmodifiableMap(m2), Collections.unmodifiableMap(d2), status, null);
        } catch (Exception ex) {
            return new Refmap(Map.of(), Map.of(), RefmapStatus.MALFORMED, "refmap JSON malformed: " + ex.getMessage());
        }
    }

    private static Map<String, Map<String, String>> parseClassMap(JsonObject obj) {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> classEntry : obj.entrySet()) {
            String classRef = classEntry.getKey();
            if (!classEntry.getValue().isJsonObject()) continue;
            JsonObject refMap = classEntry.getValue().getAsJsonObject();
            Map<String, String> inner = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> refEntry : refMap.entrySet()) {
                JsonElement v = refEntry.getValue();
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    inner.put(refEntry.getKey(), v.getAsString());
                }
            }
            if (!inner.isEmpty()) out.put(classRef, inner);
        }
        return out;
    }

    @Override
    public String toString() {
        return "Refmap[" + status + (error != null ? " error=" + error : "") + " mappings=" + mappings.size() + " contexts=" + data.size() + " entries=" + entryCount() + "]";
    }
}
