package dev.umb.cli;

import dev.umb.mappings.DefaultMappingGraph;
import dev.umb.mappings.GraphJarRemapper;
import dev.umb.mappings.MappingGraph;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code umb remap IN.jar OUT.jar --tiny|--proguard|--srg|--derived-tiny <file>=<verA>:<nsA>:<verB>:<nsB>}:
 * rewrites every statically linked reference of a mod jar from one
 * {@code (version,namespace)} node of the canonical mapping graph to another
 * (pipeline stage "Remap"). Multiple specs compose — e.g. --proguard client.txt
 * bridges mojang↔official, --tiny the intermediary file bridges official↔intermediary,
 * --srg a pre-flattening MCPConfig joined.srg bridges official↔srg, and a
 * --derived-tiny file from {@code umb match} loads the version bridge at
 * DERIVED_MATCH confidence (0.7). Precedence: --tiny specs bind before --proguard,
 * which bind before --srg, which bind before --derived-tiny (picocli keeps no
 * cross-flag order), so the remap falls between the FIRST processed spec's pair —
 * pass --pair to target a composed pair like mojang↔intermediary that no single
 * file declares.
 *
 * <p>Exit codes (D5/D7): 0 = remap completed, 1 = usage/IO. A completed remap with
 * ZERO parsed classes (empty or manifest-only jar) or zero translated symbols on a
 * class-bearing jar is refused with exit 1 unless --allow-empty is given — per the
 * D4 doctrine this almost always means the requested namespaces don't match the jar,
 * and silently emitting an unchanged copy would poison every downstream stage.
 */
@Command(name = "remap",
        mixinStandardHelpOptions = true,
        version = "umb 0.1 (M0)",
        description = "Rewrite linked references of IN.jar from one mapping-graph namespace to another.",
        exitCodeOnInvalidInput = 1)
public final class RemapCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Jar whose references are rewritten.")
    Path inJar;

    @Parameters(index = "1", description = "Output jar (must not equal IN).")
    Path outJar;

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

    @Option(names = "--pair", arity = "1",
            description = "Remap <verA>:<nsA>:<verB>:<nsB> instead of the first spec's pair — "
                    + "for composed bridges no single file declares (two published hops).")
    String pairSpec;

    @Option(names = "--allow-empty",
            description = "Permit zero translated symbols or zero parseable classes "
                    + "(still exits 0) for jars that legitimately reference nothing in "
                    + "the mapping.")
    boolean allowEmpty;

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

        var graph = new DefaultMappingGraph();
        MappingGraph.Node a = null;
        MappingGraph.Node b = null;
        // Every spec's bridge, for the --pair override and the multi-pair warning.
        List<SpecIngestion.Result> pairs = new ArrayList<>();
        if (tinySpecs != null) {
            for (String raw : tinySpecs) {
                SpecIngestion.Result res =
                        SpecIngestion.ingest(graph, raw, SpecIngestion.SpecKind.TINY, err);
                if (res == null) {
                    return 1; // reason already on stderr
                }
                pairs.add(res);
                // The FIRST spec's pair defines which namespaces we remap between.
                if (a == null) {
                    a = res.a();
                    b = res.b();
                }
            }
        }
        if (proguardSpecs != null) {
            for (String raw : proguardSpecs) {
                SpecIngestion.Result res =
                        SpecIngestion.ingest(graph, raw, SpecIngestion.SpecKind.PROGUARD, err);
                if (res == null) {
                    return 1;
                }
                pairs.add(res);
                if (a == null) {
                    a = res.a();
                    b = res.b();
                }
            }
        }
        if (srgSpecs != null) {
            for (String raw : srgSpecs) {
                SpecIngestion.Result res =
                        SpecIngestion.ingest(graph, raw, SpecIngestion.SpecKind.SRG, err);
                if (res == null) {
                    return 1;
                }
                pairs.add(res);
                if (a == null) {
                    a = res.a();
                    b = res.b();
                }
            }
        }
        if (derivedTinySpecs != null) {
            for (String raw : derivedTinySpecs) {
                SpecIngestion.Result res =
                        SpecIngestion.ingest(graph, raw, SpecIngestion.SpecKind.DERIVED, err);
                if (res == null) {
                    return 1;
                }
                pairs.add(res);
                if (a == null) {
                    a = res.a();
                    b = res.b();
                }
            }
        }

        SpecIngestion.NodePair override = SpecIngestion.parsePairSpec(pairSpec, err);
        if (pairSpec != null && override == null) {
            return 1; // malformed --pair, reason on stderr
        }
        if (override != null) {
            // D5: an override naming nodes no loaded spec bridges would silently
            // translate to empty paths and trip guard-2 below with a misleading
            // "jar does not use namespace" message.
            Set<String> bridged = new HashSet<>();
            for (SpecIngestion.Result p : pairs) {
                bridged.add(SpecIngestion.label(p.a()));
                bridged.add(SpecIngestion.label(p.b()));
            }
            String unbridged = !bridged.contains(SpecIngestion.label(override.a()))
                    ? SpecIngestion.label(override.a())
                    : !bridged.contains(SpecIngestion.label(override.b()))
                    ? SpecIngestion.label(override.b()) : null;
            if (unbridged != null) {
                err.println("error: --pair names " + unbridged + " but no loaded spec bridges it");
                return 1;
            }
            a = override.a();
            b = override.b();
        } else if (SpecIngestion.distinctNodePairs(pairs) > 1) {
            err.println("warning: loaded specs bridge more than one (version,namespace) pair — "
                    + "the remap falls between " + SpecIngestion.label(a) + "->" + SpecIngestion.label(b)
                    + " (first spec ingested); pass --pair to choose a composed pair");
        }

        GraphJarRemapper.Result r;
        try {
            r = new GraphJarRemapper(graph).remap(inJar, outJar, a, b);
        } catch (IOException e) {
            err.println("error: cannot write " + outJar + ": " + e.getMessage());
            return 1;
        } catch (IllegalArgumentException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }

        // D4, two guards. The remapper publishes atomically BEFORE these counts are
        // visible to the CLI, so a refused run must DELETE the artifact it just
        // published: an unchanged copy left at OUT would be consumed by any
        // downstream that keys on file existence (review-found transactional gap).
        if (r.classesRemapped() == 0 && !allowEmpty) {
            deleteRefusedOutput(outJar, err);
            err.println("warning: no class in " + inJar + " could be parsed as a classfile; "
                    + "refusing to emit a vacuous result (D4) — pass --allow-empty to "
                    + "accept an unchanged copy");
            return 1;
        }
        if (r.symbolsTranslated() == 0 && r.classesRemapped() > 0 && !allowEmpty) {
            deleteRefusedOutput(outJar, err);
            err.println("warning: 0 symbols translated across " + r.classesRemapped()
                    + " classes — the jar likely does not use namespace '" + a.ns()
                    + "'. Pass --allow-empty to accept an unchanged copy.");
            return 1;
        }

        out.printf(Locale.ROOT,
                "remapped %s -> %s%n  entries copied : %d%n  classes        : %d%n"
                        + "  symbols mapped : %d%n  symbols kept   : %d%n",
                a.version() + "/" + a.ns(), b.version() + "/" + b.ns(),
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
        return 0;
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
}