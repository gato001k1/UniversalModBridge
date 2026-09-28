package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Entity-rendering content resolution: the extraction pipeline for what an entity renderer draws
 * (geometry + texture), as opposed to {@link BindingScanner}'s pre-existing job of finding WHICH
 * renderer is bound to WHICH entity class (the {@code registerEntityRenderingHandler} binding
 * itself). None of these classes live under {@code com/hbm/} or any other real mod's package —
 * every resolver under test here is the generic vanilla {@code Render}/{@code ModelRenderer} API,
 * per ENTITY-RENDER-EXTRACTION.md.
 */
class EntityRenderResolutionTest implements Opcodes {

    private static final String RENDER_ENTITY = "net/minecraft/client/renderer/entity/Render";
    private static final String MODEL_BASE = "net/minecraft/client/model/ModelBase";
    private static final String MODEL_RENDERER = "net/minecraft/client/model/ModelRenderer";
    private static final String RESLOC = "net/minecraft/util/ResourceLocation";
    private static final String ENTITY = "net/minecraft/entity/Entity";
    private static final String GET_ENTITY_TEXTURE_DESC = "(Lnet/minecraft/entity/Entity;)Lnet/minecraft/util/ResourceLocation;";

    /** A class with a custom {@code <init>} body (unlike {@link TestAsm#classWithMethod}, which
     *  always emits a trivial one) — needed here because the Java-model geometry test's whole
     *  point is the {@code <init>} body's own box/rotation-point construction sequence. */
    private static ClassNode classWithCustomInit(String name, String superName,
                                                  java.util.function.Consumer<MethodVisitor> initBody) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(V1_8, ACC_PUBLIC, name, null, superName, null);
        MethodVisitor init = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        init.visitCode();
        initBody.accept(init);
        init.visitInsn(RETURN);
        init.visitMaxs(0, 0);
        init.visitEnd();
        cw.visitEnd();
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    // ================================================================================
    // Case 1: an entity renderer resolving a Techne/Java model (box + rotation point + texture
    // offset), generically from the model class's own field layout and <init> body.
    // ================================================================================

    @Test
    void entityRendererResolvesATechneJavaModelsBoxAndRotationPoint() {
        ClassNode model = classWithCustomInit("any/pkg20/ModelThing", MODEL_BASE, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, MODEL_BASE, "<init>", "()V", false);
            // this.part = new ModelRenderer(this, 0, 0);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitTypeInsn(NEW, MODEL_RENDERER);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKESPECIAL, MODEL_RENDERER, "<init>",
                    "(Lnet/minecraft/client/model/ModelBase;II)V", false);
            mv.visitFieldInsn(PUTFIELD, "any/pkg20/ModelThing", "part", "L" + MODEL_RENDERER + ";");
            // this.part.addBox(-2F, -2F, -2F, 4, 4, 4);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, "any/pkg20/ModelThing", "part", "L" + MODEL_RENDERER + ";");
            mv.visitLdcInsn(-2F); mv.visitLdcInsn(-2F); mv.visitLdcInsn(-2F);
            mv.visitInsn(ICONST_4); mv.visitInsn(ICONST_4); mv.visitInsn(ICONST_4);
            mv.visitMethodInsn(INVOKEVIRTUAL, MODEL_RENDERER, "func_78789_a",
                    "(FFFIII)L" + MODEL_RENDERER + ";", false);
            mv.visitInsn(POP);
            // this.part.setRotationPoint(0F, 1F, 0F);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, "any/pkg20/ModelThing", "part", "L" + MODEL_RENDERER + ";");
            mv.visitInsn(FCONST_0); mv.visitInsn(FCONST_1); mv.visitInsn(FCONST_0);
            mv.visitMethodInsn(INVOKEVIRTUAL, MODEL_RENDERER, "func_78793_a", "(FFF)V", false);
        });
        model.fields.add(new FieldNode(ACC_PUBLIC, "part", "L" + MODEL_RENDERER + ";", null, null));

        ClassNode renderer = TestAsm.classWithMethod("any/pkg20/MyEntityRenderer", RENDER_ENTITY, null,
                "makeModel", "()V", ACC_PRIVATE, mv -> {
                    mv.visitTypeInsn(NEW, "any/pkg20/ModelThing");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg20/ModelThing", "<init>", "()V", false);
                    mv.visitInsn(POP);
                });

        JarIndex jar = TestAsm.jarOf(model, renderer);
        HolderResolver holders = new HolderResolver(jar);
        holders.scanAll();
        RendererAnalyzer analyzer = new RendererAnalyzer(jar, holders);
        RendererInfo info = analyzer.analyze("any/pkg20/MyEntityRenderer");

        assertTrue(info.javaModelClasses.contains("any.pkg20.ModelThing"), info.javaModelClasses.toString());
        List<RendererInfo.TechnePart> parts = info.javaModelParts.get("any.pkg20.ModelThing");
        assertNotNull(parts, "expected geometry to be recovered for the Java model");
        assertEquals(1, parts.size());
        RendererInfo.TechnePart p = parts.get(0);
        assertEquals("any.pkg20.ModelThing.part", p.field);
        assertEquals(Integer.valueOf(0), p.texOffsetX);
        assertEquals(Integer.valueOf(0), p.texOffsetY);
        assertEquals(-2F, p.boxX); assertEquals(-2F, p.boxY); assertEquals(-2F, p.boxZ);
        assertEquals(Integer.valueOf(4), p.boxW); assertEquals(Integer.valueOf(4), p.boxH); assertEquals(Integer.valueOf(4), p.boxD);
        assertEquals(0F, p.rotPointX); assertEquals(1F, p.rotPointY); assertEquals(0F, p.rotPointZ);
        assertNull(p.unresolvedReason);
    }

    // ================================================================================
    // Case 2: an entity renderer resolving a texture via the vanilla getEntityTexture-equivalent
    // method (func_110775_a — javap/SRG-verified, see ENTITY-RENDER-EXTRACTION.md).
    // ================================================================================

    @Test
    void entityRendererResolvesATextureViaGetEntityTexture() {
        ClassNode holder = TestAsm.staticMethodClass("any/pkg21/ResourceManager", mv -> {
            mv.visitTypeInsn(NEW, RESLOC);
            mv.visitInsn(DUP);
            mv.visitLdcInsn("any");
            mv.visitLdcInsn("textures/entity/thing.png");
            mv.visitMethodInsn(INVOKESPECIAL, RESLOC, "<init>",
                    "(Ljava/lang/String;Ljava/lang/String;)V", false);
            mv.visitFieldInsn(PUTSTATIC, "any/pkg21/ResourceManager", "TEX", "L" + RESLOC + ";");
        });
        holder.fields.add(new FieldNode(ACC_PUBLIC | ACC_STATIC, "TEX", "L" + RESLOC + ";", null, null));

        ClassNode renderer = TestAsm.classWithMethod("any/pkg21/MyEntityRenderer", RENDER_ENTITY, null,
                "func_110775_a", GET_ENTITY_TEXTURE_DESC, ACC_PROTECTED, mv -> {
                    mv.visitFieldInsn(GETSTATIC, "any/pkg21/ResourceManager", "TEX", "L" + RESLOC + ";");
                    mv.visitInsn(ARETURN);
                });

        JarIndex jar = TestAsm.jarOf(holder, renderer);
        HolderResolver holders = new HolderResolver(jar);
        holders.scanAll();
        RendererAnalyzer analyzer = new RendererAnalyzer(jar, holders);
        RendererInfo info = analyzer.analyze("any/pkg21/MyEntityRenderer");

        assertTrue(info.getEntityTextureFound);
        assertEquals(1, info.entityTextureRefs.size());
        RendererInfo.EntityTextureRef ref = info.entityTextureRefs.get(0);
        assertEquals("static-field", ref.kind);
        assertEquals("any:textures/entity/thing.png", ref.path);
        assertEquals("assets/any/textures/entity/thing.png", ref.assetPath);
        assertNull(ref.unresolvedReason);
    }

    // ================================================================================
    // Case 3: an entity renderer whose model/texture cannot be resolved generically — correctly
    // left unresolved, not guessed. Covers BOTH failure shapes: a getEntityTexture body that
    // computes its ResourceLocation from a genuinely dynamic (non-literal) value, and a Techne
    // addBox call whose dimension is read from an instance field this pass cannot prove a single
    // constant value for (mirrors Railcraft's real, live TunnelBore model — see
    // ENTITY-RENDER-EXTRACTION.md — not a contrived-only case).
    // ================================================================================

    @Test
    void entityRendererWithGenuinelyDynamicTextureAndGeometryIsHonestlyLeftUnresolved() {
        ClassNode model = classWithCustomInit("any/pkg22/ModelDynamic", MODEL_BASE, mv -> {
            mv.visitVarInsn(ALOAD, 0);
            mv.visitMethodInsn(INVOKESPECIAL, MODEL_BASE, "<init>", "()V", false);
            // this.part = new ModelRenderer(this, 0, 0);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitTypeInsn(NEW, MODEL_RENDERER);
            mv.visitInsn(DUP);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitInsn(ICONST_0);
            mv.visitInsn(ICONST_0);
            mv.visitMethodInsn(INVOKESPECIAL, MODEL_RENDERER, "<init>",
                    "(Lnet/minecraft/client/model/ModelBase;II)V", false);
            mv.visitFieldInsn(PUTFIELD, "any/pkg22/ModelDynamic", "part", "L" + MODEL_RENDERER + ";");
            // this.part.addBox(0F, 0F, 0F, this.width, 4, 4) — `width` is never assigned anywhere
            // in this class (deliberately: MethodSim's tryInstanceFieldSingleAssignment correctly
            // finds zero assignments, so this stays honestly UNKNOWN rather than a guessed value).
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, "any/pkg22/ModelDynamic", "part", "L" + MODEL_RENDERER + ";");
            mv.visitInsn(FCONST_0); mv.visitInsn(FCONST_0); mv.visitInsn(FCONST_0);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitFieldInsn(GETFIELD, "any/pkg22/ModelDynamic", "width", "I");
            mv.visitInsn(ICONST_4);
            mv.visitInsn(ICONST_4);
            mv.visitMethodInsn(INVOKEVIRTUAL, MODEL_RENDERER, "func_78789_a",
                    "(FFFIII)L" + MODEL_RENDERER + ";", false);
            mv.visitInsn(POP);
        });
        model.fields.add(new FieldNode(ACC_PUBLIC, "part", "L" + MODEL_RENDERER + ";", null, null));
        model.fields.add(new FieldNode(ACC_PUBLIC, "width", "I", null, null));

        ClassNode renderer = TestAsm.classWithMethod("any/pkg22/MyEntityRenderer", RENDER_ENTITY, null,
                "func_110775_a", GET_ENTITY_TEXTURE_DESC, ACC_PROTECTED, mv -> {
                    // return new ResourceLocation("any", entity.toString()); — a genuinely dynamic
                    // (non-literal) second argument, never resolvable to a compile-time constant.
                    mv.visitTypeInsn(NEW, RESLOC);
                    mv.visitInsn(DUP);
                    mv.visitLdcInsn("any");
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/lang/Object", "toString", "()Ljava/lang/String;", false);
                    mv.visitMethodInsn(INVOKESPECIAL, RESLOC, "<init>",
                            "(Ljava/lang/String;Ljava/lang/String;)V", false);
                    mv.visitInsn(ARETURN);
                });
        ClassNode ownerOfModel = TestAsm.classWithMethod("any/pkg22/MakesModel", null, null,
                "makeModel", "()V", ACC_PUBLIC, mv -> {
                    mv.visitTypeInsn(NEW, "any/pkg22/ModelDynamic");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg22/ModelDynamic", "<init>", "()V", false);
                    mv.visitInsn(POP);
                });

        JarIndex jar = TestAsm.jarOf(model, renderer, ownerOfModel);
        HolderResolver holders = new HolderResolver(jar);
        holders.scanAll();
        RendererAnalyzer analyzer = new RendererAnalyzer(jar, holders);

        // getEntityTexture: found, but honestly unresolved (never guesses at entity.toString()).
        RendererInfo rendererInfo = analyzer.analyze("any/pkg22/MyEntityRenderer");
        assertTrue(rendererInfo.getEntityTextureFound);
        assertEquals(1, rendererInfo.entityTextureRefs.size());
        RendererInfo.EntityTextureRef ref = rendererInfo.entityTextureRefs.get(0);
        assertNull(ref.path, "a dynamically-computed ResourceLocation must never be reported as resolved");
        assertNotNull(ref.unresolvedReason);

        // Techne geometry: the model class is found, but its one box is honestly unresolved
        // (the width argument is a `this.field` this pass proved has no assignment anywhere).
        RendererInfo modelUserInfo = analyzer.analyze("any/pkg22/MakesModel");
        assertTrue(modelUserInfo.javaModelClasses.contains("any.pkg22.ModelDynamic"));
        List<RendererInfo.TechnePart> parts = modelUserInfo.javaModelParts.get("any.pkg22.ModelDynamic");
        assertNotNull(parts);
        assertEquals(1, parts.size());
        RendererInfo.TechnePart p = parts.get(0);
        assertNull(p.boxW, "a non-constant addBox argument must never be reported as a resolved box");
        assertNotNull(p.unresolvedReason);
    }
}
