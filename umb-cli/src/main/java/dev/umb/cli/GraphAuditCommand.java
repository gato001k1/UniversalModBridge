package dev.umb.cli;

import dev.umb.mappings.DefaultMappingGraph;
import dev.umb.mappings.MappingGraph;
import dev.umb.mappings.RoundtripAudit;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * {@code umb graph-audit} (spec §16 CLI surface): ingest one or more published
 * mapping sets — tiny-v2 files, Mojang's ProGuard-format client.txt and/or MCPConfig
 * joined.srg files — into a
 * single {@link DefaultMappingGraph}, then round-trip audit the first pair
 * a -> b -> a (spec §128 audit semantics); later files compose into the graph but
 * are not probed by this wave, and the result line names the audited pair. The
 * {@code --pair} override selects a COMPOSED pair neither file declares alone (e.g.
 * 1.20.1:mojang ↔ 1.20.1:intermediary across client.txt + the Fabric intermediary
 * file, via the shared official/obfuscated node).
 *
 * <p>D5 exit-code nuance: a contradiction-heavy audit still exits 0 — the numbers ARE
 * the deliverable and downstream waves gate on them; usage/IO failures exit 1, with
 * the reason on stderr via the picocli err writer so callers can separate the streams.
 * A vacuous audit is also refused with exit 1 (D4's lesson: an empty-but-"healthy"
 * run must never read as success) — tested=0 means a truncated download, a wrong
 * version/ns label or --limit 0, none of which deserve a green light. With --json,
 * stdout carries ONLY the one JSON object; per-file progress goes nowhere.
 */
@Command(name = "graph-audit",
        mixinStandardHelpOptions = true,
        version = "umb 0.1 (M0)",
        description = "Load tiny-v2 / ProGuard (client.txt) mapping sets and audit roundtrip consistency.",
        exitCodeOnInvalidInput = 1)
public final class GraphAuditCommand implements Callable<Integer> {

    /**
     * Bounded default: one probe costs a component-wide BFS on both sides of the meet,
     * so auditing every class of a full MC graph by accident would burn minutes on this
     * commit-constrained host. Pass a larger --limit for exhaustive audits.
     */
    private static final int DEFAULT_AUDIT_LIMIT = 1000;

    @Option(names = "--tiny", arity = "1",
            description = "Mapping set to load, as " + SpecIngestion.SPEC_USAGE + "; repeatable.")
    List<String> tinySpecs;

    @Option(names = "--proguard", arity = "1",
            description = "ProGuard-format mapping (Mojang client.txt: mojang names ↔ the "
                    + "obfuscated runtime names Fabric labels 'official') to load, as "
                    + SpecIngestion.SPEC_USAGE + "; repeatable.")
    List<String> proguardSpecs;

    @Option(names = "--srg", arity = "1",
            description = "MCPConfig joined.srg mapping (the srg ↔ obfuscated-runtime "
                    + "columns of a pre-flattening era) to load, as "
                    + SpecIngestion.SPEC_USAGE + "; repeatable.")
    List<String> srgSpecs;

    @Option(names = "--derived-tiny", arity = "1",
            description = "Matcher-emitted tiny file (from umb match) to load at DERIVED_MATCH "
                    + "confidence (0.7) as " + SpecIngestion.SPEC_USAGE + "; repeatable.")
    List<String> derivedTinySpecs;

    @Option(names = "--pair", arity = "1",
            description = "Audit <verA>:<nsA>:<verB>:<nsB> instead of the first file's pair — "
                    + "for composed bridges no single file declares (two published hops).")
    String pairSpec;

    @Option(names = "--limit", defaultValue = "1000",
            description = "Max CLASS probes in the audit (default ${DEFAULT-VALUE}).")
    int limit;

    @Option(names = "--kind", defaultValue = "class",
            description = "Symbol kind to audit: class|field|method (default ${DEFAULT-VALUE}; "
                    + "field/method reserved for a later wave).")
    String kindName;

    @Option(names = "--json", description = "Emit the audit numbers as one flat JSON object.")
    boolean json;

    @Spec
    CommandSpec cmdSpec;

    @Override
    public Integer call() {
        PrintWriter out = cmdSpec.commandLine().getOut();
        PrintWriter err = cmdSpec.commandLine().getErr();

        boolean noSpecs = (tinySpecs == null || tinySpecs.isEmpty())
                && (proguardSpecs == null || proguardSpecs.isEmpty())
                && (srgSpecs == null || srgSpecs.isEmpty())
                && (derivedTinySpecs == null || derivedTinySpecs.isEmpty());
        if (noSpecs) {
            err.println("error: no mapping given (want --tiny, --proguard, --srg or --derived-tiny, "
                    + "each " + SpecIngestion.SPEC_USAGE + ")");
            return 1;
        }
        var kind = parseKind(kindName, err);
        if (kind == null) {
            return 1;
        }
        if (limit < 0) {
            err.println("error: --limit must be >= 0, got " + limit);
            return 1;
        }
        SpecIngestion.NodePair override = SpecIngestion.parsePairSpec(pairSpec, err);
        if (pairSpec != null && override == null) {
            return 1; // malformed --pair, reason on stderr
        }

        var graph = new DefaultMappingGraph();
        List<SpecIngestion.Result> loaded = new ArrayList<>();
        Set<String> seenNodes = new HashSet<>(); // "version/ns" labels across all specs
        if (tinySpecs != null) {
            for (String raw : tinySpecs) {
                if (!load(graph, raw, SpecIngestion.SpecKind.TINY, loaded, seenNodes, err)) {
                    return 1; // reason already on stderr
                }
            }
        }
        if (proguardSpecs != null) {
            for (String raw : proguardSpecs) {
                if (!load(graph, raw, SpecIngestion.SpecKind.PROGUARD, loaded, seenNodes, err)) {
                    return 1;
                }
            }
        }
        if (srgSpecs != null) {
            for (String raw : srgSpecs) {
                if (!load(graph, raw, SpecIngestion.SpecKind.SRG, loaded, seenNodes, err)) {
                    return 1;
                }
            }
        }
        if (derivedTinySpecs != null) {
            for (String raw : derivedTinySpecs) {
                if (!load(graph, raw, SpecIngestion.SpecKind.DERIVED, loaded, seenNodes, err)) {
                    return 1;
                }
            }
        }
        if (!json) {
            for (SpecIngestion.Result l : loaded) {
                out.println("loaded " + l.file().getFileName() + ": nodes=["
                        + SpecIngestion.label(l.a()) + ", " + SpecIngestion.label(l.b())
                        + "] edges=" + SpecIngestion.countEdges(l.tinyFile()));
            }
        }

        // --pair overrides which nodes get probed; without it the FIRST file's pair
        // (the direct bridge) is the deliverable.
        MappingGraph.Node auditA;
        MappingGraph.Node auditB;
        String pair;
        if (override != null) {
            auditA = override.a();
            auditB = override.b();
            pair = SpecIngestion.label(auditA) + "->" + SpecIngestion.label(auditB) + " (--pair)";
        } else {
            SpecIngestion.Result first = loaded.get(0);
            auditA = first.a();
            auditB = first.b();
            pair = SpecIngestion.label(auditA) + "->" + SpecIngestion.label(auditB)
                    + (loaded.size() > 1 ? " (file 1 of " + loaded.size() + ")" : "");
            if (SpecIngestion.distinctNodePairs(loaded) > 1) {
                // The first file's pair is the default deliverable, but the operator
                // loaded a composed graph — say so instead of letting a second pair
                // silently go unaudited (mirrors remap's multi-pair warning).
                err.println("warning: loaded specs bridge more than one (version,namespace) pair — "
                        + "auditing " + SpecIngestion.label(auditA) + "->" + SpecIngestion.label(auditB)
                        + "; pass --pair selects another");
            }
        }

        RoundtripAudit.AuditResult r;
        try {
            r = RoundtripAudit.audit(graph, auditA, auditB, kind, limit);
        } catch (UnsupportedOperationException e) {
            // field/method kinds are an extension point this wave; a clean refusal beats a stack trace.
            err.println("error: " + e.getMessage());
            return 1;
        }

        if (r.tested() == 0) {
            // D4's lesson generalized: tested=0 comes from a truncated download, a wrong
            // version/ns label or --limit 0 — never from a healthy pipeline step.
            err.println("warning: nothing audited — pool at " + SpecIngestion.label(auditA) + " holds 0 probed "
                    + kind.name().toLowerCase(Locale.ROOT) + " symbols toward " + SpecIngestion.label(auditB)
                    + " (limit=" + limit + "); refusing to report a vacuous pass");
            return 1;
        }

        if (json) {
            out.println("{\"pair\":" + jsonStr(pair)
                    + ",\"tested\":" + r.tested()
                    + ",\"ok\":" + r.ok()
                    + ",\"contradictions\":" + r.contradictions()
                    + ",\"minPathConfidence\":" + jsonNumber(r.minPathConfidence())
                    + "}");
        } else {
            out.println("pair=" + pair);
            out.printf(Locale.ROOT, "tested=%d ok=%d contradictions=%d minPathConfidence=%s%n",
                    r.tested(), r.ok(), r.contradictions(), confidence(r.minPathConfidence()));
            r.samples().stream().limit(5).forEach(s -> out.println("  ! " + s));
        }
        return 0;
    }

    /** Loads one spec, records the compose warning, and appends to {@code loaded}. False = fatal, reason on stderr. */
    private boolean load(DefaultMappingGraph graph, String raw, SpecIngestion.SpecKind kind,
                         List<SpecIngestion.Result> loaded, Set<String> seenNodes, PrintWriter err) {
        SpecIngestion.Result l = SpecIngestion.ingest(graph, raw, kind, err);
        if (l == null) {
            return false;
        }
        String la = SpecIngestion.label(l.a());
        String lb = SpecIngestion.label(l.b());
        if (!seenNodes.isEmpty() && !seenNodes.contains(la) && !seenNodes.contains(lb)) {
            err.println("warning: " + l.file().getFileName() + " bridges " + la + "->" + lb
                    + ", which shares no (version,namespace) with earlier specs;"
                    + " the files cannot compose into one graph");
        }
        seenNodes.add(la);
        seenNodes.add(lb);
        loaded.add(l);
        return true;
    }

    /** NaN means nothing round-tripped; keep it visible in human output instead of hiding it as 0.0000. */
    private static String confidence(double c) {
        return Double.isNaN(c) ? "NaN" : String.format(Locale.ROOT, "%.4f", c);
    }

    /** JSON has no NaN literal, so the untestable case serializes as null. */
    private static String jsonNumber(double d) {
        return Double.isNaN(d) ? "null" : Double.toString(d);
    }

    /** Minimal string escaping for the pair label; version/ns tails are operator input. */
    private static String jsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static MappingGraph.SymbolKind parseKind(String name, PrintWriter err) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "class" -> MappingGraph.SymbolKind.CLASS;
            case "field" -> MappingGraph.SymbolKind.FIELD;
            case "method" -> MappingGraph.SymbolKind.METHOD;
            default -> {
                err.println("error: --kind must be class|field|method, got '" + name + "'");
                yield null;
            }
        };
    }
}