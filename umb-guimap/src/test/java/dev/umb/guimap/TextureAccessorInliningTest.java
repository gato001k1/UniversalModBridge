package dev.umb.guimap;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * NOTEXTURE-GAP lane: focused tests for the real, evidenced shape behind 80 of the 87
 * {@code noTextureSkipped} rects (see {@code research/out/legacy/guimap-notes/NOTEXTURE-GAP.md}) —
 * a mod puts its GUI's draw method on a SHARED ancestor class (javap-verified real example: HBM's
 * {@code GUITurretBase}), and each concrete sibling overrides only a zero-arg
 * {@code getTexture(): ResourceLocation}-shaped accessor the shared method calls, returning ITS OWN
 * static field set once in its own {@code <clinit>}. Resolving this needs BOTH:
 * <ol>
 *   <li>{@code tryInlineAccessor} treating an {@code ARETURN} (object-reference return) as
 *   inlineable, not just the four numeric return opcodes; and</li>
 *   <li>a {@code this}-receiver virtual call starting its override search at the CONCRETE class the
 *   scan is for (see {@code MethodSim#selfClassInternal}), not the ancestor whose bytecode happens
 *   to contain the call.</li>
 * </ol>
 * Both are gated behind {@code MethodSim#extendedAccessorInlining}, opt-in ONLY for
 * {@code DrawLayerScanner#classifyTexture}'s one bounded hop — the last test below is the
 * regression guard proving the AUTOMATIC, every-call-site scan (which guard/label classification
 * depends on staying unresolved-and-recognisable-as-an-accessor-hop) is never affected.
 */
class TextureAccessorInliningTest {

    private static final String BASE = "test/notexturegap/GuiSharedBase";
    private static final String SUB = "test/notexturegap/GuiVariantA";
    private static final String SUB_AMBIGUOUS = "test/notexturegap/GuiVariantAmbiguous";
    private static final String RL = "Lnet/minecraft/util/ResourceLocation;";

    private static ClassNode toNode(ClassWriter cw) {
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode m : cn.methods) if (m.name.equals(name) && m.desc.equals(desc)) return m;
        throw new IllegalStateException("method not found: " + name + desc);
    }

    /** The shared ancestor: declares its OWN (never-assigned) {@code texture} field/accessor, and
     *  the vanilla background draw method that binds whatever {@code this.getTexture()} returns —
     *  the exact {@code GUITurretBase} shape (javap-verified, see NOTEXTURE-GAP.md). */
    private static ClassNode baseClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, BASE, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "texture", RL, null, null).visitEnd();

        MethodVisitor getTexture = cw.visitMethod(ACC_PROTECTED, "getTexture", "()" + RL, null, null);
        getTexture.visitCode();
        getTexture.visitFieldInsn(GETSTATIC, BASE, "texture", RL);
        getTexture.visitInsn(ARETURN);
        getTexture.visitMaxs(1, 1);
        getTexture.visitEnd();

        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        bg.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/Minecraft", "func_71410_x",
                "()Lnet/minecraft/client/Minecraft;", false);
        bg.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/Minecraft", "func_110434_K",
                "()L" + Vanilla.TEXTURE_MANAGER + ";", false);
        bg.visitVarInsn(ALOAD, 0);
        bg.visitMethodInsn(INVOKEVIRTUAL, BASE, "getTexture", "()" + RL, false);
        bg.visitMethodInsn(INVOKEVIRTUAL, Vanilla.TEXTURE_MANAGER, Vanilla.M_BIND_TEXTURE_MANAGER,
                "(" + RL + ")V", false);
        bg.visitVarInsn(ALOAD, 0);
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, BASE, Vanilla.F_GUILEFT, "I");
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, BASE, Vanilla.F_GUITOP, "I");
        bg.visitInsn(ICONST_0);
        bg.visitInsn(ICONST_0);
        bg.visitIntInsn(BIPUSH, 18);
        bg.visitIntInsn(BIPUSH, 18);
        bg.visitMethodInsn(INVOKEVIRTUAL, BASE, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        bg.visitInsn(RETURN);
        bg.visitMaxs(12, 4);
        bg.visitEnd();

        cw.visitEnd();
        return toNode(cw);
    }

    /** A concrete sibling: never overrides the draw method (relies entirely on the shared ancestor's
     *  bytecode above), but DOES override {@code getTexture()} to return its OWN static field, set
     *  once in its OWN {@code <clinit>} to a literal ResourceLocation — exactly
     *  {@code GUITurretSentry}'s real, javap-verified shape. */
    private static ClassNode variantClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, SUB, null, BASE, null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "texture", RL, null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, Vanilla.RESOURCE_LOCATION);
        clinit.visitInsn(DUP);
        clinit.visitLdcInsn("modid:textures/gui/variant_a.png");
        clinit.visitMethodInsn(INVOKESPECIAL, Vanilla.RESOURCE_LOCATION, "<init>", "(Ljava/lang/String;)V", false);
        clinit.visitFieldInsn(PUTSTATIC, SUB, "texture", RL);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(4, 0);
        clinit.visitEnd();

        MethodVisitor getTexture = cw.visitMethod(ACC_PROTECTED, "getTexture", "()" + RL, null, null);
        getTexture.visitCode();
        getTexture.visitFieldInsn(GETSTATIC, SUB, "texture", RL);
        getTexture.visitInsn(ARETURN);
        getTexture.visitMaxs(1, 1);
        getTexture.visitEnd();

        cw.visitEnd();
        return toNode(cw);
    }

    /** Same override shape as {@link #variantClass}, except {@code getTexture()} has TWO return
     *  sites (an {@code if}/{@code else} choosing between two static fields) — the "never guesses"
     *  boundary every accessor-inlining mechanism in this codebase shares. */
    private static ClassNode ambiguousVariantClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, SUB_AMBIGUOUS, null, BASE, null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "textureA", RL, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "textureB", RL, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE, "mode", "Z", null, null).visitEnd();

        MethodVisitor getTexture = cw.visitMethod(ACC_PROTECTED, "getTexture", "()" + RL, null, null);
        getTexture.visitCode();
        getTexture.visitVarInsn(ALOAD, 0);
        getTexture.visitFieldInsn(GETFIELD, SUB_AMBIGUOUS, "mode", "Z");
        Label elseLabel = new Label();
        getTexture.visitJumpInsn(IFEQ, elseLabel);
        getTexture.visitFieldInsn(GETSTATIC, SUB_AMBIGUOUS, "textureA", RL);
        getTexture.visitInsn(ARETURN);
        getTexture.visitLabel(elseLabel);
        getTexture.visitFieldInsn(GETSTATIC, SUB_AMBIGUOUS, "textureB", RL);
        getTexture.visitInsn(ARETURN);
        getTexture.visitMaxs(2, 2);
        getTexture.visitEnd();

        cw.visitEnd();
        return toNode(cw);
    }

    private static JarIndex jarWithBaseAndVariant(ClassNode variant, String variantInternalName) {
        JarIndex jar = new JarIndex();
        jar.classes.put(BASE, baseClass());
        jar.classes.put(variantInternalName, variant);
        byte[] png = new byte[24];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, png, 0, 8);
        png[12] = 'I'; png[13] = 'H'; png[14] = 'D'; png[15] = 'R';
        png[19] = 32; png[23] = 32; // 32x32, big-endian width/height at offsets 16-19/20-23
        jar.assets.put("assets/modid/textures/gui/variant_a.png", png);
        return jar;
    }

    @Test
    void virtualDispatchAcrossASharedAncestorDrawMethodResolvesTheOverridingSiblingsOwnTextureConstant() {
        JarIndex jar = jarWithBaseAndVariant(variantClass(), SUB);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, new FieldConstResolver(jar), SUB, null);
        ClassNode baseNode = jar.cls(BASE);
        scanner.scan(baseNode, findMethod(baseNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));

        assertEquals(1, scanner.textureBinds.size());
        JsonObject tex = scanner.textureBinds.get(0);
        assertTrue(tex.get("resolved").getAsBoolean(), "expected the sibling's own texture to resolve: " + tex);
        assertEquals("modid:textures/gui/variant_a.png", tex.get("path").getAsString());
        assertTrue(tex.get("existsInJar").getAsBoolean());
        assertEquals(1, scanner.drawRects.size());
        assertEquals(0, scanner.drawRects.get(0).get("textureBindIndex").getAsInt());
    }

    @Test
    void withoutKnowingTheConcreteSiblingTheSharedAncestorsOwnUnassignedFieldStaysUnresolved() {
        // Same jar/bytecode as above, but guiClassInternal is null (as if the caller genuinely
        // didn't know which concrete class this scan was for) - the walk then starts at BASE (the
        // compile-time owner) exactly as it always did, finds BASE's OWN getTexture()/texture field,
        // which is never assigned anywhere - proves the fix is gated on real knowledge, not a
        // coincidentally-working default.
        JarIndex jar = jarWithBaseAndVariant(variantClass(), SUB);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, new FieldConstResolver(jar), null, null);
        ClassNode baseNode = jar.cls(BASE);
        scanner.scan(baseNode, findMethod(baseNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));

        assertEquals(1, scanner.textureBinds.size());
        assertFalse(scanner.textureBinds.get(0).get("resolved").getAsBoolean());
    }

    @Test
    void classifyTextureNeverGuessesWhenTheAccessorHasMoreThanOneReturnSite() {
        JarIndex jar = jarWithBaseAndVariant(ambiguousVariantClass(), SUB_AMBIGUOUS);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, new FieldConstResolver(jar), SUB_AMBIGUOUS, null);
        ClassNode baseNode = jar.cls(BASE);
        scanner.scan(baseNode, findMethod(baseNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));

        assertEquals(1, scanner.textureBinds.size());
        assertFalse(scanner.textureBinds.get(0).get("resolved").getAsBoolean(),
                "a two-return-site accessor must never be resolved by guessing which branch runs");
    }

    @Test
    void classifyTextureNeverFollowsAnInvokespecialCallEvenWithASelfClassKnown() {
        // Manually-built call-result Val (bypassing bytecode scanning entirely) with invokeOp set to
        // INVOKESPECIAL - the "no dispatch to resolve, owner is already the exact target" case
        // MethodSim#selfClassInternal's own javadoc calls out as ineligible for the runtime-type
        // search, even when a concrete "self" class IS known.
        JarIndex jar = jarWithBaseAndVariant(variantClass(), SUB);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, new FieldConstResolver(jar), SUB, null);
        Val call = Val.unknown("result of GuiSharedBase.getTexture()");
        call.owner = BASE; call.name = "getTexture"; call.desc = "()" + RL;
        call.receiver = Val.thisRef();
        call.invokeOp = Opcodes.INVOKESPECIAL;

        JsonObject result = scanner.classifyTexture(call);
        assertFalse(result.get("resolved").getAsBoolean());
    }

    @Test
    void automaticScanNeverInlinesAnObjectReturningAccessorCallEvenWithExactlyOneReturnSite() {
        // The regression guard: a guard/label consumer elsewhere in this codebase (see
        // DrawLayerScanner#fieldRequirementJson/ExprEval) relies on an accessor call like this
        // staying an UNRESOLVED "result of X.y()" Val when reached through the NORMAL, automatic,
        // every-call-site scan - never silently inlined - so it can instead be recognised afterwards
        // as one accessor HOP in a tile-entity field chain. This must hold even for the EXACT shape
        // (this-receiver, single ARETURN site) that IS deliberately inlined by the separate, opt-in
        // dev.umb.guimap.MethodSim#resolveAccessorForTextureClassification path.
        JarIndex jar = jarWithBaseAndVariant(variantClass(), SUB);
        ClassNode subNode = jar.cls(SUB);

        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, SUB, null, BASE, null);
        MethodVisitor probe = cw.visitMethod(ACC_PUBLIC, "probe", "()V", null, null);
        probe.visitCode();
        probe.visitVarInsn(ALOAD, 0);
        probe.visitMethodInsn(INVOKEVIRTUAL, SUB, "getTexture", "()" + RL, false);
        Label after = new Label();
        probe.visitJumpInsn(IFNULL, after);
        probe.visitLabel(after);
        probe.visitInsn(RETURN);
        probe.visitMaxs(2, 1);
        probe.visitEnd();
        cw.visitEnd();
        ClassNode probeHost = toNode(cw);
        // splice the probe method onto the real jar-registered SUB class node so
        // MethodSim's same-jar lookup (jar.cls(SUB)) finds getTexture() normally.
        subNode.methods.add(findMethod(probeHost, "probe", "()V"));

        List<Val> capturedAtGuard = new java.util.ArrayList<>();
        MethodSim.run(subNode, findMethod(subNode, "probe", "()V"), jar, (insn, stack, locals) -> {
            if (insn.getOpcode() == Opcodes.IFNULL && !stack.isEmpty()) {
                capturedAtGuard.add(stack.get(stack.size() - 1));
            }
        });

        assertEquals(1, capturedAtGuard.size());
        Val v = capturedAtGuard.get(0);
        assertEquals(Val.Kind.UNKNOWN, v.kind, "must stay an unresolved call-result placeholder, never inlined automatically");
        assertTrue(v.reason != null && v.reason.startsWith("result of "), "reason was: " + v.reason);
        assertEquals(Opcodes.INVOKEVIRTUAL, v.invokeOp);
    }
}
