package dev.umb.rendermap;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises {@link BindingScanner}'s GENERIC resolvers against small synthetic classes generated
 * with ASM (see {@link TestAsm}), for every universal registration form the mandate names, plus
 * the loop/array-registration shape, the implementor-scan fallbacks, orphan reporting, and a mod
 * with no renderers at all. None of these classes live under {@code com/hbm/} — that is the point:
 * every one of these must resolve with zero mod-specific literals anywhere in the resolution path.
 */
class BindingScannerTest implements Opcodes {

    private static final String MFC = "net/minecraftforge/client/MinecraftForgeClient";
    private static final String CLIENT_REG = "cpw/mods/fml/client/registry/ClientRegistry";
    private static final String RENDER_REG = "cpw/mods/fml/client/registry/RenderingRegistry";
    private static final String IITEM_RENDERER = "net/minecraftforge/client/IItemRenderer";
    private static final String ISBRH = "cpw/mods/fml/client/registry/ISimpleBlockRenderingHandler";
    private static final String TESR = "net/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer";
    private static final String RENDER_ENTITY = "net/minecraft/client/renderer/entity/Render";
    private static final String T_ITEM = "net/minecraft/item/Item";
    private static final String ITEM_DESC = "Lnet/minecraft/item/Item;";

    @Test
    void registerItemRenderer_directCall_isTaggedGeneric() {
        ClassNode renderer = TestAsm.bareClass("any/pkg/FooRenderer", null, IITEM_RENDERER);
        ClassNode registrar = TestAsm.staticMethodClass("any/pkg/Registrar", mv -> {
            mv.visitFieldInsn(GETSTATIC, "any/pkg/SomeHolder", "foo", ITEM_DESC);
            mv.visitTypeInsn(NEW, "any/pkg/FooRenderer");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/FooRenderer", "<init>", "()V", false);
            mv.visitMethodInsn(INVOKESTATIC, MFC, "registerItemRenderer",
                    "(Lnet/minecraft/item/Item;Lnet/minecraftforge/client/IItemRenderer;)V", false);
        });
        BindingScanner bs = new BindingScanner(TestAsm.jarOf(renderer, registrar));
        bs.scanAll();

        BindingScanner.ItemBinding b = bs.itemBindings.stream()
                .filter(x -> "any/pkg/SomeHolder".equals(x.itemFieldOwner)).findFirst().orElseThrow();
        assertEquals("foo", b.itemFieldName);
        assertEquals("any.pkg.FooRenderer", b.rendererClass);
        assertEquals(BindingScanner.GENERIC, b.resolverKind);
    }

    @Test
    void bindTileEntitySpecialRenderer_isTaggedGeneric() {
        ClassNode tesr = TestAsm.bareClass("any/pkg/MyTesr", TESR);
        ClassNode registrar = TestAsm.staticMethodClass("any/pkg/Registrar", mv -> {
            mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType("any/pkg/MyTileEntity"));
            mv.visitTypeInsn(NEW, "any/pkg/MyTesr");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/MyTesr", "<init>", "()V", false);
            mv.visitMethodInsn(INVOKESTATIC, CLIENT_REG, "bindTileEntitySpecialRenderer",
                    "(Ljava/lang/Class;Lnet/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer;)V",
                    false);
        });
        BindingScanner bs = new BindingScanner(TestAsm.jarOf(tesr, registrar));
        bs.scanAll();

        assertEquals(1, bs.tesrBindings.size());
        BindingScanner.TesrBinding t = bs.tesrBindings.get(0);
        assertEquals("any.pkg.MyTileEntity", t.teClass);
        assertEquals("any.pkg.MyTesr", t.rendererClass);
        assertEquals(BindingScanner.GENERIC, t.resolverKind);
        assertTrue(bs.orphanRenderers.isEmpty(), "bound TESR must not also be reported as orphan");
    }

    @Test
    void registerBlockHandler_bothOverloads_areTaggedGeneric() {
        ClassNode h1 = TestAsm.bareClass("any/pkg/OneArgHandler", null, ISBRH);
        ClassNode h2 = TestAsm.bareClass("any/pkg/TwoArgHandler", null, ISBRH);
        ClassNode registrar = TestAsm.staticMethodClass("any/pkg/Registrar", mv -> {
            // 1-arg overload
            mv.visitTypeInsn(NEW, "any/pkg/OneArgHandler");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/OneArgHandler", "<init>", "()V", false);
            mv.visitMethodInsn(INVOKESTATIC, RENDER_REG, "registerBlockHandler",
                    "(Lcpw/mods/fml/client/registry/ISimpleBlockRenderingHandler;)V", false);
            // 2-arg (int, handler) overload
            mv.visitIntInsn(BIPUSH, 42);
            mv.visitTypeInsn(NEW, "any/pkg/TwoArgHandler");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/TwoArgHandler", "<init>", "()V", false);
            mv.visitMethodInsn(INVOKESTATIC, RENDER_REG, "registerBlockHandler",
                    "(ILcpw/mods/fml/client/registry/ISimpleBlockRenderingHandler;)V", false);
        });
        BindingScanner bs = new BindingScanner(TestAsm.jarOf(h1, h2, registrar));
        bs.scanAll();

        List<String> handlers = bs.isbrhBindings.stream().map(b -> b.handlerClass).toList();
        assertTrue(handlers.contains("any.pkg.OneArgHandler"), handlers.toString());
        assertTrue(handlers.contains("any.pkg.TwoArgHandler"), handlers.toString());
        for (BindingScanner.IsbrhBinding b : bs.isbrhBindings)
            assertEquals(BindingScanner.GENERIC, b.resolverKind);
    }

    @Test
    void registerEntityRenderingHandler_isTaggedGeneric() {
        ClassNode render = TestAsm.bareClass("any/pkg/MyRender", RENDER_ENTITY);
        ClassNode registrar = TestAsm.staticMethodClass("any/pkg/Registrar", mv -> {
            mv.visitLdcInsn(org.objectweb.asm.Type.getObjectType("any/pkg/MyEntity"));
            mv.visitTypeInsn(NEW, "any/pkg/MyRender");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/MyRender", "<init>", "()V", false);
            mv.visitMethodInsn(INVOKESTATIC, RENDER_REG, "registerEntityRenderingHandler",
                    "(Ljava/lang/Class;Lnet/minecraft/client/renderer/entity/Render;)V", false);
        });
        BindingScanner bs = new BindingScanner(TestAsm.jarOf(render, registrar));
        bs.scanAll();

        assertEquals(1, bs.entityBindings.size());
        BindingScanner.EntityBinding e = bs.entityBindings.get(0);
        assertEquals("any.pkg.MyEntity", e.entityClass);
        assertEquals("any.pkg.MyRender", e.rendererClass);
        assertEquals(BindingScanner.GENERIC, e.resolverKind);
        assertTrue(bs.orphanRenderers.isEmpty(), "bound Render must not also be reported as orphan");
    }

    @Test
    void itemSubclassDirectlyImplementingIItemRenderer_isFoundWithNoCallSiteAtAll() {
        // Generalizes HBM's own IItemRendererProvider "return this" pattern with the STANDARD
        // Forge interface: no registration call site anywhere in this jar.
        ClassNode selfRenderer = TestAsm.bareClass("any/pkg/SelfRenderItem", T_ITEM, IITEM_RENDERER);
        BindingScanner bs = new BindingScanner(TestAsm.jarOf(selfRenderer));
        bs.scanAll();

        BindingScanner.ItemBinding b = bs.itemBindings.stream()
                .filter(x -> "any.pkg.SelfRenderItem".equals(x.itemSelfClass)).findFirst().orElseThrow();
        assertEquals("any.pkg.SelfRenderItem", b.rendererClass);
        assertEquals(BindingScanner.GENERIC, b.resolverKind);
        assertTrue(b.producer.contains("self-implementing"), b.producer);
    }

    @Test
    void isbrhImplementor_isFoundEvenWithNoRegisterBlockHandlerCallSiteAnywhere() {
        ClassNode isbrh = TestAsm.classWithMethod("any/pkg/HeldNotCalled", null, new String[]{ISBRH},
                "getRenderId", "()I", ACC_PUBLIC, mv -> {
                    mv.visitFieldInsn(GETSTATIC, "any/pkg/HeldNotCalled", "RENDER_ID", "I");
                    mv.visitInsn(IRETURN);
                });
        BindingScanner bs = new BindingScanner(TestAsm.jarOf(isbrh));
        bs.scanAll();
        bs.resolveIsbrhRenderIds();

        BindingScanner.IsbrhBinding b = bs.isbrhBindings.stream()
                .filter(x -> "any.pkg.HeldNotCalled".equals(x.handlerClass)).findFirst().orElseThrow();
        assertEquals(BindingScanner.GENERIC, b.resolverKind);
        assertTrue(b.producer.startsWith("implementor-scan"), b.producer);
        assertEquals("any/pkg/HeldNotCalled", b.renderIdFieldOwner);
        assertEquals("RENDER_ID", b.renderIdFieldName);
    }

    @Test
    void orphanTesrAndEntityRenderer_areReportedNotSilentlyDropped() {
        ClassNode tesr = TestAsm.bareClass("any/pkg/UnboundTesr", TESR);
        ClassNode render = TestAsm.bareClass("any/pkg/UnboundRender", RENDER_ENTITY);
        BindingScanner bs = new BindingScanner(TestAsm.jarOf(tesr, render));
        bs.scanAll();

        assertEquals(2, bs.orphanRenderers.size());
        assertTrue(bs.orphanRenderers.stream().anyMatch(o ->
                "any.pkg.UnboundTesr".equals(o.rendererClass) && "TileEntitySpecialRenderer".equals(o.interfaceOrSuper)
                        && o.reason != null));
        assertTrue(bs.orphanRenderers.stream().anyMatch(o ->
                "any.pkg.UnboundRender".equals(o.rendererClass) && "Render".equals(o.interfaceOrSuper)
                        && o.reason != null));
    }

    @Test
    void loopOverACompileTimeArrayOfIsbrhHandlers_bindsEveryElement() {
        // for (int i = 0; i < 3; i++) RenderingRegistry.registerBlockHandler(handlers[i]);
        // A classic javac `iinc`-based for-loop over a compile-time-built array — the shape the
        // mandate explicitly calls out ("renderers registered in a loop or from an array").
        ClassNode h0 = TestAsm.bareClass("any/pkg/H0", null, ISBRH);
        ClassNode h1 = TestAsm.bareClass("any/pkg/H1", null, ISBRH);
        ClassNode h2 = TestAsm.bareClass("any/pkg/H2", null, ISBRH);
        ClassNode registrar = TestAsm.staticMethodClass("any/pkg/Registrar", mv -> {
            String ISBRH_DESC = "Lcpw/mods/fml/client/registry/ISimpleBlockRenderingHandler;";
            mv.visitIntInsn(BIPUSH, 3);
            mv.visitTypeInsn(ANEWARRAY, "cpw/mods/fml/client/registry/ISimpleBlockRenderingHandler");
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_0);
            mv.visitTypeInsn(NEW, "any/pkg/H0");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/H0", "<init>", "()V", false);
            mv.visitInsn(AASTORE);
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_1);
            mv.visitTypeInsn(NEW, "any/pkg/H1");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/H1", "<init>", "()V", false);
            mv.visitInsn(AASTORE);
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_2);
            mv.visitTypeInsn(NEW, "any/pkg/H2");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/H2", "<init>", "()V", false);
            mv.visitInsn(AASTORE);
            mv.visitVarInsn(ASTORE, 0); // arr
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, 1); // i = 0
            Label loop = new Label();
            Label end = new Label();
            mv.visitLabel(loop);
            mv.visitVarInsn(ILOAD, 1);
            mv.visitIntInsn(BIPUSH, 3);
            mv.visitJumpInsn(IF_ICMPGE, end);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 1);
            mv.visitInsn(AALOAD);
            mv.visitMethodInsn(INVOKESTATIC, RENDER_REG, "registerBlockHandler", "(" + ISBRH_DESC + ")V", false);
            mv.visitIincInsn(1, 1);
            mv.visitJumpInsn(GOTO, loop);
            mv.visitLabel(end);
        });
        BindingScanner bs = new BindingScanner(TestAsm.jarOf(h0, h1, h2, registrar));
        bs.scanAll();

        List<String> handlers = bs.isbrhBindings.stream().map(b -> b.handlerClass).toList();
        assertTrue(handlers.contains("any.pkg.H0"), handlers.toString());
        assertTrue(handlers.contains("any.pkg.H1"), handlers.toString());
        assertTrue(handlers.contains("any.pkg.H2"), handlers.toString());
    }

    @Test
    void parallelArraysOfItemsAndRenderers_pairUpPositionally() {
        // for (int i = 0; i < 2; i++) registerItemRenderer(items[i], renderers[i]);
        ClassNode rA = TestAsm.bareClass("any/pkg/RendA", null, IITEM_RENDERER);
        ClassNode rB = TestAsm.bareClass("any/pkg/RendB", null, IITEM_RENDERER);
        ClassNode registrar = TestAsm.staticMethodClass("any/pkg/Registrar", mv -> {
            // Item[] items = { Holder.a, Holder.b };
            mv.visitInsn(ICONST_2);
            mv.visitTypeInsn(ANEWARRAY, "net/minecraft/item/Item");
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_0);
            mv.visitFieldInsn(GETSTATIC, "any/pkg/Holder", "a", ITEM_DESC);
            mv.visitInsn(AASTORE);
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_1);
            mv.visitFieldInsn(GETSTATIC, "any/pkg/Holder", "b", ITEM_DESC);
            mv.visitInsn(AASTORE);
            mv.visitVarInsn(ASTORE, 0); // items[]
            // IItemRenderer[] renderers = { new RendA(), new RendB() };
            mv.visitInsn(ICONST_2);
            mv.visitTypeInsn(ANEWARRAY, "net/minecraftforge/client/IItemRenderer");
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_0);
            mv.visitTypeInsn(NEW, "any/pkg/RendA");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/RendA", "<init>", "()V", false);
            mv.visitInsn(AASTORE);
            mv.visitInsn(DUP);
            mv.visitInsn(ICONST_1);
            mv.visitTypeInsn(NEW, "any/pkg/RendB");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/RendB", "<init>", "()V", false);
            mv.visitInsn(AASTORE);
            mv.visitVarInsn(ASTORE, 1); // renderers[]
            mv.visitInsn(ICONST_0);
            mv.visitVarInsn(ISTORE, 2); // i = 0
            Label loop = new Label();
            Label end = new Label();
            mv.visitLabel(loop);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitInsn(ICONST_2);
            mv.visitJumpInsn(IF_ICMPGE, end);
            mv.visitVarInsn(ALOAD, 0);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitInsn(AALOAD);
            mv.visitVarInsn(ALOAD, 1);
            mv.visitVarInsn(ILOAD, 2);
            mv.visitInsn(AALOAD);
            mv.visitMethodInsn(INVOKESTATIC, MFC, "registerItemRenderer",
                    "(Lnet/minecraft/item/Item;Lnet/minecraftforge/client/IItemRenderer;)V", false);
            mv.visitIincInsn(2, 1);
            mv.visitJumpInsn(GOTO, loop);
            mv.visitLabel(end);
        });
        BindingScanner bs = new BindingScanner(TestAsm.jarOf(rA, rB, registrar));
        bs.scanAll();

        BindingScanner.ItemBinding ba = bs.itemBindings.stream()
                .filter(x -> "a".equals(x.itemFieldName)).findFirst().orElseThrow();
        BindingScanner.ItemBinding bb = bs.itemBindings.stream()
                .filter(x -> "b".equals(x.itemFieldName)).findFirst().orElseThrow();
        assertEquals("any.pkg.RendA", ba.rendererClass);
        assertEquals("any.pkg.RendB", bb.rendererClass);
    }

    @Test
    void modWithNoRenderersAtAll_producesEmptyBindingsAndDoesNotThrow() {
        ClassNode unrelated = TestAsm.bareClass("any/pkg/PlainNonRendererClass", null);
        BindingScanner bs = new BindingScanner(TestAsm.jarOf(unrelated));
        assertDoesNotThrow(bs::scanAll);
        assertDoesNotThrow(bs::resolveIsbrhRenderIds);

        assertTrue(bs.itemBindings.isEmpty());
        assertTrue(bs.tesrBindings.isEmpty());
        assertTrue(bs.isbrhBindings.isEmpty());
        assertTrue(bs.entityBindings.isEmpty());
        assertTrue(bs.orphanRenderers.isEmpty());
    }

    @Test
    void bonusResolversCanBeSwitchedOffWithoutRemovingAnyGenericBinding() {
        ClassNode renderer = TestAsm.bareClass("any/pkg/FooRenderer2", null, IITEM_RENDERER);
        ClassNode registrar = TestAsm.staticMethodClass("any/pkg/Registrar", mv -> {
            mv.visitFieldInsn(GETSTATIC, "any/pkg/SomeHolder", "foo2", ITEM_DESC);
            mv.visitTypeInsn(NEW, "any/pkg/FooRenderer2");
            mv.visitInsn(DUP);
            mv.visitMethodInsn(INVOKESPECIAL, "any/pkg/FooRenderer2", "<init>", "()V", false);
            mv.visitMethodInsn(INVOKESTATIC, MFC, "registerItemRenderer",
                    "(Lnet/minecraft/item/Item;Lnet/minecraftforge/client/IItemRenderer;)V", false);
        });
        JarIndex jar = TestAsm.jarOf(renderer, registrar);

        BindingScanner withBonus = new BindingScanner(jar);
        withBonus.scanAll();
        BindingScanner genericOnly = new BindingScanner(jar);
        genericOnly.includeBonusResolvers = false;
        genericOnly.scanAll();

        assertEquals(withBonus.itemBindings.size(), genericOnly.itemBindings.size(),
                "disabling the bonus resolver must not remove a binding the generic pass already found");
    }

    /**
     * Iron Chests' real shape (mandate #3, "TESR through one level of CommonProxy indirection
     * with an enum-field class argument"), confirmed by direct bytecode inspection of
     * {@code cpw.mods.ironchest.client.ClientProxy.registerTileEntitySpecialRenderer
     * (IronChestType type)}: {@code ClientRegistry.bindTileEntitySpecialRenderer(type.clazz, new
     * TileEntityIronChestRenderer())}, where {@code type} is this method's OWN enum-typed
     * parameter (bound to a DIFFERENT constant on each of the enum's own several call sites
     * elsewhere in the jar — which this analysis never traces) and {@code clazz} is an instance
     * field the enum's constructor sets from one of ITS OWN constructor arguments (a plain {@code
     * Class} literal at each constant's {@code <clinit>} declaration).
     *
     * <p>Since the receiver of the {@code getfield} is a bare method parameter, not a array
     * element or a compile-time constant, the ONLY reason this is resolvable at all without
     * tracing every caller (an unbounded search this project deliberately avoids) is that the
     * parameter's OWN declared type is a jar-declared enum: the full, fixed set of values it could
     * ever hold is already known from the enum's own declaration. Two constants (RED/BLUE) are
     * enough to prove the renderer is broadcast to both while each gets its OWN class.
     */
    @Test
    void tesrClassArgumentIsAGetfieldOnAnEnumTypedParameter_expandsToOneBindingPerConstant() throws Exception {
        String enumName = "any/pkg11/ChestType";
        org.objectweb.asm.ClassWriter ecw = new org.objectweb.asm.ClassWriter(0);
        ecw.visit(V1_8, ACC_PUBLIC | ACC_FINAL | ACC_SUPER | ACC_ENUM, enumName, null, "java/lang/Enum", null);
        ecw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, "RED", "L" + enumName + ";", null, null).visitEnd();
        ecw.visitField(ACC_PUBLIC | ACC_STATIC | ACC_FINAL | ACC_ENUM, "BLUE", "L" + enumName + ";", null, null).visitEnd();
        ecw.visitField(ACC_PRIVATE, "clazz", "Ljava/lang/Class;", null, null).visitEnd();

        org.objectweb.asm.MethodVisitor ctor = ecw.visitMethod(ACC_PRIVATE, "<init>",
                "(Ljava/lang/String;ILjava/lang/Class;)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitVarInsn(ILOAD, 2);
        ctor.visitMethodInsn(INVOKESPECIAL, "java/lang/Enum", "<init>", "(Ljava/lang/String;I)V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 3);
        ctor.visitFieldInsn(PUTFIELD, enumName, "clazz", "Ljava/lang/Class;");
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(0, 0);
        ctor.visitEnd();

        org.objectweb.asm.MethodVisitor clinit = ecw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, enumName);
        clinit.visitInsn(DUP);
        clinit.visitLdcInsn("RED");
        clinit.visitInsn(ICONST_0);
        clinit.visitLdcInsn(org.objectweb.asm.Type.getObjectType("any/pkg11/RedChestTE"));
        clinit.visitMethodInsn(INVOKESPECIAL, enumName, "<init>", "(Ljava/lang/String;ILjava/lang/Class;)V", false);
        clinit.visitFieldInsn(PUTSTATIC, enumName, "RED", "L" + enumName + ";");
        clinit.visitTypeInsn(NEW, enumName);
        clinit.visitInsn(DUP);
        clinit.visitLdcInsn("BLUE");
        clinit.visitInsn(ICONST_1);
        clinit.visitLdcInsn(org.objectweb.asm.Type.getObjectType("any/pkg11/BlueChestTE"));
        clinit.visitMethodInsn(INVOKESPECIAL, enumName, "<init>", "(Ljava/lang/String;ILjava/lang/Class;)V", false);
        clinit.visitFieldInsn(PUTSTATIC, enumName, "BLUE", "L" + enumName + ";");
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(0, 0);
        clinit.visitEnd();
        ecw.visitEnd();
        ClassNode enumCn = new ClassNode();
        new org.objectweb.asm.ClassReader(ecw.toByteArray()).accept(enumCn, 0);

        ClassNode tesr = TestAsm.bareClass("any/pkg11/ChestRenderer", TESR);
        ClassNode proxy = TestAsm.classWithMethod("any/pkg11/ClientProxy", null, null,
                "registerTileEntitySpecialRenderer", "(L" + enumName + ";)V", ACC_PUBLIC, mv -> {
                    mv.visitVarInsn(ALOAD, 1);
                    mv.visitFieldInsn(GETFIELD, enumName, "clazz", "Ljava/lang/Class;");
                    mv.visitTypeInsn(NEW, "any/pkg11/ChestRenderer");
                    mv.visitInsn(DUP);
                    mv.visitMethodInsn(INVOKESPECIAL, "any/pkg11/ChestRenderer", "<init>", "()V", false);
                    mv.visitMethodInsn(INVOKESTATIC, CLIENT_REG, "bindTileEntitySpecialRenderer",
                            "(Ljava/lang/Class;L" + TESR + ";)V", false);
                    mv.visitInsn(RETURN);
                });

        BindingScanner bs = new BindingScanner(TestAsm.jarOf(enumCn, tesr, proxy));
        bs.scanAll();

        assertEquals(2, bs.tesrBindings.size(), "one binding per enum constant, not one opaque"
                + " unresolved row for the whole call site");
        List<String> classes = bs.tesrBindings.stream().map(t -> t.teClass).sorted().toList();
        assertEquals(List.of("any.pkg11.BlueChestTE", "any.pkg11.RedChestTE"), classes);
        for (BindingScanner.TesrBinding t : bs.tesrBindings) {
            assertEquals("any.pkg11.ChestRenderer", t.rendererClass,
                    "the SAME renderer class is broadcast to every constant's own TE class");
            assertNull(t.unresolvedReason);
        }
    }
}
