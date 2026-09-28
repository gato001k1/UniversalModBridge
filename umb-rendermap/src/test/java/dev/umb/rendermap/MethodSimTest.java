package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the stack backtracker against tiny methods generated with ASM, so the
 * expected instruction sequence is written out explicitly rather than mined from the mod jar.
 */
class MethodSimTest implements Opcodes {

    private static final String OWNER = "test/Gen";
    private static final String MODITEMS = "com/hbm/items/ModItems";
    private static final String MODBLOCKS = "com/hbm/blocks/ModBlocks";
    private static final String ITEM = "Lnet/minecraft/item/Item;";
    private static final String BLOCK = "Lnet/minecraft/block/Block;";
    private static final String MFC = "net/minecraftforge/client/MinecraftForgeClient";
    private static final String REG_DESC = "(Lnet/minecraft/item/Item;Lnet/minecraftforge/client/IItemRenderer;)V";

    /** Build a class with one static void method whose body the caller emits. */
    private static ClassNode gen(String methodName, String desc, Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, OWNER, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, methodName, desc, null, null);
        mv.visitCode();
        body.accept(mv);
        mv.visitInsn(RETURN);
        mv.visitMaxs(16, 16);
        mv.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    /** Run the simulator and capture the operand stack seen at each call to {@code target}. */
    private static List<List<Val>> stacksAt(ClassNode cn, String target) {
        List<List<Val>> out = new ArrayList<>();
        MethodNode mn = cn.methods.stream().filter(m -> !m.name.equals("<init>")).findFirst().orElseThrow();
        MethodSim.run(cn, mn, (AbstractInsnNode insn, List<Val> stack, java.util.Map<Integer, Val> locals) -> {
            if (insn instanceof MethodInsnNode m && m.name.equals(target)) out.add(new ArrayList<>(stack));
        });
        return out;
    }

    private static Val arg(List<Val> stack, int argc, int index) {
        return stack.get(stack.size() - argc + index);
    }

    @Test
    void resolvesGetstaticItemAndNewRenderer() {
        ClassNode cn = gen("init", "()V", mv -> {
            mv.visitFieldInsn(GETSTATIC, MODITEMS, "gun_darter", ITEM);
            mv.visitTypeInsn(NEW, "com/hbm/render/item/ItemRenderDarter");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "com/hbm/render/item/ItemRenderDarter", "<init>", "()V", false);
            mv.visitMethodInsn(INVOKESTATIC, MFC, "registerItemRenderer", REG_DESC, false);
        });
        List<List<Val>> hits = stacksAt(cn, "registerItemRenderer");
        assertEquals(1, hits.size());
        Val item = arg(hits.get(0), 2, 0), renderer = arg(hits.get(0), 2, 1);
        assertEquals(Val.Kind.STATIC_FIELD, item.kind);
        assertEquals("com/hbm/items/ModItems.gun_darter", item.fieldKey());
        assertEquals(Val.Kind.NEW_OBJ, renderer.kind);
        assertEquals("com/hbm/render/item/ItemRenderDarter", renderer.typeName);
        assertTrue(renderer.ctorArgs.isEmpty());
    }

    @Test
    void capturesConstructorArgumentsIncludingStaticFields() {
        ClassNode cn = gen("init", "()V", mv -> {
            mv.visitFieldInsn(GETSTATIC, MODITEMS, "gun_henry", ITEM);
            mv.visitTypeInsn(NEW, "com/hbm/render/item/ItemRenderHenry");
            mv.visitInsn(DUP);
            mv.visitFieldInsn(GETSTATIC, "com/hbm/main/ResourceManager", "henry_tex",
                    "Lnet/minecraft/util/ResourceLocation;");
            mv.visitMethodInsn(INVOKESPECIAL, "com/hbm/render/item/ItemRenderHenry", "<init>",
                    "(Lnet/minecraft/util/ResourceLocation;)V", false);
            mv.visitMethodInsn(INVOKESTATIC, MFC, "registerItemRenderer", REG_DESC, false);
        });
        Val renderer = arg(stacksAt(cn, "registerItemRenderer").get(0), 2, 1);
        assertEquals(1, renderer.ctorArgs.size());
        assertEquals("com/hbm/main/ResourceManager.henry_tex", renderer.ctorArgs.get(0).fieldKey());
    }

    @Test
    void modelsItemGetItemFromBlock() {
        ClassNode cn = gen("init", "()V", mv -> {
            mv.visitFieldInsn(GETSTATIC, MODBLOCKS, "machine_press", BLOCK);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/item/Item", "func_150898_a",
                    "(Lnet/minecraft/block/Block;)Lnet/minecraft/item/Item;", false);
            mv.visitTypeInsn(NEW, "test/R");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "test/R", "<init>", "()V", false);
            mv.visitMethodInsn(INVOKESTATIC, MFC, "registerItemRenderer", REG_DESC, false);
        });
        Val item = arg(stacksAt(cn, "registerItemRenderer").get(0), 2, 0);
        assertEquals(Val.Kind.ITEM_FROM_BLOCK, item.kind);
        assertEquals("com/hbm/blocks/ModBlocks.machine_press", item.inner.fieldKey());
    }

    @Test
    void resolvesStringVarargsArrayForRenderOnly() {
        ClassNode cn = gen("draw", "()V", mv -> {
            mv.visitFieldInsn(GETSTATIC, "com/hbm/main/ResourceManager", "turret_chekhov",
                    "Lnet/minecraftforge/client/model/IModelCustom;");
            mv.visitInsn(ICONST_2);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_0);
            mv.visitLdcInsn("Base");
            mv.visitInsn(AASTORE);
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_1);
            mv.visitLdcInsn("Barrel");
            mv.visitInsn(AASTORE);
            mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraftforge/client/model/IModelCustom",
                    "renderOnly", "([Ljava/lang/String;)V", true);
        });
        List<Val> st = stacksAt(cn, "renderOnly").get(0);
        Val arr = st.get(st.size() - 1);
        Val recv = st.get(st.size() - 2);
        assertEquals(Val.Kind.ARRAY, arr.kind);
        assertEquals(2, arr.elements.size());
        assertEquals("Base", arr.elements.get(0).stringValue);
        assertEquals("Barrel", arr.elements.get(1).stringValue);
        assertEquals("com/hbm/main/ResourceManager.turret_chekhov", recv.fieldKey());
    }

    @Test
    void followsBuilderChainAndRecordsTheUnlocalizedName() {
        ClassNode cn = gen("init", "()V", mv -> {
            mv.visitTypeInsn(NEW, "com/hbm/items/tool/ItemBlowtorch");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "com/hbm/items/tool/ItemBlowtorch", "<init>", "()V", false);
            mv.visitLdcInsn("acetylene_torch");
            mv.visitMethodInsn(INVOKEVIRTUAL, "com/hbm/items/tool/ItemBlowtorch", "func_77655_b",
                    "(Ljava/lang/String;)Lnet/minecraft/item/Item;", false);
            mv.visitFieldInsn(PUTSTATIC, MODITEMS, "acetylene_torch", ITEM);
        });
        Val[] seen = new Val[1];
        MethodNode mn = cn.methods.get(0);
        MethodSim.run(cn, mn, (insn, stack, locals) -> {
            if (insn.getOpcode() == PUTSTATIC && !stack.isEmpty()) seen[0] = stack.get(stack.size() - 1);
        });
        assertNotNull(seen[0]);
        assertEquals(Val.Kind.NEW_OBJ, seen[0].kind);
        assertEquals("com/hbm/items/tool/ItemBlowtorch", seen[0].typeName);
        assertFalse(seen[0].syntheticBuilder);
        assertTrue(seen[0].calls.stream()
                .anyMatch(c -> c[0].equals("func_77655_b") && "acetylene_torch".equals(c[1])));
    }

    @Test
    void builderChainOnAStaticFieldSynthesisesAFreshObject() {
        // ModItems.base.copy().setUnlocalizedName("variant") must NOT alias onto `base`
        ClassNode cn = gen("init", "()V", mv -> {
            mv.visitFieldInsn(GETSTATIC, MODITEMS, "mp_fuselage_10_kerosene", ITEM);
            mv.visitMethodInsn(INVOKEVIRTUAL, "com/hbm/items/weapon/ItemCustomMissilePart", "copy",
                    "()Lcom/hbm/items/weapon/ItemCustomMissilePart;", false);
            mv.visitLdcInsn("mp_fuselage_10_kerosene_camo");
            mv.visitMethodInsn(INVOKEVIRTUAL, "com/hbm/items/weapon/ItemCustomMissilePart", "func_77655_b",
                    "(Ljava/lang/String;)Lnet/minecraft/item/Item;", false);
            mv.visitFieldInsn(PUTSTATIC, MODITEMS, "mp_fuselage_10_kerosene_camo", ITEM);
        });
        Val[] seen = new Val[1];
        MethodSim.run(cn, cn.methods.get(0), (insn, stack, locals) -> {
            if (insn.getOpcode() == PUTSTATIC && !stack.isEmpty()) seen[0] = stack.get(stack.size() - 1);
        });
        assertEquals(Val.Kind.NEW_OBJ, seen[0].kind);
        assertTrue(seen[0].syntheticBuilder);
        assertEquals("com/hbm/items/weapon/ItemCustomMissilePart", seen[0].typeName);
        assertEquals("com/hbm/items/ModItems.mp_fuselage_10_kerosene", seen[0].ctorArgs.get(0).fieldKey());
        assertTrue(seen[0].calls.stream()
                .anyMatch(c -> c[0].equals("func_77655_b") && "mp_fuselage_10_kerosene_camo".equals(c[1])));
    }

    @Test
    void operandStackIsClearedAtAJumpTargetSoNoValueLeaksAcrossBlocks() {
        ClassNode cn = gen("loopy", "()V", mv -> {
            Label top = new Label();
            mv.visitLabel(top);                                   // jump target
            mv.visitFieldInsn(GETSTATIC, MODITEMS, "a", ITEM);
            mv.visitInsn(POP);
            mv.visitJumpInsn(GOTO, top);
        });
        // nothing to assert beyond "it terminates and never underflows into a bogus value":
        // walk it and require every observed stack to be consistent with the block it is in.
        MethodNode mn = cn.methods.get(0);
        List<Integer> depths = new ArrayList<>();
        MethodSim.run(cn, mn, (insn, stack, locals) -> depths.add(stack.size()));
        assertFalse(depths.isEmpty());
        assertTrue(depths.stream().allMatch(d -> d >= 0 && d <= 2));
    }

    @Test
    void unmodelledValuesCarryAReasonInsteadOfBeingGuessed() {
        ClassNode cn = gen("init", "(Ljava/util/Map;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEINTERFACE, "java/util/Map", "get",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", true);
            mv.visitTypeInsn(CHECKCAST, "net/minecraft/item/Item");
            mv.visitTypeInsn(NEW, "test/R");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "test/R", "<init>", "()V", false);
            mv.visitMethodInsn(INVOKESTATIC, MFC, "registerItemRenderer", REG_DESC, false);
        });
        Val item = arg(stacksAt(cn, "registerItemRenderer").get(0), 2, 0);
        assertEquals(Val.Kind.UNKNOWN, item.kind);
        assertNotNull(item.reason);
        assertTrue(item.reason.contains("Map.get"), item.reason);
    }

    @Test
    void staticCallResultsKeepTheirArgumentsSoFactoriesStayReadable() {
        ClassNode cn = gen("init", "()V", mv -> {
            mv.visitFieldInsn(GETSTATIC, "com/hbm/main/ResourceManager", "missileMicro_tex",
                    "Lnet/minecraft/util/ResourceLocation;");
            mv.visitFieldInsn(GETSTATIC, "com/hbm/main/ResourceManager", "missileMicro",
                    "Lnet/minecraftforge/client/model/IModelCustom;");
            mv.visitMethodInsn(INVOKESTATIC, "com/hbm/render/item/ItemRenderMissileGeneric",
                    "generateStandard",
                    "(Lnet/minecraft/util/ResourceLocation;Lnet/minecraftforge/client/model/IModelCustom;)"
                            + "Ljava/util/function/Consumer;", false);
            mv.visitMethodInsn(INVOKESTATIC, "test/Sink", "take", "(Ljava/lang/Object;)V", false);
        });
        Val v = stacksAt(cn, "take").get(0).get(0);
        assertEquals(Val.Kind.CALL, v.kind);
        assertEquals("generateStandard", v.name);
        assertEquals(2, v.ctorArgs.size());
        assertEquals("com/hbm/main/ResourceManager.missileMicro_tex", v.ctorArgs.get(0).fieldKey());
        assertEquals("com/hbm/main/ResourceManager.missileMicro", v.ctorArgs.get(1).fieldKey());
    }

    @Test
    void stringBuilderChainResolvesToAConcreteStringWhenEveryPieceIsKnown() {
        // new ResourceLocation("hbm", new StringBuilder().append("textures/x_").append(arg1).append(".png").toString())
        // with local 1 SEEDED to a concrete literal, mimicking one enum constant's ctor argument.
        ClassNode cn = gen("build", "(Ljava/lang/String;)V", mv -> {
            mv.visitTypeInsn(NEW, "net/minecraft/util/ResourceLocation");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("hbm");
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            mv.visitLdcInsn("textures/x_");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            mv.visitLdcInsn(".png");
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                    "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/util/ResourceLocation", "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            mv.visitMethodInsn(INVOKESTATIC, "test/Sink", "take", "(Ljava/lang/Object;)V", false);
        });
        MethodNode mn = cn.methods.get(0);
        java.util.Map<Integer, Val> seed = java.util.Map.of(1, Val.string("machine_a"));
        Val[] seen = new Val[1];
        MethodSim.run(cn, mn, seed, (insn, stack, locals) -> {
            if (insn.getOpcode() == INVOKESTATIC && insn instanceof MethodInsnNode m && "take".equals(m.name))
                seen[0] = stack.get(stack.size() - 1);
        });
        assertEquals(Val.Kind.NEW_OBJ, seen[0].kind);
        assertEquals(2, seen[0].ctorArgs.size());
        assertEquals(Val.Kind.STRING, seen[0].ctorArgs.get(1).kind);
        assertEquals("textures/x_machine_a.png", seen[0].ctorArgs.get(1).stringValue);
    }

    @Test
    void stringBuilderChainStaysUnresolvedWhenAPieceIsNotAKnownString() {
        // Same shape, but the middle append's argument is left as a generic (unseeded) parameter,
        // so toString() must NOT be guessed.
        ClassNode cn = gen("build", "(Ljava/lang/String;)V", mv -> {
            mv.visitTypeInsn(NEW, "java/lang/StringBuilder");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                    "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "test/Sink", "take", "(Ljava/lang/Object;)V", false);
        });
        Val v = stacksAt(cn, "take").get(0).get(0);
        assertEquals(Val.Kind.UNKNOWN, v.kind);
    }

    @Test
    void methodParametersAreIdentifiedByIndexSoForwardersCanBeDetected() {
        ClassNode cn = gen("register", "(Lnet/minecraft/block/Block;)V", mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitLdcInsn(Type.getObjectType("com/hbm/items/block/ItemBlockBase"));
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/block/Block", "func_149739_a",
                    "()Ljava/lang/String;", false);
            mv.visitMethodInsn(INVOKESTATIC, "cpw/mods/fml/common/registry/GameRegistry", "registerBlock",
                    "(Lnet/minecraft/block/Block;Ljava/lang/Class;Ljava/lang/String;)"
                            + "Lnet/minecraft/block/Block;", false);
            mv.visitInsn(POP);
        });
        List<Val> st = stacksAt(cn, "registerBlock").get(0);
        Val block = arg(st, 3, 0), cls = arg(st, 3, 1), name = arg(st, 3, 2);
        assertEquals(Val.Kind.PARAM, block.kind);
        assertEquals(0, block.numberValue.intValue());
        assertEquals(Val.Kind.CLASS, cls.kind);
        assertEquals("com/hbm/items/block/ItemBlockBase", cls.typeName);
        assertEquals(Val.Kind.DERIVED, name.kind);
        assertEquals("getUnlocalizedName", name.stringValue);
    }

    /**
     * Chisel's real {@code blockPlanks}/{@code blockStainedGlass}/{@code blockStainedGlassPane}
     * shape (CONTENT-RESOLUTION.md's disclosed limit: "MethodSim does not persist an array's
     * constructed identity across a PUTSTATIC/GETSTATIC round trip"), confirmed by direct
     * bytecode inspection of Chisel's own {@code ChiselBlocks.class}: a {@code Block[]} static
     * field is read via {@code getstatic}, populated element-by-element with a loop (non-constant
     * index), and immediately re-read via a SECOND {@code getstatic} of the SAME field in the SAME
     * method to pass the just-built element onward. Opt-in via the {@link JarIndex}-aware
     * {@link MethodSim#run(ClassNode, MethodNode, JarIndex, MethodSim.Handler)} overload only —
     * every other caller (the 3-arg overload) is proven unaffected below.
     */
    @Test
    void contentArrayFieldSurvivesTheGetstaticRoundTrip_whenJarContextIsProvided() {
        ClassNode blockCls = TestAsm.bareClass("any/pkg12/MyBlock", "net/minecraft/block/Block");
        ClassNode holder = TestAsm.bareClass("any/pkg12/Holder", null);
        holder.fields.add(new org.objectweb.asm.tree.FieldNode(ACC_PUBLIC | ACC_STATIC, "arr",
                "[Lany/pkg12/MyBlock;", null, null));

        ClassNode registrar = TestAsm.staticMethodClass("any/pkg12/Load", mv -> {
            Label loop = new Label(), end = new Label();
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, 0);
            mv.visitLabel(loop);
            mv.visitVarInsn(ILOAD, 0);
            mv.visitInsn(ICONST_2);
            mv.visitJumpInsn(IF_ICMPGE, end);
            mv.visitFieldInsn(GETSTATIC, "any/pkg12/Holder", "arr", "[Lany/pkg12/MyBlock;");
            mv.visitVarInsn(ILOAD, 0);
            mv.visitTypeInsn(NEW, "any/pkg12/MyBlock");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg12/MyBlock", "<init>", "()V", false);
            mv.visitInsn(AASTORE);
            mv.visitFieldInsn(GETSTATIC, "any/pkg12/Holder", "arr", "[Lany/pkg12/MyBlock;");
            mv.visitVarInsn(ILOAD, 0);
            mv.visitInsn(AALOAD);
            mv.visitMethodInsn(INVOKESTATIC, "test/Sink", "take", "(Ljava/lang/Object;)V", false);
            mv.visitIincInsn(0, 1);
            mv.visitJumpInsn(GOTO, loop);
            mv.visitLabel(end);
        });

        JarIndex jar = TestAsm.jarOf(blockCls, holder, registrar);
        MethodNode mn = registrar.methods.stream().filter(m -> "run".equals(m.name)).findFirst().orElseThrow();

        List<Val> seenWithJar = new ArrayList<>();
        MethodSim.run(registrar, mn, jar, (insn, stack, locals) -> {
            if (insn.getOpcode() == INVOKESTATIC && insn instanceof MethodInsnNode m && "take".equals(m.name))
                seenWithJar.add(stack.get(stack.size() - 1));
        });
        assertEquals(1, seenWithJar.size());
        assertEquals(Val.Kind.NEW_OBJ, seenWithJar.get(0).kind,
                "the just-constructed block, not an opaque getstatic placeholder, must come back"
                        + " through the SAME array field's getstatic later in this one method");
        assertEquals("any/pkg12/MyBlock", seenWithJar.get(0).typeName);

        // Without a JarIndex (every OTHER caller of MethodSim today), behaviour is byte-for-byte
        // unchanged: the round trip still loses the array's identity, exactly as before this
        // capability existed.
        List<Val> seenWithoutJar = new ArrayList<>();
        MethodSim.run(registrar, mn, (insn, stack, locals) -> {
            if (insn.getOpcode() == INVOKESTATIC && insn instanceof MethodInsnNode m && "take".equals(m.name))
                seenWithoutJar.add(stack.get(stack.size() - 1));
        });
        assertEquals(1, seenWithoutJar.size());
        assertEquals(Val.Kind.UNKNOWN, seenWithoutJar.get(0).kind);
    }
}
