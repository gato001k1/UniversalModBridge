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
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * remap CLI surface with ProGuard (client.txt) input: D5 exit codes, the
 * --proguard ingestion contract (namespaces resolve by header name, so the
 * mojang↔official bridge flows in the direction the operator asks for), and
 * the D4 no-vacuous-success refusal on jars that carry no parseable classfile.
 */
class RemapCommandTest {

    /** Official<->intermediary bridge, the same shape GraphAuditCommandTest composes with. */
    private static final String TINY = String.join("\n",
            "tiny\t2\t0\tofficial\tintermediary",
            "c\tnet/minecraft/A\tnet/minecraft/class_100",
            "\tf\tI\ta\tfield_1",
            "\tm\t(I)V\ta\tmethod_1");

    @TempDir
    Path tmp;

    /** Routes through the shared factory so D5 exit-code wiring is exercised too. */
    private static CommandLine cli(StringWriter out, StringWriter err) {
        CommandLine cmd = UmbCli.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }

    /** The flag's FROM side is nsA: <verA>:<nsA>:<verB>:<nsB>. */
    private String spec(Path f, String nsA, String nsB) {
        return f + "=1.20.1:" + nsA + ":1.20.1:" + nsB;
    }

    private Path proguardFixture(String name) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, String.join("\n",
                "# comment",
                "net.minecraft.Beta -> net/minecraft/A:",
                "    void jump(int) -> a",
                "    int HEALTH -> b"), StandardCharsets.UTF_8);
        return p;
    }

    private Path tinyFixture(String name) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, TINY, StandardCharsets.UTF_8);
        return p;
    }

    /** The shape umb match emits for a renamed method: com/ex/Old.go -> com/ex/New.run. */
    private Path derivedFixture(String name) throws IOException {
        Path p = tmp.resolve(name);
        Files.writeString(p, String.join("\n",
                "tiny\t2\t0\tmojang@1.21.1\tmojang@26.2",
                "c\tcom/ex/Old\tcom/ex/New",
                "\tm\t(I)V\tgo\trun"), StandardCharsets.UTF_8);
        return p;
    }

    /** Writes META-INF/MANIFEST.MF first, then the given entries — the order real mod jars keep. */
    private static Path jarWithManifest(Path dir, String name, Map<String, byte[]> entries)
            throws IOException {
        Path p = dir.resolve(name);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            manifest.write(out);
            out.closeEntry();
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    /**
     * A class whose go() method invokes {@code net/minecraft/A.a(I)V} — the OFFICIAL
     * names, which is what a legacy mod linking against obfuscated runtime names uses.
     */
    private static byte[] callerCallingOfficialA() {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "q/Caller", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "go", "()V", null, null);
        mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "net/minecraft/A", "a", "(I)V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A class whose go() method invokes {@code net/minecraft/Beta.jump(I)V} — the MOJANG
     * names, which is the FROM side of a composed mojang->intermediary remap.
     */
    private static byte[] callerCallingMojangBeta() {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "q/Caller", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "go", "()V", null, null);
        mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "net/minecraft/Beta", "jump", "(I)V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * A class whose go() method invokes {@code com/ex/Old.go(I)V} — the 1.21.1 column of the
     * umb-match-derived bridge (owner and method both renamed in the 26.2 column).
     */
    private static byte[] callerCallingOldGo() {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "q/Caller", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "go", "()V", null, null);
        mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "com/ex/Old", "go", "(I)V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class whose go() references ONLY java/lang/Object.toString — nothing the mapping touches. */
    private static byte[] callerCallingUnmappable() {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "q/Caller", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "go", "()V", null, null);
        mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "java/lang/Object", "toString", "()Ljava/lang/String;", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.POP);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] entryBytes(Path jar, String name) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            var e = jf.getEntry(name);
            if (e == null) {
                throw new AssertionError("no entry " + name + " in " + jar);
            }
            try (var in = jf.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    // ------------------------------------------------------------------ tests

    /** D4: a jar with no classfile at all must not produce a vacuous "remap completed". */
    @Test
    void manifestOnlyJarWithProguardIsRefusedAsVacuous() throws IOException {
        Path in = jarWithManifest(tmp, "manifest-only.jar", Map.of());
        Path out = tmp.resolve("manifest-only-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--proguard", spec(proguardFixture("client-fixture.txt"), "mojang", "official"));
        assertEquals(1, exit, () -> "a copy with zero classes must not read as success: " + stdout);
        assertTrue(stderr.toString().contains("could be parsed as a classfile"),
                () -> stderr.toString());
    }

    /**
     * The remapper publishes OUT atomically BEFORE the CLI sees the zero-class count, so
     * a pre-existing stale copy at OUT (or the just-written unchanged copy) must be
     * deleted on refusal — downstreams key on file existence (review-found gap).
     */
    @Test
    void refusedVacuousRunLeavesNoOutputBehind() throws IOException {
        Path in = jarWithManifest(tmp, "manifest-only.jar", Map.of());
        Path out = tmp.resolve("manifest-only-out.jar");
        Files.write(out, new byte[] {0, 1, 2, 3, 4}); // pre-seed OUT as a stale artifact
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--proguard", spec(proguardFixture("client-fixture.txt"), "mojang", "official"));
        assertEquals(1, exit, () -> "a copy with zero classes must not read as success: " + stdout);
        assertTrue(stderr.toString().contains("refusing to emit a vacuous result"),
                () -> stderr.toString());
        assertTrue(Files.notExists(out), "the stale unchanged copy must not survive the refusal");
    }

    @Test
    void allowEmptyAcceptsUnchangedCopy() throws IOException {
        Path in = jarWithManifest(tmp, "manifest-only.jar", Map.of());
        Path out = tmp.resolve("manifest-only-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--proguard", spec(proguardFixture("client-fixture.txt"), "mojang", "official"),
                "--allow-empty");
        assertEquals(0, exit, () -> stderr.toString());
        assertTrue(stdout.toString().contains("remapped 1.20.1/mojang -> 1.20.1/official"),
                () -> stdout.toString());
    }

    /** The jar links the obfuscated runtime names; remapping official→mojang recovers the deobfuscated owner. */
    @Test
    void proguardRemapsCallsOfficialToMojang() throws IOException {
        Path in = jarWithManifest(tmp, "caller.jar",
                Map.of("q/Caller.class", callerCallingOfficialA()));
        Path out = tmp.resolve("caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--proguard", spec(proguardFixture("client-fixture.txt"), "official", "mojang"));
        assertEquals(0, exit, () -> stderr.toString());
        // Owner class (net/minecraft/A -> net/minecraft/Beta) + method name (a -> jump).
        assertTrue(stdout.toString().contains("symbols mapped : 2"),
                () -> "expected owner + method to translate:\n" + stdout);
        byte[] bytes = entryBytes(out, "q/Caller.class");
        assertTrue(new String(bytes, StandardCharsets.ISO_8859_1).contains("net/minecraft/Beta"),
                "the INVOKEVIRTUAL owner must be remapped to its mojang name");
    }

    @Test
    void badProguardSpecExitsOne() throws IOException {
        Path in = jarWithManifest(tmp, "caller.jar", Map.of());
        Path out = tmp.resolve("caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        // missing the verB:nsB tail half
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--proguard", proguardFixture("client-fixture.txt") + "=1.20.1:mojang");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("bad --proguard spec"), () -> stderr.toString());
    }

    @Test
    void noMappingGivenExitsOne() throws IOException {
        Path in = jarWithManifest(tmp, "caller.jar", Map.of());
        Path out = tmp.resolve("caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap", in.toString(), out.toString());
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("no mapping given"), () -> stderr.toString());
    }

    // -------------------------------------------------- --pair composition

    /**
     * Neither file alone declares mojang<->intermediary; --pair selects the composed
     * bridge through the shared official node and the remap lands on the far hop.
     */
    @Test
    void composedPairRemapViaPairSpansTwoHops() throws IOException {
        Path tiny = tinyFixture("intermediary.tiny");
        Path in = jarWithManifest(tmp, "mojang-caller.jar",
                Map.of("q/Caller.class", callerCallingMojangBeta()));
        Path out = tmp.resolve("mojang-caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--proguard", spec(proguardFixture("client-fixture.txt"), "mojang", "official"),
                "--tiny", spec(tiny, "official", "intermediary"),
                "--pair", "1.20.1:mojang:1.20.1:intermediary");
        assertEquals(0, exit, () -> stderr.toString());
        assertTrue(stdout.toString().contains("remapped 1.20.1/mojang -> 1.20.1/intermediary"),
                () -> "result line must name the composed pair:\n" + stdout);
        assertTrue(stdout.toString().contains("symbols mapped : 2"),
                () -> "owner + method across two hops:\n" + stdout);
        byte[] bytes = entryBytes(out, "q/Caller.class");
        assertTrue(new String(bytes, StandardCharsets.ISO_8859_1).contains("net/minecraft/class_100"),
                "the INVOKEVIRTUAL owner must reach the intermediary name across the composed bridge");
    }

    @Test
    void badPairSpecExitsOne() throws IOException {
        Path in = jarWithManifest(tmp, "caller.jar", Map.of());
        Path out = tmp.resolve("caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        // three fields instead of verA:nsA:verB:nsB
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--proguard", spec(proguardFixture("client-fixture.txt"), "mojang", "official"),
                "--pair", "1.20.1:mojang:1.20.1");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("bad --pair"), () -> stderr.toString());
    }

    /** D5: an override naming a node no loaded spec bridges must not silently read as an empty remap. */
    @Test
    void pairNamingUnbridgedNodeExitsOne() throws IOException {
        Path in = jarWithManifest(tmp, "caller.jar", Map.of());
        Path out = tmp.resolve("caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        // mojang is bridged by the proguard file, forge is not a loaded node
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--proguard", spec(proguardFixture("client-fixture.txt"), "mojang", "official"),
                "--pair", "1.20.1:mojang:1.20.1:forge");
        assertEquals(1, exit, () -> "an unbridged --pair endpoint is an operator error:\n" + stdout);
        assertTrue(stderr.toString().contains("no loaded spec bridges it"), () -> stderr.toString());
    }

    /** Two composing specs without --pair: the first (tiny) pair binds and the run says so. */
    @Test
    void multiSpecCompositionWarnsOnStderrWithoutPair() throws IOException {
        Path tiny = tinyFixture("intermediary.tiny");
        Path in = jarWithManifest(tmp, "official-caller.jar",
                Map.of("q/Caller.class", callerCallingOfficialA()));
        Path out = tmp.resolve("official-caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--proguard", spec(proguardFixture("client-fixture.txt"), "mojang", "official"),
                "--tiny", spec(tiny, "official", "intermediary"));
        assertEquals(0, exit, () -> stderr.toString());
        assertTrue(stderr.toString().contains("pass --pair to choose a composed pair"),
                () -> stderr.toString());
        assertTrue(stdout.toString().contains("remapped 1.20.1/official -> 1.20.1/intermediary"),
                () -> "default pair is the tiny spec's bridge:\n" + stdout);
    }

    /**
     * D4 guard-2: a class-bearing jar whose references only hit unmappable names must
     * not read as "remapped" — the just-published copy is deleted so downstreams cannot
     * key on file existence.
     */
    @Test
    void zeroTranslatedSymbolsOnClassBearingJarIsRefused() throws IOException {
        Path in = jarWithManifest(tmp, "unmapped-caller.jar",
                Map.of("q/Caller.class", callerCallingUnmappable()));
        Path out = tmp.resolve("unmapped-caller-out.jar");
        Files.write(out, new byte[] {0, 1, 2, 3, 4}); // stale artifact at OUT
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--proguard", spec(proguardFixture("client-fixture.txt"), "mojang", "official"));
        assertEquals(1, exit, () -> "0 symbols on a class-bearing jar must be refused: " + stdout);
        assertTrue(stderr.toString().contains("0 symbols translated"), () -> stderr.toString());
        assertTrue(Files.notExists(out),
                "the just-published unchanged copy must not survive guard-2's refusal");

        // explicit opt-in accepts the unchanged copy
        StringWriter stdout2 = new StringWriter();
        StringWriter stderr2 = new StringWriter();
        int exit2 = cli(stdout2, stderr2).execute("remap",
                in.toString(), out.toString(),
                "--proguard", spec(proguardFixture("client-fixture.txt"), "mojang", "official"),
                "--allow-empty");
        assertEquals(0, exit2, () -> stderr2.toString());
        assertTrue(Files.isRegularFile(out), "allow-empty must leave the output behind");
        assertTrue(stdout2.toString().contains("remapped 1.20.1/mojang -> 1.20.1/official"),
                () -> stdout2.toString());
    }

    // ---------------------------------------------- --derived-tiny version bridges

    /**
     * The umb match emit shape: a bridge from the 1.21.1 column to the 26.2 column whose
     * header labels carry the @version suffix. The caller links com/ex/Old.go(I)V — both
     * owner and method land on the far column through the DERIVED_MATCH (0.7) edges. The
     * SPEC names plain graph nodes; the @-labels are header disambiguation only.
     */
    @Test
    void derivedTinyRemapsOwnerAndMethod() throws IOException {
        Path derived = derivedFixture("bridge.tiny");
        Path in = jarWithManifest(tmp, "old-caller.jar",
                Map.of("q/Caller.class", callerCallingOldGo()));
        Path out = tmp.resolve("old-caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--derived-tiny", derived + "=1.21.1:mojang:26.2:mojang");
        assertEquals(0, exit, () -> stderr.toString());
        assertTrue(stdout.toString().contains("remapped 1.21.1/mojang -> 26.2/mojang"),
                () -> "result line must name the derived pair:\n" + stdout);
        assertTrue(stdout.toString().contains("symbols mapped : 2"),
                () -> "owner class + method from the bridge:\n" + stdout);
        byte[] bytes = entryBytes(out, "q/Caller.class");
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(text.contains("com/ex/New"), "the INVOKEVIRTUAL owner must be renamed");
        assertTrue(text.contains("run"), "the INVOKEVIRTUAL target method must be renamed");
    }

    @Test
    void badDerivedSpecExitsOne() throws IOException {
        Path in = jarWithManifest(tmp, "caller.jar", Map.of());
        Path out = tmp.resolve("caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        // missing the verB:nsB tail half
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--derived-tiny", derivedFixture("bridge.tiny") + "=1.21.1:mojang");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("bad --derived-tiny spec"), () -> stderr.toString());
    }

    /** A spec whose expected col0 label (forge@1.21.1) is not what the header says — refused. */
    @Test
    void derivedHeaderMismatchExitsOne() throws IOException {
        Path in = jarWithManifest(tmp, "caller.jar", Map.of());
        Path out = tmp.resolve("caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--derived-tiny", derivedFixture("bridge.tiny") + "=1.21.1:forge:26.2:mojang");
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("does not match spec"), () -> stderr.toString());
    }

    /**
     * The REAL composition shape: the published file labels its 26.2 column PLAIN
     * {@code mojang} (what client.txt labels), and the derived spec names plain nodes —
     * so both files share the (26.2, mojang) node and --pair walks one 0.7 hop + one 1.0
     * hop out to intermediary.
     */
    @Test
    void derivedTinyComposesWithPublishedTinyViaPair() throws IOException {
        Path published = tmp.resolve("to-intermediary.tiny");
        Files.writeString(published, String.join("\n",
                "tiny\t2\t0\tmojang\tintermediary",
                "c\tcom/ex/New\tnet/minecraft/class_100",
                "\tm\t(I)V\trun\tmethod_1"), StandardCharsets.UTF_8);
        Path derived = derivedFixture("bridge.tiny");
        Path in = jarWithManifest(tmp, "old-caller.jar",
                Map.of("q/Caller.class", callerCallingOldGo()));
        Path out = tmp.resolve("old-caller-out.jar");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("remap",
                in.toString(), out.toString(),
                "--derived-tiny", derived + "=1.21.1:mojang:26.2:mojang",
                "--tiny", published + "=26.2:mojang:26.2:intermediary",
                "--pair", "1.21.1:mojang:26.2:intermediary");
        assertEquals(0, exit, () -> stderr.toString());
        assertTrue(stdout.toString().contains("remapped 1.21.1/mojang -> 26.2/intermediary"),
                () -> "result line must name the composed pair:\n" + stdout);
        assertTrue(stdout.toString().contains("symbols mapped : 2"),
                () -> "owner + method across the 0.7 and 1.0 hops:\n" + stdout);
        byte[] bytes = entryBytes(out, "q/Caller.class");
        assertTrue(new String(bytes, StandardCharsets.ISO_8859_1)
                        .contains("net/minecraft/class_100"),
                "the INVOKEVIRTUAL owner must reach the intermediary name across the bridge");
    }
}