package dev.umb.guimap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/** Small focused tests for {@link ExprEval}, {@link PngUtil} and the conflict-detection paths of
 *  {@link FieldConstResolver}, split out of the end-to-end scenario in GuiClassAnalyzerTest. */
class UtilTest {

    @Test
    void exprEvalDecodesAPlainGetfieldWithNoArithmetic() {
        ExprEval.Decoded d = ExprEval.decode("getfield com/example/Gui.field_147003_i");
        assertNotNull(d);
        assertEquals("com/example/Gui", d.owner);
        assertEquals("field_147003_i", d.fieldName);
        assertEquals(0, d.delta);
    }

    @Test
    void exprEvalAccumulatesAChainOfDeltas() {
        // ySize - 96 + 2, exactly the shape seen in GUIFurnaceIron's foreground label y position
        ExprEval.Decoded d = ExprEval.decode("getfield com/example/Gui.field_147000_g-96+2");
        assertNotNull(d);
        assertEquals("field_147000_g", d.fieldName);
        assertEquals(-94, d.delta);
    }

    @Test
    void exprEvalRejectsNonGetfieldReasons() {
        assertNull(ExprEval.decode("arithmetic"));
        assertNull(ExprEval.decode(null));
        assertNull(ExprEval.decode("result of Foo.bar()"));
    }

    @Test
    void pngUtilReadsWidthAndHeightFromTheIhdrChunk() {
        byte[] png = new byte[24];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, png, 0, 8);
        png[12] = 'I'; png[13] = 'H'; png[14] = 'D'; png[15] = 'R';
        put32(png, 16, 176); put32(png, 20, 166);
        PngUtil.Dim d = PngUtil.dimensions(png);
        assertNotNull(d);
        assertEquals(176, d.w);
        assertEquals(166, d.h);
    }

    @Test
    void pngUtilRejectsNonPngBytes() {
        assertNull(PngUtil.dimensions(new byte[]{1, 2, 3}));
        assertNull(PngUtil.dimensions(null));
        assertNull(PngUtil.dimensions("not a png at all, just text bytes".getBytes()));
    }

    private static void put32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24); b[off + 1] = (byte) (v >>> 16); b[off + 2] = (byte) (v >>> 8); b[off + 3] = (byte) v;
    }

    /** Two constructors assign field_146999_f two DIFFERENT constants -> must be reported as a
     *  conflict, never guessed at, per the module's "never fabricate a value" rule. */
    @Test
    void fieldConstResolverFlagsConflictingConstantsAcrossConstructorsInsteadOfGuessing() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, "test/GuiConflict", null, Vanilla.GUI_CONTAINER, null);

        // Deliberately does not call any super <init> - this class is never loaded or verified by
        // a real JVM, only walked by MethodSim, which does not require one.
        MethodVisitor c1 = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        c1.visitCode();
        c1.visitVarInsn(ALOAD, 0);
        c1.visitIntInsn(SIPUSH, 176);
        c1.visitFieldInsn(PUTFIELD, "test/GuiConflict", Vanilla.F_XSIZE, "I");
        c1.visitInsn(RETURN);
        c1.visitMaxs(4, 4);
        c1.visitEnd();

        MethodVisitor c2 = cw.visitMethod(ACC_PUBLIC, "<init>", "(I)V", null, null);
        c2.visitCode();
        c2.visitVarInsn(ALOAD, 0);
        c2.visitIntInsn(SIPUSH, 200);
        c2.visitFieldInsn(PUTFIELD, "test/GuiConflict", Vanilla.F_XSIZE, "I");
        c2.visitInsn(RETURN);
        c2.visitMaxs(4, 4);
        c2.visitEnd();
        cw.visitEnd();

        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        JarIndex jar = new JarIndex();
        jar.classes.put("test/GuiConflict", cn);

        FieldConstResolver resolver = new FieldConstResolver(jar);
        FieldConstResolver.IntField f = resolver.intField("test/GuiConflict", Vanilla.F_XSIZE);
        assertNotNull(f);
        assertTrue(f.conflict);
    }
}
