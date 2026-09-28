package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RendererTransformExtractor using generated ASM bytecode.
 */
class RendererTransformExtractorTest implements Opcodes {

    private static final String GL11 = "org/lwjgl/opengl/GL11";

    /** Generate a class with one method that the caller emits. */
    private static ClassNode genClass(String className, String methodName, java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC | ACC_STATIC, methodName, "()V", null, null);
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

    @Test
    void extractsGlScalefWithConstantValues() {
        ClassNode cn = genClass("test/Scale", "render", mv -> {
            // glScalef(2.0f, 3.0f, 4.0f)
            mv.visitLdcInsn(2.0f);
            mv.visitLdcInsn(3.0f);
            mv.visitLdcInsn(4.0f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);
        });

        List<RendererTransformExtractor.TransformRecord> transforms =
                RendererTransformExtractor.extractTransforms(cn);

        assertEquals(1, transforms.size());
        RendererTransformExtractor.TransformRecord rec = transforms.get(0);
        assertEquals("glScalef", rec.op);
        assertEquals(3, rec.args.size());
        assertEquals(2.0f, rec.args.get(0));
        assertEquals(3.0f, rec.args.get(1));
        assertEquals(4.0f, rec.args.get(2));
        assertFalse(rec.isDynamic);
    }

    @Test
    void extractsGlTranslatefWithConstantValues() {
        ClassNode cn = genClass("test/Translate", "render", mv -> {
            // glTranslatef(0.5f, 1.5f, 2.5f)
            mv.visitLdcInsn(0.5f);
            mv.visitLdcInsn(1.5f);
            mv.visitLdcInsn(2.5f);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glTranslatef", "(FFF)V", false);
        });

        List<RendererTransformExtractor.TransformRecord> transforms =
                RendererTransformExtractor.extractTransforms(cn);

        assertEquals(1, transforms.size());
        RendererTransformExtractor.TransformRecord rec = transforms.get(0);
        assertEquals("glTranslatef", rec.op);
        assertEquals(3, rec.args.size());
        assertEquals(0.5f, rec.args.get(0));
        assertEquals(1.5f, rec.args.get(1));
        assertEquals(2.5f, rec.args.get(2));
        assertFalse(rec.isDynamic);
    }

    @Test
    void extractsGlRotatefWithConstantValues() {
        ClassNode cn = genClass("test/Rotate", "render", mv -> {
            // glRotatef(45.0f, 0.0f, 1.0f, 0.0f)
            mv.visitLdcInsn(45.0f);
            mv.visitInsn(FCONST_0);  // 0.0f
            mv.visitInsn(FCONST_1);  // 1.0f
            mv.visitInsn(FCONST_0);  // 0.0f
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotatef", "(FFFF)V", false);
        });

        List<RendererTransformExtractor.TransformRecord> transforms =
                RendererTransformExtractor.extractTransforms(cn);

        assertEquals(1, transforms.size());
        RendererTransformExtractor.TransformRecord rec = transforms.get(0);
        assertEquals("glRotatef", rec.op);
        assertEquals(4, rec.args.size());
        assertEquals(45.0f, rec.args.get(0));
        assertEquals(0.0f, rec.args.get(1));
        assertEquals(1.0f, rec.args.get(2));
        assertEquals(0.0f, rec.args.get(3));
        assertFalse(rec.isDynamic);
    }

    @Test
    void extractsGlPushAndPopMatrix() {
        ClassNode cn = genClass("test/Matrix", "render", mv -> {
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glPushMatrix", "()V", false);
            // glScalef(1.0f, 1.0f, 1.0f) — inside the push/pop
            mv.visitInsn(FCONST_1);
            mv.visitInsn(FCONST_1);
            mv.visitInsn(FCONST_1);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glPopMatrix", "()V", false);
        });

        List<RendererTransformExtractor.TransformRecord> transforms =
                RendererTransformExtractor.extractTransforms(cn);

        assertEquals(3, transforms.size());
        assertEquals("glPushMatrix", transforms.get(0).op);
        assertEquals(0, transforms.get(0).args.size());
        assertEquals("glScalef", transforms.get(1).op);
        assertEquals("glPopMatrix", transforms.get(2).op);
        assertEquals(0, transforms.get(2).args.size());
    }

    @Test
    void extractsDoubleConstantsForGlScaled() {
        ClassNode cn = genClass("test/ScaleD", "render", mv -> {
            // glScaled(1.0d, 2.0d, 3.0d)
            mv.visitLdcInsn(1.0d);
            mv.visitLdcInsn(2.0d);
            mv.visitLdcInsn(3.0d);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScaled", "(DDD)V", false);
        });

        List<RendererTransformExtractor.TransformRecord> transforms =
                RendererTransformExtractor.extractTransforms(cn);

        assertEquals(1, transforms.size());
        RendererTransformExtractor.TransformRecord rec = transforms.get(0);
        assertEquals("glScaled", rec.op);
        assertEquals(3, rec.args.size());
        assertEquals(1.0d, rec.args.get(0));
        assertEquals(2.0d, rec.args.get(1));
        assertEquals(3.0d, rec.args.get(2));
        assertFalse(rec.isDynamic);
    }

    @Test
    void extractsWithFconstInstructions() {
        ClassNode cn = genClass("test/Implied", "render", mv -> {
            // Use fconst instructions instead of ldc
            mv.visitInsn(FCONST_0);  // 0.0f
            mv.visitInsn(FCONST_1);  // 1.0f
            mv.visitInsn(FCONST_2);  // 2.0f
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);
        });

        List<RendererTransformExtractor.TransformRecord> transforms =
                RendererTransformExtractor.extractTransforms(cn);

        assertEquals(1, transforms.size());
        RendererTransformExtractor.TransformRecord rec = transforms.get(0);
        assertEquals("glScalef", rec.op);
        assertEquals(3, rec.args.size());
        assertEquals(0.0f, rec.args.get(0));
        assertEquals(1.0f, rec.args.get(1));
        assertEquals(2.0f, rec.args.get(2));
        assertFalse(rec.isDynamic);
    }

    @Test
    void ignoresNonGlMethods() {
        ClassNode cn = genClass("test/Other", "render", mv -> {
            mv.visitLdcInsn(42);
            mv.visitMethodInsn(INVOKESTATIC, "java/lang/System", "exit", "(I)V", false);
        });

        List<RendererTransformExtractor.TransformRecord> transforms =
                RendererTransformExtractor.extractTransforms(cn);

        assertEquals(0, transforms.size());
    }

    @Test
    void ensuringArityIsAlwaysCorrectEvenForDynamicArgs() {
        // This test verifies that dynamic arguments are marked with null
        // and that arity is always correct: 3 for scale/translate, 4 for rotate
        ClassNode cn = genClass("test/Arity", "render", mv -> {
            // glScalef with 2 constants, 1 dynamic (loaded from a field)
            mv.visitLdcInsn(1.0f);
            mv.visitLdcInsn(2.0f);
            mv.visitFieldInsn(GETSTATIC, "test/Config", "scaleZ", "F");
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glScalef", "(FFF)V", false);

            // glRotatef with 1 constant, 3 dynamic
            mv.visitFieldInsn(GETSTATIC, "test/Config", "angle", "F");
            mv.visitFieldInsn(GETSTATIC, "test/Config", "rotX", "F");
            mv.visitFieldInsn(GETSTATIC, "test/Config", "rotY", "F");
            mv.visitFieldInsn(GETSTATIC, "test/Config", "rotZ", "F");
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glRotatef", "(FFFF)V", false);
        });

        List<RendererTransformExtractor.TransformRecord> transforms =
                RendererTransformExtractor.extractTransforms(cn);

        assertEquals(2, transforms.size());

        // Check glScalef has exactly 3 args (2 constants + 1 null)
        RendererTransformExtractor.TransformRecord scale = transforms.get(0);
        assertEquals("glScalef", scale.op);
        assertEquals(3, scale.args.size());
        assertEquals(1.0f, scale.args.get(0));
        assertEquals(2.0f, scale.args.get(1));
        assertNull(scale.args.get(2));  // Dynamic arg marked as null
        assertEquals(1, scale.dynamicIndices.size());
        assertEquals(2, scale.dynamicIndices.get(0).intValue());
        assertTrue(scale.isDynamic);

        // Check glRotatef has exactly 4 args (all dynamic)
        RendererTransformExtractor.TransformRecord rotate = transforms.get(1);
        assertEquals("glRotatef", rotate.op);
        assertEquals(4, rotate.args.size());
        assertNull(rotate.args.get(0));  // Dynamic
        assertNull(rotate.args.get(1));  // Dynamic
        assertNull(rotate.args.get(2));  // Dynamic
        assertNull(rotate.args.get(3));  // Dynamic
        assertEquals(4, rotate.dynamicIndices.size());
        assertTrue(rotate.isDynamic);
    }
}
