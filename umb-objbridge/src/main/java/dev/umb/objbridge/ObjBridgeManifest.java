package dev.umb.objbridge;

import com.google.gson.*;
import dev.umb.objbridge.map.RenderMap;
import dev.umb.objbridge.transform.RendererTransforms;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable manifest records and the validated, per-mod OBJ inputs. */
public final class ObjBridgeManifest {
    public record Mod(String namespace, Path renderMapPath, Path assetsRoot, Path transformsPath,
                      Path snapshotPath, Path basePackPath, Path objPackPath) { }
    public record Loaded(Mod mod, RenderMap renderMap, RendererTransforms transforms) { }

    private static final Map<String, RenderMap> MAP_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, RendererTransforms> TRANSFORM_CACHE = new ConcurrentHashMap<>();

    private ObjBridgeManifest() { }

    public static List<Loaded> load(Path manifest) throws IOException {
        if (manifest == null) throw new IllegalArgumentException("manifest path is null");
        JsonObject root = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonElement mods = root.get("mods");
        if (mods == null || !mods.isJsonArray() || mods.getAsJsonArray().isEmpty())
            throw new IllegalArgumentException("manifest " + manifest + " must contain a non-empty mods[]");
        Path base = manifest.toAbsolutePath().normalize().getParent();
        Set<String> namespaces = new LinkedHashSet<>();
        Map<String, String> rowOwners = new LinkedHashMap<>();
        List<Loaded> out = new ArrayList<>();
        for (JsonElement element : mods.getAsJsonArray()) {
            if (!element.isJsonObject()) throw new IllegalArgumentException("manifest mods[] entry is not an object");
            JsonObject o = element.getAsJsonObject();
            String ns = required(o, "namespace");
            // Some 1.7.10 mods (notably Railcraft) registered a mixed-case legacy mod id.
            // Preserve that spelling for row ownership and runtime lookup; modern lowercase
            // resource-path restrictions do not apply to the legacy registry id here.
            if (!ns.matches("[A-Za-z0-9_.-]+")) throw new IllegalArgumentException("invalid namespace: " + ns);
            if (!namespaces.add(ns)) throw new IllegalArgumentException("duplicate manifest namespace: " + ns);
            // Entries for another era (e.g. "era":"1.16.5") are owned by that era's bridge
            // (BridgeRouter); objbridge only consumes 1.7.10 render-map data. Skip them visibly.
            JsonElement era = o.get("era");
            if (era != null && era.isJsonPrimitive() && !"1.7.10".equals(era.getAsString())) {
                System.out.println("[UMB-OBJBRIDGE] manifest mod " + ns + " era=" + era.getAsString()
                        + " is not a 1.7.10 render-map mod; skipped by objbridge");
                continue;
            }
            Path map = resolve(base, required(o, "rendermap"));
            Path assets = resolve(base, required(o, "assets"));
            Path transforms = optionalPath(base, o, "transforms");
            // The manifest schema permits transforms to be omitted when the render-map directory
            // carries the standard sidecar.  Keep scalar and manifest launches equivalent: a
            // sibling renderer-transforms.json is the canonical default produced by rendermap.
            if (transforms == null) {
                Path sibling = map.resolveSibling("renderer-transforms.json");
                if (Files.isRegularFile(sibling)) transforms = sibling;
            }
            final Path resolvedTransforms = transforms;
            Mod mod = new Mod(ns, map, assets, resolvedTransforms, optionalPath(base, o, "snapshot"),
                    optionalPath(base, o, "basePack"), optionalPath(base, o, "objPack"));
            String mapKey = ns + "\u0000" + map.toAbsolutePath().normalize();
            RenderMap rm = MAP_CACHE.computeIfAbsent(mapKey, k -> readMap(map));
            String txKey = ns + "\u0000" + String.valueOf(resolvedTransforms == null ? "" : resolvedTransforms.toAbsolutePath().normalize());
            RendererTransforms rt = TRANSFORM_CACHE.computeIfAbsent(txKey, k -> readTransforms(resolvedTransforms));
            validateRows(ns, rm, rowOwners, map);
            out.add(new Loaded(mod, rm, rt));
        }
        return List.copyOf(out);
    }

    private static String required(JsonObject o, String key) {
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive() || e.getAsString().isBlank())
            throw new IllegalArgumentException("manifest mod missing required field: " + key);
        return e.getAsString();
    }

    private static Path resolve(Path base, String value) {
        Path p = Path.of(value);
        return (p.isAbsolute() ? p : base.resolve(p)).normalize();
    }

    private static Path optionalPath(Path base, JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || e.getAsString().isBlank() ? null : resolve(base, e.getAsString());
    }

    private static RenderMap readMap(Path p) {
        try { return RenderMap.read(p); }
        catch (IOException e) { throw new IllegalArgumentException("cannot read render map " + p, e); }
    }

    private static RendererTransforms readTransforms(Path p) {
        if (p == null || !Files.isRegularFile(p)) return RendererTransforms.of(new JsonObject());
        try { return RendererTransforms.read(p); }
        catch (IOException e) { throw new IllegalArgumentException("cannot read transforms " + p, e); }
    }

    private static void validateRows(String owner, RenderMap map, Map<String, String> owners, Path source) {
        for (String id : rowIds(map)) {
            // Vanilla rows (minecraft:*) are references every extracted render map carries for the
            // vanilla blocks a mod touches; the scalar path already skips them at splice time
            // ("not vanilla" test), so they are neither owned nor a cross-mod collision.
            if (id != null && id.startsWith("minecraft:")) continue;
            if (id == null || !id.startsWith(owner + ":")) {
                String previous = owners.get(id);
                throw new IllegalArgumentException("render-map row " + id + " in " + source
                        + " is not owned by manifest namespace " + owner
                        + (previous == null ? "" : " (already owned by " + previous + ")"));
            }
            String previous = owners.putIfAbsent(id, owner);
            if (previous != null && !previous.equals(owner))
                throw new IllegalArgumentException("duplicate render-map row id " + id + " owned by " + previous + " and " + owner);
        }
    }

    private static Set<String> rowIds(RenderMap map) {
        Set<String> ids = new LinkedHashSet<>();
        map.items().forEach(r -> ids.add(r.id()));
        map.blocks().forEach(r -> ids.add(r.id()));
        map.tileEntities().forEach(r -> ids.addAll(r.blockIds()));
        return ids;
    }
}
