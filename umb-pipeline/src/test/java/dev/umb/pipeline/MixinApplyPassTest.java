package dev.umb.pipeline;

import dev.umb.core.BasicModAnalyzer;
import dev.umb.core.ModAnalysis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8-4: MixinApplyPass — AT widening + @Accessor bridging + mixin rewriting wired into jar flow.
 * Synthetic fixtures only (CC0), stubs never ship. Never modifies original jar (§24).
 */
class MixinApplyPassTest {

    @TempDir Path tmp;

    private static final String MIXIN_DESC = "Lorg/spongepowered/asm/mixin/Mixin;";
    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    private static AnnotationNode ann(String desc, Object... kv) {
        AnnotationNode a = new AnnotationNode(desc);
        a.values = new ArrayList<>();
        for (int i = 0; i < kv.length; i++) a.values.add(kv[i]);
        return a;
    }

    private static AnnotationNode at(String id) {
        return ann("Lorg/spongepowered/asm/mixin/injection/At;", "value", id);
    }

    private static byte[] targetClassBytes(String internalName) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "secret", "I", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "finalField", "I", null, null));
        MethodNode tick = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()V", null, null);
        tick.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/List", "size", "()I", false));
        tick.instructions.add(new InsnNode(Opcodes.RETURN));
        tick.visitMaxs(0, 0);
        cn.methods.add(tick);
        MethodNode hidden = new MethodNode(Opcodes.ACC_PRIVATE, "hidden", "()V", null, null);
        hidden.instructions.add(new InsnNode(Opcodes.RETURN));
        hidden.visitMaxs(0, 0);
        cn.methods.add(hidden);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static byte[] mixinWithAccessor(String internalName, String targetInternal) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        AnnotationNode mixin = ann(MIXIN_DESC, "value", List.of(Type.getObjectType(targetInternal)));
        cn.visibleAnnotations = new ArrayList<>();
        cn.visibleAnnotations.add(mixin);
        MethodNode acc = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getSecret", "()I", null, null);
        acc.visibleAnnotations = new ArrayList<>();
        acc.visibleAnnotations.add(ann(ACCESSOR_DESC, "value", "secret"));
        cn.methods.add(acc);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static byte[] mixinWithInject(String internalName, String targetInternal, String targetMethod, AnnotationNode atNode) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        AnnotationNode mixin = ann(MIXIN_DESC, "value", List.of(Type.getObjectType(targetInternal)));
        cn.visibleAnnotations = new ArrayList<>();
        cn.visibleAnnotations.add(mixin);
        MethodNode inject = new MethodNode(Opcodes.ACC_PUBLIC, "onTick", "()V", null, null);
        inject.instructions.add(new InsnNode(Opcodes.RETURN));
        inject.visitMaxs(0, 0);
        inject.visibleAnnotations = new ArrayList<>();
        inject.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/injection/Inject;",
                "method", List.of(targetMethod), "at", List.of(atNode)));
        cn.methods.add(inject);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static byte[] mixinWithInvoker(String internalName, String targetInternal, String methodRef) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                internalName, null, "java/lang/Object", null);
        AnnotationNode mixin = ann(MIXIN_DESC, "value", List.of(Type.getObjectType(targetInternal)));
        cn.visibleAnnotations = new ArrayList<>();
        cn.visibleAnnotations.add(mixin);
        MethodNode inv = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "callHidden", "()V", null, null);
        inv.visibleAnnotations = new ArrayList<>();
        inv.visibleAnnotations.add(ann(INVOKER_DESC, "value", methodRef));
        cn.methods.add(inv);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static Path jarOf(Path dir, String name, Map<String, byte[]> entries) throws IOException {
        Path p = dir.resolve(name);
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    private static ModAnalysis analyze(Path jar) throws IOException {
        return new BasicModAnalyzer().analyze(jar);
    }

    @Test
    void atWidenerWideningApplied() throws Exception {
        String targetInternal = "com/example/Target";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        // FML AT path via manifest
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nFMLAT: META-INF/accesstransformer.cfg\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/accesstransformer.cfg", "public com.example.Target secret\n".getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "in-at.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-at.jar");
        MixinApplyPass pass = new MixinApplyPass();
        PassReport r = pass.run(a, in, out);
        assertNotEquals(PassReport.Status.FAIL, r.status(), r.notes().toString());
        assertTrue(Files.exists(out));
        // Verify original jar not mutated (§24): re-read input
        try (JarFile jfIn = new JarFile(in.toFile())) {
            byte[] inBytes = jfIn.getInputStream(jfIn.getEntry(targetInternal + ".class")).readAllBytes();
            ClassNode n = new ClassNode();
            new org.objectweb.asm.ClassReader(inBytes).accept(n, 0);
            FieldNode fn = n.fields.stream().filter(f -> f.name.equals("secret")).findFirst().orElseThrow();
            assertTrue((fn.access & Opcodes.ACC_PRIVATE) != 0, "input jar must stay private (§24)");
        }
        try (JarFile jfOut = new JarFile(out.toFile())) {
            byte[] outBytes = jfOut.getInputStream(jfOut.getEntry(targetInternal + ".class")).readAllBytes();
            ClassNode n = new ClassNode();
            new org.objectweb.asm.ClassReader(outBytes).accept(n, 0);
            FieldNode fn = n.fields.stream().filter(f -> f.name.equals("secret")).findFirst().orElseThrow();
            assertTrue((fn.access & Opcodes.ACC_PUBLIC) != 0, "AT should widen to public");
        }
        assertTrue(r.status() == PassReport.Status.OK || r.status() == PassReport.Status.WARN);
    }

    @Test
    void accessorBridgeGeneratedForDeclaredMixinTarget() throws Exception {
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinA";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinA\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put(mixinInternal + ".class", mixinWithAccessor(mixinInternal, targetInternal));
        Path in = jarOf(tmp, "in-acc.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-acc.jar");
        MixinApplyPass pass = new MixinApplyPass();
        PassReport r = pass.run(a, in, out);
        assertNotEquals(PassReport.Status.FAIL, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        try (JarFile jfOut = new JarFile(out.toFile())) {
            byte[] b = jfOut.getInputStream(jfOut.getEntry(targetInternal + ".class")).readAllBytes();
            ClassNode n = new ClassNode();
            new org.objectweb.asm.ClassReader(b).accept(n, 0);
            assertTrue(n.methods.stream().anyMatch(m -> m.name.equals("getSecret") && m.desc.equals("()I")),
                    "getSecret accessor must be generated on target");
        }
    }

    @Test
    void invokerBridgeGenerated() throws Exception {
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinB";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinB\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put(mixinInternal + ".class", mixinWithInvoker(mixinInternal, targetInternal, "hidden"));
        Path in = jarOf(tmp, "in-inv.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-inv.jar");
        MixinApplyPass pass = new MixinApplyPass();
        PassReport r = pass.run(a, in, out);
        assertNotEquals(PassReport.Status.FAIL, r.status());
        try (JarFile jfOut = new JarFile(out.toFile())) {
            byte[] b = jfOut.getInputStream(jfOut.getEntry(targetInternal + ".class")).readAllBytes();
            ClassNode n = new ClassNode();
            new org.objectweb.asm.ClassReader(b).accept(n, 0);
            assertTrue(n.methods.stream().anyMatch(m -> m.name.equals("callHidden")),
                    "invoker must be generated");
        }
    }

    @Test
    void mixinInjectAppliedViaRewrite() throws Exception {
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinC";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinC\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put(mixinInternal + ".class", mixinWithInject(mixinInternal, targetInternal, "tick", at("HEAD")));
        Path in = jarOf(tmp, "in-inject.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-inject.jar");
        MixinApplyPass pass = new MixinApplyPass();
        PassReport r = pass.run(a, in, out);
        assertNotEquals(PassReport.Status.FAIL, r.status(), r.notes().toString());
        try (JarFile jfOut = new JarFile(out.toFile())) {
            byte[] b = jfOut.getInputStream(jfOut.getEntry(targetInternal + ".class")).readAllBytes();
            ClassNode n = new ClassNode();
            new org.objectweb.asm.ClassReader(b).accept(n, 0);
            var tick = n.methods.stream().filter(m -> m.name.equals("tick")).findFirst().orElseThrow();
            long injected = tick.instructions.iterator().hasNext() ? 0 : 0;
            boolean hasHandlerCall = false;
            for (org.objectweb.asm.tree.AbstractInsnNode insn = tick.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof org.objectweb.asm.tree.MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC) hasHandlerCall = true;
            }
            assertTrue(hasHandlerCall, "HEAD inject must insert INVOKESTATIC handler call");
        }
    }

    @Test
    void fabricAccessWidenerV2Widening() throws Exception {
        String targetInternal = "com/example/Target";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"accessWidener\":\"mixins.accessWidener\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put("mixins.accessWidener", "accessWidener v2 named\naccessible field com/example/Target secret I\n".getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "in-wid.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-wid.jar");
        MixinApplyPass pass = new MixinApplyPass();
        PassReport r = pass.run(a, in, out);
        assertNotEquals(PassReport.Status.FAIL, r.status());
        try (JarFile jfOut = new JarFile(out.toFile())) {
            byte[] b = jfOut.getInputStream(jfOut.getEntry(targetInternal + ".class")).readAllBytes();
            ClassNode n = new ClassNode();
            new org.objectweb.asm.ClassReader(b).accept(n, 0);
            FieldNode fn = n.fields.stream().filter(f -> f.name.equals("secret")).findFirst().orElseThrow();
            assertTrue((fn.access & Opcodes.ACC_PUBLIC) != 0);
        }
    }

    @Test
    void noMixinOrAtWorkSkippedAndIdentityCopied() throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        entries.put("com/example/Plain.class", targetClassBytes("com/example/Plain"));
        Path in = jarOf(tmp, "in-plain.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-plain.jar");
        MixinApplyPass pass = new MixinApplyPass();
        PassReport r = pass.run(a, in, out);
        assertEquals(PassReport.Status.SKIPPED, r.status());
        assertTrue(Files.exists(out));
        // identity copy: class still present
        try (JarFile jfOut = new JarFile(out.toFile())) {
            assertNotNull(jfOut.getEntry("com/example/Plain.class"));
        }
    }

    @Test
    void unresolvableTargetsD4WarnNotFail() throws Exception {
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinD";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinD\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        // accessor targeting missing field (D4)
        entries.put(mixinInternal + ".class", mixinWithAccessor(mixinInternal, targetInternal.replace('/', '.'))); // actually still secret, but we'll override value via raw accessor that misses
        // Replace mixin with one that targets missing
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                mixinInternal, null, "java/lang/Object", null);
        cn.visibleAnnotations = new ArrayList<>();
        cn.visibleAnnotations.add(ann(MIXIN_DESC, "value", List.of(Type.getObjectType(targetInternal))));
        MethodNode acc = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "getMissing", "()I", null, null);
        acc.visibleAnnotations = new ArrayList<>();
        acc.visibleAnnotations.add(ann(ACCESSOR_DESC, "value", "noSuchField"));
        cn.methods.add(acc);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        entries.put(mixinInternal + ".class", cw.toByteArray());
        Path in = jarOf(tmp, "in-d4.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-d4.jar");
        MixinApplyPass pass = new MixinApplyPass();
        PassReport r = pass.run(a, in, out);
        // Must not FAIL honestly — D4 is evidence + skip, status OK or WARN
        assertNotEquals(PassReport.Status.FAIL, r.status());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("missingField") || n.contains("noSuchField") || n.contains("not found") || n.contains("accessor")),
                "D4 evidence should be surfaced in notes: " + r.notes());
    }

    @Test
    void preservesNonClassEntries() throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[]}".getBytes(StandardCharsets.UTF_8));
        entries.put("com/example/Target.class", targetClassBytes("com/example/Target"));
        entries.put("assets/x/texture.png", new byte[]{0x01, 0x02, 0x03});
        entries.put("META-INF/accesstransformer.cfg", "public com.example.Target secret\n".getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "in-assets.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-assets.jar");
        MixinApplyPass pass = new MixinApplyPass();
        pass.run(a, in, out);
        try (JarFile jfOut = new JarFile(out.toFile())) {
            assertNotNull(jfOut.getEntry("assets/x/texture.png"));
            assertNotNull(jfOut.getEntry("fabric.mod.json"));
        }
    }

    private static byte[] mixinWithShadow(String internalName, String targetInternal, String shadowFieldName) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        AnnotationNode mixin = ann(MIXIN_DESC, "value", List.of(Type.getObjectType(targetInternal)));
        cn.visibleAnnotations = new ArrayList<>();
        cn.visibleAnnotations.add(mixin);
        FieldNode fn = new FieldNode(Opcodes.ACC_PUBLIC, shadowFieldName, "I", null, null);
        fn.visibleAnnotations = new ArrayList<>();
        fn.visibleAnnotations.add(new AnnotationNode("Lorg/spongepowered/asm/mixin/Shadow;"));
        cn.fields.add(fn);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    @Test
    void unappliedMixinAuditYieldsWarnAndMixinAuditDiagnostic() throws Exception {
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinUnapplied";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinUnapplied\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put(mixinInternal + ".class", mixinWithInject(mixinInternal, targetInternal, "noSuchMethod", at("HEAD")));
        Path in = jarOf(tmp, "in-unapplied.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-unapplied.jar");
        MixinApplyPass pass = new MixinApplyPass();
        PassReport r = pass.run(a, in, out);
        assertEquals(PassReport.Status.WARN, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("MIXIN_AUDIT")), "must have MIXIN_AUDIT diagnostic: " + r.diagnostics());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("unapplied mixin") || n.contains("auditWarnings")), r.notes().toString());
        try (JarFile jfOut = new JarFile(out.toFile())) {
            assertNotNull(jfOut.getEntry(targetInternal + ".class"), "target must still be present in output");
        }
    }

    @Test
    void shadowMismatchAuditYieldsWarn() throws Exception {
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinShadow";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinShadow\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put(mixinInternal + ".class", mixinWithShadow(mixinInternal, targetInternal, "ghostField"));
        Path in = jarOf(tmp, "in-shadow.jar", entries);
        ModAnalysis a = analyze(in);
        assertTrue(a.mixinInventory().configs().stream().flatMap(c -> c.mixins().stream()).anyMatch(m -> m.shadowMembers().contains("ghostField")),
                "analyzer must record shadow member");
        Path out = tmp.resolve("out-shadow.jar");
        MixinApplyPass pass = new MixinApplyPass();
        PassReport r = pass.run(a, in, out);
        assertEquals(PassReport.Status.WARN, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("MIXIN_AUDIT") && d.message().contains("ghostField")),
                "shadow mismatch must be MIXIN_AUDIT with field name: " + r.diagnostics());
    }

    @Test
    void shadowPresentDoesNotTriggerAudit() throws Exception {
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinShadowOk";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinShadowOk\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put(mixinInternal + ".class", mixinWithShadow(mixinInternal, targetInternal, "secret"));
        Path in = jarOf(tmp, "in-shadow-ok.jar", entries);
        ModAnalysis a = analyze(in);
        Path out = tmp.resolve("out-shadow-ok.jar");
        MixinApplyPass pass = new MixinApplyPass();
        PassReport r = pass.run(a, in, out);
        // shadow "secret" exists on target, so no audit warn for that reason (may still be WARN for other reasons but not shadow mismatch)
        assertTrue(r.diagnostics().stream().noneMatch(d -> d.code().equals("MIXIN_AUDIT") && d.message().contains("shadow mismatch") && d.message().contains("secret")),
                "present shadow must not trigger mismatch: " + r.diagnostics());
    }

    // ------------------------------------------------------------------ M8-7 translate-or-reject (AT vs ASM/JS coremods + require fail-soft)

    private static byte[] ifmlPluginBytes(String internalName) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object",
                new String[]{"net/minecraftforge/fml/relauncher/IFMLLoadingPlugin"});
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.visitMaxs(0, 0);
        cn.methods.add(init);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    private static byte[] mixinWithInjectRequire(String internalName, String targetInternal, String targetMethod, AnnotationNode atNode, int require) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        AnnotationNode mixin = ann(MIXIN_DESC, "value", List.of(Type.getObjectType(targetInternal)));
        cn.visibleAnnotations = new ArrayList<>();
        cn.visibleAnnotations.add(mixin);
        MethodNode inject = new MethodNode(Opcodes.ACC_PUBLIC, "onTick", "()V", null, null);
        inject.instructions.add(new InsnNode(Opcodes.RETURN));
        inject.visitMaxs(0, 0);
        inject.visibleAnnotations = new ArrayList<>();
        inject.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/injection/Inject;",
                "method", List.of(targetMethod), "at", List.of(atNode), "require", require));
        cn.methods.add(inject);
        ClassWriter cw = new ClassWriter(0);
        cn.accept(cw);
        return cw.toByteArray();
    }

    @Test
    void atViaManifestFmlAtTranslatesAndAtOnlyDoesNotRefuse() throws Exception {
        String targetInternal = "com/example/Target";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nFMLAT: META-INF/accesstransformer.cfg\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/accesstransformer.cfg", "public com.example.Target secret\n".getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "in-at-manifest.jar", entries);
        ModAnalysis a = analyze(in);
        assertTrue(a.coremodShapes().contains(ModAnalysis.CoremodShape.AT_ONLY), "AT-only jar must be AT_ONLY: " + a.coremodShapes());
        Path out = tmp.resolve("out-at-manifest.jar");
        PassReport r = new MixinApplyPass().run(a, in, out);
        assertNotEquals(PassReport.Status.FAIL, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(r.diagnostics().stream().noneMatch(d -> d.code().startsWith("COREMOD_")), "AT-only must not carry COREMOD diagnostics: " + r.diagnostics());
        try (JarFile jfOut = new JarFile(out.toFile())) {
            byte[] b = jfOut.getInputStream(jfOut.getEntry(targetInternal + ".class")).readAllBytes();
            ClassNode n = new ClassNode();
            new org.objectweb.asm.ClassReader(b).accept(n, 0);
            FieldNode fn = n.fields.stream().filter(f -> f.name.equals("secret")).findFirst().orElseThrow();
            assertTrue((fn.access & Opcodes.ACC_PUBLIC) != 0, "FML AT cfg must widen to public via AtWidener");
        }
        // §24: original jar still private
        try (JarFile jfIn = new JarFile(in.toFile())) {
            byte[] ib = jfIn.getInputStream(jfIn.getEntry(targetInternal + ".class")).readAllBytes();
            ClassNode n = new ClassNode();
            new org.objectweb.asm.ClassReader(ib).accept(n, 0);
            FieldNode fn = n.fields.stream().filter(f -> f.name.equals("secret")).findFirst().orElseThrow();
            assertTrue((fn.access & Opcodes.ACC_PRIVATE) != 0, "input jar must stay private (AT §24): " + fn.access);
        }
    }

    @Test
    void bareFmlAtNameUnderMetaInfResolvesAndWidens() throws Exception {
        // Real Forge mods declare the bare name (FMLAT: HBM_at.cfg) with the entry
        // at META-INF/HBM_at.cfg. The analyzer resolves it; the pass must find the
        // file (no AT_UNRESOLVABLE) and widen the target (atWidened path).
        String targetInternal = "com/example/Target";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nFMLAT: HBM_at.cfg\r\n".getBytes(StandardCharsets.UTF_8));
        entries.put("META-INF/HBM_at.cfg", "public com.example.Target secret\n".getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "in-at-bare.jar", entries);
        ModAnalysis a = analyze(in);
        assertTrue(a.accessTransformers().contains("META-INF/HBM_at.cfg"),
                "bare FMLAT name must resolve to its META-INF entry: " + a.accessTransformers());
        Path out = tmp.resolve("out-at-bare.jar");
        PassReport r = new MixinApplyPass().run(a, in, out);
        assertNotEquals(PassReport.Status.FAIL, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(r.diagnostics().stream().noneMatch(d -> d.code().equals("AT_UNRESOLVABLE")),
                "resolved AT must be found, not unresolvable: " + r.diagnostics());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("atWidened=1")),
                "exactly one member must widen: " + r.notes());
        try (JarFile jfOut = new JarFile(out.toFile())) {
            byte[] b = jfOut.getInputStream(jfOut.getEntry(targetInternal + ".class")).readAllBytes();
            ClassNode n = new ClassNode();
            new org.objectweb.asm.ClassReader(b).accept(n, 0);
            FieldNode fn = n.fields.stream().filter(f -> f.name.equals("secret")).findFirst().orElseThrow();
            assertTrue((fn.access & Opcodes.ACC_PUBLIC) != 0, "bare-name AT cfg must widen to public");
        }
    }

    @Test
    void asmCoremodViaIfmlPluginRefusedWithNamedKind() throws Exception {
        String pluginInternal = "com/example/coremod/Plugin";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        entries.put(pluginInternal + ".class", ifmlPluginBytes(pluginInternal));
        entries.put("com/example/Target.class", targetClassBytes("com/example/Target"));
        Path in = jarOf(tmp, "in-asm-coremod.jar", entries);
        ModAnalysis a = analyze(in);
        assertTrue(a.coremodShapes().contains(ModAnalysis.CoremodShape.IFML_PLUGIN), "must detect IFML_PLUGIN: " + a.coremodShapes());
        Path out = tmp.resolve("out-asm-coremod.jar");
        PassReport r = new MixinApplyPass().run(a, in, out);
        assertEquals(PassReport.Status.FAIL, r.status(), r.notes().toString());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("COREMOD_ASM_UNSUPPORTED")),
                "must carry COREMOD_ASM_UNSUPPORTED: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("COREMOD_ASM_UNSUPPORTED") && d.message().contains("IFML")),
                "message must name IFML shape: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().noneMatch(d -> d.code().equals("COREMOD_JS_UNSUPPORTED")),
                "pure ASM coremod must not carry JS code: " + r.diagnostics());
        // input jar not mutated (§24) — still readable with original bytes
        try (JarFile jfIn = new JarFile(in.toFile())) {
            assertNotNull(jfIn.getEntry(pluginInternal + ".class"), "input jar still holds plugin class after refusal");
        }
    }

    @Test
    void launchWrapperTweakerRefusedWithAsmKind() throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        entries.put("com/example/Target.class", targetClassBytes("com/example/Target"));
        entries.put("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nTweakClass: com.example.Tweaker\r\n".getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "in-tweaker.jar", entries);
        ModAnalysis a = analyze(in);
        assertTrue(a.coremodShapes().contains(ModAnalysis.CoremodShape.LAUNCHWRAPPER_TWEAKER),
                "must detect LAUNCHWRAPPER_TWEAKER: " + a.coremodShapes());
        Path out = tmp.resolve("out-tweaker.jar");
        PassReport r = new MixinApplyPass().run(a, in, out);
        assertEquals(PassReport.Status.FAIL, r.status());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("COREMOD_ASM_UNSUPPORTED")),
                "tweaker must be COREMOD_ASM_UNSUPPORTED: " + r.diagnostics());
    }

    @Test
    void jsCoremodModernRefusedWithNamedKind() throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        entries.put("com/example/Target.class", targetClassBytes("com/example/Target"));
        entries.put("coremods/my.js", "function initializeCoreMod() { return {}; }".getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "in-js-modern.jar", entries);
        ModAnalysis a = analyze(in);
        assertTrue(a.coremodShapes().contains(ModAnalysis.CoremodShape.JS_COREMOD_MODERN),
                "must detect JS_COREMOD_MODERN: " + a.coremodShapes());
        Path out = tmp.resolve("out-js-modern.jar");
        PassReport r = new MixinApplyPass().run(a, in, out);
        assertEquals(PassReport.Status.FAIL, r.status());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("COREMOD_JS_UNSUPPORTED")),
                "must carry COREMOD_JS_UNSUPPORTED: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().noneMatch(d -> d.code().equals("COREMOD_ASM_UNSUPPORTED")),
                "pure JS modern must not carry ASM code: " + r.diagnostics());
    }

    @Test
    void jsCoremodHistoricalRefusedWithNamedKind() throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        entries.put("com/example/Target.class", targetClassBytes("com/example/Target"));
        entries.put("legacy/patch.js", "ModLoader.addOverride('/terrain.png', '/my.png');".getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "in-js-hist.jar", entries);
        ModAnalysis a = analyze(in);
        assertTrue(a.coremodShapes().contains(ModAnalysis.CoremodShape.JS_COREMOD_HISTORICAL),
                "must detect JS_COREMOD_HISTORICAL: " + a.coremodShapes());
        Path out = tmp.resolve("out-js-hist.jar");
        PassReport r = new MixinApplyPass().run(a, in, out);
        assertEquals(PassReport.Status.FAIL, r.status());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("COREMOD_JS_UNSUPPORTED")),
                "historical JS must be COREMOD_JS_UNSUPPORTED: " + r.diagnostics());
    }

    @Test
    void asmAndJsBothPresentYieldBothDiagnosticsFail() throws Exception {
        String pluginInternal = "com/example/coremod/PluginBoth";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\"}".getBytes(StandardCharsets.UTF_8));
        entries.put(pluginInternal + ".class", ifmlPluginBytes(pluginInternal));
        entries.put("com/example/Target.class", targetClassBytes("com/example/Target"));
        entries.put("coremods/both.js", "function initializeCoreMod() {}".getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "in-both.jar", entries);
        ModAnalysis a = analyze(in);
        assertTrue(a.coremodShapes().contains(ModAnalysis.CoremodShape.IFML_PLUGIN), a.coremodShapes().toString());
        assertTrue(a.coremodShapes().contains(ModAnalysis.CoremodShape.JS_COREMOD_MODERN), a.coremodShapes().toString());
        PassReport r = new MixinApplyPass().run(a, in, tmp.resolve("out-both.jar"));
        assertEquals(PassReport.Status.FAIL, r.status());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("COREMOD_ASM_UNSUPPORTED")), r.diagnostics().toString());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("COREMOD_JS_UNSUPPORTED")), r.diagnostics().toString());
    }

    @Test
    void coremodRefusalPrecedesMixinWorkAndPreservesInput() throws Exception {
        String pluginInternal = "com/example/coremod/PluginMix";
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinWithTarget";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinWithTarget\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(pluginInternal + ".class", ifmlPluginBytes(pluginInternal));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put(mixinInternal + ".class", mixinWithInject(mixinInternal, targetInternal, "tick", at("HEAD")));
        entries.put("META-INF/accesstransformer.cfg", "public com.example.Target secret\n".getBytes(StandardCharsets.UTF_8));
        Path in = jarOf(tmp, "in-coremod-mixin.jar", entries);
        // capture input bytes for §24 check
        byte[] beforeBytes;
        try (JarFile jfIn = new JarFile(in.toFile())) {
            beforeBytes = jfIn.getInputStream(jfIn.getEntry(targetInternal + ".class")).readAllBytes();
        }
        ModAnalysis a = analyze(in);
        PassReport r = new MixinApplyPass().run(a, in, tmp.resolve("out-coremod-mixin.jar"));
        assertEquals(PassReport.Status.FAIL, r.status(), r.notes().toString());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("COREMOD_ASM_UNSUPPORTED")),
                "coremod must win over mixin work: " + r.diagnostics());
        // mixin audit must not be the reason — refusal is coremod, not MIXIN_UNRESOLVED/MIXIN_AUDIT
        assertTrue(r.diagnostics().stream().noneMatch(d -> d.code().equals("MIXIN_UNRESOLVED") || d.code().equals("MIXIN_AUDIT")),
                "coremod refusal must not also emit mixin audit: " + r.diagnostics());
        try (JarFile jfIn = new JarFile(in.toFile())) {
            byte[] afterBytes = jfIn.getInputStream(jfIn.getEntry(targetInternal + ".class")).readAllBytes();
            assertArrayEquals(beforeBytes, afterBytes, "input jar must not be mutated when coremod refuses (§24)");
        }
    }

    @Test
    void mixinUnresolvedRequireZeroDroppedAsWarnNotFail() throws Exception {
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinDrop";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinDrop\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put(mixinInternal + ".class", mixinWithInjectRequire(mixinInternal, targetInternal, "noSuchMethod", at("HEAD"), 0));
        Path in = jarOf(tmp, "in-require0.jar", entries);
        ModAnalysis a = analyze(in);
        assertEquals(0, a.mixinInventory().configs().stream().flatMap(c -> c.mixins().stream())
                .filter(m -> m.className().endsWith("MixinDrop")).findFirst().orElseThrow().requireGreaterThanZero(),
                "require header must be 0 for fail-soft");
        Path out = tmp.resolve("out-require0.jar");
        PassReport r = new MixinApplyPass().run(a, in, out);
        assertNotEquals(PassReport.Status.FAIL, r.status(), "require==0 must be dropped, not failed: " + r.notes() + " diag=" + r.diagnostics());
        assertEquals(PassReport.Status.WARN, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(r.diagnostics().stream().noneMatch(d -> d.code().equals("MIXIN_UNRESOLVED")),
                "require==0 must not emit MIXIN_UNRESOLVED: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("MIXIN_AUDIT")),
                "require==0 unapplied must still emit MIXIN_AUDIT warn: " + r.diagnostics());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("require==0") || n.contains("dropped")),
                "warn note must name require==0 fail-soft: " + r.notes());
        assertTrue(Files.exists(out), "WARN path still emits jar (with original target)");
        // original bytes unchanged (§24)
        try (JarFile jfIn = new JarFile(in.toFile())) {
            byte[] ib = jfIn.getInputStream(jfIn.getEntry(targetInternal + ".class")).readAllBytes();
            ClassNode n = new ClassNode();
            new org.objectweb.asm.ClassReader(ib).accept(n, 0);
            assertTrue(n.methods.stream().anyMatch(m -> m.name.equals("tick")), "input still has tick");
        }
    }

    @Test
    void mixinUnresolvedRequireOneFailsWithMixinUnresolved() throws Exception {
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinHard";
        Map<String, byte[]> entries = new HashMap<>();
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinHard\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        entries.put(mixinInternal + ".class", mixinWithInjectRequire(mixinInternal, targetInternal, "noSuchMethod", at("HEAD"), 1));
        Path in = jarOf(tmp, "in-require1.jar", entries);
        ModAnalysis a = analyze(in);
        assertEquals(1, a.mixinInventory().configs().stream().flatMap(c -> c.mixins().stream())
                .filter(m -> m.className().endsWith("MixinHard")).findFirst().orElseThrow().requireGreaterThanZero());
        Path out = tmp.resolve("out-require1.jar");
        PassReport r = new MixinApplyPass().run(a, in, out);
        assertEquals(PassReport.Status.FAIL, r.status(), r.notes().toString() + " diag=" + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("MIXIN_UNRESOLVED")),
                "require>0 unapplied must be MIXIN_UNRESOLVED FAIL: " + r.diagnostics());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("MIXIN_UNRESOLVED") && d.message().contains("MIXIN_UNRESOLVED")),
                "message must carry MIXIN_UNRESOLVED kind: " + r.diagnostics());
    }

    @Test
    void mixinRequireViaConfigDefaultRequireHardFails() throws Exception {
        String targetInternal = "com/example/Target";
        String mixinInternal = "com/example/mixin/MixinCfgReq";
        Map<String, byte[]> entries = new HashMap<>();
        // config injectors.defaultRequire=1 makes even absent annotation require hard
        entries.put("fabric.mod.json", "{\"schemaVersion\":1,\"id\":\"x\",\"version\":\"1\",\"mixins\":[\"a.mixins.json\"]}".getBytes(StandardCharsets.UTF_8));
        entries.put("a.mixins.json", "{\"package\":\"com.example.mixin\",\"mixins\":[\"MixinCfgReq\"],\"injectors\":{\"defaultRequire\":1}}".getBytes(StandardCharsets.UTF_8));
        entries.put(targetInternal + ".class", targetClassBytes(targetInternal));
        // inject without explicit require — defaults to 1 via config
        entries.put(mixinInternal + ".class", mixinWithInject(mixinInternal, targetInternal, "noSuchMethod", at("HEAD")));
        Path in = jarOf(tmp, "in-cfgreq.jar", entries);
        ModAnalysis a = analyze(in);
        assertEquals(1, a.mixinInventory().configs().stream().flatMap(c -> c.mixins().stream())
                .filter(m -> m.className().endsWith("MixinCfgReq")).findFirst().orElseThrow().requireGreaterThanZero(),
                "config defaultRequire=1 must make require hard");
        PassReport r = new MixinApplyPass().run(a, in, tmp.resolve("out-cfgreq.jar"));
        assertEquals(PassReport.Status.FAIL, r.status(), r.notes().toString());
        assertTrue(r.diagnostics().stream().anyMatch(d -> d.code().equals("MIXIN_UNRESOLVED")), r.diagnostics().toString());
    }
}
