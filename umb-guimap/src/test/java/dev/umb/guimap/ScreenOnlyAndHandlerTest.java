package dev.umb.guimap;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Covers the two paths a real HBM mod jar actually exercises that
 * {@link GuiClassAnalyzerTest} does not:
 * <ul>
 *   <li>a GUI that extends {@code GuiScreen} directly (not {@code GuiContainer}) — the shape of
 *       {@code com.hbm.inventory.gui.GUIRBMKConsole}, the RBMK reactor console — which has no
 *       vanilla xSize/ySize field and no vanilla background/foreground method split, and
 *       legitimately has no paired Container.</li>
 *   <li>the {@code IGuiHandler.getClientGuiElement}/{@code getServerGuiElement} cross-check
 *       pairing source, independent of any GUI constructor shape.</li>
 * </ul>
 */
class ScreenOnlyAndHandlerTest {

    private static final String SCREEN = "test/GuiFakeConsole";

    private static ClassNode toNode(ClassWriter cw) {
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    /** A GuiScreen-only class with its OWN (non-vanilla-named) xSize/ySize fields, set in the
     *  constructor, and a single drawScreen override that draws a u=0,v=0 background rect. */
    private static ClassNode fakeConsoleClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, SCREEN, null, Vanilla.GUI_SCREEN, null);
        cw.visitField(ACC_PROTECTED, "xSize", "I", null, null).visitEnd();
        cw.visitField(ACC_PROTECTED, "ySize", "I", null, null).visitEnd();

        MethodVisitor ctor = cw.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        ctor.visitCode();
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitMethodInsn(INVOKESPECIAL, Vanilla.GUI_SCREEN, "<init>", "()V", false);
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitIntInsn(SIPUSH, 244);
        ctor.visitFieldInsn(PUTFIELD, SCREEN, "xSize", "I");
        ctor.visitVarInsn(ALOAD, 0);
        ctor.visitIntInsn(SIPUSH, 172);
        ctor.visitFieldInsn(PUTFIELD, SCREEN, "ySize", "I");
        ctor.visitInsn(RETURN);
        ctor.visitMaxs(4, 4);
        ctor.visitEnd();

        MethodVisitor draw = cw.visitMethod(ACC_PUBLIC, Vanilla.M_DRAW_SCREEN, "(IIF)V", null, null);
        draw.visitCode();
        draw.visitVarInsn(ALOAD, 0);
        draw.visitInsn(ICONST_0); // x
        draw.visitInsn(ICONST_0); // y
        draw.visitInsn(ICONST_0); // u
        draw.visitInsn(ICONST_0); // v
        draw.visitVarInsn(ALOAD, 0); draw.visitFieldInsn(GETFIELD, SCREEN, "xSize", "I");
        draw.visitVarInsn(ALOAD, 0); draw.visitFieldInsn(GETFIELD, SCREEN, "ySize", "I");
        draw.visitMethodInsn(INVOKEVIRTUAL, SCREEN, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        draw.visitInsn(RETURN);
        draw.visitMaxs(8, 4);
        draw.visitEnd();

        cw.visitEnd();
        return toNode(cw);
    }

    @Test
    void guiScreenOnlyGetsInferredSizeFromTheUZeroVZeroConventionAndNoContainer() {
        JarIndex jar = new JarIndex();
        jar.classes.put(SCREEN, fakeConsoleClass());
        FieldConstResolver resolver = new FieldConstResolver(jar);
        ContainerPairer pairer = new ContainerPairer(jar);
        GuiClassAnalyzer analyzer = new GuiClassAnalyzer(jar, resolver, pairer);

        JsonObject row = analyzer.analyze(new GuiScanner.Candidate(jar.cls(SCREEN), GuiScanner.Kind.GUI_SCREEN_ONLY));
        JsonObject size = row.getAsJsonObject("size");
        assertEquals(244, size.get("xSize").getAsInt());
        assertEquals(172, size.get("ySize").getAsInt());
        assertEquals("inferred", size.get("confidence").getAsString());

        JsonObject container = row.getAsJsonObject("container");
        assertEquals("none", container.get("confidence").getAsString());
        assertTrue(container.get("className").isJsonNull());
    }

    @Test
    void guiScannerFindsBothKindsAndSkipsAbstractClasses() {
        JarIndex jar = new JarIndex();
        jar.classes.put(SCREEN, fakeConsoleClass());
        ClassWriter abs = new ClassWriter(0);
        abs.visit(V1_8, ACC_PUBLIC | ACC_ABSTRACT, "test/AbstractGui", null, Vanilla.GUI_CONTAINER, null);
        abs.visitEnd();
        jar.classes.put("test/AbstractGui", toNode(abs));

        List<GuiScanner.Candidate> found = new GuiScanner(jar).scan();
        assertEquals(1, found.size());
        assertEquals(SCREEN, found.get(0).cn.name);
        assertEquals(GuiScanner.Kind.GUI_SCREEN_ONLY, found.get(0).kind);
    }

    /** Builds a fake IGuiHandler with a two-way tableswitch on guiId, matching the vanilla shape
     *  of {@code getClientGuiElement}/{@code getServerGuiElement}. */
    @Test
    void guiHandlerScannerRecoversGuiIdToClassPairingsFromTheSwitchShape() {
        JarIndex jar = new JarIndex();
        ClassWriter guiCn = new ClassWriter(0);
        guiCn.visit(V1_8, ACC_PUBLIC, "test/GuiA", null, Vanilla.GUI_SCREEN, null);
        MethodVisitor gc = guiCn.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        gc.visitCode(); gc.visitVarInsn(ALOAD, 0);
        gc.visitMethodInsn(INVOKESPECIAL, Vanilla.GUI_SCREEN, "<init>", "()V", false);
        gc.visitInsn(RETURN); gc.visitMaxs(1, 1); gc.visitEnd();
        guiCn.visitEnd();
        jar.classes.put("test/GuiA", toNode(guiCn));

        ClassWriter containerCn = new ClassWriter(0);
        containerCn.visit(V1_8, ACC_PUBLIC, "test/ContainerA", null, Vanilla.CONTAINER, null);
        MethodVisitor cc = containerCn.visitMethod(ACC_PUBLIC, "<init>", "()V", null, null);
        cc.visitCode(); cc.visitVarInsn(ALOAD, 0);
        cc.visitMethodInsn(INVOKESPECIAL, Vanilla.CONTAINER, "<init>", "()V", false);
        cc.visitInsn(RETURN); cc.visitMaxs(1, 1); cc.visitEnd();
        containerCn.visitEnd();
        jar.classes.put("test/ContainerA", toNode(containerCn));

        ClassWriter handler = new ClassWriter(0);
        handler.visit(V1_8, ACC_PUBLIC, "test/HandlerA", null, "java/lang/Object",
                new String[]{Vanilla.IGUI_HANDLER});
        String desc = "(ILnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;III)Ljava/lang/Object;";

        MethodVisitor server = handler.visitMethod(ACC_PUBLIC, "getServerGuiElement", desc, null, null);
        server.visitCode();
        org.objectweb.asm.Label case0 = new org.objectweb.asm.Label();
        org.objectweb.asm.Label dflt = new org.objectweb.asm.Label();
        server.visitVarInsn(ILOAD, 1);
        server.visitTableSwitchInsn(0, 0, dflt, case0);
        server.visitLabel(case0);
        server.visitTypeInsn(NEW, "test/ContainerA");
        server.visitInsn(DUP);
        server.visitMethodInsn(INVOKESPECIAL, "test/ContainerA", "<init>", "()V", false);
        server.visitInsn(ARETURN);
        server.visitLabel(dflt);
        server.visitInsn(ACONST_NULL);
        server.visitInsn(ARETURN);
        server.visitMaxs(4, 8);
        server.visitEnd();

        MethodVisitor client = handler.visitMethod(ACC_PUBLIC, "getClientGuiElement", desc, null, null);
        client.visitCode();
        org.objectweb.asm.Label ccase0 = new org.objectweb.asm.Label();
        org.objectweb.asm.Label cdflt = new org.objectweb.asm.Label();
        client.visitVarInsn(ILOAD, 1);
        client.visitTableSwitchInsn(0, 0, cdflt, ccase0);
        client.visitLabel(ccase0);
        client.visitTypeInsn(NEW, "test/GuiA");
        client.visitInsn(DUP);
        client.visitMethodInsn(INVOKESPECIAL, "test/GuiA", "<init>", "()V", false);
        client.visitInsn(ARETURN);
        client.visitLabel(cdflt);
        client.visitInsn(ACONST_NULL);
        client.visitInsn(ARETURN);
        client.visitMaxs(4, 8);
        client.visitEnd();

        handler.visitEnd();
        jar.classes.put("test/HandlerA", toNode(handler));

        List<GuiHandlerScanner.Pairing> pairings = new GuiHandlerScanner(jar).scan();
        assertEquals(1, pairings.size());
        assertEquals(0, pairings.get(0).guiId);
        assertEquals("test.GuiA", pairings.get(0).guiClass);
        assertEquals("test.ContainerA", pairings.get(0).containerClass);
        assertEquals("test.HandlerA", pairings.get(0).handlerClass);
    }
}
