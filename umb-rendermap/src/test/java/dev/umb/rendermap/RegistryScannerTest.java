package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link RegistryScanner}'s bounded one-hop generalizations (mandate #1 / #3) against
 * synthetic classes under NON-HBM package names: a forwarder that is an INSTANCE method (not
 * HBM's static-only shape) and itself forwards through one further method before reaching
 * {@code GameRegistry} — mirroring Chisel's real
 * {@code CarvableHelper.registerBlock(Block,String)} -> {@code registerBlock(Block,String,Class)}
 * -> {@code GameRegistry.registerBlock} chain (confirmed by direct bytecode inspection) — and a
 * registration call site fed by a loop variable over a compile-time array of already-named static
 * fields.
 */
class RegistryScannerTest implements Opcodes {

    private static final String GR = "cpw/mods/fml/common/registry/GameRegistry";
    private static final String T_BLOCK = "Lnet/minecraft/block/Block;";

    @Test
    void twoHopInstanceMethodForwarder_isFoundAndRecordsTheLiteralNameAndHopCount() {
        // public void registerBlock(Block, String) { registerBlockInner(b, name); }      -- hop 1
        // void registerBlockInner(Block, String)  { GameRegistry.registerBlock(b, ..., name); } -- hop 0
        // Neither method is static — HBM's own forwarder shape always was; a small per-mod
        // "registration helper" OBJECT (not a static utility class) is at least as common in real
        // 1.7.10 mods, and is exactly Chisel's own CarvableHelper shape.
        ClassNode helper = TestAsm.classWithMethod("any/pkg5/Helper", null, null,
                "registerBlock", "(Lnet/minecraft/block/Block;Ljava/lang/String;)V", ACC_PUBLIC, mv -> {
                    mv.visitVarInsn(ALOAD, 0);
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitVarInsn(ALOAD, 2);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "any/pkg5/Helper", "registerBlockInner",
                            "(Lnet/minecraft/block/Block;Ljava/lang/String;)V", false);
                    mv.visitInsn(RETURN);
                });
        org.objectweb.asm.MethodVisitor inner = helper.visitMethod(ACC_PUBLIC, "registerBlockInner",
                "(Lnet/minecraft/block/Block;Ljava/lang/String;)V", null, null);
        inner.visitCode();
        inner.visitVarInsn(ALOAD, 1);
        inner.visitLdcInsn(org.objectweb.asm.Type.getObjectType("net/minecraft/item/ItemBlock"));
        inner.visitVarInsn(ALOAD, 2);
        inner.visitMethodInsn(INVOKESTATIC, GR, "registerBlock",
                "(Lnet/minecraft/block/Block;Ljava/lang/Class;Ljava/lang/String;)Lnet/minecraft/block/Block;", false);
        inner.visitInsn(POP);
        inner.visitInsn(RETURN);
        inner.visitMaxs(0, 0);
        inner.visitEnd();

        ClassNode holder = TestAsm.staticMethodClass("any/pkg5/Registrar", mv -> {
            mv.visitTypeInsn(NEW, "any/pkg5/Helper");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg5/Helper", "<init>", "()V", false);
            mv.visitFieldInsn(GETSTATIC, "any/pkg5/SomeHolder", "myBlock", T_BLOCK);
            mv.visitLdcInsn("myBlockLocalName");
            mv.visitMethodInsn(INVOKEVIRTUAL, "any/pkg5/Helper", "registerBlock",
                    "(Lnet/minecraft/block/Block;Ljava/lang/String;)V", false);
        });

        RegistryScanner reg = new RegistryScanner(TestAsm.jarOf(helper, holder));
        reg.scanAll();

        RegistryScanner.Reg r = reg.blockFieldToReg.get("any/pkg5/SomeHolder.myBlock");
        assertNotNull(r, reg.blockFieldToReg.keySet().toString());
        assertTrue(r.viaForwarder);
        assertEquals("myBlockLocalName", r.registryName,
                "the literal name at the OUTERMOST call site must be captured even though the"
                        + " forwarder's own body two hops deep builds the real id differently");
        assertEquals("literal", r.nameSource);
        assertEquals(2, r.hopsUsed, "one hop Helper.registerBlock -> registerBlockInner, then"
                + " registerBlockInner calls GameRegistry directly");
    }

    @Test
    void registrationCallSiteFedByALoopVariable_bindsEveryArrayElement() {
        // Block[] blocks = { Holder.a, Holder.b };  (each already its own named static field)
        // String[] names = { "nameZero", "nameOne" };
        // for (i = 0; i < 2; i++) GameRegistry.registerBlock(blocks[i], ItemBlock.class, names[i]);
        // Gap 3 / A4 (mandate #3): walks the call site back to whatever value was actually
        // passed — here a loop variable over a compile-time array — instead of collapsing to one
        // unresolved row per element.
        ClassNode holder = TestAsm.bareClass("any/pkg6/Holder", null);
        holder.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "a", T_BLOCK, null, null));
        holder.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "b", T_BLOCK, null, null));

        ClassNode registrar = TestAsm.staticMethodClass("any/pkg6/Registrar", mv -> {
            mv.visitInsn(ICONST_2);
            mv.visitTypeInsn(ANEWARRAY, "net/minecraft/block/Block");
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_0);
            mv.visitFieldInsn(GETSTATIC, "any/pkg6/Holder", "a", T_BLOCK);
            mv.visitInsn(AASTORE);
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_1);
            mv.visitFieldInsn(GETSTATIC, "any/pkg6/Holder", "b", T_BLOCK);
            mv.visitInsn(AASTORE);
            mv.visitVarInsn(ASTORE, 0);

            mv.visitInsn(ICONST_2);
            mv.visitTypeInsn(ANEWARRAY, "java/lang/String");
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_0);
            mv.visitLdcInsn("nameZero");
            mv.visitInsn(AASTORE);
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_1);
            mv.visitLdcInsn("nameOne");
            mv.visitInsn(AASTORE);
            mv.visitVarInsn(ASTORE, 1);

            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, 2);
            Label loop = new Label();
            Label end = new Label();
            mv.visitLabel(loop);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitInsn(ICONST_2);
            mv.visitJumpInsn(IF_ICMPGE, end);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitInsn(AALOAD);
            mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType("net/minecraft/item/ItemBlock"));
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitInsn(AALOAD);
            mv.visitMethodInsn(INVOKESTATIC, GR, "registerBlock",
                    "(Lnet/minecraft/block/Block;Ljava/lang/Class;Ljava/lang/String;)Lnet/minecraft/block/Block;",
                    false);
            mv.visitInsn(POP);
            mv.visitIincInsn(2, 1);
            mv.visitJumpInsn(GOTO, loop);
            mv.visitLabel(end);
        });

        RegistryScanner reg = new RegistryScanner(TestAsm.jarOf(holder, registrar));
        reg.scanAll();

        // one syntactic bytecode call site (blockCallSites counts call SITES, not the runtime
        // iterations MethodSim's single-pass simulation never actually executes) that expands to
        // two records, one per array element.
        assertEquals(1, reg.blockCallSites);
        RegistryScanner.Reg ra = reg.blockFieldToReg.get("any/pkg6/Holder.a");
        RegistryScanner.Reg rb = reg.blockFieldToReg.get("any/pkg6/Holder.b");
        assertNotNull(ra, reg.blockFieldToReg.keySet().toString());
        assertNotNull(rb, reg.blockFieldToReg.keySet().toString());
        assertEquals("nameZero", ra.registryName);
        assertEquals("nameOne", rb.registryName);
        assertFalse(ra.viaForwarder);
        assertEquals(0, ra.hopsUsed);
    }
}
