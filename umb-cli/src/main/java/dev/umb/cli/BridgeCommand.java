package dev.umb.cli;

import dev.umb.mappings.CrossEraSrgBridge;
import dev.umb.mappings.SrgReader;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * {@code umb bridge OLD.srg NEW.srg --out OUT.tiny --src-ver V1 --dst-ver V2}
 * (M9-2, measure-first): pairs the two joined.srg files by SHARED SRG names across
 * eras and emits a tiny-v2 file whose two columns are {@code <ns>@<version>} labels
 * ({@code srg@1.7.10}, {@code srg@1.12.2}). This is a NEW derivation design, not a
 * DeriveMatcher (D13) extension: the class join key is the common SRG className
 * (measured 77.3% shared across 1.7.10/1.12.2) rather than any structural
 * fingerprint, and members join class-SCOPED on srg names because func_/field_
 * repeat across classes (member names are never global keys).
 *
 * <p>Fields pair on the srg name alone — FD rows carry no type, so the field
 * descriptor is null on both sides and never invented (D4). Methods pair on
 * (srg name, srg descriptor) EXACTLY — measured 60.7% continuity; a method whose
 * name recurs on the partner class without a common descriptor is counted and
 * dropped, never emitted at guessed identity (D4; the 746-name ambiguity band is
 * the honest report). Classes absent from the newer file (417 srg classes in the
 * real data) stay unmapped (D4).
 *
 * <p>The emitted file is loaded through the EXISTING {@code --derived-tiny}
 * ingestion (DERIVED_MATCH confidence 0.7) — it is byte-for-byte the same tiny-v2
 * shape {@code umb match} emits (ns@version header labels, positional columns), so
 * a second SpecKind would be a redundant twin: the report's trailing
 * {@code load with:} line is the ready-to-paste graph-audit / remap spec.
 *
 * <p>Every run prints the SHA-256 of the written bytes: the bridge is deterministic
 * by construction (sorted iteration, no hash-order leakage), so the sha pins the
 * real-data output — the operator replays the command against the same inputs and
 * MUST observe the identical hash, or the derivation is no longer reproducible.
 *
 * <p>D4 guards run BEFORE any write (bridge is pure computation — a refused run
 * provably leaves no file, sparing any prior good artifact): 0 SRG classes on
 * either side, or 0 paired classes, exit 1 with no file. D5/D7: usage errors exit 1
 * via the picocli err writer.
 */
@Command(name = "bridge",
        mixinStandardHelpOptions = true,
        version = "umb 0.1 (M0)",
        description = "Pair two joined.srg files by shared SRG names across eras (M9-2) and emit a --derived-tiny bridge.",
        exitCodeOnInvalidInput = 1)
public final class BridgeCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Old-era joined.srg (source; namespace[0] column).")
    Path oldSrg;

    @Parameters(index = "1", description = "New-era joined.srg (destination; namespace[1] column).")
    Path newSrg;

    @Option(names = "--out", required = true, description = "Tiny v2 file to emit.")
    Path out;

    @Option(names = "--src-ver", required = true, description = "Version label for the source column.")
    String srcVer;

    @Option(names = "--dst-ver", required = true, description = "Version label for the destination column.")
    String dstVer;

    @Option(names = "--ns", defaultValue = "srg",
            description = "Namespace name both columns carry (default ${DEFAULT-VALUE}).")
    String ns;

    @Option(names = "--json", description = "Emit the report counters as one flat JSON object.")
    boolean json;

    @Spec
    CommandSpec cmdSpec;

    @Override
    public Integer call() {
        PrintWriter outw = cmdSpec.commandLine().getOut();
        PrintWriter err = cmdSpec.commandLine().getErr();

        if (!Files.isRegularFile(oldSrg)) {
            err.println("error: no such file: " + oldSrg);
            return 1;
        }
        if (!Files.isRegularFile(newSrg)) {
            err.println("error: no such file: " + newSrg);
            return 1;
        }
        Path outAbs = out.toAbsolutePath().normalize();
        Path oldAbs = oldSrg.toAbsolutePath().normalize();
        Path newAbs = newSrg.toAbsolutePath().normalize();
        if (outAbs.equals(oldAbs) || outAbs.equals(newAbs)) {
            err.println("error: --out must be a different file than OLD and NEW");
            return 1;
        }
        if (srcVer.isEmpty() || dstVer.isEmpty()) {
            err.println("error: --src-ver and --dst-ver must be non-empty");
            return 1;
        }
        if (ns.isEmpty()) {
            err.println("error: --ns must be non-empty");
            return 1;
        }
        if (srcVer.equals(dstVer)) {
            // Both columns are the same namespace by definition of the srg bridge;
            // identical versions make (ver, ns) the SAME node on both sides, and a
            // derived tiny loaded there self-maps every row (D4 on a second axis).
            err.println("error: --src-ver and --dst-ver must differ ('" + ns + "@"
                    + srcVer + "') — a cross-era bridge cannot map a node to itself");
            return 1;
        }

        TinyV2Reader.TinyFile oldF;
        TinyV2Reader.TinyFile newF;
        try {
            oldF = SrgReader.read(oldSrg);
            newF = SrgReader.read(newSrg);
        } catch (IOException | IllegalArgumentException e) {
            err.println("error: cannot read " + oldSrg + " or " + newSrg + ": " + e.getMessage());
            return 1;
        }

        CrossEraSrgBridge.BridgeResult r;
        try {
            r = CrossEraSrgBridge.bridge(oldF, newF);
        } catch (IllegalArgumentException e) {
            err.println("error: cannot bridge: " + e.getMessage());
            return 1;
        }

        // D4: guard BEFORE any write — bridge is pure, so a refused run provably
        // leaves no file (and spares any prior good artifact at --out).
        if (r.oldClasses() == 0 || r.newClasses() == 0) {
            err.println("error: " + (r.oldClasses() == 0 ? oldSrg : newSrg)
                    + " has 0 SRG classes; refusing to emit a vacuous bridge (D4)");
            return 1;
        }
        if (r.classesPaired() == 0) {
            err.println("error: 0 classes paired between " + oldSrg + " and " + newSrg
                    + " (" + r.oldClasses() + " old / " + r.newClasses() + " new SRG classes) — "
                    + "the two eras share no SRG class names; refusing to emit an empty bridge (D4)");
            return 1;
        }

        String srcLabel = ns + "@" + srcVer;
        String dstLabel = ns + "@" + dstVer;
        try {
            TinyV2Writer.write(out, List.of(srcLabel, dstLabel), r.output());
        } catch (IOException e) {
            err.println("error: cannot write " + out + ": " + e.getMessage());
            return 1;
        }
        String sha;
        try {
            sha = sha256(out);
        } catch (IOException e) {
            err.println("error: cannot read back " + out + " for the determinism sha: " + e.getMessage());
            return 1;
        }

        if (json) {
            emitJson(outw, r, srcLabel, dstLabel, sha);
        } else {
            emitText(outw, r, srcLabel, dstLabel, sha);
        }
        return 0;
    }

    private void emitText(PrintWriter outw, CrossEraSrgBridge.BridgeResult r,
                          String srcLabel, String dstLabel, String sha) {
        String d = "  %-22s: %d%n"; // fixed-width label column keeps every row aligned
        String s = "  %-22s: %s%n";
        outw.printf(Locale.ROOT, "bridged %s -> %s%n", srcLabel, dstLabel);
        outw.printf(Locale.ROOT, d, "old classes", r.oldClasses());
        outw.printf(Locale.ROOT, d, "new classes", r.newClasses());
        outw.printf(Locale.ROOT, d, "classes paired", r.classesPaired());
        outw.printf(Locale.ROOT, d, "classes unpaired", r.classesUnpaired());
        outw.printf(Locale.ROOT, d, "fields paired", r.fieldsPaired());
        outw.printf(Locale.ROOT, d, "methods paired", r.methodsPaired());
        outw.printf(Locale.ROOT, d, "methods name-ambiguous", r.methodsNameOnlyDropped());
        outw.printf(Locale.ROOT, d, "edges written", r.totalEdges());
        outw.printf(Locale.ROOT, s, "sha256", sha);
        outw.println(loadWith());
    }

    /**
     * The ready-to-paste spec the emitted file is made for (graph-audit / remap
     * --derived-tiny). Both columns are the same namespace so the tail is
     * {@code <srcVer>:<ns>:<dstVer>:<ns>}; the graph nodes are the spec's PLAIN
     * (version, namespace) pairs, and the @-suffixed header labels are the column
     * disambiguation the loader validates positionally.
     */
    private String loadWith() {
        return "load with: --derived-tiny " + out + "=" + srcVer + ":" + ns
                + ":" + dstVer + ":" + ns;
    }

    /** Exactly one flat JSON object: identity + the report counters + the sha + the loadWith spec. */
    private void emitJson(PrintWriter outw, CrossEraSrgBridge.BridgeResult r,
                          String srcLabel, String dstLabel, String sha) {
        outw.println("{\"out\":" + jsonStr(out.toString())
                + ",\"srcVersion\":" + jsonStr(srcVer)
                + ",\"dstVersion\":" + jsonStr(dstVer)
                + ",\"namespace\":" + jsonStr(ns)
                + ",\"srcLabel\":" + jsonStr(srcLabel)
                + ",\"dstLabel\":" + jsonStr(dstLabel)
                + ",\"oldClasses\":" + r.oldClasses()
                + ",\"newClasses\":" + r.newClasses()
                + ",\"classesPaired\":" + r.classesPaired()
                + ",\"classesUnpaired\":" + r.classesUnpaired()
                + ",\"fieldsPaired\":" + r.fieldsPaired()
                + ",\"methodsPaired\":" + r.methodsPaired()
                + ",\"methodsNameOnlyDropped\":" + r.methodsNameOnlyDropped()
                + ",\"edgesWritten\":" + r.totalEdges()
                + ",\"sha256\":" + jsonStr(sha)
                + ",\"loadWith\":" + jsonStr(loadWith()) + "}");
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"") + "\"";
    }

    /** SHA-256 of the written file's bytes — the determinism pin replays must reproduce. */
    private static String sha256(Path file) throws IOException {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("no SHA-256 provider on this JVM", e);
        }
        StringBuilder sb = new StringBuilder(64);
        for (byte b : md.digest(Files.readAllBytes(file))) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}