package dev.umb.cli;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
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
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * umb launch CLI surface (Bridge v2). Pins the contract: exit 0 iff every entrypoint
 * completed (or --allow-failures, with the failed count still printed); a missing
 * entrypoint exits 1 and is NAMED; D4 refuses zero class entries or zero entrypoints
 * with no --entry override (stderr, nothing written); --method renames the driven
 * lifecycle; --json is exactly one flat object. Routes through UmbCli.commandLine()
 * so the picocli err-writer wiring is exercised too.
 */
class LaunchCommandTest {

    @TempDir
    Path tmp;

    private static CommandLine cli(StringWriter out, StringWriter err) {
        CommandLine cmd = UmbCli.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }

    private static Path jar(Path dir, String name, Map<String, byte[]> entries, boolean manifest)
            throws IOException {
        Path p = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            if (manifest) {
                var mf = new Manifest();
                mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
                mf.write(out);
                out.closeEntry();
            }
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    /** fabric.mod.json declaring main -> the given classes. */
    private static byte[] fabricMain(String... classes) {
        StringBuilder s = new StringBuilder("{\"entrypoints\":{\"main\":[");
        for (int i = 0; i < classes.length; i++) {
            if (i > 0) {
                s.append(",");
            }
            s.append("\"").append(classes[i]).append("\"");
        }
        return s.append("]}}").toString().getBytes(StandardCharsets.UTF_8);
    }

    /** A public no-arg lifecycle method (default name onInitialize). The JVM does not
     * synthesize constructors, so a no-arg {@code <init>} is emitted explicitly - the
     * driver requires it (ModLoaderTest fixtures do the same). */
    private static byte[] lifecycle(String internalName, String methodName) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, methodName, "()V", null, null);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void defaultCtor(ClassWriter cw) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    /** A class extending an ABSENT class q/Missing. On this toolchain the failure is at
     * LOAD, not drive: {@code Class.forName} resolves the direct-superclass chain, so the
     * load-time NoClassDefFoundError is turned into {@code missing:MISSING_ENTRYPOINT_CLASS}
     * by LifecycleDriver.loadEntrypoint - never an uncaught Error (regression guard for
     * translated mods whose classes carry absent obf supertypes). */
    private static byte[] absentSuper(String internalName) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "q/Missing", null);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "q/Missing", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class whose static initializer THROWS: load succeeds (initialize=false), but
     * newInstance - the first active use - runs {@code <clinit>} and the JVM throws a bare
     * {@link ExceptionInInitializerError} (a {@link LinkageError}; NOT wrapped in
     * InvocationTargetException). This is the drive-time linkage channel the v2.1
     * {@code catch (LinkageError)} classification exists for. */
    private static byte[] explodingClinit(String internalName) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("boom");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException",
                "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(3, 0); // new + dup + ldc "boom" — peak stack 3
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A lifecycle that always throws IllegalStateException("kaboom"). */
    private static byte[] throwingLifecycle(String internalName) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("kaboom");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException",
                "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitMaxs(3, 1); // new + dup + ldc "kaboom" — peak stack is 3
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A host-universe class the mod can CONSTRUCT: com.host.Gadget(int, String) with
     * size() -> 100 + value and name() -> tag. Branches-free fixtures may use the plain
     * ClassWriter(0); this one carries no control flow either, but COMPUTE_FRAMES is used
     * so later branch-bearing fixtures share one gotcha-free pattern. */
    private static byte[] gadgetClass() {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, "com/host/Gadget", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "value", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "tag", "Ljava/lang/String;", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(ILjava/lang/String;)V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ILOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, "com/host/Gadget", "value", "I");
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, "com/host/Gadget", "tag", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "size", "()I", null, null);
        mv.visitIntInsn(Opcodes.BIPUSH, 100);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, "com/host/Gadget", "value", "I");
        mv.visitInsn(Opcodes.IADD);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "name", "()Ljava/lang/String;", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, "com/host/Gadget", "tag", "Ljava/lang/String;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The consumer model's OWN legacy interface: {@code size()} and {@code name()}. */
    private static byte[] sizeInterface(String internalName) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                internalName, null, "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "size", "()I", null, null);
        mv.visitEnd();
        mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "name", "()Ljava/lang/String;", null, null);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A MOD code constructed a REAL host Gadget (41, "gizmo") and returns it from a no-arg
     * lifecycle — the raw-host publish channel (no proxy round-trip). */
    private static byte[] seedClass(String internalName) {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()Ljava/lang/Object;", null, null);
        mv.visitTypeInsn(Opcodes.NEW, "com/host/Gadget");
        mv.visitInsn(Opcodes.DUP);
        mv.visitIntInsn(Opcodes.BIPUSH, 41);
        mv.visitLdcInsn("gizmo");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/host/Gadget", "<init>", "(ILjava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Returns whatever it was passed — publishing the VIEW it consumed (the proxy-return
     * channel: the lifecycle received a materialized object and re-publulished it). */
    private static byte[] forwardingClass(String internalName) {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultCtor(cw);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "(Lq/legacy/Size;)Ljava/lang/Object;", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The CONSUMER: its lifecycle receives the published object through its OWN interface
     * and THROWS unless the host-computed values arrive intact — a passing drive proves the
     * view dispatched to the real host instance (size()==141, name()=="gizmo"). */
    private static byte[] consumingClass(String internalName) {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "(Lq/legacy/Size;)V", null, null);
        Label okSize = new Label();
        Label okName = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "q/legacy/Size", "size", "()I", true);
        mv.visitIntInsn(Opcodes.SIPUSH, 141);
        mv.visitJumpInsn(Opcodes.IF_ICMPEQ, okSize);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("bad size");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(okSize);
        mv.visitLdcInsn("gizmo");
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "q/legacy/Size", "name", "()Ljava/lang/String;", true);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, okName);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        mv.visitInsn(Opcodes.DUP);
        mv.visitLdcInsn("bad name");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(okName);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A consuming lifecycle whose parameter is NOT an interface — the named-D4 target. */
    private static byte[] stringParamConsumer(String internalName) {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        defaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "(Ljava/lang/String;)V", null, null);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Plan JSON for the provider->consumer vertical: a raw-host seed publisher, a
     * consume-and-re-publish provider, and the consuming consumer. */
    private static String verticalPlan() {
        return "[{\"class\":\"q.Seed\",\"publish\":\"thing\"},"
                + "{\"class\":\"q.Provider\",\"consume\":[{\"param\":0,\"id\":\"thing\"}],\"publish\":\"thing\"},"
                + "{\"class\":\"q.Consumer\",\"consume\":[{\"param\":0,\"id\":\"thing\"}]}]";
    }

    /** The mod jar backing the vertical: seed + provider + consumer + the Size interface,
     * with the host jar carrying the constructible com.host.Gadget. */
    private static Map<String, byte[]> verticalMod() {
        return Map.of(
                "fabric.mod.json", fabricMain("q.Seed", "q.Provider", "q.Consumer"),
                "q/Seed.class", seedClass("q/Seed"),
                "q/Provider.class", forwardingClass("q/Provider"),
                "q/Consumer.class", consumingClass("q/Consumer"),
                "q/legacy/Size.class", sizeInterface("q/legacy/Size"));
    }

    @Test
    void scanOnlyLaunchIsUnchanged() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Main"),
                "q/Main.class", lifecycle("q/Main", "onInitialize")), true);
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString());
        assertEquals(0, code);
        assertTrue(out.toString().contains("q.Main completed"), out.toString());
        assertTrue(out.toString().matches("(?s).*failed\\s*:\\s*0.*"), out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void entryOverrideAddsToScanFindings() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        // No metadata at all; only the --entry override names a class.
        Path mod = jar(tmp, "mod.jar", Map.of(
                "q/Main.class", lifecycle("q/Main", "onInitialize")), true);
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--entry", "q.Main");
        assertEquals(0, code);
        assertTrue(out.toString().contains("q.Main completed"), out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void duplicateOverrideLaunchesOnce() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Main"),
                "q/Main.class", lifecycle("q/Main", "onInitialize")), true);
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--entry", "q.Main");
        assertEquals(0, code);
        assertEquals(1, count(out.toString(), "q.Main completed"), out.toString());
    }

    @Test
    void missingEntrypointExitsOneAndNamesIt() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        // fabric.mod.json declares q.Main but the class is absent.
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Main"),
                "q/Other.class", lifecycle("q/Other", "onInitialize")), true);
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString());
        assertEquals(1, code);
        assertTrue(out.toString().contains("q.Main missing:MISSING_ENTRYPOINT_CLASS"), out.toString());
        assertEquals("", err.toString(), "a missing entrypoint is a report line, not a usage error");
    }

    @Test
    void zeroEntrypointsWithoutOverrideRefuses() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        // A real class file, but no metadata and no --entry: refusal, with nothing claimed.
        Path mod = jar(tmp, "mod.jar", Map.of(
                "q/Plain.class", lifecycle("q/Plain", "onInitialize")), true);
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString());
        assertEquals(1, code);
        assertTrue(err.toString().contains("no entrypoints declared"), err.toString());
        assertTrue(err.toString().contains("no --entry given"), err.toString());
        assertFalse(out.toString().contains("launching"), out.toString());
    }

    @Test
    void emptyJarRefuses() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        Path mod = jar(tmp, "empty.jar", Map.of(), true);
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString());
        assertEquals(1, code);
        assertTrue(err.toString().contains("0 class entries"), err.toString());
        assertFalse(out.toString().contains("launching"), out.toString());
    }

    @Test
    void throwingEntrypointReportsCauseAndAllowFailuresDowngradesExit() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Thrower"),
                "q/Thrower.class", throwingLifecycle("q/Thrower")), true);

        var out = new StringWriter();
        var err = new StringWriter();
        int strict = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString());
        assertEquals(1, strict);
        assertTrue(out.toString().contains(
                "q.Thrower threw:java.lang.IllegalStateException: kaboom"), out.toString());
        assertTrue(out.toString().matches("(?s).*failed\\s*:\\s*1.*"), out.toString());

        out = new StringWriter();
        err = new StringWriter();
        int lax = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--allow-failures");
        assertEquals(0, lax);
        // --allow-failures downgrades the exit but NEVER the failed count.
        assertTrue(out.toString().contains("q.Thrower threw:"), out.toString());
        assertTrue(out.toString().matches("(?s).*failed\\s*:\\s*1.*"), out.toString());
    }

    @Test
    void driveTimeLinkageErrorIsPerEntryAndBatchContinues() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        // fabric.mod.json declares BOTH: q.Exploding (throwing <clinit> -> the JVM throws
        // a bare ExceptionInInitializerError at newInstance, a LinkageError) and q.Sibling
        // (healthy). The batch must keep going past the broken entry and report it as its
        // own threw line, never a picocli execution exception.
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Exploding", "q.Sibling"),
                "q/Exploding.class", explodingClinit("q/Exploding"),
                "q/Sibling.class", lifecycle("q/Sibling", "onInitialize")), true);

        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString());
        assertEquals(1, code);
        assertTrue(out.toString().contains("q.Exploding threw:java.lang.ExceptionInInitializerError"),
                out.toString());
        assertTrue(out.toString().contains("q.Sibling completed"),
                "the batch continues past the linkage failure: " + out);
        assertTrue(out.toString().matches("(?s).*failed\\s*:\\s*1.*"), out.toString());
        assertEquals("", err.toString(), "a linkage failure is a report line, not a usage error");

        out = new StringWriter();
        err = new StringWriter();
        int lax = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--allow-failures");
        assertEquals(0, lax);
        assertTrue(out.toString().contains("q.Exploding threw:java.lang.ExceptionInInitializerError"),
                out.toString());
        assertTrue(out.toString().contains("q.Sibling completed"), out.toString());
        assertTrue(out.toString().matches("(?s).*failed\\s*:\\s*1.*"),
                "--allow-failures never suppresses the failed count: " + out);
    }

    @Test
    void absentDirectSuperClassIsClassifiedAtLoadNeverEscalates() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        // q.Broken extends the entirely absent q/Missing. On this toolchain Class.forName
        // resolves the direct-superclass chain, so the NoClassDefFoundError surfaces at
        // LOAD and is already an honest per-entry missing line (v2.1 regression guard: the
        // translated-mod case must never become an uncaught Error).
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Broken"),
                "q/Broken.class", absentSuper("q/Broken")), true);
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString());
        assertEquals(1, code);
        assertTrue(out.toString().contains(
                "q.Broken missing:MISSING_ENTRYPOINT_CLASS"), out.toString());
        assertTrue(out.toString().matches("(?s).*failed\\s*:\\s*1.*"), out.toString());
        assertEquals("", err.toString(), "a load-time linkage failure is a report line too");
    }

    @Test
    void jsonEmitsExactlyOneFlatObject() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Main"),
                "q/Main.class", lifecycle("q/Main", "onInitialize")), true);
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--json");
        assertEquals(0, code);
        String[] lines = out.toString().strip().split("\n");
        assertEquals(1, lines.length, out.toString());
        String json = lines[0];
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(mod.toAbsolutePath().toString(), o.get("modJar").getAsString());
        assertEquals(host.toAbsolutePath().toString(), o.get("hostJar").getAsString());
        assertEquals("q.Main", o.get("entrypoints").getAsJsonArray().get(0)
                .getAsJsonObject().get("class").getAsString());
        assertEquals("fabric:main", o.get("entrypoints").getAsJsonArray().get(0)
                .getAsJsonObject().get("source").getAsString());
        assertEquals("completed", o.get("results").getAsJsonArray().get(0)
                .getAsJsonObject().get("status").getAsString());
        assertEquals(1, o.get("completed").getAsInt());
        assertEquals(0, o.get("failed").getAsInt());
        assertEquals(0, o.get("exit").getAsInt());
        assertEquals("", err.toString());
    }

    @Test
    void renamedMethodDrives() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Main"),
                "q/Main.class", lifecycle("q/Main", "start")), true);

        // No --method: the lifecycle is start, not onInitialize.
        var out = new StringWriter();
        var err = new StringWriter();
        int strict = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString());
        assertEquals(1, strict);
        assertTrue(out.toString().contains("q.Main missing:NO_LIFECYCLE_METHOD"), out.toString());

        // --method start finds it.
        out = new StringWriter();
        err = new StringWriter();
        int renamed = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--method", "start");
        assertEquals(0, renamed);
        assertTrue(out.toString().contains("q.Main completed"), out.toString());
    }

    // ------------------------------------------------------------------ M7 interop (--plan)

    @Test
    void planDrivesProviderThenConsumerThroughPublishAndConsume() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of("com/host/Gadget.class", gadgetClass()), true);
        Path mod = jar(tmp, "mod.jar", verticalMod(), true);
        Path plan = Files.writeString(tmp.resolve("plan.json"), verticalPlan());
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--plan", plan.toString());
        assertEquals(0, code, out.toString());
        // The seed CONSTRUCTED a real host Gadget and published it (raw-host channel);
        // the provider consumed the view and RE-PUBLISHED it (proxy channel); the consumer
        // resolved it through its OWN interface and its drive only completes if the
        // host-computed values arrive — one host instance, every side a view.
        assertTrue(out.toString().contains("q.Seed completed published:thing"), out.toString());
        assertTrue(out.toString().contains("q.Provider completed consumed:thing published:thing"), out.toString());
        assertTrue(out.toString().contains("q.Consumer completed consumed:thing"), out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void consumeBeforePublishIsPerEntryMissingAndBatchContinues() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Broken", "q.Fine"),
                "q/Broken.class", consumingClass("q/Broken"),
                "q/Fine.class", lifecycle("q/Fine", "onInitialize"),
                "q/legacy/Size.class", sizeInterface("q/legacy/Size")), true);
        Path plan = Files.writeString(tmp.resolve("plan.json"),
                "[{\"class\":\"q.Broken\",\"consume\":[{\"param\":0,\"id\":\"nothing\"}]},"
                        + "{\"class\":\"q.Fine\"}]");
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--plan", plan.toString());
        assertEquals(1, code, out.toString());
        assertTrue(out.toString().contains("q.Broken missing:NOT_PUBLISHED consumed:nothing"), out.toString());
        assertTrue(out.toString().contains("q.Fine completed"),
                "the batch continues past an unresolved consume: " + out);
        assertEquals("", err.toString());
    }

    @Test
    void malformedPlanRefusesD4() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Main"),
                "q/Main.class", lifecycle("q/Main", "onInitialize")), true);
        // An entry with no "class" at all: the scanner discipline refuses, never guesses.
        Path plan = Files.writeString(tmp.resolve("plan.json"),
                "[{\"consume\":[{\"param\":0}]}]");
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--plan", plan.toString());
        assertEquals(1, code);
        assertTrue(err.toString().contains("malformed plan"), err.toString());
        assertTrue(err.toString().contains("class"), err.toString());
        assertFalse(out.toString().contains("launching"),
                "a malformed plan drives nothing: " + out);
    }

    @Test
    void voidLifecyclePublishesNothingAndConsumerNamesNotPublished() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of(), true);
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.VoidP", "q.Consumer"),
                "q/VoidP.class", lifecycle("q/VoidP", "onInitialize"),
                "q/Consumer.class", consumingClass("q/Consumer"),
                "q/legacy/Size.class", sizeInterface("q/legacy/Size")), true);
        Path plan = Files.writeString(tmp.resolve("plan.json"),
                "[{\"class\":\"q.VoidP\",\"publish\":\"thing\"},"
                        + "{\"class\":\"q.Consumer\",\"consume\":[{\"param\":0,\"id\":\"thing\"}]}]");
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--plan", plan.toString());
        assertEquals(1, code, out.toString());
        // The void provider completed and published NOTHING - no error, nothing declared.
        assertTrue(out.toString().contains("q.VoidP completed"), out.toString());
        assertFalse(out.toString().contains(" published:"), out.toString());
        assertTrue(out.toString().contains("q.Consumer missing:NOT_PUBLISHED consumed:thing"), out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void planComposesWithEntryOverrideAndDedupsToOncePerClass() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of("com/host/Gadget.class", gadgetClass()), true);
        Path mod = jar(tmp, "mod.jar", Map.of(
                        "fabric.mod.json", fabricMain("q.Seed", "q.Consumer", "q.Extra"),
                        "q/Seed.class", seedClass("q/Seed"),
                        "q/Consumer.class", consumingClass("q/Consumer"),
                        "q/Extra.class", lifecycle("q/Extra", "onInitialize"),
                        "q/legacy/Size.class", sizeInterface("q/legacy/Size")), true);
        // --entry adds a class the plan does not name, and re-names a planned class:
        // both must still drive exactly once (same dedup as scan+--entry).
        Path plan = Files.writeString(tmp.resolve("plan.json"),
                "[{\"class\":\"q.Seed\",\"publish\":\"thing\"},"
                        + "{\"class\":\"q.Consumer\",\"consume\":[{\"param\":0,\"id\":\"thing\"}]}]");
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--plan", plan.toString(),
                "--entry", "q.Seed", "--entry", "q.Extra");
        assertEquals(0, code, out.toString());
        assertEquals(1, count(out.toString(), "q.Seed completed"), out.toString());
        assertEquals(1, count(out.toString(), "q.Consumer completed"), out.toString());
        assertEquals(1, count(out.toString(), "q.Extra completed"), out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void consumeIntoNonInterfaceParamIsNamedD4() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of("com/host/Gadget.class", gadgetClass()), true);
        Path mod = jar(tmp, "mod.jar", Map.of(
                "fabric.mod.json", fabricMain("q.Seed", "q.Consumer"),
                "q/Seed.class", seedClass("q/Seed"),
                "q/Consumer.class", stringParamConsumer("q/Consumer")), true);
        // The seed publishes under "thing", but the consumer's lifecycle is
        // onInitialize(String): its parameter interface can never back a view - a named
        // CONSUME_MISMATCH D4, never a silent cast to a guessed interface.
        Path plan = Files.writeString(tmp.resolve("plan.json"),
                "[{\"class\":\"q.Seed\",\"publish\":\"thing\"},"
                        + "{\"class\":\"q.Consumer\",\"consume\":[{\"param\":0,\"id\":\"thing\"}]}]");
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--plan", plan.toString());
        assertEquals(1, code, out.toString());
        assertTrue(out.toString().contains(
                "q.Consumer missing:CONSUME_MISMATCH consume parameter 0 must be an interface"), out.toString());
        assertTrue(out.toString().contains("q.Seed completed published:thing"), out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void jsonCarriesConsumedAndPublished() throws IOException {
        Path host = jar(tmp, "host.jar", Map.of("com/host/Gadget.class", gadgetClass()), true);
        Path mod = jar(tmp, "mod.jar", verticalMod(), true);
        Path plan = Files.writeString(tmp.resolve("plan.json"), verticalPlan());
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "launch", mod.toString(), "--host", host.toString(), "--plan", plan.toString(), "--json");
        assertEquals(0, code, out.toString());
        String[] lines = out.toString().strip().split("\n");
        assertEquals(1, lines.length, "still exactly one flat object: " + out);
        JsonObject o = JsonParser.parseString(lines[0]).getAsJsonObject();
        JsonArray results = o.get("results").getAsJsonArray();
        assertEquals("cli:--plan", o.get("entrypoints").getAsJsonArray().get(0)
                .getAsJsonObject().get("source").getAsString());
        assertEquals("thing", results.get(0).getAsJsonObject().get("published").getAsString());
        assertEquals("completed", results.get(0).getAsJsonObject().get("status").getAsString());
        assertEquals(1, results.get(1).getAsJsonObject().get("consumed").getAsJsonArray().size());
        assertEquals("thing", results.get(1).getAsJsonObject().get("consumed")
                .getAsJsonArray().get(0).getAsString());
        assertEquals("thing", results.get(1).getAsJsonObject().get("published").getAsString());
        assertEquals("thing", results.get(2).getAsJsonObject().get("consumed")
                .getAsJsonArray().get(0).getAsString());
        assertEquals(3, o.get("completed").getAsInt());
        assertEquals(0, o.get("failed").getAsInt());
        assertEquals(0, o.get("exit").getAsInt());
        assertEquals("", err.toString());
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }
}