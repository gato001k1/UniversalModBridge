package dev.umb.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * graph-audit CLI surface: subcommand wiring, D5 exit codes (0 = audit ran, 1 = usage/IO),
 * human output shape and the --json flat object. Fixtures are tiny synthetic files — the
 * full real-mappings run lives in the wave's end-to-end smoke, not unit tests.
 */
class GraphAuditCommandTest {

    private static final String TINY = String.join("\n",
            "tiny\t2\t0\tofficial\tintermediary",
            "c\tnet/minecraft/A\tnet/minecraft/class_100",
            "\tf\tI\ta\tfield_1",
            "\tm\t(I)V\ta\tmethod_1");

    /** Mirrors the real client.txt shape: dotted mojang class, obfuscated target. */
    private static final String PROGUARD = String.join("\n",
            "# comment",
            "net.minecraft.Beta -> net/minecraft/A:",
            "    void jump(int) -> a",
            "    int HEALTH -> b");

    /**
     * The shape umb match emits: two columns named {@code ns@version} so the labels stay
     * distinct across the version bridge, one class row + one method row.
     */
    private static final String DERIVED = String.join("\n",
            "tiny\t2\t0\tmojang@1.21.1\tmojang@26.2",
            "c\tcom/ex/Old\tcom/ex/New",
            "\tm\t(I)V\tgo\trun");

    @TempDir
    Path tmp;

    private Path fixture(String name) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, TINY, StandardCharsets.UTF_8);
        return p;
    }

    private Path proguardFixture(String name) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, PROGUARD, StandardCharsets.UTF_8);
        return p;
    }

    private Path derivedFixture(String name) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, DERIVED, StandardCharsets.UTF_8);
        return p;
    }

    /** Routes through the shared factory so D5 exit-code wiring is exercised too. */
    private static CommandLine cli(StringWriter out, StringWriter err) {
        CommandLine cmd = UmbCli.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }

    private String spec(Path f) {
        return f + "=1.20.1:official:1.20.1:intermediary";
    }

    private String proguardSpec(Path f) {
        return f + "=1.20.1:mojang:1.20.1:official";
    }

    @Test
    void auditsCleanRoundTripOnFixture() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit", "--tiny", spec(fixture("m.tiny")));
        assertEquals(0, exit);
        assertTrue(err.toString().isEmpty(), "stderr should stay empty on success");
        // one class row -> exactly one probe, and it must round-trip
        assertTrue(out.toString().contains("tested=1 ok=1 contradictions=0"),
                () -> "unexpected audit line:\n" + out);
        assertTrue(out.toString().contains("minPathConfidence=1.0000"));
        assertTrue(out.toString().contains("edges=3"), () -> "per-file edge line missing:\n" + out);
    }

    @Test
    void bareUsageListsGraphAudit() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute();
        assertEquals(0, exit);
        assertTrue(out.toString().contains("graph-audit"), () -> out.toString());
    }

    @Test
    void jsonFlagEmitsSingleParseableObject() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit", "--tiny", spec(fixture("m.tiny")), "--json");
        assertEquals(0, exit);
        String trimmed = out.toString().trim();
        assertTrue(trimmed.startsWith("{") && trimmed.endsWith("}"),
                () -> "stdout must be exactly one JSON object, got:\n" + out);
        assertFalse(trimmed.contains("loaded"),
                "human progress lines must not pollute the --json stream:\n" + out);
        assertTrue(trimmed.contains("\"tested\":1"));
        assertTrue(trimmed.contains("\"ok\":1"));
        assertTrue(trimmed.contains("\"contradictions\":0"));
        assertTrue(trimmed.contains("\"minPathConfidence\":1.0"));
        assertTrue(trimmed.contains("\"pair\":\"1.20.1/official->1.20.1/intermediary\""),
                () -> out.toString());
    }

    /** D5: picocli's usage-error default is 2, but 2 belongs to check-linkage's verdict. */
    @Test
    void usageErrorsExitOneNotTwo() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit", "--bogus", "x");
        assertEquals(1, exit, () -> "stderr was: " + err);

        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        assertEquals(1, cli(out2, err2).execute("frobnicate"), "unknown subcommand too");
    }

    @Test
    void subcommandHelpPrintsUsageAndExitsZero() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit", "--help");
        assertEquals(0, exit, () -> err.toString());
        assertTrue(out.toString().contains("--tiny"), () -> out.toString());
    }

    /** A header-only file audits nothing; D4's lesson says an empty-but-green run is a lie. */
    @Test
    void vacuousAuditIsRefusedWithUsageGradeExit() throws IOException {
        Path p = tmp.resolve("header-only.tiny");
        Files.writeString(p, "tiny\t2\t0\tofficial\tintermediary\n", StandardCharsets.UTF_8);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit", "--tiny", spec(p));
        assertEquals(1, exit, () -> "a tested=0 audit must not read as success");
        assertTrue(err.toString().contains("nothing audited"), () -> err.toString());
    }

    @Test
    void limitZeroIsAlsoAVacuousAudit() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit", "--tiny", spec(fixture("m.tiny")), "--limit", "0");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("nothing audited"), () -> err.toString());
    }

    @Test
    void resultLineNamesTheAuditedPair() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit", "--tiny", spec(fixture("m.tiny")));
        assertEquals(0, exit);
        assertTrue(out.toString().contains("pair=1.20.1/official->1.20.1/intermediary"),
                () -> out.toString());
    }

    /** Specs sharing no (version,namespace) cannot compose; the operator probably mistyped a label. */
    @Test
    void disjointMultiFileSpecsWarnOnStderr() throws IOException {
        Path f = fixture("m.tiny");
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--tiny", f + "=1.20.1:official:1.20.1:intermediary",
                "--tiny", f + "=1.20.2:intermediary:1.20.2:official",
                "--limit", "5");
        assertEquals(0, exit, () -> "the first pair still audits fine");
        assertTrue(err.toString().contains("cannot compose"), () -> err.toString());
        assertTrue(out.toString().contains("(file 1 of 2)"), () -> out.toString());
    }

    @Test
    void badSpecStringExitsOne() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        // missing colons in the version/ns tail
        int exit = cli(out, err).execute("graph-audit", "--tiny", fixture("m.tiny") + "=1.20.1:official");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("bad --tiny spec"), () -> err.toString());
    }

    @Test
    void missingFileExitsOne() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--tiny", tmp.resolve("absent.tiny") + "=1:a:2:b");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("no such file"), () -> err.toString());
    }

    @Test
    void unknownNamespaceExitsOne() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--tiny", fixture("m.tiny") + "=1.20.1:official:1.20.1:bogus");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("has no namespace 'bogus'"), () -> err.toString());
    }

    /** --pair naming one node on both sides short-circuits translate() into trivially-green probes. */
    @Test
    void identityPairIsRefused() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--tiny", spec(fixture("m.tiny")),
                "--pair", "1.20.1:official:1.20.1:official");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("endpoints must differ"), () -> err.toString());
    }

    /** A spec whose endpoints are the SAME node is the file-level form of the identity pair. */
    @Test
    void selfMappingSpecIsRefused() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--tiny", fixture("m.tiny") + "=1.20.1:official:1.20.1:official");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("endpoints must differ"), () -> err.toString());
    }

    // -------------------------------------------------- --proguard

    @Test
    void proguardAuditsCleanRoundTrip() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--proguard", proguardSpec(proguardFixture("client-fixture.txt")));
        assertEquals(0, exit);
        assertTrue(err.toString().isEmpty(), "stderr should stay empty on success");
        assertTrue(out.toString().contains("pair=1.20.1/mojang->1.20.1/official"),
                () -> out.toString());
        assertTrue(out.toString().contains("tested=1 ok=1 contradictions=0"),
                () -> "unexpected audit line:\n" + out);
        assertTrue(out.toString().contains("minPathConfidence=1.0000"),
                () -> "one published class edge each way:\n" + out);
        // One class row + one field + one method row -> 3 edges on the loaded line (the
        // single CLASS probe's round trip itself is a fwd+back pair of edges).
        assertTrue(out.toString().contains("edges=3"), () -> "per-file edge line missing:\n" + out);
    }

    @Test
    void proguardComposesWithTinyThroughSharedOfficialNode() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--proguard", proguardSpec(proguardFixture("client-fixture.txt")),
                "--tiny", spec(fixture("m.tiny")),
                "--pair", "1.20.1:mojang:1.20.1:intermediary");
        assertEquals(0, exit);
        assertTrue(err.toString().isEmpty(),
                "shared official node must compose without a warning: " + err);
        assertTrue(out.toString().contains("pair=1.20.1/mojang->1.20.1/intermediary (--pair)"),
                () -> out.toString());
        assertTrue(out.toString().contains("tested=1 ok=1 contradictions=0"),
                () -> "unexpected audit line:\n" + out);
        assertTrue(out.toString().contains("minPathConfidence=1.0000"),
                () -> "two published hops compose to full confidence:\n" + out);
    }

    @Test
    void badProguardSpecExitsOne() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        // missing the verB:nsB tail half
        int exit = cli(out, err).execute("graph-audit",
                "--proguard", proguardFixture("client-fixture.txt") + "=1.20.1:mojang");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("bad --proguard spec"), () -> err.toString());
    }

    @Test
    void proguardUnknownNamespaceExitsOne() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--proguard", proguardFixture("client-fixture.txt") + "=1.20.1:mojang:1.20.1:bogus");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("has no namespace 'bogus'"), () -> err.toString());
    }

    @Test
    void helpListsProguardOption() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit", "--help");
        assertEquals(0, exit, () -> err.toString());
        assertTrue(out.toString().contains("--proguard"), () -> out.toString());
        assertTrue(out.toString().contains("--pair"), () -> out.toString());
    }

    // -------------------------------------------------- composing specs

    /** proguard + tiny compose through the shared official node, but without --pair only the first-loaded (tiny) pair is audited. */
    @Test
    void composingSpecsWithoutPairWarnsOnStderr() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--proguard", proguardSpec(proguardFixture("client-fixture.txt")),
                "--tiny", spec(fixture("m.tiny")));
        assertEquals(0, exit, () -> "the default pair still audits clean: " + err);
        assertTrue(err.toString().contains("pass --pair selects another"), () -> err.toString());
        // tiny loads first, so its bridge is the audited default
        assertTrue(out.toString().contains("pair=1.20.1/official->1.20.1/intermediary"),
                () -> out.toString());
    }

    /** A ProGuard body violating the grammar is a usage-grade failure, not a crash. */
    @Test
    void malformedProguardBodyExitsOne() throws IOException {
        Path p = tmp.resolve("bad-client.txt");
        Files.writeString(p, "    int x -> a\n", StandardCharsets.UTF_8);

        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit", "--proguard", proguardSpec(p));
        assertEquals(1, exit);
        assertTrue(err.toString().contains("cannot load"), () -> err.toString());
    }

    @Test
    void badPairSpecExitsOne() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        // three fields instead of verA:nsA:verB:nsB
        int exit = cli(out, err).execute("graph-audit",
                "--tiny", spec(fixture("m.tiny")),
                "--pair", "1.20.1:official:1.20.1");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("bad --pair"), () -> err.toString());
    }

    // -------------------------------------------------- --derived-tiny

    /** DERIVED_MATCH edges are 0.7: each direction of the round trip filters through 0.7. */
    @Test
    void derivedTinyAuditLoadsAtZeroPointSeven() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--derived-tiny", derivedFixture("derived.tiny")
                        + "=1.21.1:mojang:26.2:mojang");
        assertEquals(0, exit, () -> err.toString());
        assertTrue(err.toString().isEmpty(), "derived ingestion should be silent: " + err);
        assertTrue(out.toString().contains("pair=1.21.1/mojang->26.2/mojang"),
                () -> out.toString());
        assertTrue(out.toString().contains("tested=1 ok=1 contradictions=0"),
                () -> "unexpected audit line:\n" + out);
        assertTrue(out.toString().contains("minPathConfidence=0.4900"),
                "a 0.7 edge each way is 0.7*0.7 = 0.49:\n" + out);
        assertTrue(out.toString().contains("edges=2"),
                "one class + one method row on the loaded line:\n" + out);
    }

    /**
     * The REAL composition shape: a published file whose column is PLAIN {@code mojang}
     * (what client.txt labels) and a derived spec naming plain nodes must share the
     * (version, mojang) node — a --pair spanning BOTH hops audits over one
     * DERIVED_MATCH (0.7) edge and one PUBLISHED (1.0) edge. Before positional
     * resolution the derived file landed on (26.2, mojang@26.2), an island no
     * published file could reach.
     */
    @Test
    void derivedTinyComposesWithPublishedTinyAtMixedConfidence() throws IOException {
        Path der = derivedFixture("derived.tiny");
        Path pub = tmp.resolve("pub.tiny");
        Files.writeString(pub, String.join("\n",
                "tiny\t2\t0\tmojang\tintermediary",
                "c\tcom/ex/New\tnet/minecraft/B"), StandardCharsets.UTF_8);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--derived-tiny", der + "=1.21.1:mojang:26.2:mojang",
                "--tiny", pub + "=26.2:mojang:26.2:intermediary",
                "--pair", "1.21.1:mojang:26.2:intermediary");
        assertEquals(0, exit, () -> err.toString());
        assertTrue(err.toString().isEmpty(),
                "the shared (26.2, mojang) node composes without a warning: " + err);
        assertTrue(out.toString().contains("pair=1.21.1/mojang->26.2/intermediary (--pair)"),
                () -> out.toString());
        assertTrue(out.toString().contains("tested=1 ok=1 contradictions=0"),
                "the composed probe must round-trip across both hops:\n" + out);
        assertTrue(out.toString().contains("minPathConfidence=0.4900"),
                "0.7 (derived) * 1.0 (published) each way = 0.49, the mixed-confidence fingerprint:\n"
                        + out);
        assertTrue(out.toString().contains("edges=2") && out.toString().contains("edges=1"),
                "both files' loaded lines must appear:\n" + out);
    }

    /** Negative control for the composition: without the derived file the pair probe pool is empty. */
    @Test
    void composedPairIsUnreachableWithoutTheDerivedBridge() throws IOException {
        Path pub = tmp.resolve("pub.tiny");
        Files.writeString(pub, String.join("\n",
                "tiny\t2\t0\tmojang\tintermediary",
                "c\tcom/ex/New\tnet/minecraft/B"), StandardCharsets.UTF_8);
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--tiny", pub + "=26.2:mojang:26.2:intermediary",
                "--pair", "1.21.1:mojang:26.2:intermediary");
        assertEquals(1, exit,
                () -> "node 1.21.1/mojang exists only in the derived file: " + out);
        assertTrue(err.toString().contains("nothing audited"), () -> err.toString());
    }

    @Test
    void badDerivedSpecExitsOne() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        // missing the verB:nsB tail half
        int exit = cli(out, err).execute("graph-audit",
                "--derived-tiny", derivedFixture("d.tiny") + "=1.21.1:mojang");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("bad --derived-tiny spec"), () -> err.toString());
    }

    /**
     * Positional resolution is validated against the spec: a spec naming
     * {@code forge@1.21.1} as the expected col0 label cannot silently load a file
     * whose header says {@code mojang@1.21.1} (the old name-lookup refusal, kept as a
     * mismatch refusal now that columns are positional).
     */
    @Test
    void derivedHeaderMismatchExitsOne() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--derived-tiny", derivedFixture("d.tiny") + "=1.21.1:forge:26.2:mojang");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("does not match spec"), () -> err.toString());
    }

    /** A spec whose endpoints are swapped wants the labels in the wrong columns — refused, not silently reversed. */
    @Test
    void derivedReversedSpecIsRefused() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--derived-tiny", derivedFixture("d.tiny") + "=26.2:mojang:1.21.1:mojang");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("does not match spec"), () -> err.toString());
    }

    @Test
    void derivedSelfMappingSpecIsRefused() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit",
                "--derived-tiny", derivedFixture("d.tiny")
                        + "=1.21.1:mojang:1.21.1:mojang");
        assertEquals(1, exit);
        assertTrue(err.toString().contains("endpoints must differ"), () -> err.toString());
    }

    @Test
    void helpListsDerivedTinyOption() throws IOException {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = cli(out, err).execute("graph-audit", "--help");
        assertEquals(0, exit, () -> err.toString());
        assertTrue(out.toString().contains("--derived-tiny"), () -> out.toString());
    }
}
