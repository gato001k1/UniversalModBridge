package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code HolderResolver} used to unwrap {@code new T(resourceLocation, ...)} to the underlying
 * resource ONLY for a hardcoded list of HBM's own loader class names (plus a generic one-arg-ctor
 * fallback that happens to also catch this shape by accident). This proves the NEW, explicit,
 * mod-agnostic path: any class in the jar that implements Forge's {@code IModelCustom} — whatever
 * it is named, in whatever package — is recognised as a model loader constructor, exercised here
 * with a TWO-argument constructor (so the incidental one-arg fallback cannot be what makes this
 * pass) and a class name nothing like any of HBM's own loaders.
 */
class HolderResolverGenericTest implements Opcodes {

    @Test
    void unwrapsAnyIModelCustomImplementorEvenWithAMultiArgConstructor() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, "any/pkg/MyCustomLoader", null, "java/lang/Object",
                new String[]{"net/minecraftforge/client/model/IModelCustom"});
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/util/ResourceLocation;F)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(2, 3);
        ctor.visitEnd();
        cw.visitEnd();
        ClassNode loaderClass = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(loaderClass, 0);

        ClassNode holder = TestAsm.staticMethodClass("any/pkg/Holder", mv -> {
            mv.visitTypeInsn(NEW, "any/pkg/MyCustomLoader");
            mv.visitInsn(DUP);
            mv.visitTypeInsn(NEW, "net/minecraft/util/ResourceLocation");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("any");
            mv.visitLdcInsn("models/thing.customfmt");
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/util/ResourceLocation", "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            mv.visitLdcInsn(1.0f);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/MyCustomLoader", "<init>",
                    "(Lnet/minecraft/util/ResourceLocation;F)V", false);
            mv.visitFieldInsn(PUTSTATIC, "any/pkg/Holder", "model",
                    "Lnet/minecraftforge/client/model/IModelCustom;");
        });
        holder.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "model",
                "Lnet/minecraftforge/client/model/IModelCustom;", null, null));

        JarIndex jar = TestAsm.jarOf(loaderClass, holder);
        HolderResolver hr = new HolderResolver(jar);
        hr.scanAll();

        ResRef ref = hr.byField.get("any/pkg/Holder.model");
        assertNotNull(ref);
        assertTrue(ref.resolved(), ref.unresolvedReason);
        assertEquals("any:models/thing.customfmt", ref.path);
        assertEquals("MyCustomLoader", ref.loader);
    }

    @Test
    void aClassThatDoesNotImplementIModelCustomAndHasMultipleCtorArgsStaysUnresolved() {
        ClassNode notALoader = TestAsm.bareClass("any/pkg/PlainHelper", null);
        ClassNode holder = TestAsm.staticMethodClass("any/pkg/Holder2", mv -> {
            mv.visitTypeInsn(NEW, "any/pkg/PlainHelper");
            mv.visitInsn(DUP);
            mv.visitTypeInsn(NEW, "net/minecraft/util/ResourceLocation");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("any:models/thing.obj");
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/util/ResourceLocation", "<init>",
                    "(Ljava/lang/String;)V", false);
            mv.visitLdcInsn(1.0f);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/PlainHelper", "<init>",
                    "(Lnet/minecraft/util/ResourceLocation;F)V", false);
            mv.visitFieldInsn(PUTSTATIC, "any/pkg/Holder2", "model",
                    "Lnet/minecraftforge/client/model/IModelCustom;");
        });
        holder.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "model",
                "Lnet/minecraftforge/client/model/IModelCustom;", null, null));

        JarIndex jar = TestAsm.jarOf(notALoader, holder);
        HolderResolver hr = new HolderResolver(jar);
        hr.scanAll();

        ResRef ref = hr.byField.get("any/pkg/Holder2.model");
        assertNotNull(ref);
        assertFalse(ref.resolved(), "a two-ctor-arg class that isn't an IModelCustom implementor must not be guessed");
    }
}
