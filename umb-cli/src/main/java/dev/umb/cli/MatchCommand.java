package dev.umb.cli;

import dev.umb.mappings.DeriveMatcher;
import dev.umb.mappings.MappingGraph;
import dev.umb.mappings.TinyV2Reader;
import dev.umb.mappings.TinyV2Writer;
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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code umb match SRC.jar DST.jar --out OUT.tiny --src-ver V1 --dst-ver V2}: structural
 * match of TWO CLASS JARS ALREADY IN THE SAME NAMESPACE at different versions, emitting a
 * tiny-v2 file whose two columns are the {@code <ns>@<version>} labels
 * ({@code mojang@1.21.1}, {@code mojang@26.2}). The labels must stay DISTINCT because the
 * graph-audit / remap spec grammar resolves columns with {@code namespaces.indexOf(label)}:
 * unless the versions are the same, the {@code @<version>} suffix guarantees two distinct
 * header columns even when both sides share a namespace name
 * (exactly why {@link TinyV2Writer} documents the distinct-label requirement).
 *
 * <p>The emitted file is MEANT for {@code --derived-tiny} ingestion on the graph-audit and
 * remap commands, which load its edges at DERIVED_MATCH confidence (0.7) — the report's
 * trailing {@code load with:} line is the ready-to-paste spec. The bridge is
 * {@link DeriveMatcher}'s deterministic match: class pass 1 by identical name,
 * pass 2 by structural fingerprint; member edges by (kind, name, descriptor) or by unique
 * descriptor. No guessed edges are ever emitted (D4).
 *
 * <p>D4 guards run BEFORE any file is written (match is pure computation — nothing to
 * un-publish, unlike remap): 0 parseable classes on either side, or 0 matched classes,
 * exit 1 with no file created; a class-pairing with 0 MEMBER matches is a loud stderr
 * warning but still exit 0 (class edges alone are a non-vacuous deliverable, the operator
 * just learns the paired jars expose no shared static surface). D5/D7: usage errors exit 1
 * via the picocli err writer.
 */
@Command(name = "match",
        mixinStandardHelpOptions = true,
        version = "umb 0.1 (M0)",
        description = "Structurally match two same-namespace jars across versions and emit a --derived-tiny bridge.",
        exitCodeOnInvalidInput = 1)
public final class MatchCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Source jar (older version; namespace[0] column).")
    Path srcJar;

    @Parameters(index = "1", description = "Destination jar (newer version; namespace[1] column).")
    Path dstJar;

    @Option(names = "--out", required = true, description = "Tiny v2 file to emit.")
    Path out;

    @Option(names = "--src-ver", required = true, description = "Version label for the source column.")
    String srcVer;

    @Option(names = "--dst-ver", required = true, description = "Version label for the destination column.")
    String dstVer;

    @Option(names = "--ns-a", defaultValue = "mojang",
            description = "Namespace name for the source column (default ${DEFAULT-VALUE}).")
    String nsA;

    @Option(names = "--ns-b", defaultValue = "mojang",
            description = "Namespace name for the destination column (default ${DEFAULT-VALUE}).")
    String nsB;

    @Option(names = "--max-report", defaultValue = "25",
            description = "Cap listed warnings (default ${DEFAULT-VALUE}).")
    int maxReport;

    @Option(names = "--json", description = "Emit the report counters as one flat JSON object.")
    boolean json;

    @Spec
    CommandSpec cmdSpec;

    @Override
    public Integer call() {
        PrintWriter outw = cmdSpec.commandLine().getOut();
        PrintWriter err = cmdSpec.commandLine().getErr();

        if (!Files.isRegularFile(srcJar)) {
            err.println("error: no such file: " + srcJar);
            return 1;
        }
        if (!Files.isRegularFile(dstJar)) {
            err.println("error: no such file: " + dstJar);
            return 1;
        }
        Path outAbs = out.toAbsolutePath().normalize();
        Path srcAbs = srcJar.toAbsolutePath().normalize();
        Path dstAbs = dstJar.toAbsolutePath().normalize();
        if (outAbs.equals(srcAbs) || outAbs.equals(dstAbs)) {
            err.println("error: --out must be a different file than SRC and DST");
            return 1;
        }
        if (srcVer.isEmpty() || dstVer.isEmpty()) {
            err.println("error: --src-ver and --dst-ver must be non-empty");
            return 1;
        }
        if (nsA.isEmpty() || nsB.isEmpty()) {
            err.println("error: --ns-a and --ns-b must be non-empty");
            return 1;
        }
        if (maxReport < 0) {
            err.println("error: --max-report must be >= 0, got " + maxReport);
            return 1;
        }
        if (srcVer.equals(dstVer) && nsA.equals(nsB)) {
            // The header would declare the SAME label twice; even if it survived the
            // writer, indexOf collapses both columns to 0 and every row self-maps.
            err.println("error: --src-ver/--ns-a and --dst-ver/--ns-b must differ ('"
                    + nsA + "@" + srcVer + "') — a version bridge cannot map a node to itself");
            return 1;
        }

        DeriveMatcher.MatchResult r;
        try {
            r = DeriveMatcher.match(srcJar, dstJar);
        } catch (IOException e) {
            err.println("error: cannot read " + srcJar + " or " + dstJar + ": " + e.getMessage());
            return 1;
        }

        // D4: guard BEFORE any write — match is pure, so a refused run provably leaves
        // no file (the remap command must delete-after-publish because its engine writes
        // first; here nothing is ever created, which also spares any prior good artifact).
        if (r.parseableSrcClasses() == 0 || r.parseableDstClasses() == 0) {
            err.println("error: " + (r.parseableSrcClasses() == 0 ? srcJar : dstJar)
                    + " has 0 parseable classes; refusing to emit a vacuous bridge (D4)");
            return 1;
        }
        if (r.matchedClasses() == 0) {
            err.println("error: 0 classes matched between " + srcJar + " and " + dstJar
                    + " (" + r.parseableSrcClasses() + " src / " + r.parseableDstClasses()
                    + " dst parseable) — wrong namespace, vastly different structure, or the "
                    + "versions are unrelated; refusing to emit an empty bridge (D4)");
            return 1;
        }

        try {
            writeBridge(r, out, nsA + "@" + srcVer, nsB + "@" + dstVer);
        } catch (IOException e) {
            err.println("error: cannot write " + out + ": " + e.getMessage());
            return 1;
        }

        if (r.membersExact() + r.membersUniqueDesc() == 0) {
            err.println("warning: " + r.matchedClasses() + " classes matched but 0 members matched "
                    + "— the jars expose no shared static surface; " + out.getFileName()
                    + " carries class edges only");
        }
        if (json) {
            emitJson(outw, r);
        } else {
            emitText(outw, r);
        }
        return 0;
    }

    /** Class + member rows in match order; namespace[0] = src side, so member descriptors stay in src form. */
    private static void writeBridge(DeriveMatcher.MatchResult r, Path out,
                                    String srcLabel, String dstLabel) throws IOException {
        List<TinyV2Reader.ClassEntry> entries = new ArrayList<>();
        for (DeriveMatcher.ClassMatch cm : r.classes()) {
            List<TinyV2Reader.FieldEntry> fields = new ArrayList<>();
            List<TinyV2Reader.MethodEntry> methods = new ArrayList<>();
            for (DeriveMatcher.MemberMatch mm : cm.members()) {
                String[] names = new String[] {mm.srcName(), mm.dstName()};
                if (mm.kind() == MappingGraph.SymbolKind.FIELD) {
                    fields.add(new TinyV2Reader.FieldEntry(mm.descriptor(), names));
                } else {
                    methods.add(new TinyV2Reader.MethodEntry(mm.descriptor(), names));
                }
            }
            entries.add(new TinyV2Reader.ClassEntry(
                    new String[] {cm.srcName(), cm.dstName()}, fields, methods));
        }
        TinyV2Writer.write(out, List.of(srcLabel, dstLabel), entries);
    }

    private void emitText(PrintWriter outw, DeriveMatcher.MatchResult r) {
        String label = "  %-22s: %d%n"; // fixed-width label column keeps every row aligned
        outw.printf(Locale.ROOT, "matched %s -> %s%n", nsA + "@" + srcVer, nsB + "@" + dstVer);
        outw.printf(Locale.ROOT, label, "src classes parseable", r.parseableSrcClasses());
        outw.printf(Locale.ROOT, label, "dst classes parseable", r.parseableDstClasses());
        outw.printf(Locale.ROOT, label, "classes exact", r.classesExact());
        outw.printf(Locale.ROOT, label, "classes fingerprint", r.classesFingerprint());
        outw.printf(Locale.ROOT, label, "class collisions", r.classCollisions());
        outw.printf(Locale.ROOT, label, "classes matched", r.matchedClasses());
        outw.printf(Locale.ROOT, label, "classes unmatched src", r.unmatchedSrcClasses());
        outw.printf(Locale.ROOT, label, "classes unmatched dst", r.unmatchedDstClasses());
        outw.printf(Locale.ROOT, label, "members exact", r.membersExact());
        outw.printf(Locale.ROOT, label, "members unique-desc", r.membersUniqueDesc());
        outw.printf(Locale.ROOT, label, "edges written", r.totalEdges());
        if (!r.warnings().isEmpty()) {
            outw.printf(Locale.ROOT, label, "warnings", r.warnings().size());
            r.warnings().stream().limit(maxReport)
                    .forEach(w -> outw.println("    ! " + w));
            if (r.warnings().size() > maxReport) {
                outw.printf(Locale.ROOT, "    ... and %d more%n",
                        r.warnings().size() - maxReport);
            }
        }
        outw.println(loadWith());
    }

    /**
     * The ready-to-paste spec the emitted file is made for (graph-audit / remap
     * --derived-tiny). The spec names the PLAIN graph nodes {@code (ver, ns)} so the
     * bridge composes with published specs on the same node — e.g. client.txt's
     * {@code (1.21.11, mojang)}; the @-suffixed header labels are column
     * disambiguation the loader validates positionally, not node names.
     */
    private String loadWith() {
        return "load with: --derived-tiny " + out + "=" + srcVer + ":" + nsA
                + ":" + dstVer + ":" + nsB;
    }

    /** Exactly one flat JSON object: identity + the report counters + the loadWith spec. */
    private void emitJson(PrintWriter outw, DeriveMatcher.MatchResult r) {
        outw.println("{\"out\":" + jsonStr(out.toString())
                + ",\"srcVersion\":" + jsonStr(srcVer)
                + ",\"dstVersion\":" + jsonStr(dstVer)
                + ",\"srcNamespace\":" + jsonStr(nsA)
                + ",\"dstNamespace\":" + jsonStr(nsB)
                + ",\"srcLabel\":" + jsonStr(nsA + "@" + srcVer)
                + ",\"dstLabel\":" + jsonStr(nsB + "@" + dstVer)
                + ",\"parseableSrcClasses\":" + r.parseableSrcClasses()
                + ",\"parseableDstClasses\":" + r.parseableDstClasses()
                + ",\"classesExact\":" + r.classesExact()
                + ",\"classesFingerprint\":" + r.classesFingerprint()
                + ",\"classCollisions\":" + r.classCollisions()
                + ",\"matchedClasses\":" + r.matchedClasses()
                + ",\"unmatchedSrcClasses\":" + r.unmatchedSrcClasses()
                + ",\"unmatchedDstClasses\":" + r.unmatchedDstClasses()
                + ",\"membersExact\":" + r.membersExact()
                + ",\"membersUniqueDesc\":" + r.membersUniqueDesc()
                + ",\"edgesWritten\":" + r.totalEdges()
                + ",\"warnings\":" + r.warnings().size()
                + ",\"loadWith\":" + jsonStr(loadWith()) + "}");
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }
}