package dev.umb.guimap;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * End-to-end test of the GuiContainer extraction path against a small ASM-generated class built
 * to the exact shape javap showed for {@code com.hbm.inventory.gui.GUIFurnaceIron} in the real
 * jar: a constructor that builds its own Container and sets field_146999_f/field_147000_g,
 * a background layer that binds a static ResourceLocation texture field and draws one static
 * full-panel rect plus one progress-driven dynamic rect, and a foreground layer that draws one
 * translated label. Mirrors umb-rendermap's MethodSimTest approach of writing bytecode directly
 * with ASM rather than mining it from a real mod jar.
 */
class GuiClassAnalyzerTest {

    private static final String GUI = "test/GuiFakeMachine";
    private static final String CONTAINER = "test/ContainerFakeMachine";
    private static final String TE = "test/TileEntityFake";
    private static final String RL = "Lnet/minecraft/util/ResourceLocation;";

    private static JarIndex buildJar() {
        JarIndex jar = new JarIndex();
        jar.classes.put(CONTAINER, containerClass());
        jar.classes.put(GUI, guiClass());
        // minimal valid PNG: 8-byte signature + 4-byte IHDR length + "IHDR" + 4-byte width + 4-byte height
        byte[] png = new byte[24];
        byte[] sig = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(sig, 0, png, 0, 8);
        png[12] = 'I'; png[13] = 'H'; png[14] = 'D'; png[15] = 'R';
        putBE32(png, 16, 200); putBE32(png, 20, 150);
        jar.assets.put("assets/modid/textures/gui/fake.png", png);
        return jar;
    }

    private static void putBE32(byte[] b, int off, int v) {
        b[off] = (byte) (v >>> 24); b[off + 1] = (byte) (v >>> 16); b[off + 2] = (byte) (v >>> 8); b[off + 3] = (byte) v;
    }

    private static ClassNode containerClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, CONTAINER, null, Vanilla.CONTAINER, null);
        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, Vanilla.CONTAINER, "<init>", "()V", false);
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(2, 3);
        ctor.visitEnd();
        cw.visitEnd();
        return toNode(cw);
    }

    private static ClassNode guiClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "texture", RL, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE, "machine", "L" + TE + ";", null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, Vanilla.RESOURCE_LOCATION);
        clinit.visitInsn(DUP);
        clinit.visitLdcInsn("modid:textures/gui/fake.png");
        clinit.visitMethodInsn(INVOKESPECIAL, Vanilla.RESOURCE_LOCATION, "<init>", "(Ljava/lang/String;)V", false);
        clinit.visitFieldInsn(PUTSTATIC, GUI, "texture", RL);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(4, 0);
        clinit.visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitTypeInsn(NEW, CONTAINER);
        ctor.visitInsn(DUP);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitVarInsn(ALOAD, 2);
        ctor.visitMethodInsn(INVOKESPECIAL, CONTAINER, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", false);
        ctor.visitMethodInsn(INVOKESPECIAL, Vanilla.GUI_CONTAINER, "<init>", "(Lnet/minecraft/inventory/Container;)V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitVarInsn(ALOAD, 2);
        ctor.visitFieldInsn(PUTFIELD, GUI, "machine", "L" + TE + ";");
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitIntInsn(SIPUSH, 200);
        ctor.visitFieldInsn(PUTFIELD, GUI, Vanilla.F_XSIZE, "I");
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitIntInsn(SIPUSH, 150);
        ctor.visitFieldInsn(PUTFIELD, GUI, Vanilla.F_YSIZE, "I");
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(10, 10);
        ctor.visitEnd();

        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        bg.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/Minecraft", "func_71410_x",
                "()Lnet/minecraft/client/Minecraft;", false);
        bg.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/Minecraft", "func_110434_K",
                "()L" + Vanilla.TEXTURE_MANAGER + ";", false);
        bg.visitFieldInsn(GETSTATIC, GUI, "texture", RL);
        bg.visitMethodInsn(INVOKEVIRTUAL, Vanilla.TEXTURE_MANAGER, Vanilla.M_BIND_TEXTURE_MANAGER,
                "(L" + Vanilla.RESOURCE_LOCATION + ";)V", false);
        // this.func_73729_b(guiLeft, guiTop, 0, 0, xSize, ySize) — the full-panel background blit
        bg.visitVarInsn(ALOAD, 0);
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUILEFT, "I");
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUITOP, "I");
        bg.visitInsn(ICONST_0);
        bg.visitInsn(ICONST_0);
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_XSIZE, "I");
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_YSIZE, "I");
        bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        // this.func_73729_b(guiLeft+53, guiTop+36, 176, 18, this.machine.progress, 5) — dynamic progress bar
        bg.visitVarInsn(ALOAD, 0);
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUILEFT, "I");
        bg.visitIntInsn(BIPUSH, 53); bg.visitInsn(IADD);
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUITOP, "I");
        bg.visitIntInsn(BIPUSH, 36); bg.visitInsn(IADD);
        bg.visitIntInsn(SIPUSH, 176);
        bg.visitIntInsn(BIPUSH, 18);
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
        bg.visitFieldInsn(GETFIELD, TE, "progress", "I");
        bg.visitIntInsn(BIPUSH, 5);
        bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        bg.visitInsn(RETURN);
        bg.visitMaxs(12, 4);
        bg.visitEnd();

        MethodVisitor fg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_FG, Vanilla.M_DRAW_FG_DESC, null, null);
        fg.visitCode();
        fg.visitVarInsn(ALOAD, 0); fg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_FONTRENDERER, "L" + Vanilla.FONT_RENDERER + ";");
        fg.visitLdcInsn("container.inventory");
        fg.visitMethodInsn(INVOKESTATIC, Vanilla.STAT_COLLECTOR, Vanilla.M_TRANSLATE,
                "(Ljava/lang/String;)Ljava/lang/String;", false);
        fg.visitIntInsn(BIPUSH, 8);
        fg.visitIntInsn(BIPUSH, 6);
        fg.visitLdcInsn(4210752);
        fg.visitMethodInsn(INVOKEVIRTUAL, Vanilla.FONT_RENDERER, Vanilla.M_DRAW_STRING, "(Ljava/lang/String;III)I", false);
        fg.visitInsn(POP);
        fg.visitInsn(RETURN);
        fg.visitMaxs(8, 4);
        fg.visitEnd();

        cw.visitEnd();
        return toNode(cw);
    }

    private static ClassNode toNode(ClassWriter cw) {
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    private static JsonObject analyze() {
        JarIndex jar = buildJar();
        FieldConstResolver resolver = new FieldConstResolver(jar);
        ContainerPairer pairer = new ContainerPairer(jar);
        GuiClassAnalyzer analyzer = new GuiClassAnalyzer(jar, resolver, pairer);
        GuiScanner.Candidate cand = new GuiScanner.Candidate(jar.cls(GUI), GuiScanner.Kind.GUI_CONTAINER);
        return analyzer.analyze(cand);
    }

    @Test
    void resolvesExactPanelSizeFromConstructorConstants() {
        JsonObject row = analyze();
        JsonObject size = row.getAsJsonObject("size");
        assertEquals(200, size.get("xSize").getAsInt());
        assertEquals(150, size.get("ySize").getAsInt());
        assertEquals("exact", size.get("confidence").getAsString());
    }

    @Test
    void resolvesBackgroundTextureAndVerifiesItExistsInTheJarWithRealPngDimensions() {
        JsonObject row = analyze();
        var textures = row.getAsJsonArray("backgroundTextures");
        assertEquals(1, textures.size());
        JsonObject tex = textures.get(0).getAsJsonObject();
        assertTrue(tex.get("resolved").getAsBoolean());
        assertEquals("modid:textures/gui/fake.png", tex.get("path").getAsString());
        assertTrue(tex.get("existsInJar").getAsBoolean());
        assertEquals(200, tex.get("sheetWidth").getAsInt());
        assertEquals(150, tex.get("sheetHeight").getAsInt());
    }

    @Test
    void splitsStaticFullPanelRectFromDynamicProgressRect() {
        JsonObject row = analyze();
        var rects = row.getAsJsonArray("backgroundDrawRects");
        assertEquals(2, rects.size());

        JsonObject full = rects.get(0).getAsJsonObject();
        assertFalse(full.get("dynamic").getAsBoolean());
        var fullArgs = full.getAsJsonArray("args");
        // u=0, v=0, w=xSize(200 via field substitution), h=ySize(150 via field substitution)
        assertEquals("const", fullArgs.get(2).getAsJsonObject().get("kind").getAsString());
        assertEquals(0, fullArgs.get(2).getAsJsonObject().get("value").getAsInt());
        assertEquals(200, fullArgs.get(4).getAsJsonObject().get("value").getAsInt());
        assertEquals(150, fullArgs.get(5).getAsJsonObject().get("value").getAsInt());
        assertEquals("position", fullArgs.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("guiLeft", fullArgs.get(0).getAsJsonObject().get("base").getAsString());

        // The bar's width arg is a BARE `this.machine.progress` read — no multiply/divide at all.
        // Per the STATE_LINEAR classification added for the dynamic-rects lane, this is the
        // degenerate (multiplier=1, divisor=1) case of "a linear expression over one runtime
        // field", so the whole rect is no longer row-level "dynamic": every arg is now resolved to
        // either PANEL_RELATIVE, CONST or STATE_LINEAR (see DrawLayerScanner.classifyInt).
        JsonObject bar = rects.get(1).getAsJsonObject();
        assertFalse(bar.get("dynamic").getAsBoolean());
        var barArgs = bar.getAsJsonArray("args");
        assertEquals("position", barArgs.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals(53, barArgs.get(0).getAsJsonObject().get("delta").getAsInt());
        assertEquals("const", barArgs.get(2).getAsJsonObject().get("kind").getAsString());
        assertEquals(176, barArgs.get(2).getAsJsonObject().get("value").getAsInt());

        JsonObject widthArg = barArgs.get(4).getAsJsonObject();
        assertEquals("stateLinear", widthArg.get("kind").getAsString());
        assertEquals("STATE_LINEAR", widthArg.get("classification").getAsString());
        JsonObject binding = widthArg.getAsJsonObject("stateBinding");
        assertEquals(1.0, binding.get("multiplier").getAsDouble());
        assertEquals("const", binding.getAsJsonObject("divisor").get("kind").getAsString());
        assertEquals(1.0, binding.getAsJsonObject("divisor").get("value").getAsDouble());
        JsonObject src = binding.getAsJsonObject("source");
        assertEquals("progress", src.get("fieldName").getAsString());
        assertEquals("test.TileEntityFake", src.get("ownerClass").getAsString());
        assertEquals("tileEntityViaGui", src.get("origin").getAsString());
        assertEquals("this.machine.progress", src.get("originPath").getAsString());
    }

    /**
     * SCREEN-RENDER lane: a 1.7.10 GUI can {@code bindTexture} more than once inside one draw
     * method, and every {@code drawTexturedModalRect} call implicitly uses whichever bind most
     * recently executed before it. Builds a class that binds texture A, draws a rect, binds
     * texture B, draws a second rect — proves {@code textureBindIndex} tracks the bind that was
     * actually in effect for each rect (0 then 1), not just "the GUI's first texture" for both.
     */
    private static final String GUI2 = "test/GuiFakeTwoTextures";

    private static JarIndex buildJarTwoTextures() {
        JarIndex jar = buildJar();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI2, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "textureA", RL, null, null).visitEnd();
        cw.visitField(ACC_PRIVATE | ACC_STATIC, "textureB", RL, null, null).visitEnd();

        MethodVisitor clinit = cw.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.visitCode();
        clinit.visitTypeInsn(NEW, Vanilla.RESOURCE_LOCATION);
        clinit.visitInsn(DUP);
        clinit.visitLdcInsn("modid:textures/gui/fakeA.png");
        clinit.visitMethodInsn(INVOKESPECIAL, Vanilla.RESOURCE_LOCATION, "<init>", "(Ljava/lang/String;)V", false);
        clinit.visitFieldInsn(PUTSTATIC, GUI2, "textureA", RL);
        clinit.visitTypeInsn(NEW, Vanilla.RESOURCE_LOCATION);
        clinit.visitInsn(DUP);
        clinit.visitLdcInsn("modid:textures/gui/fakeB.png");
        clinit.visitMethodInsn(INVOKESPECIAL, Vanilla.RESOURCE_LOCATION, "<init>", "(Ljava/lang/String;)V", false);
        clinit.visitFieldInsn(PUTSTATIC, GUI2, "textureB", RL);
        clinit.visitInsn(RETURN);
        clinit.visitMaxs(4, 0);
        clinit.visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitTypeInsn(NEW, CONTAINER);
        ctor.visitInsn(DUP);
        ctor.visitVarInsn(ALOAD, 1);
        ctor.visitVarInsn(ALOAD, 2);
        ctor.visitMethodInsn(INVOKESPECIAL, CONTAINER, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", false);
        ctor.visitMethodInsn(INVOKESPECIAL, Vanilla.GUI_CONTAINER, "<init>", "(Lnet/minecraft/inventory/Container;)V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitIntInsn(SIPUSH, 200);
        ctor.visitFieldInsn(PUTFIELD, GUI2, Vanilla.F_XSIZE, "I");
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitIntInsn(SIPUSH, 150);
        ctor.visitFieldInsn(PUTFIELD, GUI2, Vanilla.F_YSIZE, "I");
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(10, 10);
        ctor.visitEnd();

        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        // bind A; draw rect 1 (uses A -> textureBindIndex 0)
        bindTexture(bg, GUI2, "textureA");
        drawRect(bg, GUI2, 1, 1, 0, 0, 20, 20);
        // bind B; draw rect 2 (uses B -> textureBindIndex 1)
        bindTexture(bg, GUI2, "textureB");
        drawRect(bg, GUI2, 2, 2, 0, 0, 30, 30);
        bg.visitInsn(RETURN);
        bg.visitMaxs(12, 4);
        bg.visitEnd();

        cw.visitEnd();
        jar.classes.put(GUI2, toNode(cw));
        return jar;
    }

    private static void bindTexture(MethodVisitor mv, String gui, String field) {
        mv.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/Minecraft", "func_71410_x",
                "()Lnet/minecraft/client/Minecraft;", false);
        mv.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/Minecraft", "func_110434_K",
                "()L" + Vanilla.TEXTURE_MANAGER + ";", false);
        mv.visitFieldInsn(GETSTATIC, gui, field, RL);
        mv.visitMethodInsn(INVOKEVIRTUAL, Vanilla.TEXTURE_MANAGER, Vanilla.M_BIND_TEXTURE_MANAGER,
                "(L" + Vanilla.RESOURCE_LOCATION + ";)V", false);
    }

    private static void drawRect(MethodVisitor mv, String gui, int x, int y, int u, int v, int w, int h) {
        mv.visitVarInsn(ALOAD, 0);
        mv.visitIntInsn(BIPUSH, x);
        mv.visitIntInsn(BIPUSH, y);
        mv.visitIntInsn(BIPUSH, u);
        mv.visitIntInsn(BIPUSH, v);
        mv.visitIntInsn(BIPUSH, w);
        mv.visitIntInsn(BIPUSH, h);
        mv.visitMethodInsn(INVOKEVIRTUAL, gui, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
    }

    @Test
    void tracksWhichBindTextureCallWasMostRecentBeforeEachDrawnRect() {
        JarIndex jar = buildJarTwoTextures();
        FieldConstResolver resolver = new FieldConstResolver(jar);
        ContainerPairer pairer = new ContainerPairer(jar);
        GuiClassAnalyzer analyzer = new GuiClassAnalyzer(jar, resolver, pairer);
        JsonObject row = analyzer.analyze(new GuiScanner.Candidate(jar.cls(GUI2), GuiScanner.Kind.GUI_CONTAINER));

        var textures = row.getAsJsonArray("backgroundTextures");
        assertEquals(2, textures.size());
        assertEquals("modid:textures/gui/fakeA.png", textures.get(0).getAsJsonObject().get("path").getAsString());
        assertEquals("modid:textures/gui/fakeB.png", textures.get(1).getAsJsonObject().get("path").getAsString());

        var rects = row.getAsJsonArray("backgroundDrawRects");
        assertEquals(2, rects.size());
        assertEquals(0, rects.get(0).getAsJsonObject().get("textureBindIndex").getAsInt());
        assertEquals(1, rects.get(1).getAsJsonObject().get("textureBindIndex").getAsInt());
    }

    @Test
    void resolvesTranslatedLabel() {
        JsonObject row = analyze();
        var labels = row.getAsJsonArray("foregroundLabels");
        assertEquals(1, labels.size());
        JsonObject label = labels.get(0).getAsJsonObject();
        assertFalse(label.get("dynamic").getAsBoolean());
        assertTrue(label.get("translated").getAsBoolean());
        assertEquals("container.inventory", label.get("text").getAsString());
    }

    @Test
    void pairsTheContainerConstructedInlineInTheGuiConstructor() {
        JsonObject row = analyze();
        JsonObject c = row.getAsJsonObject("container");
        assertEquals("test.ContainerFakeMachine", c.get("className").getAsString());
        assertEquals("exact", c.get("confidence").getAsString());
    }

    /**
     * Mirrors HBM's eleven {@code GUITurretXxx extends GUITurretBase} classes: the CONCRETE
     * class's own constructor forwards (InventoryPlayer, TileEntity) to an ABSTRACT mod base
     * class with no Container in its descriptor at all; the base class's OWN constructor is the
     * one that actually does {@code new ContainerFakeTurret(...)}. Confirms {@link ContainerPairer}
     * walks up past the concrete class instead of giving up at the first level.
     */
    @Test
    void pairsThroughAnIntermediateAbstractBaseClassThatActuallyBuildsTheContainer() {
        JarIndex jar = buildJar();

        ClassWriter baseCn = new ClassWriter(0);
        baseCn.visit(V1_8, ACC_PUBLIC | ACC_ABSTRACT, "test/GuiTurretBase", null, Vanilla.GUI_CONTAINER, null);
        MethodVisitor baseCtor = baseCn.visitMethod(ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", null, null);
        baseCtor.visitCode();
        baseCtor.visitVarInsn(ALOAD, 0);
        baseCtor.visitTypeInsn(NEW, "test/ContainerFakeTurret");
        baseCtor.visitInsn(DUP);
        baseCtor.visitVarInsn(ALOAD, 1);
        baseCtor.visitVarInsn(ALOAD, 2);
        baseCtor.visitMethodInsn(INVOKESPECIAL, "test/ContainerFakeTurret", "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", false);
        baseCtor.visitMethodInsn(INVOKESPECIAL, Vanilla.GUI_CONTAINER, "<init>", "(Lnet/minecraft/inventory/Container;)V", false);
        baseCtor.visitInsn(RETURN);
        baseCtor.visitMaxs(10, 10);
        baseCtor.visitEnd();
        baseCn.visitEnd();
        jar.classes.put("test/GuiTurretBase", toNode(baseCn));

        ClassWriter turretContainerCn = new ClassWriter(0);
        turretContainerCn.visit(V1_8, ACC_PUBLIC, "test/ContainerFakeTurret", null, Vanilla.CONTAINER, null);
        MethodVisitor tcCtor = turretContainerCn.visitMethod(ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", null, null);
        tcCtor.visitCode();
        tcCtor.visitVarInsn(ALOAD, 0);
        tcCtor.visitMethodInsn(INVOKESPECIAL, Vanilla.CONTAINER, "<init>", "()V", false);
        tcCtor.visitInsn(RETURN);
        tcCtor.visitMaxs(2, 3);
        tcCtor.visitEnd();
        turretContainerCn.visitEnd();
        jar.classes.put("test/ContainerFakeTurret", toNode(turretContainerCn));

        ClassWriter concreteCn = new ClassWriter(0);
        concreteCn.visit(V1_8, ACC_PUBLIC, "test/GuiFakeTurretSentry", null, "test/GuiTurretBase", null);
        MethodVisitor cCtor = concreteCn.visitMethod(ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", null, null);
        cCtor.visitCode();
        cCtor.visitVarInsn(ALOAD, 0);
        cCtor.visitVarInsn(ALOAD, 1);
        cCtor.visitVarInsn(ALOAD, 2);
        cCtor.visitMethodInsn(INVOKESPECIAL, "test/GuiTurretBase", "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", false);
        cCtor.visitInsn(RETURN);
        cCtor.visitMaxs(4, 4);
        cCtor.visitEnd();
        concreteCn.visitEnd();
        jar.classes.put("test/GuiFakeTurretSentry", toNode(concreteCn));

        FieldConstResolver resolver = new FieldConstResolver(jar);
        ContainerPairer pairer = new ContainerPairer(jar);
        GuiClassAnalyzer analyzer = new GuiClassAnalyzer(jar, resolver, pairer);
        JsonObject row = analyzer.analyze(new GuiScanner.Candidate(jar.cls("test/GuiFakeTurretSentry"), GuiScanner.Kind.GUI_CONTAINER));

        JsonObject c = row.getAsJsonObject("container");
        assertEquals("test.ContainerFakeTurret", c.get("className").getAsString());
        assertEquals("exact", c.get("confidence").getAsString());
        assertTrue(c.get("source").getAsString().contains("1 superclass level"));
    }

    /**
     * Same GUITurretBase-shaped hierarchy, but this time checking that
     * field_146999_f/field_147000_g AND the func_146976_a override declared only on the abstract
     * base class are still found and correctly attributed when analysing the CONCRETE leaf class,
     * which itself declares neither.
     */
    @Test
    void resolvesSizeAndBackgroundMethodInheritedFromAnAbstractBaseClass() {
        JarIndex jar = buildJar();

        ClassWriter baseCn = new ClassWriter(0);
        baseCn.visit(V1_8, ACC_PUBLIC | ACC_ABSTRACT, "test/GuiTurretBase2", null, Vanilla.GUI_CONTAINER, null);
        baseCn.visitField(ACC_PRIVATE | ACC_STATIC, "texture", RL, null, null).visitEnd();
        MethodVisitor bclinit = baseCn.visitMethod(ACC_STATIC, "<clinit>", "()V", null, null);
        bclinit.visitCode();
        bclinit.visitTypeInsn(NEW, Vanilla.RESOURCE_LOCATION);
        bclinit.visitInsn(DUP);
        bclinit.visitLdcInsn("modid:textures/gui/turret_base.png");
        bclinit.visitMethodInsn(INVOKESPECIAL, Vanilla.RESOURCE_LOCATION, "<init>", "(Ljava/lang/String;)V", false);
        bclinit.visitFieldInsn(PUTSTATIC, "test/GuiTurretBase2", "texture", RL);
        bclinit.visitInsn(RETURN);
        bclinit.visitMaxs(4, 0);
        bclinit.visitEnd();

        MethodVisitor baseCtor = baseCn.visitMethod(ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", null, null);
        baseCtor.visitCode();
        baseCtor.visitVarInsn(ALOAD, 0);
        baseCtor.visitTypeInsn(NEW, CONTAINER);
        baseCtor.visitInsn(DUP);
        baseCtor.visitVarInsn(ALOAD, 1);
        baseCtor.visitVarInsn(ALOAD, 2);
        baseCtor.visitMethodInsn(INVOKESPECIAL, CONTAINER, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", false);
        baseCtor.visitMethodInsn(INVOKESPECIAL, Vanilla.GUI_CONTAINER, "<init>", "(Lnet/minecraft/inventory/Container;)V", false);
        baseCtor.visitVarInsn(ALOAD, 0);
        baseCtor.visitIntInsn(SIPUSH, 176);
        baseCtor.visitFieldInsn(PUTFIELD, "test/GuiTurretBase2", Vanilla.F_XSIZE, "I");
        baseCtor.visitVarInsn(ALOAD, 0);
        baseCtor.visitIntInsn(SIPUSH, 222);
        baseCtor.visitFieldInsn(PUTFIELD, "test/GuiTurretBase2", Vanilla.F_YSIZE, "I");
        baseCtor.visitInsn(RETURN);
        baseCtor.visitMaxs(10, 10);
        baseCtor.visitEnd();

        MethodVisitor baseBg = baseCn.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        baseBg.visitCode();
        baseBg.visitMethodInsn(INVOKESTATIC, "net/minecraft/client/Minecraft", "func_71410_x",
                "()Lnet/minecraft/client/Minecraft;", false);
        baseBg.visitMethodInsn(INVOKEVIRTUAL, "net/minecraft/client/Minecraft", "func_110434_K",
                "()L" + Vanilla.TEXTURE_MANAGER + ";", false);
        baseBg.visitFieldInsn(GETSTATIC, "test/GuiTurretBase2", "texture", RL);
        baseBg.visitMethodInsn(INVOKEVIRTUAL, Vanilla.TEXTURE_MANAGER, Vanilla.M_BIND_TEXTURE_MANAGER,
                "(L" + Vanilla.RESOURCE_LOCATION + ";)V", false);
        baseBg.visitVarInsn(ALOAD, 0);
        baseBg.visitVarInsn(ALOAD, 0); baseBg.visitFieldInsn(GETFIELD, "test/GuiTurretBase2", Vanilla.F_GUILEFT, "I");
        baseBg.visitVarInsn(ALOAD, 0); baseBg.visitFieldInsn(GETFIELD, "test/GuiTurretBase2", Vanilla.F_GUITOP, "I");
        baseBg.visitInsn(ICONST_0);
        baseBg.visitInsn(ICONST_0);
        baseBg.visitVarInsn(ALOAD, 0); baseBg.visitFieldInsn(GETFIELD, "test/GuiTurretBase2", Vanilla.F_XSIZE, "I");
        baseBg.visitVarInsn(ALOAD, 0); baseBg.visitFieldInsn(GETFIELD, "test/GuiTurretBase2", Vanilla.F_YSIZE, "I");
        baseBg.visitMethodInsn(INVOKEVIRTUAL, "test/GuiTurretBase2", Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        baseBg.visitInsn(RETURN);
        baseBg.visitMaxs(12, 4);
        baseBg.visitEnd();
        baseCn.visitEnd();
        jar.classes.put("test/GuiTurretBase2", toNode(baseCn));

        ClassWriter leafCn = new ClassWriter(0);
        leafCn.visit(V1_8, ACC_PUBLIC, "test/GuiFakeTurretLeaf", null, "test/GuiTurretBase2", null);
        MethodVisitor leafCtor = leafCn.visitMethod(ACC_PUBLIC, "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", null, null);
        leafCtor.visitCode();
        leafCtor.visitVarInsn(ALOAD, 0);
        leafCtor.visitVarInsn(ALOAD, 1);
        leafCtor.visitVarInsn(ALOAD, 2);
        leafCtor.visitMethodInsn(INVOKESPECIAL, "test/GuiTurretBase2", "<init>",
                "(Lnet/minecraft/entity/player/InventoryPlayer;L" + TE + ";)V", false);
        leafCtor.visitInsn(RETURN);
        leafCtor.visitMaxs(4, 4);
        leafCtor.visitEnd();
        leafCn.visitEnd();
        jar.classes.put("test/GuiFakeTurretLeaf", toNode(leafCn));

        FieldConstResolver resolver = new FieldConstResolver(jar);
        ContainerPairer pairer = new ContainerPairer(jar);
        GuiClassAnalyzer analyzer = new GuiClassAnalyzer(jar, resolver, pairer);
        JsonObject row = analyzer.analyze(new GuiScanner.Candidate(jar.cls("test/GuiFakeTurretLeaf"), GuiScanner.Kind.GUI_CONTAINER));

        JsonObject size = row.getAsJsonObject("size");
        assertEquals(176, size.get("xSize").getAsInt());
        assertEquals(222, size.get("ySize").getAsInt());
        assertEquals("exact", size.get("confidence").getAsString());

        var textures = row.getAsJsonArray("backgroundTextures");
        assertEquals(1, textures.size());
        assertEquals("modid:textures/gui/turret_base.png", textures.get(0).getAsJsonObject().get("path").getAsString());
        assertFalse(row.has("note_noBackgroundMethod"));
    }
}
