package dev.umb.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8-3: Mixin bytecode rewriter. Synthetic fixtures only (CC0), stubs never ship.
 */
class MixinRewriterTest {

    // ------------------------------------------------------------------ ASM fixture helpers

    private static ClassNode targetClass(String internalName) {
        ClassNode n = new ClassNode();
        n.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        return n;
    }

    private static void addField(ClassNode n, String name, String desc) {
        n.fields.add(new org.objectweb.asm.tree.FieldNode(Opcodes.ACC_PRIVATE, name, desc, null, null));
    }

    private static MethodNode method(String name, String desc) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        return m;
    }

    private static byte[] toBytes(ClassNode n) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        n.accept(cw);
        return cw.toByteArray();
    }

    private static int insnCount(MethodNode m) { return m.instructions.size(); }

    private static String insnText(MethodNode m) {
        StringBuilder sb = new StringBuilder();
        for (org.objectweb.asm.tree.AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
            sb.append(n.getClass().getSimpleName()).append(":").append(n.getOpcode()).append(";");
        }
        return sb.toString();
    }

    private static AnnotationNode atNode(String id) {
        AnnotationNode a = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        a.values = new ArrayList<>();
        a.values.add("value"); a.values.add(id);
        return a;
    }

    private static AnnotationNode atNodeWithTarget(String id, String target) {
        AnnotationNode a = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        a.values = new ArrayList<>();
        a.values.add("value"); a.values.add(id);
        a.values.add("target"); a.values.add(target);
        return a;
    }

    private static AnnotationNode atNodeFull(String id, String target, Integer ordinal, Integer opcode) {
        AnnotationNode a = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        a.values = new ArrayList<>();
        a.values.add("value"); a.values.add(id);
        if (target != null) { a.values.add("target"); a.values.add(target); }
        if (ordinal != null) { a.values.add("ordinal"); a.values.add(ordinal); }
        if (opcode != null) { a.values.add("opcode"); a.values.add(opcode); }
        return a;
    }

    private static MixinSelector injectSel(String mixinClass, String handler, String targetMethod, AnnotationNode at) {
        return injectSelWithDesc(mixinClass, handler, "()V", targetMethod, at);
    }

    private static MixinSelector injectSelWithDesc(String mixinClass, String handler, String handlerDesc, String targetMethod, AnnotationNode at) {
        // Build via parser-style: craft a ClassNode method with @Inject and parse
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, mixinClass, null, "java/lang/Object", null);
        MethodNode mn = method(handler, handlerDesc);
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.visitMaxs(0,0);
        AnnotationNode inj = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        inj.values = new ArrayList<>();
        inj.values.add("method"); inj.values.add(List.of(targetMethod));
        inj.values.add("at"); inj.values.add(at);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(inj);
        cn.methods.add(mn);
        List<MixinSelector> parsed = MixinSelectorParser.parseClass(cn);
        assertEquals(1, parsed.size());
        return parsed.get(0);
    }

    private static MixinSelector redirectSel(String mixinClass, String handler, String targetMethod, AnnotationNode at) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, mixinClass, null, "java/lang/Object", null);
        MethodNode mn = method(handler, "()V");
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.visitMaxs(0,0);
        AnnotationNode inj = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Redirect;");
        inj.values = new ArrayList<>();
        inj.values.add("method"); inj.values.add(List.of(targetMethod));
        inj.values.add("at"); inj.values.add(at);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(inj);
        cn.methods.add(mn);
        List<MixinSelector> parsed = MixinSelectorParser.parseClass(cn);
        assertEquals(1, parsed.size());
        return parsed.get(0);
    }

    private static MixinSelector modifyArgSel(String mixinClass, String handler, String targetMethod, AnnotationNode at) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, mixinClass, null, "java/lang/Object", null);
        MethodNode mn = method(handler, "()V");
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.visitMaxs(0,0);
        AnnotationNode inj = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/ModifyArg;");
        inj.values = new ArrayList<>();
        inj.values.add("method"); inj.values.add(List.of(targetMethod));
        inj.values.add("at"); inj.values.add(at);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(inj);
        cn.methods.add(mn);
        List<MixinSelector> parsed = MixinSelectorParser.parseClass(cn);
        assertEquals(1, parsed.size());
        return parsed.get(0);
    }

    private static MixinSelector modifyVariableSel(String mixinClass, String handler, String targetMethod, AnnotationNode at) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, mixinClass, null, "java/lang/Object", null);
        MethodNode mn = method(handler, "()V");
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.visitMaxs(0,0);
        AnnotationNode inj = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/ModifyVariable;");
        inj.values = new ArrayList<>();
        inj.values.add("method"); inj.values.add(List.of(targetMethod));
        inj.values.add("at"); inj.values.add(at);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(inj);
        cn.methods.add(mn);
        List<MixinSelector> parsed = MixinSelectorParser.parseClass(cn);
        assertEquals(1, parsed.size());
        return parsed.get(0);
    }

    private static MixinSelector modifyConstantSel(String mixinClass, String handler, String targetMethod, AnnotationNode at) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, mixinClass, null, "java/lang/Object", null);
        MethodNode mn = method(handler, "()V");
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.visitMaxs(0,0);
        AnnotationNode inj = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/ModifyConstant;");
        inj.values = new ArrayList<>();
        inj.values.add("method"); inj.values.add(List.of(targetMethod));
        inj.values.add("at"); inj.values.add(at);
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(inj);
        cn.methods.add(mn);
        List<MixinSelector> parsed = MixinSelectorParser.parseClass(cn);
        assertEquals(1, parsed.size());
        return parsed.get(0);
    }

    private static MixinSelector overwriteSel(String mixinClass, String handler, String targetMethod) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, mixinClass, null, "java/lang/Object", null);
        MethodNode mn = method(handler, "()V");
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.visitMaxs(0,0);
        AnnotationNode ann = new AnnotationNode("Lorg/spongepowered/asm/mixin/Overwrite;");
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann);
        // Overwrite's target via method field is synthetic: store targets as member selector
        // We bypass parser's Overwrite target restriction: manually craft MixinSelector
        List<MemberSelector> targets = List.of(MemberSelector.parse(targetMethod));
        List<AtSelector> ats = List.of();
        return new MixinSelector(mixinClass, handler, "()V", InjectionPointKind.OVERWRITE, targets, ats, List.of(), -1, -1, null, null);
    }

    // Synthetic targets

    private static ClassNode simpleTarget() {
        ClassNode cn = targetClass("com/example/Target");
        // method: void tick()V with one INVOKE to helper, one FIELD access, one NEW, one CONSTANT, one RETURN
        MethodNode tick = method("tick", "()V");
        tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/List", "size", "()I", false));
        tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/List", "isEmpty", "()Z", false));
        tick.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, "com/example/Target", "count", "I"));
        tick.instructions.add(new FieldInsnNode(Opcodes.PUTFIELD, "com/example/Target", "count", "I"));
        tick.instructions.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        tick.instructions.add(new LdcInsnNode(42));
        tick.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        tick.instructions.add(new InsnNode(Opcodes.IRETURN));
        tick.visitMaxs(0,0);
        cn.methods.add(tick);
        // second method for target miss
        MethodNode other = method("other", "()V");
        other.instructions.add(new InsnNode(Opcodes.RETURN));
        other.visitMaxs(0,0);
        cn.methods.add(other);
        return cn;
    }

    private static ClassNode twoTickTarget() {
        ClassNode cn = targetClass("com/example/Target2");
        MethodNode m = method("tick", "()V");
        m.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/List", "size", "()I", false));
        m.instructions.add(new InsnNode(Opcodes.RETURN));
        m.visitMaxs(0,0);
        cn.methods.add(m);
        // method with two invokes of same owner for ordinal
        MethodNode two = method("two", "()V");
        two.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/List", "size", "()I", false));
        two.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/List", "size", "()I", false));
        two.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/List", "isEmpty", "()Z", false));
        two.instructions.add(new InsnNode(Opcodes.RETURN));
        two.visitMaxs(0,0);
        cn.methods.add(two);
        return cn;
    }

    // ------------------------------------------------------------------ tests

    @Test
    void injectHead() {
        ClassNode target = simpleTarget();
        MixinSelector sel = injectSel("com/example/MixinA", "onTick", "tick", atNode("HEAD"));
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(1, stats.applied());
        assertEquals(0, stats.skipped());
        MethodNode tick = target.methods.stream().filter(m->m.name.equals("tick")).findFirst().orElseThrow();
        // injected call is INVOKESTATIC (184) before first
        assertTrue(insnText(tick).contains("MethodInsnNode:184;"), insnText(tick));
        long injected = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode n = tick.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof MethodInsnNode mi && mi.getOpcode() == Opcodes.INVOKESTATIC) injected++;
        }
        assertEquals(1, injected, "one INVOKESTATIC handler call at HEAD");
    }

    @Test
    void injectTailBeforeEachReturn() {
        ClassNode target = simpleTarget();
        // Add a second return
        MethodNode tick = target.methods.stream().filter(m->m.name.equals("tick")).findFirst().orElseThrow();
        // inject at TAIL -> before each return/throw
        MixinSelector sel = injectSel("com/example/MixinA", "onTail", "tick", atNode("TAIL"));
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(1, stats.applied()); // only one return in tick
        assertEquals(0, stats.skipped());
    }

    @Test
    void redirectInvokeRemovesOriginal() {
        ClassNode target = twoTickTarget();
        // Redirect every INVOKE size
        AnnotationNode at = atNodeWithTarget("INVOKE", "Ljava/util/List;size()I");
        MixinSelector sel = redirectSel("com/example/MixinA", "redir", "tick", at);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(1, stats.applied());
        MethodNode tick = target.methods.stream().filter(m->m.name.equals("tick")).findFirst().orElseThrow();
        long invokeSize = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode n = tick.instructions.getFirst(); n!=null; n=n.getNext()) {
            if (n instanceof MethodInsnNode mi && mi.name.equals("size")) invokeSize++;
        }
        assertEquals(0, invokeSize, "redirect removed invoke");
    }

    @Test
    void ordinalFiltersToNthOccurrence() {
        ClassNode target = twoTickTarget();
        // ordinal=1 should pick second size
        AnnotationNode at = atNodeFull("INVOKE", "Ljava/util/List;size()I", 1, null);
        MixinSelector sel = injectSel("com/example/MixinA", "onSecond", "two", at);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(1, stats.applied());
        // before-after: two has 3 invokes, one is isEmpty; count of MethodInsnNodes unchanged +1 injected
        MethodNode two = target.methods.stream().filter(m->m.name.equals("two")).findFirst().orElseThrow();
        long totalInvoke = 0;
        for (org.objectweb.asm.tree.AbstractInsnNode n = two.instructions.getFirst(); n!=null; n=n.getNext()) if (n instanceof MethodInsnNode) totalInvoke++;
        assertEquals(4, totalInvoke, "original 3 invokes + 1 injected handler = 4");
    }

    @Test
    void shiftAfterInsertsAfterAnchor() {
        ClassNode target = twoTickTarget();
        // INVOKE with shift via at + target
        AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new ArrayList<>();
        at.values.add("value"); at.values.add("INVOKE:AFTER");
        at.values.add("target"); at.values.add("Ljava/util/List;size()I");
        MixinSelector sel = injectSel("com/example/MixinA", "afterInvoke", "tick", at);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(1, stats.applied());
    }

    @Test
    void fieldInjectionPoint() {
        ClassNode target = simpleTarget();
        AnnotationNode at = atNodeWithTarget("FIELD", "Lcom/example/Target;count:I");
        MixinSelector sel = injectSel("com/example/MixinA", "onField", "tick", at);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertTrue(stats.applied() >= 1);
    }

    @Test
    void fieldOpcodeFilter() {
        ClassNode target = simpleTarget();
        // GETFIELD opcode 180, PUTFIELD 181
        AnnotationNode at = atNodeFull("FIELD", "Lcom/example/Target;count:I", null, Opcodes.GETFIELD);
        MixinSelector sel = injectSel("com/example/MixinA", "onGet", "tick", at);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(1, stats.applied());
        // only GETFIELD, not both
    }

    @Test
    void newInjectionPoint() {
        ClassNode target = simpleTarget();
        AnnotationNode at = atNodeWithTarget("NEW", "java/util/ArrayList");
        MixinSelector sel = injectSel("com/example/MixinA", "onNew", "tick", at);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(1, stats.applied());
    }

    @Test
    void constantInjectionPoint() {
        ClassNode target = simpleTarget();
        AnnotationNode at = atNode("CONSTANT");
        MixinSelector sel = modifyConstantSel("com/example/MixinA", "onConst", "tick", at);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(1, stats.applied());
    }

    @Test
    void constantArgsFiltering() {
        ClassNode target = simpleTarget();
        // CONSTANT with intValue=42
        AnnotationNode at = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/At;");
        at.values = new ArrayList<>();
        at.values.add("value"); at.values.add("CONSTANT");
        at.values.add("args"); at.values.add(List.of("intValue=42"));
        MixinSelector sel = modifyConstantSel("com/example/MixinA", "onConst42", "tick", at);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(1, stats.applied());
    }

    @Test
    void modifyArgAndModifyVariablePaths() {
        ClassNode target = twoTickTarget();
        AnnotationNode atA = atNodeWithTarget("INVOKE", "Ljava/util/List;size()I");
        MixinSelector ma = modifyArgSel("com/example/MixinA", "modArg", "tick", atA);
        AnnotationNode atV = atNode("HEAD");
        MixinSelector mv = modifyVariableSel("com/example/MixinA", "modVar", "tick", atV);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(ma, mv), null, ev);
        assertEquals(2, stats.applied());
    }

    @Test
    void overwriteInjectsAtHead() {
        ClassNode target = simpleTarget();
        MixinSelector ow = overwriteSel("com/example/MixinA", "overwriteTick", "tick");
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(ow), null, ev);
        assertEquals(1, stats.applied());
    }

    @Test
    void unresolvableTargetsD4ReportAndSkip() {
        ClassNode target = simpleTarget();
        // Selectors with invalid raw -> allSelectorsValid false -> D4 skip
        // Use a truly invalid MemberInfo grammar string (L-owner without ';') which MixinSelectorTest also uses
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, "com/example/MixinA", null, "java/lang/Object", null);
        MethodNode mn = method("bad", "()V");
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.visitMaxs(0,0);
        AnnotationNode inj = new AnnotationNode("Lorg/spongepowered/asm/mixin/injection/Inject;");
        inj.values = new ArrayList<>();
        inj.values.add("method"); inj.values.add(List.of("Lnet/minecraft/BadTargetWithoutSemiMethod()V"));
        inj.values.add("at"); inj.values.add(atNode("HEAD"));
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(inj);
        cn.methods.add(mn);
        List<MixinSelector> bad = MixinSelectorParser.parseClass(cn);
        assertEquals(1, bad.size());
        assertTrue(!bad.get(0).allSelectorsValid(), "L-owner without ';' must be invalid: " + bad.get(0).invalidReasons());
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, bad, null, ev);
        assertEquals(0, stats.applied());
        assertEquals(1, stats.skipped());
        assertTrue(ev.stream().anyMatch(e->e.detail().contains("skipped invalid")));
    }

    @Test
    void unresolvableResolverD4ReportAndSkip() {
        ClassNode target = simpleTarget();
        MixinSelector sel = injectSel("com/example/MixinA", "onTick", "tick", atNode("HEAD"));
        // resolver marks target as UNRESOLVABLE
        java.util.function.Function<MemberSelector, ResolvedSelector> r = ms -> ResolvedSelector.unresolvable(ms, "no graph path");
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), r, ev);
        // target unresolvable => skipped
        assertEquals(0, stats.applied());
        assertEquals(1, stats.skipped());
        assertTrue(ev.stream().anyMatch(e->e.detail().contains("unresolvable")));
    }

    @Test
    void unresolvableAtTargetD4Skip() {
        ClassNode target = simpleTarget();
        AnnotationNode at = atNodeWithTarget("INVOKE", "Ljava/util/List;size()I");
        MixinSelector sel = injectSel("com/example/MixinA", "onTick", "tick", at);
        java.util.function.Function<MemberSelector, ResolvedSelector> r = ms -> {
            // mark only invoke targets unresolvable
            if (ms.name() != null && ms.name().equals("size")) return ResolvedSelector.unresolvable(ms, "at target not mappable");
            return ResolvedSelector.passthrough(ms);
        };
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), r, ev);
        assertEquals(0, stats.applied());
        assertTrue(ev.stream().anyMatch(e->e.detail().contains("unresolvable @At")));
    }

    @Test
    void targetMethodNotFoundIsEvidence() {
        ClassNode target = simpleTarget();
        MixinSelector sel = injectSel("com/example/MixinA", "onMissing", "noSuchMethod", atNode("HEAD"));
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(0, stats.applied());
        assertTrue(ev.stream().anyMatch(e->e.detail().contains("target method not found")));
    }

    @Test
    void noInjectionPointFoundIsEvidence() {
        ClassNode target = simpleTarget();
        // tick has no BOGUS invoke
        AnnotationNode at = atNodeWithTarget("INVOKE", "Ljava/util/List;nonExisting()V");
        MixinSelector sel = injectSel("com/example/MixinA", "onBogus", "tick", at);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(0, stats.applied());
        assertTrue(ev.stream().anyMatch(e->e.detail().contains("no injection point")));
    }

    @Test
    void ordinalOutOfRangeIsEvidence() {
        ClassNode target = twoTickTarget();
        AnnotationNode at = atNodeFull("INVOKE", "Ljava/util/List;size()I", 99, null);
        MixinSelector sel = injectSel("com/example/MixinA", "onBig", "tick", at);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, ev);
        assertEquals(0, stats.applied());
        assertTrue(ev.stream().anyMatch(e->e.detail().contains("no injection point")));
    }

    @Test
    void passthroughResolverKeepsOriginalNames() {
        ClassNode target = simpleTarget();
        // Passthrough preserves raw name "tick"
        java.util.function.Function<MemberSelector, ResolvedSelector> r = ms -> ResolvedSelector.passthrough(ms);
        MixinSelector sel = injectSel("com/example/MixinA", "onTick", "tick", atNode("HEAD"));
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), r, ev);
        assertEquals(1, stats.applied());
    }

    @Test
    void viaGraphAndViaRefmapResolvedNamesApply() {
        ClassNode target = simpleTarget();
        // Pretend tick is the resolved name of func_71410_x
        MemberSelector orig = MemberSelector.parse("func_71410_x");
        MemberSelector resolved = MemberSelector.parse("tick");
        java.util.function.Function<MemberSelector, ResolvedSelector> r = ms -> {
            if (ms.name().equals("func_71410_x")) return ResolvedSelector.viaGraph(ms, resolved, 0.9, List.of("graph"));
            return ResolvedSelector.passthrough(ms);
        };
        MixinSelector sel = injectSel("com/example/MixinA", "onLegacy", "func_71410_x", atNode("HEAD"));
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), r, ev);
        assertEquals(1, stats.applied());
    }

    @Test
    void byteLevelRewriteRoundtrip() {
        ClassNode target = simpleTarget();
        byte[] before = toBytes(target);
        MixinSelector sel = injectSel("com/example/MixinA", "onTick", "tick", atNode("HEAD"));
        MixinRewriter.RewriteResult rr = MixinRewriter.rewrite(before, List.of(sel));
        assertTrue(rr.applied() == 1);
        assertNotNull(rr.bytecode());
        // bytecode should be valid classfile
        org.objectweb.asm.ClassReader cr2 = new org.objectweb.asm.ClassReader(rr.bytecode());
        ClassNode after = new ClassNode();
        cr2.accept(after, 0);
        assertEquals("com/example/Target", after.name);
        MethodNode tickAfter = after.methods.stream().filter(m->m.name.equals("tick")).findFirst().orElseThrow();
        assertTrue(insnCount(tickAfter) > insnCount(target.methods.stream().filter(m->m.name.equals("tick")).findFirst().orElseThrow()));
    }

    @Test
    void rewriteAllCollectsFromAnalysisInventory() {
        // Build analysis with inventory that carries selectors
        ClassNode target = simpleTarget();
        MixinSelector sel = injectSel("com/example/MixinA", "onTick", "tick", atNode("HEAD"));
        // Minimal ModAnalysis with mixinInventory
        ModAnalysis.MixinClassInventory cls = new ModAnalysis.MixinClassInventory(
                "com.example.MixinA", ModAnalysis.MixinClassPresence.PRESENT_IN_JAR, true,
                List.of(), ModAnalysis.MappingNamespace.UNKNOWN, 1, 1, 0, 0, 0, List.of(), List.of(), List.of(sel));
        ModAnalysis.MixinConfigInventory cfg = new ModAnalysis.MixinConfigInventory(
                "x.mixins.json", "com.example", null, false, null, null, "", 0, 1, 1, 0, List.of(), List.of(cls));
        ModAnalysis.MixinInventory inv = new ModAnalysis.MixinInventory(List.of(cfg), List.of(), 1, 1, 0);
        ModAnalysis analysis = new ModAnalysis("m","0.1", java.util.Optional.empty(), ModAnalysis.LoaderKind.FABRIC, ModAnalysis.MappingNamespace.INTERMEDIARY, 52,
                List.of(), List.of(), List.of(), List.of(), false, List.of(), List.of(), Map.of(), List.of(), List.of(), inv, List.of(), List.of());
        Map<String, MixinRewriter.RewriteResult> res = MixinRewriter.rewriteAll(analysis, Map.of("com/example/Target", toBytes(target)), null);
        assertEquals(1, res.size());
        assertEquals(1, res.get("com/example/Target").applied());
    }

    // ------------------------------------------------------------------ M8-6 OVERWRITE enforcement (body replacement + widening preservation, D4)

    private static ClassNode targetForOverwrite(String internalName, String methodName, String methodDesc, int access, int returnValue) {
        ClassNode cn = targetClass(internalName);
        MethodNode mn = new MethodNode(access, methodName, methodDesc, null, null);
        // simple body: ICONST returnValue; IRETURN or RETURN
        if (methodDesc.endsWith("I")) {
            if (returnValue >= -1 && returnValue <= 5) mn.instructions.add(new InsnNode(Opcodes.ICONST_0 + returnValue));
            else mn.instructions.add(new LdcInsnNode(returnValue));
            mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        } else {
            mn.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        mn.visitMaxs(0, 0);
        cn.methods.add(mn);
        return cn;
    }

    private static ClassNode mixinWithOverwriteBody(String mixinInternal, String handlerName, String handlerDesc, int handlerAccess, int returnValue, boolean addTryCatch) {
        ClassNode cn = new ClassNode();
        cn.visit(52, Opcodes.ACC_PUBLIC, mixinInternal, null, "java/lang/Object", null);
        MethodNode mn = new MethodNode(handlerAccess, handlerName, handlerDesc, null, null);
        // body distinct from target: load returnValue
        org.objectweb.asm.Label l0 = new org.objectweb.asm.Label();
        org.objectweb.asm.Label l1 = new org.objectweb.asm.Label();
        org.objectweb.asm.Label l2 = new org.objectweb.asm.Label();
        // Use tree API with LabelNodes for try-catch coverage
        org.objectweb.asm.tree.LabelNode ln0 = new org.objectweb.asm.tree.LabelNode(l0);
        org.objectweb.asm.tree.LabelNode ln1 = new org.objectweb.asm.tree.LabelNode(l1);
        org.objectweb.asm.tree.LabelNode ln2 = new org.objectweb.asm.tree.LabelNode(l2);
        mn.instructions.add(ln0);
        if (handlerDesc.endsWith("I")) {
            if (returnValue >= -1 && returnValue <= 5) mn.instructions.add(new InsnNode(Opcodes.ICONST_0 + returnValue));
            else mn.instructions.add(new LdcInsnNode(returnValue));
        } else {
            mn.instructions.add(new LdcInsnNode("replaced"));
        }
        mn.instructions.add(ln1);
        if (handlerDesc.endsWith("I")) mn.instructions.add(new InsnNode(Opcodes.IRETURN));
        else mn.instructions.add(new InsnNode(Opcodes.ARETURN));
        mn.instructions.add(ln2);
        if (addTryCatch) {
            mn.tryCatchBlocks = new ArrayList<>();
            mn.tryCatchBlocks.add(new org.objectweb.asm.tree.TryCatchBlockNode(ln0, ln1, ln2, "java/lang/Exception"));
        }
        mn.visitMaxs(0, 0);
        cn.methods.add(mn);
        return cn;
    }

    @Test
    void overwriteReplacesBodyWithMixinBody() {
        ClassNode target = targetForOverwrite("com/example/Target", "compute", "()I", Opcodes.ACC_PUBLIC, 1);
        ClassNode mixin = mixinWithOverwriteBody("com/example/MixinA", "compute", "()I", Opcodes.ACC_PUBLIC, 42, false);
        MixinSelector ow = overwriteSel("com/example/MixinA", "compute", "compute");
        // Need handlerDesc matching targetDesc; overwriteSel used "()V" by default — craft matching one
        MixinSelector sel = new MixinSelector("com/example/MixinA", "compute", "()I", InjectionPointKind.OVERWRITE,
                List.of(MemberSelector.parse("compute()I")), List.of(), List.of(), -1, -1, null, null);
        Map<String, ClassNode> mixinNodes = Map.of("com/example/MixinA", mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, mixinNodes, ev);
        assertEquals(1, stats.applied(), ev.toString());
        assertTrue(ev.isEmpty(), ev.toString());
        MethodNode after = target.methods.stream().filter(m -> m.name.equals("compute")).findFirst().orElseThrow();
        // Body must be mixin's (ICONST/ldc 42), not original (1)
        String text = insnText(after);
        assertTrue(text.contains("LdcInsnNode") || text.contains("ICONST"), text);
        // Verify bytecode still valid
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        target.accept(cw);
        byte[] bytes = cw.toByteArray();
        ClassNode reloaded = new ClassNode();
        new org.objectweb.asm.ClassReader(bytes).accept(reloaded, 0);
        MethodNode reloadedCompute = reloaded.methods.stream().filter(m -> m.name.equals("compute")).findFirst().orElseThrow();
        boolean has42 = false;
        for (org.objectweb.asm.tree.AbstractInsnNode n = reloadedCompute.instructions.getFirst(); n != null; n = n.getNext()) {
            if (n instanceof LdcInsnNode ldc && Integer.valueOf(42).equals(ldc.cst)) has42 = true;
            if (n.getOpcode() == Opcodes.BIPUSH || n.getOpcode() == Opcodes.ICONST_2) {
                // ICONST_2 is 2, not 42 — only Ldc path matters for 42
            }
        }
        assertTrue(has42, "reloaded body must contain 42 from mixin");
    }

    @Test
    void overwritePreservesWidenedAccess() {
        // Target already widened to public non-final (AT ran before rewrite)
        ClassNode target = targetForOverwrite("com/example/Target", "compute", "()I", Opcodes.ACC_PUBLIC, 1);
        // Simulate AT widening left it public; now mixin handler is package-private — overwrite must NOT narrow
        ClassNode mixin = mixinWithOverwriteBody("com/example/MixinA", "compute", "()I", 0, 99, false);
        MethodNode targetMethod = target.methods.stream().filter(m -> m.name.equals("compute")).findFirst().orElseThrow();
        int widened = targetMethod.access;
        assertTrue((widened & Opcodes.ACC_PUBLIC) != 0);
        MixinSelector sel = new MixinSelector("com/example/MixinA", "compute", "()I", InjectionPointKind.OVERWRITE,
                List.of(MemberSelector.parse("compute()I")), List.of(), List.of(), -1, -1, null, null);
        Map<String, ClassNode> mixinNodes = Map.of("com/example/MixinA", mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, mixinNodes, ev);
        assertEquals(1, stats.applied());
        MethodNode after = target.methods.stream().filter(m -> m.name.equals("compute")).findFirst().orElseThrow();
        assertEquals(widened, after.access, "overwrite must preserve widened access, not clobber with mixin's");
        assertTrue((after.access & Opcodes.ACC_PUBLIC) != 0);
    }

    @Test
    void overwriteDescriptorMismatchD4() {
        ClassNode target = targetForOverwrite("com/example/Target", "compute", "()I", Opcodes.ACC_PUBLIC, 1);
        ClassNode mixin = mixinWithOverwriteBody("com/example/MixinA", "compute", "()Ljava/lang/String;", Opcodes.ACC_PUBLIC, 0, false);
        MixinSelector sel = new MixinSelector("com/example/MixinA", "compute", "()Ljava/lang/String;", InjectionPointKind.OVERWRITE,
                List.of(MemberSelector.parse("compute()I")), List.of(), List.of(), -1, -1, null, null);
        Map<String, ClassNode> mixinNodes = Map.of("com/example/MixinA", mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, mixinNodes, ev);
        assertEquals(0, stats.applied());
        assertEquals(1, stats.skipped());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("descriptor mismatch")), ev.toString());
    }

    @Test
    void overwriteStaticMismatchD4() {
        ClassNode target = targetForOverwrite("com/example/Target", "compute", "()I", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, 1);
        ClassNode mixin = mixinWithOverwriteBody("com/example/MixinA", "compute", "()I", Opcodes.ACC_PUBLIC, 2, false);
        MixinSelector sel = new MixinSelector("com/example/MixinA", "compute", "()I", InjectionPointKind.OVERWRITE,
                List.of(MemberSelector.parse("compute()I")), List.of(), List.of(), -1, -1, null, null);
        Map<String, ClassNode> mixinNodes = Map.of("com/example/MixinA", mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, mixinNodes, ev);
        assertEquals(0, stats.applied());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("static mismatch")), ev.toString());
    }

    @Test
    void overwriteAbstractHandlerD4() {
        ClassNode target = targetForOverwrite("com/example/Target", "compute", "()I", Opcodes.ACC_PUBLIC, 1);
        ClassNode mixin = new ClassNode();
        mixin.visit(52, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE, "com/example/MixinA", null, "java/lang/Object", null);
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "compute", "()I", null, null);
        mixin.methods.add(mn);
        MixinSelector sel = new MixinSelector("com/example/MixinA", "compute", "()I", InjectionPointKind.OVERWRITE,
                List.of(MemberSelector.parse("compute()I")), List.of(), List.of(), -1, -1, null, null);
        Map<String, ClassNode> mixinNodes = Map.of("com/example/MixinA", mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, mixinNodes, ev);
        assertEquals(0, stats.applied());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("abstract")), ev.toString());
    }

    @Test
    void overwriteConstructorNotSupportedD4() {
        ClassNode target = targetClass("com/example/Target");
        MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        ctor.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        ctor.instructions.add(new InsnNode(Opcodes.RETURN));
        ctor.visitMaxs(0, 0);
        target.methods.add(ctor);
        ClassNode mixin = mixinWithOverwriteBody("com/example/MixinA", "<init>", "()V", Opcodes.ACC_PUBLIC, 0, false);
        MixinSelector sel = new MixinSelector("com/example/MixinA", "<init>", "()V", InjectionPointKind.OVERWRITE,
                List.of(MemberSelector.parse("<init>()V")), List.of(), List.of(), -1, -1, null, null);
        Map<String, ClassNode> mixinNodes = Map.of("com/example/MixinA", mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, mixinNodes, ev);
        assertEquals(0, stats.applied());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("constructor")), ev.toString());
    }

    @Test
    void overwriteMissingMixinClassD4() {
        ClassNode target = targetForOverwrite("com/example/Target", "compute", "()I", Opcodes.ACC_PUBLIC, 1);
        MixinSelector sel = new MixinSelector("com/example/MixinA", "compute", "()I", InjectionPointKind.OVERWRITE,
                List.of(MemberSelector.parse("compute()I")), List.of(), List.of(), -1, -1, null, null);
        Map<String, ClassNode> mixinNodes = Map.of(); // empty
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        // Use legacy path? No — pass empty map, falls back to marker inject. To hit D4 we pass non-empty map without key
        ClassNode dummy = new ClassNode();
        dummy.visit(52, Opcodes.ACC_PUBLIC, "com/example/Other", null, "java/lang/Object", null);
        Map<String, ClassNode> withOther = Map.of("com/example/Other", dummy);
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, withOther, ev);
        assertEquals(0, stats.applied());
        assertTrue(ev.stream().anyMatch(e -> e.detail().contains("not in pass input")), ev.toString());
    }

    @Test
    void overwriteCopiesTryCatchAndLabels() {
        ClassNode target = targetForOverwrite("com/example/Target", "compute", "()I", Opcodes.ACC_PUBLIC, 1);
        ClassNode mixin = mixinWithOverwriteBody("com/example/MixinA", "compute", "()I", Opcodes.ACC_PUBLIC, 77, true);
        MixinSelector sel = new MixinSelector("com/example/MixinA", "compute", "()I", InjectionPointKind.OVERWRITE,
                List.of(MemberSelector.parse("compute()I")), List.of(), List.of(), -1, -1, null, null);
        Map<String, ClassNode> mixinNodes = Map.of("com/example/MixinA", mixin);
        List<ModAnalysis.Evidence> ev = new ArrayList<>();
        var stats = MixinRewriter.rewrite(target, List.of(sel), null, mixinNodes, ev);
        assertEquals(1, stats.applied());
        MethodNode after = target.methods.stream().filter(m -> m.name.equals("compute")).findFirst().orElseThrow();
        assertEquals(1, after.tryCatchBlocks.size(), "try-catch must be cloned");
        assertEquals("java/lang/Exception", after.tryCatchBlocks.get(0).type);
        // Verify frames recompute cleanly
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        target.accept(cw);
        assertNotNull(cw.toByteArray());
    }
}
