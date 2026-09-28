package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the three dynamic-row resolver families against small synthetic classes built with
 * ASM, mirroring the exact bytecode shapes read out of the real jar (see the class javadoc on
 * {@link DynamicVariantResolver}): {@code EnumBatteryPack} (enum-instance-field-table),
 * {@code ItemAmmoHIMARS.itemTypes} (array-instance-field-table via a forwarding anonymous
 * subclass), and {@code ItemRenderLibrary$77} (a plain damage==0 branch).
 */
class DynamicVariantResolverTest implements Opcodes {

    private static ClassNode classOf(String name, String superName, Consumer<ClassWriter> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, name, null, superName, null);
        body.accept(cw);
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    private static void method(ClassWriter cw, int access, String name, String desc, Consumer<MethodVisitor> body) {
        MethodVisitor mv = cw.visitMethod(access, name, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitMaxs(16, 16);
        mv.visitEnd();
    }

    // ==================================================================== Family A: enum table

    private static final String FE = "test/FakeEnum";

    private ClassNode fakeEnum() {
        return classOf(FE, "java/lang/Object", cw -> {
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "A", "L" + FE + ";", null, null);
            cw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL, "B", "L" + FE + ";", null, null);
            cw.visitField(ACC_PUBLIC, "texture", "Lnet/minecraft/util/ResourceLocation;", null, null);

            // <init>(String name, int ordinal, String key)
            method(cw, ACC_PUBLIC, "<init>", "(Ljava/lang/String;ILjava/lang/String;)V", mv -> {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                // this.texture = new ResourceLocation("hbm", new StringBuilder().append("textures/x_").append(key).append(".png").toString());
                mv.visitVarInsn(ALOAD, 0);
                mv.visitTypeInsn(NEW, "net/minecraft/util/ResourceLocation");
                mv.visitInsn(DUP);
                mv.visitLdcInsn("hbm");
                mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                mv.visitLdcInsn("textures/x_");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitVarInsn(ALOAD, 3);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn(".png");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/util/ResourceLocation", "<init>",
                        "(Ljava/lang/String;Ljava/lang/String;)V", false);
                mv.visitFieldInsn(PUTFIELD, FE, "texture", "Lnet/minecraft/util/ResourceLocation;");
                mv.visitInsn(RETURN);
            });

            method(cw, ACC_PUBLIC, "ordinal", "()I", mv -> { mv.visitInsn(ICONST_0); mv.visitInsn(IRETURN); });

            // isHigh(): return this.ordinal() > A.ordinal();
            method(cw, ACC_PUBLIC, "isHigh", "()Z", mv -> {
                Label lFalse = new Label(), end = new Label();
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKEVIRTUAL, FE, "ordinal", "()I", false);
                mv.visitFieldInsn(GETSTATIC, FE, "A", "L" + FE + ";");
                mv.visitMethodInsn(INVOKEVIRTUAL, FE, "ordinal", "()I", false);
                mv.visitJumpInsn(IF_ICMPLE, lFalse);
                mv.visitInsn(ICONST_1);
                mv.visitJumpInsn(GOTO, end);
                mv.visitLabel(lFalse);
                mv.visitInsn(ICONST_0);
                mv.visitLabel(end);
                mv.visitInsn(IRETURN);
            });

            // <clinit>: A = new FakeEnum("A",0,"aa"); B = new FakeEnum("B",1,"bb");
            method(cw, ACC_STATIC, "<clinit>", "()V", mv -> {
                mv.visitTypeInsn(NEW, FE);
                mv.visitInsn(DUP);
                mv.visitLdcInsn("A");
                mv.visitInsn(ICONST_0);
                mv.visitLdcInsn("aa");
                mv.visitMethodInsn(INVOKESPECIAL, FE, "<init>", "(Ljava/lang/String;ILjava/lang/String;)V", false);
                mv.visitFieldInsn(PUTSTATIC, FE, "A", "L" + FE + ";");
                mv.visitTypeInsn(NEW, FE);
                mv.visitInsn(DUP);
                mv.visitLdcInsn("B");
                mv.visitInsn(ICONST_1);
                mv.visitLdcInsn("bb");
                mv.visitMethodInsn(INVOKESPECIAL, FE, "<init>", "(Ljava/lang/String;ILjava/lang/String;)V", false);
                mv.visitFieldInsn(PUTSTATIC, FE, "B", "L" + FE + ";");
                mv.visitInsn(RETURN);
            });
        });
    }

    @Test
    void readsEveryEnumConstantInOrdinalOrderWithItsCtorArgs() {
        ClassNode cn = fakeEnum();
        List<DynamicVariantResolver.EnumConst> consts = DynamicVariantResolver.readEnumClinit(cn);
        assertEquals(2, consts.size());
        assertEquals("A", consts.get(0).name);
        assertEquals(0, consts.get(0).ordinal);
        assertEquals("B", consts.get(1).name);
        assertEquals(1, consts.get(1).ordinal);
        assertEquals("bb", consts.get(1).ctorArgs.get(2).stringValue);
    }

    @Test
    void resolvesTheStringBuilderBuiltTextureFieldPerConstant() {
        ClassNode cn = fakeEnum();
        List<DynamicVariantResolver.EnumConst> consts = DynamicVariantResolver.readEnumClinit(cn);
        Val texA = DynamicVariantResolver.resolveInstanceField(cn, consts.get(0), "texture");
        Val texB = DynamicVariantResolver.resolveInstanceField(cn, consts.get(1), "texture");
        HolderResolver holders = new HolderResolver(new JarIndex());
        assertEquals("hbm:textures/x_aa.png", holders.asResourceLocation(texA));
        assertEquals("hbm:textures/x_bb.png", holders.asResourceLocation(texB));
    }

    @Test
    void evaluatesTheOrdinalThresholdPredicatePerConstant() {
        ClassNode cn = fakeEnum();
        List<DynamicVariantResolver.EnumConst> consts = DynamicVariantResolver.readEnumClinit(cn);
        Map<String, Integer> nameToOrd = Map.of("A", 0, "B", 1);
        assertEquals(Boolean.FALSE, DynamicVariantResolver.evalOrdinalThresholdPredicate(cn, "isHigh", nameToOrd, 0));
        assertEquals(Boolean.TRUE, DynamicVariantResolver.evalOrdinalThresholdPredicate(cn, "isHigh", nameToOrd, 1));
    }

    // ================================================================== Family B: array table

    private static final String TARGET = "test/Elem";
    private static final String SUB = "test/ElemSub";
    private static final String OWNER = "test/Owner";

    private ClassNode targetElem() {
        return classOf(TARGET, "java/lang/Object", cw -> {
            cw.visitField(ACC_PUBLIC, "texture", "Lnet/minecraft/util/ResourceLocation;", null, null);
            cw.visitField(ACC_PUBLIC, "modelType", "I", null, null);
            method(cw, ACC_PUBLIC, "<init>", "(Ljava/lang/String;I)V", mv -> {
                mv.visitVarInsn(ALOAD, 0);
                mv.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitVarInsn(ALOAD, 0);
                mv.visitTypeInsn(NEW, "net/minecraft/util/ResourceLocation");
                mv.visitInsn(DUP);
                mv.visitLdcInsn("hbm");
                mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
                mv.visitInsn(DUP);
                mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
                mv.visitLdcInsn("textures/projectiles/");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitVarInsn(ALOAD, 1);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitLdcInsn(".png");
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/util/ResourceLocation", "<init>",
                        "(Ljava/lang/String;Ljava/lang/String;)V", false);
                mv.visitFieldInsn(PUTFIELD, TARGET, "texture", "Lnet/minecraft/util/ResourceLocation;");
                mv.visitVarInsn(ALOAD, 0);
                mv.visitVarInsn(ILOAD, 2);
                mv.visitFieldInsn(PUTFIELD, TARGET, "modelType", "I");
                mv.visitInsn(RETURN);
            });
        });
    }

    private ClassNode forwardingSubclass() {
        return classOf(SUB, TARGET, cw -> method(cw, ACC_PUBLIC, "<init>", "(Ljava/lang/String;I)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitMethodInsn(INVOKESPECIAL, TARGET, "<init>", "(Ljava/lang/String;I)V", false);
            mv.visitInsn(RETURN);
        }));
    }

    private ClassNode owner() {
        return classOf(OWNER, "java/lang/Object", cw -> {
            cw.visitField(ACC_PUBLIC | ACC_STATIC, "arr", "[L" + TARGET + ";", null, null);
            method(cw, ACC_PUBLIC | ACC_STATIC, "init", "()V", mv -> {
                mv.visitFieldInsn(GETSTATIC, OWNER, "arr", "[L" + TARGET + ";");
                mv.visitInsn(ICONST_0);
                mv.visitTypeInsn(NEW, SUB);
                mv.visitInsn(DUP);
                mv.visitLdcInsn("standard");
                mv.visitInsn(ICONST_0);
                mv.visitMethodInsn(INVOKESPECIAL, SUB, "<init>", "(Ljava/lang/String;I)V", false);
                mv.visitInsn(AASTORE);
                mv.visitFieldInsn(GETSTATIC, OWNER, "arr", "[L" + TARGET + ";");
                mv.visitInsn(ICONST_1);
                mv.visitTypeInsn(NEW, SUB);
                mv.visitInsn(DUP);
                mv.visitLdcInsn("single");
                mv.visitInsn(ICONST_1);
                mv.visitMethodInsn(INVOKESPECIAL, SUB, "<init>", "(Ljava/lang/String;I)V", false);
                mv.visitInsn(AASTORE);
                mv.visitInsn(RETURN);
            });
        });
    }

    @Test
    void readsEveryArrayStoreSiteWithItsElementAndCtorArgs() {
        ClassNode ownerCn = owner();
        List<DynamicVariantResolver.ArrayEntry> entries = DynamicVariantResolver.readArrayPopulation(ownerCn, "arr");
        assertEquals(2, entries.size());
        assertEquals(SUB, entries.get(0).value.typeName);
        assertEquals("standard", entries.get(0).value.ctorArgs.get(0).stringValue);
        assertEquals(1, entries.get(1).index);
    }

    @Test
    void verifiesTheAnonymousSubclassForwardsEveryArgumentUnchanged() {
        ClassNode subCn = forwardingSubclass();
        MethodNode subInit = subCn.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();
        assertTrue(DynamicVariantResolver.isTrivialForwardingCtor(subCn, subInit.desc, TARGET, "(Ljava/lang/String;I)V"));
    }

    @Test
    void resolvesTextureAndModelTypeThroughTheForwardingSubclass() {
        ClassNode ownerCn = owner();
        ClassNode targetCn = targetElem();
        List<DynamicVariantResolver.ArrayEntry> entries = DynamicVariantResolver.readArrayPopulation(ownerCn, "arr");
        MethodNode targetInit = targetCn.methods.stream().filter(m -> m.name.equals("<init>")).findFirst().orElseThrow();

        DynamicVariantResolver.ArrayEntry e0 = entries.get(0); // "standard", modelType 0
        Val tex0 = DynamicVariantResolver.runInitAndCapture(targetCn, targetInit, e0.value.ctorArgs, "texture");
        Val mt0 = DynamicVariantResolver.runInitAndCapture(targetCn, targetInit, e0.value.ctorArgs, "modelType");
        HolderResolver holders = new HolderResolver(new JarIndex());
        assertEquals("hbm:textures/projectiles/standard.png", holders.asResourceLocation(tex0));
        assertEquals(0, mt0.numberValue.intValue());

        DynamicVariantResolver.ArrayEntry e1 = entries.get(1); // "single", modelType 1
        Val tex1 = DynamicVariantResolver.runInitAndCapture(targetCn, targetInit, e1.value.ctorArgs, "texture");
        Val mt1 = DynamicVariantResolver.runInitAndCapture(targetCn, targetInit, e1.value.ctorArgs, "modelType");
        assertEquals("hbm:textures/projectiles/single.png", holders.asResourceLocation(tex1));
        assertEquals(1, mt1.numberValue.intValue());
    }

    // ============================================================ Family C: damage==0 branch

    @Test
    void findsTheDamageZeroTwoWayTextureBranch() {
        String r = "test/Renderer";
        ClassNode cn = classOf(r, "java/lang/Object", cw -> method(cw, ACC_PUBLIC, "renderCommonWithStack",
                "(Lnet/minecraft/item/ItemStack;)V", mv -> {
            Label nonZero = new Label(), end = new Label();
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/item/ItemStack", "func_77960_j", "()I", false);
            mv.visitJumpInsn(IFNE, nonZero);
            mv.visitFieldInsn(GETSTATIC, "test/Res", "texA", "Lnet/minecraft/util/ResourceLocation;");
            mv.visitInsn(POP);
            mv.visitJumpInsn(GOTO, end);
            mv.visitLabel(nonZero);
            mv.visitFieldInsn(GETSTATIC, "test/Res", "texB", "Lnet/minecraft/util/ResourceLocation;");
            mv.visitInsn(POP);
            mv.visitLabel(end);
            mv.visitInsn(RETURN);
        }));
        DynamicVariantResolver.DamageZeroBranch br = DynamicVariantResolver.findDamageZeroTextureBranch(
                cn, "Lnet/minecraft/util/ResourceLocation;");
        assertTrue(br.matched);
        assertEquals("texA", br.zeroBranchField.name);
        assertEquals("texB", br.nonZeroBranchField.name);
    }

    @Test
    void reportsNoMatchWhenThereIsNoDamageBranchAtAll() {
        String r = "test/RendererNoBranch";
        ClassNode cn = classOf(r, "java/lang/Object", cw -> method(cw, ACC_PUBLIC, "renderCommonWithStack",
                "(Lnet/minecraft/item/ItemStack;)V", mv -> mv.visitInsn(RETURN)));
        assertFalse(DynamicVariantResolver.findDamageZeroTextureBranch(cn, "Lnet/minecraft/util/ResourceLocation;").matched);
    }

    // ================================================================= VariantIdRule (mirrors LegacyIds)

    @Test
    void readableIdsAreUsedWhenEverySubItemHasADistinctUnlocalizedName() {
        List<VariantIdRule.Sub> subs = List.of(
                new VariantIdRule.Sub(0, "item.battery_pack.battery_redstone"),
                new VariantIdRule.Sub(1, "item.battery_pack.battery_lead"));
        List<String> ids = VariantIdRule.variantIds("hbm:item.battery_pack", "item.battery_pack", subs);
        assertEquals(List.of("hbm:item.battery_pack.battery_redstone", "hbm:item.battery_pack.battery_lead"), ids);
    }

    @Test
    void metaZeroKeepsTheBaseIdWhenNamesAreNotReadable() {
        List<VariantIdRule.Sub> subs = List.of(
                new VariantIdRule.Sub(0, "item.gear_large"),   // same as base -> not readable
                new VariantIdRule.Sub(1, "item.gear_large"));
        List<String> ids = VariantIdRule.variantIds("hbm:item.gear_large", "item.gear_large", subs);
        assertEquals(List.of("hbm:item.gear_large", "hbm:item.gear_large_1"), ids);
    }
}
