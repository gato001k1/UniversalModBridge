package dev.umb.objbridge.gen;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.umb.objbridge.map.RenderMap;
import dev.umb.objbridge.ObjBridgeManifest;
import dev.umb.objbridge.map.TexturePick;
import dev.umb.objbridge.obj.ObjMesh;
import dev.umb.objbridge.transform.ItemPerspectiveRatio;
import dev.umb.objbridge.transform.RendererTransforms;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 *
 * <pre>
 * java dev.umb.objbridge.gen.ObjPackGen &lt;render-map.json&gt; &lt;snapshot.json&gt; &lt;assetsRoot&gt; &lt;outDir&gt;
 *          [--ns hbm] [--report &lt;file.md&gt;] [--block-list &lt;file.json&gt;]
 * </pre>
 *
 * <p>It writes exactly three kinds of thing and nothing else:
 * <ol>
 *   <li>{@code pack.mcmeta} - same shape/format numbers as the v0 pack ({@code min_format} /
 *       {@code max_format} 88, from the client jar's {@code version.json}
 *       {@code pack_version.resource_major}),</li>
 *   <li>{@code assets/minecraft/atlases/blocks.json} with ONE extra
 *       {@code minecraft:directory} source, {@code prefix "models/"} / {@code source "models"}. Atlas
 *       pack's copy is concatenated - this ADDS a source instead of replacing vanilla's,</li>
 *   <li>{@code assets/&lt;ns&gt;/textures/models/**.png} (the chosen textures, copied to lowercased
 *       paths because {@code Identifier} paths only allow {@code [a-z0-9/._-]}) and
 *       {@code assets/&lt;ns&gt;/items/&lt;path&gt;.json} client-item definitions of type
 *       {@code umb:obj}, which OVERRIDE the base pack's flat {@code minecraft:model} definitions when
 *       this pack sits above it.</li>
 * </ol>
 */
public final class ObjPackGen {

    private static final int PACK_FORMAT = 88;

    private ObjPackGen() { }

    /** One emitted item definition. {@code rendererClass} is null when the row named none (most plain
     *  items do), in which case {@link #oversized} always answers {@code false}. */
    private record Def(String legacyId, String packPath, String objPath, String spriteId,
                       String reason, int triangles, int groups, boolean viaBlock, String rendererClass) { }

    /**
     * no recoverable OBJ geometry (a hand-coded Java model, for one concrete example - MCHeli's
     * weapon renderers build their mesh from Java calls, not an {@code .obj} file). {@code umb:obj}
     * cannot describe these at all (see the {@code "no-obj"} skip below); they still get a
     * {@code "type":"umb:held"} wrapper around the base pack's own flat model so
     * {@code HeldItemModel}'s live-renderer check still runs for them at draw time.
     */
    private record HeldOnlyRow(String legacyId, String packPath) { }

    /** One block that the agent should splice at runtime. */
    private record BlockEntry(String blockId, String objPath, String spriteId, String reason,
                              int triangles) { }

    /** Above this derived vanilla-relative GUI scale, the item def is marked {@code oversized_in_gui}
     *  (see {@link #itemDef}) so 26.2's own picture-in-picture item renderer draws it past the 16x16
     *  slot instead of it being silently squashed to fit or clipped - see {@code ObjTransforms}'s class
     *  "already fills a full block-unit at its longest axis," clearly past what a 16x16 icon can show at
     *  the vanilla default of 0.625 without help. */
    static final float OVERSIZED_GUI_SCALE_THRESHOLD = 1.0f;

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "--manifest".equals(args[0])) {
            List<ObjBridgeManifest.Loaded> mods = ObjBridgeManifest.load(Paths.get(args[1]));
            for (ObjBridgeManifest.Loaded loaded : mods) {
                ObjBridgeManifest.Mod m = loaded.mod();
                if (m.snapshotPath() == null || m.objPackPath() == null)
                    throw new IllegalArgumentException("manifest mod " + m.namespace() + " requires snapshot and objPack for ObjPackGen");
                List<String> one = new ArrayList<>(List.of(m.renderMapPath().toString(), m.snapshotPath().toString(),
                        m.assetsRoot().toString(), m.objPackPath().toString(), "--ns", m.namespace()));
                if (m.transformsPath() != null) { one.add("--transforms"); one.add(m.transformsPath().toString()); }
                if (m.basePackPath() != null) { one.add("--base-pack-dir"); one.add(m.basePackPath().toString()); }
                main(one.toArray(String[]::new));
            }
            return;
        }
        if (args.length < 4) {
            System.err.println("usage: ObjPackGen <render-map.json> <snapshot.json> <assetsRoot> "
                    + "<outDir> [--ns <namespace>] [--report file.md] [--block-list file.json] "
                    + "[--transforms renderer-transforms.json] [--base-pack-dir dir]");
            System.exit(2);
        }
        Path mapFile = Paths.get(args[0]);
        Path snapFile = Paths.get(args[1]);
        Path assetsRoot = Paths.get(args[2]);
        Path outDir = Paths.get(args[3]);
        // Explicit --ns always wins; when omitted this used to hardcode "hbm" - a silent default
        // that would make ObjPackGen quietly namespace-filter EVERY row out on any other mod's run
        // (every id check below is `id.startsWith(ns + ":")`). `nsArg == null` here means "not given
        // on the command line"; the real default is derived from the render map's own data below,
        // once it is loaded, instead of one mod's literal namespace.
        String nsArg = null;
        Path report = null, blockList = null, basePackDirArg = null;
        Path transformsFile = Paths.get("research/out/legacy/rendermap/renderer-transforms.json");
        for (int i = 4; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--ns" -> nsArg = args[++i];
                case "--report" -> report = Paths.get(args[++i]);
                case "--block-list" -> blockList = Paths.get(args[++i]);
                case "--transforms" -> transformsFile = Paths.get(args[++i]);
                case "--base-pack-dir" -> basePackDirArg = Paths.get(args[++i]);
                default -> { }
            }
        }
        if (report == null) report = outDir.resolveSibling("").resolve("objpackgen-report.md");

        RenderMap map = RenderMap.read(mapFile);
        Snapshot snap = Snapshot.read(snapFile);
        String ns = nsArg != null ? nsArg : dominantNamespace(map);
        if (nsArg == null) {
            System.out.println("--ns not given; derived namespace \"" + ns
                    + "\" from the render map's own ids (majority non-minecraft namespace) - "
                    + "pass --ns explicitly to avoid guessing");
        }
        // Missing/unreadable transforms file degrades to "no per-item data", same convention as
        // ObjBridge.rendererTransforms(): every def just falls back to oversized=false, never fatal.
        RendererTransforms transforms;
        if (Files.isRegularFile(transformsFile)) {
            transforms = RendererTransforms.read(transformsFile);
        } else {
            System.out.println("renderer transforms missing (" + transformsFile + "); no def will be marked oversized_in_gui");
            transforms = RendererTransforms.of(new JsonObject());
        }
        System.out.println("render map: items=" + map.items().size() + " blocks=" + map.blocks().size()
                + " tileEntities=" + map.tileEntities().size());
        System.out.println("snapshot  : items=" + snap.itemIds.size() + " blocks=" + snap.blockIds.size());

        // ------------------------------------------------------------ pick per row
        List<Def> defs = new ArrayList<>();
        List<HeldOnlyRow> heldOnlyRows = new ArrayList<>();
        Map<String, RenderMap.Asset> texturesToCopy = new LinkedHashMap<>();   // spriteId -> asset
        Map<String, Integer> reasonCounts = new TreeMap<>();
        Map<String, Integer> skipCounts = new TreeMap<>();
        List<String> skipDetail = new ArrayList<>();
        Set<String> emittedPaths = new LinkedHashSet<>();
        Map<String, Integer> triCache = new LinkedHashMap<>();

        for (RenderMap.ItemRow row : map.items()) {
            String id = row.id();
            if (id == null || !id.startsWith(ns + ":")) { bump(skipCounts, "wrong-namespace"); continue; }
            if (!snap.itemIds.contains(id)) { bump(skipCounts, "not-in-snapshot"); continue; }
            TexturePick.Pick pick = TexturePick.choose(row.models(), row.textures(), assetsRoot);
            if (pick.model() == null) {
                bump(skipCounts, "no-obj");
                if (skipDetail.size() < 40) skipDetail.add(id + " : no OBJ in models[] (" + row.models().size() + " entries)");
                // WAS detected for this item (row.rendererClass() - the same generic signal
                // #oversized already uses) - HeldItemModel's live capture path still applies to
                // it at draw time, so wrap the base pack's flat model instead of leaving it as a
                // permanent placeholder. A row with no detected renderer class at all is a plain
                // vanilla-shaped item with nothing to gain from the wrapper, so it is left alone.
                if (row.rendererClass() != null) {
                    String heldPath = TexturePick.sanitize(bare(id));
                    if (!heldPath.isEmpty()) heldOnlyRows.add(new HeldOnlyRow(id, heldPath));
                }
                continue;
            }
            if (pick.texture() == null) {
                bump(skipCounts, "no-texture");
                if (skipDetail.size() < 40) skipDetail.add(id + " : OBJ " + pick.model().path() + " but no usable texture (" + row.textures().size() + " listed, all effect/none)");
                continue;
            }
            String packPath = TexturePick.sanitize(bare(id));
            if (packPath.isEmpty()) { bump(skipCounts, "unusable-id"); continue; }
            String sprite = pick.spriteId();
            int tris = triangles(triCache, assetsRoot, pick.model());
            if (tris <= 0) {
                bump(skipCounts, "obj-unparseable");
                if (skipDetail.size() < 40) skipDetail.add(id + " : " + pick.model().path() + " parsed to 0 triangles");
                continue;
            }
            if (!emittedPaths.add(packPath)) { bump(skipCounts, "duplicate-pack-path"); continue; }
            texturesToCopy.putIfAbsent(sprite, pick.texture());
            bump(reasonCounts, pick.reason().name());
            defs.add(new Def(id, packPath, pick.model().path(), sprite, pick.reason().name(), tris,
                    row.groups().size(), snap.blockIds.contains(id), row.rendererClass()));
        }

        // renderer class for these at all (see RenderMap.UnattributedItemRow's own javadoc:
        // field-tracing cannot follow an item constructed inside a loop over a data-driven config
        // table, a common pattern for a mod with many similar item variants - MCHeli's own
        // light-weapon items, e.g. fim92/fgm148, are exactly this shape and were otherwise
        // invisible to ObjPackGen entirely). Wrapping them too is SAFE even though the census
        // runtime check, so an item that genuinely has no renderer just falls back to its
        // ordinary base model at draw time, at the cost of one cheap reflective lookup - the same
        // cost every OBJ-backed held item already pays.
        int unattributedCandidates = 0;
        for (RenderMap.UnattributedItemRow row : map.unattributedItems()) {
            String id = row.id();
            if (id == null || !id.startsWith(ns + ":")) continue;
            if (!snap.itemIds.contains(id)) continue;
            String heldPath = TexturePick.sanitize(bare(id));
            if (heldPath.isEmpty() || emittedPaths.contains(heldPath)) continue;
            heldOnlyRows.add(new HeldOnlyRow(id, heldPath));
            unattributedCandidates++;
        }

        // ------------------------------------------------------------ blocks
        List<BlockEntry> blocks = new ArrayList<>();
        Map<String, Integer> blockSkip = new TreeMap<>();
        for (RenderMap.BlockRow row : map.blocks()) {
            String id = row.id();
            if (id == null || !id.startsWith(ns + ":")) continue;
            if (!snap.blockIds.contains(id)) { bump(blockSkip, "not-in-snapshot"); continue; }
            List<RenderMap.Asset> tex = new ArrayList<>(row.textures());
            String texSource = tex.isEmpty() ? "tileEntity" : "block-row";
            if (tex.isEmpty()) {
                RenderMap.TeRow te = map.tileEntityFor(id);
                if (te != null) tex.addAll(te.textures());
            }
            TexturePick.Pick pick = dev.umb.objbridge.BlockTexturePolicy.choose(row.models(), tex, assetsRoot);
            if (pick.model() == null) { bump(blockSkip, "no-obj"); continue; }
            if (pick.texture() == null) { bump(blockSkip, "no-texture"); continue; }
            int tris = triangles(triCache, assetsRoot, pick.model());
            if (tris <= 0) { bump(blockSkip, "obj-unparseable"); continue; }
            String sprite = pick.spriteId();
            texturesToCopy.putIfAbsent(sprite, pick.texture());
            blocks.add(new BlockEntry(id, pick.model().path(), sprite,
                    pick.reason().name() + "/" + texSource, tris));

            // the block's own BlockItem needs an item def too, so held/inventory shows the mesh. The
            // renderer class it carries for the OVERSIZED decision is the block's own held/inventory
            // IItemRenderer - very often the TESR's anonymous inner class ($1), never the TESR class
            // itself (which only ever draws the PLACED block) - same preference ObjBridge's runtime
            // itemRendererIndex() uses, so ObjPackGen's oversized flag and ObjTransforms.forItem's actual
            // bake-time GUI scale are always computed from the identical class.
            String packPath = TexturePick.sanitize(bare(id));
            if (!packPath.isEmpty() && emittedPaths.add(packPath)) {
                bump(reasonCounts, pick.reason().name());
                String worldClass = row.tesrClass() != null ? row.tesrClass() : row.isbrhClass();
                String inner = worldClass == null ? null : worldClass + "$1";
                String rendererClass = inner != null && transforms.hasClass(inner) ? inner : worldClass;
                defs.add(new Def(id, packPath, pick.model().path(), sprite,
                        pick.reason().name() + "(block)", tris, row.groups().size(), true, rendererClass));
            }
        }

        // ------------------------------------------------- 1.13-style variant siblings
        // umb-hostagent flattens 1.7.10 metadata into separate registry entries (LegacyIds.variantId),
        // so one legacy id can be several 26.2 items: the base id plus `<baseId>_<meta>` (and, when the
        // sub-items all have distinct names, a "readable" id we cannot reconstruct from the render map
        // alone). The render map only knows the base id. We therefore look at the base pack's own item
        // definitions and mirror our def onto every `<basePath>_<digits>` sibling that really exists -
        // an override can only ever hit a path the base pack already declares, so this cannot invent
        // The base pack directory used to be hardcoded to "hbm-generated" regardless of which mod
        // this run targets - harmless for HBM (the only mod that literal ever matched) but on any
        // other mod's run it either read a stale/leftover HBM pack from a previous run (silent
        // cross-mod contamination of the variant-sibling matching below) or found nothing at all.
        // The harness's own PackGen already writes this directory per-modid as
        // `packs/<ModId>-generated` (confirmed on disk: IronChest-generated, Railcraft-generated,
        // chisel-generated, mcheli-generated, hbm-generated all exist side by side) - the caller
        // knows the exact path and should pass --base-pack-dir; the derived fallback below is a
        // best-effort guess from `ns` (works when the modid is already lowercase, as hbm/chisel/
        // mcheli's are) and is logged as a guess so a mismatch is visible rather than silent.
        Path baseItems;
        if (basePackDirArg != null) {
            baseItems = basePackDirArg.resolve("assets/" + ns + "/items");
        } else {
            baseItems = outDir.resolveSibling(ns + "-generated").resolve("assets/" + ns + "/items");
            System.out.println("--base-pack-dir not given; guessing base pack at "
                    + baseItems.getParent().getParent()
                    + " (derived from --ns; pass --base-pack-dir explicitly when the modid"
                    + " casing differs from the lowercase asset namespace)");
        }
        Set<String> basePaths = new LinkedHashSet<>();
        if (Files.isDirectory(baseItems)) {
            try (var s = Files.list(baseItems)) {
                for (Path p : s.toList()) {
                    String n = p.getFileName().toString();
                    if (n.endsWith(".json")) basePaths.add(n.substring(0, n.length() - 5));
                }
            }
        }
        int expanded = 0, basePathAbsent = 0;
        List<String> basePathAbsentSample = new ArrayList<>();
        if (!basePaths.isEmpty()) {
            for (Def d : List.copyOf(defs)) {
                if (!basePaths.contains(d.packPath())) {
                    basePathAbsent++;
                    if (basePathAbsentSample.size() < 20) {
                        basePathAbsentSample.add(d.packPath() + "  (legacy id " + d.legacyId() + ")");
                    }
                }
                String prefix = d.packPath() + "_";
                for (String cand : basePaths) {
                    if (!cand.startsWith(prefix)) continue;
                    String tail = cand.substring(prefix.length());
                    if (tail.isEmpty() || !tail.chars().allMatch(Character::isDigit)) continue;
                    if (!emittedPaths.add(cand)) continue;
                    defs.add(new Def(d.legacyId() + "@" + tail, cand, d.objPath(), d.spriteId(),
                            d.reason() + "(variant)", d.triangles(), d.groups(), d.viaBlock(),
                            d.rendererClass()));
                    expanded++;
                }
            }
        }

        // ------------------------------------------------------------ write
        // any fault mid-generation (a missing library on the classpath, a bad asset) destroyed the
        // live pack and left a half-built directory behind. That actually happened: the pack was
        // reduced to 426 of 983 files by a NoClassDefFoundError. Build into a staging directory and
        // swap it in only once generation has fully succeeded, so a failure leaves the existing pack
        // untouched.
        Path workDir = outDir.resolveSibling(outDir.getFileName() + ".staging");
        if (Files.exists(workDir)) deleteTree(workDir);
        Files.createDirectories(workDir);

        write(workDir.resolve("pack.mcmeta"), """
                {
                  "pack": {
                    "description": "UMB OBJ model overlay - %s 1.7.10 OBJ meshes as real geometry on Minecraft 26.2",
                    "min_format": %d,
                    "max_format": %d
                  }
                }
                """.formatted(ns, PACK_FORMAT, PACK_FORMAT));

        // One minecraft:directory source per top-level texture directory we actually copy into, so
        // every sprite id we hand out exists. `models` covers the overwhelming majority; a handful of
        // renderers bound an armour/blocks/particle sheet for their OBJ instead.
        Set<String> spriteDirs = new java.util.TreeSet<>();
        for (String spriteId : texturesToCopy.keySet()) {
            String rel = spriteId.substring(spriteId.indexOf(':') + 1);   // models/weapons/minigun
            int slash = rel.indexOf('/');
            spriteDirs.add(slash > 0 ? rel.substring(0, slash) : rel);
        }
        if (spriteDirs.isEmpty()) spriteDirs.add("models");
        StringBuilder atlas = new StringBuilder("{\n  \"sources\": [\n");
        int di = 0;
        for (String dir : spriteDirs) {
            atlas.append("    {\n      \"type\": \"minecraft:directory\",\n")
                    .append("      \"prefix\": ").append(q(dir + "/")).append(",\n")
                    .append("      \"source\": ").append(q(dir)).append("\n    }");
            atlas.append(++di < spriteDirs.size() ? ",\n" : "\n");
        }
        atlas.append("  ]\n}\n");
        write(workDir.resolve("assets/minecraft/atlases/blocks.json"), atlas.toString());

        int copied = 0, copyFailed = 0;
        long copiedBytes = 0;
        List<String> copyErrors = new ArrayList<>();
        for (Map.Entry<String, RenderMap.Asset> e : texturesToCopy.entrySet()) {
            String spriteId = e.getKey();                          // hbm:models/weapons/minigun
            RenderMap.Asset asset = e.getValue();
            Path src = assetsRoot.resolve(asset.assetPath() != null ? asset.assetPath()
                    : "assets/" + asset.namespace() + "/" + asset.bare());
            String rel = spriteId.substring(spriteId.indexOf(':') + 1);   // models/weapons/minigun
            Path dst = workDir.resolve("assets/" + ns + "/textures/" + rel + ".png");
            try {
                if (!Files.isRegularFile(src)) {
                    copyFailed++;
                    if (copyErrors.size() < 20) copyErrors.add("missing source " + src);
                    continue;
                }
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                copied++;
                copiedBytes += Files.size(dst);
            } catch (IOException io) {
                copyFailed++;
                if (copyErrors.size() < 20) copyErrors.add(src + " -> " + dst + " : " + io);
            }
        }

        int oversizedCount = 0;
        Map<String, Integer> oversizedSkipReason = new TreeMap<>();
        for (Def d : defs) {
            Path f = workDir.resolve("assets/" + ns + "/items/" + d.packPath() + ".json");
            Files.createDirectories(f.getParent());
            boolean big = oversized(transforms, d.rendererClass(), oversizedSkipReason);
            if (big) oversizedCount++;
            write(f, itemDef(d.objPath(), d.spriteId(), big));
        }

        // "no-obj" skip above). emittedPaths already carries every def/block/variant path chosen
        // above, so this can only ever ADD a wrapper around a path nothing else claimed.
        int heldOnlyWritten = 0;
        for (HeldOnlyRow h : heldOnlyRows) {
            if (!emittedPaths.add(h.packPath())) continue;
            Path f = workDir.resolve("assets/" + ns + "/items/" + h.packPath() + ".json");
            Files.createDirectories(f.getParent());
            write(f, heldOnlyDef(ns, h.packPath()));
            heldOnlyWritten++;
        }

        // Generation of every pack file succeeded. Only now is it safe to replace the live pack.
        // The old directory is moved aside first and deleted only after the new one is in place, so
        // an interruption during the swap still leaves a complete pack on disk under one name or the
        // other rather than no pack at all.
        Path retired = outDir.resolveSibling(outDir.getFileName() + ".retired");
        if (Files.exists(retired)) deleteTree(retired);
        boolean hadPrevious = Files.exists(outDir);
        if (hadPrevious) Files.move(outDir, retired);
        try {
            Files.move(workDir, outDir);
        } catch (IOException swapFailed) {
            if (hadPrevious) Files.move(retired, outDir);   // put the old pack back
            throw swapFailed;
        }
        if (hadPrevious) deleteTree(retired);

        if (blockList != null) {
            Files.createDirectories(blockList.getParent());
            StringBuilder sb = new StringBuilder("{\n  \"blocks\": [\n");
            for (int i = 0; i < blocks.size(); i++) {
                BlockEntry b = blocks.get(i);
                sb.append("    {\"id\": ").append(q(b.blockId()))
                        .append(", \"model\": ").append(q(b.objPath()))
                        .append(", \"texture\": ").append(q(b.spriteId())).append("}");
                sb.append(i + 1 < blocks.size() ? ",\n" : "\n");
            }
            sb.append("  ]\n}\n");
            write(blockList, sb.toString());
        }

        // ------------------------------------------------------------ report
        int baseMatched = 0, baseMissing = 0;
        List<String> baseMissingSample = new ArrayList<>();
        if (!basePaths.isEmpty()) {
            for (Def d : defs) {
                if (basePaths.contains(d.packPath())) baseMatched++;
                else {
                    baseMissing++;
                    if (baseMissingSample.size() < 15) baseMissingSample.add(d.packPath());
                }
            }
        }

        StringBuilder r = new StringBuilder();
        r.append("# ").append(outDir.getFileName()).append(" overlay pack - generation report\n\n");
        r.append("Generated by `dev.umb.objbridge.gen.ObjPackGen`.\n\n");
        r.append("| input | value |\n|---|---|\n");
        r.append("| render map | `").append(mapFile).append("` |\n");
        r.append("| snapshot | `").append(snapFile).append("` |\n");
        r.append("| assets root | `").append(assetsRoot).append("` |\n");
        r.append("| out dir | `").append(outDir).append("` |\n");
        r.append("| pack format | ").append(PACK_FORMAT).append(" |\n\n");

        r.append("## Counts\n\n| metric | value |\n|---|---|\n");
        r.append("| render-map item rows | ").append(map.items().size()).append(" |\n");
        r.append("| **item defs written (`items/*.json`, type `umb:obj`)** | ").append(defs.size()).append(" |\n");
        r.append("| ... of which are BLOCK items | ").append(defs.stream().filter(Def::viaBlock).count()).append(" |\n");
        r.append("| **blocks for the agent to splice** | ").append(blocks.size()).append(" |\n");
        r.append("| distinct textures copied | ").append(copied).append(" |\n");
        r.append("| texture copy failures | ").append(copyFailed).append(" |\n");
        r.append("| copied texture bytes | ").append(copiedBytes).append(" |\n");
        r.append("| atlas directory sources added | ").append(spriteDirs).append(" |\n");
        r.append("| triangles behind the item defs | ").append(defs.stream().mapToInt(Def::triangles).sum()).append(" |\n");
        r.append("| triangles behind the block splices | ").append(blocks.stream().mapToInt(BlockEntry::triangles).sum()).append(" |\n");
        r.append("| item defs marked `\"oversized_in_gui\": true` (derived GUI scale > ")
                .append(OVERSIZED_GUI_SCALE_THRESHOLD).append(") | ").append(oversizedCount).append(" |\n");
        if (!basePaths.isEmpty()) {
            r.append("| base-pack item defs seen (`").append(ns).append("-generated`) | ").append(basePaths.size()).append(" |\n");
            r.append("| item defs that override an existing base-pack def | ").append(baseMatched).append(" |\n");
            r.append("| item defs with NO base-pack counterpart (id drift!) | ").append(baseMissing).append(" |\n");
            r.append("| defs mirrored onto `_<meta>` variant siblings | ").append(expanded).append(" |\n");
            r.append("| base ids whose own def no longer exists in the base pack | ").append(basePathAbsent).append(" |\n");
        }
        r.append("\n");

        r.append("## Primary-texture choice, by rule\n\n");
        r.append("Rules, in order: `BASENAME` = texture basename equals the OBJ basename; `FAMILY` = first\n");
        r.append("texture in the same directory family as the model; `MIRROR` = the on-disk\n");
        r.append("`textures/models/<same relative path>.png`; `FIRST` = first non-effect texture the row lists.\n");
        r.append("Textures whose name contains plume/flash/muzzle/glow/lens/beam/laser are excluded from every rule.\n\n");
        r.append("| rule | item defs |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : reasonCounts.entrySet()) {
            r.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        r.append("\n## Skipped item rows\n\n| reason | rows |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : skipCounts.entrySet()) {
            r.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }
        r.append("\n### First skipped rows in detail\n\n");
        for (String s : skipDetail) r.append("- `").append(s).append("`\n");

        r.append("\n## Skipped block rows\n\n| reason | rows |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : blockSkip.entrySet()) {
            r.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }

        r.append("\n## `oversized_in_gui` decision, by reason NOT set\n\n");
        r.append("Every def not already counted in the `oversized_in_gui` row above, broken down by why:\n");
        r.append("`no-renderer-class` = plain item, the render map names no `IItemRenderer` at all (no\n");
        r.append("per-perspective data can exist, always flat vanilla scale); `unknown-to-transforms` = a\n");
        r.append("class IS named but `renderer-transforms.json` has never heard of it; `no-perspective-ops`\n");
        r.append("= known class, but zero INVENTORY/FIRST_PERSON/THIRD_PERSON/COMMON ops recorded (e.g. a\n");
        r.append("renderer that only ever touches the WORLD/TESR path); `under-threshold` = real derived\n");
        r.append("GUI scale computed, just not past ").append(OVERSIZED_GUI_SCALE_THRESHOLD).append(".\n\n");
        r.append("| reason | item defs |\n|---|---|\n");
        for (Map.Entry<String, Integer> e : oversizedSkipReason.entrySet()) {
            r.append("| ").append(e.getKey()).append(" | ").append(e.getValue()).append(" |\n");
        }

        r.append("\n## Twenty largest meshes now in play\n\n| id | obj | sprite | triangles |\n|---|---|---|---|\n");
        defs.stream().sorted(Comparator.comparingInt(Def::triangles).reversed()).limit(20).forEach(d ->
                r.append("| `").append(d.legacyId()).append("` | `").append(d.objPath())
                        .append("` | `").append(d.spriteId()).append("` | ").append(d.triangles()).append(" |\n"));

        if (!copyErrors.isEmpty()) {
            r.append("\n## Texture copy errors\n\n");
            for (String s : copyErrors) r.append("- `").append(s).append("`\n");
        }
        if (!baseMissingSample.isEmpty()) {
            r.append("\n## Item defs with no base-pack counterpart\n\n");
            r.append("These would NOT override anything - the id sanitizer disagreed with PackGen's:\n\n");
            for (String s : baseMissingSample) r.append("- `").append(s).append("`\n");
        }
        if (!basePathAbsentSample.isEmpty()) {
            r.append("\n## Base ids that no longer have their own base-pack def\n\n");
            r.append("umb-hostagent's `LegacyIds.variantId` gives a whole variant GROUP \"readable\" ids\n");
            r.append("(`<ns>:<sub.unlocalizedName>`) when every sub-item has a distinct name, which retires the\n");
            r.append("plain base id. `Snapshot.collectItemVariants` (this file) mirrors that rule from the\n");
            r.append("snapshot's own `subItems[]`, so a render-map row keyed by the base id is still matched to\n");
            r.append("every real variant id it should cover - the ids below are base ids the SNAPSHOT itself\n");
            r.append("shows as retired that still slipped through, which means either the snapshot's `subItems`\n");
            r.append("were incomplete for this item or the base-pack disagrees with the mirrored rule; worth a\n");
            r.append("closer look, not a guess made here.\n\n");
            for (String s : basePathAbsentSample) r.append("- `").append(s).append("`\n");
        }
        r.append("\n## Known v1 limitations\n\n");
        r.append("- Every `g`/`o` group of the OBJ is drawn. 235 of the 507 OBJs have more than one group and the\n");
        r.append("  1.7.10 renderers picked among them at draw time; e.g. `minigun.obj` has both `Gun` and `GunDual`,\n");
        r.append("  so both are drawn overlapping. The `\"groups\"` field of the `umb:obj` codec already accepts a\n");
        r.append("  subset - the render map's `groups[]` hint is recorded per row and can be switched on later.\n");
        r.append("- Most `dynamic` item rows (renderer chose model/texture at draw time) are still resolved by\n");
        r.append("  taking the FIRST model and the primary texture. `dev.umb.rendermap.DynamicVariantResolver`\n");
        r.append("  (umb-rendermap) now resolves the subset whose choice is a compile-time table indexed by\n");
        r.append("  `getItemDamage()` (enum tables, static instance-arrays, plain damage==0 branches) into one\n");
        r.append("  exact row per real variant id instead - see that row's `resolvedBy` field.\n");
        r.append("- A BLOCK's own item-form mesh is shaped by its in-world (WORLD-path) transform when the\n");
        r.append("  block's TESR/ISBRH class resolves one (block-item-in-slot rule,\n");
        r.append("  `dev.umb.objbridge.ObjBridge#itemGeometryFit`/laneInv-progress.md), then normalised to fit\n");
        r.append("  the item slot like auto-fit - so it keeps its correct in-world proportions instead of the\n");
        r.append("  raw OBJ file's own (often unrelated) aspect ratio. Every OTHER item (the overwhelming\n");
        r.append("  majority) is still a plain auto-fit of the raw mesh: one uniform scale putting the largest\n");
        r.append("  bbox extent at 1 block, with only the PER-PERSPECTIVE scale (GUI/first-person/third-person,\n");
        r.append("  see `ObjTransforms.forItem` and the `oversized_in_gui` count above) recovered from the\n");
        r.append("  renderer's own ratios - the base geometry's overall size is not.\n");
        r.append("- The copied model textures are mostly not power-of-two (`122x140` is the single most common\n");
        r.append("  size, 130 files), so Minecraft's sprite loader will reduce the block atlas mipmap level.\n");
        r.append("- A cheap future win, measured but deliberately NOT implemented (it is a fifth rule the brief\n");
        r.append("  did not specify): indexing every PNG under `textures/models/**` by basename and matching the\n");
        r.append("  OBJ basename against that index rescues exactly 12 more blocks and 12 more items - all of\n");
        r.append("  them the anvils, whose `models/blocks/anvil.obj` is skinned by `textures/models/machines/anvil.png`\n");
        r.append("  which no renderer row names. Zero ambiguous matches.\n");
        write(report, r.toString());

        System.out.println("item defs   : " + defs.size());
        System.out.println("held-only   : " + heldOnlyWritten + " (" + heldOnlyRows.size()
                + " candidates: rendererClass-detected-no-OBJ + " + unattributedCandidates
                + " unattributed)");
        System.out.println("blocks      : " + blocks.size());
        System.out.println("textures    : " + copied + " copied, " + copyFailed + " failed");
        System.out.println("reasons     : " + reasonCounts);
        System.out.println("skips       : " + skipCounts);
        System.out.println("block skips : " + blockSkip);
        System.out.println("oversized   : " + oversizedCount + " item defs marked oversized_in_gui"
                + " (not: " + oversizedSkipReason + ")");
        System.out.println("pack        : " + outDir);
        System.out.println("report      : " + report);
        if (!basePaths.isEmpty()) {
            System.out.println("base-pack   : defs=" + basePaths.size() + " overridden=" + baseMatched
                    + " missing=" + baseMissing + " variantSiblings=+" + expanded
                    + " baseIdRetired=" + basePathAbsent);
        }
    }

    // ---------------------------------------------------------------- helpers

    public static String itemDef(String objPath, String spriteId) {
        return itemDef(objPath, spriteId, false);
    }

    /**
     * @param oversized when true, emits the top-level {@code "oversized_in_gui": true} client-item
     *                  {@code false} on {@code ClientItem$Properties} - see {@code ObjTransforms}'s class
     *                  docs) so 26.2's own picture-in-picture item renderer lets this item's GUI icon
     *                  overflow its 16x16 slot instead of it being squashed or clipped. Omitted entirely
     *                  (rather than written as {@code false}) when not needed, matching every other
     *                  optional field this generator writes.
     */
    public static String itemDef(String objPath, String spriteId, boolean oversized) {
        String model = """
                  "model": {
                    "type": "umb:obj",
                    "model": %s,
                    "texture": %s
                  }""".formatted(q(objPath), q(spriteId));
        return oversized
                ? "{\n" + model + ",\n  \"oversized_in_gui\": true\n}\n"
                : "{\n" + model + "\n}\n";
    }

    /**
     * {@code "type":"minecraft:model"} reference {@code dev.umb.packgen.PackGen}'s base layer
     * already wrote for {@code ns:item/path} (that base {@code models/item/&lt;path&gt;.json} is
     * untouched - this only overrides the client-item DEFINITION, same as {@link #itemDef} does
     * for the OBJ case). {@link dev.umb.objbridge.itemeffects.HeldItemModel.Unbaked} decodes
     * {@code base} through vanilla's own {@code ItemModels.CODEC}, so this is not tied to
     * {@code "minecraft:model"} specifically - it is simply the type the base pack already uses.
     */
    static String heldOnlyDef(String ns, String path) {
        return """
                {
                  "model": {
                    "type": "umb:held",
                    "base": {
                      "type": "minecraft:model",
                      "model": %s
                    }
                  }
                }
                """.formatted(q(ns + ":item/" + path));
    }

    /**
     * Whether {@code rendererClass}'s own derived GUI scale (computed with the EXACT same
     * {@link ItemPerspectiveRatio} math {@code dev.umb.objbridge.item.ObjTransforms.forItem} uses at
     * bake time) exceeds {@link #OVERSIZED_GUI_SCALE_THRESHOLD}. {@code counts}, when given, is bumped
     * with why a class did NOT qualify (for the report) - "no-renderer-class" (most plain items - no
     * per-item data at all, always flat vanilla 0.625, never oversized), "unknown-to-transforms",
     * "no-perspective-ops" (class known but zero INVENTORY/FIRST_PERSON/THIRD_PERSON/COMMON ops), or
     * "under-threshold" (real data, just not big enough to need help).
     */
    static boolean oversized(RendererTransforms transforms, String rendererClass,
                                     Map<String, Integer> counts) {
        if (rendererClass == null) { bump(counts, "no-renderer-class"); return false; }
        if (!transforms.hasClass(rendererClass)) { bump(counts, "unknown-to-transforms"); return false; }
        ItemPerspectiveRatio.Buckets b = ItemPerspectiveRatio.buckets(transforms, rendererClass);
        if (b.found() == 0) { bump(counts, "no-perspective-ops"); return false; }
        float baseline = b.baseline();
        float mult = ItemPerspectiveRatio.ratioMultiplier(b.invEffective(), baseline);
        float guiScale = VANILLA_GUI_SCALE * mult;
        if (guiScale > OVERSIZED_GUI_SCALE_THRESHOLD) return true;
        bump(counts, "under-threshold");
        return false;
    }

    /** {@code minecraft:block/block}'s {@code gui} display scale - see {@code ObjTransforms.GUI}. */
    static final float VANILLA_GUI_SCALE = 0.625f;

    private static int triangles(Map<String, Integer> cache, Path assetsRoot, RenderMap.Asset model) {
        Integer got = cache.get(model.path());
        if (got != null) return got;
        int n = 0;
        Path p = assetsRoot.resolve(model.assetPath() != null ? model.assetPath()
                : "assets/" + model.namespace() + "/" + model.bare());
        try {
            if (Files.isRegularFile(p)) n = ObjMesh.parse(p).triangleCount();
        } catch (Exception e) {
            n = 0;
        }
        cache.put(model.path(), n);
        return n;
    }

    private static String bare(String id) {
        int c = id.indexOf(':');
        return c >= 0 ? id.substring(c + 1) : id;
    }

    /**
     * The single most common non-{@code minecraft} namespace across every id in the render map -
     * used ONLY as the {@code --ns} default when the caller does not pass one explicitly. The
     * render map is produced per-target-jar ({@code dev.umb.rendermap.RenderMap}), so in the normal
     * case every id in it already shares one namespace; this is a fallback for that data, never a
     * literal mod name. Returns {@code "unknown"} (which will make every id check below correctly,
     * honestly skip every row rather than mimic any one mod) when the map carries no non-vanilla id
     * at all.
     */
    private static String dominantNamespace(RenderMap map) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (RenderMap.ItemRow r : map.items()) bumpNamespace(counts, r.id());
        for (RenderMap.BlockRow r : map.blocks()) bumpNamespace(counts, r.id());
        String best = null;
        int bestCount = 0;
        for (var e : counts.entrySet()) {
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        }
        return best != null ? best : "unknown";
    }

    private static void bumpNamespace(Map<String, Integer> counts, String id) {
        if (id == null) return;
        int c = id.indexOf(':');
        if (c <= 0) return;
        String ns = id.substring(0, c);
        if ("minecraft".equals(ns)) return;
        counts.merge(ns, 1, Integer::sum);
    }

    private static void bump(Map<String, Integer> m, String k) {
        m.merge(k, 1, Integer::sum);
    }

    private static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static void write(Path p, String content) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    private static void deleteTree(Path root) throws IOException {
        try (var s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }

    /** Just the id sets - the snapshot's own reader lives in umb-hostagent and is off limits here. */
    record Snapshot(Set<String> itemIds, Set<String> blockIds) {
        static Snapshot read(Path file) throws IOException {
            Set<String> items = new LinkedHashSet<>();
            Set<String> blocks = new LinkedHashSet<>();
            try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject root = new Gson().fromJson(r, JsonObject.class);
                collect(root, "items", items);
                collect(root, "blocks", blocks);
                collectItemVariants(root, items);
            }
            return new Snapshot(items, blocks);
        }

        private static void collect(JsonObject root, String key, Set<String> into) {
            JsonElement e = root == null ? null : root.get(key);
            if (e == null || !e.isJsonArray()) return;
            JsonArray a = e.getAsJsonArray();
            for (JsonElement x : a) {
                if (!x.isJsonObject()) continue;
                JsonElement id = x.getAsJsonObject().get("id");
                if (id != null && !id.isJsonNull()) into.add(id.getAsString());
            }
        }

        /**
         * The snapshot only records each item's BASE legacy id; umb-hostagent additionally
         * registers one entry per distinct metadata damage value
         * ({@code dev.umb.hostagent.content.LegacyIds.variantId} / {@code VariantPlan}, read-only
         * "not-in-snapshot"). This is the THIRD independent read-only port of that tiny rule
         * (umb-hostagent registers it, umb-rendermap's {@code VariantIdRule} resolves per-variant
         * dynamic rows against it, this one only needs the id set) - all three must keep agreeing,
         * which is exactly what the base-pack "id drift" counter in this report checks for.
         */
        private static void collectItemVariants(JsonObject root, Set<String> into) {
            JsonElement e = root == null ? null : root.get("items");
            if (e == null || !e.isJsonArray()) return;
            for (JsonElement x : e.getAsJsonArray()) {
                if (!x.isJsonObject()) continue;
                JsonObject o = x.getAsJsonObject();
                String id = str(o, "id");
                String baseUnloc = str(o, "unlocalizedName");
                if (id == null) continue;
                JsonElement subsEl = o.get("subItems");
                if (subsEl == null || !subsEl.isJsonArray()) continue;
                // de-dup by damage, in first-seen order (the snapshot lists some subItems twice)
                Map<Integer, String> byDamage = new LinkedHashMap<>();
                for (JsonElement se : subsEl.getAsJsonArray()) {
                    if (!se.isJsonObject()) continue;
                    JsonObject so = se.getAsJsonObject();
                    int damage = so.has("damage") && !so.get("damage").isJsonNull() ? so.get("damage").getAsInt() : 0;
                    byDamage.putIfAbsent(damage, str(so, "unlocalizedName"));
                }
                if (byDamage.size() <= 1) continue;
                boolean readable = readableIdsUsable(baseUnloc, byDamage.values());
                String ns = namespaceOf(id, "minecraft");
                for (Map.Entry<Integer, String> se : byDamage.entrySet()) {
                    if (readable) into.add(ns + ":" + se.getValue());
                    else if (se.getKey() != 0) into.add(id + "_" + se.getKey());
                    // meta 0 in the non-readable case is the base id, already added by collect()
                }
            }
        }

        private static boolean readableIdsUsable(String baseUnloc, java.util.Collection<String> subNames) {
            if (subNames.isEmpty()) return false;
            Set<String> seen = new LinkedHashSet<>();
            for (String n : subNames) {
                if (n == null || n.isEmpty() || n.equals(baseUnloc) || !seen.add(n)) return false;
            }
            return true;
        }

        private static String namespaceOf(String legacyId, String fallback) {
            int c = legacyId.indexOf(':');
            return c <= 0 ? fallback : legacyId.substring(0, c);
        }

        private static String str(JsonObject o, String k) {
            JsonElement v = o.get(k);
            return v == null || v.isJsonNull() ? null : v.getAsString();
        }
    }
}
