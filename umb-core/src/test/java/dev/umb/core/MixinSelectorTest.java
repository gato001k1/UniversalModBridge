package dev.umb.core;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.InsnNode;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M8-2: selector IR — MemberSelector, AtSelector, MixinSelectorParser.
 * Synthetic ASM fixtures only (CC0), D4 never guesses — invalid kept, not dropped.
 */
class MixinSelectorTest {

    // ------------------------------------------------------------------ MemberSelector grammar

    @Test
    void memberSelector_bareName() {
        MemberSelector m = MemberSelector.parse("doThing");
        assertTrue(m.valid(), m.error());
        assertNull(m.owner());
        assertEquals("doThing", m.name());
        assertEquals(0, m.quantifier().min());
        assertEquals(1, m.quantifier().max());
        assertNull(m.descriptor());
        assertNull(m.tail());
    }

    @Test
    void memberSelector_dottedOwnerAndFieldDescriptor() {
        MemberSelector m = MemberSelector.parse("net.minecraft.world.World.field_1234_foo:I");
        // Dotted owner splits on last dot before quantifier
        assertTrue(m.valid(), m.error());
        assertEquals("net/minecraft/world/World", m.owner());
        assertEquals("field_1234_foo", m.name());
        assertEquals("I", m.descriptor());
        assertTrue(m.isField());
    }

    @Test
    void memberSelector_LownerAndMethodDescriptor() {
        MemberSelector m = MemberSelector.parse("Lnet/minecraft/server/MinecraftServer;run()V");
        assertTrue(m.valid(), m.error());
        assertEquals("net/minecraft/server/MinecraftServer", m.owner());
        assertEquals("run", m.name());
        assertEquals("()V", m.descriptor());
        assertTrue(m.isMethod());
    }

    @Test
    void memberSelector_quantifiers() {
        MemberSelector star = MemberSelector.parse("func_1234_a*");
        assertTrue(star.valid()); assertEquals(0, star.quantifier().min()); assertEquals(Integer.MAX_VALUE, star.quantifier().max());

        MemberSelector plus = MemberSelector.parse("func_1234_a+");
        assertTrue(plus.valid()); assertEquals(1, plus.quantifier().min());

        MemberSelector braced = MemberSelector.parse("func_1234_a{2,5}");
        assertTrue(braced.valid()); assertEquals(2, braced.quantifier().min()); assertEquals(5, braced.quantifier().max());

        MemberSelector exact = MemberSelector.parse("func_1234_a{3}");
        assertTrue(exact.valid()); assertEquals(3, exact.quantifier().min()); assertEquals(3, exact.quantifier().max());

        MemberSelector openHigh = MemberSelector.parse("func_1234_a{2,}");
        assertTrue(openHigh.valid()); assertEquals(2, openHigh.quantifier().min()); assertEquals(Integer.MAX_VALUE, openHigh.quantifier().max());

        MemberSelector openLow = MemberSelector.parse("func_1234_a{,3}");
        assertTrue(openLow.valid()); assertEquals(0, openLow.quantifier().min()); assertEquals(3, openLow.quantifier().max());
    }

    @Test
    void memberSelector_invalidKept() {
        MemberSelector empty = MemberSelector.parse("   ");
        assertFalse(empty.valid()); assertNotNull(empty.error());

        MemberSelector badQuant = MemberSelector.parse("foo{,}");
        // {,} -> min 0 max INF is actually valid per our parser (empty inner -> INF)
        // Try truly bad
        MemberSelector badBrace = MemberSelector.parse("foo{bad}");
        assertFalse(badBrace.valid());

        MemberSelector lNoSemi = MemberSelector.parse("Lnet/minecraft/Foo;bar");
        // This is L-owner without semicolon around correctly? Actually "L...;bar" has semicolon
        // Try without semicolon
        MemberSelector lBad = MemberSelector.parse("Lnet/minecraft/FoomyMethod()V");
        assertFalse(lBad.valid());

        MemberSelector tailBad = MemberSelector.parse("a -> ");
        assertFalse(tailBad.valid());
    }

    @Test
    void memberSelector_tailChain() {
        MemberSelector m = MemberSelector.parse("owner.Foo/bar -> target.Baz()V");
        assertTrue(m.valid(), m.error());
        assertNotNull(m.tail());
        assertEquals("Baz", m.tail().name());
    }

    // ------------------------------------------------------------------ AtSelector

    @Test
    void atSelector_headAndInvokeWithTarget() {
        AtSelector head = AtSelector.parseSimple("HEAD");
        assertTrue(head.valid(), head.error()); assertEquals("HEAD", head.pointId()); assertEquals(AtSelector.Shift.NONE, head.shift());

        AtSelector inv = AtSelector.parse("INVOKE:AFTER", "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/Vec3i;)Lnet/minecraft/world/BlockState;", null, null, null, null, null, null, null, null, null);
        assertTrue(inv.valid(), inv.error()); assertEquals("INVOKE", inv.pointId()); assertEquals(AtSelector.Shift.AFTER, inv.shift());
        assertNotNull(inv.target()); assertTrue(inv.target().valid());
    }

    @Test
    void atSelector_ordinalAndOpcodeAndArgs() {
        AtSelector a = AtSelector.parse("INVOKE", "m()V", 2, 182, List.of("fuzz=50", "ordinal=1"), null, "myId", true, false, null, null);
        assertTrue(a.valid(), a.error()); assertEquals(2, a.ordinal()); assertEquals(182, a.opcode());
        assertEquals("50", a.args().get("fuzz")); assertEquals("myId", a.id());
    }

    @Test
    void atSelector_invalidUnknownPoint() {
        AtSelector bad = AtSelector.parseSimple("BOGUS_POINT");
        assertFalse(bad.valid()); assertNotNull(bad.error());
    }

    @Test
    void atSelector_shiftByBounds() {
        AtSelector ok = AtSelector.parse("INVOKE", null, null, null, null, null, null, null, null, "BY", 3);
        assertTrue(ok.valid()); assertEquals(AtSelector.Shift.BY, ok.shift());
        AtSelector bad = AtSelector.parse("INVOKE", null, null, null, null, null, null, null, null, "BY", 99);
        assertFalse(bad.valid());
    }

    // ------------------------------------------------------------------ MixinSelectorParser via ASM

    private static AnnotationNode ann(String desc, Object... kv) {
        AnnotationNode a = new AnnotationNode(desc);
        a.values = new ArrayList<>();
        for (int i = 0; i < kv.length; i++) a.values.add(kv[i]);
        return a;
    }

    private static AnnotationNode at(String id) {
        return ann("Lorg/spongepowered/asm/mixin/injection/At;", "value", id);
    }

    private static AnnotationNode atWithTarget(String id, String target) {
        return ann("Lorg/spongepowered/asm/mixin/injection/At;", "value", id, "target", target);
    }

    private static ClassNode mixinClass(String internalName) {
        ClassNode n = new ClassNode();
        n.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        n.visibleAnnotations = new ArrayList<>();
        n.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/Mixin;", "targets", List.of("net/minecraft/server/MinecraftServer")));
        return n;
    }

    private static MethodNode methodNode(String name) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.visitMaxs(0, 0);
        return mn;
    }

    @Test
    void parser_injectHead() {
        ClassNode cn = mixinClass("com/example/mixin/TestMixin");
        MethodNode mn = methodNode("handlerHead");
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/injection/Inject;",
                "method", List.of("func_71410_x()V"), "at", List.of(at("HEAD")), "require", 1));
        cn.methods.add(mn);

        List<MixinSelector> sels = MixinSelectorParser.parseClass(cn);
        assertEquals(1, sels.size());
        MixinSelector s = sels.get(0);
        assertEquals(InjectionPointKind.INJECT, s.kind());
        assertEquals(1, s.targets().size()); assertTrue(s.targets().get(0).valid());
        assertEquals(1, s.atSelectors().size()); assertTrue(s.atSelectors().get(0).isHead());
        assertEquals(1, s.require()); assertTrue(s.allSelectorsValid());
    }

    @Test
    void parser_redirectInvokeWithFieldTarget() {
        ClassNode cn = mixinClass("com/example/mixin/RedirectMixin");
        MethodNode mn = methodNode("redirectField");
        mn.visibleAnnotations = new ArrayList<>();
        AnnotationNode at = atWithTarget("FIELD", "Lnet/minecraft/world/World;field_1234_foo:I");
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/injection/Redirect;",
                "method", List.of("method_1234_bar()V"), "at", at));
        cn.methods.add(mn);

        List<MixinSelector> sels = MixinSelectorParser.parseClass(cn);
        assertEquals(1, sels.size());
        MixinSelector s = sels.get(0);
        assertEquals(InjectionPointKind.REDIRECT, s.kind());
        assertEquals("FIELD", s.atSelectors().get(0).pointId());
        assertNotNull(s.atSelectors().get(0).target()); assertTrue(s.atSelectors().get(0).target().isField());
    }

    @Test
    void parser_invalidSelectorKeptNotDropped() {
        ClassNode cn = mixinClass("com/example/mixin/BadMixin");
        MethodNode mn = methodNode("badHandler");
        // target with bad L-owner (no semicolon) — parser keeps invalid
        AnnotationNode at = atWithTarget("INVOKE", "Lnet/minecraft/BadTargetWithoutSemiMethod()V");
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/injection/Inject;",
                "method", List.of("not a valid selector!!!"), "at", List.of(at)));
        cn.methods.add(mn);

        List<MixinSelector> sels = MixinSelectorParser.parseClass(cn);
        assertEquals(1, sels.size());
        assertFalse(sels.get(0).allSelectorsValid());
        assertFalse(sels.get(0).invalidReasons().isEmpty());
    }

    @Test
    void parser_modifyArgAndSlice() {
        ClassNode cn = mixinClass("com/example/mixin/SliceMixin");
        MethodNode mn = methodNode("modify");
        AnnotationNode slice = ann("Lorg/spongepowered/asm/mixin/injection/Slice;",
                "from", at("HEAD"), "to", atWithTarget("INVOKE", "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/Vec3i;)Lnet/minecraft/world/BlockState;"), "id", "mySlice");
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/injection/ModifyArg;",
                "method", List.of("method_9999_baz(I)V"), "at", atWithTarget("INVOKE", "Lnet/minecraft/world/World;setBlockState(Lnet/minecraft/util/Vec3i;)Z"), "slice", slice));
        cn.methods.add(mn);

        List<MixinSelector> sels = MixinSelectorParser.parseClass(cn);
        assertEquals(1, sels.size());
        MixinSelector s = sels.get(0);
        assertEquals(InjectionPointKind.MODIFY_ARG, s.kind());
        assertEquals(1, s.slices().size()); assertEquals("mySlice", s.slices().get(0).id());
        assertNotNull(s.slices().get(0).from()); assertNotNull(s.slices().get(0).to());
    }

    @Test
    void parser_refmapStatusAndSelectorsWiredIntoInventory() throws Exception {
        ClassNode cn = mixinClass("com/example/mixin/WiredMixin");
        MethodNode mn = methodNode("onTick");
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/injection/Inject;",
                "method", List.of("func_71410_x()V"), "at", List.of(at("HEAD")), "require", 1));
        cn.methods.add(mn);
        byte[] cls = toBytes(cn);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("umb-wired");
        java.nio.file.Path jar = dir.resolve("wired.jar");
        try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(java.nio.file.Files.newOutputStream(jar))) {
            out.putNextEntry(new java.util.jar.JarEntry("fabric.mod.json"));
            out.write(("{\"schemaVersion\":1,\"id\":\"wired\",\"version\":\"0.1.0\",\"mixins\":[\"wired.mixins.json\"]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.jar.JarEntry("wired.mixins.json"));
            out.write(("{\"package\":\"com.example.mixin\",\"refmap\":\"wired.refmap.json\",\"mixins\":[\"WiredMixin\"]}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.jar.JarEntry("wired.refmap.json"));
            out.write(("{\"mappings\":{\"com/example/mixin/WiredMixin\":{\"func_71410_x\":\"method_1234_foo\"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
            out.putNextEntry(new java.util.jar.JarEntry("com/example/mixin/WiredMixin.class"));
            out.write(cls); out.closeEntry();
        }
        ModAnalysis a = new BasicModAnalyzer().analyze(jar);
        ModAnalysis.MixinConfigInventory cfg = a.mixinInventory().configs().get(0);
        assertEquals(Refmap.RefmapStatus.PRESENT, cfg.refmapStatus());
        assertEquals(1, cfg.refmapEntryCount());
        ModAnalysis.MixinClassInventory ci = cfg.mixins().get(0);
        assertEquals(1, ci.selectors().size());
        MixinSelector sel = ci.selectors().get(0);
        assertEquals(InjectionPointKind.INJECT, sel.kind());
        assertEquals(1, sel.targets().size());
        assertTrue(sel.allSelectorsValid());
    }

    private static byte[] toBytes(ClassNode n) {
        org.objectweb.asm.ClassWriter cw = new org.objectweb.asm.ClassWriter(0);
        n.accept(cw);
        return cw.toByteArray();
    }

    @Test
    void parser_customInjectionPointDottedIdKeepsValid() {
        ClassNode cn = mixinClass("com/example/mixin/CustomMixin");
        MethodNode mn = methodNode("customHandler");
        mn.visibleAnnotations = new ArrayList<>();
        mn.visibleAnnotations.add(ann("Lorg/spongepowered/asm/mixin/injection/Inject;",
                "method", List.of("handler()V"), "at", List.of(at("com.example.CustomPoint:BEFORE"))));
        cn.methods.add(mn);
        List<MixinSelector> sels = MixinSelectorParser.parseClass(cn);
        assertEquals(1, sels.size());
        assertTrue(sels.get(0).atSelectors().get(0).valid());
        assertEquals("com.example.CustomPoint", sels.get(0).atSelectors().get(0).pointId());
    }
}
