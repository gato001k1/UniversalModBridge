package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link RendererAnalyzer#scope} used to require every class it collected to start with
 * {@code com/hbm/}/{@code api/hbm/} — meaning it returned an EMPTY scope (and therefore zero
 * models/textures/groups) for literally every renderer belonging to any other mod, even one
 * {@link BindingScanner} had already correctly bound. These tests exercise a renderer under a
 * plain, non-HBM package and confirm it now resolves, plus the new {@code ModelBase} (Techne)
 * java-model detection.
 */
class RendererAnalyzerGenericTest implements Opcodes {

    @Test
    void resolvesModelAndTextureForARendererOutsideAnyHbmPackage() {
        ClassNode holder = TestAsm.staticMethodClass("any/pkg/ResourceManager", mv -> {
            mv.visitTypeInsn(NEW, "net/minecraft/util/ResourceLocation");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("any");
            mv.visitLdcInsn("textures/x.png");
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/util/ResourceLocation", "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            mv.visitFieldInsn(PUTSTATIC, "any/pkg/ResourceManager", "tex", "Lnet/minecraft/util/ResourceLocation;");

            mv.visitTypeInsn(NEW, "net/minecraft/util/ResourceLocation");
            mv.visitInsn(DUP);
            mv.visitLdcInsn("any");
            mv.visitLdcInsn("models/x.obj");
            mv.visitMethodInsn(INVOKESPECIAL, "net/minecraft/util/ResourceLocation", "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            mv.visitMethodInsn(INVOKESTATIC, "net/minecraftforge/client/model/AdvancedModelLoader", "loadModel",
                    "(Lnet/minecraft/util/ResourceLocation;)Lnet/minecraftforge/client/model/IModelCustom;", false);
            mv.visitFieldInsn(PUTSTATIC, "any/pkg/ResourceManager", "model",
                    "Lnet/minecraftforge/client/model/IModelCustom;");
        });
        holder.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "tex",
                "Lnet/minecraft/util/ResourceLocation;", null, null));
        holder.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "model",
                "Lnet/minecraftforge/client/model/IModelCustom;", null, null));

        ClassNode renderer = TestAsm.classWithMethod("any/pkg/MyItemRenderer", null,
                new String[]{"net/minecraftforge/client/IItemRenderer"}, "renderItem", "()V", ACC_PUBLIC, mv -> {
                    mv.visitInsn(ACONST_NULL);
                    mv.visitFieldInsn(GETSTATIC, "any/pkg/ResourceManager", "tex",
                            "Lnet/minecraft/util/ResourceLocation;");
                    mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/renderer/texture/TextureManager",
                            "func_110577_a", "(Lnet/minecraft/util/ResourceLocation;)V", false);
                    mv.visitFieldInsn(GETSTATIC, "any/pkg/ResourceManager", "model",
                            "Lnet/minecraftforge/client/model/IModelCustom;");
                    mv.visitMethodInsn(INVOKEINTERFACE, "net/minecraftforge/client/model/IModelCustom",
                            "renderAll", "()V", true);
                });

        JarIndex jar = TestAsm.jarOf(holder, renderer);
        HolderResolver holders = new HolderResolver(jar);
        holders.scanAll();
        RendererAnalyzer analyzer = new RendererAnalyzer(jar, holders);
        RendererInfo info = analyzer.analyze("any/pkg/MyItemRenderer");

        assertTrue(info.textureFields.contains("any.pkg.ResourceManager.tex"), info.textureFields.toString());
        assertTrue(info.modelFields.contains("any.pkg.ResourceManager.model"), info.modelFields.toString());
        assertTrue(info.usesRenderAll);
    }

    @Test
    void detectsAModelBaseSubclassReferencedByARendererAsAJavaModel() {
        ClassNode model = TestAsm.bareClass("any/pkg/ModelThing", "net/minecraft/client/model/ModelBase");
        ClassNode renderer = TestAsm.classWithMethod("any/pkg/MyTesr",
                "net/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer", null,
                "draw", "()V", ACC_PUBLIC, mv -> {
                    mv.visitTypeInsn(NEW, "any/pkg/ModelThing");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/ModelThing", "<init>", "()V", false);
                    mv.visitInsn(POP);
                });

        JarIndex jar = TestAsm.jarOf(model, renderer);
        HolderResolver holders = new HolderResolver(jar);
        holders.scanAll();
        RendererAnalyzer analyzer = new RendererAnalyzer(jar, holders);
        RendererInfo info = analyzer.analyze("any/pkg/MyTesr");

        assertTrue(info.javaModelClasses.contains("any.pkg.ModelThing"), info.javaModelClasses.toString());
    }

    @Test
    void detectsAModelBaseSubclassHeldAsAFieldEvenIfNeverConstructedInAScannedMethod() {
        ClassNode model = TestAsm.bareClass("any/pkg/FieldModel", "net/minecraft/client/model/ModelBase");
        ClassNode renderer = TestAsm.bareClass("any/pkg/MyTesr2",
                "net/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer");
        renderer.fields.add(new FieldNode(0, "model", "Lany/pkg/FieldModel;", null, null));

        JarIndex jar = TestAsm.jarOf(model, renderer);
        HolderResolver holders = new HolderResolver(jar);
        holders.scanAll();
        RendererAnalyzer analyzer = new RendererAnalyzer(jar, holders);
        RendererInfo info = analyzer.analyze("any/pkg/MyTesr2");

        assertTrue(info.javaModelClasses.contains("any.pkg.FieldModel"), info.javaModelClasses.toString());
    }
}
