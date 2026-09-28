package dev.umb.cli;

import dev.umb.mappings.DefaultMappingGraph;
import dev.umb.mappings.MappingGraph;
import dev.umb.mappings.ProGuardReader;
import dev.umb.mappings.Provenance;
import dev.umb.mappings.SrgReader;
import dev.umb.mappings.TinyV2Reader;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Package-private spec-ingestion contract shared by the graph-facing commands
 * (graph-audit, remap, translate): parses one {@code --tiny} / {@code --proguard}
 * / {@code --srg} / {@code --derived-tiny} argument in the
 * {@code <file>=<verA>:<nsA>:<verB>:<nsB>} grammar, reads and parses the file,
 * resolves the two header columns and folds the edges into the shared
 * {@link DefaultMappingGraph} under the file basename (source tag). Extracted from
 * RemapCommand/GraphAuditCommand once a third consumer (translate) appeared — the
 * three commands must agree byte-for-byte on what a malformed or self-mapping or
 * wrong-namespace spec means, and the duplicated {@code --pair} machinery (same
 * grammar, same messages) lives here for the same reason.
 *
 * <p>Three shape contracts live here because every graph consumer needs the same
 * ones. (1) tiny/proguard/srg columns resolve BY NAME ({@code namespaces.indexOf}),
 * so any header column order works and the spec's direction is the operator's
 * choice. (2) derived-tiny columns — the files {@code umb match} emits, whose
 * header labels carry an {@code ns@version} suffix whose ONLY job is keeping the
 * two columns distinct — resolve POSITIONALLY and are validated against the spec;
 * the GRAPH nodes are the spec's plain {@code (version, namespace)} so a derived
 * bridge composes with published specs sharing the node, and a reversed/wrong-file
 * spec cannot load silently into swapped nodes. (3) a spec mapping a
 * {@code (version,namespace)} to itself is refused up front because translate()
 * short-circuits identity to an empty path — a trivially-green no-op must never
 * read as a successful bridge (D4).
 */
final class SpecIngestion {

    static final String SPEC_USAGE = "<file>=<verA>:<nsA>:<verB>:<nsB>";

    /**
     * What an ingested file IS, and how its edges enter the graph: tiny, proguard
     * and srg files are published mappings (confidence 1.0 — a joined.srg from
     * MCPConfig carries the same authority as a published tiny file); an
     * umb-match-emitted tiny file is a DERIVED_MATCH artifact (0.7). The flag name
     * doubles as the error-label for spec-parsing messages. Binding order across
     * flags is --tiny, then --proguard, then --srg, then --derived-tiny (picocli
     * keeps no cross-flag order).
     */
    enum SpecKind {
        TINY(false, false, Provenance.PUBLISHED, "--tiny"),
        PROGUARD(true, false, Provenance.PUBLISHED, "--proguard"),
        SRG(false, true, Provenance.PUBLISHED, "--srg"),
        DERIVED(false, false, Provenance.DERIVED_MATCH, "--derived-tiny");

        final boolean proguardForm;
        final boolean srgForm;
        final double confidence;
        final String flag;

        SpecKind(boolean proguardForm, boolean srgForm, double confidence, String flag) {
            this.proguardForm = proguardForm;
            this.srgForm = srgForm;
            this.confidence = confidence;
            this.flag = flag;
        }
    }

    /** One successfully ingested spec: the file read, the nodes it bridged, the parsed rows. */
    record Result(Path file, MappingGraph.Node a, MappingGraph.Node b,
                  TinyV2Reader.TinyFile tinyFile) {}

    /** The two graph nodes a spec (or the --pair override) names. */
    record NodePair(MappingGraph.Node a, MappingGraph.Node b) {}

    /**
     * Parses one spec argument and folds its file into {@code graph} at {@code kind}'s
     * confidence. Returns null after printing a usage-grade reason on {@code err};
     * missing files and read/parse failures are operator problems, not crashes.
     */
    static Result ingest(DefaultMappingGraph graph, String raw, SpecKind kind,
                         PrintWriter err) {
        String flag = kind.flag;
        // lastIndexOf rather than indexOf: '=' may legitimately appear inside a path;
        // the version/ns tail never carries one.
        int eq = raw.lastIndexOf('=');
        if (eq <= 0) {
            err.println("error: bad " + flag + " spec '" + raw + "' (want " + flag + " " + SPEC_USAGE + ")");
            return null;
        }
        String filePart = raw.substring(0, eq);
        String[] tail = raw.substring(eq + 1).split(":", -1);
        if (tail.length != 4 || hasEmpty(tail)) {
            err.println("error: bad " + flag + " spec '" + raw + "' (want " + flag + " " + SPEC_USAGE + ")");
            return null;
        }
        if (tail[0].equals(tail[2]) && tail[1].equals(tail[3])) {
            // Identity remap: translate() short-circuits to an empty path, so the
            // run degenerates to all-unmapped (D4-refused downstream) — refuse the
            // shape outright.
            err.println("error: " + flag + " endpoints must differ ('" + raw + "')"
                    + " — a (version,namespace) cannot map to itself");
            return null;
        }
        Path file = Path.of(filePart);
        if (!Files.isRegularFile(file)) {
            err.println("error: no such file: " + file);
            return null;
        }
        try {
            TinyV2Reader.TinyFile f = kind.proguardForm
                    ? ProGuardReader.read(file)
                    : kind.srgForm ? SrgReader.read(file) : TinyV2Reader.read(file);
            var a = new MappingGraph.Node(tail[0], tail[1]);
            var b = new MappingGraph.Node(tail[2], tail[3]);
            int colA;
            int colB;
            if (kind == SpecKind.DERIVED) {
                // umb match emits header labels ns@version whose ONLY job is keeping the
                // two header columns distinct; the GRAPH nodes are the spec's plain
                // (version, namespace) so this file composes with published specs that
                // bridge the same node — client.txt's (1.21.11, mojang), not some
                // (1.21.11, mojang@1.21.11) island no other file reaches. Columns are
                // positional (col0=src, col1=dst — umb match defines the order) and
                // validated against the spec so a reversed or wrong-file spec cannot
                // load silently into the wrong nodes.
                colA = 0;
                colB = 1;
                String expectA = tail[1] + "@" + tail[0];
                String expectB = tail[3] + "@" + tail[2];
                if (f.namespaces().size() != 2
                        || !expectA.equals(f.namespaces().get(0))
                        || !expectB.equals(f.namespaces().get(1))) {
                    err.println("error: " + file.getFileName() + " header ["
                            + String.join(", ", f.namespaces()) + "] does not match spec '"
                            + raw + "' (derived-tiny wants columns " + expectA + ", " + expectB
                            + " — the ns@version labels umb match emits)");
                    return null;
                }
            } else {
                colA = f.namespaces().indexOf(tail[1]);
                if (colA < 0) {
                    err.println("error: " + file.getFileName() + " has no namespace '" + tail[1]
                            + "' (header: " + String.join(", ", f.namespaces()) + ")");
                    return null;
                }
                colB = f.namespaces().indexOf(tail[3]);
                if (colB < 0) {
                    err.println("error: " + file.getFileName() + " has no namespace '" + tail[3]
                            + "' (header: " + String.join(", ", f.namespaces()) + ")");
                    return null;
                }
            }
            graph.addTinyFile(f, a, colA, b, colB, kind.confidence,
                    file.getFileName().toString());
            return new Result(file, a, b, f);
        } catch (IOException e) {
            err.println("error: cannot read " + file + ": " + e.getMessage());
            return null;
        } catch (IllegalArgumentException e) {
            // malformed tiny/proguard structure, or a requested column missing a name on some row
            err.println("error: cannot load " + file + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Parses the optional --pair override. Returns null when absent (no error) or
     * when malformed (reason on stderr — callers re-check the raw flag to tell the
     * two apart).
     */
    static NodePair parsePairSpec(String raw, PrintWriter err) {
        if (raw == null) {
            return null;
        }
        String[] tail = raw.split(":", -1);
        if (tail.length != 4 || hasEmpty(tail)) {
            err.println("error: bad --pair '" + raw + "' (want --pair <verA>:<nsA>:<verB>:<nsB>)");
            return null;
        }
        if (tail[0].equals(tail[2]) && tail[1].equals(tail[3])) {
            // Identity override: translate() short-circuits identity to empty paths,
            // so every probe/remap would trivially "succeed" at confidence 1.0 — a
            // green result proving nothing (D4 on a second axis; review-found).
            err.println("error: --pair endpoints must differ ('" + raw + "')");
            return null;
        }
        return new NodePair(new MappingGraph.Node(tail[0], tail[1]),
                new MappingGraph.Node(tail[2], tail[3]));
    }

    /** Distinct undirected node pairs the loaded specs bridge — bridge, not orientation, is what matters. */
    static int distinctNodePairs(Iterable<Result> specs) {
        Set<UndirectedPair> bridges = new HashSet<>();
        for (Result r : specs) {
            bridges.add(UndirectedPair.of(r.a(), r.b()));
        }
        return bridges.size();
    }

    /** An undirected node pair, keyed by its two sorted labels so orientation cannot double-count. */
    private record UndirectedPair(String x, String y) {
        static UndirectedPair of(MappingGraph.Node a, MappingGraph.Node b) {
            String la = label(a);
            String lb = label(b);
            return la.compareTo(lb) <= 0 ? new UndirectedPair(la, lb) : new UndirectedPair(lb, la);
        }
    }

    /** One edge per c/f/m row — mirrors exactly what DefaultMappingGraph.addTinyFile emits. */
    static long countEdges(TinyV2Reader.TinyFile f) {
        long n = 0;
        for (TinyV2Reader.ClassEntry c : f.classes()) {
            n += 1 + c.fields().size() + c.methods().size();
        }
        return n;
    }

    static boolean hasEmpty(String[] parts) {
        for (String p : parts) {
            if (p.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Compact printable identity of a graph node, used in reports and error messages. */
    static String label(MappingGraph.Node n) {
        return n.version() + "/" + n.ns();
    }
}