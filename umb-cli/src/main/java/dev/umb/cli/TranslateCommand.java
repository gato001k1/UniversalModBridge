package dev.umb.cli;

import dev.umb.cache.CacheKey;
import dev.umb.cache.CachedEntry;
import dev.umb.cache.ReproRecord;
import dev.umb.cache.Sha256;
import dev.umb.cache.TranslatedJarCache;
import dev.umb.core.BasicModAnalyzer;
import dev.umb.core.ModAnalysis;
import dev.umb.mappings.DefaultMappingGraph;
import dev.umb.mappings.GraphJarRemapper;
import dev.umb.mappings.MappingGraph;
import dev.umb.pipeline.MixinApplyPass;
import dev.umb.pipeline.PassReport;
import dev.umb.pipeline.RenderPipelinePass;
import dev.umb.pipeline.StructuralRepairPass;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * {@code umb translate IN.jar OUT.jar --cache DIR --src-ver VER --host VER}: the
 * end-to-end pipeline command (M4): load the mapping specs into the shared graph,
 * detect-or-validate the source {@code (version,namespace)} of the mod jar, remap
 * its linked references onto the host node, and memoize the result in the
 * content-addressed translated-jar cache so a re-run with unchanged inputs is a
 * byte-identical cache HIT instead of a re-remap. Spec grammar is the SAME
 * ingestion contract as remap/graph-audit — one helper, {@link SpecIngestion},
 * keeps the three commands agreeing on what a malformed spec means.
 *
 * <p>Cache identity (spec §23): the key is the source jar SHA-256 + host version +
 * loader + translator version + a composite of every loaded spec file's
 * {@code basename=sha256} (deterministic: specs bind tiny/proguard/derived, then
 * CLI argument order within each flag) + the patch-set slot. A HIT (unless
 * --force) copies the stored, integrity-verified jar bytes to OUT byte-for-byte
 * and exits 0 without remapping; a MISS remaps, then refuses vacuously-small
 * results (D4 guards identical to remap — but translate has no --allow-empty,
 * so the refusal is unconditional) and only on success stores the
 * {@link ReproRecord} beside the output so the run is reproducible without
 * rerunning the pipeline. A corrupted cache entry degrades to a MISS (the store
 * quarantines it), never a crash.
 */
@Command(name = "translate",
        mixinStandardHelpOptions = true,
        version = "umb 0.1 (M4)",
        description = "Remap IN.jar onto a host node and memoize the result in the translated-jar cache.",
        exitCodeOnInvalidInput = 1)
public final class TranslateCommand implements Callable<Integer> {

    private static final String TRANSLATOR_VERSION = "umb 0.1 (M11)";
    private static final String COMPAT_DB_VERSION = "none";
    private static final String PATCH_DB_VERSION = "none";

    @Parameters(index = "0", description = "Mod jar to translate.")
    Path inJar;

    @Parameters(index = "1", description = "Output jar (must not equal IN).")
    Path outJar;

    @Option(names = "--cache", required = true,
            description = "Translated-jar cache directory (created if absent).")
    Path cacheDir;

    @Option(names = "--src-ver", required = true,
            description = "Source MC version of the mod jar (cannot be auto-detected).")
    String srcVer;

    @Option(names = "--host", required = true,
            description = "Host MC version to translate onto.")
    String hostVer;

    @Option(names = "--src-ns",
            description = "Source namespace; default: auto-detect from the jar "
                    + "(mojang, intermediary, or obfuscated -> official).")
    String srcNs;

    @Option(names = "--host-ns", defaultValue = "mojang",
            description = "Host namespace (default ${DEFAULT-VALUE}).")
    String hostNs;

    @Option(names = "--loader", defaultValue = "none",
            description = "Host loader context (default ${DEFAULT-VALUE}); part of the cache key.")
    String loader;

    @Option(names = "--tiny", arity = "1",
            description = "Mapping set to load as " + SpecIngestion.SPEC_USAGE + "; repeatable.")
    List<String> tinySpecs;

    @Option(names = "--proguard", arity = "1",
            description = "ProGuard-format mapping (Mojang client.txt) to load as "
                    + SpecIngestion.SPEC_USAGE + "; repeatable.")
    List<String> proguardSpecs;

    @Option(names = "--srg", arity = "1",
            description = "MCPConfig joined.srg mapping (srg ↔ obfuscated-runtime) to "
                    + "load as " + SpecIngestion.SPEC_USAGE + "; repeatable.")
    List<String> srgSpecs;

    @Option(names = "--derived-tiny", arity = "1",
            description = "Matcher-emitted tiny file (from umb match) to load at DERIVED_MATCH "
                    + "confidence (0.7) as " + SpecIngestion.SPEC_USAGE + "; repeatable.")
    List<String> derivedTinySpecs;

    @Option(names = "--force",
            description = "Bypass the cache lookup and re-remap, overwriting any stored entry.")
    boolean force;

    @Option(names = {"--max-report"}, defaultValue = "10",
            description = "Cap listed warnings (default ${DEFAULT-VALUE}).")
    int maxReport;

    @Spec
    CommandSpec cmdSpec;

    @Override
    public Integer call() {
        PrintWriter out = cmdSpec.commandLine().getOut();
        PrintWriter err = cmdSpec.commandLine().getErr();

        if (!Files.isRegularFile(inJar)) {
            err.println("error: no such file: " + inJar);
            return 1;
        }
        boolean noSpecs = (tinySpecs == null || tinySpecs.isEmpty())
                && (proguardSpecs == null || proguardSpecs.isEmpty())
                && (srgSpecs == null || srgSpecs.isEmpty())
                && (derivedTinySpecs == null || derivedTinySpecs.isEmpty());
        if (noSpecs) {
            err.println("error: no mapping given (want --tiny, --proguard, --srg or --derived-tiny, "
                    + "each " + SpecIngestion.SPEC_USAGE + ")");
            return 1;
        }
        if (Files.exists(outJar) && outJar.toAbsolutePath().normalize()
                .equals(inJar.toAbsolutePath().normalize())) {
            err.println("error: OUT must be a different file than IN");
            return 1;
        }
        if (srcVer.isEmpty() || hostVer.isEmpty()) {
            err.println("error: --src-ver and --host must be non-empty");
            return 1;
        }
        if (srcNs != null && srcNs.isEmpty()) {
            err.println("error: --src-ns must be non-empty");
            return 1;
        }
        if (hostNs.isEmpty() || loader.isEmpty()) {
            err.println("error: --host-ns and --loader must be non-empty");
            return 1;
        }

        var graph = new DefaultMappingGraph();
        List<SpecIngestion.Result> loaded = new ArrayList<>();
        if (tinySpecs != null) {
            for (String raw : tinySpecs) {
                SpecIngestion.Result res =
                        SpecIngestion.ingest(graph, raw, SpecIngestion.SpecKind.TINY, err);
                if (res == null) {
                    return 1; // reason already on stderr
                }
                loaded.add(res);
            }
        }
        if (proguardSpecs != null) {
            for (String raw : proguardSpecs) {
                SpecIngestion.Result res =
                        SpecIngestion.ingest(graph, raw, SpecIngestion.SpecKind.PROGUARD, err);
                if (res == null) {
                    return 1;
                }
                loaded.add(res);
            }
        }
        if (srgSpecs != null) {
            for (String raw : srgSpecs) {
                SpecIngestion.Result res =
                        SpecIngestion.ingest(graph, raw, SpecIngestion.SpecKind.SRG, err);
                if (res == null) {
                    return 1;
                }
                loaded.add(res);
            }
        }
        if (derivedTinySpecs != null) {
            for (String raw : derivedTinySpecs) {
                SpecIngestion.Result res =
                        SpecIngestion.ingest(graph, raw, SpecIngestion.SpecKind.DERIVED, err);
                if (res == null) {
                    return 1;
                }
                loaded.add(res);
            }
        }

        // Source node: --src-ns when given, else the M0 analyzer's verdict mapped to
        // graph names exactly as the remap specs spell them. The VERSION can never be
        // auto-detected (a fabric.mod.json constraint is a RANGE, not a pin), so
        // --src-ver is mandatory regardless of how the namespace resolves.
        String sourceNs = srcNs != null ? srcNs : detectNamespace(inJar, err);
        if (sourceNs == null) {
            return 1; // reason already on stderr
        }
        MappingGraph.Node sourceNode = new MappingGraph.Node(srcVer, sourceNs);
        MappingGraph.Node hostNode = new MappingGraph.Node(hostVer, hostNs);
        if (sourceNode.equals(hostNode)) {
            err.println("error: host node " + SpecIngestion.label(hostNode)
                    + " equals the source node " + SpecIngestion.label(sourceNode)
                    + " — a translation cannot map a node to itself");
            return 1;
        }

        // Same D5 guard as remap's --pair override: a pair reaching a node no spec
        // bridges would silently translate to empty paths and trip guard-2 below
        // with a misleading "jar does not use namespace" message.
        Set<String> bridged = new HashSet<>();
        for (SpecIngestion.Result p : loaded) {
            bridged.add(SpecIngestion.label(p.a()));
            bridged.add(SpecIngestion.label(p.b()));
        }
        String unbridged = !bridged.contains(SpecIngestion.label(sourceNode))
                ? SpecIngestion.label(sourceNode)
                : !bridged.contains(SpecIngestion.label(hostNode))
                ? SpecIngestion.label(hostNode) : null;
        if (unbridged != null) {
            err.println("error: " + unbridged + " is not bridged by any loaded spec"
                    + " — no translation path reaches it");
            return 1;
        }

        // Content+environment identity (spec §23): source jar SHA-256, host node and
        // loader, the fixed translator/compat/patch slots, and a deterministic
        // composite of every loaded spec's basename=sha256. Hashing once keeps the
        // HIT path and the ReproRecord's first-16-hex views derived from the SAME
        // bytes (determinism where cheap).
        String sourceHash = sha256(inJar, err);
        if (sourceHash == null) {
            return 1;
        }
        Map<String, String> specShaMap = specShas(loaded, err);
        if (specShaMap == null) {
            return 1;
        }
        String mappingVersion = specShaMap.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(";"));
        Map<String, String> reproVersions = new LinkedHashMap<>();
        specShaMap.forEach((basename, sha) -> reproVersions.put(basename, sha.substring(0, 16)));
        CacheKey key = new CacheKey(sourceHash, hostVer, loader, TRANSLATOR_VERSION,
                COMPAT_DB_VERSION, mappingVersion, PATCH_DB_VERSION);

        // ---- HIT path (unless --force): byte-identical copy of the stored jar,
        // no remap. The store already verified integrity on read; a broken read
        // degrades to a MISS like the store's own quarantine path.
        if (!force) {
            Optional<CachedEntry> hit;
            try {
                hit = TranslatedJarCache.at(cacheDir).get(key);
            } catch (IOException e) {
                hit = Optional.empty(); // tolerance: never crash a run that can remap
            }
            if (hit.isPresent()) {
                CachedEntry entry = hit.get();
                try {
                    Files.createDirectories(outJar.toAbsolutePath().getParent());
                    Files.copy(entry.jarPath(), outJar, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException e) {
                    err.println("error: cannot write " + outJar + ": " + e.getMessage());
                    return 1;
                }
                out.println("cache: HIT " + key.asPathSegment());
                printRepro(out, entry.meta());
                return 0;
            }
            // MISS: fall through to the remap below; the "cache: MISS stored" line
            // prints after the report, only on success.
        }

        // ---- MISS (or --force): remap source node -> host node, then AT+mixin apply ----
        // M8-5 pipeline order: remap (graph renames) -> MixinApplyPass (AT widening + accessor + MixinRewriter)
        // sits strictly after remap and before smoke/launch, composing onto the remap output (§24).
        Path remapStaging = null;
        GraphJarRemapper.Result r;
        try {
            remapStaging = stashRemapStaging(outJar);
            r = new GraphJarRemapper(graph).remap(inJar, remapStaging, sourceNode, hostNode);
        } catch (IOException e) {
            err.println("error: cannot write " + outJar + ": " + e.getMessage());
            if (remapStaging != null) deleteRefusedOutput(remapStaging, err);
            return 1;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            if (remapStaging != null) deleteRefusedOutput(remapStaging, err);
            return 1;
        }

        // D4, two guards identical to remap — but translate has no --allow-empty so
        // a refusal is unconditional. The remapper publishes the STAGING artifact
        // atomically BEFORE these counts are visible, and a refused run must leave
        // NEITHER OUT nor any cache entry: delete the staging (and any OUT), then
        // return before any put().
        if (r.classesRemapped() == 0) {
            deleteRefusedOutput(remapStaging, err);
            deleteRefusedOutput(outJar, err);
            err.println("warning: no class in " + inJar + " could be parsed as a classfile; "
                    + "refusing to emit a vacuous result (D4)");
            return 1;
        }
        if (r.symbolsTranslated() == 0 && r.classesRemapped() > 0) {
            deleteRefusedOutput(remapStaging, err);
            deleteRefusedOutput(outJar, err);
            err.println("warning: 0 symbols translated across " + r.classesRemapped()
                    + " classes — the jar likely does not use namespace '" + sourceNode.ns() + "'");
            return 1;
        }

        // M8-5: compose AT/mixin onto the remap output, then M11 structural, then M10 render.
        // Order: remap (graph names -> host) -> AT/mixin (host names)
        //        -> structural (supertypes are host names only after remap) -> render (assets).
        ModAnalysis mixinAnalysis;
        try {
            mixinAnalysis = new BasicModAnalyzer().analyze(inJar);
        } catch (IOException e) {
            err.println("error: cannot analyze " + inJar + " for mixin pass: " + e.getMessage());
            deleteRefusedOutput(remapStaging, err);
            deleteRefusedOutput(outJar, err);
            return 1;
        }
        Path mixinStaging = null;
        PassReport mixinReport;
        try {
            mixinStaging = outJar.resolveSibling(outJar.getFileName() + ".mixin-" + Long.toUnsignedString(System.nanoTime()) + ".tmp");
            mixinReport = new MixinApplyPass().run(mixinAnalysis, remapStaging, mixinStaging);
        } catch (Exception e) {
            err.println("error: mixin pass failed: " + e.getMessage());
            deleteRefusedOutput(remapStaging, err);
            if (mixinStaging != null) deleteRefusedOutput(mixinStaging, err);
            deleteRefusedOutput(outJar, err);
            return 1;
        } finally {
            try { Files.deleteIfExists(remapStaging); } catch (IOException ignored) {}
        }
        if (mixinReport.status() == PassReport.Status.FAIL) {
            if (mixinStaging != null) deleteRefusedOutput(mixinStaging, err);
            deleteRefusedOutput(outJar, err);
            for (PassReport.Diagnostic d : mixinReport.diagnostics()) {
                err.println("error: [" + d.code() + "] " + d.message());
            }
            return 1;
        }
        // M11 structural repair: ICCE supertype/method surgery on host names.
        // Non-fatal by design (OK/SKIPPED/WARN — a corpus jar legitimately carries
        // hundreds of unfixable renderers); only an unexpected FAIL aborts.
        Path structuralStaging = null;
        PassReport structuralReport;
        try {
            structuralStaging = outJar.resolveSibling(outJar.getFileName() + ".structural-" + Long.toUnsignedString(System.nanoTime()) + ".tmp");
            structuralReport = new StructuralRepairPass().run(mixinAnalysis, mixinStaging, structuralStaging);
        } catch (Exception e) {
            err.println("error: structural pass failed: " + e.getMessage());
            if (mixinStaging != null) deleteRefusedOutput(mixinStaging, err);
            if (structuralStaging != null) deleteRefusedOutput(structuralStaging, err);
            deleteRefusedOutput(outJar, err);
            return 1;
        } finally {
            if (mixinStaging != null) try { Files.deleteIfExists(mixinStaging); } catch (IOException ignored) {}
        }
        if (structuralReport.status() == PassReport.Status.FAIL) {
            if (structuralStaging != null) deleteRefusedOutput(structuralStaging, err);
            deleteRefusedOutput(outJar, err);
            for (PassReport.Diagnostic d : structuralReport.diagnostics()) {
                err.println("error: [" + d.code() + "] " + d.message());
            }
            return 1;
        }
        // M10 render pipeline: model/blockstate remap + texture namespace translation + atlas hook.
        // Render stage is non-fatal (OK/SKIPPED/WARN); a FAIL would need to be handled like mixin.
        PassReport renderReport;
        try {
            renderReport = new RenderPipelinePass().run(mixinAnalysis, structuralStaging, outJar);
        } catch (Exception e) {
            err.println("error: render pass failed: " + e.getMessage());
            if (structuralStaging != null) deleteRefusedOutput(structuralStaging, err);
            deleteRefusedOutput(outJar, err);
            return 1;
        } finally {
            if (structuralStaging != null) try { Files.deleteIfExists(structuralStaging); } catch (IOException ignored) {}
        }
        if (renderReport.status() == PassReport.Status.FAIL) {
            deleteRefusedOutput(outJar, err);
            for (PassReport.Diagnostic d : renderReport.diagnostics()) {
                err.println("error: [" + d.code() + "] " + d.message());
            }
            return 1;
        }

        // ---- success: record provenance, then store ----
        String outputHash = sha256(outJar, err);
        if (outputHash == null) {
            // The artifact was just published but cannot be read back: refuse to
            // cache an entry whose integrity we cannot attest.
            return 1;
        }
        ReproRecord repro = new ReproRecord(sourceHash, srcVer, reproVersions,
                TRANSLATOR_VERSION, PATCH_DB_VERSION, hostVer, outputHash);
        try {
            TranslatedJarCache.at(cacheDir).put(key, outJar, repro);
        } catch (IOException e) {
            // Tolerance: the OUT artifact is valid and primary; a side-channel store
            // failure is a warning, not a failed run.
            err.println("warning: cache store failed for " + key.asPathSegment()
                    + ": " + e.getMessage());
        }

        out.printf(Locale.ROOT,
                "remapped %s -> %s%n  entries copied : %d%n  classes        : %d%n"
                        + "  symbols mapped : %d%n  symbols kept   : %d%n",
                SpecIngestion.label(sourceNode), SpecIngestion.label(hostNode),
                r.entriesCopied(), r.classesRemapped(),
                r.symbolsTranslated(), r.symbolsUnmapped());
        if (!r.warnings().isEmpty()) {
            out.println("  warnings       : " + r.warnings().size());
            r.warnings().stream().limit(maxReport)
                    .forEach(w -> out.println("    ! " + w));
            if (r.warnings().size() > maxReport) {
                out.printf(Locale.ROOT, "    ... and %d more%n", r.warnings().size() - maxReport);
            }
        }
        if (mixinReport.status() != PassReport.Status.SKIPPED) {
            for (String note : mixinReport.notes()) out.println("  mixin: " + note);
            for (PassReport.Diagnostic d : mixinReport.diagnostics()) {
                out.println("  mixin [" + d.code() + "] " + d.message());
            }
        }
        if (structuralReport.status() != PassReport.Status.SKIPPED) {
            for (String note : structuralReport.notes()) out.println("  structural: " + note);
            for (PassReport.Diagnostic d : structuralReport.diagnostics()) {
                out.println("  structural [" + d.code() + "] " + d.message());
            }
        }
        if (renderReport.status() != PassReport.Status.SKIPPED) {
            for (String note : renderReport.notes()) out.println("  render: " + note);
            for (PassReport.Diagnostic d : renderReport.diagnostics()) {
                out.println("  render [" + d.code() + "] " + d.message());
            }
        }
        out.println("cache: MISS stored " + key.asPathSegment());
        return 0;
    }

    /** Staging beside OUT for the remap output that the AT/mixin pass consumes. */
    private static Path stashRemapStaging(Path outJar) throws IOException {
        Path dir = outJar.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        String base = outJar.getFileName().toString();
        Path staging = outJar.resolveSibling(base + ".remap-" + Long.toUnsignedString(System.nanoTime()) + ".tmp");
        return staging;
    }

    /** A refused run must not leave the artifact GraphJarRemapper already published at OUT. */
    private static void deleteRefusedOutput(Path outJar, PrintWriter err) {
        try {
            Files.deleteIfExists(outJar);
        } catch (IOException e) {
            err.println("error: refused run could not delete stale output " + outJar
                    + ": " + e.getMessage());
        }
    }

    /** Stored-repro facts on a HIT: everything to answer "how was this produced". */
    private static void printRepro(PrintWriter out, ReproRecord meta) {
        out.printf(Locale.ROOT, "  source hash    : %s%n", meta.sourceHash());
        out.printf(Locale.ROOT, "  source version : %s%n", meta.sourceVersion());
        out.printf(Locale.ROOT, "  mapping        : %d specs%n", meta.mappingVersions().size());
        for (Map.Entry<String, String> e : meta.mappingVersions().entrySet()) {
            out.println("    " + e.getKey() + "=" + e.getValue());
        }
        out.printf(Locale.ROOT, "  translator     : %s%n", meta.translatorVersion());
        out.printf(Locale.ROOT, "  host           : %s%n", meta.hostVersion());
        out.printf(Locale.ROOT, "  output hash    : %s%n", meta.outputHash());
    }

    /**
     * Runs the M0 analyzer on the mod jar — the SAME call `umb analyze` makes — and
     * maps its namespace verdict onto the graph names specs spell. OBFSUSCATED is
     * exactly what Fabric labels "official"; SRG/MCP are loader-specific runtime
     * names this wave does not bridge and UNKNOWN means the jar carries no namespace
     * evidence, so any of them is an operator-problem usage error naming --src-ns.
     */
    private static String detectNamespace(Path jar, PrintWriter err) {
        ModAnalysis a;
        try {
            a = new BasicModAnalyzer().analyze(jar);
        } catch (IOException e) {
            err.println("error: cannot read " + jar + ": " + e.getMessage());
            return null;
        }
        ModAnalysis.MappingNamespace ns = a.namespace();
        String graphName = switch (ns) {
            case OBFUSCATED -> "official";
            case MOJANG -> "mojang";
            case INTERMEDIARY -> "intermediary";
            case SRG, MCP, UNKNOWN -> null;
        };
        if (graphName == null) {
            err.println("error: cannot auto-detect the source namespace of " + jar
                    + " (analyzer says " + ns + "); pass --src-ns mojang|intermediary|official");
            return null;
        }
        return graphName;
    }

    /** One {@code basename -> full sha256} per loaded spec, in ingestion order. */
    private static Map<String, String> specShas(List<SpecIngestion.Result> loaded,
                                                 PrintWriter err) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (SpecIngestion.Result res : loaded) {
            String sha = sha256(res.file(), err);
            if (sha == null) {
                return null;
            }
            hashes.put(res.file().getFileName().toString(), sha);
        }
        return hashes;
    }

    /** SHA-256 of a file, or a one-line stderr reason + null (callers exit 1). */
    private static String sha256(Path file, PrintWriter err) {
        try {
            return Sha256.ofFile(file);
        } catch (IOException e) {
            err.println("error: cannot read " + file + ": " + e.getMessage());
            return null;
        }
    }
}