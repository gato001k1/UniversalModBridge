package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Door-live follow-up (GL-state ops): cull-face toggles and clip planes resolve to
 * replayable constant ops. Uses generated ASM bytecode (no mod jar needed), mirroring
 * the HBM vehicle-door shapes: {@code glDisable(GL_CULL_FACE)} method-scoped, and a
 * {@code DoubleBuffer} plane equation built with {@code new double[4]} + {@code put} +
 * {@code rewind} + {@code glClipPlane}.
 */
class GlStateOpResolverTest implements Opcodes {

    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String TE = "test/FakeTE";
    private static final String TESR = "test/FakeGlTESR";

    private static JarIndex indexWithTe() {
        JarIndex jar = new JarIndex();
        ClassNode te = new ClassNode();
        te.visit(V1_8, ACC_PUBLIC, TE, null, "net/minecraft/tileentity/TileEntity", null);
        te.visitEnd();
        jar.classes.put(TE, te);
        return jar;
    }

    /** render(LFakeTE;Ljava/nio/DoubleBuffer;)V : slots 0=this 1=te 2=buf. */
    private static ClassNode tesrWithBuffer(java.util.function.Consumer<MethodVisitor> body) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, TESR, null, "java/lang/Object", null);
        MethodVisitor mv = cw.visitMethod(ACC_PUBLIC, "render",
                "(L" + TE + ";Ljava/nio/DoubleBuffer;)V", null, null);
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

    private static MethodNode renderMethod(ClassNode cn) {
        for (MethodNode mn : cn.methods) if ("render".equals(mn.name)) return mn;
        throw new IllegalStateException("no render method");
    }

    private static void renderPart(MethodVisitor mv, String group) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitLdcInsn(group);
        mv.visitMethodInsn(INVOKEVIRTUAL, "test/FakeModel", "renderPart", "(Ljava/lang/String;)V", false);
    }

    private static DynamicOpResolver.Op find(List<DynamicOpResolver.Op> ops, String gl, String group) {
        for (DynamicOpResolver.Op op : ops) {
            if (gl.equals(op.gl) && (group == null ? op.group == null : group.equals(op.group))) return op;
        }
        return null;
    }

    private static double constArg(DynamicOpResolver.Op op, int i) {
        assertNotNull(op, "op present");
        assertTrue(i < op.args.size(), "arg present");
        assertNotNull(op.args.get(i).expr, "arg resolved, not skipped");
        assertTrue(op.args.get(i).expr instanceof DynamicOpResolver.ConstExpr,
                "arg is const, was " + op.args.get(i).expr.getClass().getSimpleName());
        return ((DynamicOpResolver.ConstExpr) op.args.get(i).expr).value;
    }

    @Test
    void cullDisableScopedPerDraw() {
        JarIndex jar = indexWithTe();
        ClassNode cn = tesrWithBuffer(mv -> {
            mv.visitIntInsn(SIPUSH, 2884);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glDisable", "(I)V", false);
            renderPart(mv, "P");
            mv.visitIntInsn(SIPUSH, 2884);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glEnable", "(I)V", false);
        });
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, -1,
                DynamicOpResolver.posArgs(mn));
        // The disable lands in P's prefix (draws with culling off); the trailing
        // re-enable is chronological only (never inside a draw prefix).
        DynamicOpResolver.Op off = find(r.ops, "glCullFace", "P");
        assertNotNull(off, "cull-off op attributed to P");
        assertEquals(0.0, constArg(off, 0), 1e-9);
        DynamicOpResolver.Op on = null;
        for (DynamicOpResolver.Op op : r.ops) {
            if ("glCullFace".equals(op.gl) && op.group == null
                    && !op.args.isEmpty() && op.args.get(0).expr != null
                    && ((DynamicOpResolver.ConstExpr) op.args.get(0).expr).value == 1.0) {
                on = op;
            }
        }
        assertNotNull(on, "trailing cull-on stays chronological");
    }

    @Test
    void clipPlaneEquationFolds() {
        JarIndex jar = indexWithTe();
        ClassNode cn = tesrWithBuffer(mv -> {
            // buf.put(new double[]{1,0,0,3.4375}); buf.rewind();
            mv.visitVarInsn(ALOAD, 2);
            mv.visitInsn(ICONST_4);
            mv.visitIntInsn(NEWARRAY, T_DOUBLE);
            double[] eq = {1.0, 0.0, 0.0, 3.4375};
            for (int i = 0; i < 4; i++) {
                mv.visitInsn(DUP);
                mv.visitInsn(ICONST_0 + i);
                mv.visitLdcInsn(eq[i]);
                mv.visitInsn(DASTORE);
            }
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/nio/DoubleBuffer", "put", "([D)Ljava/nio/DoubleBuffer;", false);
            mv.visitInsn(POP);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKEVIRTUAL, "java/nio/DoubleBuffer", "rewind", "()Ljava/nio/Buffer;", false);
            mv.visitInsn(POP);
            // glEnable(GL_CLIP_PLANE0); glClipPlane(GL_CLIP_PLANE0, buf);
            mv.visitIntInsn(SIPUSH, 12288);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glEnable", "(I)V", false);
            mv.visitIntInsn(SIPUSH, 12288);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glClipPlane", "(ILjava/nio/DoubleBuffer;)V", false);
            renderPart(mv, "Q");
        });
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, -1,
                DynamicOpResolver.posArgs(mn));
        DynamicOpResolver.Op en = find(r.ops, "glClipEnable", "Q");
        assertNotNull(en, "clip-enable attributed to Q");
        assertEquals(12288.0, constArg(en, 0), 1e-9);
        assertEquals(1.0, constArg(en, 1), 1e-9);
        DynamicOpResolver.Op cp = find(r.ops, "glClipPlane", "Q");
        assertNotNull(cp, "clip-plane equation attributed to Q");
        double[] want = {12288.0, 1.0, 0.0, 0.0, 3.4375};
        assertEquals(5, cp.args.size());
        for (int i = 0; i < 5; i++) assertEquals(want[i], constArg(cp, i), 1e-9);
    }

    @Test
    void nonConstPlaneStaysHonestSkip() {
        JarIndex jar = indexWithTe();
        ClassNode cn = tesrWithBuffer(mv -> {
            // glClipPlane(cap, buf) with an unknown buffer: counted skip, no fabricated plane.
            mv.visitIntInsn(SIPUSH, 12288);
            mv.visitVarInsn(ALOAD, 2);
            mv.visitMethodInsn(INVOKESTATIC, GL11, "glClipPlane", "(ILjava/nio/DoubleBuffer;)V", false);
            renderPart(mv, "Q");
        });
        MethodNode mn = renderMethod(cn);
        DynamicOpResolver.Resolved r = DynamicOpResolver.resolve(cn, mn, jar, 0, -1,
                DynamicOpResolver.posArgs(mn));
        assertNull(find(r.ops, "glClipPlane", "Q"), "no fabricated plane");
        assertTrue(r.skipped.getOrDefault("clipplane-nonconst", 0) >= 1, "skip counted: " + r.skipped);
    }
}
