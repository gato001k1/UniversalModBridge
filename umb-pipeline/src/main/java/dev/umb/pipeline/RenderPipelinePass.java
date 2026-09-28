package dev.umb.pipeline;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import dev.umb.core.ModAnalysis;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

/**
 * M10: rendering pipeline -- bake model JSON remap, texture namespace translation,
 * host atlas stitching hook.
 *
 * <p>Reads non-class entries under {@code assets/[ns]/blockstates} and
 * {@code assets/[ns]/models (recursive)}, parses with Gson 2.11.0, and rewrites
 * resource locations that denote models / parents / textures. texture
 * references undergo legacy->modern path normalisation ({@code blocks/->block/},
 * {@code items/->item/}) and keep their namespace otherwise. references whose
 * backing file is absent from the jar are NOT invented -- they surface as
 * {@code Evidence} / {@code Diagnostic} per D4, and the original JSON value is
 * kept. Malformed JSON is tolerated per S131: copy verbatim + warn. The input
 * jar is never mutated (S24); output is written via {@link JarOutputStream}.
 *
 * <p>Host atlas stitching hook: every collected texture resource location is
 * recorded into a synthetic entry {@code META-INF/umb/atlas/<modId>.json}
 * (stitch hint for the host's {@code blocks} atlas -- 26.2 merges beds/signs
 * into blocks). The hook is host-neutral JSON, never a class, and is emitted
 * only when at least one texture reference was observed.
 *
 * <p>Graph-aware constructor is reserved for verticals where registry renames
 * (block/item ids) are bridged through {@link dev.umb.mappings.MappingGraph};
 * v0 keeps mod-owned namespaces intact and vanilla references verbatim, which is
 * the honest D4 behaviour until a mapping for registry names is available.
 */
public final class RenderPipelinePass implements TranslationPass {

    public static final String PASS_ID = "M10-render";
    public static final String EVIDENCE_KIND_ASSET = "render-asset";
    public static final String EVIDENCE_KIND_ATLAS = "render-atlas";
    public static final String ATLAS_HOOK_PREFIX = "META-INF/umb/atlas/";

    @Override
    public String id() { return PASS_ID; }

    @Override
    public PassReport run(ModAnalysis analysis, Path input, Path output) throws Exception {
        if (analysis == null) throw new NullPointerException("analysis");
        if (input == null || !Files.isRegularFile(input)) {
            PassReport r = new PassReport(PASS_ID, PassReport.Status.FAIL);
            r.diag(new PassReport.Diagnostic("MISSING_INPUT", "input jar not found: " + input,
                    analysis.modId(), analysis.sourceMcVersion().orElse("(unknown)"),
                    "render-pipeline", "RenderPipelinePass", "Ensure IN.jar exists"));
            return r;
        }
        if (output == null) throw new NullPointerException("output");
        Path outParent = output.toAbsolutePath().getParent();
        if (outParent != null) Files.createDirectories(outParent);

        String modId = analysis.modId() != null ? analysis.modId() : "unknown";

        // Collect all entry bytes first so we can probe existence for D4.
        Map<String, byte[]> allEntries = new LinkedHashMap<>();
        List<String> entryOrder = new ArrayList<>();
        try (JarFile jf = new JarFile(input.toFile())) {
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry je = en.nextElement();
                if (je.isDirectory()) continue;
                try (InputStream in = jf.getInputStream(je)) {
                    byte[] b = in.readAllBytes();
                    allEntries.put(je.getName(), b);
                    entryOrder.add(je.getName());
                }
            }
        }

        // Empty jar (no classfiles) is still allowed through render pass; we only
        // process assets. If there are zero assets at all we will still emit identity.
        Set<String> jarPaths = new HashSet<>(allEntries.keySet());
        // Synthetic texture file existence: also consider png entries.
        Set<String> pngEntries = new HashSet<>();
        for (String p : jarPaths) if (p.endsWith(".png")) pngEntries.add(p);

        List<ModAnalysis.Evidence> evidence = new ArrayList<>();
        List<String> collectedTextures = new ArrayList<>();
        Set<String> seenTextures = new HashSet<>();

        // Output entry map: preserve order, but allow JSON rewrites and legacy
        // texture path renames. We build a new ordered map for writing.
        Map<String, byte[]> outEntries = new LinkedHashMap<>();
        int blockstateCount = 0;
        int modelCount = 0;
        int rewrittenJsonCount = 0;
        int atlasTextures = 0;

        for (String name : entryOrder) {
            byte[] data = allEntries.get(name);
            boolean isBlockstate = isBlockstateJson(name);
            boolean isModel = isModelJson(name);
            if ((isBlockstate || isModel)) {
                if (isBlockstate) blockstateCount++;
                else modelCount++;
                String text = new String(data, StandardCharsets.UTF_8);
                JsonElement root;
                try {
                    root = JsonParser.parseString(text);
                } catch (JsonSyntaxException | IllegalStateException e) {
                    // S131: untrusted input -- keep verbatim, warn, evidence.
                    evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND_ASSET,
                            "unparseable json kept verbatim: " + name + " (" + e.getMessage() + ")", 0.95));
                    outEntries.put(name, data);
                    continue;
                }
                // Walk and potentially rewrite. Track if we changed anything.
                boolean[] dirty = new boolean[]{false};
                walkAndRemap(root, modId, jarPaths, pngEntries, evidence, collectedTextures, seenTextures, dirty, name);
                byte[] outBytes;
                if (dirty[0]) {
                    com.google.gson.Gson gson = new com.google.gson.GsonBuilder().disableHtmlEscaping().create();
                    String rewritten = gson.toJson(root);
                    outBytes = rewritten.getBytes(StandardCharsets.UTF_8);
                    rewrittenJsonCount++;
                } else {
                    // Keep original bytes to preserve formatting when untouched
                    outBytes = data;
                }
                outEntries.put(name, outBytes);
            } else if (isLegacyTextureEntry(name)) {
                // Texture namespace translation on entry path: blocks/->block/, items/->item/
                String translated = translateTextureEntryPath(name);
                if (!translated.equals(name)) {
                    evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND_ASSET,
                            "texture entry path translated: " + name + " -> " + translated, 0.7));
                }
                // If translated collides with existing output key, keep first-win (original)
                outEntries.putIfAbsent(translated, data);
                // Also keep original name if it differs? No -- we translate, not duplicate.
                // If collision, preserve both by keeping original as alias? Keep only translated for host.
                if (!translated.equals(name) && allEntries.containsKey(translated)) {
                    // Collision: host already has modern path -- keep existing, drop legacy alias
                }
            } else {
                outEntries.put(name, data);
            }
        }

        // Host atlas stitching hook: emit synthetic JSON when we saw textures.
        if (!collectedTextures.isEmpty()) {
            String atlasPath = ATLAS_HOOK_PREFIX + sanitizeFileName(modId) + ".json";
            JsonObject atlas = new JsonObject();
            atlas.addProperty("parent_atlas", "minecraft:blocks");
            atlas.addProperty("mod_id", modId);
            atlas.addProperty("source", PASS_ID);
            // 26.2 merges beds/signs into blocks -- note for host
            atlas.addProperty("note", "26.2 blocks atlas (beds/signs merged)");
            JsonArray arr = new JsonArray();
            // Deterministic order
            List<String> sorted = new ArrayList<>(new HashSet<>(collectedTextures));
            sorted.sort(String::compareTo);
            for (String t : sorted) arr.add(t);
            atlas.add("textures", arr);
            com.google.gson.Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
            byte[] atlasBytes = gson.toJson(atlas).getBytes(StandardCharsets.UTF_8);
            outEntries.put(atlasPath, atlasBytes);
            atlasTextures = sorted.size();
            evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND_ATLAS,
                    "atlas hook emitted: " + atlasPath + " with " + sorted.size() + " texture(s) for blocks atlas", 0.85));
        }

        // Write output jar (S24: original never mutated)
        Files.createDirectories(output.getParent() != null ? output.getParent() : Path.of("."));
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(output))) {
            for (Map.Entry<String, byte[]> e : outEntries.entrySet()) {
                JarEntry je = new JarEntry(e.getKey());
                je.setTime(System.currentTimeMillis());
                out.putNextEntry(je);
                out.write(e.getValue());
                out.closeEntry();
            }
        }

        // Build report per S115
        boolean hasAssetEvidence = evidence.stream().anyMatch(ev -> ev.kind().equals(EVIDENCE_KIND_ASSET));
        PassReport.Status status;
        if (blockstateCount == 0 && modelCount == 0 && collectedTextures.isEmpty()) {
            status = PassReport.Status.SKIPPED;
        } else if (hasAssetEvidence) {
            status = PassReport.Status.WARN;
        } else {
            status = PassReport.Status.OK;
        }
        PassReport report = new PassReport(PASS_ID, status);
        report.note("blockstates: " + blockstateCount + " models: " + modelCount
                + " rewrittenJson: " + rewrittenJsonCount
                + " textures: " + collectedTextures.size() + " atlasTextures: " + atlasTextures);
        if (!collectedTextures.isEmpty()) {
            report.note("atlas hook: " + ATLAS_HOOK_PREFIX + sanitizeFileName(modId) + ".json -> minecraft:blocks");
        }
        for (ModAnalysis.Evidence ev : evidence) {
            report.note("evidence[" + ev.kind() + "] " + ev.detail());
            String code = ev.kind().equals(EVIDENCE_KIND_ATLAS) ? "RENDER_ATLAS"
                    : ev.detail().contains("unresolvable") ? "RENDER_UNRESOLVABLE"
                    : ev.detail().contains("unparseable") ? "RENDER_MALFORMED_JSON"
                    : "RENDER_ASSET";
            boolean isUnresolvable = ev.detail().contains("unresolvable");
            report.diag(new PassReport.Diagnostic(code, ev.detail(),
                    modId, analysis.sourceMcVersion().orElse("(unknown)"),
                    "render-pipeline", "RenderPipelinePass",
                    isUnresolvable ? "Add missing model/texture or verify registry rename mapping; reference kept verbatim per D4"
                            : "Verify JSON / atlas hook; texture path kept per D4 if unmapped"));
        }
        // Even when WARN, diagnostics ride the report; TranslateCommand will decide cache policy.
        return report;
    }

    // ------------------------------------------------------------------ JSON walk

    private static void walkAndRemap(JsonElement el, String modId, Set<String> jarPaths,
                                     Set<String> pngEntries, List<ModAnalysis.Evidence> evidence,
                                     List<String> collectedTextures, Set<String> seenTextures,
                                     boolean[] dirty, String jsonPath) {
        if (el.isJsonObject()) {
            JsonObject obj = el.getAsJsonObject();
            // Collect keys to avoid concurrent modification
            List<String> keys = new ArrayList<>(obj.keySet());
            for (String k : keys) {
                JsonElement v = obj.get(k);
                if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isString()) {
                    String s = v.getAsString();
                    // Heuristic: resource locations appear under keys model/parent/textures/* etc.
                    boolean isModelKey = k.equals("model") || k.equals("parent");
                    // For top-level model/parent strings:
                    if (isModelKey && looksLikeResourceLocation(s)) {
                        String rewritten = translateModelRef(s, modId);
                        if (!rewritten.equals(s)) {
                            obj.addProperty(k, rewritten);
                            dirty[0] = true;
                        }
                        // D4: check backing file existence for model refs
                        if (!isResolvableModelRef(rewritten, jarPaths)) {
                            evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND_ASSET,
                                    "unresolvable model ref '" + s + "'"
                                            + (rewritten.equals(s) ? "" : " -> '" + rewritten + "'")
                                            + " in " + jsonPath + " (no backing json in jar; kept verbatim per D4)", 0.9));
                        }
                    } else if (looksLikeResourceLocation(s) && isPotentialTextureRef(k, obj)) {
                        // Texture value inside textures map -- handled below via textures map walk
                        // Fall through to generic handling
                        walkAndRemap(v, modId, jarPaths, pngEntries, evidence, collectedTextures, seenTextures, dirty, jsonPath);
                    } else {
                        // Recurse
                        walkAndRemap(v, modId, jarPaths, pngEntries, evidence, collectedTextures, seenTextures, dirty, jsonPath);
                    }
                } else if (v.isJsonObject() && k.equals("textures")) {
                    // Textures map: every string value is a texture resource location
                    JsonObject texMap = v.getAsJsonObject();
                    List<String> texKeys = new ArrayList<>(texMap.keySet());
                    for (String tk : texKeys) {
                        JsonElement tv = texMap.get(tk);
                        if (tv.isJsonPrimitive() && tv.getAsJsonPrimitive().isString()) {
                            String tex = tv.getAsString();
                            if (looksLikeResourceLocation(tex) || tex.contains("/")) {
                                String rewritten = translateTextureRef(tex, modId);
                                if (!rewritten.equals(tex)) {
                                    texMap.addProperty(tk, rewritten);
                                    dirty[0] = true;
                                }
                                // Collect for atlas hook (dedup)
                                String normalized = rewritten;
                                if (seenTextures.add(normalized)) {
                                    collectedTextures.add(normalized);
                                }
                                // D4: check backing png existence
                                if (!isResolvableTextureRef(rewritten, jarPaths, pngEntries)) {
                                    evidence.add(new ModAnalysis.Evidence(EVIDENCE_KIND_ASSET,
                                            "unresolvable texture ref '" + tex + "'"
                                                    + (rewritten.equals(tex) ? "" : " -> '" + rewritten + "'")
                                                    + " in " + jsonPath + " (no backing png in jar; kept verbatim per D4)", 0.8));
                                }
                            }
                        } else {
                            walkAndRemap(tv, modId, jarPaths, pngEntries, evidence, collectedTextures, seenTextures, dirty, jsonPath);
                        }
                    }
                    // Walk remaining non-texture children already handled
                    for (String tk : texKeys) {
                        JsonElement tv = texMap.get(tk);
                        if (!tv.isJsonPrimitive()) {
                            walkAndRemap(tv, modId, jarPaths, pngEntries, evidence, collectedTextures, seenTextures, dirty, jsonPath);
                        }
                    }
                } else {
                    walkAndRemap(v, modId, jarPaths, pngEntries, evidence, collectedTextures, seenTextures, dirty, jsonPath);
                }
            }
        } else if (el.isJsonArray()) {
            JsonArray arr = el.getAsJsonArray();
            for (JsonElement child : arr) {
                walkAndRemap(child, modId, jarPaths, pngEntries, evidence, collectedTextures, seenTextures, dirty, jsonPath);
            }
        }
    }

    private static boolean isPotentialTextureRef(String key, JsonObject parent) {
        // Used only for generic walk -- textures map handled explicitly above
        return false;
    }

    // ------------------------------------------------------------------ path helpers

    static boolean isBlockstateJson(String name) {
        return name.startsWith("assets/") && name.contains("/blockstates/") && name.endsWith(".json");
    }

    static boolean isModelJson(String name) {
        return name.startsWith("assets/") && name.contains("/models/") && name.endsWith(".json");
    }

    static boolean isLegacyTextureEntry(String name) {
        return name.startsWith("assets/") && name.contains("/textures/") && name.endsWith(".png")
                && (name.contains("/textures/blocks/") || name.contains("/textures/items/"));
    }

    static String translateTextureEntryPath(String name) {
        // blocks/ -> block/, items/ -> item/
        String r = name.replace("/textures/blocks/", "/textures/block/");
        r = r.replace("/textures/items/", "/textures/item/");
        return r;
    }

    static boolean looksLikeResourceLocation(String s) {
        if (s == null || s.isBlank()) return false;
        // Resource location: [namespace:]path, path contains / or alphanumeric
        // Reject obvious non-refs (pure numbers, etc.)
        if (s.contains(" ") || s.contains("\"")) return false;
        // Contains : or / is strong signal; bare path like "block/cube_all" also qualifies
        return s.contains(":") || s.contains("/");
    }

    static String translateModelRef(String ref, String modId) {
        // Model refs: normalize texture path segments blocks/->block/ if present
        // Keep namespace intact (D4: never invent). Only normalize path segment.
        String[] parts = splitResourceLocation(ref);
        String ns = parts[0];
        String path = parts[1];
        String newPath = path.replace("blocks/", "block/").replace("items/", "item/");
        if (newPath.equals(path)) return ref;
        return ns + ":" + newPath;
    }

    static String translateTextureRef(String ref, String modId) {
        String[] parts = splitResourceLocation(ref);
        String ns = parts[0];
        String path = parts[1];
        // Normalize blocks/items -> block/item for 26.2 host
        String newPath = path.replace("blocks/", "block/").replace("items/", "item/");
        if (newPath.equals(path)) return ref;
        // If ref had no explicit namespace, preserve bare form? But we split with default minecraft
        // If original had no ":", keep bare.
        if (!ref.contains(":")) return newPath;
        return ns + ":" + newPath;
    }

    private static String[] splitResourceLocation(String ref) {
        int colon = ref.indexOf(':');
        if (colon >= 0) {
            String ns = ref.substring(0, colon);
            String path = ref.substring(colon + 1);
            if (ns.isEmpty()) ns = "minecraft";
            return new String[]{ns, path};
        } else {
            // Bare path implies minecraft namespace per modern spec, but keep as minecraft for resolution
            return new String[]{"minecraft", ref};
        }
    }

    private static boolean isResolvableModelRef(String ref, Set<String> jarPaths) {
        String[] parts = splitResourceLocation(ref);
        String ns = parts[0];
        String path = parts[1];
        // Model file is assets/<ns>/models/<path>.json OR assets/<ns>/models/block/<path>.json etc.
        // ref already includes subpath like "block/foo" or "item/foo"
        String candidate = "assets/" + ns + "/models/" + path + ".json";
        if (jarPaths.contains(candidate)) return true;
        // Also try without namespace prefix duplication?
        // Vanilla minecraft models are host-provided, assume resolvable
        if (ns.equals("minecraft")) return true;
        return false;
    }

    private static boolean isResolvableTextureRef(String ref, Set<String> jarPaths, Set<String> pngEntries) {
        String[] parts = splitResourceLocation(ref);
        String ns = parts[0];
        String path = parts[1];
        String candidate = "assets/" + ns + "/textures/" + path + ".png";
        if (jarPaths.contains(candidate) || pngEntries.contains(candidate)) return true;
        // Also try legacy blocks/ variant
        String alt = candidate.replace("/textures/block/", "/textures/blocks/");
        if (jarPaths.contains(alt)) return true;
        alt = candidate.replace("/textures/item/", "/textures/items/");
        if (jarPaths.contains(alt)) return true;
        if (ns.equals("minecraft")) return true; // host-provided
        return false;
    }

    private static String sanitizeFileName(String s) {
        return s.replaceAll("[^a-zA-Z0-9_.-]", "_");
    }
}
