package dev.umb.cli;

import dev.umb.mappings.TinyV2Reader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * umb match CLI surface (M4): the structural matcher wired as a command, the D4
 * no-vacuous-bridge guards, the D5/D7 exit codes, --json, and the load-with line
 * that makes the emitted file paste straight into graph-audit / remap.
 */
class MatchCommandTest {

    private static final String SRC_SPECS = "k/Keep!f|x|I"; // exact-name class
    private static final String SRC_SPECS_2 = "com/ex/Old!m|go|(I)V"; // renamed in dst

    @TempDir
    Path tmp;

    /** Routes through the shared factory so D5 exit-code wiring is exercised too. */
    private static CommandLine cli(StringWriter out, StringWriter err) {
        CommandLine cmd = UmbCli.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }

    /**
     * Spec "com/ex/Old!m|go|(I)V!f|a|I": class name, then members as
     * {@code kind|name|descriptor} ({@code f} field, {@code m} method).
     */
    private static byte[] classBytes(String spec) {
        String[] bits = spec.split("!");
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, bits[0], null, "java/lang/Object", null);
        for (int i = 1; i < bits.length; i++) {
            String[] m = bits[i].split("\\|");
            if (m[0].equals("f")) {
                var fv = cw.visitField(Opcodes.ACC_PUBLIC, m[1], m[2], null, null);
                fv.visitEnd();
            } else {
                MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m[1], m[2], null, null);
                mv.visitCode();
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(0, 0);
                mv.visitEnd();
            }
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Jar entries (classpath keyed by internal name, ASM takes the bytes anyway). */
    private static Map<String, byte[]> jarEntries(String... specs) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (String s : specs) {
            out.put(s.split("!")[0] + ".class", classBytes(s));
        }
        return out;
    }

    /** Writes META-INF/MANIFEST.MF first, then the given entries — the order real mod jars keep. */
    private Path jar(String name, String... specs) throws IOException {
        Path p = tmp.resolve(name);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            manifest.write(out);
            out.closeEntry();
            for (var e : jarEntries(specs).entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void matchEmitsBridgeAndReport() throws IOException {
        Path src = jar("src.jar", SRC_SPECS, SRC_SPECS_2);
        Path dst = jar("dst.jar", SRC_SPECS, "com/ex/New!m|run|(I)V");
        Path out = tmp.resolve("bridge.tiny");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("match",
                src.toString(), dst.toString(),
                "--out", out.toString(), "--src-ver", "1.21.1", "--dst-ver", "26.2");
        assertEquals(0, exit, () -> stderr.toString());
        assertTrue(stderr.toString().isEmpty(), "match should be silent on success");
        assertTrue(Files.isRegularFile(out), "the bridge file must be written");

        String s = stdout.toString();
        // counters per pass (labels are %-22s aligned, so pad intentionally)
        assertTrue(s.contains("matched mojang@1.21.1 -> mojang@26.2"), () -> s);
        assertTrue(s.contains("src classes parseable : 2"), () -> s);
        assertTrue(s.contains("classes exact         : 1"), () -> s);
        assertTrue(s.contains("classes fingerprint   : 1"), () -> s);
        assertTrue(s.contains("class collisions      : 0"), () -> s);
        assertTrue(s.contains("classes matched       : 2"), () -> s);
        assertTrue(s.contains("members exact         : 1"), () -> s);
        assertTrue(s.contains("members unique-desc   : 1"), () -> s);
        assertTrue(s.contains("edges written         : 4"), () -> s);

        // the ready-to-paste ingestion spec: PLAIN graph nodes, so the bridge composes
        // with published specs on the same (ver, ns) node
        assertTrue(s.contains("load with: --derived-tiny " + out
                        + "=1.21.1:mojang:26.2:mojang"),
                "load-with line must name the plain graph nodes:\n" + s);

        // the file round-trips through the reader with two DISTINCT ns@version header
        // labels — the writer tiny-v2-escapes dots (so the raw bytes differ), the reader
        // unescapes them — and carries both bridges, the shape --derived-tiny consumes
        var tf = TinyV2Reader.read(out);
        assertEquals("mojang@1.21.1", tf.namespaces().get(0), "src header label");
        assertEquals("mojang@26.2", tf.namespaces().get(1),
                "dst header label must stay distinct despite the shared namespace name");
        TinyV2Reader.ClassEntry old = tf.classes().stream()
                .filter(c -> c.names()[0].equals("com/ex/Old")).findFirst().orElseThrow();
        assertEquals("com/ex/New", old.names()[1], "renamed class bridges to its dst column");
        assertEquals("(I)V", old.methods().get(0).descriptor(), "descriptor stays in src form");
        assertEquals("go", old.methods().get(0).names()[0], "src member name");
        assertEquals("run", old.methods().get(0).names()[1], "renamed member lands on dst column");
        TinyV2Reader.ClassEntry keep = tf.classes().stream()
                .filter(c -> c.names()[0].equals("k/Keep")).findFirst().orElseThrow();
        assertEquals("k/Keep", keep.names()[1], "same-name class self-maps across columns");
    }

    /** The emitted file is the load-with contract's payload: graph-audit ingests it at 0.7. */
    @Test
    void matchOutputRoundTripsThroughGraphAudit() throws IOException {
        Path src = jar("src.jar", SRC_SPECS, SRC_SPECS_2);
        Path dst = jar("dst.jar", SRC_SPECS, "com/ex/New!m|run|(I)V");
        Path out = tmp.resolve("bridge.tiny");
        assertEquals(0, cli(new StringWriter(), new StringWriter()).execute("match",
                src.toString(), dst.toString(),
                "--out", out.toString(), "--src-ver", "1.21.1", "--dst-ver", "26.2"));

        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        int exit = cli(out2, err2).execute("graph-audit",
                "--derived-tiny", out + "=1.21.1:mojang:26.2:mojang");
        assertEquals(0, exit, () -> err2.toString());
        assertTrue(err2.toString().isEmpty());
        assertTrue(out2.toString().contains("pair=1.21.1/mojang->26.2/mojang"),
                () -> out2.toString());
        assertTrue(out2.toString().contains("tested=2 ok=2 contradictions=0"));
        assertTrue(out2.toString().contains("minPathConfidence=0.4900"),
                "both directions traverse a 0.7 edge, so 0.7*0.7:\n" + out2);
    }

    // ---------------------------------------------------------------- --json

    /** Exactly one flat JSON object on stdout; no human lines can leak in. */
    @Test
    void jsonEmitsSingleFlatObject() throws IOException {
        Path src = jar("src.jar", SRC_SPECS, SRC_SPECS_2);
        Path dst = jar("dst.jar", SRC_SPECS, "com/ex/New!m|run|(I)V");
        Path out = tmp.resolve("bridge.tiny");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("match",
                src.toString(), dst.toString(),
                "--out", out.toString(), "--src-ver", "1.21.1", "--dst-ver", "26.2", "--json");
        assertEquals(0, exit, () -> stderr.toString());
        String trimmed = stdout.toString().trim();
        assertTrue(trimmed.startsWith("{") && trimmed.endsWith("}") && !trimmed.contains("\n"),
                () -> "stdout must be exactly one flat JSON object, got:\n" + stdout);
        assertFalse(trimmed.contains("matched mojang"), "human report must not leak in:\n" + stdout);
        assertTrue(trimmed.contains("\"parseableSrcClasses\":2"), () -> trimmed);
        assertTrue(trimmed.contains("\"classesExact\":1"), () -> trimmed);
        assertTrue(trimmed.contains("\"classesFingerprint\":1"), () -> trimmed);
        assertTrue(trimmed.contains("\"membersExact\":1"), () -> trimmed);
        assertTrue(trimmed.contains("\"membersUniqueDesc\":1"), () -> trimmed);
        assertTrue(trimmed.contains("\"edgesWritten\":4"), () -> trimmed);
        assertTrue(trimmed.contains("\"loadWith\":\"load with: --derived-tiny "
                + out.toString().replace("\\", "\\\\")
                + "=1.21.1:mojang:26.2:mojang\""), () -> trimmed);
    }

    // ---------------------------------------------------- D4 no-vacuous-bridge

    /** 0 parseable classes on one side: the bridge would be empty — refuse, no file. */
    @Test
    void refusesEmptySourceJar() throws IOException {
        Path src = jar("empty.jar");
        Path dst = jar("dst.jar", SRC_SPECS, "com/ex/New!m|run|(I)V");
        Path out = tmp.resolve("bridge.tiny");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("match",
                src.toString(), dst.toString(),
                "--out", out.toString(), "--src-ver", "1.21.1", "--dst-ver", "26.2");
        assertEquals(1, exit, () -> "an all-empty match must not read as success: " + stdout);
        assertTrue(stderr.toString().contains("has 0 parseable classes"), () -> stderr.toString());
        assertTrue(Files.notExists(out), "no output may be created on a refused match");
    }

    /** 0 matched classes: unrelated namespaces or unrelated structure — refuse, no file. */
    @Test
    void refusesZeroMatchedClasses() throws IOException {
        Path src = jar("src.jar", "x/A!f|a|I");
        Path dst = jar("dst.jar", "y/B!m|go|()V");
        Path out = tmp.resolve("bridge.tiny");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("match",
                src.toString(), dst.toString(),
                "--out", out.toString(), "--src-ver", "1.21.1", "--dst-ver", "26.2");
        assertEquals(1, exit, () -> "a zero-match bridge is D4-refused: " + stdout);
        assertTrue(stderr.toString().contains("0 classes matched"), () -> stderr.toString());
        assertTrue(Files.notExists(out));
    }

    /** 0 members matched but classes DID match: honest warning, exit 0, class edges only. */
    @Test
    void zeroMembersWarnsButEmitsClassEdges() throws IOException {
        Path src = jar("src.jar", "n/Empty");
        Path dst = jar("dst.jar", "n/Empty");
        Path out = tmp.resolve("bridge.tiny");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("match",
                src.toString(), dst.toString(),
                "--out", out.toString(), "--src-ver", "1.21.1", "--dst-ver", "26.2");
        assertEquals(0, exit, () -> stderr.toString());
        assertTrue(stderr.toString().contains("0 members matched"),
                "the missing static surface is a loud warning, not a lie:\n" + stderr);
        assertTrue(stdout.toString().contains("edges written         : 1"), () -> stdout.toString());
        assertTrue(Files.isRegularFile(out), "class edges alone are still a bridge");
    }

    // --------------------------------------------------------- D5/D7 usage rules

    /** OUT aliasing an input would overwrite the corpus — usage error. */
    @Test
    void outEqualsInputIsRefused() throws IOException {
        Path src = jar("src.jar", SRC_SPECS);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("match",
                src.toString(), src.toString(),
                "--out", src.toString(), "--src-ver", "1.21.1", "--dst-ver", "26.2");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("must be a different file"), () -> stderr.toString());
    }

    /** A version bridge cannot map a node onto itself: identical ver+ns couples. */
    @Test
    void identityBridgeIsRefused() throws IOException {
        Path src = jar("src.jar", SRC_SPECS);
        Path dst = jar("dst.jar", SRC_SPECS);
        Path out = tmp.resolve("bridge.tiny");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("match",
                src.toString(), dst.toString(),
                "--out", out.toString(), "--src-ver", "26.2", "--dst-ver", "26.2");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("must differ"), () -> stderr.toString());
    }

    @Test
    void usageErrorsExitOne() throws IOException {
        Path src = jar("src.jar", SRC_SPECS);
        Path dst = jar("dst.jar", SRC_SPECS);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        // --out missing
        int exit = cli(stdout, stderr).execute("match",
                src.toString(), dst.toString(), "--src-ver", "1.21.1", "--dst-ver", "26.2");
        assertEquals(1, exit, () -> "missing required --out must exit 1, got:\n" + stdout);

        StringWriter out2 = new StringWriter();
        StringWriter err2 = new StringWriter();
        // --src-ver missing
        int exit2 = cli(out2, err2).execute("match", src.toString(), dst.toString(),
                "--out", tmp.resolve("b.tiny").toString(), "--dst-ver", "2");
        assertEquals(1, exit2, () -> err2.toString());
    }

    @Test
    void missingSourceJarExitsOne() throws IOException {
        Path dst = jar("dst.jar", SRC_SPECS);
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("match",
                tmp.resolve("absent.jar").toString(), dst.toString(),
                "--out", tmp.resolve("b.tiny").toString(), "--src-ver", "1", "--dst-ver", "2");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("no such file"), () -> stderr.toString());
        assertTrue(Files.notExists(tmp.resolve("b.tiny")));
    }
}