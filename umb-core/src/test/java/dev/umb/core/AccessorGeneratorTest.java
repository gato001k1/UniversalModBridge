package dev.umb.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8-4: @Accessor/@Invoker bridging via ASM 9.9. Synthetic fixtures only (CC0), stubs never ship.
 */
class AccessorGeneratorTest {

    private static final String ACCESSOR_DESC = "Lorg/spongepowered/asm/mixin/gen/Accessor;";
    private static final String INVOKER_DESC = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    private static ClassNode targetWithMembers() {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, "com/example/Target", null, "java/lang/Object", null);
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "health", "I", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "STATIC_VAL", "Ljava/lang/String;", null, null));
        cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "finalField", "I", null, null));
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE, "doWork", "(I)Ljava/lang/String;", null, null);
        m.instructions.add(new org.objectweb.asm.tree.LdcInsnNode("hi"));
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ARETURN));
        m.visitMaxs(0, 0);
        cn.methods.add(m);
        MethodNode sm = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "staticWork", "()V", null, null);
        sm.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        sm.visitMaxs(0, 0);
        cn.methods.add(sm);
        return cn;
    }

    private static ClassNode mixinNode(String internalName, List<MethodNode> methods) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                internalName, null, "java/lang/Object", null);
        for (MethodNode mn : methods) cn.methods.add(mn);
        return cn;
    }

    private static MethodNode accessorMethod(String name, String desc, String value, boolean isInvoker, boolean isStatic) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | (isStatic ? Opcodes.ACC_STATIC : 0),
                name, desc, null, null);
        AnnotationNode ann = new AnnotationNode(isInvoker ? INVOKER_DESC : ACCESSOR_DESC);
        ann.values = new ArrayList<>();
        if (value != null) { ann.values.add("value"); ann.values.add(value); }
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann);
        return mn;
    }

    @Test
    void extractGetterDerivedName() {
        MethodNode m = accessorMethod("getHealth", "()I", null, false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        assertEquals(1, reqs.size());
        assertEquals("health", reqs.get(0).targetName());
        assertEquals("I", reqs.get(0).targetDesc());
        assertEquals(AccessorGenerator.Kind.GETTER, reqs.get(0).kind());
        assertTrue(reqs.get(0).isField());
    }

    @Test
    void extractSetterDerivedName() {
        MethodNode m = accessorMethod("setHealth", "(I)V", null, false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        assertEquals(1, reqs.size());
        assertEquals("health", reqs.get(0).targetName());
        assertEquals("I", reqs.get(0).targetDesc());
        assertEquals(AccessorGenerator.Kind.SETTER, reqs.get(0).kind());
    }

    @Test
    void extractIsPrefix() {
        MethodNode m = accessorMethod("isAlive", "()Z", null, false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        assertEquals("alive", reqs.get(0).targetName());
    }

    @Test
    void extractInvokerDerivedNameCallPrefix() {
        MethodNode m = accessorMethod("callDoWork", "(I)Ljava/lang/String;", null, true, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        assertEquals(1, reqs.size());
        assertEquals("doWork", reqs.get(0).targetName());
        assertEquals("(I)Ljava/lang/String;", reqs.get(0).targetDesc());
        assertEquals(AccessorGenerator.Kind.INVOKER, reqs.get(0).kind());
    }

    @Test
    void extractInvokePrefix() {
        MethodNode m = accessorMethod("invokeDoWork", "(I)Ljava/lang/String;", null, true, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        assertEquals("doWork", reqs.get(0).targetName());
    }

    @Test
    void extractExplicitValueOverridesDerived() {
        MethodNode m = accessorMethod("getHealth", "()I", "field_1234_health", false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        assertEquals("field_1234_health", reqs.get(0).targetName());
    }

    @Test
    void generateGetterInstanceField() {
        ClassNode target = targetWithMembers();
        MethodNode m = accessorMethod("getHealth", "()I", null, false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs, ev);
        assertEquals(1, r.generated());
        assertTrue(ev.isEmpty());
        MethodNode gen = target.methods.stream().filter(n -> n.name.equals("getHealth")).findFirst().orElseThrow();
        assertTrue(containsOpcode(gen, Opcodes.GETFIELD), "getter must use GETFIELD");
        // bytecode valid
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        target.accept(cw);
        byte[] b = cw.toByteArray();
        assertNotNull(new org.objectweb.asm.ClassReader(b));
    }

    @Test
    void generateSetterInstanceField() {
        ClassNode target = targetWithMembers();
        MethodNode m = accessorMethod("setHealth", "(I)V", null, false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs);
        assertEquals(1, r.generated());
        MethodNode gen = target.methods.stream().filter(n -> n.name.equals("setHealth")).findFirst().orElseThrow();
        assertTrue(containsOpcode(gen, Opcodes.PUTFIELD));
    }

    @Test
    void generateGetterStaticFieldWithInstanceAccessor() {
        ClassNode target = targetWithMembers();
        MethodNode m = accessorMethod("getSTATIC_VAL", "()Ljava/lang/String;", null, false, false);
        // name getSTATIC_VAL derives target STATIC_VAL (decapitalized -> sTATIC_VAL? Actually derive lowercases first char)
        // Use explicit value to avoid derivation quirks
        MethodNode m2 = accessorMethod("getStaticVal", "()Ljava/lang/String;", "STATIC_VAL", false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m2));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs);
        assertEquals(1, r.generated());
        MethodNode gen = target.methods.stream().filter(n -> n.name.equals("getStaticVal")).findFirst().orElseThrow();
        assertTrue(containsOpcode(gen, Opcodes.GETSTATIC));
    }

    @Test
    void generateStaticAccessorForStaticField() {
        ClassNode target = targetWithMembers();
        MethodNode m = accessorMethod("getSTATIC_VAL", "()Ljava/lang/String;", "STATIC_VAL", false, true);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs);
        assertEquals(1, r.generated());
        MethodNode gen = target.methods.stream().filter(n -> n.name.equals("getSTATIC_VAL")).findFirst().orElseThrow();
        assertTrue((gen.access & Opcodes.ACC_STATIC) != 0);
        assertTrue(containsOpcode(gen, Opcodes.GETSTATIC));
    }

    @Test
    void generateInvokerInstanceMethod() {
        ClassNode target = targetWithMembers();
        MethodNode m = accessorMethod("callDoWork", "(I)Ljava/lang/String;", null, true, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs);
        assertEquals(1, r.generated());
        MethodNode gen = target.methods.stream().filter(n -> n.name.equals("callDoWork")).findFirst().orElseThrow();
        assertTrue(containsOpcode(gen, Opcodes.INVOKEVIRTUAL));
    }

    @Test
    void generateInvokerStaticMethod() {
        ClassNode target = targetWithMembers();
        MethodNode m = accessorMethod("callStaticWork", "()V", "staticWork", true, true);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        assertEquals(1, reqs.size());
        // request targets staticWork
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs);
        assertEquals(1, r.generated());
        MethodNode gen = target.methods.stream().filter(n -> n.name.equals("callStaticWork")).findFirst().orElseThrow();
        assertTrue(containsOpcode(gen, Opcodes.INVOKESTATIC));
    }

    @Test
    void d4MissingFieldReportsEvidenceAndSkips() {
        ClassNode target = targetWithMembers();
        MethodNode m = accessorMethod("getMissing", "()I", "missingField", false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs, ev);
        assertEquals(0, r.generated());
        assertTrue(ev.stream().anyMatch(e -> e.kind().equals(AccessorGenerator.EVIDENCE_KIND)
                && e.detail().contains("not found")));
    }

    @Test
    void d4MissingMethodForInvoker() {
        ClassNode target = targetWithMembers();
        MethodNode m = accessorMethod("callNope", "()V", "nope", true, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs, ev);
        assertEquals(0, r.generated());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("not found")));
    }

    @Test
    void duplicateMethodIsEvidenceAndSkipped() {
        ClassNode target = targetWithMembers();
        // pre-add a method with same name/desc as accessor would generate
        MethodNode existing = new MethodNode(Opcodes.ACC_PUBLIC, "getHealth", "()I", null, null);
        existing.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.ICONST_0));
        existing.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.IRETURN));
        existing.visitMaxs(0, 0);
        target.methods.add(existing);
        MethodNode m = accessorMethod("getHealth", "()I", null, false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs, ev);
        assertEquals(0, r.generated());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("already exists")));
    }

    @Test
    void emptyRequestsProducesZero() {
        ClassNode target = targetWithMembers();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, List.of());
        assertEquals(0, r.generated());
    }

    // ------------------------------------------------------------------ M8-6 descriptor validation (D4, evidence, skip)

    @Test
    void d4GetterReturnMismatchReportsEvidenceAndSkips() {
        ClassNode target = targetWithMembers();
        // health is I, but accessor claims Ljava/lang/String;
        MethodNode m = accessorMethod("getHealth", "()Ljava/lang/String;", "health", false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        // Extract will set targetDesc from explicit handling? For plain "health" without descriptor, targetDesc = return type
        // So targetDesc = Ljava/lang/String; — mismatch vs field I should be caught as by-name mismatch
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs, ev);
        assertEquals(0, r.generated());
        assertTrue(ev.stream().anyMatch(e -> e.kind().equals(AccessorGenerator.EVIDENCE_KIND)
                && e.detail().contains("descriptor mismatch")), ev.toString());
    }

    @Test
    void d4SetterArgMismatchReportsEvidenceAndSkips() {
        ClassNode target = targetWithMembers();
        // health is I, setter with String arg
        MethodNode m = accessorMethod("setHealth", "(Ljava/lang/String;)V", "health", false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs, ev);
        assertEquals(0, r.generated());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("descriptor mismatch")), ev.toString());
    }

    @Test
    void d4SetterMustReturnVoid() {
        ClassNode target = targetWithMembers();
        // setter must return void, but return I
        MethodNode m = accessorMethod("setHealth", "(I)I", "health", false, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs, ev);
        assertEquals(0, r.generated());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("must return void") || e.detail().contains("descriptor mismatch")), ev.toString());
    }

    @Test
    void d4GetterWithArgsReportsEvidenceAndSkips() {
        ClassNode target = targetWithMembers();
        // Manually craft request with correct field desc but extra args — bypass extract to hit validate path
        AccessorGenerator.AccessorRequest req = new AccessorGenerator.AccessorRequest(
                "com/example/MixinA", "getHealth", "(I)I", false,
                AccessorGenerator.Kind.GETTER, "health", "I", true);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, List.of(req), ev);
        assertEquals(0, r.generated());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("must have 0 args") || e.detail().contains("descriptor mismatch")), ev.toString());
    }

    @Test
    void d4InvokerDescriptorMismatchViaManualRequest() {
        ClassNode target = targetWithMembers();
        // doWork is (I)Ljava/lang/String; — accessor claims ()V
        AccessorGenerator.AccessorRequest req = new AccessorGenerator.AccessorRequest(
                "com/example/MixinA", "callDoWork", "()V", false,
                AccessorGenerator.Kind.INVOKER, "doWork", "()V", false);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, List.of(req), ev);
        assertEquals(0, r.generated());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("descriptor mismatch")), ev.toString());
    }

    @Test
    void d4InvokerByNameMismatchViaExtractReportsEvidence() {
        ClassNode target = targetWithMembers();
        // Extract invoker targeting doWork but with wrong descriptor via explicit value not overriding desc — use manual mismatch
        // Use extract with explicit method name that exists but descriptor differs: accessor desc (I)I vs target (I)Ljava/lang/String;
        MethodNode m = accessorMethod("callDoWork", "(I)I", "doWork", true, false);
        ClassNode mixin = mixinNode("com/example/MixinA", List.of(m));
        List<AccessorGenerator.AccessorRequest> reqs = AccessorGenerator.extract(mixin);
        // req targetDesc = (I)I, field doWork has (I)Ljava/lang/String; — byName mismatch path
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        AccessorGenerator.GenerateResult r = AccessorGenerator.generate(target, reqs, ev);
        assertEquals(0, r.generated());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("descriptor mismatch")), ev.toString());
    }

    @Test
    void extractEmptyWhenNoAnnotations() {
        ClassNode plain = new ClassNode();
        plain.visit(52, Opcodes.ACC_PUBLIC, "com/example/Plain", null, "java/lang/Object", null);
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, "foo", "()V", null, null);
        m.instructions.add(new org.objectweb.asm.tree.InsnNode(Opcodes.RETURN));
        m.visitMaxs(0, 0);
        plain.methods.add(m);
        assertTrue(AccessorGenerator.extract(plain).isEmpty());
    }

    private static boolean containsOpcode(MethodNode mn, int opcode) {
        for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n.getOpcode() == opcode) return true;
            if (n instanceof FieldInsnNode fi && fi.getOpcode() == opcode) return true;
            if (n instanceof MethodInsnNode mi && mi.getOpcode() == opcode) return true;
        }
        return false;
    }
}
