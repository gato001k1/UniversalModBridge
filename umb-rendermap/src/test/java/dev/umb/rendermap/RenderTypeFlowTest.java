package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RenderTypeFlow} against synthetic bytecode reproducing the EXACT dispatch shapes found in
 * the real HBM jar (javap survey over all 21 renderItem-bearing renderer classes: 15 $SwitchMap
 * switches, 2 if_acmp guards, 4 no dispatch, 0 anything else — see the class javadoc):
 * <ul>
 *   <li>the if_acmp guard pair is {@code ItemRenderBlock.renderItem} verbatim,</li>
 *   <li>the tableswitch-with-fall-through is {@code ItemRenderMissilePart.renderItem} (cases 1,2
 *       fall through into case 3's tail — the shared tail must get the UNION),</li>
 *   <li>the $SwitchMap companion {@code <clinit>} is {@code ItemRenderMissilePart$1} verbatim
 *       (EQUIPPED-&gt;1, EQUIPPED_FIRST_PERSON-&gt;2, ENTITY-&gt;3, INVENTORY-&gt;4).</li>
 * </ul>
 */
class RenderTypeFlowTest implements Opcodes {

    private static final String ENUM = RenderTypeFlow.ENUM_INTERNAL;
    private static final String ENUM_D = RenderTypeFlow.ENUM_DESC;
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String RENDER_ITEM_DESC =
            "(" + ENUM_D + "Lnet/minecraft/item/ItemStack;[Ljava/lang/Object;)V";

    /** V1_5 + COMPUTE_MAXS: branchy synthetic code without stack-map frames (never loaded, only
     *  parsed back into a ClassNode — same approach as {@code MethodSimTest}'s fixtures). */
    private static ClassNode genRenderer(String name, Consumer<MethodVisitor> renderItemBody) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_5, ACC_PUBLIC, name, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "renderItem", RENDER_ITEM_DESC, null, null);
        mv.visitCode();
        renderItemBody.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    /** The javac companion class: {@code static final int[] $SwitchMap$...$ItemRenderType} plus a
     *  {@code <clinit>} storing {@code $SwitchMap[CONST.ordinal()] = k} per mapping — the same
     *  shape javap shows for {@code ItemRenderMissilePart$1} (minus the NoSuchFieldError guards,
     *  which {@link RenderTypeFlow#decodeSwitchMap}'s per-IASTORE walk-back never looks at). */
    private static ClassNode genCompanion(String name, String[][] constToKey) {
        String field = "$SwitchMap$net$minecraftforge$client$IItemRenderer$ItemRenderType";
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_5, 0, name, null, "java/lang/Object", null);
        cw.visitField(ACC_STATIC | ACC_FINAL, field, "[I", null, null).visitEnd();
        MethodVisitor mv = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        mv.visitCode();
        mv.visitIntInsn(BIPUSH, 16);
        mv.visitIntInsn(NEWARRAY, T_INT);
        mv.visitFieldInsn(PUTSTATIC, name, field, "[I");
        for (String[] pair : constToKey) {
            mv.visitFieldInsn(GETSTATIC, name, field, "[I");
            mv.visitFieldInsn(GETSTATIC, ENUM, pair[0], ENUM_D);
            mv.visitMethodInsn(INVOKEVIRTUAL, ENUM, "ordinal", "()I", false);
            mv.visitIntInsn(BIPUSH, Integer.parseInt(pair[1]));
            mv.visitInsn(IASTORE);
        }
        mv.visitInsn(RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    private static List<RendererTransformExtractor.TransformRecord> extract(ClassNode cn, JarIndex jar) {
        return RendererTransformExtractor.extractTransforms(cn, jar);
    }

    private static org.objectweb.asm.tree.MethodNode method(ClassNode cn, String name) {
        for (org.objectweb.asm.tree.MethodNode m : cn.methods) {
            if (name.equals(m.name)) return m;
        }
        throw new AssertionError("no method " + name + " in " + cn.name);
    }

    private static RendererTransformExtractor.TransformRecord only(
            List<RendererTransformExtractor.TransformRecord> recs, String op) {
        RendererTransformExtractor.TransformRecord found = null;
        for (RendererTransformExtractor.TransformRecord r : recs) {
            if (op.equals(r.op)) {
                assertNull(found, "expected exactly one " + op + " record");
                found = r;
            }
        }
        assertTrue(found != null, "no " + op + " record extracted");
        return found;
    }

    // ------------------------------------------------------------------ the if_acmp guard idiom

    @Test
    void acmpGuardPairAttributesTheGuardedOpAndLeavesTheSharedTailUnattributed() {
        // ItemRenderBlock.renderItem verbatim: if (type==EQUIPPED || type==EQUIPPED_FIRST_PERSON)
        // glTranslatef(0.5,0.5,0.5); then shared code for every type.
        ClassNode cn = genRenderer("test/AcmpGuard", mv -> {
            Label doIt = new Label(), after = new Label();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, ENUM, "EQUIPPED", ENUM_D);
            mv.visitJumpInsn(IF_ACMPEQ, doIt);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, ENUM, "EQUIPPED_FIRST_PERSON", ENUM_D);
            mv.visitJumpInsn(IF_ACMPNE, after);
            mv.visitLabel(doIt);
            mv.visitLdcInsn(0.5f);
            mv.visitLdcInsn(0.5f);
            mv.visitLdcInsn(0.5f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glTranslatef", "(FFF)V", false);
            mv.visitLabel(after);
            mv.visitLdcInsn(2.0f);
            mv.visitLdcInsn(2.0f);
            mv.visitLdcInsn(2.0f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);
        });
        List<RendererTransformExtractor.TransformRecord> recs = extract(cn, null);
        assertEquals(List.of("EQUIPPED", "EQUIPPED_FIRST_PERSON"), only(recs, "glTranslatef").renderTypes);
        assertNull(only(recs, "glScalef").renderTypes, "shared tail runs under ALL types - no attribution");
    }

    @Test
    void acmpEqOnlyBranchAttributesExactlyThatConstantAndItsComplement() {
        // if (type != INVENTORY) glScalef(...) else fall to glRotatef(...) - i.e. IF_ACMPEQ skips.
        ClassNode cn = genRenderer("test/AcmpSingle", mv -> {
            Label inv = new Label(), end = new Label();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, ENUM, "INVENTORY", ENUM_D);
            mv.visitJumpInsn(IF_ACMPEQ, inv);
            mv.visitLdcInsn(2.0f);
            mv.visitLdcInsn(2.0f);
            mv.visitLdcInsn(2.0f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(inv);
            mv.visitLdcInsn(45.0f);
            mv.visitLdcInsn(0.0f);
            mv.visitLdcInsn(1.0f);
            mv.visitLdcInsn(0.0f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotatef", "(FFFF)V", false);
            mv.visitLabel(end);
        });
        List<RendererTransformExtractor.TransformRecord> recs = extract(cn, null);
        assertEquals(List.of("INVENTORY"), only(recs, "glRotatef").renderTypes);
        assertEquals(List.of("ENTITY", "EQUIPPED", "EQUIPPED_FIRST_PERSON", "FIRST_PERSON_MAP"),
                only(recs, "glScalef").renderTypes);
    }

    // ------------------------------------------------------ the $SwitchMap tableswitch idiom

    /** Case layout copied from ItemRenderMissilePart: 1,2 -> L12 (falls through into L3), 3 -> L3,
     *  4 -> L4, default -> merge. */
    private static ClassNode missilePartShaped(String rendererName, String companionName) {
        String field = "$SwitchMap$net$minecraftforge$client$IItemRenderer$ItemRenderType";
        return genRenderer(rendererName, mv -> {
            Label l12 = new Label(), l3 = new Label(), l4 = new Label(), merge = new Label();
            mv.visitFieldInsn(GETSTATIC, companionName, field, "[I");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, ENUM, "ordinal", "()I", false);
            mv.visitInsn(IALOAD);
            mv.visitTableSwitchInsn(1, 4, merge, l12, l12, l3, l4);
            mv.visitLabel(l12);
            mv.visitLdcInsn(0.5);
            mv.visitInsn(DCONST_0);
            mv.visitInsn(DCONST_0);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glTranslated", "(DDD)V", false);
            mv.visitLabel(l3); // fall-through: cases 1,2 continue into case 3's tail
            mv.visitLdcInsn(0.4);
            mv.visitLdcInsn(0.4);
            mv.visitLdcInsn(0.4);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScaled", "(DDD)V", false);
            mv.visitJumpInsn(GOTO, merge);
            mv.visitLabel(l4);
            mv.visitLdcInsn(135.0);
            mv.visitInsn(DCONST_0);
            mv.visitInsn(DCONST_0);
            mv.visitInsn(DCONST_1);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotated", "(DDDD)V", false);
            mv.visitJumpInsn(GOTO, merge);
            mv.visitLabel(merge);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glPopMatrix", "()V", false);
        });
    }

    @Test
    void switchMapDispatchAttributesCasesAndFallThroughGetsTheUnion() {
        ClassNode companion = genCompanion("test/MissileShaped$1", new String[][]{
                {"EQUIPPED", "1"}, {"EQUIPPED_FIRST_PERSON", "2"}, {"ENTITY", "3"}, {"INVENTORY", "4"},
        });
        ClassNode cn = missilePartShaped("test/MissileShaped", "test/MissileShaped$1");
        List<RendererTransformExtractor.TransformRecord> recs = extract(cn, TestAsm.jarOf(cn, companion));

        assertEquals(List.of("EQUIPPED", "EQUIPPED_FIRST_PERSON"), only(recs, "glTranslated").renderTypes);
        // the fall-through tail is reachable from cases 1,2 AND its own case 3 = ENTITY
        assertEquals(List.of("ENTITY", "EQUIPPED", "EQUIPPED_FIRST_PERSON"), only(recs, "glScaled").renderTypes);
        assertEquals(List.of("INVENTORY"), only(recs, "glRotated").renderTypes);
        assertNull(only(recs, "glPopMatrix").renderTypes, "merge point reachable under every type");
    }

    @Test
    void switchMapWithMissingCompanionLeavesEveryOpUnattributedInsteadOfGuessing() {
        ClassNode cn = missilePartShaped("test/NoCompanion", "test/NoCompanion$1");
        // jar does NOT contain the companion class
        List<RendererTransformExtractor.TransformRecord> recs = extract(cn, TestAsm.jarOf(cn));
        for (RendererTransformExtractor.TransformRecord r : recs) {
            assertNull(r.renderTypes, r.op + " must stay unattributed when the $SwitchMap is undecodable");
        }
        RenderTypeFlow.Result flow = RenderTypeFlow.analyze(cn, method(cn, "renderItem"), TestAsm.jarOf(cn));
        assertTrue(flow.undecodableSwitch, "the undecodable switch must be visible to the caller");
    }

    @Test
    void directOrdinalSwitchUsesTheBytecodeVerifiedConstantOrder() {
        // switch(type.ordinal()) with no $SwitchMap remap: case 3 IS INVENTORY (javap-verified
        // order on the real forge-1.7.10 universal jar). Zero HBM occurrences; kept for other mods.
        ClassNode cn = genRenderer("test/DirectOrdinal", mv -> {
            Label inv = new Label(), merge = new Label();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, ENUM, "ordinal", "()I", false);
            mv.visitTableSwitchInsn(3, 3, merge, inv);
            mv.visitLabel(inv);
            mv.visitLdcInsn(3.0f);
            mv.visitLdcInsn(3.0f);
            mv.visitLdcInsn(3.0f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);
            mv.visitLabel(merge);
        });
        List<RendererTransformExtractor.TransformRecord> recs = extract(cn, null);
        assertEquals(List.of("INVENTORY"), only(recs, "glScalef").renderTypes);
    }

    @Test
    void defaultBranchGetsTheComplementOfEveryMappedCase() {
        ClassNode companion = genCompanion("test/DefaultComp$1", new String[][]{
                {"EQUIPPED", "1"}, {"INVENTORY", "2"},
        });
        String field = "$SwitchMap$net$minecraftforge$client$IItemRenderer$ItemRenderType";
        ClassNode cn = genRenderer("test/DefaultComp", mv -> {
            Label c1 = new Label(), dflt = new Label(), merge = new Label();
            mv.visitFieldInsn(GETSTATIC, "test/DefaultComp$1", field, "[I");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, ENUM, "ordinal", "()I", false);
            mv.visitInsn(IALOAD);
            mv.visitTableSwitchInsn(1, 2, dflt, c1, c1);
            mv.visitLabel(c1);
            mv.visitJumpInsn(GOTO, merge);
            mv.visitLabel(dflt);
            mv.visitLdcInsn(0.25f);
            mv.visitLdcInsn(0.25f);
            mv.visitLdcInsn(0.25f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);
            mv.visitLabel(merge);
        });
        List<RendererTransformExtractor.TransformRecord> recs = extract(cn, TestAsm.jarOf(cn, companion));
        assertEquals(List.of("ENTITY", "EQUIPPED_FIRST_PERSON", "FIRST_PERSON_MAP"),
                only(recs, "glScalef").renderTypes);
    }

    // -------------------------------------------------------------------------- honest skips

    @Test
    void methodWithoutTheEnumParamGetsNoAttributionAtAll() {
        ClassNode cn = TestAsm.classWithMethod("test/NoParam", null, null, "renderCommon", "()V",
                ACC_PUBLIC, mv -> {
                    mv.visitLdcInsn(0.5f);
                    mv.visitLdcInsn(0.5f);
                    mv.visitLdcInsn(0.5f);
                    mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);
                    mv.visitInsn(RETURN);
                });
        List<RendererTransformExtractor.TransformRecord> recs = extract(cn, null);
        assertNull(only(recs, "glScalef").renderTypes);
        assertEquals("no-type-param",
                RenderTypeFlow.analyze(cn, method(cn, "renderCommon"), null).skipped);
    }

    @Test
    void reassignedTypeParamAbortsAttributionForTheWholeMethod() {
        ClassNode cn = genRenderer("test/Reassign", mv -> {
            Label after = new Label();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitFieldInsn(GETSTATIC, ENUM, "INVENTORY", ENUM_D);
            mv.visitJumpInsn(IF_ACMPNE, after);
            mv.visitLdcInsn(3.0f);
            mv.visitLdcInsn(3.0f);
            mv.visitLdcInsn(3.0f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);
            mv.visitLabel(after);
            mv.visitInsn(ACONST_NULL);
            mv.visitVarInsn(ASTORE, 1); // slot 1 is the type param - anything downstream is untrusted
        });
        List<RendererTransformExtractor.TransformRecord> recs = extract(cn, null);
        assertNull(only(recs, "glScalef").renderTypes,
                "a reassigned type param means NO op in the method may claim attribution");
        assertEquals("param-reassigned",
                RenderTypeFlow.analyze(cn, method(cn, "renderItem"), null).skipped);
    }

    @Test
    void preDispatchOpsStayUnattributed() {
        // glPushMatrix BEFORE the dispatch executes under every type - must stay bare even though
        // the method dispatches right after (ItemRenderMissilePart does exactly this at insn 8).
        ClassNode companion = genCompanion("test/PreDispatch$1", new String[][]{{"INVENTORY", "1"}});
        String field = "$SwitchMap$net$minecraftforge$client$IItemRenderer$ItemRenderType";
        ClassNode cn = genRenderer("test/PreDispatch", mv -> {
            Label c1 = new Label(), merge = new Label();
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glPushMatrix", "()V", false);
            mv.visitFieldInsn(GETSTATIC, "test/PreDispatch$1", field, "[I");
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, ENUM, "ordinal", "()I", false);
            mv.visitInsn(IALOAD);
            mv.visitTableSwitchInsn(1, 1, merge, c1);
            mv.visitLabel(c1);
            mv.visitLdcInsn(4.0f);
            mv.visitLdcInsn(4.0f);
            mv.visitLdcInsn(4.0f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);
            mv.visitLabel(merge);
        });
        List<RendererTransformExtractor.TransformRecord> recs = extract(cn, TestAsm.jarOf(cn, companion));
        assertNull(only(recs, "glPushMatrix").renderTypes);
        assertEquals(List.of("INVENTORY"), only(recs, "glScalef").renderTypes);
    }
}
