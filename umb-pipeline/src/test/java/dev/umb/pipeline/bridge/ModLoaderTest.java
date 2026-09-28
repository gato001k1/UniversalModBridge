package dev.umb.pipeline.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModLoader + LifecycleDriver (M7 bridge v1): a mod entrypoint class loads in a mod
 * classloader that is a CHILD of the host universe, mod code actually RUNS (recording
 * results into its own statics), and the isolation invariant holds — the mod sees host
 * universe + own classes but NEVER the UMB app classes. Failures are named
 * MaterializationException kinds; D8 pins follow the v0 style (named absence, unwrapped
 * cause, determinism).
 */
class ModLoaderTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ ASM fixtures

    /** The smoke-host gadget from v0: host-computed values (size+1, name.toUpperCase). */
    static byte[] gadgetClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "com/host/Gadget", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "size", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "name", "Ljava/lang/String;", null, null).visitEnd();

        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(ILjava/lang/String;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ILOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, "com/host/Gadget", "size", "I");
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ALOAD, 2);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, "com/host/Gadget", "name", "Ljava/lang/String;");
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(2, 3);
        ctor.visitEnd();

        var size = cw.visitMethod(Opcodes.ACC_PUBLIC, "size", "()I", null, null);
        size.visitCode();
        size.visitVarInsn(Opcodes.ALOAD, 0);
        size.visitFieldInsn(Opcodes.GETFIELD, "com/host/Gadget", "size", "I");
        size.visitInsn(Opcodes.ICONST_1);
        size.visitInsn(Opcodes.IADD);
        size.visitInsn(Opcodes.IRETURN);
        size.visitMaxs(2, 1);
        size.visitEnd();

        var name = cw.visitMethod(Opcodes.ACC_PUBLIC, "name", "()Ljava/lang/String;", null, null);
        name.visitCode();
        name.visitVarInsn(Opcodes.ALOAD, 0);
        name.visitFieldInsn(Opcodes.GETFIELD, "com/host/Gadget", "name", "Ljava/lang/String;");
        name.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toUpperCase", "()Ljava/lang/String;", false);
        name.visitInsn(Opcodes.ARETURN);
        name.visitMaxs(1, 1);
        name.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Mod entrypoint that records "it ran" into a static field. */
    private static byte[] modMain() {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "q/ModMain", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "ran", "I", null, null).visitEnd();
        defaultCtor(cw);
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.ICONST_1);
        m.visitFieldInsn(Opcodes.PUTSTATIC, "q/ModMain", "ran", "I");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Mod entrypoint that receives one int argument and records it. */
    private static byte[] withArgs() {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "q/WithArgs", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sum", "I", null, null).visitEnd();
        defaultCtor(cw);
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "(I)V", null, null);
        m.visitCode();
        m.visitVarInsn(Opcodes.ILOAD, 1);
        m.visitFieldInsn(Opcodes.PUTSTATIC, "q/WithArgs", "sum", "I");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(1, 2);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Mod entrypoint whose onInitialize invokes a HOST-universe class method (size()+1). */
    private static byte[] hostCalling() {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "q/HostCalling", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "hostSize", "I", null, null).visitEnd();
        defaultCtor(cw);
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "com/host/Gadget");
        m.visitInsn(Opcodes.DUP);
        m.visitIntInsn(Opcodes.BIPUSH, 41);
        m.visitLdcInsn("x");
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "com/host/Gadget", "<init>", "(ILjava/lang/String;)V", false);
        m.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "com/host/Gadget", "size", "()I", false);
        m.visitFieldInsn(Opcodes.PUTSTATIC, "q/HostCalling", "hostSize", "I");
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(4, 1);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Mod entrypoint whose onInitialize RETURNS a value (M7 interop: publish-by-returning). */
    private static byte[] returning() {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "q/Returning", null, "java/lang/Object", null);
        defaultCtor(cw);
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()Ljava/lang/String;", null, null);
        m.visitCode();
        m.visitLdcInsn("hello");
        m.visitInsn(Opcodes.ARETURN);
        m.visitMaxs(1, 1);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Mod entrypoint whose onInitialize always throws. */
    private static byte[] throwing() {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "q/Throwing", null, "java/lang/Object", null);
        defaultCtor(cw);
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        m.visitCode();
        m.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException");
        m.visitInsn(Opcodes.DUP);
        m.visitLdcInsn("kaboom");
        m.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
                "(Ljava/lang/String;)V", false);
        m.visitInsn(Opcodes.ATHROW);
        m.visitMaxs(3, 1);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Mod entrypoint with NO lifecycle method at all. */
    private static byte[] noInit() {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "q/NoInit", null, "java/lang/Object", null);
        defaultCtor(cw);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Mod entrypoint whose ONLY constructor takes an int (no no-arg ctor). */
    private static byte[] oneArgCtor() {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "q/OneArgCtor", null, "java/lang/Object", null);
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(I)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 2);
        ctor.visitEnd();
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 1);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Mod entrypoint whose static initializer THROWS. Load must not run it
     * (initialize=false); the first active use (instance creation) must then explode
     * with the clinit's real exception wrapped in ExceptionInInitializerError.
     */
    private static byte[] explodingInit() {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, "q/ExplodesInit", null, "java/lang/Object", null);
        var clinit = cw.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(Opcodes.NEW, "java/lang/RuntimeException");
        clinit.visitInsn(Opcodes.DUP);
        clinit.visitLdcInsn("boom");
        clinit.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>",
                "(Ljava/lang/String;)V", false);
        clinit.visitInsn(Opcodes.ATHROW);
        clinit.visitMaxs(3, 0);
        clinit.visitEnd();
        defaultCtor(cw);
        var m = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()V", null, null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 1);
        m.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void defaultCtor(ClassWriter cw) {
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();
    }

    // ------------------------------------------------------------------ bootstrap

    private static Path jarOf(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    private static Path hostJar(Path dir) throws IOException {
        return jarOf(dir, "host.jar", Map.of("com/host/Gadget.class", gadgetClass()));
    }

    private static Path modJar(Path dir, Map<String, byte[]> classes) throws IOException {
        return jarOf(dir, "mod.jar", classes);
    }

    private static class Universe implements AutoCloseable {
        final HostUniverse host;
        final ModLoader mod;

        Universe(HostUniverse host, ModLoader mod) {
            this.host = host;
            this.mod = mod;
        }

        @Override
        public void close() throws IOException {
            mod.close();
            host.close();
        }
    }

    private static Universe load(Path tmp, Map<String, byte[]> modClasses) throws IOException {
        HostUniverse host = new HostUniverse(hostJar(tmp), List.of());
        ModLoader mod = new ModLoader(modJar(tmp, modClasses), host);
        return new Universe(host, mod);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void modEntrypointRunsAndRecordsItRan() throws Exception {
        Universe u = load(tmp, Map.of("q/ModMain.class", modMain()));
        try (u) {
            Class<?> cls = u.mod.entrypointClass("q.ModMain");
            assertEquals("q.ModMain", cls.getName());

            // default lifecycle name "onInitialize", no args
            DriverResult r = LifecycleDriver.drive(u.mod, "q.ModMain", List.of(), List.of());
            assertTrue(r.completed());
            assertNull(r.cause());
            assertEquals(cls, r.entrypointClass());
            assertEquals("onInitialize", r.lifecycleMethod().getName());
            assertEquals(1, cls.getField("ran").getInt(null), "mod code must have RUN");
        }
    }

    @Test
    void lifecycleArgsFlowToTheMethod() throws Exception {
        Universe u = load(tmp, Map.of("q/WithArgs.class", withArgs()));
        try (u) {
            Class<?> cls = u.mod.entrypointClass("q.WithArgs");
            DriverResult r = LifecycleDriver.drive(u.mod, "q.WithArgs", "onInitialize",
                    List.of(int.class), List.of(5));
            assertTrue(r.completed());
            assertEquals(5, cls.getField("sum").getInt(null));
        }
    }

    @Test
    void modCodeInvokesHostMethodThroughParentChain() throws Exception {
        Universe u = load(tmp, Map.of("q/HostCalling.class", hostCalling()));
        try (u) {
            Class<?> cls = u.mod.entrypointClass("q.HostCalling");
            DriverResult r = LifecycleDriver.drive(u.mod, "q.HostCalling", "onInitialize", List.of(), List.of());
            assertTrue(r.completed());
            assertEquals(42, cls.getField("hostSize").getInt(null),
                    "mod code must reach the HOST class's size() method through the parent chain");
        }
    }

    // ------------------------------------------------------------------ isolation

    @Test
    void modSeesHostAndOwnButNeverAppClasses() throws Exception {
        Universe u = load(tmp, Map.of("q/ModMain.class", modMain()));
        try (u) {
            ClassLoader ml = u.mod.loader();
            assertNotNull(Class.forName("com.host.Gadget", false, ml),
                    "mod must see host-universe classes");
            assertEquals("q.ModMain", Class.forName("q.ModMain", false, ml).getName(),
                    "mod must see its own classes");
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("dev.umb.pipeline.bridge.HostUniverse", false, ml),
                    "app class must NOT be on the mod loader's parent chain");
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("dev.umb.pipeline.bridge.ModLoader", false, ml));
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(getClass().getName(), false, ml));
        }
    }

    // ------------------------------------------------------------------ named failures

    @Test
    void missingEntrypointClassIsNamed() throws Exception {
        Universe u = load(tmp, Map.of("q/ModMain.class", modMain()));
        try (u) {
            MaterializationException e = assertThrows(MaterializationException.class,
                    () -> LifecycleDriver.drive(u.mod, "q.NoSuch", "onInitialize", List.of(), List.of()));
            assertEquals(MaterializationException.Kind.MISSING_ENTRYPOINT_CLASS, e.kind());
            assertTrue(e.getMessage().contains("q.NoSuch"), () -> e.getMessage());

            // the honest load surface itself throws CNFE, never null
            assertThrows(ClassNotFoundException.class, () -> u.mod.entrypointClass("q.NoSuch"));
        }
    }

    @Test
    void missingLifecycleMethodIsNamed() throws Exception {
        Universe u = load(tmp, Map.of("q/NoInit.class", noInit()));
        try (u) {
            MaterializationException e = assertThrows(MaterializationException.class,
                    () -> LifecycleDriver.drive(u.mod, "q.NoInit", "onInitialize", List.of(), List.of()));
            assertEquals(MaterializationException.Kind.NO_LIFECYCLE_METHOD, e.kind());
            assertTrue(e.getMessage().contains("onInitialize"), () -> e.getMessage());
            assertTrue(e.getMessage().contains("q.NoInit"), () -> e.getMessage());
        }
    }

    @Test
    void missingNoArgConstructorIsNamed() throws Exception {
        Universe u = load(tmp, Map.of("q/OneArgCtor.class", oneArgCtor()));
        try (u) {
            MaterializationException e = assertThrows(MaterializationException.class,
                    () -> LifecycleDriver.drive(u.mod, "q.OneArgCtor", "onInitialize", List.of(), List.of()));
            assertEquals(MaterializationException.Kind.CONSTRUCTOR_MISMATCH, e.kind());
            assertTrue(e.getMessage().contains("q.OneArgCtor"), () -> e.getMessage());
        }
    }

    @Test
    void lifecycleReturnValueIsCaptured() throws Exception {
        try (Universe u = load(tmp, Map.of("q/Returning.class", returning()))) {
            Class<?> cls = u.mod.entrypointClass("q.Returning");
            DriverResult r = LifecycleDriver.drive(u.mod, "q.Returning", "onInitialize", List.of(), List.of());
            assertTrue(r.completed());
            assertEquals("hello", r.returnValue(),
                    "the lifecycle's return value must be capturable (publish-by-returning)");
            assertEquals(cls, r.entrypointClass());
        }

        // A void lifecycle captures null.
        try (Universe u = load(tmp, Map.of("q/ModMain.class", modMain()))) {
            DriverResult r = LifecycleDriver.drive(u.mod, "q.ModMain", "onInitialize", List.of(), List.of());
            assertTrue(r.completed());
            assertNull(r.returnValue());
        }
    }

    @Test
    void throwingEntrypointIsReportedAndUnwrapped() throws Exception {
        Universe u = load(tmp, Map.of("q/Throwing.class", throwing()));
        try (u) {
            Class<?> cls = u.mod.entrypointClass("q.Throwing");
            DriverResult r = LifecycleDriver.drive(u.mod, "q.Throwing", "onInitialize", List.of(), List.of());
            assertFalse(r.completed());
            assertEquals(cls, r.entrypointClass());
            assertEquals("onInitialize", r.lifecycleMethod().getName());
            assertNotNull(r.cause());
            assertEquals(IllegalStateException.class, r.cause().getClass(),
                    "the ENTRYPOINT's real exception, not the reflection wrapper");
            assertEquals("kaboom", r.cause().getMessage());

            // driveThrowing raises the named kind with the same unwrapped cause
            MaterializationException e = assertThrows(MaterializationException.class,
                    () -> LifecycleDriver.driveThrowing(u.mod, "q.Throwing", "onInitialize", List.of(), List.of()));
            assertEquals(MaterializationException.Kind.ENTRYPOINT_THREW, e.kind());
            assertEquals(IllegalStateException.class, e.getCause().getClass());
            assertEquals("kaboom", e.getCause().getMessage());
        }
    }

    // ------------------------------------------------------------------ load discipline

    @Test
    void staticInitializersDoNotRunAtLoad() throws Exception {
        Universe u = load(tmp, Map.of("q/ExplodesInit.class", explodingInit()));
        try (u) {
            // initialize=false: a clinit that throws must NOT fire during load.
            Class<?> cls = u.mod.entrypointClass("q.ExplodesInit");
            assertEquals("q.ExplodesInit", cls.getName());

            // First active use (instance creation for the drive) runs <clinit> — it throws,
            // and the JVM wraps the clinit's REAL exception in ExceptionInInitializerError.
            ExceptionInInitializerError err = assertThrows(ExceptionInInitializerError.class,
                    () -> LifecycleDriver.drive(u.mod, "q.ExplodesInit", "onInitialize", List.of(), List.of()));
            assertTrue(err.getCause() instanceof RuntimeException);
            assertEquals("boom", err.getCause().getMessage());
        }
    }

    // ------------------------------------------------------------------ determinism

    @Test
    void sameInputsProduceSameFailureOrdering() throws Exception {
        Universe u = load(tmp, Map.of("q/NoInit.class", noInit()));
        try (u) {
            MaterializationException first = assertThrows(MaterializationException.class,
                    () -> LifecycleDriver.drive(u.mod, "q.NoInit", "onStart", List.of(), List.of()));
            MaterializationException again = assertThrows(MaterializationException.class,
                    () -> LifecycleDriver.drive(u.mod, "q.NoInit", "onStart", List.of(), List.of()));
            assertEquals(first.getMessage(), again.getMessage());
            assertTrue(first.getMessage().contains("onStart"));
        }
    }
}