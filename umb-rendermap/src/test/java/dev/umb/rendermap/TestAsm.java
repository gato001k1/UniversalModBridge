package dev.umb.rendermap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.function.Consumer;

/**
 * Shared ASM class-generation helpers for synthetic test fixtures, mirroring the pattern already
 * established by {@link MethodSimTest} (real bytecode, not mocks, so the exact instruction shape
 * under test is explicit and inspectable). Not a test class itself — no {@code @Test} methods.
 */
final class TestAsm implements Opcodes {
    private TestAsm() {}

    /** A class with a trivial {@code <init>} and one extra method whose body the caller emits. */
    static ClassNode classWithMethod(String name, String superName, String[] interfaces,
                                      String methodName, String methodDesc, int methodAccess,
                                      Consumer<MethodVisitor> body) {
        String sup = superName == null ? "java/lang/Object" : superName;
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC, name, null, sup, interfaces == null ? new String[0] : interfaces);

        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        init.visitVarInsn(ALOAD, 0);
        init.visitMethodInsn(INVOKESPECIAL, sup, "<init>", "()V", false);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();

        if (methodName != null) {
            MethodVisitor mv = cw.visitMethod(methodAccess, methodName, methodDesc, null, null);
            mv.visitCode();
            body.accept(mv);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    /** A bare class (no extra method) — just name/super/interfaces and a trivial {@code <init>}. */
    static ClassNode bareClass(String name, String superName, String... interfaces) {
        return classWithMethod(name, superName, interfaces, null, null, 0, mv -> {});
    }

    /** One static method named "run" taking no args, whose body the caller emits. */
    static ClassNode staticMethodClass(String name, Consumer<MethodVisitor> body) {
        return classWithMethod(name, null, null, "run", "()V", ACC_PUBLIC | ACC_STATIC, body);
    }

    static JarIndex jarOf(ClassNode... classes) {
        JarIndex idx = new JarIndex();
        for (ClassNode cn : classes) idx.classes.put(cn.name, cn);
        return idx;
    }
}
