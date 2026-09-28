package dev.umb.packgen;

import dev.umb.hostagent.content.BlockRec;
import dev.umb.hostagent.content.BlockShapeProfile;
import dev.umb.hostagent.content.GuiProfile;
import dev.umb.hostagent.content.LangTable;
import dev.umb.hostagent.content.LegacyIds;
import dev.umb.hostagent.content.LegacySnapshot;
import dev.umb.hostagent.content.VariantPlan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Generates a 26.2 resource pack for the legacy content the host agent registers.
 *
 * Usage: PackGen &lt;snapshot.json&gt; &lt;assetsRoot&gt; &lt;outDir&gt; [--ns hbm] [--runtime-assets dir]
 *   assetsRoot is the directory that CONTAINS "assets/", e.g. research/out/legacy/hbm-assets
 *   --runtime-assets is a SECOND, lower-priority assets root produced by dumping the running
 *   1.7.10 client's stitched texture atlases (research/out/legacy/hbm-assets-runtime, written by
 *   fixtures/umb-snapshot-1710). It is the only source for icons a legacy mod COMPOSITES AT
 *   RUNTIME and therefore never ships as a png - e.g. HBM's bedrock_ore_*_light-&lt;n&gt;,
 *   scraps-&lt;Material&gt;, watz_pellet-&lt;ISO&gt;item.watz_pellet, sellafield_slaked-&lt;n&gt;-0.
 *
 * The flattening is shared with the agent ({@link VariantPlan}, which owns the shared
 * {@link LegacyIds}), so model/texture paths line up with what actually got registered - one
 * blockstate + model + item definition + item model + lang row per 1.7.10 metadata variant.
 *
 * Formats copied from the 26.2 client jar, not from memory:
 *   pack.mcmeta         {"pack":{"description":...,"min_format":88,"max_format":88}}
 *   blockstates/x.json  {"variants":{"":{"model":"ns:block/x"}}}
 *   models/block/x.json {"parent":"minecraft:block/cube_all","textures":{"all":"ns:block/t"}}
 *   items/x.json        {"model":{"type":"minecraft:model","model":"ns:item/x"}}   (1.21.4+ shape)
 *   models/item/x.json  {"parent":"minecraft:item/generated","textures":{"layer0":"ns:item/t"}}
 */
public final class PackGen {

    /** version.json -> pack_version.resource_major on 26.2. */
    static final int RESOURCE_MAJOR = 88;

    /** 1.7.10 Block.getIcon side order -> modern model face names. */
    static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    /** 1.7.10 vanilla icon names that were renamed in modern Minecraft. */
    static final Map<String, String> VANILLA_BLOCK_ALIASES = Map.of(
            "log_oak", "oak_log",
            "stonebrick", "stone_bricks",
            "grass", "grass_block_top",
            "planks_oak", "oak_planks");

    /**
     * The RAW legacy namespace exactly as passed in (e.g. mcmod.info's {@code "IronChest"}) - used
     * ONLY to locate source assets under the mod jar's own on-disk casing. Never written into the
     * generated pack.
     */
    private final String legacyNs;
    /**
     * The sanitized, {@code Identifier}-safe form of {@link #legacyNs} (lowercase,
     * {@code [a-z0-9_.-]}) - used for EVERY path and reference this class writes into the
     * generated pack. GENERALITY fix (laneCasing, Bug 1): {@code --ns} may be handed the raw
     * mcmod.info modid verbatim (harness's own default, see harness/legacy.ps1
     * Get-ModIdentity), so this class must never trust it to already be lowercase.
     */
    private final String ns;
    private final Path assetsRoot;
    private final Path out;
    private final LangTable lang;
    /** Optional second source: the running-client atlas dump. null when not supplied. */
    private final RuntimeDump runtime;

    final Map<String, String> langOut = new TreeMap<>();
    final Set<String> copiedTextures = new LinkedHashSet<>();
    final List<String> missingTextures = new ArrayList<>();
    /** icons whose pixels came from the runtime atlas dump because the jar has no png. */
    final List<String> runtimeSourced = new ArrayList<>();
    /** lang values that still look like a raw translation key. Target: empty. */
    final List<String> rawKeyLang = new ArrayList<>();

    int blockstates, blockModels, itemDefs, itemModels, texturesCopied, texturesMissing;
    int blocksNoIcon, blockVariants, itemVariants, iconFallbacks, texturesFromRuntimeDump;
    /**
     * Blocks that got an intentionally EMPTY model (see {@link #emptyBlockModelJson}): the
     * legacy block's renderType is -1, so the ordinary block pass rendered nothing. Counted
     * separately from {@link #blocksNoIcon} (no icons AND not staticless - still skipped).
     */
    int blocksInvisible;
    /**
     * Shape-only twins (multiblock-notes/SHAPE-VARIANT-LANE.md's own honest remainder, closed
     * here): a {@link VariantPlan.BlockEntry#shapeOnly} entry that got a blockstate + block model
     * so the registered Block renders as SOMETHING instead of 26.2's fallback for an undeclared
     * block state, but deliberately no item definition/model or lang row - see
     * {@link #emitShapeOnlyBlock}. Counted separately from {@link #blockVariants} (real subBlocks
     * variants with a BlockItem) so the two populations are never conflated in a report.
     */
    int blockShapeOnly;
    private final Map<String, Boolean> resolvable = new java.util.HashMap<>();
    /**
     * block-shapes.json data (see {@link BlockShapeProfile}), consulted only to reproduce the
     * SAME {@link VariantPlan#build(LegacySnapshot, LangTable, BlockShapeProfile)} shape-only-twin
     * plan {@code Registrar}/{@code BootstrapProbe} compute from the live agent - never null,
     * defaults to {@link BlockShapeProfile#empty()} (no shape-only twins, byte-identical to every
     * caller that pre-dates this).
     */
    private final BlockShapeProfile shapes;
    VariantPlan plan;

    public PackGen(String ns, Path assetsRoot, Path out, LangTable lang) {
        this(ns, assetsRoot, out, lang, null);
    }

    public PackGen(String ns, Path assetsRoot, Path out, LangTable lang, Path runtimeAssetsRoot) {
        this(ns, assetsRoot, out, lang, runtimeAssetsRoot, BlockShapeProfile.empty());
    }

    /**
     * Full constructor: also takes the block-shapes profile so shape-only twins (a metadata a
     * mod's own runtime placement code writes, never a {@code subBlocks} choice a player selects -
     * see {@link VariantPlan.BlockEntry#shapeOnly}) get a resource-pack entry too. Passing
     * {@link BlockShapeProfile#empty()} (or using either shorter constructor above) reproduces the
     * exact pre-existing behaviour: {@code VariantPlan} never produces a shape-only twin for an
     * empty profile, so nothing here changes for a caller that does not supply one.
     */
    public PackGen(String ns, Path assetsRoot, Path out, LangTable lang, Path runtimeAssetsRoot,
                   BlockShapeProfile shapes) {
        this.legacyNs = ns;
        this.ns = LegacyIds.sanitizeNamespace(ns);
        this.assetsRoot = assetsRoot;
        this.out = out;
        this.lang = lang;
        this.runtime = RuntimeDump.open(runtimeAssetsRoot);
        this.shapes = shapes == null ? BlockShapeProfile.empty() : shapes;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: PackGen <snapshot.json> <assetsRoot> <outDir> --ns <namespace>"
                    + " [--runtime-assets <dir>]");
            System.exit(2);
        }
        Path snapshot = Paths.get(args[0]);
        Path assetsRoot = Paths.get(args[1]);
        Path out = Paths.get(args[2]);
        // GENERALITY fix (hostagent-purge #12): this used to default silently to "hbm" when --ns
        // was omitted (same shape as umb-objbridge's ObjPackGen VIOLATION 2, fixed first in
        // UNIVERSALITY-AUDIT.md Part 1). Unlike ObjPackGen, this tool has no render-map data to
        // derive a namespace guess from at the point args are parsed, so "required, fail loudly"
        // is the honest fix here rather than a guess - harness/legacy.ps1 (the only real caller)
        // already passes --ns explicitly on every invocation (see its `packArgs` line), so this
        // is not a behavior change for the test corpus.
        String ns = null;
        Path runtimeAssets = null;
        Path guiProfilePath = null;
        Path blockShapesPath = null;
        for (int i = 3; i < args.length - 1; i++) {
            if ("--ns".equals(args[i])) ns = args[i + 1];
            else if ("--runtime-assets".equals(args[i])) runtimeAssets = Paths.get(args[i + 1]);
            else if ("--gui-profile".equals(args[i])) guiProfilePath = Paths.get(args[i + 1]);
            else if ("--block-shapes".equals(args[i])) blockShapesPath = Paths.get(args[i + 1]);
        }
        if (ns == null || ns.isEmpty()) {
            System.err.println("PackGen: --ns <namespace> is required (no mod-specific default) - "
                    + "e.g. --ns yourmodid");
            System.exit(2);
            return;
        }
        // Shape-only twins (multiblock-notes/SHAPE-VARIANT-LANE.md): default to the SAME
        // repo-conventional location dev.umb.hostagent.HostAgent#blockShapesPath() defaults to
        // (a block-shapes.json sitting next to the snapshot it was extracted alongside) so an
        // explicit --block-shapes flag is never required for the common case, matching how this
        // tool already derives resolveSourceLangFile from --ns alone. BlockShapeProfile.load
        // tolerates a missing file (returns an empty profile - zero shape-only twins, exactly the
        // pre-existing behaviour), so a mod with no extracted shape data is unaffected.
        if (blockShapesPath == null) {
            blockShapesPath = snapshot.getParent() == null
                    ? Paths.get("block-shapes.json")
                    : snapshot.getParent().resolve("block-shapes.json");
        }
        BlockShapeProfile shapes = BlockShapeProfile.load(blockShapesPath);

        LegacySnapshot snap = LegacySnapshot.load(snapshot, ns);
        LangTable lang = LangTable.load(resolveSourceLangFile(assetsRoot, ns));
        PackGen gen = new PackGen(ns, assetsRoot, out, lang, runtimeAssets, shapes);
        if (runtimeAssets != null) {
            System.out.println("PACKGEN runtimeAssets=" + runtimeAssets.toAbsolutePath()
                    + " " + (gen.runtime == null ? "UNUSABLE" : gen.runtime.describe()));
        }
        System.out.println("PACKGEN blockShapes=" + blockShapesPath.toAbsolutePath()
                + " " + (shapes.size() > 0 ? ("loaded ok=" + shapes.size()) : "absent/empty (0 shape-only twins)"));
        gen.generate(snap);

        Path report = out.getParent() == null
                ? Paths.get("packgen-report.txt")
                : out.getParent().resolve(out.getFileName() + "-report.txt");
        gen.writeReport(report, snap);
        System.out.println("PACKGEN out=" + out.toAbsolutePath());
        System.out.println("PACKGEN blockstates=" + gen.blockstates
                + " blockModels=" + gen.blockModels
                + " itemDefs=" + gen.itemDefs
                + " itemModels=" + gen.itemModels
                + " texturesCopied=" + gen.texturesCopied
                + " texturesMissing=" + gen.texturesMissing
                + " texturesFromRuntimeDump=" + gen.texturesFromRuntimeDump
                + " blocksWithoutIcons=" + gen.blocksNoIcon
                 + " blocksInvisible=" + gen.blocksInvisible
                + " blockVariants=" + gen.blockVariants
                + " blockShapeOnly=" + gen.blockShapeOnly
                + " itemVariants=" + gen.itemVariants
                + " iconFallbacks=" + gen.iconFallbacks
                + " langKeys=" + gen.langOut.size()
                + " langRawKeys=" + gen.rawKeyLang.size());
        System.out.println("PACKGEN plan " + gen.plan.stats());
        System.out.println("PACKGEN report=" + report.toAbsolutePath());

        // Task B (laneConsume-progress.md): ship every referenced GUI background texture into
        // this same pack - independent of the block/item pass above (never touches assets/<ns>/
        // textures/gui/**), so --gui-profile is optional and additive.
        if (guiProfilePath != null) {
            GuiProfile profile = GuiProfile.load(guiProfilePath);
            GuiTextureReport rep = gen.copyGuiTextures(profile);
            System.out.println("PACKGEN guiTextures resolved=" + profile.size()
                    + " copied=" + rep.copied + " missing=" + rep.missing);
            if (!rep.missingDetails.isEmpty()) {
                System.out.println("PACKGEN guiTextures missing detail:");
                for (String s : rep.missingDetails) System.out.println("  " + s);
            }
        }
    }

    /**
     * Locate the extracted mod's own {@code en_US.lang} without assuming its on-disk casing
     * matches {@code --ns} byte-for-byte. GENERALITY fix (laneCasing, Bug 1): the extract stage's
     * assets root is unzipped straight from the mod jar (real casing, e.g. {@code assets/ironchest/
     * ...}), while {@code ns} here may be the raw mcmod.info modid (e.g. {@code "IronChest"}).
     * Windows resolves these identically (case-insensitive NTFS), which is exactly why this went
     * unnoticed; a case-sensitive filesystem would not. Try the raw namespace, then the sanitized
     * one, then fall back to whatever single directory actually exists under {@code assets/}.
     */
    static Path resolveSourceLangFile(Path assetsRoot, String ns) {
        Path viaRaw = assetsRoot.resolve("assets/" + ns + "/lang/en_US.lang");
        if (Files.isRegularFile(viaRaw)) return viaRaw;
        String sanitized = LegacyIds.sanitizeNamespace(ns);
        Path viaSanitized = assetsRoot.resolve("assets/" + sanitized + "/lang/en_US.lang");
        if (Files.isRegularFile(viaSanitized)) return viaSanitized;
        Path assetsDir = assetsRoot.resolve("assets");
        if (Files.isDirectory(assetsDir)) {
            try (var stream = Files.list(assetsDir)) {
                for (Path child : (Iterable<Path>) stream::iterator) {
                    if (!Files.isDirectory(child)) continue;
                    String name = child.getFileName().toString();
                    if (name.equalsIgnoreCase(ns) || name.equalsIgnoreCase(sanitized)) {
                        Path candidate = child.resolve("lang/en_US.lang");
                        if (Files.isRegularFile(candidate)) return candidate;
                    }
                }
            } catch (IOException ignored) {
                // fall through to the (missing) raw guess below; LangTable.load tolerates absence
            }
        }
        return viaRaw;
    }

    public void generate(LegacySnapshot snap) throws IOException {
        Files.createDirectories(out);
        write(out.resolve("pack.mcmeta"), packMcmeta());

        plan = buildPlan(snap);

        // ---- blocks: blockstate + block model + item definition + item model + textures ----
        for (VariantPlan.BlockEntry e : plan.blocks) {
            try {
                emitBlock(e);
            } catch (Exception ex) {
                missingTextures.add("ERROR block " + e.legacyId + ": " + ex);
            }
        }

        // ---- plain items ----
        for (VariantPlan.ItemEntry e : plan.items) {
            try {
                emitItem(e);
            } catch (Exception ex) {
                missingTextures.add("ERROR item " + e.legacyId + ": " + ex);
            }
        }

        // ---- lang ----
        StringBuilder sb = new StringBuilder("{\n");
        int n = 0;
        for (Map.Entry<String, String> e : langOut.entrySet()) {
            if (n++ > 0) sb.append(",\n");
            sb.append("  ").append(json(e.getKey())).append(": ").append(json(e.getValue()));
            if (LangTable.looksLikeRawKey(e.getValue())) {
                rawKeyLang.add(e.getKey() + " = " + e.getValue());
            }
        }
        sb.append("\n}\n");
        write(out.resolve("assets/" + ns + "/lang/en_us.json"), sb.toString());
    }

    /**
     * Builds the flattening plan, reusing the SAME {@link VariantPlan#build(LegacySnapshot,
     * LangTable, BlockShapeProfile)} overload {@code Registrar}/{@code BootstrapProbe} call so a
     * shape-only twin (see class javadoc) is determined identically everywhere - the
     * triple-consistency this whole codebase relies on.
     *
     * Computing that determination for a real, non-empty profile executes {@code BlockShapes}'
     * {@code net.minecraft.world.phys.shapes} VoxelShape math (the exact builder every real twin's
     * shape already comes from), which requires those classes on THIS process's classpath. This
     * tool has historically been launched with a small classpath (its own compiled classes plus
     * gson - see {@code harness/legacy.ps1}'s {@code Invoke-StagePack}, out of this lane's
     * ownership) that predates that requirement and does not yet include them. Rather than crash
     * every pack generation the moment a mod happens to have a block-shapes.json (a FAR worse
     * regression than simply not emitting shape-only entries), fall back to the plain two-arg
     * build - identical to this tool's entire pre-existing behaviour - and say so loudly. When
     * {@code shapes} is empty (no block-shapes.json / not supplied) this never even attempts the
     * three-arg overload, so there is zero behaviour change for a caller that predates it.
     */
    private VariantPlan buildPlan(LegacySnapshot snap) {
        if (shapes.size() > 0) {
            try {
                return VariantPlan.build(snap, lang, shapes);
            } catch (LinkageError e) {
                System.out.println("PACKGEN WARNING shape-only twins skipped this run: " + e
                        + " (net.minecraft.world.phys.shapes classes not on PackGen's own classpath - "
                        + "add research/visual/mc262-vanilla/classpath.txt's jars to enable resource-pack "
                        + "entries for shape-only twins; falling back to the plain build, zero regression "
                        + "to every other entry)");
            }
        }
        return VariantPlan.build(snap, lang);
    }

    // ------------------------------------------------------------------ blocks

    private void emitBlock(VariantPlan.BlockEntry e) throws IOException {
        if (e.shapeOnly) {
            emitShapeOnlyBlock(e);
            return;
        }
        String path = e.path;
        String[] sides = withFallback(e.sides, e.base.sidesFor(0), e.variant);
        boolean haveIcons = sides != null;
        boolean staticless = isStaticless(e.base);
        if (!haveIcons && !staticless) blocksNoIcon++;
        if (staticless) blocksInvisible++;
        if (e.variant) blockVariants++;

        // The spec deliberately skips model emission when a block has NO icon at all, so the
        // vanilla "missing model" placeholder shows instead of a silently wrong cube - UNLESS
        // a renderType -1 block is TESR/custom-renderer-owned in 1.7.10. Its six icons are
        // particle/item data, not a permission to invent a static cube in the world.
        if (haveIcons || staticless) {
            write(out.resolve("assets/" + ns + "/blockstates/" + path + ".json"),
                    "{\n  \"variants\": {\n    \"\": {\n      \"model\": \"" + ns + ":block/" + path + "\"\n    }\n  }\n}\n");
            blockstates++;

            write(out.resolve("assets/" + ns + "/models/block/" + path + ".json"),
                    staticless ? emptyBlockModelJson(e.legacyId) : blockModelJson(sides, e.legacyId));
            blockModels++;

            // ItemBlock: 1.21.4+ client item definition + a model that inherits the block model
            write(out.resolve("assets/" + ns + "/items/" + path + ".json"),
                    itemDefinition(ns + ":item/" + path));
            itemDefs++;
            write(out.resolve("assets/" + ns + "/models/item/" + path + ".json"),
                    "{\n  \"parent\": \"" + ns + ":block/" + path + "\"\n}\n");
            itemModels++;
        }

        langOut.put("block." + ns + "." + path, e.displayName);
    }

    /** True when the legacy block's ordinary block pass rendered nothing. */
    static boolean isStaticless(BlockRec base) {
        return base != null && base.renderType == -1;
    }

    /**
     * A block model that draws zero quads: {@code minecraft:block/block} (verified in the 26.2
     * client jar: display transforms only, no elements) plus an explicit particle texture so the
     * model never trips a missing-particle bake error. The particle names the same
     * {@code minecraft:block/stone} fallback {@link #textureRef} already uses for a null block
     * icon - and it is unobservable by construction, since callers only use this model when
     * {@link #isStaticless} holds. The particle is retained for break/hit effects; it never adds
     * world quads.
     */
    static String emptyBlockModelJson(String legacyId) {
        return "{\n  \"parent\": \"minecraft:block/block\",\n  \"textures\": {\n    \"particle\": "
                + json("minecraft:block/stone") + "\n  }\n}\n";
    }

    /**
     * A shape-only twin ({@link VariantPlan.BlockEntry#shapeOnly}): a Block-only registry entry
     * for a metadata a mod's OWN runtime placement code writes (e.g. a multiblock controller's
     * "activated core" tier), never a {@code subBlocks} choice a player selects from a menu - see
     * {@code multiblock-notes/SHAPE-VARIANT-LANE.md}. It gets its own blockstate + block model
     * (the registered Block needs SOME model, or 26.2 falls back to its own undeclared-state
     * rendering - purple/black or nothing - instead of the block's real, correct appearance), but
     * deliberately NO item definition, item model, or lang row: {@link VariantPlan.BlockEntry
     * #shapeOnly}'s own contract (mirrored by {@code Registrar}'s block-registration skip)
     * guarantees this entry has no {@code BlockItem} and never will, so an item-side pack entry
     * would describe something that can never exist in the live registries, and {@code
     * langOut.put} with a null {@link VariantPlan.BlockEntry#displayName} (shape-only entries have
     * none, on purpose) would otherwise write a literal JSON {@code null} lang value and a bogus
     * "still a raw key" report row for a name that was never even attempted.
     *
     * Visual-fidelity: {@code e.sides} already IS the SAME six-icon row as the block's base
     * metadata for a shape-only twin unless the 1.7.10 extractor recorded a per-meta icon row
     * specific to this exact meta ({@link BlockRec#sidesFor} falls back to the meta-0 row
     * otherwise) - independently verified against the real corpus this feature was built for:
     * all 1150 real shape-only twins resolve to the identical icon row as their base metadata (0
     * have their own recorded row), confirming a mod rarely swaps texture between "same block,
     * different structural state." This method never guesses a texture: it reuses whatever {@code
     * sidesFor(meta)} already resolved, so a future mod whose extractor DID capture a genuinely
     * different per-meta icon would automatically get that correct, specific visual instead.
     */
    private void emitShapeOnlyBlock(VariantPlan.BlockEntry e) throws IOException {
        String path = e.path;
        String[] sides = withFallback(e.sides, e.base.sidesFor(0), e.variant);
        boolean staticless = isStaticless(e.base);
        if (sides == null && !staticless) {
            // same "no icon at all -> no model" rule as a normal entry (see emitBlock) - and,
            // unlike emitBlock, no lang row either: a shape-only twin has no display name to log.
            blocksNoIcon++;
            return;
        }

        write(out.resolve("assets/" + ns + "/blockstates/" + path + ".json"),
                "{\n  \"variants\": {\n    \"\": {\n      \"model\": \"" + ns + ":block/" + path + "\"\n    }\n  }\n}\n");
        blockstates++;

        if (staticless) {
            write(out.resolve("assets/" + ns + "/models/block/" + path + ".json"),
                    emptyBlockModelJson(e.legacyId));
            blocksInvisible++;
        } else if (sides == null) {
            write(out.resolve("assets/" + ns + "/models/block/" + path + ".json"),
                    emptyBlockModelJson(e.legacyId));
            blocksInvisible++;
        } else {
            write(out.resolve("assets/" + ns + "/models/block/" + path + ".json"),
                    blockModelJson(sides, e.legacyId));
        }
        blockModels++;
        blockShapeOnly++;
        // Deliberately no items/, models/item/, or langOut entry - see this method's javadoc.
    }

    /** The block model JSON shared by a normal block entry and a shape-only twin. */
    private String blockModelJson(String[] sides, String legacyId) {
        if (BlockRec.allEqual(sides)) {
            String tex = textureRef("block", sides[0], legacyId);
            return "{\n  \"parent\": \"minecraft:block/cube_all\",\n  \"textures\": {\n    \"all\": "
                    + json(tex) + "\n  }\n}\n";
        }
        StringBuilder m = new StringBuilder("{\n  \"parent\": \"minecraft:block/cube\",\n  \"textures\": {\n");
        String particle = null;
        for (int i = 0; i < 6; i++) {
            String icon = sides[i] != null ? sides[i] : firstNonNull(sides);
            String tex = textureRef("block", icon, legacyId);
            if (particle == null) particle = tex;
            m.append("    ").append(json(FACES[i])).append(": ").append(json(tex)).append(",\n");
        }
        m.append("    \"particle\": ").append(json(particle)).append("\n  }\n}\n");
        return m.toString();
    }

    // ------------------------------------------------------------------- items

    private void emitItem(VariantPlan.ItemEntry e) throws IOException {
        String path = e.path;
        if (e.variant) itemVariants++;
        // A sub-item icon that has no png in the mod jar means HBM builds that sprite at runtime
        // (e.g. "bedrock_ore_base_light-0", stitched by a TextureAtlasSprite subclass). Falling
        // back to the base item's icon shows the family texture instead of the missing-texture
        // checkerboard.
        String icon = e.icon;
        if (!canResolve("item", icon) && canResolve("item", e.base.icon())) {
            icon = e.base.icon();
            iconFallbacks++;
        }
        String tex = textureRef("item", icon, e.legacyId);
        write(out.resolve("assets/" + ns + "/items/" + path + ".json"),
                itemDefinition(ns + ":item/" + path));
        itemDefs++;
        write(out.resolve("assets/" + ns + "/models/item/" + path + ".json"),
                "{\n  \"parent\": \"minecraft:item/generated\",\n  \"textures\": {\n    \"layer0\": "
                        + json(tex) + "\n  }\n}\n");
        itemModels++;

        langOut.put("item." + ns + "." + path, e.displayName);
    }

    /**
     * Replace any face whose icon has no png with the same face of the block's meta-0 row.
     * Only for variants: the base row is the fallback, so it is left exactly as the snapshot
     * recorded it and still reports its own misses.
     */
    private String[] withFallback(String[] row, String[] base, boolean isVariant) {
        if (row == null || base == null || !isVariant || row == base) return row;
        String[] out = null;
        for (int i = 0; i < 6; i++) {
            if (canResolve("block", row[i])) continue;
            String alt = base[i] != null ? base[i] : firstNonNull(base);
            if (!canResolve("block", alt)) continue;
            if (out == null) out = row.clone();
            out[i] = alt;
            iconFallbacks++;
        }
        return out != null ? out : row;
    }

    /** true when this legacy icon name will actually resolve to a texture in the finished pack. */
    boolean canResolve(String kind, String icon) {
        if (icon == null) return false;
        int colon = icon.indexOf(':');
        String iconNs = colon > 0 ? icon.substring(0, colon) : "minecraft";
        String iconPath = colon >= 0 ? icon.substring(colon + 1) : icon;
        if ("minecraft".equals(iconNs)) return true;
        String key = kind + '|' + iconNs + '|' + iconPath;
        Boolean known = resolvable.get(key);
        if (known != null) return known;
        boolean ok = Files.isRegularFile(jarSource(kind, iconNs, iconPath));
        if (!ok && runtime != null) {
            // A runtime-composited icon has no png anywhere in the mod jar, but the atlas dump
            // has its real pixels - so it resolves after all, and must NOT be replaced by the
            // base item's icon further up.
            ok = runtime.find(kind, icon, iconNs, iconPath) != null;
        }
        resolvable.put(key, ok);
        return ok;
    }

    /** Where 1.7.10 would have shipped this icon inside the mod jar. */
    private Path jarSource(String kind, String iconNs, String iconPath) {
        String legacyDir = "block".equals(kind) ? "blocks" : "items";
        return assetsRoot.resolve("assets/" + iconNs + "/textures/" + legacyDir + "/" + iconPath + ".png");
    }

    private static String itemDefinition(String modelId) {
        return "{\n  \"model\": {\n    \"type\": \"minecraft:model\",\n    \"model\": "
                + json(modelId) + "\n  }\n}\n";
    }

    // ---------------------------------------------------------------- textures

    /**
     * Resolve a 1.7.10 icon name to a modern texture id, copying the png into the pack.
     * legacyKind is "block" or "item" (modern, singular); 1.7.10 stored them under blocks/ items/.
     */
    String textureRef(String kind, String icon, String ownerId) {
        if (icon == null) {
            texturesMissing++;
            missingTextures.add(ownerId + " [" + kind + "] <no icon>");
            return "minecraft:" + kind + "/" + ("block".equals(kind) ? "stone" : "barrier");
        }
        int colon = icon.indexOf(':');
        String iconNs = colon > 0 ? icon.substring(0, colon) : "minecraft";
        String iconPath = colon >= 0 ? icon.substring(colon + 1) : icon;

        if ("minecraft".equals(iconNs)) {
            String mapped = VANILLA_BLOCK_ALIASES.getOrDefault(iconPath, iconPath);
            return "minecraft:" + kind + "/" + LegacyIds.sanitizePath(mapped);
        }

        String sanitized = LegacyIds.sanitizePath(iconPath);
        // jarSource/runtime.find read the SOURCE (the mod jar / atlas dump), which is laid out
        // under whatever casing the icon string itself carries - keep iconNs raw for those.
        Path src = jarSource(kind, iconNs, iconPath);
        boolean fromRuntime = false;
        if (!Files.isRegularFile(src) && runtime != null) {
            Path alt = runtime.find(kind, icon, iconNs, iconPath);
            if (alt != null) {
                src = alt;
                fromRuntime = true;
            }
        }
        // GENERALITY fix (laneCasing, Bug 1): everything written INTO the generated pack - the
        // destination file and the reference returned to the caller - must use the sanitized,
        // Identifier-safe namespace, never the icon string's raw one.
        String sanitizedIconNs = LegacyIds.sanitizeNamespace(iconNs);
        Path dst = out.resolve("assets/" + sanitizedIconNs + "/textures/" + kind + "/" + sanitized + ".png");
        String key = dst.toString();
        if (!copiedTextures.contains(key)) {
            if (Files.isRegularFile(src)) {
                try {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    copiedTextures.add(key);
                    texturesCopied++;
                    if (fromRuntime) {
                        texturesFromRuntimeDump++;
                        runtimeSourced.add(icon + " [" + kind + "] <- " + src);
                    }
                    // 1.7.10 animated textures ship a sibling .png.mcmeta. The runtime dump is
                    // frame 0 only (a single still), so it never has one.
                    Path meta = Path.of(src + ".mcmeta");
                    if (Files.isRegularFile(meta)) {
                        Files.copy(meta, Path.of(dst + ".mcmeta"), StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    texturesMissing++;
                    missingTextures.add(ownerId + " [" + kind + "] copy failed " + src + ": " + e);
                }
            } else {
                texturesMissing++;
                missingTextures.add(ownerId + " [" + kind + "] missing source " + src);
            }
        }
        return sanitizedIconNs + ":" + kind + "/" + sanitized;
    }

    // ---------------------------------------------------------- Task B: GUI textures

    /** One {@link #copyGuiTextures} run's outcome, for the caller's own reporting. */
    public static final class GuiTextureReport {
        public final int copied;
        public final int missing;
        public final List<String> missingDetails;

        GuiTextureReport(int copied, int missing, List<String> missingDetails) {
            this.copied = copied;
            this.missing = missing;
            this.missingDetails = missingDetails;
        }
    }

    /**
     * Task B (laneConsume-progress.md): copy every GUI background texture gui-profile.json
     * resolved to an existing jar asset into THIS pack, at the exact same namespaced
     * {@code assets/<ns>/textures/...} path the mod jar used - that is the identifier
     * {@link dev.umb.hostagent.content.UmbMenuProvider} builds via {@code Identifier.
     * fromNamespaceAndPath(gui.texture.namespace, gui.texture.path)}, so the path must match
     * byte-for-byte for the Identifier to resolve to a real texture at runtime.
     *
     * Independent of {@link #generate}: the block/item pass above never writes anything under
     * {@code assets/<ns>/textures/gui/}, so this only ever ADDS files to the pack this instance
     * already built, and can also be called on its own (see PackGenTest) without a block/item
     * snapshot at all.
     */
    public GuiTextureReport copyGuiTextures(GuiProfile profile) throws IOException {
        int[] tally = new int[2]; // {copied, missing} - boxed in an array so the helper below can mutate it
        List<String> missingDetails = new ArrayList<>();
        Set<String> alreadyHandled = new LinkedHashSet<>();
        for (GuiProfile.GuiEntry e : profile.entries()) {
            if (e.texture == null) {
                tally[1]++;
                missingDetails.add(e.guiClassName + " (no resolvable background texture in the jar)");
                continue;
            }
            copyOneGuiTexture(e.guiClassName, e.texture, alreadyHandled, missingDetails, tally);
            // SCREEN-RENDER lane: a GUI can bindTexture() more than once in one draw method
            // (GuiProfile.Rect#textureIndex picks the one each rect actually used) - every OTHER
            // resolved texture in the same GUI's bind list must also land in the pack, or a rect
            // that legitimately needs the second sheet would 404 at runtime. e.texture is always
            // the first non-null entry of e.textures, so it is skipped here (already handled
            // above) to avoid double-counting the tally.
            for (GuiProfile.TextureRef t : e.textures) {
                if (t == null || t == e.texture) continue;
                copyOneGuiTexture(e.guiClassName, t, alreadyHandled, missingDetails, tally);
            }
        }
        // Mesh-replay gap (gui-capture lane): the GL-EMU mesh replays WHATEVER texture the mod
        // bound at runtime - including wrapper-style binds no static profile resolves - so every
        // GUI art file the mod ships must be in the pack, not only profile-resolved backgrounds.
        copyUnprofiledGuiArt(alreadyHandled, missingDetails, tally);
        return new GuiTextureReport(tally[0], tally[1], missingDetails);
    }

    /**
     * Copies every GUI art PNG the mod jar ships that the profile pass did not already handle,
     * into the same sanitized destination convention. Walks each on-disk namespace's
     * {@code textures/gui/**} so no casing assumption and no mod, namespace, or GUI class name
     * appears here; PNG only (sources, PSDs and other non-runtime files stay out of the pack).
     */
    private void copyUnprofiledGuiArt(Set<String> alreadyHandled, List<String> missingDetails,
                                      int[] tally) throws IOException {
        Path assetsDir = assetsRoot.resolve("assets");
        if (!Files.isDirectory(assetsDir)) return;
        List<Path> namespaces = new ArrayList<>();
        try (var stream = Files.list(assetsDir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(child)) namespaces.add(child);
            }
        }
        namespaces.sort(null);
        for (Path nsDir : namespaces) {
            String sanitizedNs = LegacyIds.sanitizeNamespace(nsDir.getFileName().toString());
            Path guiDir = nsDir.resolve("textures/gui");
            if (!Files.isDirectory(guiDir)) continue;
            List<Path> files = new ArrayList<>();
            try (var walk = Files.walk(guiDir)) {
                for (Path src : (Iterable<Path>) walk::iterator) {
                    if (Files.isRegularFile(src)
                            && src.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
                                    .endsWith(".png")) {
                        files.add(src);
                    }
                }
            }
            files.sort(null);
            for (Path src : files) {
                String rel = guiDir.relativize(src).toString().replace('\\', '/');
                String sanitizedPath = LegacyIds.sanitizePath("textures/gui/" + rel);
                String key = sanitizedNs + '|' + sanitizedPath;
                if (!alreadyHandled.add(key)) continue;
                Path dst = out.resolve("assets/" + sanitizedNs + "/" + sanitizedPath);
                try {
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    tally[0]++;
                } catch (IOException ex) {
                    tally[1]++;
                    missingDetails.add("gui-art copy failed " + src + ": " + ex);
                }
            }
        }
    }

    /** Copies one resolved GUI texture into the pack (dedup'd by destination path across the whole
     *  {@link #copyGuiTextures} run - several GUIs, or several bind slots in one GUI, commonly
     *  share one sheet). {@code tally[0]} += copied, {@code tally[1]} += missing. */
    private void copyOneGuiTexture(String guiClassName, GuiProfile.TextureRef texture,
                                    Set<String> alreadyHandled, List<String> missingDetails, int[] tally) throws IOException {
        // GENERALITY fix (laneCasing, Bug 1): the SOURCE lives wherever umb-guimap actually
        // found it in the jar (assetPath, raw casing preserved) - but the DESTINATION inside
        // the generated pack must use the sanitized namespace/path, matching what
        // UmbMenuProvider builds into a live Identifier at menu-open time. For an
        // already-lowercase namespace (every case today) this is byte-for-byte the same path.
        String sanitizedGuiNs = LegacyIds.sanitizeNamespace(texture.namespace);
        String sanitizedGuiPath = LegacyIds.sanitizePath(texture.path);
        String key = sanitizedGuiNs + '|' + sanitizedGuiPath;
        if (!alreadyHandled.add(key)) return; // already copied (or already recorded missing) once
        Path src = assetsRoot.resolve(texture.assetPath);
        Path dst = out.resolve("assets/" + sanitizedGuiNs + "/" + sanitizedGuiPath);
        if (!Files.isRegularFile(src)) {
            tally[1]++;
            missingDetails.add(guiClassName + " missing source " + src);
            return;
        }
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            tally[0]++;
        } catch (IOException ex) {
            tally[1]++;
            missingDetails.add(guiClassName + " copy failed " + src + ": " + ex);
        }
    }

    // ------------------------------------------------------------------- misc

    String packMcmeta() {
        // GENERALITY fix (hostagent-purge #17): this used to hardcode "HBM 1.7.10 content" for
        // every generated pack, regardless of which mod was actually packed. `ns` is this run's
        // real namespace (constructor already sanitizes it), so interpolate it instead - the
        // string is byte-identical to before only when ns=hbm, which is the test corpus's actual
        // namespace, so today's output is unchanged.
        return "{\n  \"pack\": {\n"
                + "    \"description\": \"UMB generated pack - " + ns + " 1.7.10 content on Minecraft 26.2\",\n"
                + "    \"min_format\": " + RESOURCE_MAJOR + ",\n"
                + "    \"max_format\": " + RESOURCE_MAJOR + "\n"
                + "  }\n}\n";
    }

    void writeReport(Path report, LegacySnapshot snap) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("UMB PackGen report\n");
        sb.append("namespace=").append(ns);
        if (!ns.equals(legacyNs)) sb.append(" (legacy id namespace: ").append(legacyNs).append(')');
        sb.append('\n');
        sb.append("snapshot blocks=").append(snap.blocks.size())
                .append(" items=").append(snap.items.size()).append('\n');
        sb.append("plan ").append(plan.stats()).append('\n');
        sb.append("blockstates=").append(blockstates)
                .append(" blockModels=").append(blockModels)
                .append(" itemDefs=").append(itemDefs)
                .append(" itemModels=").append(itemModels)
                .append(" texturesCopied=").append(texturesCopied)
                .append(" texturesMissing=").append(texturesMissing)
                .append(" texturesFromRuntimeDump=").append(texturesFromRuntimeDump)
                .append(" blocksWithoutIcons=").append(blocksNoIcon)
                .append(" blocksInvisible=").append(blocksInvisible)
                .append(" blockVariants=").append(blockVariants)
                .append(" blockShapeOnly=").append(blockShapeOnly)
                .append(" itemVariants=").append(itemVariants)
                .append(" iconFallbacks=").append(iconFallbacks)
                .append(" langKeys=").append(langOut.size())
                .append(" langRawKeys=").append(rawKeyLang.size()).append('\n');
        List<String> dupes = plan.duplicatePaths();
        sb.append("\n--- duplicate registry paths (").append(dupes.size()).append(") ---\n");
        for (String s : dupes) sb.append(s).append('\n');
        sb.append("\n--- lang values that still look like raw keys (").append(rawKeyLang.size()).append(") ---\n");
        for (String s : rawKeyLang) sb.append(s).append('\n');
        // GENERALITY fix (hostagent-purge, cosmetic companion to #17): report text hardcoded
        // "HBM" regardless of which mod's lang file was actually missing the row.
        sb.append("\n--- display names synthesised from the id, ").append(ns)
                .append(" has no en_US.lang row (")
                .append(plan.humanised.size()).append(") ---\n");
        for (String s : plan.humanised) sb.append(s).append('\n');
        sb.append("\n--- textures taken from the runtime atlas dump, no png in the mod jar (")
                .append(runtimeSourced.size()).append(") ---\n");
        for (String s : runtimeSourced) sb.append(s).append('\n');
        sb.append("\n--- unresolved textures (").append(missingTextures.size()).append(") ---\n");
        for (String s : missingTextures) sb.append(s).append('\n');
        if (report.getParent() != null) Files.createDirectories(report.getParent());
        Files.writeString(report, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String firstNonNull(String[] a) {
        for (String s : a) if (s != null) return s;
        return null;
    }

    private static void write(Path p, String content) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.writeString(p, content, StandardCharsets.UTF_8);
    }

    static String json(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    // ----------------------------------------------------------- runtime atlas dump

    /**
     * The second, lower-priority texture source: PNGs cropped out of a running 1.7.10 client's
     * STITCHED texture atlases by fixtures/umb-snapshot-1710 (lane A3).
     *
     * Why a raw-name index at all, when the files are laid out by sanitized path? Because the
     * sanitizer is lossy: {@code hbm:watz_pellet-HESitem.watz_pellet} and a hypothetical
     * {@code hbm:watz_pellet_hesitem.watz_pellet} collapse onto the same file name. The index maps
     * the EXACT 1.7.10 sprite name the snapshot recorded to the exact file that was written, so
     * lookup is unambiguous; the path-based probes below are only a fallback for a hand-made or
     * index-less dump.
     *
     * Layout it understands (first hit wins):
     *   1. icon-index.json row for (atlas, exact icon name)
     *   2. icon-index.json row for (exact icon name), any atlas
     *   3. assets/&lt;ns&gt;/textures/atlas-dump/&lt;blocks|items&gt;/&lt;sanitized&gt;.png
     *   4. assets/&lt;ns&gt;/textures/atlas-dump/&lt;sanitized&gt;.png            (flat variant)
     *   5. assets/&lt;ns&gt;/textures/&lt;blocks|items&gt;/&lt;iconPath&gt;.png        (plain second assets root)
     */
    static final class RuntimeDump {

        private final Path root;
        /** "blocks|hbm:foo" -> relative file. */
        private final Map<String, String> byAtlasName = new java.util.HashMap<>();
        /** "hbm:foo" -> relative file (whichever atlas was seen first). */
        private final Map<String, String> byName = new java.util.HashMap<>();
        private String indexNote = "no icon-index.json (path lookup only)";

        private RuntimeDump(Path root) {
            this.root = root;
        }

        /** null when the directory is absent - PackGen then behaves exactly as before. */
        static RuntimeDump open(Path root) {
            if (root == null || !Files.isDirectory(root)) return null;
            RuntimeDump d = new RuntimeDump(root);
            Path index = root.resolve("icon-index.json");
            if (Files.isRegularFile(index)) {
                try {
                    d.indexNote = d.loadIndex(index);
                } catch (Exception e) {
                    d.indexNote = "icon-index.json unreadable: " + e;
                }
            }
            return d;
        }

        private String loadIndex(Path index) throws IOException {
            com.google.gson.JsonElement root0;
            try (java.io.Reader r = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
                root0 = com.google.gson.JsonParser.parseReader(r);
            }
            com.google.gson.JsonArray rows = null;
            if (root0 != null && root0.isJsonArray()) {
                rows = root0.getAsJsonArray();
            } else if (root0 != null && root0.isJsonObject()
                    && root0.getAsJsonObject().has("icons")
                    && root0.getAsJsonObject().get("icons").isJsonArray()) {
                rows = root0.getAsJsonObject().getAsJsonArray("icons");
            }
            if (rows == null) return "icon-index.json has no icon rows";
            int n = 0;
            for (com.google.gson.JsonElement e : rows) {
                if (!e.isJsonObject()) continue;
                com.google.gson.JsonObject o = e.getAsJsonObject();
                String name = optString(o, "name");
                String file = optString(o, "file");
                if (name == null || file == null) continue;
                String atlas = optString(o, "atlas");
                if (atlas != null) byAtlasName.putIfAbsent(atlas + '|' + name, file);
                byName.putIfAbsent(name, file);
                n++;
            }
            return "index rows=" + n;
        }

        private static String optString(com.google.gson.JsonObject o, String key) {
            if (o == null || !o.has(key)) return null;
            com.google.gson.JsonElement e = o.get(key);
            if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return null;
            String s = e.getAsString();
            return s.isEmpty() ? null : s;
        }

        String describe() {
            return "root=" + root.toAbsolutePath() + " " + indexNote + " names=" + byName.size();
        }

        /**
         * @param kind     modern singular kind, "block" or "item"
         * @param rawIcon  the 1.7.10 sprite name exactly as the snapshot recorded it ("hbm:foo")
         * @return an existing png, or null
         */
        Path find(String kind, String rawIcon, String iconNs, String iconPath) {
            String atlas = "block".equals(kind) ? "blocks" : "items";
            Path p = fromIndex(byAtlasName.get(atlas + '|' + rawIcon));
            if (p == null) p = fromIndex(byName.get(rawIcon));
            if (p != null) return p;

            String dirNs = LegacyIds.sanitizeNamespace(iconNs);
            String sanitized = LegacyIds.sanitizePath(iconPath);
            if (sanitized == null || sanitized.isEmpty()) return null;
            Path[] probes = {
                    root.resolve("assets/" + dirNs + "/textures/atlas-dump/" + atlas + "/" + sanitized + ".png"),
                    root.resolve("assets/" + dirNs + "/textures/atlas-dump/" + sanitized + ".png"),
                    root.resolve("assets/" + iconNs + "/textures/" + atlas + "/" + iconPath + ".png"),
            };
            for (Path probe : probes) {
                if (Files.isRegularFile(probe)) return probe;
            }
            return null;
        }

        private Path fromIndex(String rel) {
            if (rel == null) return null;
            Path p = root.resolve(rel);
            return Files.isRegularFile(p) ? p : null;
        }
    }

    /** Unused-but-kept accessor for tests. */
    public Map<String, String> langEntries() {
        return new LinkedHashMap<>(langOut);
    }

    public List<String> rawKeyLangValues() {
        return new ArrayList<>(rawKeyLang);
    }
}
