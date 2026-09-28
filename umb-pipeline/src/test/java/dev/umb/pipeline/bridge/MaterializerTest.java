package dev.umb.pipeline.bridge;

import com.legacy.GadgetLike;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Materializer (M7 bridge v0): a legacy interface proxy is backed by a REAL host-class
 * instance, proxied calls return host-computed values, and every failure mode lands as a
 * named {@link MaterializationException} saying exactly what was absent — no silent fakes.
 * The synthetic host (com.host.Gadget) is compiled in-test with ASM, mirroring the
 * SmokeLoaderTest idiom; the legacy interface is a plain source interface.
 */
class MaterializerTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ host fixture

    /**
     * com.host.Gadget(int size, String name) with two HOST-COMPUTED accessors:
     * size()I returns storedSize+1, name()Ljava/lang/String; returns storedName.toUpperCase().
     * The +1 / toUpperCase deltas prove the returned values come from HOST code, not the
     * materializer echoing constructor arguments.
     */
    private static byte[] gadgetClass() {
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

    private HostUniverse gadgetUniverse() throws IOException {
        Path hostJar = tmp.resolve("host.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(hostJar))) {
            out.putNextEntry(new JarEntry("com/host/Gadget.class"));
            out.write(gadgetClass());
            out.closeEntry();
        }
        return new HostUniverse(hostJar, List.of());
    }

    // ------------------------------------------------------------------ binding helpers

    private static Map<MethodSignature, HostCall> bindAll(Class<?> hostClass) throws Exception {
        Map<MethodSignature, HostCall> m = new LinkedHashMap<>();
        m.put(MethodSignature.of("size"), direct(hostClass, "size"));
        m.put(MethodSignature.of("name"), direct(hostClass, "name"));
        return m;
    }

    /** A HostCall that invokes the named NO-ARG host method reflectively. */
    private static HostCall direct(Class<?> hostClass, String methodName) throws Exception {
        Method target = hostClass.getMethod(methodName);
        return (host, args) -> target.invoke(host, args);
    }

    private static Materialized materialize(HostUniverse u, Class<?> hostClass, int size, String name,
                                            Map<MethodSignature, HostCall> bindings) throws Exception {
        return Materializer.materialize(u, "com.host.Gadget",
                List.of(int.class, String.class), List.of(size, name), GadgetLike.class, bindings);
    }

    // ------------------------------------------------------------------ happy path

    @Test
    void materializedProxyIsNativeAndReturnsHostComputedValues() throws Exception {
        try (HostUniverse u = gadgetUniverse()) {
            Class<?> hostClass = u.hostClass("com.host.Gadget");
            Materialized m = materialize(u, hostClass, 41, "gizmo", bindAll(hostClass));

            assertTrue(m.proxy() instanceof GadgetLike, "proxy must implement the legacy interface");
            assertTrue(hostClass.isInstance(m.hostInstance()),
                    "underlying must be a REAL instance of the host class, not a fake");
            assertEquals("com.host.Gadget", m.hostClass().getName());

            GadgetLike g = (GadgetLike) m.proxy();
            assertEquals(42, g.size(), "host-computed: ctor arg 41 + host's +1");   // host code ran
            assertEquals("GIZMO", g.name(), "host-computed: toUpperCase happens in the host");
        }
    }

    @Test
    void objectMethodsBridgeToTheHostInstanceIdentity() throws Exception {
        try (HostUniverse u = gadgetUniverse()) {
            Class<?> hostClass = u.hostClass("com.host.Gadget");
            Materialized m = materialize(u, hostClass, 3, "bob", bindAll(hostClass));
            Object proxy = m.proxy();

            String t = proxy.toString();
            assertTrue(t.contains("com.legacy.GadgetLike"), () -> t);
            assertTrue(t.contains("com.host.Gadget"), () -> t);
            assertEquals(System.identityHashCode(m.hostInstance()), proxy.hashCode());
            assertEquals(proxy, proxy);            // identity self-equality
            Materialized other = materialize(u, hostClass, 4, "other", bindAll(hostClass));
            assertNotEquals(other.proxy(), proxy, "different host instances -> not equal");
        }
    }

    // ------------------------------------------------------------------ D4 failures

    @Test
    void invokingUnboundLegacyMethodThrowsNamingTheMethod() throws Exception {
        try (HostUniverse u = gadgetUniverse()) {
            Class<?> hostClass = u.hostClass("com.host.Gadget");
            // Only "size" is bound; the proxy itself still materializes fine.
            Map<MethodSignature, HostCall> partial = new LinkedHashMap<>();
            partial.put(MethodSignature.of("size"), direct(hostClass, "size"));
            Materialized m = materialize(u, hostClass, 7, "x", partial);

            MaterializationException e = assertThrows(MaterializationException.class,
                    () -> ((GadgetLike) m.proxy()).name());
            assertEquals(MaterializationException.Kind.UNBOUND_LEGACY_METHOD, e.kind());
            assertTrue(e.getMessage().contains("name"), () -> e.getMessage());
        }
    }

    @Test
    void missingHostClassThrowsNamingTheClass() throws Exception {
        try (HostUniverse u = gadgetUniverse()) {
            MaterializationException e = assertThrows(MaterializationException.class,
                    () -> Materializer.materialize(u, "com.host.NoSuch", List.of(),
                            List.of(), GadgetLike.class, Map.of()));
            assertEquals(MaterializationException.Kind.MISSING_HOST_CLASS, e.kind());
            assertTrue(e.getMessage().contains("com.host.NoSuch"), () -> e.getMessage());
        }
    }

    @Test
    void missingHostMethodBindingTargetThrowsNamingTheMethod() throws Exception {
        try (HostUniverse u = gadgetUniverse()) {
            Class<?> hostClass = u.hostClass("com.host.Gadget");
            Map<MethodSignature, HostCall> bindings = new LinkedHashMap<>();
            bindings.put(MethodSignature.of("size"), direct(hostClass, "size")); // valid target
            bindings.put(MethodSignature.of("frobnicate"), (host, args) -> null); // no host method

            MaterializationException e = assertThrows(MaterializationException.class,
                    () -> materialize(u, hostClass, 1, "y", bindings));
            assertEquals(MaterializationException.Kind.MISSING_HOST_METHOD, e.kind());
            assertTrue(e.getMessage().contains("frobnicate"), () -> e.getMessage());
        }
    }

    @Test
    void constructorArgMismatchThrowsNamingClassAndSignature() throws Exception {
        try (HostUniverse u = gadgetUniverse()) {
            MaterializationException e = assertThrows(MaterializationException.class,
                    () -> Materializer.materialize(u, "com.host.Gadget", List.of(String.class),
                            List.of("only-a-string"), GadgetLike.class, Map.of()));
            assertEquals(MaterializationException.Kind.CONSTRUCTOR_MISMATCH, e.kind());
            assertTrue(e.getMessage().contains("com.host.Gadget"), () -> e.getMessage());
            assertTrue(e.getMessage().contains("java.lang.String"), () -> e.getMessage());
        }
    }

    @Test
    void constructorArityMismatchThrows() throws Exception {
        try (HostUniverse u = gadgetUniverse()) {
            MaterializationException e = assertThrows(MaterializationException.class,
                    () -> Materializer.materialize(u, "com.host.Gadget",
                            List.of(int.class), List.of(1, "two"), GadgetLike.class, Map.of()));
            assertEquals(MaterializationException.Kind.CONSTRUCTOR_MISMATCH, e.kind());
        }
    }

    // ------------------------------------------------------------------ determinism

    @Test
    void sameInputsProduceSameFailureOrdering() throws Exception {
        try (HostUniverse u = gadgetUniverse()) {
            Class<?> hostClass = u.hostClass("com.host.Gadget");
            // Two binding keys, BOTH missing on the host. Insertion order decides which one
            // is reported first; a LinkedHashMap must yield the same name on every run.
            Map<MethodSignature, HostCall> bindings = new LinkedHashMap<>();
            bindings.put(MethodSignature.of("aaa"), (host, args) -> null);
            bindings.put(MethodSignature.of("bbb"), (host, args) -> null);

            MaterializationException first = assertThrows(MaterializationException.class,
                    () -> materialize(u, hostClass, 2, "d", bindings));
            MaterializationException again = assertThrows(MaterializationException.class,
                    () -> materialize(u, hostClass, 2, "d", bindings));

            assertEquals(first.getMessage(), again.getMessage());
            assertTrue(first.getMessage().contains("aaa"), () -> first.getMessage());
            assertFalse(first.getMessage().contains("bbb"), () -> first.getMessage());
        }
    }
}