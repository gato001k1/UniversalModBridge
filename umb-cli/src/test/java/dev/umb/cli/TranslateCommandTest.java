package dev.umb.cli;

import dev.umb.cache.CacheKey;
import dev.umb.cache.Sha256;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * translate CLI surface: the end-to-end remap + cache + chain (M4). Exercises the
 * miss-remap-store path through the derived mojang -> 26.2:mojang bridge, the
 * byte-identical HIT path, --force re-remap, the --host component of the CacheKey,
 * --src-ns override vs the M0 analyzer's namespace auto-detection, D5 refusals
 * (unbridged node, identity pair), the D4 no-vacuous-success refusal (which must
 * leave NEITHER OUT nor a cache entry), torn-down-entry tolerance, and pinned
 * non-zero counts on the stored happy path.
 */
class TranslateCommandTest {

    @TempDir
    Path tmp;

    private static final String TRANSLATOR_VERSION = "umb 0.1 (M11)";

    /**
     * Routes through the shared factory so the D5 exit-code wiring (picocli err
     * writer, execution-exception handler) is exercised too.
     */
    private static CommandLine cli(StringWriter out, StringWriter err) {
        CommandLine cmd = UmbCli.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }

    /** The flag's FROM side is nsA: {@code <file>=<verA>:<nsA>:<verB>:<nsB>}. */
    private static String spec(Path f, String verA, String nsA, String verB, String nsB) {
        return f + "=" + verA + ":" + nsA + ":" + verB + ":" + nsB;
    }

    private static Path proguardFixture(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, String.join("\n",
                "# comment",
                "net.minecraft.Beta -> net/minecraft/A:",
                "    void jump(int) -> a",
                "    int HEALTH -> b"), StandardCharsets.UTF_8);
        return p;
    }

    /** The shape umb match emits: com/ex/Old.go -> com/ex/New.run across the 26.2 column. */
    private static Path derivedFixture(Path dir, String name) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, String.join("\n",
                "tiny\t2\t0\tmojang@1.21.1\tmojang@26.2",
                "c\tcom/ex/Old\tcom/ex/New",
                "\tm\t(I)V\tgo\trun"), StandardCharsets.UTF_8);
        return p;
    }

    /**
     * Writes META-INF/MANIFEST.MF first, then the given entries — the order real mod
     * jars keep (manifest-first readers like JarInputStream depend on it).
     */
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

    // ------------------------------------------------------------------ ASM fixtures

    /** A class whose go() method invokes com/ex/Old.go(I)V — the 1.21.1 column of the derived bridge. */
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

    /** A class whose go() method invokes net/minecraft/Beta.jump(I)V — the MOJANG names. */
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

    /** A class whose go() method invokes net/minecraft/A.a(I)V — the OFFICIAL names. */
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

    /**
     * A class the M0 analyzer reads as MOJANG: five {@code net/minecraft/} markers in
     * METHOD DESCS (m1 counts twice, m2..m4 once each — the analyzer scans descs, LDC
     * strings and field name+desc, never instruction operands, so the call's owner in
     * the method body contributes nothing to detection), zero SRG and zero intermediary
     * markers. The call() method ALSO invokes net/minecraft/Beta.jump so the remap
     * translates >= 1 symbol and passes D4 guard-2.
     */
    private static byte[] autoDetectMojangCaller() {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, "q/AutoDetect", null,
                "java/lang/Object", null);
        addReturnMethod(cw, "m1", "(Lnet/minecraft/A;Lnet/minecraft/Beta;)V");
        addReturnMethod(cw, "m2", "(Lnet/minecraft/C;)V");
        addReturnMethod(cw, "m3", "(Lnet/minecraft/D;)V");
        addReturnMethod(cw, "m4", "(Lnet/minecraft/E;)V");
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "call", "()V", null, null);
        mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
                "net/minecraft/Beta", "jump", "(I)V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void addReturnMethod(org.objectweb.asm.ClassWriter cw, String name, String desc) {
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, name, desc, null, null);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    /** The CacheKey translate builds for these inputs (one spec, default loader/slots). */
    private static CacheKey keyFor(Path srcJar, Path spec, String host) throws IOException {
        return new CacheKey(Sha256.ofFile(srcJar), host, "none", TRANSLATOR_VERSION, "none",
                spec.getFileName() + "=" + Sha256.ofFile(spec), "none");
    }

    /** All directories under <cache>/entries — live entries plus any quarantined. */
    private static long entryDirCount(Path cacheDir) throws IOException {
        Path entries = cacheDir.resolve("entries");
        if (!Files.isDirectory(entries)) {
            return 0;
        }
        try (var s = Files.list(entries)) {
            return s.filter(Files::isDirectory).count();
        }
    }

    /** Quarantine markers (*.corrupt-*) under <cache>/entries. */
    private static long corruptMarkerCount(Path cacheDir) throws IOException {
        Path entries = cacheDir.resolve("entries");
        if (!Files.isDirectory(entries)) {
            return 0;
        }
        try (var s = Files.list(entries)) {
            return s.filter(p -> p.getFileName().toString().contains("corrupt")).count();
        }
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

    /** The happy path: MISS remaps through the derived bridge, stores jar + repro manifest. */
    @Test
    void happyPathDerivedMissRemapsStoresPinnedCounts() throws IOException {
        Path bridge = derivedFixture(tmp, "bridge.tiny");
        Path in = jarWithManifest(tmp, "old-caller.jar",
                Map.of("q/Caller.class", callerCallingOldGo()));
        Path out = tmp.resolve("old-caller-out.jar");
        Path cache = tmp.resolve("cache");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("translate",
                in.toString(), out.toString(),
                "--cache", cache.toString(),
                "--src-ver", "1.21.1", "--src-ns", "mojang",
                "--host", "26.2", "--host-ns", "mojang",
                "--derived-tiny", spec(bridge, "1.21.1", "mojang", "26.2", "mojang"));
        assertEquals(0, exit, () -> stderr.toString());
        assertTrue(stdout.toString().contains("remapped 1.21.1/mojang -> 26.2/mojang"),
                () -> "result line must name the derived pair:\n" + stdout);
        assertTrue(stdout.toString().contains("cache: MISS stored"), () -> stdout.toString());

        // D8: pinned non-zero counts on the happy path. entriesCopied counts only
        // verbatim copies — the rewritten manifest and the remapped class are written
        // through writeManifest/writeEntry and do not increment it — so the pins are
        // classes (1) and translated symbols (owner + method).
        assertTrue(stdout.toString().contains("classes        : 1"), () -> stdout.toString());
        assertTrue(stdout.toString().contains("symbols mapped : 2"),
                () -> "owner + method must translate:\n" + stdout);

        // The call must actually land on the far column of the bridge.
        byte[] bytes = entryBytes(out, "q/Caller.class");
        String txt = new String(bytes, StandardCharsets.ISO_8859_1);
        assertTrue(txt.contains("com/ex/New"), "the INVOKEVIRTUAL owner must be renamed");
        assertTrue(txt.contains("run"), "the INVOKEVIRTUAL target method must be renamed");

        // Exact one live entry; the manifest (ReproRecord facts) names the pipeline slots.
        assertEquals(1, entryDirCount(cache), "exactly one stored entry");
        Path seg = cache.resolve("entries").resolve(keyFor(in, bridge, "26.2").asPathSegment());
        String mf = Files.readString(seg.resolve("manifest.json"));
        assertTrue(mf.contains("\"sourceHash\""), () -> mf);
        assertTrue(mf.contains("\"mappingVersions\""), () -> mf);
        assertTrue(mf.contains("\"bridge.tiny\""), () -> mf);
        assertTrue(mf.contains("\"hostVersion\""), () -> mf);
        assertTrue(mf.contains("\"outputHash\""), () -> mf);
        assertTrue(mf.contains("\"storedAt\""), () -> mf);
        assertEquals(Sha256.ofFile(out), Sha256.ofFile(seg.resolve("translated.jar")));
        assertArrayEquals(Files.readAllBytes(out), Files.readAllBytes(seg.resolve("translated.jar")),
                "the stored jar is byte-for-byte the artifact just published");
    }

    /** An identical second run must be a HIT that restores the stored bytes without re-remapping. */
    @Test
    void secondIdenticalRunIsHitByteIdentical() throws IOException {
        Path bridge = derivedFixture(tmp, "bridge.tiny");
        Path in = jarWithManifest(tmp, "old-caller.jar",
                Map.of("q/Caller.class", callerCallingOldGo()));
        Path out = tmp.resolve("old-caller-out.jar");
        Path cache = tmp.resolve("cache");
        String[] args = {"translate",
                in.toString(), out.toString(),
                "--cache", cache.toString(),
                "--src-ver", "1.21.1", "--src-ns", "mojang",
                "--host", "26.2", "--host-ns", "mojang",
                "--derived-tiny", spec(bridge, "1.21.1", "mojang", "26.2", "mojang")};

        assertEquals(0, cli(new StringWriter(), new StringWriter()).execute(args),
                () -> "first run must MISS-remap");
        byte[] firstOut = Files.readAllBytes(out);

        // Sabotage OUT: a HIT must fully replace it with the stored jar, never trust a
        // stale artifact sitting where the operator pointed OUT.
        Files.write(out, new byte[] {9, 8, 7});
        StringWriter stdout = new StringWriter();
        int exit = cli(stdout, new StringWriter()).execute(args);
        assertEquals(0, exit);
        assertTrue(stdout.toString().contains("cache: HIT"), () -> stdout.toString());
        assertFalse(stdout.toString().contains("remapped"), "a HIT is a copy, not a re-remap");
        assertTrue(stdout.toString().contains("output hash    :"),
                "the HIT report prints the stored repro facts:\n" + stdout);
        assertArrayEquals(firstOut, Files.readAllBytes(out),
                "byte-identical copy of the stored jar");
    }

    /** --force skips the lookup entirely and re-remaps, overwriting the stored entry. */
    @Test
    void forceBypassesLookupAndReStores() throws IOException {
        Path bridge = derivedFixture(tmp, "bridge.tiny");
        Path in = jarWithManifest(tmp, "old-caller.jar",
                Map.of("q/Caller.class", callerCallingOldGo()));
        Path out = tmp.resolve("old-caller-out.jar");
        Path cache = tmp.resolve("cache");
        var args = new ArrayList<>(List.of(
                "translate", in.toString(), out.toString(),
                "--cache", cache.toString(),
                "--src-ver", "1.21.1", "--src-ns", "mojang",
                "--host", "26.2", "--host-ns", "mojang",
                "--derived-tiny", spec(bridge, "1.21.1", "mojang", "26.2", "mojang")));

        assertEquals(0, cli(new StringWriter(), new StringWriter())
                .execute(args.toArray(new String[0])));
        args.add("--force");
        StringWriter stdout = new StringWriter();
        int exit = cli(stdout, new StringWriter()).execute(args.toArray(new String[0]));
        assertEquals(0, exit);
        assertTrue(stdout.toString().contains("cache: MISS stored"), () -> stdout.toString());
        assertTrue(stdout.toString().contains("remapped"), "--force re-runs the remap");
        assertFalse(stdout.toString().contains("cache: HIT"));
    }

    /**
     * Different --host must be a DIFFERENT cache key — the mapping slot is the same
     * file (same basename, same sha), the source jar is the same; only the host
     * version differs, so a HIT on the first run's entry would be wrong.
     */
    @Test
    void differentHostYieldsDifferentCacheKey() throws IOException {
        Path pg = proguardFixture(tmp, "client-fixture.txt");
        Path in = jarWithManifest(tmp, "official-caller.jar",
                Map.of("q/Caller.class", callerCallingOfficialA()));
        Path cache = tmp.resolve("cache");

        StringWriter a = new StringWriter();
        int exitA = cli(a, new StringWriter()).execute("translate",
                in.toString(), tmp.resolve("out-a.jar").toString(),
                "--cache", cache.toString(),
                "--src-ver", "1.20.1", "--src-ns", "official",
                "--host", "1.20.1", "--host-ns", "mojang",
                "--proguard", spec(pg, "1.20.1", "official", "1.20.1", "mojang"));
        assertEquals(0, exitA, () -> a.toString());

        StringWriter b = new StringWriter();
        int exitB = cli(b, new StringWriter()).execute("translate",
                in.toString(), tmp.resolve("out-b.jar").toString(),
                "--cache", cache.toString(),
                "--src-ver", "26.2", "--src-ns", "official",
                "--host", "26.2", "--host-ns", "mojang",
                "--proguard", spec(pg, "26.2", "official", "26.2", "mojang"));
        assertEquals(0, exitB, () -> b.toString());
        assertTrue(b.toString().contains("cache: MISS stored"),
                () -> "a new host version must not hit the first entry:\n" + b);
        assertEquals(2, entryDirCount(cache), "two distinct host versions -> two entries");
    }

    // -------------------------------------------- source-namespace resolution

    /**
     * A jar whose namespace markers all live in INSTRUCTION operands (which the M0
     * analyzer deliberately does not scan) is UNKNOWN to auto-detection, so translate
     * must refuse and name --src-ns; the override then drives the same run.
     */
    @Test
    void srcNsOverrideIsHonoredWhenAutoDetectIsInconclusive() throws IOException {
        Path pg = proguardFixture(tmp, "client-fixture.txt");
        Path in = jarWithManifest(tmp, "mojang-caller.jar",
                Map.of("q/Caller.class", callerCallingMojangBeta()));
        Path out = tmp.resolve("mojang-caller-out.jar");
        Path cache = tmp.resolve("cache");

        StringWriter autoErr = new StringWriter();
        int exitAuto = cli(new StringWriter(), autoErr).execute("translate",
                in.toString(), out.toString(),
                "--cache", cache.toString(),
                "--src-ver", "1.20.1",
                "--host", "1.20.1", "--host-ns", "official",
                "--proguard", spec(pg, "1.20.1", "mojang", "1.20.1", "official"));
        assertEquals(1, exitAuto, () -> autoErr.toString());
        assertTrue(autoErr.toString().contains("cannot auto-detect the source namespace"),
                () -> autoErr.toString());
        assertTrue(Files.notExists(out), "auto-detection failure must not touch OUT");

        StringWriter stdout = new StringWriter();
        int exit = cli(stdout, new StringWriter()).execute("translate",
                in.toString(), out.toString(),
                "--cache", cache.toString(),
                "--src-ver", "1.20.1", "--src-ns", "mojang",
                "--host", "1.20.1", "--host-ns", "official",
                "--proguard", spec(pg, "1.20.1", "mojang", "1.20.1", "official"));
        assertEquals(0, exit);
        assertTrue(stdout.toString().contains("remapped 1.20.1/mojang -> 1.20.1/official"),
                () -> stdout.toString());
        String txt = new String(entryBytes(out, "q/Caller.class"), StandardCharsets.ISO_8859_1);
        assertTrue(txt.contains("net/minecraft/A"), "owner must be remapped to the official name");
        // The mapped method name is asserted via the report count: ClassWriter(reader)
        // copies the input constant pool verbatim and never prunes unreferenced
        // leftovers, so the original Utf8 "jump" stays resident in the output bytes
        // even when the INVOKEVIRTUAL was correctly rewritten to "a" (RemapCommandTest
        // pins this same fixture's 2 translated symbols the same way).
        assertTrue(stdout.toString().contains("symbols mapped : 2"),
                () -> "owner + method name must translate:\n" + stdout);
    }

    /**
     * The positive auto-detection branch: five net/minecraft/ markers in method DESCS
     * (no SRG, no intermediary) drive the analyzer to MOJANG, so the source node is
     * resolved WITHOUT --src-ns and the run proceeds onto the host node.
     */
    @Test
    void namespaceAutoDetectMojangFromMarkers() throws IOException {
        Path pg = proguardFixture(tmp, "client-fixture.txt");
        Path in = jarWithManifest(tmp, "auto-detect.jar",
                Map.of("q/AutoDetect.class", autoDetectMojangCaller()));
        Path out = tmp.resolve("auto-detect-out.jar");
        Path cache = tmp.resolve("cache");
        StringWriter stdout = new StringWriter();
        StringWriter stderr = new StringWriter();
        int exit = cli(stdout, stderr).execute("translate",
                in.toString(), out.toString(),
                "--cache", cache.toString(),
                "--src-ver", "1.20.1",
                "--host", "1.20.1", "--host-ns", "official",
                "--proguard", spec(pg, "1.20.1", "mojang", "1.20.1", "official"));
        assertEquals(0, exit, () -> stderr.toString());
        assertTrue(stdout.toString().contains("remapped 1.20.1/mojang -> 1.20.1/official"),
                () -> "the analyzer's MOJANG verdict must feed the source node:\n" + stdout);
        String txt = new String(entryBytes(out, "q/AutoDetect.class"), StandardCharsets.ISO_8859_1);
        assertTrue(txt.contains("net/minecraft/A"),
                "detection is not enough — the call must actually be remapped");
        assertTrue(stdout.toString().contains("cache: MISS stored"), () -> stdout.toString());
        assertEquals(1, entryDirCount(cache), "one stored entry");
    }

    // ------------------------------------------------------------ D5 refusals

    /** A host node no loaded spec bridges is an operator error, not a silent empty remap. */
    @Test
    void unbridgedHostNodeExitsOne() throws IOException {
        Path tiny = tmp.resolve("intermediary.tiny");
        Files.writeString(tiny, String.join("\n",
                "tiny\t2\t0\tofficial\tintermediary",
                "c\tnet/minecraft/A\tnet/minecraft/class_100",
                "\tm\t(I)V\ta\tmethod_1"), StandardCharsets.UTF_8);
        Path in = jarWithManifest(tmp, "official-caller.jar",
                Map.of("q/Caller.class", callerCallingOfficialA()));
        Path cache = tmp.resolve("cache");
        StringWriter stderr = new StringWriter();
        int exit = cli(new StringWriter(), stderr).execute("translate",
                in.toString(), tmp.resolve("out.jar").toString(),
                "--cache", cache.toString(),
                "--src-ver", "1.20.1", "--src-ns", "official",
                "--host", "26.2", "--host-ns", "mojang",
                "--tiny", spec(tiny, "1.20.1", "official", "1.20.1", "intermediary"));
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("26.2/mojang is not bridged by any loaded spec"),
                () -> stderr.toString());
    }

    /** sourceNode == hostNode would short-circuit every translation path — refused upfront. */
    @Test
    void identityNodePairExitsOne() throws IOException {
        Path pg = proguardFixture(tmp, "client-fixture.txt");
        Path in = jarWithManifest(tmp, "caller.jar", Map.of());
        StringWriter stderr = new StringWriter();
        int exit = cli(new StringWriter(), stderr).execute("translate",
                in.toString(), tmp.resolve("out.jar").toString(),
                "--cache", tmp.resolve("cache").toString(),
                "--src-ver", "1.20.1", "--src-ns", "official",
                "--host", "1.20.1", "--host-ns", "official",
                "--proguard", spec(pg, "1.20.1", "mojang", "1.20.1", "official"));
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("cannot map a node to itself"),
                () -> stderr.toString());
    }

    /**
     * D4 guard-2, translate flavor (no --allow-empty exists here): a class-bearing jar
     * with zero translated symbols is refused, the just-published OUT is deleted, and a
     * refused run leaves NO cache entry behind — put() must never see the refusal.
     */
    @Test
    void zeroTranslatedRefusalLeavesNoOutputOrCacheEntry() throws IOException {
        Path pg = proguardFixture(tmp, "client-fixture.txt");
        Path in = jarWithManifest(tmp, "unmapped-caller.jar",
                Map.of("q/Caller.class", callerCallingUnmappable()));
        Path out = tmp.resolve("unmapped-out.jar");
        Files.write(out, new byte[] {0, 1, 2, 3, 4}); // stale artifact at OUT
        Path cache = tmp.resolve("cache");
        StringWriter stderr = new StringWriter();
        int exit = cli(new StringWriter(), stderr).execute("translate",
                in.toString(), out.toString(),
                "--cache", cache.toString(),
                "--src-ver", "1.20.1", "--src-ns", "official",
                "--host", "1.20.1", "--host-ns", "mojang",
                "--proguard", spec(pg, "1.20.1", "official", "1.20.1", "mojang"));
        assertEquals(1, exit);
        assertTrue(stderr.toString().contains("0 symbols translated"), () -> stderr.toString());
        assertTrue(Files.notExists(out), "the stale unchanged copy must not survive the refusal");
        assertTrue(Files.notExists(cache.resolve("entries")),
                "a refused run must not have created any cache entry");
    }

    /**
     * A torn-down entry (stored jar deleted out from under the manifest) is positive
     * corruption: the store quarantines it, the lookup reports MISS, the re-run re-remaps
     * and re-stores, and an untouched third run HITs again. Corruption degrades to a MISS
     * and is repaired — never a crash.
     */
    @Test
    void corruptEntryDegradesToMissAndRecovers() throws IOException {
        Path bridge = derivedFixture(tmp, "bridge.tiny");
        Path in = jarWithManifest(tmp, "old-caller.jar",
                Map.of("q/Caller.class", callerCallingOldGo()));
        Path out = tmp.resolve("old-caller-out.jar");
        Path cache = tmp.resolve("cache");
        String[] args = {"translate",
                in.toString(), out.toString(),
                "--cache", cache.toString(),
                "--src-ver", "1.21.1", "--src-ns", "mojang",
                "--host", "26.2", "--host-ns", "mojang",
                "--derived-tiny", spec(bridge, "1.21.1", "mojang", "26.2", "mojang")};

        assertEquals(0, cli(new StringWriter(), new StringWriter()).execute(args));
        assertEquals(1, entryDirCount(cache));

        // Tear the stored jar out from under the entry.
        Path seg = cache.resolve("entries").resolve(keyFor(in, bridge, "26.2").asPathSegment());
        Files.delete(seg.resolve("translated.jar"));

        StringWriter run2 = new StringWriter();
        int exit2 = cli(run2, new StringWriter()).execute(args);
        assertEquals(0, exit2, "a corrupt entry must feel like a MISS, not a crash");
        assertTrue(run2.toString().contains("cache: MISS stored"), () -> run2.toString());
        assertEquals(1, corruptMarkerCount(cache),
                "the torn-down entry must be quarantined on the way out");
        assertTrue(Files.isRegularFile(seg.resolve("translated.jar")),
                "the re-run must have re-stored a fresh jar under the same key");

        StringWriter run3 = new StringWriter();
        int exit3 = cli(run3, new StringWriter()).execute(args);
        assertEquals(0, exit3);
        assertTrue(run3.toString().contains("cache: HIT"),
                "an untouched third run must HIT the repaired entry:\n" + run3);
    }
}