package dev.umb.pipeline.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InteropRegistry (M7 bridge interop): one authoritative world. Two mods in SEPARATE
 * {@link ModLoader}s (each defining its own copy of the legacy interface — same or
 * different names) exchange an object by identifier, and every side holds a VIEW onto the
 * SAME host instance: resolving twice yields the same hostInstance, views through
 * different consumer interfaces compare equal to the materialized source (the
 * Materializer handler's host-identity equals), {@code view()} is cached per
 * (hostInstance, consumerInterface), and the failure modes are named — NOT_PUBLISHED on
 * consume-before-publish, MISSING_HOST_METHOD from the eager view validation, and
 * UNBOUND_LEGACY_METHOD at call time for unmatched interface methods.
 */
class InteropRegistryTest {

    @TempDir
    Path tmp;

    // ------------------------------------------------------------------ fixtures

    /** An abstract legacy interface declaring the given no-arg methods. */
    private static byte[] interfaceClass(String internalName, String... methods) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                internalName, null, "java/lang/Object", null);
        for (String m : methods) {
            String desc = switch (m) {
                case "size", "extra", "value", "tag" -> "()I";
                case "name" -> "()Ljava/lang/String;";
                default -> throw new IllegalArgumentException("no descriptor for " + m);
            };
            cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, m, desc, null, null).visitEnd();
        }
        cw.visitEnd();
        return cw.toByteArray();
    }

    /**
     * Host class with VALUE-BASED equals/hashCode on {@code value} and a constructor-set
     * {@code tag} NOT in equals — grained like real net.minecraft.core.Vec3i, whose
     * equals compares x/y/z and would conflate two DISTINCT instances with equal values.
     * COMPUTE_FRAMES because equals carries branches: version-52 code with branch targets
     * needs a StackMapTable, which ASM derives from the visited instructions. Maxs are
     * computed from the code (visitMaxs args are ignored), so pass 0.
     */
    private static byte[] pairClass() {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, "com/host/Pair", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "value", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "tag", "I", null, null).visitEnd();

        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(II)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ILOAD, 1);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, "com/host/Pair", "value", "I");
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitVarInsn(Opcodes.ILOAD, 2);
        ctor.visitFieldInsn(Opcodes.PUTFIELD, "com/host/Pair", "tag", "I");
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        // equals(Object): identity short-circuit, instanceof, then compare value ONLY.
        var eq = cw.visitMethod(Opcodes.ACC_PUBLIC, "equals", "(Ljava/lang/Object;)Z", null, null);
        eq.visitCode();
        eq.visitVarInsn(Opcodes.ALOAD, 0);
        eq.visitVarInsn(Opcodes.ALOAD, 1);
        var same = new Label();
        eq.visitJumpInsn(Opcodes.IF_ACMPEQ, same);
        eq.visitVarInsn(Opcodes.ALOAD, 1);
        eq.visitTypeInsn(Opcodes.INSTANCEOF, "com/host/Pair");
        var notPair = new Label();
        eq.visitJumpInsn(Opcodes.IFEQ, notPair);
        eq.visitVarInsn(Opcodes.ALOAD, 1);
        eq.visitTypeInsn(Opcodes.CHECKCAST, "com/host/Pair");
        eq.visitVarInsn(Opcodes.ASTORE, 2);
        eq.visitVarInsn(Opcodes.ALOAD, 0);
        eq.visitFieldInsn(Opcodes.GETFIELD, "com/host/Pair", "value", "I");
        eq.visitVarInsn(Opcodes.ALOAD, 2);
        eq.visitFieldInsn(Opcodes.GETFIELD, "com/host/Pair", "value", "I");
        eq.visitJumpInsn(Opcodes.IF_ICMPNE, notPair);
        eq.visitLabel(same);
        eq.visitInsn(Opcodes.ICONST_1);
        eq.visitInsn(Opcodes.IRETURN);
        eq.visitLabel(notPair);
        eq.visitInsn(Opcodes.ICONST_0);
        eq.visitInsn(Opcodes.IRETURN);
        eq.visitMaxs(0, 0);
        eq.visitEnd();

        // hashCode() mirrors equals: equal pairs hash alike.
        var hc = cw.visitMethod(Opcodes.ACC_PUBLIC, "hashCode", "()I", null, null);
        hc.visitCode();
        hc.visitVarInsn(Opcodes.ALOAD, 0);
        hc.visitFieldInsn(Opcodes.GETFIELD, "com/host/Pair", "value", "I");
        hc.visitInsn(Opcodes.IRETURN);
        hc.visitMaxs(0, 0);
        hc.visitEnd();

        var value = cw.visitMethod(Opcodes.ACC_PUBLIC, "value", "()I", null, null);
        value.visitCode();
        value.visitVarInsn(Opcodes.ALOAD, 0);
        value.visitFieldInsn(Opcodes.GETFIELD, "com/host/Pair", "value", "I");
        value.visitInsn(Opcodes.IRETURN);
        value.visitMaxs(0, 0);
        value.visitEnd();

        var tag = cw.visitMethod(Opcodes.ACC_PUBLIC, "tag", "()I", null, null);
        tag.visitCode();
        tag.visitVarInsn(Opcodes.ALOAD, 0);
        tag.visitFieldInsn(Opcodes.GETFIELD, "com/host/Pair", "tag", "I");
        tag.visitInsn(Opcodes.IRETURN);
        tag.visitMaxs(0, 0);
        tag.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static Path jar(Path dir, String name, Map<String, byte[]> entries) throws IOException {
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

    private static Materialized gadgetMaterialized(HostUniverse u, Class<?> legacyInterface) throws Exception {
        return Materializer.materialize(u, "com.host.Gadget",
                List.of(int.class, String.class), List.of(41, "gizmo"),
                legacyInterface, bindAll(u.hostClass("com.host.Gadget")));
    }

    /**
     * Host jar (com.host.Gadget) + a mod jar with the legacy interfaces, loaded through
     * TWO SEPARATE ModLoaders so each defines its OWN copy of every interface.
     */
    private static class World implements AutoCloseable {
        final HostUniverse host;
        final ModLoader a;
        final ModLoader b;

        World(HostUniverse host, ModLoader a, ModLoader b) {
            this.host = host;
            this.a = a;
            this.b = b;
        }

        @Override
        public void close() throws IOException {
            b.close();
            a.close();
            host.close();
        }
    }

    private World world() throws IOException {
        Path hostJar = jar(tmp, "host.jar", Map.of(
                "com/host/Gadget.class", ModLoaderTest.gadgetClass(),
                "com/host/Pair.class", pairClass()));
        Path ifaceJar = jar(tmp, "ifaces.jar", Map.of(
                "com/legacy/PosLike.class", interfaceClass("com/legacy/PosLike", "size", "name"),
                "com/legacy/ConsumedLike.class",
                        interfaceClass("com/legacy/ConsumedLike", "size", "name"),
                "com/legacy/RichLike.class",
                        interfaceClass("com/legacy/RichLike", "size", "name", "extra"),
                "com/legacy/TagLike.class",
                        interfaceClass("com/legacy/TagLike", "value", "tag")));
        HostUniverse host = new HostUniverse(hostJar, List.of());
        ModLoader a = new ModLoader(ifaceJar, host);
        ModLoader b = new ModLoader(ifaceJar, host);
        return new World(host, a, b);
    }

    // ------------------------------------------------------------------ identity

    @Test
    void resolveTwiceYieldsTheSameHostInstance() throws Exception {
        try (World w = world()) {
            Class<?> posLike = w.a.entrypointClass("com.legacy.PosLike");
            Materialized m = gadgetMaterialized(w.host, posLike);
            InteropRegistry registry = new InteropRegistry();
            registry.publish("gadget", m);

            assertSame(m.hostInstance(), registry.resolve("gadget").hostInstance());
            assertSame(m.hostInstance(), registry.resolve("gadget").hostInstance());
            assertSame(m.proxy(), registry.resolve("gadget").proxy(),
                    "resolve is the authoritative Materialized, not a copy");
        }
    }

    @Test
    void viewsThroughDifferentConsumerInterfacesShareOneHostInstance() throws Exception {
        try (World w = world()) {
            Class<?> posLikeA = w.a.entrypointClass("com.legacy.PosLike");
            Class<?> consumedB = w.b.entrypointClass("com.legacy.ConsumedLike");
            Class<?> hostClass = w.host.hostClass("com.host.Gadget");
            Materialized m = gadgetMaterialized(w.host, posLikeA);
            InteropRegistry registry = new InteropRegistry();
            registry.publish("gadget", m);

            Object viewB = registry.view(m, consumedB, bindAll(hostClass));

            // The view is instanceof the consumer's OWN interface...
            assertTrue(consumedB.isInstance(viewB));
            assertTrue(posLikeA.isInstance(m.proxy()));
            // ... dispatches HOST-COMPUTED values (ctor 41 + host's +1, toUpperCase)...
            assertEquals(42, consumedB.getMethod("size").invoke(viewB));
            assertEquals("GIZMO", consumedB.getMethod("name").invoke(viewB));
            // ... and IS the provider's object: equality and hashCode live at the host.
            assertEquals(m.proxy(), viewB, "view and materialized source are the same object");
            assertEquals(m.proxy().hashCode(), viewB.hashCode());
        }
    }

    @Test
    void sameNameInterfaceInDifferentLoadersMatchesByNameAndDescriptor() throws Exception {
        try (World w = world()) {
            // Same binary name com.legacy.PosLike, ONE copy per loader -> different Class.
            Class<?> posLikeA = w.a.entrypointClass("com.legacy.PosLike");
            Class<?> posLikeB = w.b.entrypointClass("com.legacy.PosLike");
            assertNotEquals(posLikeA, posLikeB, "loaders differ, so the Class objects must");
            assertEquals("com.legacy.PosLike", posLikeA.getName());

            Materialized m = gadgetMaterialized(w.host, posLikeA);
            InteropRegistry registry = new InteropRegistry();
            registry.publish("gadget", m);

            Object viewA = registry.view(m, posLikeA, bindAll(w.host.hostClass("com.host.Gadget")));
            Object viewB = registry.view(m, posLikeB, bindAll(w.host.hostClass("com.host.Gadget")));
            assertNotSame(viewA, viewB, "different proxies for different interface Classes");
            assertEquals(viewA, viewB, "but the SAME host instance underneath");
            assertEquals(42, posLikeB.getMethod("size").invoke(viewB));
            assertSame(m.hostInstance(), registry.resolve("gadget").hostInstance());
        }
    }

    @Test
    void viewIsCachedPerConsumerInterface() throws Exception {
        try (World w = world()) {
            Class<?> consumedB = w.b.entrypointClass("com.legacy.ConsumedLike");
            Materialized m = gadgetMaterialized(w.host, consumedB);
            InteropRegistry registry = new InteropRegistry();
            registry.publish("gadget", m);

            Object first = registry.view(m, consumedB, bindAll(w.host.hostClass("com.host.Gadget")));
            assertSame(first, registry.view(m, consumedB, bindAll(w.host.hostClass("com.host.Gadget"))),
                    "one authoritative view per (hostInstance, consumerInterface)");
        }
    }

    @Test
    void viewsStayPinnedToTheirHostInstanceForValueEqualsHosts() throws Exception {
        try (World w = world()) {
            Class<?> tagLike = w.b.entrypointClass("com.legacy.TagLike");
            Class<?> pairClass = w.host.hostClass("com.host.Pair");
            Map<MethodSignature, HostCall> bindings = new LinkedHashMap<>();
            bindings.put(MethodSignature.of("value"), direct(pairClass, "value"));
            bindings.put(MethodSignature.of("tag"), direct(pairClass, "tag"));

            // Pair's equals compares ONLY value, so these two DISTINCT host instances
            // are equals() to each other. The view cache keys by host identity (`==`),
            // not equals — it must never conflate them (a WeakHashMap would, and the
            // second view would dispatch to the FIRST instance).
            Materialized m1 = Materializer.materialize(w.host, "com.host.Pair",
                    List.of(int.class, int.class), List.of(10, 10), tagLike, bindings);
            Materialized m2 = Materializer.materialize(w.host, "com.host.Pair",
                    List.of(int.class, int.class), List.of(10, 20), tagLike, bindings);

            InteropRegistry registry = new InteropRegistry();
            Object v1 = registry.view(m1, tagLike, bindings);
            Object v2 = registry.view(m2, tagLike, bindings);

            assertNotSame(v1, v2, "equal-but-distinct host instances must not share a cache entry");
            assertEquals(10, (int) tagLike.getMethod("value").invoke(v2));
            assertEquals(20, (int) tagLike.getMethod("tag").invoke(v2),
                    "second view must dispatch to ITS OWN host instance, not the first");
        }
    }

    @Test
    void viewsOfDifferentHostInstancesAreNotEqual() throws Exception {
        try (World w = world()) {
            Class<?> consumedB = w.b.entrypointClass("com.legacy.ConsumedLike");
            Class<?> hostClass = w.host.hostClass("com.host.Gadget");
            Materialized m1 = gadgetMaterialized(w.host, consumedB);
            Materialized m2 = Materializer.materialize(w.host, "com.host.Gadget",
                    List.of(int.class, String.class), List.of(5, "other"), consumedB,
                    bindAll(hostClass));
            InteropRegistry registry = new InteropRegistry();

            Object v1 = registry.view(m1, consumedB, bindAll(hostClass));
            Object v2 = registry.view(m2, consumedB, bindAll(hostClass));
            assertNotEquals(v1, v2, "two host instances are two objects, never one world");
        }
    }

    // ------------------------------------------------------------------ D4 failures

    @Test
    void consumeBeforePublishIsNamed() throws Exception {
        try (World w = world()) {
            InteropRegistry registry = new InteropRegistry();
            MaterializationException e = assertThrows(MaterializationException.class,
                    () -> registry.resolve("never-published"));
            assertEquals(MaterializationException.Kind.NOT_PUBLISHED, e.kind());
            assertTrue(e.getMessage().contains("never-published"), () -> e.getMessage());
        }
    }

    @Test
    void eagerViewValidationNamesMissingHostMethodDeterministically() throws Exception {
        try (World w = world()) {
            Class<?> consumedB = w.b.entrypointClass("com.legacy.ConsumedLike");
            Class<?> hostClass = w.host.hostClass("com.host.Gadget");
            Materialized m = gadgetMaterialized(w.host, consumedB);
            InteropRegistry registry = new InteropRegistry();
            registry.publish("gadget", m);

            Map<MethodSignature, HostCall> bad = new LinkedHashMap<>();
            bad.put(MethodSignature.of("aaa"), (host, args) -> null);
            bad.put(MethodSignature.of("bbb"), (host, args) -> null);
            MaterializationException first = assertThrows(MaterializationException.class,
                    () -> registry.view(m, consumedB, bad));
            MaterializationException again = assertThrows(MaterializationException.class,
                    () -> registry.view(m, consumedB, bad));
            assertEquals(MaterializationException.Kind.MISSING_HOST_METHOD, first.kind());
            assertTrue(first.getMessage().contains("aaa"), () -> first.getMessage());
            assertFalse(first.getMessage().contains("bbb"), () -> first.getMessage());
            assertEquals(first.getMessage(), again.getMessage());

            // Nothing partial was cached: a valid view of the same interface still works.
            assertNotNull(registry.view(m, consumedB, bindAll(hostClass)));
        }
    }

    @Test
    void unmatchedConsumerMethodsStayUnboundAtCallTime() throws Exception {
        try (World w = world()) {
            Class<?> richB = w.b.entrypointClass("com.legacy.RichLike");
            Class<?> hostClass = w.host.hostClass("com.host.Gadget");
            Materialized m = gadgetMaterialized(w.host, richB);
            InteropRegistry registry = new InteropRegistry();

            // richB declares size/name/extra; bindings cover only size/name. The view
            // CREATES fine (only the HOST side is validated eagerly), and extra() is
            // UNBOUND at call time, named exactly. The test reaches the proxy through
            // reflection, so the handler's MaterializationException sits under the
            // InvocationTargetException that Method.invoke wraps ANY callee throw in —
            // a mod calling the method directly would see it raw.
            Object view = registry.view(m, richB, bindAll(hostClass));
            assertEquals(42, richB.getMethod("size").invoke(view));
            InvocationTargetException ite = assertThrows(InvocationTargetException.class,
                    () -> richB.getMethod("extra").invoke(view));
            Throwable cause = ite.getCause();
            assertTrue(cause instanceof MaterializationException,
                    () -> "expected MaterializationException, was: " + cause);
            MaterializationException e = (MaterializationException) cause;
            assertEquals(MaterializationException.Kind.UNBOUND_LEGACY_METHOD, e.kind());
            assertTrue(e.getMessage().contains("extra"), () -> e.getMessage());
        }
    }
}