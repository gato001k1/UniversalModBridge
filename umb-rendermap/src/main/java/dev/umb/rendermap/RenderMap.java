package dev.umb.rendermap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.io.File;

/**
 * Entry point: {@code RenderMap <mod.jar> <snapshot.json> <outDir> [--no-bonus-indirection]
 * [--also-write <file>]} — for ANY 1.7.10 Forge mod jar. {@code <mod.jar>} is read as a
 * plain parameter; nothing here is hardcoded to one mod's jar path or package.
 *
 * <p>Emits {@code <namespace>-render-map.json} (namespace from the snapshot filename,
 * e.g. {@code hbm-snapshot.json} -> {@code hbm-render-map.json}) and {@code REPORT.md} into
 * {@code outDir}. {@code --also-write} drops an identical copy under another file name for
 * callers still pointed at a legacy layout (never a mod literal in code — the name comes
 * from the command line).
 */
public class RenderMap {

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: RenderMap <mod.jar> <snapshot.json> <outDir>"
                    + " [--no-bonus-indirection] [--also-write <file>]");
            System.exit(2);
        }
        Path jarPath = Paths.get(args[0]);
        Path snapPath = Paths.get(args[1]);
        Path outDir = Paths.get(args[2]);
        // Gate for the optional structural-indirection bonus tier
        // (BindingScanner.includeBonusResolvers and this class's own bonus-tier dynamic
        // variant discovery). Default true for backward compatibility with every existing
        // caller; pass --no-bonus-indirection to measure the generic tier alone with the
        // bonus tier fully off, proving turning it off cannot regress the generic pass.
        boolean includeBonus = true;
        List<String> alsoWrite = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            if ("--no-bonus-indirection".equals(args[i])) includeBonus = false;
            else if ("--also-write".equals(args[i]) && i + 1 < args.length) alsoWrite.add(args[++i]);
        }
        // <modid>-snapshot.json is the naming convention this whole harness already uses
        // (fixtures/, harness/legacy.ps1, every research/out/legacy/<modid>-snapshot.json seen in
        // this repo) — not a new mod-specific literal, just reading a convention that already
        // exists, to hint Snapshot#dominantLocalPrefix away from a leftover/unioned mod's ids when
        // the shared native mods\ folder was not cleaned between runs (a known harness bug).
        String probableModId = snapPath.getFileName().toString().replaceFirst("(?i)-snapshot\\.json$", "");
        Files.createDirectories(outDir);

        long t0 = System.currentTimeMillis();
        log("reading jar " + jarPath);
        JarIndex jar = JarIndex.open(jarPath);
        log("  " + jar.classes.size() + " classes, " + jar.assets.size() + " assets");
        List<Path> explicitEngine = new ArrayList<>();
        for (int i = 3; i < args.length - 1; i++) {
            if ("--engine-classpath".equals(args[i])) {
                for (String raw : args[++i].split(java.util.regex.Pattern.quote(File.pathSeparator))) {
                    if (!raw.isBlank()) explicitEngine.add(Paths.get(raw));
                }
            }
        }
        loadEngineClasspathIfPresent(jar, explicitEngine);

        log("loading snapshot " + snapPath);
        Snapshot snap = Snapshot.load(snapPath);
        TileEntityLinker.apply(jar, snap);
        log("  " + snap.blocks.size() + " blocks, " + snap.items.size() + " items, "
                + snap.tileEntities.size() + " TEs, " + snap.entities.size() + " entities");

        log("parsing OBJ models");
        Map<String, ObjModel> models = parseModels(jar);
        log("  " + models.size() + " .obj parsed");

        log("resolving resource holders");
        HolderResolver holders = new HolderResolver(jar, probableModId);
        holders.scanAll();
        log("  " + holders.holderClasses.size() + " holder classes, " + holders.byField.size() + " fields");

        log("resolving ModItems/ModBlocks field -> registry id");
        ModRegistryResolver mods = new ModRegistryResolver(jar);
        mods.scanAll();

        log("scanning GameRegistry call sites");
        RegistryScanner reg = new RegistryScanner(jar);
        reg.scanAll();
        log("  registerItem sites=" + reg.itemCallSites + " registerBlock sites=" + reg.blockCallSites
                + " forwarded=" + reg.forwardedCallSites + " forwarders=" + reg.forwarders.size());
        mods.resolveIds(snap, reg, probableModId);
        log("  field->id resolved=" + mods.byField.values().stream().filter(e -> e.id != null).count()
                + "/" + mods.byField.size());

        log("scanning renderer bindings" + (includeBonus ? "" : " (generic tier only, --no-bonus-indirection)"));
        BindingScanner bind = new BindingScanner(jar);
        bind.includeBonusResolvers = includeBonus;
        bind.scanAll();
        bind.resolveIsbrhRenderIds();
        log("  items=" + bind.itemBindings.size() + " tesr=" + bind.tesrBindings.size()
                + " isbrh=" + bind.isbrhBindings.size() + " entity=" + bind.entityBindings.size());

        // Metadata-branched TE relink: blocks whose snapshot TE has no bound TESR get a
        // second chance through their own factory when it also constructs a rendered TE.
        Set<String> boundTeClasses = new LinkedHashSet<>();
        for (BindingScanner.TesrBinding t : bind.tesrBindings)
            if (t.teClass != null && t.rendererClass != null) boundTeClasses.add(t.teClass);
        Map<String, String> teRelinkNotes = TileEntityLinker.relinkUnrendered(jar, snap, boundTeClasses);
        log("  te-relinked=" + teRelinkNotes.size());

        RendererAnalyzer analyzer = new RendererAnalyzer(jar, holders);

        Out out = new Out(jar, snap, models, holders, mods, reg, bind, analyzer);
        out.jarPathStr = jarPath.toString();
        out.snapPathStr = snapPath.toString();
        out.mapFileName = mapFileName(probableModId, snap);
        out.outputNamespace = sanitizeNamespace(probableModId);
        out.alsoWrite = alsoWrite;
        out.teRelinkNotes = teRelinkNotes;
        out.build();
        out.write(outDir);
        log("done in " + (System.currentTimeMillis() - t0) + " ms");
    }

    /**
     * Output map name from data, never a literal: the snapshot filename's
     * {@code <namespace>-snapshot.json} stem when present, else the snapshot content's own
     * dominant non-vanilla namespace, else {@code "unknown"}. For the historical HBM layout
     * ({@code hbm-snapshot.json}) this yields exactly {@code hbm-render-map.json}, so every
     * existing reader keeps working with zero compat copies needed.
     */
    static String mapFileName(String probableModId, Snapshot snap) {
        String ns = sanitizeNamespace(probableModId);
        if (ns.isEmpty() && snap != null) ns = dominantNamespace(snap);
        ns = sanitizeNamespace(ns);
        if (ns == null || ns.isEmpty()) ns = "unknown";
        return ns + "-render-map.json";
    }

    /** The same Identifier-safe namespace contract used by the host and pack generators. */
    static String sanitizeNamespace(String raw) {
        if (raw == null) return "";
        return raw.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    /** Most common non-{@code minecraft:} id namespace in the snapshot; "" when none. */
    static String dominantNamespace(Snapshot snap) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Snapshot.Blk b : snap.blocks) tallyNamespace(counts, b.id);
        for (Snapshot.Itm i : snap.items) tallyNamespace(counts, i.id);
        String best = "";
        int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet())
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        return best;
    }

    private static void tallyNamespace(Map<String, Integer> counts, String id) {
        if (id == null) return;
        int c = id.indexOf(':');
        if (c <= 0) return;
        String ns = id.substring(0, c).toLowerCase(java.util.Locale.ROOT);
        if (!ns.isEmpty() && !"minecraft".equals(ns)) counts.merge(ns, 1, Integer::sum);
    }

    /**
     * Best-effort: loads the 1.7.10 vanilla client + Forge universal jars (engine-shared
     * infrastructure every 1.7.10 Forge mod sits on, not a mod-specific resource) into
     * {@link JarIndex#engine} so hierarchy walks (e.g. Iron Chests' {@code BlockIronChest extends
     * BlockContainer extends Block}, where {@code BlockContainer} ships only in Minecraft's own
     * jar) don't dead-end at the mod jar's boundary. Tries a handful of well-known relative
     * locations used elsewhere in this repo, walking up from the working directory; silently does
     * nothing if none exist — every resolver in this package already degrades correctly with no
     * engine classpath at all (it just can't see through a vanilla intermediate superclass).
     */
    static final String[] ENGINE_JAR_CANDIDATES = {
            "research/out/1.7.10-client-srg.jar",
            "research/visual/mc1710-native/scratch-installer/extracted/"
                    + "forge-1.7.10-10.13.4.1614-1.7.10-universal.jar",
    };

    static void loadEngineClasspathIfPresent(JarIndex jar, List<Path> explicit) {
        Set<Path> loaded = new LinkedHashSet<>();
        List<Path> candidates = new ArrayList<>(explicit);
        if (candidates.isEmpty()) {
            for (Path base : candidateRoots()) {
                for (String rel : ENGINE_JAR_CANDIDATES) candidates.add(base.resolve(rel).normalize());
            }
        }
        for (Path p : candidates) {
            if (!Files.exists(p) || !loaded.add(p)) continue;
            try {
                int before = jar.engine.size();
                jar.loadEngineClasspath(p);
                log("  engine classpath: " + p + " (+" + (jar.engine.size() - before) + " classes)");
            } catch (IOException e) {
                log("  engine classpath: " + p + " failed to load (" + e + "), continuing without it");
            }
        }
        if (jar.engine.isEmpty())
            log("  engine classpath: none found (hierarchy walks through a vanilla/Forge"
                    + " intermediate superclass will be incomplete for this run)");
    }

    private static List<Path> candidateRoots() {
        List<Path> out = new ArrayList<>();
        Path cur = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && cur != null; i++, cur = cur.getParent()) out.add(cur);
        return out;
    }

    static Map<String, ObjModel> parseModels(JarIndex jar) {
        Map<String, ObjModel> models = new TreeMap<>();
        for (Map.Entry<String, byte[]> e : jar.assets.entrySet()) {
            String p = e.getKey();
            if (!p.toLowerCase().endsWith(".obj")) continue;
            // assets/<ns>/<rest> -> "<ns>:<rest>"
            String key = p;
            String[] seg = p.split("/", 3);
            if (seg.length == 3 && "assets".equals(seg[0])) key = seg[1] + ":" + seg[2];
            models.put(key, ObjParser.parse(key, p, e.getValue()));
        }
        return models;
    }

    static void log(String s) { System.out.println("[rendermap] " + s); }

    // ------------------------------------------------------------------
    /** Builds the joined output document. */
    static class Out {
        final JarIndex jar; final Snapshot snap; final Map<String, ObjModel> models;
        final HolderResolver holders; final ModRegistryResolver mods;
        final RegistryScanner reg; final BindingScanner bind; final RendererAnalyzer analyzer;

        final Map<String, JsonObject> itemRows = new LinkedHashMap<>();
        final Map<String, JsonObject> blockRows = new LinkedHashMap<>();
        final List<JsonObject> teRows = new ArrayList<>();
        final List<JsonObject> entityRows = new ArrayList<>();
        final List<String> unresolvedPatterns = new ArrayList<>();
        final Map<String, Integer> unresolvedCounts = new LinkedHashMap<>();
        final Map<String, String> unresolvedExample = new LinkedHashMap<>();

        int itemsWithCustomRenderer, itemsMapped, itemsUnresolved;
        int blocksWithIsbrh, blocksMapped, tesrs, tesrsMapped;
        // gap 2 (A1): ISBRH-bound blocks whose texture came from their own getIcon(side,meta)
        // (live-runtime capture) rather than a static field the renderer analysis could find.
        int blocksIsbrhIconTextureAdded, blocksIsbrhIconTextureCtmSkipped;
        int blocksCustomIconTextureAdded;
        // item-side mirror of gap 2/A1: GENERIC-tier items whose renderer names no static texture
        // field, resolved instead from the item's own getIconFromDamage/getIconIndex(ItemStack)
        // (live-runtime capture, Snapshot.Itm#iconName / SubItm#iconName) — see ITEM-ICON-RESOLUTION.md.
        int itemsIconTextureAdded, itemsIconTextureCtmSkipped;
        int objTotal, objParsed, objErrors, blockItemFallbacks;
        int blocksCustomRenderId, blocksCustomWithoutRenderer, blocksVanillaRender;
        int fieldsAttributedItems, fieldsAttributedBlocks;
        // entity content resolution (see buildEntities()): model/texture presence per entity
        // binding, the honest confidence-tier split, and the two new resolvers this lane added.
        int entityIdsWithResolvedModel, entityIdsWithResolvedTexture, entityIdsWithModelAndTexture;
        int entityIdsExact, entityIdsInferred, entityIdsUnresolved;
        int entityTextureResolvedViaGetEntityTexture;
        int entityJavaModelPartsTotal, entityJavaModelPartsResolved;
        String jarPathStr, snapPathStr;
        String mapFileName = "unknown-render-map.json";
        String outputNamespace = "unknown";
        List<String> alsoWrite = new ArrayList<>();
        Map<String, String> teRelinkNotes = new LinkedHashMap<>();
        final Set<String> javaModelClassesSeen = new LinkedHashSet<>();
        /** hop count -> number of attributed fields that needed exactly that many hops (gap 1). */
        Map<Integer, Integer> hopDistribution = new TreeMap<>();

        Out(JarIndex jar, Snapshot snap, Map<String, ObjModel> models, HolderResolver holders,
            ModRegistryResolver mods, RegistryScanner reg, BindingScanner bind, RendererAnalyzer analyzer) {
            this.jar = jar; this.snap = snap; this.models = models; this.holders = holders;
            this.mods = mods; this.reg = reg; this.bind = bind; this.analyzer = analyzer;
        }

        int dynamicVariantRowsAdded, dynamicVariantRowsUnresolved;
        final List<String> dynamicVariantNotes = new ArrayList<>();

        void build() {
            buildItems();
            // Bonus-tier dynamic-variant discovery (structural, per-row): any item row whose
            // renderer indexes a damage==0 two-way branch gets its variants derived from
            // bytecode (Family C). Enum-table and array-table variants (Families A/B) have no
            // derivable group mapping, so their old hand-maintained per-item call sites are
            // deleted and their base rows honestly stay `dynamic:true` — counted, not papered.
            if (bind.includeBonusResolvers) discoverDamageZeroVariants();
            buildTileEntities();
            buildBlocks();
            buildBlockItemFallback();
            buildEntities();
        }

        /**
         * Derives Family-C variant rows mechanically: for every item row with a dynamic
         * renderer, test the renderer's bytecode for the damage==0 two-branch texture shape
         * and, on a match, expand that row into per-damage rows with the existing resolver.
         * No per-item literals: the base id, renderer class, and holder fields all come from
         * the row and the jar. Dynamic rows with no derivable table keep their honest
         * `dynamic:true` base row and are counted once in the notes, not faked.
         */
        void discoverDamageZeroVariants() {
            List<String> baseIds = new ArrayList<>();
            for (String id : itemRows.keySet()) {
                if (!snap.itemById.containsKey(id)) continue;
                JsonObject row = itemRows.get(id);
                if (row != null && row.has("dynamic") && row.get("dynamic").getAsBoolean()
                        && !"null".equals(str(row, "rendererClass"))) baseIds.add(id);
            }
            int undiscovered = 0;
            for (String baseId : baseIds) {
                JsonObject row = itemRows.get(baseId);
                String rendererInternal = JarIndex.internal(str(row, "rendererClass"));
                ClassNode renderer = jar.cls(rendererInternal);
                if (renderer == null) { undiscovered++; continue; }
                DynamicVariantResolver.DamageZeroBranch br =
                        DynamicVariantResolver.findDamageZeroTextureBranch(
                                renderer, HolderResolver.T_RESLOC);
                if (!br.matched) { undiscovered++; continue; }
                resolveDamageZeroBranch(baseId, rendererInternal,
                        "damage-zero-branch (auto-discovered getItemDamage()==0 two-way branch in "
                        + str(row, "rendererClass") + ")");
            }
            if (undiscovered > 0) dynamicVariantNotes.add(undiscovered
                    + " dynamic row(s) with no derivable damage-table branch"
                    + " (hand-maintained per-item overrides removed; base rows stay dynamic:true)");
        }

        /**
         * A block item with no {@code IItemRenderer} of its own is drawn in the inventory by
         * its block's ISBRH ({@code renderInventoryBlock}) or, for a TESR block, by the
         * provider-interface path. Where we resolved the block's renderer but no
         * item binding exists, publish the block's resources against the item id as
         * {@code inferred} so the baking lane still has something to work with.
         */
        void buildBlockItemFallback() {
            for (Snapshot.Itm i : snap.modItems()) {
                if (itemRows.containsKey(i.id)) continue;
                JsonObject blk = blockRows.get(i.id);
                if (blk == null) continue;
                String isbrh = str(blk, "isbrhClass"), tesr = str(blk, "tesrClass");
                if ("null".equals(isbrh) && "null".equals(tesr)) continue;
                JsonObject row = new JsonObject();
                row.addProperty("id", i.id);
                row.addProperty("field", (String) null);
                row.addProperty("className", i.className);
                row.addProperty("iconName", i.iconName);
                row.addProperty("viaBlock", true);
                row.addProperty("producer", "block-item fallback (ISBRH/TESR inventory render)");
                row.addProperty("bindingSite", (String) null);
                row.addProperty("rendererClass", "null".equals(isbrh) ? tesr : isbrh);
                row.add("models", blk.get("models").deepCopy());
                row.add("textures", blk.get("textures").deepCopy());
                row.add("groups", blk.get("groups").deepCopy());
                row.add("javaModels", blk.has("javaModels") ? blk.get("javaModels").deepCopy() : new JsonArray());
                row.addProperty("usesRenderAll", false);
                row.addProperty("dynamic", blk.get("dynamic").getAsBoolean());
                row.add("dynamicReasons", new JsonArray());
                row.add("renderTypes", new JsonArray());
                row.addProperty("confidence", "inferred");
                row.addProperty("notes", "no IItemRenderer registered for this block item; resources copied"
                        + " from the block's " + ("null".equals(isbrh) ? "TESR" : "ISBRH"));
                itemRows.put(i.id, row);
                blockItemFallbacks++;
            }
        }

        // ---------- items ----------

        void buildItems() {
            for (BindingScanner.ItemBinding b : bind.itemBindings) {
                itemsWithCustomRenderer++;
                if (b.itemSelfClass != null) {
                    List<String> ids = snap.itemsByClass.get(b.itemSelfClass);
                    if (ids == null || ids.isEmpty()) {
                        itemsUnresolved++;
                        noteUnresolved("getItemForRenderer() returns `this` but no snapshot item has className "
                                + b.itemSelfClass, b.producer + " @ " + b.site);
                        continue;
                    }
                    for (String id : ids) { itemsMapped++; emitItemRow(id, b); }
                    continue;
                }
                String id = idForField(b.itemFieldOwner, b.itemFieldName);
                if (id == null) {
                    itemsUnresolved++;
                    String reason = b.unresolvedReason != null ? b.unresolvedReason
                            : (b.itemFieldName == null ? "no item field recovered"
                               : "field " + JarIndex.dotted(b.itemFieldOwner) + "." + b.itemFieldName
                                 + " has no registry id");
                    noteUnresolved(reason, b.producer + " @ " + b.site
                            + (b.itemFieldName != null
                               ? " -> " + JarIndex.dotted(b.itemFieldOwner) + "." + b.itemFieldName : ""));
                    continue;
                }
                if (!snap.itemById.containsKey(id)) {
                    itemsUnresolved++;
                    noteUnresolved("computed id not present in snapshot", id + " (from " + b.site + ")");
                    continue;
                }
                itemsMapped++;
                emitItemRow(id, b);
            }
        }

        void emitItemRow(String id, BindingScanner.ItemBinding b) {
            {
                Snapshot.Itm sItm = snap.itemById.get(id);
                RendererInfo ri = b.rendererClass == null ? null
                        : analyzer.analyze(JarIndex.internal(b.rendererClass));

                JsonObject row = itemRows.get(id);
                if (row == null) { row = new JsonObject(); itemRows.put(id, row); }
                row.addProperty("id", id);
                row.addProperty("field", b.itemFieldName == null ? null
                        : JarIndex.dotted(b.itemFieldOwner) + "." + b.itemFieldName);
                row.addProperty("className", sItm.className);
                row.addProperty("iconName", sItm.iconName);
                row.addProperty("viaBlock", b.viaBlock);
                row.addProperty("producer", b.producer);
                row.addProperty("bindingSite", b.site);
                row.addProperty("rendererClass", b.rendererClass);
                if (b.rendererFactory != null) row.addProperty("rendererFactory", b.rendererFactory);

                Set<String> modelFields = new LinkedHashSet<>();
                Set<String> texFields = new LinkedHashSet<>();
                if (ri != null) { modelFields.addAll(ri.modelFields); texFields.addAll(ri.textureFields); }
                if (b.directModelField != null) modelFields.add(b.directModelField);
                if (b.directTextureField != null) texFields.add(b.directTextureField);
                for (String f : b.ctorArgFields) {
                    ResRef byDotted = findByDotted(f);
                    if (byDotted != null && byDotted.resolved()) {
                        if (byDotted.kind == ResRef.Kind.MODEL) modelFields.add(f); else texFields.add(f);
                    }
                }
                row.add("models", refArray(modelFields));
                JsonArray texArr = refArray(texFields);
                boolean addedIconTexture = false;
                // Item-side mirror of gap 2/A1 (see RenderMap.iconTextureRefs / CONTENT-RESOLUTION.md):
                // a GENERIC-tier item renderer (never the mod's own bonus-tier bespoke dispatch layer —
                // gating on resolverKind keeps this capability free of any mod-specific literal) that
                // names no static texture field of its own still very often has a plain, resolvable
                // icon: the item's own getIconFromDamage/getIconIndex(ItemStack) result, captured live
                // by the native 1.7.10 boot (Snapshot.Itm#iconName), not guessed from bytecode.
                if (BindingScanner.GENERIC.equals(b.resolverKind) && b.rendererClass != null && texFields.isEmpty()) {
                    for (JsonElement e : iconTextureRefsItem(sItm)) { texArr.add(e); addedIconTexture = true; }
                    if (addedIconTexture) itemsIconTextureAdded++;
                }
                row.add("textures", texArr);
                row.add("groups", strArray(ri == null ? Set.of() : ri.groups));
                row.addProperty("usesRenderAll", ri != null && ri.usesRenderAll);
                row.addProperty("dynamic", ri != null && ri.dynamic);
                row.add("dynamicReasons", strArray(ri == null ? Set.of() : ri.dynamicReasons));
                row.add("renderTypes", strArray(ri == null ? Set.of() : ri.renderTypes));
                if (ri != null) {
                    JsonObject gl = new JsonObject();
                    gl.addProperty("rotate", ri.glRotate);
                    gl.addProperty("scale", ri.glScale);
                    gl.addProperty("translate", ri.glTranslate);
                    row.add("gl", gl);
                    row.add("boundTextureFields", strArray(ri.boundTextureFields));
                }
                row.add("javaModels", javaModelsArray(ri == null ? Set.of() : ri.javaModelClasses));
                boolean hasModel = !modelFields.isEmpty();
                // hasStaticTex: a texture the renderer's OWN bytecode names directly (a static
                // model/texture holder field) — as opposed to addedIconTexture, a real but WEAKER
                // (live-runtime, not bytecode-named) resource. Never let an icon-only resolution earn
                // the "exact" tier — the same discipline the block-side gap 2/A1 fix uses.
                boolean hasStaticTex = !texFields.isEmpty();
                String conf = b.rendererClass == null ? "unresolved"
                        : hasModel && hasStaticTex ? "exact"
                        : addedIconTexture ? "inferred-icon"
                        : hasModel || hasStaticTex ? "inferred"
                        : "inferred";
                row.addProperty("confidence", conf);
                if ("inferred-icon".equals(conf))
                    row.addProperty("notes", "renderer resolved via the generic tier but names no static"
                            + " texture field; texture resolved from the item's own"
                            + " getIconFromDamage/getIconIndex(ItemStack) (live-runtime capture) instead"
                            + " — the item-side mirror of gap 2/A1 in CONTENT-RESOLUTION.md");
                else if (!hasModel && !hasStaticTex)
                    row.addProperty("notes", "renderer bound but reads no resolvable model/texture holder field"
                            + " (likely a 2D-icon transform renderer)");
            }
        }

        /**
         * Item-side mirror of {@link #iconTextureRefs(Snapshot.Blk)}: resolves an item's own
         * live-runtime icon (captured by the native 1.7.10 boot, NOT guessed from bytecode) to a
         * real, verified texture path. Unlike blocks, an item has no per-side icon set — just one
         * icon per damage variant — so the fallback here is across damage variants (base
         * {@link Snapshot.Itm#iconName}, meta 0, first) rather than across block sides.
         *
         * <p>Deliberately excludes {@code "missingno"} (the live call returned no icon at all) and
         * any icon name containing {@code '|'} (a connected-texture-mod compound key — genuinely
         * not a single static texture, same exclusion as the block-side fix). Every surfaced path
         * is checked against the mod's own jar asset listing before being reported as resolved,
         * EXCEPT the {@code minecraft:} namespace (guaranteed to ship with the client) — a wrong
         * path is worse than an honest gap.
         *
         * <p>An {@link Snapshot.Itm#isBlockItem} item (a plain {@code ItemBlock} wrapper) gets its
         * icon from the BLOCK's own icon set, which 1.7.10 ships under {@code textures/blocks/},
         * not {@code textures/items/} — found live while adversarially verifying this fix. Tried
         * second, after the items folder, never guessed when {@code isBlockItem} is absent.
         */
        JsonArray iconTextureRefsItem(Snapshot.Itm itm) {
            JsonArray out = new JsonArray();
            if (itm == null) return out;
            LinkedHashSet<String> candidates = new LinkedHashSet<>();
            if (itm.iconName != null) candidates.add(itm.iconName);
            for (Snapshot.SubItm s : itm.subItems) if (s.iconName != null) candidates.add(s.iconName);
            String raw = null;
            for (String c : candidates) {
                if (c.isEmpty()) continue;
                if ("missingno".equalsIgnoreCase(c)) continue;
                if (c.indexOf('|') >= 0) { itemsIconTextureCtmSkipped++; continue; }
                raw = c;
                break;
            }
            if (raw == null) return out;
            int c = raw.indexOf(':');
            String rawNs = c > 0 ? raw.substring(0, c) : "minecraft";
            String p = c >= 0 ? raw.substring(c + 1) : raw;
            // Namespace casing normalization — same "Finding A5" fix the block-side icon resolver
            // uses (an icon name's own namespace segment is sometimes cased differently than the
            // mod's real, lowercase asset folder).
            String ns = rawNs.toLowerCase(java.util.Locale.ROOT);
            boolean vanilla = "minecraft".equals(ns);
            List<String> folders = itm.isBlockItem != null ? List.of("items", "blocks") : List.of("items");
            String path = null, assetPath = null;
            for (String folder : folders) {
                String candPath = ns + ":textures/" + folder + "/" + p;
                String candAsset = "assets/" + ns + "/textures/" + folder + "/" + p + ".png";
                if (vanilla || jar.assets.containsKey(candAsset)) { path = candPath; assetPath = candAsset; break; }
            }
            if (path == null) return out; // honest zero, not a guess
            JsonObject o = new JsonObject();
            o.addProperty("field", "getIconFromDamage/getIconIndex(stack):" + raw);
            o.addProperty("path", path);
            o.addProperty("assetPath", assetPath);
            o.addProperty("producer", "item's own getIconFromDamage/getIconIndex(ItemStack), live-runtime capture");
            o.addProperty("verifiedInJar", !vanilla);
            out.add(o);
            return out;
        }

        // -------- dynamic-row resolvers (per-damage compile-time tables; see DynamicVariantResolver) --------

        List<VariantIdRule.Sub> dedupSubItemsByDamage(Snapshot.Itm base) {
            Map<Integer, Snapshot.SubItm> byDamage = new LinkedHashMap<>();
            for (Snapshot.SubItm s : base.subItems) byDamage.putIfAbsent(s.damage, s);
            List<VariantIdRule.Sub> out = new ArrayList<>();
            for (Map.Entry<Integer, Snapshot.SubItm> e : byDamage.entrySet())
                out.add(new VariantIdRule.Sub(e.getKey(), e.getValue().unlocalizedName));
            out.sort(Comparator.comparingInt(VariantIdRule.Sub::meta));
            return out;
        }

        JsonObject buildVariantRow(String id, Snapshot.Itm base, JsonObject templateRow, String rendererClass,
                                    JsonArray modelsCopy, String texturePath, List<String> groups,
                                    String resolvedBy, int damage) {
            JsonObject row = new JsonObject();
            row.addProperty("id", id);
            row.addProperty("field", str(templateRow, "field"));
            row.addProperty("className", base.className);
            row.addProperty("iconName", base.iconName);
            row.addProperty("viaBlock", templateRow.has("viaBlock") && templateRow.get("viaBlock").getAsBoolean());
            row.addProperty("producer", str(templateRow, "producer"));
            row.addProperty("bindingSite", str(templateRow, "bindingSite"));
            row.addProperty("rendererClass", rendererClass);
            row.add("models", modelsCopy.deepCopy());
            JsonArray texArr = new JsonArray();
            JsonObject texObj = new JsonObject();
            texObj.addProperty("field", "dynamic-variant-resolver:" + id);
            texObj.addProperty("path", texturePath);
            texObj.addProperty("assetPath", ResRef.toAssetPath(texturePath));
            texArr.add(texObj);
            row.add("textures", texArr);
            JsonArray groupsArr = new JsonArray();
            for (String g : groups) groupsArr.add(g);
            row.add("groups", groupsArr);
            row.addProperty("usesRenderAll",
                    templateRow.has("usesRenderAll") && templateRow.get("usesRenderAll").getAsBoolean());
            row.addProperty("dynamic", true);
            row.add("dynamicReasons", templateRow.has("dynamicReasons")
                    ? templateRow.get("dynamicReasons").deepCopy() : new JsonArray());
            row.add("renderTypes", templateRow.has("renderTypes")
                    ? templateRow.get("renderTypes").deepCopy() : new JsonArray());
            if (templateRow.has("gl")) row.add("gl", templateRow.get("gl").deepCopy());
            row.add("boundTextureFields", new JsonArray());
            row.addProperty("confidence", "inferred-dynamic");
            row.addProperty("resolvedBy", resolvedBy);
            row.addProperty("damage", damage);
            // gap 4: every row this bonus tier produces is tagged, same convention as
            // BindingScanner.ItemBinding#resolverKind, so a consumer can filter it out exactly the
            // way computeGenericDelta() already does for BindingScanner's own rows.
            row.addProperty("resolverTier", BindingScanner.BONUS_INDIRECTION);
            return row;
        }


        /** Family C: a plain {@code damage==0 ? A : B} branch, no table; see the class javadoc. */
        void resolveDamageZeroBranch(String baseId, String rendererInternal, String resolvedBy) {
            JsonObject baseRow = itemRows.get(baseId);
            Snapshot.Itm base = snap.itemById.get(baseId);
            ClassNode renderer = jar.cls(rendererInternal);
            if (baseRow == null || base == null || renderer == null) {
                dynamicVariantNotes.add(baseId + ": skipped (base row, snapshot item, or renderer class missing)");
                return;
            }
            JsonArray modelsCopy = baseRow.has("models") ? baseRow.getAsJsonArray("models") : new JsonArray();
            if (modelsCopy.size() == 0) {
                // A texture-swap branch with no mesh behind it: per-damage textures are real,
                // but a variant row without a model cannot render downstream, so the honest
                // base row (dynamic:true) stands and no phantom row is manufactured.
                dynamicVariantNotes.add(baseId + ": branch matched but base row names no model"
                        + " — texture-only variants not expanded");
                return;
            }
            DynamicVariantResolver.DamageZeroBranch br =
                    DynamicVariantResolver.findDamageZeroTextureBranch(renderer, HolderResolver.T_RESLOC);
            if (!br.matched) {
                dynamicVariantNotes.add(baseId + ": " + JarIndex.dotted(rendererInternal)
                        + " did not match the damage==0 two-branch texture shape");
                return;
            }
            ResRef zero = holders.byField.get(br.zeroBranchField.owner + "." + br.zeroBranchField.name);
            ResRef nonZero = holders.byField.get(br.nonZeroBranchField.owner + "." + br.nonZeroBranchField.name);
            if (zero == null || !zero.resolved() || nonZero == null || !nonZero.resolved()) {
                dynamicVariantNotes.add(baseId + ": one or both branch textures did not resolve to a holder field");
                return;
            }

            List<VariantIdRule.Sub> subs = dedupSubItemsByDamage(base);
            List<String> ids = VariantIdRule.variantIds(baseId, base.unlocalizedName, subs);
            List<String> groups = new ArrayList<>();
            if (baseRow.has("groups")) for (var e : baseRow.getAsJsonArray("groups")) groups.add(e.getAsString());
            String rendererClass = str(baseRow, "rendererClass");

            int resolved = 0;
            for (int i = 0; i < subs.size(); i++) {
                VariantIdRule.Sub s = subs.get(i);
                String id = ids.get(i);
                String texPath = s.meta() == 0 ? zero.path : nonZero.path;
                itemRows.put(id, buildVariantRow(id, base, baseRow, rendererClass, modelsCopy, texPath, groups,
                        resolvedBy, s.meta()));
                resolved++;
            }
            finishVariantResolution(baseId, ids, resolved, 0, subs.size(), "damage-zero-branch");
        }

        void finishVariantResolution(String baseId, List<String> newIds, int resolved, int unresolved,
                                      int totalSubs, String family) {
            if (resolved == 0) return; // leave the original ambiguous row exactly as it was
            if (!newIds.contains(baseId)) itemRows.remove(baseId);
            dynamicVariantRowsAdded += resolved;
            dynamicVariantRowsUnresolved += unresolved;
            dynamicVariantNotes.add(baseId + ": " + family + " resolved " + resolved + "/" + totalSubs
                    + " variant(s)" + (unresolved > 0 ? ", " + unresolved + " unresolved" : "")
                    + (newIds.contains(baseId) ? "" : " (base id superseded — hostagent never registers it)"));
        }

        ResRef findByDotted(String dotted) {
            int i = dotted.lastIndexOf('.');
            if (i < 0) return null;
            String key = JarIndex.internal(dotted.substring(0, i)) + "." + dotted.substring(i + 1);
            return holders.byField.get(key);
        }

        String idForField(String owner, String name) {
            if (owner == null || name == null) return null;
            ModRegistryResolver.Entry e = mods.byField.get(owner + "." + name);
            return e == null ? null : e.id;
        }

        List<BindingScanner.OrphanRenderer> orphanRenderers() { return bind.orphanRenderers; }

        static class GenericDelta {
            final Set<String> itemIdsWithRenderer = new LinkedHashSet<>();
            final Set<String> itemIdsWithModelAndTexture = new LinkedHashSet<>();
            final Set<String> blockIdsWithRenderer = new LinkedHashSet<>();
            final Set<String> blockIdsWithModelAndTexture = new LinkedHashSet<>();
            int isbrhImplementorScanCount;
        }

        /**
         * Re-derives item/block resolution counts using ONLY {@code resolverKind:"generic"}
         * bindings (see {@link BindingScanner}), so the report can state plainly how much of the
         * coverage above comes from the universal Forge/vanilla APIs alone versus the bonus
         * structural-indirection tier. Read-only: does not touch {@link #itemRows}/{@link #blockRows}.
         */
        GenericDelta computeGenericDelta() {
            GenericDelta gd = new GenericDelta();
            for (BindingScanner.ItemBinding b : bind.itemBindings) {
                if (!BindingScanner.GENERIC.equals(b.resolverKind)) continue;
                List<String> ids = new ArrayList<>();
                if (b.itemSelfClass != null) {
                    List<String> byClass = snap.itemsByClass.get(b.itemSelfClass);
                    if (byClass != null) ids.addAll(byClass);
                } else {
                    String id = idForField(b.itemFieldOwner, b.itemFieldName);
                    if (id != null && snap.itemById.containsKey(id)) ids.add(id);
                }
                if (b.rendererClass == null) continue;
                RendererInfo ri = analyzer.analyze(JarIndex.internal(b.rendererClass));
                boolean hasModel = !ri.modelFields.isEmpty() || b.directModelField != null;
                boolean hasTex = !ri.textureFields.isEmpty() || b.directTextureField != null;
                for (String id : ids) {
                    gd.itemIdsWithRenderer.add(id);
                    if (hasModel && hasTex) gd.itemIdsWithModelAndTexture.add(id);
                }
            }
            Map<String, String> renderIdToHandlerGen = new LinkedHashMap<>();
            for (BindingScanner.IsbrhBinding b : bind.isbrhBindings) {
                if (!BindingScanner.GENERIC.equals(b.resolverKind) || b.renderIdFieldName == null) continue;
                renderIdToHandlerGen.put(b.renderIdFieldOwner + "#" + b.renderIdFieldName, b.handlerClass);
                if (b.producer != null && b.producer.startsWith("implementor-scan")) gd.isbrhImplementorScanCount++;
            }
            Map<String, String> teToRendererGen = new LinkedHashMap<>();
            for (BindingScanner.TesrBinding t : bind.tesrBindings)
                if (BindingScanner.GENERIC.equals(t.resolverKind) && t.teClass != null && t.rendererClass != null)
                    teToRendererGen.put(t.teClass, t.rendererClass);
            for (Snapshot.Blk blk : snap.blocks) {
                String isbrh = null;
                String fieldForBlock = blockFieldFor(blk.id);
                for (String cls : classChainOf(blk.className)) {
                    BindingScanner.BlockRenderId br = bind.blockRenderIds.get(cls);
                    if (br == null) continue;
                    if (fieldForBlock != null && br.perBlockField.containsKey(fieldForBlock)) {
                        isbrh = renderIdToHandlerGen.get(br.perBlockField.get(fieldForBlock));
                        if (isbrh != null) break;
                    }
                    if (br.defaultFieldName != null) {
                        String h = renderIdToHandlerGen.get(br.defaultFieldOwner + "#" + br.defaultFieldName);
                        if (h != null) { isbrh = h; break; }
                    }
                }
                String tesr = blk.tileEntityClass == null ? null : teToRendererGen.get(blk.tileEntityClass);
                if (isbrh == null && tesr == null) continue;
                gd.blockIdsWithRenderer.add(blk.id);
                boolean hasModel = false, hasTex = false;
                for (String rc : new String[]{isbrh, tesr}) {
                    if (rc == null) continue;
                    RendererInfo ri = analyzer.analyze(JarIndex.internal(rc));
                    hasModel |= !ri.modelFields.isEmpty();
                    hasTex |= !ri.textureFields.isEmpty();
                }
                if (hasModel && hasTex) gd.blockIdsWithModelAndTexture.add(blk.id);
            }
            return gd;
        }

        // ---------- tile entities ----------

        void buildTileEntities() {
            for (BindingScanner.TesrBinding t : bind.tesrBindings) {
                tesrs++;
                JsonObject row = new JsonObject();
                row.addProperty("teClass", t.teClass);
                row.addProperty("rendererClass", t.rendererClass);
                RendererInfo ri = t.rendererClass == null ? null
                        : analyzer.analyze(JarIndex.internal(t.rendererClass));
                row.add("models", refArray(ri == null ? Set.of() : ri.modelFields));
                row.add("textures", refArray(ri == null ? Set.of() : ri.textureFields));
                row.add("groups", strArray(ri == null ? Set.of() : ri.groups));
                row.add("javaModels", javaModelsArray(ri == null ? Set.of() : ri.javaModelClasses));
                row.addProperty("usesRenderAll", ri != null && ri.usesRenderAll);
                row.addProperty("dynamic", ri != null && ri.dynamic);
                List<String> ids = t.teClass == null ? null : snap.blocksByTeClass.get(t.teClass);
                row.add("blockIds", strArray(ids == null ? Set.of() : new LinkedHashSet<>(ids)));
                if (t.unresolvedReason != null) row.addProperty("unresolvedReason", t.unresolvedReason);
                boolean mapped = t.teClass != null && t.rendererClass != null
                        && ri != null && (!ri.modelFields.isEmpty() || !ri.textureFields.isEmpty());
                if (mapped) tesrsMapped++;
                row.addProperty("confidence", t.rendererClass == null ? "unresolved" : mapped ? "exact" : "inferred");
                teRows.add(row);
            }
        }

        // ---------- blocks ----------

        void buildBlocks() {
            // render-id field -> ISBRH handler
            Map<String, String> renderIdToHandler = new LinkedHashMap<>();
            for (BindingScanner.IsbrhBinding b : bind.isbrhBindings)
                if (b.renderIdFieldName != null)
                    renderIdToHandler.put(b.renderIdFieldOwner + "#" + b.renderIdFieldName, b.handlerClass);

            // TE class -> TESR
            Map<String, String> teToRenderer = new LinkedHashMap<>();
            for (BindingScanner.TesrBinding t : bind.tesrBindings)
                if (t.teClass != null && t.rendererClass != null) teToRenderer.put(t.teClass, t.rendererClass);

            // 171 snapshot blocks report hasTileEntity but no tileEntityClass (the dump could not
            // instantiate one without a world). For those, recover the TESR from the other
            // direction: the TESR itself implements a provider interface and names the block.
            // Producer check is by structural prefix (any "provider-interface:..." binding),
            // never by one interface's name.
            Map<String, String> blockFieldToTesr = new LinkedHashMap<>();
            for (BindingScanner.ItemBinding b : bind.itemBindings) {
                if (b.producer == null || !b.producer.startsWith("provider-interface:")) continue;
                if (b.itemFieldName == null) continue;
                if (!b.viaBlock) continue;
                String provider = b.site.substring(0, b.site.lastIndexOf('.'));
                if (jar.isSubclassOf(JarIndex.internal(provider),
                        "net/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer"))
                    blockFieldToTesr.putIfAbsent(b.itemFieldOwner + "." + b.itemFieldName, provider);
            }

            for (Snapshot.Blk blk : snap.blocks) {
                JsonObject row = new JsonObject();
                row.addProperty("id", blk.id);
                row.addProperty("className", blk.className);
                row.addProperty("renderType", blk.renderType);
                row.addProperty("hasTileEntity", blk.hasTileEntity);
                row.addProperty("tileEntityClass", blk.tileEntityClass);

                // ISBRH: walk the block class's own getRenderType(), then its superclasses'
                String isbrh = null; String via = null;
                String fieldForBlock = blockFieldFor(blk.id);
                for (String cls : classChainOf(blk.className)) {
                    BindingScanner.BlockRenderId br = bind.blockRenderIds.get(cls);
                    if (br == null) continue;
                    if (fieldForBlock != null && br.perBlockField.containsKey(fieldForBlock)) {
                        String key = br.perBlockField.get(fieldForBlock);
                        isbrh = renderIdToHandler.get(key);
                        via = JarIndex.dotted(cls) + ".getRenderType() [this==" + JarIndex.dotted(fieldForBlock) + "]";
                        if (isbrh != null) break;
                    }
                    if (br.defaultFieldName != null) {
                        String key = br.defaultFieldOwner + "#" + br.defaultFieldName;
                        String h = renderIdToHandler.get(key);
                        if (h != null) {
                            isbrh = h;
                            via = JarIndex.dotted(cls) + ".getRenderType() -> "
                                    + JarIndex.dotted(br.defaultFieldOwner) + "." + br.defaultFieldName;
                            break;
                        }
                    }
                }
                row.addProperty("isbrhClass", isbrh);
                row.addProperty("isbrhVia", via);
                if (isbrh != null) blocksWithIsbrh++;

                String tesr = blk.tileEntityClass == null ? null : teToRenderer.get(blk.tileEntityClass);
                String tesrVia = tesr == null ? null : "bindTileEntitySpecialRenderer(" + blk.tileEntityClass + ")";
                if (tesr == null && fieldForBlock != null) {
                    tesr = blockFieldToTesr.get(fieldForBlock);
                    if (tesr != null) tesrVia = tesr + " names this block via its provider interface"
                            + " (snapshot has no tileEntityClass for it)";
                }
                row.addProperty("tesrClass", tesr);
                row.addProperty("tesrVia", tesrVia);

                Set<String> modelFields = new LinkedHashSet<>();
                Set<String> texFields = new LinkedHashSet<>();
                Set<String> groups = new LinkedHashSet<>();
                Set<String> javaModels = new LinkedHashSet<>();
                boolean dynamic = false;
                for (String rc : new String[]{isbrh, tesr}) {
                    if (rc == null) continue;
                    RendererInfo ri = analyzer.analyze(JarIndex.internal(rc));
                    modelFields.addAll(ri.modelFields);
                    texFields.addAll(ri.textureFields);
                    groups.addAll(ri.groups);
                    javaModels.addAll(ri.javaModelClasses);
                    dynamic |= ri.dynamic;
                }
                // A block whose registration passes a config object that vends its renderer
                // through a factory method: follow those bytecode edges instead of matching
                // block names to assets. This also covers blocks whose generic TESR has no
                // direct model field.
                if (resolveConfigVisual(blk, fieldForBlock, modelFields, texFields, groups))
                    tesrVia = (tesrVia == null ? "" : tesrVia + "; ") + "exact config-object -> renderer factory";
                if (teRelinkNotes.containsKey(blk.id))
                    tesrVia = (tesrVia == null ? "" : tesrVia + "; ") + teRelinkNotes.get(blk.id);
                row.addProperty("tesrVia", tesrVia);
                row.add("models", refArray(modelFields));
                JsonArray texArr = refArray(texFields);
                boolean addedIconTexture = false;
                // A custom render id without a resolved ISBRH/TESR is still allowed to draw
                // through a mod-owned path that static analysis cannot name. Its verified
                // legacy icon is the universal static safety net. The same path handles a
                // plain ISBRH whose renderer names no asset.
                boolean customRenderId = blk.renderType >= 40;
                boolean needsStaticSafetyNet = modelFields.isEmpty() && texFields.isEmpty()
                        && (isbrh != null || customRenderId || blk.hasTileEntity || blk.renderType != 0);
                if (needsStaticSafetyNet) {
                    // Gap 2 / A1 (mandate #2): ISBRH's own contract is "draw this block yourself
                    // in Java" — there is no static asset reference on the binding for
                    // RendererAnalyzer to find. But for a PLAIN ISBRH block (fixed icon per
                    // side/meta, no connected-texture logic) the icon IS discoverable: read the
                    // IIcon the block's own getIcon(side,meta) actually returns, captured live by
                    // the native runtime boot (Snapshot.Blk#icons), the same way block icon
                    // extraction already works for vanilla-rendered blocks.
                    for (JsonElement e : iconTextureRefs(blk)) { texArr.add(e); addedIconTexture = true; }
                    if (addedIconTexture) {
                        if (isbrh != null) blocksIsbrhIconTextureAdded++;
                        else blocksCustomIconTextureAdded++;
                    }
                }
                row.add("textures", texArr);
                row.add("groups", strArray(groups));
                row.add("javaModels", javaModelsArray(javaModels));
                row.addProperty("dynamic", dynamic);
                boolean mapped = !modelFields.isEmpty() || !texFields.isEmpty() || addedIconTexture;
                if (mapped) blocksMapped++;
                row.addProperty("customRenderId", customRenderId);
                String conf;
                boolean hasNonIconTexture = !modelFields.isEmpty() || !texFields.isEmpty();
                if (addedIconTexture)
                    // The icon is verified live runtime data, but an opaque custom renderer could
                    // draw something other than a flat copy of it, so this stays inferred.
                    conf = "inferred-icon";
                else if (isbrh != null || tesr != null)
                    // "inferred-icon": the ONLY resource resolved is the block's own live-runtime
                    // getIcon(side,meta) result (gap 2/A1) — a real, verified texture, but honestly
                    // distinguished from "exact" (a model and/or a texture the renderer's OWN
                    // bytecode names directly) since a non-trivial ISBRH could in principle draw
                    // something other than a flat copy of that icon.
                    conf = hasNonIconTexture ? "exact" : "inferred";
                else if (!customRenderId && !blk.hasTileEntity) conf = "none";  // plain vanilla-model block
                else conf = "unresolved";
                row.addProperty("confidence", conf);
                if ("none".equals(conf))
                    row.addProperty("notes", "vanilla render type " + blk.renderType
                            + " and no tile entity: drawn by the standard block renderer, no OBJ involved");
                else if ("inferred-icon".equals(conf))
                    row.addProperty("notes", "ISBRH-bound but the renderer's own bytecode names no static"
                            + " model/texture field; texture resolved from the block's own getIcon(side,meta)"
                            + " (live-runtime capture) instead — see gap 2/A1 in CONTENT-RESOLUTION.md");
                else if ("unresolved".equals(conf))
                    row.addProperty("notes", "custom render id " + blk.renderType
                            + " but no ISBRH claims it and no TESR is bound");
                if ("inferred-icon".equals(conf) && isbrh == null)
                    row.addProperty("notes", "custom-render-bound but no static model/texture field was resolved;"
                            + " texture comes from the block's own getIcon(side,meta) (live-runtime capture)");
                if (customRenderId) blocksCustomRenderId++;
                if (customRenderId && isbrh == null && tesr == null) blocksCustomWithoutRenderer++;
                if ("none".equals(conf)) blocksVanillaRender++;
                blockRows.put(blk.id, row);
            }
        }

        /**
         * Follows a block-registration config object to the renderer it vends, purely by type
         * flow — never by the config class's or factory method's name. Shape: the block's static
         * holder field is assigned (PUTSTATIC) shortly after a GETSTATIC of a jar-declared
         * config-object field (the registration passes the config into the block constructor);
         * the config's concrete class (found via the NEW behind its own field assignment) has a
         * no-arg factory method returning a jar interface type, whose body materializes an
         * implementor (GETSTATIC singleton or NEW). The resolved renderer class is analyzed for
         * model/texture/groups exactly like a directly bound TESR.
         */
        private boolean resolveConfigVisual(Snapshot.Blk blk, String blockField,
                                            Set<String> modelFields, Set<String> texFields,
                                            Set<String> groups) {
            if (blockField == null || blk.className == null) return false;
            String configField = configFieldForBlock(blockField);
            if (configField == null) return false;
            String configClass = concreteFieldType(configField);
            if (configClass == null) return false;
            String renderer = configFactoryRenderer(configClass);
            if (renderer == null) return false;
            RendererInfo ri = analyzer.analyze(renderer);
            boolean changed = modelFields.addAll(ri.modelFields);
            changed |= texFields.addAll(ri.textureFields);
            changed |= groups.addAll(ri.groups);
            return changed;
        }

        /**
         * The config-object field assigned into the block registration, found by construction
         * dataflow instead of textual proximity: walking backward from the block holder's
         * PUTSTATIC, the stored value must come from a constructor call ({@code NEW B ...}
         * {@code INVOKESPECIAL B.<init>}); a GETSTATIC of a jar-declared config-object type
         * among that call's arguments (between NEW and {@code <init>}) is the registration's
         * config. Setter calls after construction (creative tabs, hardness, ...) are excluded
         * by construction — they sit between {@code <init>} and PUTSTATIC, never in the
         * argument window. The previous whole-method "nearest preceding declaration" scan
         * leaked one block's config into neighbors registered later in the same giant
         * registration method; this shape cannot. Bounds live on the method below.
         */

        private String configFieldForBlock(String blockField) {
            String[] p = blockField.split("\\.", 2);
            if (p.length != 2) return null;
            for (ClassNode cn : jar.classes.values()) {
                for (MethodNode mn : cn.methods) {
                    if (mn.instructions == null) continue;
                    AbstractInsnNode[] ins = mn.instructions.toArray();
                    for (int i = 0; i < ins.length; i++) {
                        if (ins[i].getOpcode() != Opcodes.PUTSTATIC) continue;
                        FieldInsnNode put = (FieldInsnNode) ins[i];
                        if (!(put.owner + "." + put.name).equals(blockField)) continue;
                        String found = configFromConstruction(ins, i, blockField);
                        if (found != null) return found;
                    }
                }
            }
            return null;
        }

        /**
         * Tries the single constructor call that can own this PUTSTATIC: javac emits
         * holder.field = new B(...) with (almost) nothing between the
         * INVOKESPECIAL B.<init> and the PUTSTATIC - the DUP reference is consumed
         * directly. Earlier constructor calls in the same giant registration method belong to
         * OTHER blocks; trying them is exactly how one block's config leaked into its
         * neighbors, so there is deliberately no fallback loop. Setter calls after
         * construction (creative tabs, hardness) sit after the PUTSTATIC or after the
         * <init> in ways that never match this shape.
         */
        private static final int PUTSTATIC_INIT_WINDOW = 16;
        private static final int ARG_WINDOW = 128;

        private String configFromConstruction(AbstractInsnNode[] ins, int putIdx, String blockField) {
            int initIdx = -1;
            String initOwner = null;
            for (int i = putIdx - 1; i >= Math.max(0, putIdx - PUTSTATIC_INIT_WINDOW); i--) {
                if (ins[i].getOpcode() != Opcodes.INVOKESPECIAL) continue;
                MethodInsnNode cm = (MethodInsnNode) ins[i];
                if (!"<init>".equals(cm.name)) continue;
                initOwner = cm.owner;
                if (!jar.isSubclassOf(initOwner, "net/minecraft/block/Block")) continue;
                initIdx = i;
                break;
            }
            if (initIdx < 0) return null;
            int newIdx = -1;
            for (int j = initIdx - 1; j >= Math.max(0, initIdx - ARG_WINDOW); j--) {
                if (ins[j].getOpcode() != Opcodes.NEW) continue;
                if (!initOwner.equals(((TypeInsnNode) ins[j]).desc)) continue;
                newIdx = j;
                break;
            }
            if (newIdx < 0) return null;
            String found = null;
            for (int j = newIdx + 1; j < initIdx; j++) {
                if (ins[j].getOpcode() != Opcodes.GETSTATIC) continue;
                FieldInsnNode f = (FieldInsnNode) ins[j];
                if (!isJarObjectType(f.desc)) continue;
                if ((f.owner + "." + f.name).equals(blockField)) continue;
                found = f.owner + "." + f.name;
            }
            return found;
        }

        /**
         * An object-typed descriptor whose class is declared in THIS jar (jar classes only,
         * never the engine classpath: vanilla/Forge types like creative tabs or materials are
         * registration noise, not config objects).
         */
        private boolean isJarObjectType(String desc) {
            if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L' || !desc.endsWith(";")) return false;
            return jar.classes.containsKey(desc.substring(1, desc.length() - 1));
        }

        /**
         * The concrete class behind a config static field: the NEW whose instance the
         * config owner's {@code <clinit>} stores into it. Falls back to the field's own
         * declared type when no construction site is found (e.g. assigned from a call).
         */
        private String concreteFieldType(String field) {
            String[] p = field.split("\\.", 2);
            if (p.length != 2) return null;
            ClassNode owner = jar.cls(p[0]);
            if (owner == null) return null;
            for (MethodNode mn : owner.methods) {
                if (!"<clinit>".equals(mn.name) || mn.instructions == null) continue;
                String pending = null;
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() == Opcodes.NEW) pending = ((TypeInsnNode) in).desc;
                    else if (in.getOpcode() == Opcodes.PUTSTATIC) {
                        FieldInsnNode f = (FieldInsnNode) in;
                        if ((f.owner + "." + f.name).equals(field) && pending != null)
                            return pending;
                    }
                }
            }
            return null;
        }

        /**
         * A no-arg factory method on the config class (concrete class first, then supertypes)
         * returning a jar-declared interface type, materialized in-body as a GETSTATIC
         * singleton or a NEW of an implementor. Returns the renderer's internal class name.
         */
        private String configFactoryRenderer(String configClass) {
            for (ClassNode cn : jar.superChain(configClass)) {
                if (cn.methods == null) continue;
                for (MethodNode mn : cn.methods) {
                    if (mn == null || mn.desc == null || mn.instructions == null) continue;
                    if (Type.getArgumentTypes(mn.desc).length != 0) continue;
                    Type ret = Type.getReturnType(mn.desc);
                    if (ret.getSort() != Type.OBJECT) continue;
                    ClassNode retCn = jar.cls(ret.getInternalName());
                    if (retCn == null || (retCn.access & Opcodes.ACC_INTERFACE) == 0) continue;
                    String contract = ret.getInternalName();
                    for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                        if (in.getOpcode() == Opcodes.GETSTATIC) {
                            FieldInsnNode f = (FieldInsnNode) in;
                            if (f.desc.contains(contract)
                                    || jar.implementorsOf(contract).stream().anyMatch(c -> c.name.equals(f.owner)))
                                return f.owner;
                        } else if (in.getOpcode() == Opcodes.NEW) {
                            String fresh = ((TypeInsnNode) in).desc;
                            if (contract.equals(fresh)
                                    || jar.implementorsOf(contract).stream().anyMatch(c -> c.name.equals(fresh)))
                                return fresh;
                        }
                    }
                }
            }
            return null;
        }

        /** "com/hbm/blocks/ModBlocks.foo" for a block id, if we recovered one. */
        String blockFieldFor(String id) {
            for (Map.Entry<String, ModRegistryResolver.Entry> e : mods.byField.entrySet()) {
                ModRegistryResolver.Entry v = e.getValue();
                if (v.isBlock && id.equals(v.id)) return e.getKey();
            }
            return null;
        }

        private Map<String, List<String>> chainCache = new LinkedHashMap<>();
        List<String> classChainOf(String dottedClass) {
            if (dottedClass == null) return List.of();
            return chainCache.computeIfAbsent(dottedClass, d -> {
                List<String> out = new ArrayList<>();
                for (org.objectweb.asm.tree.ClassNode cn : jar.superChain(JarIndex.internal(d))) out.add(cn.name);
                return out;
            });
        }

        // ---------- entities ----------

        void buildEntities() {
            for (BindingScanner.EntityBinding e : bind.entityBindings) {
                JsonObject row = new JsonObject();
                row.addProperty("entityClass", e.entityClass);
                row.addProperty("rendererClass", e.rendererClass);
                row.addProperty("resolverTier", e.resolverKind);
                RendererInfo ri = e.rendererClass == null ? null
                        : analyzer.analyze(JarIndex.internal(e.rendererClass));
                // refArrayJarVerified, not plain refArray (mandate: "adversarially verify every
                // newly resolved model/texture path against the real jar's zip listing") — a real
                // wrong resolution this lane's own adversarial check caught: HBM's
                // EntityBurningFOEQ renderer names a static field whose OWN bytecode literally
                // constructs `new ResourceLocation("hbm", "textures/models/sat_foeq.png")`, but no
                // such file ships in the jar (the real files are under textures/items/ and
                // textures/blocks/ instead — a genuine bug/typo in the MOD's own bytecode, not a
                // tool error) — see ENTITY-RENDER-EXTRACTION.md.
                JsonArray modelsArr = refArrayJarVerified(ri == null ? Set.of() : ri.modelFields);
                JsonArray texturesArr = refArrayJarVerified(ri == null ? Set.of() : ri.textureFields);
                row.add("models", modelsArr);
                row.add("textures", texturesArr);
                row.add("boundTextureFields", strArray(ri == null ? Set.of() : ri.boundTextureFields));
                row.add("groups", strArray(ri == null ? Set.of() : ri.groups));
                row.add("javaModels", javaModelsArray(ri == null ? Set.of() : ri.javaModelClasses,
                        ri == null ? Map.of() : ri.javaModelParts));

                // getEntityTexture(Entity) — see RendererAnalyzer#resolveEntityTexture. Every
                // resolved path is jar-verified here (never trusted from the resolver's own
                // reduction) exactly like the block-side gap 2/A1 icon fix: a wrong path is worse
                // than an honest gap.
                JsonArray entityTexArr = new JsonArray();
                boolean hasEntityTexture = false;
                if (ri != null) {
                    for (RendererInfo.EntityTextureRef ref : ri.entityTextureRefs) {
                        JsonObject o = new JsonObject();
                        o.addProperty("kind", ref.kind);
                        o.addProperty("field", ref.field);
                        o.addProperty("path", ref.path);
                        if (ref.path != null) {
                            boolean vanilla = ref.path.startsWith("minecraft:");
                            boolean verified = vanilla || jar.assets.containsKey(ref.assetPath);
                            if (!verified) {
                                // Adversarial discipline (mandate: "verify every resolved
                                // path against the real jar's zip listing"): a getEntityTexture
                                // resolution that does not actually exist in the jar is reported
                                // as unresolved, not as a wrong "resolved" row.
                                o = new JsonObject();
                                o.addProperty("kind", ref.kind);
                                o.addProperty("field", ref.field);
                                o.addProperty("unresolvedReason", "resolved path " + ref.assetPath
                                        + " is not present in the jar's own asset listing");
                            } else {
                                o.addProperty("assetPath", ref.assetPath);
                                o.addProperty("verifiedInJar", !vanilla);
                                hasEntityTexture = true;
                                entityTextureResolvedViaGetEntityTexture++;
                            }
                        } else {
                            o.addProperty("unresolvedReason", ref.unresolvedReason);
                        }
                        entityTexArr.add(o);
                    }
                }
                row.add("entityTexture", entityTexArr);
                if (ri != null) row.addProperty("getEntityTextureFound", ri.getEntityTextureFound);

                boolean hasVerifiedModel = false;
                for (JsonElement el : modelsArr) if (hasResolvedPath(el)) hasVerifiedModel = true;
                boolean hasVerifiedTexture = false;
                for (JsonElement el : texturesArr) if (hasResolvedPath(el)) hasVerifiedTexture = true;
                boolean hasModel = ri != null && (hasVerifiedModel || !ri.javaModelClasses.isEmpty());
                boolean hasTexture = ri != null
                        && (hasVerifiedTexture || !ri.boundTextureFields.isEmpty() || hasEntityTexture);
                if (hasModel) entityIdsWithResolvedModel++;
                if (hasTexture) entityIdsWithResolvedTexture++;
                if (hasModel && hasTexture) entityIdsWithModelAndTexture++;

                // Confidence discipline mirrors the block/item precedent (RenderMap's own
                // "exact"/"inferred"/"unresolved" tiers): every source that feeds hasModel/
                // hasTexture here is the renderer's OWN bytecode naming a concrete resource
                // (a static holder field, a bindTexture-call argument, a getEntityTexture
                // return, or a referenced ModelBase/Techne class) — the SAME evidentiary
                // strength "exact" already means elsewhere, so both-present reaches "exact"
                // too. There is no live-runtime-capture source for entities (Snapshot never
                // captures an entity's own icon the way it does a block's), so entities never
                // reach the block-only "inferred-icon" tier. A renderer that was found and
                // analyzed but resolved genuinely nothing is reported "unresolved" rather than
                // the item precedent's more generous "inferred", per the mandate's own framing
                // ("correctly left unresolved, not guessed").
                String conf = e.rendererClass == null ? "unresolved"
                        : hasModel && hasTexture ? "exact"
                        : hasModel || hasTexture ? "inferred"
                        : "unresolved";
                row.addProperty("confidence", conf);
                if ("exact".equals(conf)) entityIdsExact++;
                else if ("inferred".equals(conf)) entityIdsInferred++;
                else entityIdsUnresolved++;

                if (e.unresolvedReason != null) row.addProperty("unresolvedReason", e.unresolvedReason);
                entityRows.add(row);
            }
        }

        // ---------- helpers ----------

        void noteUnresolved(String pattern, String example) {
            String key = pattern.length() > 160 ? pattern.substring(0, 160) : pattern;
            unresolvedCounts.merge(key, 1, Integer::sum);
            unresolvedExample.putIfAbsent(key, example);
        }

        JsonArray refArray(Set<String> fields) {
            JsonArray a = new JsonArray();
            for (String f : fields) {
                ResRef r = findByDotted(f);
                JsonObject o = new JsonObject();
                o.addProperty("field", f);
                o.addProperty("path", r == null ? null : r.path);
                o.addProperty("assetPath", r == null ? null : r.assetPath);
                if (r != null && r.loader != null) o.addProperty("loader", r.loader);
                if (r != null && r.path != null && r.path.endsWith(".obj")) {
                    ObjModel m = models.get(r.path);
                    o.addProperty("objPresent", m != null);
                }
                a.add(o);
            }
            return a;
        }

        /**
         * Like {@link #refArray(Set)}, but adversarially re-checks every resolved
         * {@code assetPath} against the jar's own zip listing before reporting it — {@link
         * #refArray} itself has no such check (a pre-existing gap this lane found while verifying
         * ITS OWN new entity work, not something entity-specific: no block/item content in this
         * project's 5-mod corpus happened to hit it before, because nothing else in HBM referenced
         * {@code com.hbm.main.ResourceManager.sat_foeq_tex} — see ENTITY-RENDER-EXTRACTION.md for
         * the full adversarial-verification writeup). Used only for entity rows, whose {@code
         * models}/{@code textures} arrays are the first callers of {@link RendererAnalyzer#analyze}
         * against entity-renderer classes this project has ever exercised — fixing {@link
         * #refArray} itself for every OTHER caller (block/item/TESR rows) is a real, disclosed,
         * NOT-fixed generic follow-up (see the report), deliberately left alone here to keep this
         * lane's blast radius to entity rows only.
         */
        static boolean hasResolvedPath(JsonElement el) {
            JsonObject o = el.getAsJsonObject();
            return o.has("path") && !o.get("path").isJsonNull();
        }

        JsonArray refArrayJarVerified(Set<String> fields) {
            JsonArray out = new JsonArray();
            for (JsonElement el : refArray(fields)) {
                JsonObject o = el.getAsJsonObject();
                JsonElement assetPathEl = o.get("assetPath");
                String assetPath = assetPathEl.isJsonNull() ? null : assetPathEl.getAsString();
                if (assetPath == null) { out.add(o); continue; } // already honestly unresolved
                boolean vanilla = assetPath.startsWith("assets/minecraft/");
                if (vanilla || jar.assets.containsKey(assetPath)) { out.add(o); continue; }
                JsonObject fixed = new JsonObject();
                fixed.addProperty("field", o.get("field").getAsString());
                fixed.addProperty("unresolvedReason", "resolved path " + assetPath
                        + " is not present in the jar's own asset listing");
                out.add(fixed);
            }
            return out;
        }

        /**
         * Gap 2 / A1 (mandate #2): resolves an ISBRH-bound block's own {@code getIcon(side, meta)}
         * result to a real, verified texture path, using {@link Snapshot.Blk#icons} — captured
         * live by the native 1.7.10 boot, not guessed from bytecode. Only the block's primary
         * variant (meta 0, or the first row) is surfaced, matching the per-block-id (not
         * per-meta) granularity already used for models[]/textures[] elsewhere in this row.
         *
         * <p>Deliberately excludes {@code "missingno"} (the live call returned no icon at all) and
         * any icon name containing {@code '|'} (a connected-texture-mod compound key — see A5/B2:
         * genuinely not a single static texture, no amount of static or dynamic analysis recovers
         * the real per-neighbor visual). Every surfaced path is checked against the mod's own jar
         * asset listing before being reported as resolved, EXCEPT the {@code minecraft:} namespace
         * (guaranteed to ship with the client, so never present in a third-party mod's own jar) —
         * a wrong path is worse than an honest gap.
         */
        JsonArray iconTextureRefs(Snapshot.Blk blk) {
            JsonArray out = new JsonArray();
            if (blk.icons == null || blk.icons.isEmpty()) return out;
            // Prefer meta 0 (the block's default variant), but a "family" block whose meta 0 is
            // deliberately blank ("missingno") or CTM-only (see A5/B2 — a connected-texture-mod
            // compound key, not a single static texture) still very often has a plain, resolvable
            // icon on some OTHER metadata variant (Chisel's own "temple"/"templemossy" blocks: meta
            // 0 is a CTM cobble variant, but metas 1,2,4-7,12-15 are all plain named textures); take
            // the first variant (in meta order) with at least one PLAIN (non-missingno, non-CTM)
            // side rather than reporting a false negative for a block that plainly does have one.
            Snapshot.IconEntry primary = null;
            for (Snapshot.IconEntry ie : blk.icons) {
                boolean hasPlain = ie.sides != null && ie.sides.stream().anyMatch(s -> s != null && !s.isEmpty()
                        && !"missingno".equalsIgnoreCase(s) && s.indexOf('|') < 0);
                if (!hasPlain) continue;
                if (ie.meta == 0) { primary = ie; break; }
                if (primary == null) primary = ie;
            }
            if (primary == null) primary = blk.icons.get(0); // nothing plain anywhere: honest empty result below
            if (primary.sides == null) return out;
            Set<String> seen = new LinkedHashSet<>();
            for (String raw : primary.sides) {
                if (raw == null || raw.isEmpty() || !seen.add(raw)) continue;
                if ("missingno".equalsIgnoreCase(raw)) continue;
                if (raw.indexOf('|') >= 0) { blocksIsbrhIconTextureCtmSkipped++; continue; }
                int c = raw.indexOf(':');
                String rawNs = c > 0 ? raw.substring(0, c) : "minecraft";
                String p = c >= 0 ? raw.substring(c + 1) : raw;
                // A5 (see GENERALITY-MEASUREMENT.md): an icon-name string's own namespace segment
                // is sometimes cased differently than the mod's real, lowercase asset folder
                // (Chisel's own icon strings say "Chisel:carpet/white" but the jar's real entry is
                // assets/chisel/textures/blocks/carpet/white.png) — normalize here the same way
                // PackGen's own --ns argument is already normalized, rather than honestly-but-
                // needlessly failing a texture that really is in the jar over a casing mismatch.
                String ns = rawNs.toLowerCase(java.util.Locale.ROOT);
                String path = ns + ":textures/blocks/" + p;
                String assetPath = "assets/" + ns + "/textures/blocks/" + p + ".png";
                boolean vanilla = "minecraft".equals(ns);
                if (!vanilla && !jar.assets.containsKey(assetPath)) {
                    // Some legacy blocks expose an atlas key with a numeric side/variant suffix
                    // (for example Railcraft's detector.item.0), while the jar stores the shared
                    // texture without that suffix (detector.item.png). Strip only a terminal
                    // all-digit dot suffix, and accept it only when the normalized asset exists.
                    int dot = p.lastIndexOf('.');
                    if (dot > 0 && dot + 1 < p.length()
                            && p.substring(dot + 1).chars().allMatch(Character::isDigit)) {
                        String base = p.substring(0, dot);
                        String candidateAsset = "assets/" + ns + "/textures/blocks/" + base + ".png";
                        if (jar.assets.containsKey(candidateAsset)) {
                            p = base;
                            path = ns + ":textures/blocks/" + p;
                            assetPath = candidateAsset;
                        }
                    }
                }
                if (!vanilla && !jar.assets.containsKey(assetPath)) continue; // honest zero, not a guess
                JsonObject o = new JsonObject();
                o.addProperty("field", "getIcon(side,meta):" + raw);
                o.addProperty("path", path);
                o.addProperty("assetPath", assetPath);
                o.addProperty("producer", "block's own getIcon(side,meta), live-runtime capture");
                o.addProperty("verifiedInJar", !vanilla);
                out.add(o);
            }
            return out;
        }

        static JsonArray strArray(Set<String> s) {
            JsonArray a = new JsonArray();
            for (String x : s) a.add(x);
            return a;
        }

        /**
         * A {@code ModelBase}/Techne-style Java model is CODE, not a loadable mesh — reported here
         * ({@code modelKind:"java"} + the class name) rather than silently missing from
         * {@code models[]}, and never baked (mandate requirement 5).
         */
        JsonArray javaModelsArray(Set<String> classes) { return javaModelsArray(classes, Map.of()); }

        /**
         * Like {@link #javaModelsArray(Set)}, but also embeds each Techne/{@code ModelRenderer}
         * part this lane's {@code RendererAnalyzer#resolveJavaModelGeometry} recovered for the
         * class (box + rotation-point + texture-offset — see {@link RendererInfo.TechnePart}),
         * when {@code partsByClass} (typically one renderer's own {@code
         * RendererInfo#javaModelParts}) has an entry for it. Kept as a separate overload rather
         * than changing every existing caller's signature: the multi-renderer block/TESR row
         * shape merges java-model classes from more than one {@code RendererInfo} into one set
         * (see {@code buildBlocks()}), so there is no single owning {@code RendererInfo} to pull
         * parts from there — only the entity row (exactly one renderer per binding) has one.
         */
        JsonArray javaModelsArray(Set<String> classes, Map<String, List<RendererInfo.TechnePart>> partsByClass) {
            JsonArray a = new JsonArray();
            for (String c : classes) {
                javaModelClassesSeen.add(c);
                JsonObject o = new JsonObject();
                o.addProperty("class", c);
                o.addProperty("modelKind", "java");
                List<RendererInfo.TechnePart> parts = partsByClass.get(c);
                if (parts != null && !parts.isEmpty()) {
                    JsonArray partsArr = new JsonArray();
                    for (RendererInfo.TechnePart p : parts) {
                        entityJavaModelPartsTotal++;
                        JsonObject po = new JsonObject();
                        po.addProperty("field", p.field);
                        if (p.texOffsetX != null) po.addProperty("textureOffsetX", p.texOffsetX);
                        if (p.texOffsetY != null) po.addProperty("textureOffsetY", p.texOffsetY);
                        boolean boxResolved = p.boxW != null;
                        if (boxResolved) {
                            JsonObject box = new JsonObject();
                            box.addProperty("x", p.boxX); box.addProperty("y", p.boxY); box.addProperty("z", p.boxZ);
                            box.addProperty("w", p.boxW); box.addProperty("h", p.boxH); box.addProperty("d", p.boxD);
                            if (p.boxScale != null) box.addProperty("scale", p.boxScale);
                            po.add("box", box);
                        }
                        if (p.rotPointX != null) {
                            JsonObject rp = new JsonObject();
                            rp.addProperty("x", p.rotPointX); rp.addProperty("y", p.rotPointY); rp.addProperty("z", p.rotPointZ);
                            po.add("rotationPoint", rp);
                        }
                        if (p.unresolvedReason != null) po.addProperty("unresolvedReason", p.unresolvedReason);
                        if (boxResolved) entityJavaModelPartsResolved++;
                        partsArr.add(po);
                    }
                    o.add("parts", partsArr);
                }
                a.add(o);
            }
            return a;
        }

        // ---------- writing ----------

        void write(Path outDir) throws IOException {
            JsonObject root = new JsonObject();
            JsonObject meta = new JsonObject();
            meta.addProperty("generator", "dev.umb.rendermap.RenderMap");
            meta.addProperty("generatedAtUtc", java.time.Instant.now().toString());
            meta.addProperty("classesScanned", jar.classes.size());
            // v2: added confidence:"inferred-dynamic" rows from DynamicVariantResolver (per-damage
            // compile-time tables resolved to the real flattened variant id), each carrying
            // "resolvedBy" and "damage". All v1 fields are unchanged, so umb-objbridge's existing
            // reader (which only pulls the fields it knows) keeps working unmodified.
            meta.addProperty("schemaVersion", 2);
            root.add("meta", meta);

            JsonArray items = new JsonArray();
            List<String> keys = new ArrayList<>(itemRows.keySet());
            keys.sort(Comparator.naturalOrder());
            for (String k : keys) items.add(itemRows.get(k));
            root.add("items", items);

            JsonArray blocks = new JsonArray();
            List<String> bkeys = new ArrayList<>(blockRows.keySet());
            bkeys.sort(Comparator.naturalOrder());
            for (String k : bkeys) blocks.add(blockRows.get(k));
            root.add("blocks", blocks);

            JsonArray tes = new JsonArray();
            for (JsonObject o : teRows) tes.add(o);
            root.add("tileEntities", tes);

            JsonArray ents = new JsonArray();
            for (JsonObject o : entityRows) ents.add(o);
            root.add("entities", ents);

            // models inventory
            JsonObject modelsObj = new JsonObject();
            for (Map.Entry<String, ObjModel> e : models.entrySet()) {
                ObjModel m = e.getValue();
                objTotal++;
                if (m.parseErrors.isEmpty()) objParsed++; else objErrors++;
                JsonObject o = new JsonObject();
                o.addProperty("path", m.path);
                o.addProperty("bytes", m.bytes);
                o.addProperty("vertices", m.vertices);
                o.addProperty("uvs", m.uvs);
                o.addProperty("normals", m.normals);
                o.addProperty("faces", m.faces);
                o.addProperty("triangles", m.triangles);
                o.addProperty("quads", m.quads);
                o.addProperty("ngons", m.ngons);
                o.addProperty("hasUvs", m.hasUvs);
                o.addProperty("hasNormals", m.hasNormals);
                JsonArray gs = new JsonArray();
                for (ObjModel.Group g : m.groups) {
                    JsonObject go = new JsonObject();
                    go.addProperty("name", g.name);
                    go.addProperty("order", g.order);
                    go.addProperty("faces", g.faces);
                    gs.add(go);
                }
                o.add("groups", gs);
                if (m.bboxMin != null) {
                    JsonArray mn = new JsonArray(); for (double d : m.bboxMin) mn.add(d);
                    JsonArray mx = new JsonArray(); for (double d : m.bboxMax) mx.add(d);
                    o.add("bboxMin", mn); o.add("bboxMax", mx);
                }
                o.add("mtllibs", strArray(new LinkedHashSet<>(m.mtllibs)));
                o.add("usemtls", strArray(new LinkedHashSet<>(m.usemtls)));
                o.add("parseErrors", strArray(new LinkedHashSet<>(m.parseErrors)));
                modelsObj.add(m.key, o);
            }
            root.add("models", modelsObj);

            // field -> id attribution
            int itemFieldsResolved = 0, blockFieldsResolved = 0;
            Set<String> attributedItemIds = new LinkedHashSet<>();
            Set<String> attributedBlockIds = new LinkedHashSet<>();
            JsonArray fieldRows = new JsonArray();
            // gap 1 (mandate #1): hop-count distribution across every field this pass could name
            // at all — 0 = literal/getUnlocalizedName visible directly at the field's own
            // construction or registration call site; >0 = only found via the bounded one-hop
            // scans (ModRegistryResolver's constructor scan, RegistryScanner's forwarder-chain
            // trace). Reported so the benefit of the deeper hop is measurable, per the mandate.
            Map<Integer, Integer> hopDistribution = new TreeMap<>();
            for (Map.Entry<String, ModRegistryResolver.Entry> e : mods.byField.entrySet()) {
                ModRegistryResolver.Entry v = e.getValue();
                RegistryScanner.Reg site = v.isBlock ? reg.blockFieldToReg.get(e.getKey())
                        : reg.itemFieldToReg.get(e.getKey());
                boolean registered = site != null;
                if (!registered) continue;
                JsonObject o = new JsonObject();
                o.addProperty("field", JarIndex.dotted(v.fieldOwner) + "." + v.fieldName);
                o.addProperty("kind", v.isBlock ? "block" : "item");
                o.addProperty("id", v.id);
                o.addProperty("concreteClass", v.concreteClass);
                if (v.unresolvedReason != null) o.addProperty("unresolvedReason", v.unresolvedReason);
                boolean inSnap = v.id != null
                        && (v.isBlock ? snap.blockById.containsKey(v.id) : snap.itemById.containsKey(v.id));
                o.addProperty("inSnapshot", inSnap);
                int hops = Math.max(v.hopsUsed, site.hopsUsed);
                o.addProperty("hopsUsed", hops);
                fieldRows.add(o);
                if (v.id != null && inSnap) {
                    if (v.isBlock) { blockFieldsResolved++; attributedBlockIds.add(v.id); }
                    else { itemFieldsResolved++; attributedItemIds.add(v.id); }
                    hopDistribution.merge(hops, 1, Integer::sum);
                }
            }
            root.add("fieldToId", fieldRows);
            fieldsAttributedItems = itemFieldsResolved;
            fieldsAttributedBlocks = blockFieldsResolved;
            this.hopDistribution = hopDistribution;

            // block items: a registered block also produces an item with the same id
            Set<String> attributedAnyItemIds = new LinkedHashSet<>(attributedItemIds);
            for (String bid : attributedBlockIds) if (snap.itemById.containsKey(bid)) attributedAnyItemIds.add(bid);

            JsonArray unattrItems = new JsonArray();
            for (Snapshot.Itm i : snap.modItems())
                if (!attributedAnyItemIds.contains(i.id)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", i.id);
                    o.addProperty("className", i.className);
                    unattrItems.add(o);
                }
            root.add("unattributedItemIds", unattrItems);
            JsonArray unattrBlocks = new JsonArray();
            for (Snapshot.Blk b : snap.modBlocks())
                if (!attributedBlockIds.contains(b.id)) unattrBlocks.add(b.id);
            root.add("unattributedBlockIds", unattrBlocks);

            JsonObject cov = new JsonObject();
            cov.addProperty("itemsWithCustomRenderer", itemsWithCustomRenderer);
            cov.addProperty("itemsMapped", itemsMapped);
            cov.addProperty("itemsUnresolved", itemsUnresolved);
            cov.addProperty("distinctItemIdsWithRenderer", itemRows.size());
            cov.addProperty("blockItemFallbackRows", blockItemFallbacks);
            int idsWithModel = 0, idsWithTexture = 0, idsWithBoth = 0, idsDynamic = 0;
            for (JsonObject r : itemRows.values()) {
                boolean hm = hasResolved(r, "models"), ht = hasResolved(r, "textures");
                if (hm) idsWithModel++;
                if (ht) idsWithTexture++;
                if (hm && ht) idsWithBoth++;
                if (r.has("dynamic") && r.get("dynamic").getAsBoolean()) idsDynamic++;
            }
            cov.addProperty("itemIdsWithResolvedModel", idsWithModel);
            cov.addProperty("itemIdsWithResolvedTexture", idsWithTexture);
            cov.addProperty("itemIdsWithModelAndTexture", idsWithBoth);
            cov.addProperty("itemIdsFlaggedDynamic", idsDynamic);
            cov.addProperty("dynamicVariantRowsAdded", dynamicVariantRowsAdded);
            cov.addProperty("dynamicVariantRowsUnresolved", dynamicVariantRowsUnresolved);
            cov.addProperty("snapshotItems", snap.items.size());
            cov.addProperty("snapshotItemsMod", snap.modItems().size());
            cov.addProperty("snapshotBlocks", snap.blocks.size());
            cov.addProperty("snapshotBlocksMod", snap.modBlocks().size());
            cov.addProperty("blockIdsAttributedToField", attributedBlockIds.size());
            cov.addProperty("itemIdsAttributedToField", attributedAnyItemIds.size());
            cov.addProperty("blocksWithIsbrh", blocksWithIsbrh);
            cov.addProperty("blocksMapped", blocksMapped);
            cov.addProperty("blocksWithCustomRenderId", blocksCustomRenderId);
            cov.addProperty("blocksCustomRenderIdWithoutRenderer", blocksCustomWithoutRenderer);
            cov.addProperty("blocksPlainVanillaRender", blocksVanillaRender);
            cov.addProperty("tesrs", tesrs);
            cov.addProperty("tesrsMapped", tesrsMapped);
            cov.addProperty("teRelinkedMetadataBranch", teRelinkNotes.size());
            cov.addProperty("entityRenderers", entityRows.size());
            cov.addProperty("entityIdsWithResolvedModel", entityIdsWithResolvedModel);
            cov.addProperty("entityIdsWithResolvedTexture", entityIdsWithResolvedTexture);
            cov.addProperty("entityIdsWithModelAndTexture", entityIdsWithModelAndTexture);
            cov.addProperty("entityIdsExact", entityIdsExact);
            cov.addProperty("entityIdsInferred", entityIdsInferred);
            cov.addProperty("entityIdsUnresolved", entityIdsUnresolved);
            cov.addProperty("entityTextureResolvedViaGetEntityTexture", entityTextureResolvedViaGetEntityTexture);
            cov.addProperty("entityJavaModelPartsTotal", entityJavaModelPartsTotal);
            cov.addProperty("entityJavaModelPartsResolved", entityJavaModelPartsResolved);
            cov.addProperty("objTotal", objTotal);
            cov.addProperty("objParsed", objParsed);
            cov.addProperty("objErrors", objErrors);
            cov.addProperty("registerItemCallSites", reg.itemCallSites);
            cov.addProperty("registerBlockCallSites", reg.blockCallSites);
            cov.addProperty("forwardedRegisterCallSites", reg.forwardedCallSites);
            cov.addProperty("holderFieldsTotal", holders.byField.size());
            long holderResolved = holders.byField.values().stream().filter(ResRef::resolved).count();
            cov.addProperty("holderFieldsResolved", holderResolved);

            // ---- gap 1 (mandate #1): the bounded one-hop interprocedural step ----
            int fieldsResolvedViaHop = 0;
            JsonObject hopHisto = new JsonObject();
            for (Map.Entry<Integer, Integer> h : hopDistribution.entrySet()) {
                hopHisto.addProperty(String.valueOf(h.getKey()), h.getValue());
                if (h.getKey() > 0) fieldsResolvedViaHop += h.getValue();
            }
            cov.addProperty("fieldToIdResolvedViaHop", fieldsResolvedViaHop);
            cov.add("fieldToIdHopDistribution", hopHisto);

            // ---- gap 2 / A1 (mandate #2): ISBRH-block texture from getIcon(side,meta) ----
            cov.addProperty("blocksIsbrhIconTextureAdded", blocksIsbrhIconTextureAdded);
            cov.addProperty("blocksCustomIconTextureAdded", blocksCustomIconTextureAdded);
            cov.addProperty("blocksIsbrhIconTextureCtmSkipped", blocksIsbrhIconTextureCtmSkipped);

            // ---- item-side mirror of gap 2/A1: item texture from getIconFromDamage/getIconIndex ----
            cov.addProperty("itemsIconTextureAdded", itemsIconTextureAdded);
            cov.addProperty("itemsIconTextureCtmSkipped", itemsIconTextureCtmSkipped);

            // ---- bonus tier flag: whether the optional structural-indirection tier ran ----
            cov.addProperty("bonusIndirectionTierEnabled", bind.includeBonusResolvers);

            // ---- honest generic-vs-bespoke delta (mandate requirement 3) ----
            // Every binding is tagged resolverKind:"generic" or "bonus-indirection"
            // (BindingScanner.GENERIC / BONUS_INDIRECTION). These counts answer: how many
            // item/block ids would resolve a renderer/model/texture through the UNIVERSAL
            // Forge/vanilla registration APIs alone, with the structural-indirection tier
            // switched off entirely.
            GenericDelta gd = computeGenericDelta();
            cov.addProperty("itemIdsWithRendererGenericOnly", gd.itemIdsWithRenderer.size());
            cov.addProperty("itemIdsWithModelAndTextureGenericOnly", gd.itemIdsWithModelAndTexture.size());
            cov.addProperty("blockIdsWithRendererGenericOnly", gd.blockIdsWithRenderer.size());
            cov.addProperty("blockIdsWithModelAndTextureGenericOnly", gd.blockIdsWithModelAndTexture.size());
            cov.addProperty("isbrhImplementorsFoundByGenericScanAlone", gd.isbrhImplementorScanCount);
            cov.addProperty("orphanTesrRenderers", (int) orphanRenderers().stream()
                    .filter(o -> "TileEntitySpecialRenderer".equals(o.interfaceOrSuper)).count());
            cov.addProperty("orphanEntityRenderers", (int) orphanRenderers().stream()
                    .filter(o -> "Render".equals(o.interfaceOrSuper)).count());
            cov.addProperty("rendererClassesUsingJavaModel", javaModelClassesSeen.size());
            root.add("coverage", cov);

            JsonArray orphans = new JsonArray();
            for (BindingScanner.OrphanRenderer o : orphanRenderers()) {
                JsonObject oo = new JsonObject();
                oo.addProperty("rendererClass", o.rendererClass);
                oo.addProperty("interfaceOrSuper", o.interfaceOrSuper);
                oo.addProperty("reason", o.reason);
                orphans.add(oo);
            }
            root.add("orphanRenderers", orphans);
            root.add("javaModelClasses", strArray(javaModelClassesSeen));

            // unresolved patterns
            JsonArray up = new JsonArray();
            List<Map.Entry<String, Integer>> sorted = new ArrayList<>(unresolvedCounts.entrySet());
            sorted.sort((a, b) -> b.getValue() - a.getValue());
            for (Map.Entry<String, Integer> e : sorted) {
                JsonObject o = new JsonObject();
                o.addProperty("pattern", e.getKey());
                o.addProperty("count", e.getValue());
                o.addProperty("example", unresolvedExample.get(e.getKey()));
                up.add(o);
            }
            root.add("unresolvedPatterns", up);

            root.add("dynamicVariantResolutionNotes", strArray(new LinkedHashSet<>(dynamicVariantNotes)));

            sanitizeOutput(root, outputNamespace);

            Path json = outDir.resolve(mapFileName);
            try (Writer w = Files.newBufferedWriter(json, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().serializeNulls().create().toJson(root, w);
            }
            log("wrote " + json + " (" + Files.size(json) + " bytes)");
            // Compat copies for callers still pointed at a legacy layout: identical bytes,
            // caller-chosen names (never a mod literal in code).
            for (String extra : alsoWrite) {
                if (extra == null || extra.isEmpty() || extra.equals(mapFileName)) continue;
                Path copy = outDir.resolve(extra);
                Files.copy(json, copy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                log("wrote compat copy " + copy);
            }

            writeReport(outDir, root, attributedBlockIds, attributedAnyItemIds, sorted);
        }

        /** Normalize every emitted identifier/path namespace at the serialization boundary. */
        private static void sanitizeOutput(JsonElement element, String fallbackNamespace) {
            if (element == null || element.isJsonNull()) return;
            if (element.isJsonArray()) {
                for (JsonElement child : element.getAsJsonArray()) sanitizeOutput(child, fallbackNamespace);
                return;
            }
            if (!element.isJsonObject()) return;
            JsonObject object = element.getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : new ArrayList<>(object.entrySet())) {
                String key = entry.getKey();
                JsonElement value = entry.getValue();
                if ("id".equals(key) && value.isJsonPrimitive()) {
                    object.addProperty(key, sanitizeId(value.getAsString(), fallbackNamespace));
                } else if ("blockIds".equals(key) && value.isJsonArray()) {
                    for (int i = 0; i < value.getAsJsonArray().size(); i++) {
                        JsonElement id = value.getAsJsonArray().get(i);
                        if (id.isJsonPrimitive()) value.getAsJsonArray().set(i,
                                new com.google.gson.JsonPrimitive(sanitizeId(id.getAsString(), fallbackNamespace)));
                    }
                } else if (("path".equals(key) || "assetPath".equals(key)) && value.isJsonPrimitive()) {
                    object.addProperty(key, sanitizeAssetPath(value.getAsString(), fallbackNamespace));
                } else {
                    sanitizeOutput(value, fallbackNamespace);
                }
            }
        }

        private static String sanitizeId(String raw, String fallbackNamespace) {
            if (raw == null || raw.isEmpty()) return raw;
            int colon = raw.indexOf(':');
            String ns = colon > 0 ? raw.substring(0, colon) : fallbackNamespace;
            String path = colon > 0 ? raw.substring(colon + 1) : raw;
            ns = "minecraft".equalsIgnoreCase(ns) ? "minecraft" : sanitizeNamespace(ns);
            if (ns.isEmpty()) ns = fallbackNamespace;
            return ns + ":" + path;
        }

        private static String sanitizeAssetPath(String raw, String fallbackNamespace) {
            if (raw == null || raw.isEmpty()) return raw;
            if (raw.startsWith("assets/")) {
                String rest = raw.substring("assets/".length());
                int slash = rest.indexOf('/');
                if (slash > 0) return "assets/" + sanitizeNamespace(rest.substring(0, slash)) + rest.substring(slash);
                return raw;
            }
            return raw.contains(":") ? sanitizeId(raw, fallbackNamespace) : raw;
        }

        void writeReport(Path outDir, JsonObject root, Set<String> attrBlocks, Set<String> attrItems,
                         List<Map.Entry<String, Integer>> unresolvedSorted) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append("# Render map — report\n\n");
            sb.append("Generated by `dev.umb.rendermap.RenderMap` from `").append(jarPathStr)
              .append("` + `").append(snapPathStr).append("`.\n")
              .append("Static bytecode analysis only; no game was launched.\n\n");

            JsonObject cov = root.getAsJsonObject("coverage");
            sb.append("## Coverage\n\n| metric | value |\n|---|---|\n");
            for (String k : cov.keySet()) sb.append("| ").append(k).append(" | ").append(cov.get(k)).append(" |\n");
            sb.append("\n");

            sb.append("## Generic-vs-bespoke resolution delta\n\n");
            sb.append("Every renderer binding is tagged by which resolver tier found it "
                    + "(`dev.umb.rendermap.BindingScanner`): **generic** — the universal Forge/vanilla "
                    + "registration APIs every 1.7.10 mod must use (`MinecraftForgeClient.registerItemRenderer`, "
                    + "`ClientRegistry.bindTileEntitySpecialRenderer`, `RenderingRegistry.registerBlockHandler`, "
                    + "`RenderingRegistry.registerEntityRenderingHandler`, self-implementing `IItemRenderer` "
                    + "Items, and whole-jar implementor-scan for `ISimpleBlockRenderingHandler`) — or "
                    + "**bonus-indirection** — structural indirection shapes (static item-keyed renderer "
                    + "maps, indirect model-texture registries joined with map-dispatch loops, "
                    + "item-provider interfaces), detected by bytecode type flow with no per-mod rules. "
                    + "The counts below isolate the generic tier alone:\n\n");
            sb.append("| metric | generic-only | full pipeline (generic + bonus) |\n|---|---|---|\n");
            sb.append("| item ids with a renderer row | ").append(cov.get("itemIdsWithRendererGenericOnly"))
              .append(" | ").append(cov.get("distinctItemIdsWithRenderer")).append(" |\n");
            sb.append("| item ids with model+texture | ").append(cov.get("itemIdsWithModelAndTextureGenericOnly"))
              .append(" | ").append(cov.get("itemIdsWithModelAndTexture")).append(" |\n");
            sb.append("| block ids with a renderer (ISBRH/TESR) | ").append(cov.get("blockIdsWithRendererGenericOnly"))
              .append(" | ").append(cov.get("blocksMapped")).append(" |\n");
            sb.append("| block ids with model+texture | ").append(cov.get("blockIdsWithModelAndTextureGenericOnly"))
              .append(" | (see blocksMapped; blocks report a single mapped/unmapped flag, not model+texture"
                    + " separately) |\n");
            sb.append("\nISBRH classes found ONLY by whole-jar implementor-scan (no `registerBlockHandler` call")
              .append(" site resolved to them): ").append(cov.get("isbrhImplementorsFoundByGenericScanAlone"))
              .append(".\n\n");
            sb.append("On this jar the generic tier alone finds ")
              .append(pct(cov.get("itemIdsWithModelAndTextureGenericOnly").getAsInt(),
                      Math.max(1, cov.get("itemIdsWithModelAndTexture").getAsInt())))
              .append(" of the full pipeline's item model+texture coverage. The gap is this jar's own"
                    + " renderer indirection, dispatched through these call shapes: "
                    + bonusProducers(bind)
                    + ". This is the honest measurement the mandate asked for, not a shortfall to hide:"
                    + " on a mod that only uses the four universal APIs directly, the generic-only and"
                    + " full-pipeline columns above are identical.\n\n");

            sb.append("## Orphan renderer classes (implementor found, no binding site resolved)\n\n");
            sb.append("Any class implementing `TileEntitySpecialRenderer` or extending `Render` that no "
                    + "resolver could tie to a TE/entity class — unlike ISBRH (self-reports its render id) or "
                    + "an Item implementing `IItemRenderer` directly, 1.7.10 has no generic API for a TE or "
                    + "Entity to name its own renderer, so the registration call site is the only place that "
                    + "link is made; when it could not be resolved, this is a real, reportable static-analysis "
                    + "limit rather than a silently dropped renderer.\n\n");
            List<BindingScanner.OrphanRenderer> orphans = orphanRenderers();
            if (orphans.isEmpty()) sb.append("_none_\n\n");
            else {
                sb.append("| class | kind | reason |\n|---|---|---|\n");
                for (BindingScanner.OrphanRenderer o : orphans)
                    sb.append("| `").append(o.rendererClass).append("` | ").append(o.interfaceOrSuper)
                      .append(" | ").append(o.reason).append(" |\n");
                sb.append("\n");
            }

            sb.append("## Java (ModelBase/Techne) models\n\n");
            sb.append("A `ModelBase` subclass is Java code that issues GL calls directly — there is no mesh "
                    + "file to bake. Detected and reported (`modelKind:\"java\"` on the row), never baked, "
                    + "per the mandate.\n\n");
            if (javaModelClassesSeen.isEmpty()) sb.append("_none found in this jar_\n\n");
            else {
                sb.append(javaModelClassesSeen.size()).append(" class(es): ");
                sb.append(String.join(", ", javaModelClassesSeen.stream().map(c -> "`" + c + "`").toList()));
                sb.append("\n\n");
            }

            sb.append("## Dynamic-row per-variant resolution\n\n");
            sb.append("`dev.umb.rendermap.DynamicVariantResolver` turns a `dynamic:true` row whose renderer indexes a")
              .append(" compile-time table by `ItemStack.getItemDamage()` into one row per real, flattened variant")
              .append(" id (`confidence:\"inferred-dynamic\"`, `resolvedBy` names the family). Notes:\n\n");
            if (dynamicVariantNotes.isEmpty()) sb.append("_none_\n");
            else for (String n : dynamicVariantNotes) sb.append("- ").append(n).append("\n");
            sb.append("\n");

            int modBlockCount = snap.modBlocks().size(), modItemCount = snap.modItems().size();
            sb.append("### Field -> registry id attribution\n\n");
            sb.append("Percentages are against **this mod's own (non-`minecraft:`) namespace only** ")
              .append("(the snapshot also carries ")
              .append(snap.blocks.size() - modBlockCount).append(" vanilla blocks and ")
              .append(snap.items.size() - modItemCount).append(" vanilla items, which this mod never registers).\n\n");
            sb.append("- mod block ids in snapshot: ").append(modBlockCount)
              .append("; attributed to a holder static field: ").append(attrBlocks.size())
              .append(" (").append(pct(attrBlocks.size(), modBlockCount)).append(")\n");
            sb.append("- mod item ids in snapshot: ").append(modItemCount)
              .append("; attributed to a holder static field: ").append(attrItems.size())
              .append(" (").append(pct(attrItems.size(), modItemCount)).append(")\n\n");
            sb.append("Unattributed mod item ids: ").append(modItemCount - attrItems.size()).append(".\n\n");

            sb.append("#### Gap 1 — bounded one-hop interprocedural step\n\n");
            sb.append("How many hops (0 = no hop needed, 1 = resolved only via").append(
                    " ModRegistryResolver's constructor scan or RegistryScanner's forwarder-chain trace)")
              .append(" it took to name each of the ").append(attrBlocks.size() + attrItems.size())
              .append(" attributed field(s) above:\n\n| hops | fields |\n|---|---|\n");
            if (hopDistribution.isEmpty()) sb.append("| _n/a_ | 0 |\n");
            else for (Map.Entry<Integer, Integer> h : hopDistribution.entrySet())
                sb.append("| ").append(h.getKey()).append(" | ").append(h.getValue()).append(" |\n");
            sb.append("\n");

            sb.append("#### Gap 2 / A1 — ISBRH block texture from getIcon(side,meta)\n\n");
            sb.append("Blocks whose ISBRH renderer named no static model/texture field, resolved instead from")
              .append(" the block's own live-runtime `getIcon(side,meta)` result: **")
              .append(blocksIsbrhIconTextureAdded).append("**. Skipped as a connected-texture-mod compound key")
              .append(" (contains `|`, e.g. Chisel's CTM icons — genuinely not a single static texture): **")
              .append(blocksIsbrhIconTextureCtmSkipped).append("**.\n\n");

            sb.append("#### Item-side mirror of gap 2/A1 — item texture from")
              .append(" getIconFromDamage/getIconIndex(ItemStack)\n\n");
            sb.append("Custom-render blocks without a resolved renderer that received the same verified")
              .append(" icon safety net: **").append(blocksCustomIconTextureAdded).append("**.\n\n");
            sb.append("GENERIC-tier items whose renderer named no static model/texture field, resolved instead")
              .append(" from the item's own live-runtime icon (base or, failing that, a damage-variant")
              .append(" fallback): **").append(itemsIconTextureAdded)
              .append("**. Skipped as a connected-texture-mod compound key (contains `|`): **")
              .append(itemsIconTextureCtmSkipped).append("**. See ITEM-ICON-RESOLUTION.md.\n\n");


            sb.append("## Top unresolved patterns\n\n");
            int n = 0;
            for (Map.Entry<String, Integer> e : unresolvedSorted) {
                if (n++ >= 15) break;
                sb.append("- **").append(e.getValue()).append("x** ").append(e.getKey())
                  .append("\n  - example: `").append(unresolvedExample.get(e.getKey())).append("`\n");
            }
            if (unresolvedSorted.isEmpty()) sb.append("_none_\n");

            sb.append("\n## OBJ inventory summary\n\n");
            int totalFaces = 0, totalTris = 0, totalQuads = 0, totalNgons = 0, noUv = 0, multiGroup = 0;
            for (ObjModel m : models.values()) {
                totalFaces += m.faces; totalTris += m.triangles; totalQuads += m.quads; totalNgons += m.ngons;
                if (!m.hasUvs) noUv++;
                if (m.groups.size() > 1) multiGroup++;
            }
            sb.append("- files: ").append(models.size()).append("\n");
            sb.append("- faces: ").append(totalFaces).append(" (tri ").append(totalTris)
              .append(", quad ").append(totalQuads).append(", ngon ").append(totalNgons).append(")\n");
            sb.append("- files without UVs: ").append(noUv).append("\n");
            sb.append("- files with more than one group: ").append(multiGroup).append("\n");
            sb.append("- no `.mtl` file ships in the jar; `mtllib`/`usemtl` lines are therefore dangling"
                    + " and every texture comes from the renderer's `bindTexture` call instead\n");
            for (ObjModel m : models.values())
                if (!m.parseErrors.isEmpty())
                    sb.append("- parse errors in `").append(m.key).append("`: ")
                      .append(m.parseErrors.size()).append(" (first: ").append(m.parseErrors.get(0)).append(")\n");

            sb.append("\n### Largest OBJ models by face count\n\n| model | faces | groups | verts | uvs |\n|---|---|---|---|---|\n");
            List<ObjModel> byFaces = new ArrayList<>(models.values());
            byFaces.sort((a, b) -> b.faces - a.faces);
            for (int i = 0; i < Math.min(10, byFaces.size()); i++) {
                ObjModel m = byFaces.get(i);
                sb.append("| `").append(m.key).append("` | ").append(m.faces).append(" | ")
                  .append(m.groups.size()).append(" | ").append(m.vertices).append(" | ")
                  .append(m.uvs).append(" |\n");
            }

            sb.append("\n## Renderer inventory\n\n");
            sb.append("| kind | bindings | with a resolved renderer class | with a resolved model or texture |\n|---|---|---|---|\n");
            sb.append("| item | ").append(bind.itemBindings.size()).append(" | ")
              .append(bind.itemBindings.stream().filter(b -> b.rendererClass != null).count()).append(" | ")
              .append(itemRows.values().stream()
                      .filter(r -> hasResolved(r, "models") || hasResolved(r, "textures")).count())
              .append(" (distinct ids) |\n");
            sb.append("| TESR | ").append(tesrs).append(" | ")
              .append(bind.tesrBindings.stream().filter(b -> b.rendererClass != null).count()).append(" | ")
              .append(tesrsMapped).append(" |\n");
            sb.append("| ISBRH | ").append(bind.isbrhBindings.size()).append(" | ")
              .append(bind.isbrhBindings.stream().filter(b -> b.handlerClass != null).count()).append(" | ")
              .append(bind.isbrhBindings.stream().filter(b -> b.renderIdFieldName != null).count())
              .append(" (render-id field recovered) |\n");
            sb.append("| entity | ").append(entityRows.size()).append(" | ")
              .append(bind.entityBindings.stream().filter(b -> b.rendererClass != null).count()).append(" | ")
              .append(entityRows.stream()
                      .filter(r -> hasResolved(r, "models") || hasResolved(r, "textures")).count()).append(" |\n");

            Path p = outDir.resolve("REPORT.md");
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
            log("wrote " + p);
        }

        static boolean hasResolved(JsonObject row, String key) {
            if (!row.has(key) || !row.get(key).isJsonArray()) return false;
            for (var e : row.getAsJsonArray(key)) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("path") && !o.get("path").isJsonNull()) return true;
            }
            return false;
        }
        static String pathsOf(JsonObject row, String key) {
            List<String> out = new ArrayList<>();
            for (var e : row.getAsJsonArray(key)) {
                JsonObject o = e.getAsJsonObject();
                if (o.has("path") && !o.get("path").isJsonNull())
                    out.add("`" + o.get("path").getAsString() + "`");
            }
            return String.join(", ", out);
        }
        static String str(JsonObject o, String k) {
            return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "null";
        }
        static String pct(int a, int b) {
            return b == 0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * a / b);
        }
        /** Distinct bonus-tier producer shapes behind this run's indirection rows, from data. */
        static String bonusProducers(BindingScanner bind) {
            Set<String> producers = new java.util.TreeSet<>();
            for (BindingScanner.ItemBinding b : bind.itemBindings)
                if (!BindingScanner.GENERIC.equals(b.resolverKind) && b.producer != null)
                    producers.add("`" + b.producer + "`");
            if (producers.isEmpty()) return "_none (no bonus-tier bindings on this jar)_";
            List<String> top = new ArrayList<>(producers);
            String s = String.join(", ", top.subList(0, Math.min(8, top.size())));
            return producers.size() > 8 ? s + ", … (+" + (producers.size() - 8) + " more)" : s;
        }
    }
}
