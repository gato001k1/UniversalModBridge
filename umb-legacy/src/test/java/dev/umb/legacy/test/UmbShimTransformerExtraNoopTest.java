package dev.umb.legacy.test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import dev.umb.legacy.legacyside.UmbShimTransformer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Legacy compatibility behavior. */
class UmbShimTransformerExtraNoopTest {

    private static final String FIXTURE_CLASS = "dev/umb/legacy/test/fixture/coolmod/CoolModNetworkHandler";
    private static final String FIXTURE_CLASS_DOTTED = "dev.umb.legacy.test.fixture.coolmod.CoolModNetworkHandler";

    @AfterEach
    void resetOverride() {
        UmbShimTransformer.setExtraNoopTargetsForTest(null);
    }

    @Test
    void parseExtraNoopTargetsParsesMultipleEntries() {
        Map<String, String[]> parsed = UmbShimTransformer.parseExtraNoopTargets(
                " com.example.coolmod.Net = sendA , sendB ; com.example.coolmod.Other=sendC");
        assertArrayEquals(new String[] {"sendA", "sendB"}, parsed.get("com.example.coolmod.Net"));
        assertArrayEquals(new String[] {"sendC"}, parsed.get("com.example.coolmod.Other"));
        assertEquals(2, parsed.size());
    }

    @Test
    void parseExtraNoopTargetsIgnoresMalformedEntriesRatherThanCrashing() {
        assertTrue(UmbShimTransformer.parseExtraNoopTargets(null).isEmpty());
        assertTrue(UmbShimTransformer.parseExtraNoopTargets("").isEmpty());
        assertTrue(UmbShimTransformer.parseExtraNoopTargets("noEqualsSign").isEmpty());
        assertTrue(UmbShimTransformer.parseExtraNoopTargets("=noClassName").isEmpty());
        assertTrue(UmbShimTransformer.parseExtraNoopTargets("com.example.Foo=").isEmpty());
    }

    @Test
    void transformNoOpsAConfiguredNonHbmClassesMethod() throws Exception {
        Map<String, String[]> targets = new HashMap<String, String[]>();
        targets.put(FIXTURE_CLASS_DOTTED, new String[] {"sendPacket"});
        UmbShimTransformer.setExtraNoopTargetsForTest(targets);

        byte[] original = buildFixtureClass();
        byte[] transformed = new UmbShimTransformer()
                .transform(FIXTURE_CLASS_DOTTED, FIXTURE_CLASS_DOTTED, original);
        assertNotSame(original, transformed);

        final boolean[] hasCall = {false};
        final boolean[] found = {false};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                              String[] exceptions) {
                if (!"sendPacket".equals(name)) {
                    return null;
                }
                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                                 boolean isInterface) {
                        hasCall[0] = true;
                    }
                };
            }
        }, 0);
        assertTrue(found[0], "sendPacket method not found in transformed class");
        assertFalse(hasCall[0], "extra-noop target's method body should have every call stripped");
    }

    @Test
    void transformLeavesAnUnconfiguredClassAlone() throws Exception {
        UmbShimTransformer.setExtraNoopTargetsForTest(Collections.<String, String[]>emptyMap());
        byte[] original = buildFixtureClass();
        byte[] transformed = new UmbShimTransformer()
                .transform(FIXTURE_CLASS_DOTTED, FIXTURE_CLASS_DOTTED, original);
        assertSame(original, transformed, "an unrecognized class must pass through untouched");
    }

    @Test
    void transformInstrumentsEverySrgInteractionReturn() throws Exception {
        byte[] original = buildInteractionFixtureClass();
        byte[] transformed = new UmbShimTransformer().transform(
                "dev.umb.legacy.test.fixture.InteractionEntity",
                "dev.umb.legacy.test.fixture.InteractionEntity", original);
        assertNotSame(original, transformed);

        final boolean[] found = {false};
        final boolean[] callsDiag = {false};
        new ClassReader(transformed).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                                              String[] exceptions) {
                if (!"func_130002_c".equals(name)
                        || "(Lnet/minecraft/entity/player/EntityPlayer;)Z".equals(descriptor) == false) {
                    return null;
                }
                found[0] = true;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String mname, String mdesc,
                                                boolean isInterface) {
                        if ("dev/umb/legacy/legacyside/LegacyInteractionDiag".equals(owner)
                                && "record".equals(mname)) {
                            callsDiag[0] = true;
                        }
                    }
                };
            }
        }, 0);
        assertTrue(found[0], "SRG interaction method not found after transform");
        assertTrue(callsDiag[0], "interaction returns must call the generic diagnostic");
    }

    /**
     * A tiny synthetic class under a clearly-non-mod fixture package: a static void method that
     * calls {@code System.out.println}, so an untouched transform leaves a real call in the body
     * and a no-op transform removes it.
     */
    private static byte[] buildFixtureClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, FIXTURE_CLASS, null, "java/lang/Object", null);

        MethodVisitor ctor = cw.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(Opcodes.ALOAD, 0);
        ctor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(Opcodes.RETURN);
        ctor.visitMaxs(1, 1);
        ctor.visitEnd();

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "sendPacket", "()V", null, null);
        mv.visitCode();
        mv.visitFieldInsn(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;");
        mv.visitLdcInsn("hi");
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(2, 0);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }

    private static byte[] buildInteractionFixtureClass() {
        String owner = "dev/umb/legacy/test/fixture/InteractionEntity";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V1_6, Opcodes.ACC_PUBLIC, owner, null, "java/lang/Object", null);

        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, "func_130002_c",
                "(Lnet/minecraft/entity/player/EntityPlayer;)Z", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 2);
        mv.visitEnd();

        cw.visitEnd();
        return cw.toByteArray();
    }
}
