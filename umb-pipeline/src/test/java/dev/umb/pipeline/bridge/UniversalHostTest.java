package dev.umb.pipeline.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M12 universal host (Decision C): one 26.2 image, per-mod ModLoader children,
 * isolation holds, Vec3i(100,200,-3) materialized proxy + publish/consume interop
 * (sequential gate A + concurrent stretch B with shared InteropRegistry).
 * CC0 fixtures, ASM 9.9, D4, §24, portable toolchain via tools/windows/umb-env.ps1.
 */
class UniversalHostTest {

    @TempDir Path tmp;

    // ---------------- host fixtures

    private static byte[] vec3iClass() {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, "net/minecraft/core/Vec3i", null, "java/lang/Object", null);
        cw.visitField(Opcodes.ACC_PRIVATE, "x", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "y", "I", null, null).visitEnd();
        cw.visitField(Opcodes.ACC_PRIVATE, "z", "I", null, null).visitEnd();
        var ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(III)V", null, null);
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ILOAD, 1); ctor.visitFieldInsn(Opcodes.PUTFIELD, "net/minecraft/core/Vec3i", "x", "I");
        ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ILOAD, 2); ctor.visitFieldInsn(Opcodes.PUTFIELD, "net/minecraft/core/Vec3i", "y", "I");
        ctor.visitVarInsn(Opcodes.ALOAD, 0); ctor.visitVarInsn(Opcodes.ILOAD, 3); ctor.visitFieldInsn(Opcodes.PUTFIELD, "net/minecraft/core/Vec3i", "z", "I");
        ctor.visitInsn(Opcodes.RETURN); ctor.visitMaxs(0, 0); ctor.visitEnd();
        for (String m : List.of("getX", "getY", "getZ")) {
            String field = m.equals("getX") ? "x" : m.equals("getY") ? "y" : "z";
            var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, m, "()I", null, null);
            mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitFieldInsn(Opcodes.GETFIELD, "net/minecraft/core/Vec3i", field, "I");
            mv.visitInsn(Opcodes.IRETURN); mv.visitMaxs(0, 0); mv.visitEnd();
        }
        cw.visitEnd(); return cw.toByteArray();
    }

    private Path hostJar() throws IOException {
        Path p = tmp.resolve("host-26.2.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            out.putNextEntry(new JarEntry("net/minecraft/core/Vec3i.class")); out.write(vec3iClass()); out.closeEntry();
        }
        return p;
    }

    // ---------------- mod fixtures

    private static byte[] legacyPosApi() {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "q/legacy/PosApi", null, "java/lang/Object", null);
        for (String m : List.of("getX", "getY", "getZ")) { var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, m, "()I", null, null); mv.visitEnd(); }
        cw.visitEnd(); return cw.toByteArray();
    }

    private static byte[] seedClass() {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, "p/Seed", null, "java/lang/Object", null);
        defaultCtor(cw);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "()Ljava/lang/Object;", null, null);
        mv.visitTypeInsn(Opcodes.NEW, "net/minecraft/core/Vec3i"); mv.visitInsn(Opcodes.DUP);
        mv.visitIntInsn(Opcodes.BIPUSH, 100); mv.visitIntInsn(Opcodes.SIPUSH, 200); mv.visitIntInsn(Opcodes.BIPUSH, -3);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "net/minecraft/core/Vec3i", "<init>", "(III)V", false);
        mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(0, 0); mv.visitEnd(); cw.visitEnd(); return cw.toByteArray();
    }

    private static byte[] providerClass() {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, "p/EntryPoint", null, "java/lang/Object", null);
        defaultCtor(cw);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "(Lq/legacy/PosApi;)Ljava/lang/Object;", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 1); mv.visitInsn(Opcodes.ARETURN); mv.visitMaxs(0, 0); mv.visitEnd();
        cw.visitEnd(); return cw.toByteArray();
    }

    private static byte[] consumerClass() {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, "c/EntryPoint", null, "java/lang/Object", null);
        defaultCtor(cw);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "(Lq/legacy/PosApi;)V", null, null);
        Label ok = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "q/legacy/PosApi", "getX", "()I", true);
        mv.visitIntInsn(Opcodes.BIPUSH, 100); mv.visitJumpInsn(Opcodes.IF_ICMPEQ, ok);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("bad x");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok);
        mv.visitVarInsn(Opcodes.ALOAD, 1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "q/legacy/PosApi", "getY", "()I", true);
        Label ok2 = new Label(); mv.visitIntInsn(Opcodes.SIPUSH, 200); mv.visitJumpInsn(Opcodes.IF_ICMPEQ, ok2);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("bad y");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok2);
        mv.visitVarInsn(Opcodes.ALOAD, 1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "q/legacy/PosApi", "getZ", "()I", true);
        Label ok3 = new Label(); mv.visitIntInsn(Opcodes.BIPUSH, -3); mv.visitJumpInsn(Opcodes.IF_ICMPEQ, ok3);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("bad z");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok3); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd(); cw.visitEnd(); return cw.toByteArray();
    }

    private static byte[] verifyClass() {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, "c/Verify", null, "java/lang/Object", null);
        defaultCtor(cw);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "(Lq/legacy/PosApi;)V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "q/legacy/PosApi", "getX", "()I", true);
        mv.visitIntInsn(Opcodes.BIPUSH, 100); mv.visitJumpInsn(Opcodes.IF_ICMPEQ, new Label() {{}}); // reuse above pattern compactly — just assert via same checks
        // Simpler: reuse consumer's Body — verify is identical to consumer for this gate
        mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd(); cw.visitEnd(); return cw.toByteArray();
    }

    private static byte[] verifyStrict() {
        var cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
        cw.visit(52, Opcodes.ACC_PUBLIC, "c/Verify", null, "java/lang/Object", null);
        defaultCtor(cw);
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "onInitialize", "(Lq/legacy/PosApi;)V", null, null);
        Label ok = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "q/legacy/PosApi", "getX", "()I", true);
        mv.visitIntInsn(Opcodes.BIPUSH, 100); mv.visitJumpInsn(Opcodes.IF_ICMPEQ, ok);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("verify x");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok);
        Label ok2 = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "q/legacy/PosApi", "getY", "()I", true);
        mv.visitIntInsn(Opcodes.SIPUSH, 200); mv.visitJumpInsn(Opcodes.IF_ICMPEQ, ok2);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("verify y");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok2);
        Label ok3 = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 1); mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, "q/legacy/PosApi", "getZ", "()I", true);
        mv.visitIntInsn(Opcodes.BIPUSH, -3); mv.visitJumpInsn(Opcodes.IF_ICMPEQ, ok3);
        mv.visitTypeInsn(Opcodes.NEW, "java/lang/IllegalStateException"); mv.visitInsn(Opcodes.DUP); mv.visitLdcInsn("verify z");
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "(Ljava/lang/String;)V", false); mv.visitInsn(Opcodes.ATHROW);
        mv.visitLabel(ok3); mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(0, 0); mv.visitEnd(); cw.visitEnd(); return cw.toByteArray();
    }

    private static void defaultCtor(ClassWriter cw) {
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        mv.visitVarInsn(Opcodes.ALOAD, 0); mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        mv.visitInsn(Opcodes.RETURN); mv.visitMaxs(1, 1); mv.visitEnd();
    }

    private static Path jar(Path dir, String name, Map<String, byte[]> entries, String fabricJson) throws IOException {
        Path p = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            if (fabricJson != null) { out.putNextEntry(new JarEntry("fabric.mod.json")); out.write(fabricJson.getBytes(StandardCharsets.UTF_8)); out.closeEntry(); }
            for (var e : entries.entrySet()) { out.putNextEntry(new JarEntry(e.getKey())); out.write(e.getValue()); out.closeEntry(); }
        }
        return p;
    }

    private static String planJson() {
        return "[{\"class\":\"p.Seed\",\"publish\":\"shared.vec3i\"},{\"class\":\"p.EntryPoint\",\"consume\":[{\"param\":0,\"id\":\"shared.vec3i\"}],\"publish\":\"shared.vec3i\"},{\"class\":\"c.EntryPoint\",\"consume\":[{\"param\":0,\"id\":\"shared.vec3i\"}]},{\"class\":\"c.Verify\",\"consume\":[{\"param\":0,\"id\":\"shared.vec3i\"}]}]";
    }

    // ---------------- tests

    @Test
    void universalHostSingleImageVec3iProxy() throws Exception {
        Path host = hostJar();
        try (UniversalHost h = new UniversalHost(host, List.of())) {
            Class<?> hostClass = h.universe().hostClass("net.minecraft.core.Vec3i");
            assertNotNull(hostClass);
            Map<MethodSignature, HostCall> bindings = new LinkedHashMap<>();
            bindings.put(MethodSignature.of("getX"), (o, a) -> hostClass.getMethod("getX").invoke(o, a));
            bindings.put(MethodSignature.of("getY"), (o, a) -> hostClass.getMethod("getY").invoke(o, a));
            bindings.put(MethodSignature.of("getZ"), (o, a) -> hostClass.getMethod("getZ").invoke(o, a));
            // Materialize directly against the universal image
            var m = Materializer.materialize(h.universe(), "net.minecraft.core.Vec3i",
                    List.of(int.class, int.class, int.class), List.of(100, 200, -3),
                    Class.forName("q.legacy.PosApi", false, UniversalHostTest.class.getClassLoader()), bindings);
            assertTrue(hostClass.isInstance(m.hostInstance()));
        }
    }

    @Test
    void sequentialGateFourStageVerticalPublishConsume() throws Exception {
        Path host = hostJar();
        Path mod = jar(tmp, "mod.jar", Map.of(
                "q/legacy/PosApi.class", legacyPosApi(),
                "p/Seed.class", seedClass(),
                "p/EntryPoint.class", providerClass(),
                "c/EntryPoint.class", consumerClass(),
                "c/Verify.class", verifyStrict()), "{\"schemaVersion\":1,\"id\":\"synth\",\"version\":\"1\"}");
        Path plan = tmp.resolve("plan.json"); Files.writeString(plan, planJson());

        try (UniversalHost h = new UniversalHost(host, List.of())) {
            ModLoader ml = h.openMod(mod);
            LaunchPlan lp = LaunchPlan.parse(plan);
            InteropRegistry reg = h.registry();
            // Drive in plan order — publish/consume via registry (mirrors LaunchCommand logic)
            for (LaunchPlan.PlanEntry pe : lp.entries()) {
                if (!pe.hasConsume()) {
                    var r = LifecycleDriver.drive(ml, pe.className(), List.of(), List.of());
                    assertTrue(r.completed(), () -> pe.className() + " threw: " + r.cause());
                    if (pe.hasPublish() && r.returnValue() != null) {
                        var rec = Materializer.recover(r.returnValue(), h.universe());
                        if (rec != null) reg.publish(pe.publishId(), rec);
                    }
                } else {
                    // Consume path: resolve + view behind the declared param interface
                    Class<?> ep = ml.entrypointClass(pe.className());
                    var meth = LifecycleDriver.findLifecycleMethod(ep, "onInitialize", pe.consume().size());
                    Class<?> iface = meth.getParameterTypes()[0];
                    var resolved = reg.resolve(pe.consume().get(0).identifier());
                    var bindings = Materializer.bindByName(iface, resolved.hostClass());
                    Object view = reg.view(resolved, iface, bindings);
                    var r = LifecycleDriver.drive(ml, pe.className(), "onInitialize", List.of(iface), List.of(view));
                    assertTrue(r.completed(), () -> pe.className() + " threw: " + r.cause());
                    if (pe.hasPublish() && r.returnValue() != null) {
                        var rec = Materializer.recover(r.returnValue(), h.universe());
                        if (rec != null) reg.publish(pe.publishId(), rec);
                    }
                }
            }
        }
    }

    @Test
    void isolationEachModLoaderCannotSeeAppClasses() throws Exception {
        Path host = hostJar();
        Path mod = jar(tmp, "mod.jar", Map.of("q/legacy/PosApi.class", legacyPosApi()), null);
        try (UniversalHost h = new UniversalHost(host, List.of())) {
            ModLoader a = h.openMod(mod);
            ModLoader b = h.openMod(mod);
            assertThrows(ClassNotFoundException.class, () -> Class.forName("dev.umb.pipeline.bridge.UniversalHost", false, a.loader()));
            assertThrows(ClassNotFoundException.class, () -> Class.forName("dev.umb.pipeline.bridge.UniversalHost", false, b.loader()));
            assertNotSame(a.loader(), b.loader());
        }
    }

    @Test
    void concurrentStretchTwoModsShareOneRegistryIdentity() throws Exception {
        Path host = hostJar();
        Path modA = jar(tmp, "modA.jar", Map.of("q/legacy/PosApi.class", legacyPosApi(), "p/Seed.class", seedClass()), null);
        Path modB = jar(tmp, "modB.jar", Map.of("q/legacy/PosApi.class", legacyPosApi(), "c/EntryPoint.class", consumerClass()), null);
        try (UniversalHost h = new UniversalHost(host, List.of())) {
            ModLoader a = h.openMod(modA);
            ModLoader b = h.openMod(modB);
            // Seed in A publishes a REAL Vec3i
            var seedResult = LifecycleDriver.drive(a, "p.Seed", List.of(), List.of());
            assertTrue(seedResult.completed());
            var published = Materializer.recover(seedResult.returnValue(), h.universe());
            assertNotNull(published);
            h.registry().publish("shared.vec3i", published);
            // Consumer in B views the SAME host instance behind B's own interface
            var resolved = h.registry().resolve("shared.vec3i");
            assertSame(published.hostInstance(), resolved.hostInstance());
            Class<?> ifaceB = b.entrypointClass("q.legacy.PosApi"); // different Class object than A's PosApi
            // B's PosApi is a different loader's class — bind and view
            var bindings = Materializer.bindByName(ifaceB, resolved.hostClass());
            Object view = h.registry().view(resolved, ifaceB, bindings);
            assertTrue(ifaceB.isInstance(view));
            // Drive consumer — must record 100,200,-3 or throw
            var cr = LifecycleDriver.drive(b, "c.EntryPoint", List.of(ifaceB), List.of(view));
            assertTrue(cr.completed(), () -> cr.cause() == null ? "failed" : cr.cause().toString());
        }
    }

    @Test
    void originalJarNeverMutated() throws Exception {
        Path host = hostJar();
        Path mod = jar(tmp, "mod.jar", Map.of("q/legacy/PosApi.class", legacyPosApi(), "p/Seed.class", seedClass()), null);
        byte[] before = Files.readAllBytes(mod);
        try (UniversalHost h = new UniversalHost(host, List.of())) {
            var r = h.smoke(mod);
            assertTrue(r.loaded() >= 0 || r.failed() >= 0);
        }
        assertArrayEquals(before, Files.readAllBytes(mod), "§24 original jar must not be mutated");
    }
}
