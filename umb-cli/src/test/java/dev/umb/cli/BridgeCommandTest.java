package dev.umb.cli;

import dev.umb.mappings.TinyV2Reader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * umb bridge CLI surface (M9-2): the srg-name cross-era pairing wired as a command,
 * the D4 no-vacuous-bridge guards, the D5/D7 exit codes, --json, the load-with line
 * that pastes straight into graph-audit --derived-tiny, and the sha256 determinism
 * pin printed on every run.
 */
class BridgeCommandTest {

    /** Real joined.srg 5-token dialect: func_2 keeps its name but changes descriptor across eras. */
    private static final String OLD_SRG = String.join("\n",
            "CL: aak net/minecraft/Server",
            "FD: aak/a net/minecraft/Server/field_1_a",
            "MD: aak/q ()V net/minecraft/Server/func_1_a ()V",
            "MD: aak/r (I)V net/minecraft/Server/func_2_b (I)V");

    private static final String NEW_SRG = String.join("\n",
            "CL: aak net/minecraft/Server",
            "FD: aak/a net/minecraft/Server/field_1_a",
            "MD: aak/q ()V net/minecraft/Server/func_1_a ()V",
            "MD: aak/r (Z)V net/minecraft/Server/func_2_b (Z)V");

    private static final Pattern SHA = Pattern.compile("sha256\\s+: ([0-9a-f]{64})");

    @TempDir
    Path tmp;

    private Path srg(String name, String content) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, content, StandardCharsets.UTF_8);
        return p;
    }

    private static CommandLine cli(StringWriter out, StringWriter err) {
        CommandLine cmd = UmbCli.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }

    /** Reuses the command's exact report format so label padding can never drift. */
    private static String row(String label, int v) {
        return String.format(Locale.ROOT, "  %-22s: %d", label, v);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void bridgeEmitsReportAndFile() throws IOException {
        Path old = srg("joined-1.7.10.srg", OLD_SRG);
        Path neu = srg("joined-1.12.2.srg", NEW_SRG);
        Path out = tmp.resolve("bridge.tiny");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("bridge",
                old.toString(), neu.toString(),
                "--out", out.toString(), "--src-ver", "1.7.10", "--dst-ver", "1.12.2");
        assertEquals(0, exit, () -> stderr.toString());
        assertTrue(stderr.toString().isEmpty(), "bridge should be silent on success");
        assertTrue(Files.isRegularFile(out), "the bridge file must be written");

        String s = stdout.toString();
        assertTrue(s.contains("bridged srg@1.7.10 -> srg@1.12.2"), () -> s);
        assertTrue(s.contains(row("old classes", 1)), () -> s);
        assertTrue(s.contains(row("new classes", 1)), () -> s);
        assertTrue(s.contains(row("classes paired", 1)), () -> s);
        assertTrue(s.contains(row("classes unpaired", 0)), () -> s);
        assertTrue(s.contains(row("fields paired", 1)), () -> s);
        assertTrue(s.contains(row("methods paired", 1)),
                "func_1_a ()V pairs; func_2_b changes descriptor so cannot pair:\n" + s);
        assertTrue(s.contains(row("methods name-ambiguous", 1)), () -> s);
        assertTrue(s.contains(row("edges written", 3)), () -> s);
        assertTrue(s.contains("load with: --derived-tiny " + out
                        + "=1.7.10:srg:1.12.2:srg"),
                "load-with line names the plain graph nodes:\n" + s);

        // the file round-trips with two DISTINCT ns@version header labels and carries
        // the paired class with its field + the descriptor-stable method
        var tf = TinyV2Reader.read(out);
        assertEquals("srg@1.7.10", tf.namespaces().get(0));
        assertEquals("srg@1.12.2", tf.namespaces().get(1),
                "dst header label must stay distinct despite the shared namespace name");
        assertEquals(1, tf.classes().size());
        assertArrayEquals(new String[] { "net/minecraft/Server", "net/minecraft/Server" },
                tf.classes().get(0).names());
        assertEquals(1, tf.classes().get(0).fields().size());
        assertArrayEquals(new String[] { "field_1_a", "field_1_a" },
                tf.classes().get(0).fields().get(0).names());
        assertEquals(1, tf.classes().get(0).methods().size(),
                "func_2_b must not be emitted at guessed identity (D4)");
        assertArrayEquals(new String[] { "func_1_a", "func_1_a" },
                tf.classes().get(0).methods().get(0).names());
        assertEquals("()V", tf.classes().get(0).methods().get(0).descriptor());
    }

    /** The emitted file is the load-with contract's payload: graph-audit ingests it at 0.7. */
    @Test
    void bridgeOutputRoundTripsThroughGraphAudit() throws IOException {
        Path old = srg("joined-1.7.10.srg", OLD_SRG);
        Path neu = srg("joined-1.12.2.srg", NEW_SRG);
        Path out = tmp.resolve("bridge.tiny");
        assertEquals(0, cli(new StringWriter(), new StringWriter()).execute("bridge",
                old.toString(), neu.toString(),
                "--out", out.toString(), "--src-ver", "1.7.10", "--dst-ver", "1.12.2"));

        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        int exit = cli(out2, err2).execute("graph-audit",
                "--derived-tiny", out + "=1.7.10:srg:1.12.2:srg");
        assertEquals(0, exit, () -> err2.toString());
        assertTrue(err2.toString().isEmpty(), () -> err2.toString());
        assertTrue(out2.toString().contains("pair=1.7.10/srg->1.12.2/srg"), () -> out2.toString());
        assertTrue(out2.toString().contains("tested=1 ok=1 contradictions=0"), () -> out2.toString());
    }

    // -------------------------------------------------------------- determinism sha

    @Test
    void sha256IsDeterministicAcrossRuns() throws IOException {
        Path old = srg("joined-1.7.10.srg", OLD_SRG);
        Path neu = srg("joined-1.12.2.srg", NEW_SRG);
        Path out = tmp.resolve("bridge.tiny");

        StringWriter a = new StringWriter();
        assertEquals(0, cli(a, new StringWriter()).execute("bridge",
                old.toString(), neu.toString(),
                "--out", out.toString(), "--src-ver", "1.7.10", "--dst-ver", "1.12.2"));
        byte[] first = Files.readAllBytes(out);
        String sha1 = sha(a.toString());

        StringWriter b = new StringWriter();
        assertEquals(0, cli(b, new StringWriter()).execute("bridge",
                old.toString(), neu.toString(),
                "--out", out.toString(), "--src-ver", "1.7.10", "--dst-ver", "1.12.2"));
        assertEquals(sha1, sha(b.toString()),
                "the printed sha256 must be reproducible run-to-run");
        assertArrayEquals(first, Files.readAllBytes(out),
                "and the written bytes must be byte-for-byte identical");
    }

    // ---------------------------------------------------------------- --json

    @Test
    void jsonEmitsSingleFlatObject() throws IOException {
        Path old = srg("joined-1.7.10.srg", OLD_SRG);
        Path neu = srg("joined-1.12.2.srg", NEW_SRG);
        Path out = tmp.resolve("bridge.tiny");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("bridge",
                old.toString(), neu.toString(),
                "--out", out.toString(), "--src-ver", "1.7.10", "--dst-ver", "1.12.2", "--json");
        assertEquals(0, exit, () -> stderr.toString());
        String trimmed = stdout.toString().trim();
        assertTrue(trimmed.startsWith("{") && trimmed.endsWith("}") && !trimmed.contains("\n"),
                () -> "stdout must be exactly one flat JSON object, got:\n" + stdout);
        assertFalse(trimmed.contains("bridged srg"), "human report must not leak in:\n" + stdout);
        assertTrue(trimmed.contains("\"classesPaired\":1"), () -> trimmed);
        assertTrue(trimmed.contains("\"classesUnpaired\":0"), () -> trimmed);
        assertTrue(trimmed.contains("\"methodsPaired\":1"), () -> trimmed);
        assertTrue(trimmed.contains("\"methodsNameOnlyDropped\":1"), () -> trimmed);
        assertTrue(trimmed.contains("\"edgesWritten\":3"), () -> trimmed);
        assertTrue(trimmed.contains("\"sha256\":\""), () -> trimmed);
        assertTrue(trimmed.contains("\"loadWith\":\"load with: --derived-tiny "
                + out.toString().replace("\\", "\\\\")
                + "=1.7.10:srg:1.12.2:srg\""), () -> trimmed);
    }

    // ---------------------------------------------------- D4 no-vacuous-bridge

    /** 0 SRG classes on one side: the bridge would be empty — refuse, no file. */
    @Test
    void refusesEmptyOldSrg() throws IOException {
        Path old = srg("empty.srg", "# only a comment\n\n");
        Path neu = srg("joined-1.12.2.srg", NEW_SRG);
        Path out = tmp.resolve("bridge.tiny");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("bridge",
                old.toString(), neu.toString(),
                "--out", out.toString(), "--src-ver", "1.7.10", "--dst-ver", "1.12.2");
        assertEquals(1, exit, () -> "an all-empty bridge must not read as success: " + stdout);
        assertTrue(stderr.toString().contains("has 0 SRG classes"), () -> stderr.toString());
        assertTrue(Files.notExists(out), "no output may be created on a refused bridge");
    }

    /** 0 paired classes: the eras share no SRG class names — refuse, no file. */
    @Test
    void refusesNoSharedSrgClasses() throws IOException {
        Path old = srg("a.srg", "CL: aak net/minecraft/Ancient");
        Path neu = srg("b.srg", "CL: bzz net/minecraft/Modern");
        Path out = tmp.resolve("bridge.tiny");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("bridge",
                old.toString(), neu.toString(),
                "--out", out.toString(), "--src-ver", "1.7.10", "--dst-ver", "1.12.2");
        assertEquals(1, exit, () -> "a zero-pair bridge is D4-refused: " + stdout);
        assertTrue(stderr.toString().contains("0 classes paired"), () -> stderr.toString());
        assertTrue(Files.notExists(out));
    }

    /** Malformed srg is an operator problem reported on stderr, not a crash. */
    @Test
    void refusesMalformedSrg() throws IOException {
        Path old = srg("bad.srg", "ZZ: nonsense net/minecraft/Server");
        Path neu = srg("joined-1.12.2.srg", NEW_SRG);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("bridge",
                old.toString(), neu.toString(),
                "--out", tmp.resolve("bridge.tiny").toString(),
                "--src-ver", "1.7.10", "--dst-ver", "1.12.2");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("cannot read"), () -> stderr.toString());
    }

    // --------------------------------------------------------- D5/D7 usage rules

    /** OUT aliasing an input would overwrite the corpus — usage error. */
    @Test
    void outEqualsInputIsRefused() throws IOException {
        Path old = srg("joined-1.7.10.srg", OLD_SRG);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("bridge",
                old.toString(), old.toString(),
                "--out", old.toString(), "--src-ver", "1.7.10", "--dst-ver", "1.12.2");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("must be a different file"), () -> stderr.toString());
    }

    /** A cross-era bridge cannot map a node onto itself: identical versions = identical (ver, ns) node. */
    @Test
    void identityBridgeIsRefused() throws IOException {
        Path old = srg("joined-1.7.10.srg", OLD_SRG);
        Path neu = srg("joined-1.12.2.srg", NEW_SRG);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("bridge",
                old.toString(), neu.toString(),
                "--out", tmp.resolve("bridge.tiny").toString(),
                "--src-ver", "1.7.10", "--dst-ver", "1.7.10");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("must differ"), () -> stderr.toString());
    }

    @Test
    void missingInputExitsOne() throws IOException {
        Path neu = srg("joined-1.12.2.srg", NEW_SRG);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("bridge",
                tmp.resolve("absent.srg").toString(), neu.toString(),
                "--out", tmp.resolve("bridge.tiny").toString(),
                "--src-ver", "1.7.10", "--dst-ver", "1.12.2");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("no such file"), () -> stderr.toString());
        assertTrue(Files.notExists(tmp.resolve("bridge.tiny")));
    }

    @Test
    void usageErrorsExitOne() throws IOException {
        Path old = srg("joined-1.7.10.srg", OLD_SRG);
        Path neu = srg("joined-1.12.2.srg", NEW_SRG);
        // --out missing
        assertEquals(1, cli(new StringWriter(), new StringWriter()).execute("bridge",
                old.toString(), neu.toString(), "--src-ver", "1.7.10", "--dst-ver", "1.12.2"));
        // --src-ver missing
        assertEquals(1, cli(new StringWriter(), new StringWriter()).execute("bridge",
                old.toString(), neu.toString(),
                "--out", tmp.resolve("b.tiny").toString(), "--dst-ver", "1.12.2"));
    }

    private static String sha(String stdout) {
        Matcher m = SHA.matcher(stdout);
        assertTrue(m.find(), () -> "sha256 line must be present in:\n" + stdout);
        return m.group(1);
    }
}