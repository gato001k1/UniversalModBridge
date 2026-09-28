package dev.umb.guimap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * SYNC-BINDING lane: {@link ContainerSyncScanner} exercised against small hand-built Container
 * bytecode shaped exactly like the real, javap-verified HBM patterns this lane extracted from
 * {@code ContainerMachineRTG}/{@code ContainerMachineShredder} (see
 * {@code research/out/legacy/guimap-notes/SYNC-BINDING.md}):
 *
 * <pre>
 *   // server route (a func_75142_b/detectAndSendChanges-style override, or the equivalent
 *   // func_75132_a initial-sync call — this scanner matches the ICrafting call itself, not the
 *   // enclosing method's name):
 *   crafter.func_71112_a(this, ID, this.diFurnace.FIELD);
 *
 *   // client route (func_75137_b/updateProgressBar):
 *   protected void func_75137_b(int id, int value) {
 *       if (id != ID) return;
 *       this.diFurnace.FIELD = value;
 *   }
 * </pre>
 *
 * Same "hand-built bytecode via ASM ClassWriter" convention as {@code StateLinearClassificationTest}.
 */
class ContainerSyncScannerTest {

    private static final String CONTAINER = "test/ContainerSyncFake";
    private static final String TE = "test/TileEntitySyncFake";

    @Test
    void serverAndClientRouteAgreeOnTheSameFieldForTheSameId() {
        ClassNode cn = buildContainer(/* clientField */ "heat", /* clientOwner */ TE);
        JsonArray bindings = ContainerSyncScanner.scanContainer(cn);
        assertEquals(1, bindings.size(), bindings.toString());
        JsonObject b = bindings.get(0).getAsJsonObject();
        assertEquals(0, b.get("syncIndex").getAsInt());
        assertEquals("heat", b.getAsJsonObject("field").get("fieldName").getAsString());
        assertEquals(JarIndex.dotted(TE), b.getAsJsonObject("field").get("ownerClass").getAsString());
        assertTrue(b.get("serverRoute").getAsBoolean());
        assertTrue(b.get("clientRoute").getAsBoolean());
        assertTrue(b.get("agree").getAsBoolean(), b.toString());
        assertFalse(b.has("disagreement"));
    }

    @Test
    void disagreementBetweenRoutesIsReportedNeverSilentlyResolved() {
        // client route assigns a DIFFERENT field than the server route sent for the same id.
        ClassNode cn = buildContainer(/* clientField */ "progress", /* clientOwner */ TE);
        JsonArray bindings = ContainerSyncScanner.scanContainer(cn);
        assertEquals(1, bindings.size());
        JsonObject b = bindings.get(0).getAsJsonObject();
        assertTrue(b.get("serverRoute").getAsBoolean());
        assertTrue(b.get("clientRoute").getAsBoolean());
        assertFalse(b.get("agree").getAsBoolean());
        assertTrue(b.has("disagreement"), b.toString());
        JsonObject d = b.getAsJsonObject("disagreement");
        assertTrue(d.get("serverField").getAsString().endsWith("#heat"));
        assertTrue(d.get("clientField").getAsString().endsWith("#progress"));
    }

    @Test
    void serverRouteAloneIsReportedWithAgreeAsJsonNull() {
        ClassNode cn = buildServerOnlyContainer();
        JsonArray bindings = ContainerSyncScanner.scanContainer(cn);
        assertEquals(1, bindings.size());
        JsonObject b = bindings.get(0).getAsJsonObject();
        assertTrue(b.get("serverRoute").getAsBoolean());
        assertFalse(b.get("clientRoute").getAsBoolean());
        assertTrue(b.get("agree").isJsonNull(), "single-route evidence must not claim agreement: " + b);
    }

    @Test
    void multiIdContainerBindsEachIdToItsOwnFieldBothRoutesAgreeing() {
        ClassNode cn = buildTwoIdContainer();
        JsonArray bindings = ContainerSyncScanner.scanContainer(cn);
        assertEquals(2, bindings.size());
        JsonObject b0 = bindings.get(0).getAsJsonObject(), b1 = bindings.get(1).getAsJsonObject();
        assertEquals(0, b0.get("syncIndex").getAsInt());
        assertEquals("targetX", b0.getAsJsonObject("field").get("fieldName").getAsString());
        assertTrue(b0.get("agree").getAsBoolean());
        assertEquals(1, b1.get("syncIndex").getAsInt());
        assertEquals("targetY", b1.getAsJsonObject("field").get("fieldName").getAsString());
        assertTrue(b1.get("agree").getAsBoolean());
    }

    // ---------------------------------------------------------------- fixture builders

    /** id 0 -> TE.heat on the server route; id 0 -> (clientOwner).clientField on the client route. */
    private static ClassNode buildContainer(String clientField, String clientOwner) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, CONTAINER, null, Vanilla.CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "diFurnace", "L" + TE + ";", null, null).visitEnd();

        // void serverSync(ICrafting c) { c.func_71112_a(this, 0, this.diFurnace.heat); }
        MethodVisitor sv = cw.visitMethod(ACC_PUBLIC, "serverSync", "(L" + Vanilla.ICRAFTING + ";)V", null, null);
        sv.visitCode();
        sv.visitVarInsn(ALOAD, 1);
        sv.visitVarInsn(ALOAD, 0);
        sv.visitInsn(ICONST_0);
        sv.visitVarInsn(ALOAD, 0);
        sv.visitFieldInsn(GETFIELD, CONTAINER, "diFurnace", "L" + TE + ";");
        sv.visitFieldInsn(GETFIELD, TE, "heat", "I");
        sv.visitMethodInsn(INVOKEINTERFACE, Vanilla.ICRAFTING, Vanilla.M_SEND_PROGRESS_BAR_UPDATE,
                Vanilla.M_SEND_PROGRESS_BAR_UPDATE_DESC, true);
        sv.visitInsn(RETURN);
        sv.visitMaxs(6, 2);
        sv.visitEnd();

        // protected void func_75137_b(int id, int value) { if (id != 0) return; this.<owner>.<field> = value; }
        MethodVisitor cl = cw.visitMethod(ACC_PROTECTED, Vanilla.M_UPDATE_PROGRESS_BAR,
                Vanilla.M_UPDATE_PROGRESS_BAR_DESC, null, null);
        cl.visitCode();
        org.objectweb.asm.Label skip = new org.objectweb.asm.Label();
        cl.visitVarInsn(ILOAD, 1);
        cl.visitJumpInsn(IFNE, skip);
        cl.visitVarInsn(ALOAD, 0);
        cl.visitFieldInsn(GETFIELD, CONTAINER, "diFurnace", "L" + TE + ";");
        cl.visitVarInsn(ILOAD, 2);
        cl.visitFieldInsn(PUTFIELD, clientOwner, clientField, "I");
        cl.visitLabel(skip);
        cl.visitInsn(RETURN);
        cl.visitMaxs(3, 3);
        cl.visitEnd();
        cw.visitEnd();
        return toNode(cw);
    }

    private static ClassNode buildServerOnlyContainer() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, CONTAINER, null, Vanilla.CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "diFurnace", "L" + TE + ";", null, null).visitEnd();
        MethodVisitor sv = cw.visitMethod(ACC_PUBLIC, "serverSync", "(L" + Vanilla.ICRAFTING + ";)V", null, null);
        sv.visitCode();
        sv.visitVarInsn(ALOAD, 1);
        sv.visitVarInsn(ALOAD, 0);
        sv.visitInsn(ICONST_0);
        sv.visitVarInsn(ALOAD, 0);
        sv.visitFieldInsn(GETFIELD, CONTAINER, "diFurnace", "L" + TE + ";");
        sv.visitFieldInsn(GETFIELD, TE, "heat", "I");
        sv.visitMethodInsn(INVOKEINTERFACE, Vanilla.ICRAFTING, Vanilla.M_SEND_PROGRESS_BAR_UPDATE,
                Vanilla.M_SEND_PROGRESS_BAR_UPDATE_DESC, true);
        sv.visitInsn(RETURN);
        sv.visitMaxs(6, 2);
        sv.visitEnd();
        cw.visitEnd();
        return toNode(cw);
    }

    /** Teleporter-style: id 0 -> targetX, id 1 -> targetY, both routes agreeing. */
    private static ClassNode buildTwoIdContainer() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, CONTAINER, null, Vanilla.CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "diFurnace", "L" + TE + ";", null, null).visitEnd();

        MethodVisitor sv = cw.visitMethod(ACC_PUBLIC, "serverSync", "(L" + Vanilla.ICRAFTING + ";)V", null, null);
        sv.visitCode();
        for (int id = 0; id <= 1; id++) {
            String field = id == 0 ? "targetX" : "targetY";
            sv.visitVarInsn(ALOAD, 1);
            sv.visitVarInsn(ALOAD, 0);
            sv.visitInsn(id == 0 ? ICONST_0 : ICONST_1);
            sv.visitVarInsn(ALOAD, 0);
            sv.visitFieldInsn(GETFIELD, CONTAINER, "diFurnace", "L" + TE + ";");
            sv.visitFieldInsn(GETFIELD, TE, field, "I");
            sv.visitMethodInsn(INVOKEINTERFACE, Vanilla.ICRAFTING, Vanilla.M_SEND_PROGRESS_BAR_UPDATE,
                    Vanilla.M_SEND_PROGRESS_BAR_UPDATE_DESC, true);
        }
        sv.visitInsn(RETURN);
        sv.visitMaxs(6, 2);
        sv.visitEnd();

        MethodVisitor cl = cw.visitMethod(ACC_PROTECTED, Vanilla.M_UPDATE_PROGRESS_BAR,
                Vanilla.M_UPDATE_PROGRESS_BAR_DESC, null, null);
        cl.visitCode();
        org.objectweb.asm.Label afterX = new org.objectweb.asm.Label();
        org.objectweb.asm.Label afterY = new org.objectweb.asm.Label();
        cl.visitVarInsn(ILOAD, 1);
        cl.visitJumpInsn(IFNE, afterX);
        cl.visitVarInsn(ALOAD, 0);
        cl.visitFieldInsn(GETFIELD, CONTAINER, "diFurnace", "L" + TE + ";");
        cl.visitVarInsn(ILOAD, 2);
        cl.visitFieldInsn(PUTFIELD, TE, "targetX", "I");
        cl.visitLabel(afterX);
        cl.visitVarInsn(ILOAD, 1);
        cl.visitInsn(ICONST_1);
        cl.visitJumpInsn(IF_ICMPNE, afterY);
        cl.visitVarInsn(ALOAD, 0);
        cl.visitFieldInsn(GETFIELD, CONTAINER, "diFurnace", "L" + TE + ";");
        cl.visitVarInsn(ILOAD, 2);
        cl.visitFieldInsn(PUTFIELD, TE, "targetY", "I");
        cl.visitLabel(afterY);
        cl.visitInsn(RETURN);
        cl.visitMaxs(3, 3);
        cl.visitEnd();
        cw.visitEnd();
        return toNode(cw);
    }

    private static ClassNode toNode(ClassWriter cw) {
        ClassNode cn = new ClassNode();
        new org.objectweb.asm.ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }
}
