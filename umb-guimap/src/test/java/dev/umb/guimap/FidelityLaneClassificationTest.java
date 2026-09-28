package dev.umb.guimap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * GUI-fidelity lane: classification of the dynamic-overlay idioms the five lane machines
 * actually use (all shapes javap-verified against HBM-NTM-1.0.27_X5771.jar before encoding
 * here): divide-by-{@code Math.max(field, const)} floors, whole-term {@code Math.ceil}, and
 * (later) value-selecting branches. Same hand-built-bytecode convention as
 * {@code StateLinearClassificationTest}.
 */
class FidelityLaneClassificationTest {

    private static final String GUI = "test/GuiFake";
    private static final String TE = "test/TileEntityFake";

    private interface BodyWriter { void write(MethodVisitor mv); }

    private static JsonArray drawRectArgs(BodyWriter body) {
        JarIndex jar = new JarIndex();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "machine", "L" + TE + ";", null, null).visitEnd();
        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        body.write(bg);
        bg.visitInsn(RETURN);
        bg.visitMaxs(16, 8);
        bg.visitEnd();
        cw.visitEnd();
        ClassNode guiNode = toNode(cw);
        jar.classes.put(GUI, guiNode);

        ClassWriter teCw = new ClassWriter(0);
        teCw.visit(V1_8, ACC_PUBLIC, TE, null, "java/lang/Object", null);
        teCw.visitField(ACC_PUBLIC, "heat", "I", null, null).visitEnd();
        teCw.visitField(ACC_PUBLIC, "heatMax", "I", null, null).visitEnd();
        teCw.visitField(ACC_PUBLIC, "progress", "D", null, null).visitEnd();
        teCw.visitField(ACC_PUBLIC | ACC_STATIC, "staticLimit", "I", null, null).visitEnd();
        teCw.visitEnd();
        jar.classes.put(TE, toNode(teCw));

        FieldConstResolver resolver = new FieldConstResolver(jar);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, resolver, null, null);
        MethodNode mn = findMethod(guiNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC);
        scanner.scan(guiNode, mn);
        assertEquals(1, scanner.drawRects.size(), "expected exactly one drawTexturedModalRect call");
        return scanner.drawRects.get(0).getAsJsonArray("args");
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
        throw new IllegalStateException("method not found: " + name + desc);
    }

    private static ClassNode toNode(ClassWriter cw) {
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    private static void drawWithWidthArg(MethodVisitor bg, Runnable pushWidth) {
        bg.visitVarInsn(ALOAD, 0);
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUILEFT, "I");
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUITOP, "I");
        bg.visitInsn(ICONST_0);
        bg.visitInsn(ICONST_0);
        pushWidth.run();
        bg.visitIntInsn(BIPUSH, 16);
        bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
    }

    private static void pushHeatTimes24(MethodVisitor bg) {
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
        bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
        bg.visitIntInsn(BIPUSH, 24);
        bg.visitInsn(IMUL);
    }

    private static void pushMaxHeatMaxOne(MethodVisitor bg) {
        bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
        bg.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
        bg.visitInsn(ICONST_1);
        bg.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
    }

    @Test
    void maxFloorDivisorBindsWithFloorRecorded() {
        // this.machine.heat * 24 / Math.max(this.machine.heatMax, 1) - the furnace
        // progress/burnTime arrow idiom (javap: GUIFurnaceIron.func_146976_a).
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            pushHeatTimes24(bg);
            pushMaxHeatMaxOne(bg);
            bg.visitInsn(IDIV);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("STATE_LINEAR", w.get("classification").getAsString());
        JsonObject binding = w.getAsJsonObject("stateBinding");
        assertEquals(24.0, binding.get("multiplier").getAsDouble());
        JsonObject divisor = binding.getAsJsonObject("divisor");
        assertEquals("field", divisor.get("kind").getAsString());
        assertEquals("heatMax", divisor.get("fieldName").getAsString());
        assertEquals(1.0, divisor.get("divisorFloor").getAsDouble());
        assertEquals("heat", binding.getAsJsonObject("source").get("fieldName").getAsString());
    }

    @Test
    void maxFloorDivisorOrderIndependent() {
        // Math.max(1, this.machine.heatMax) - same meaning, swapped operands.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            pushHeatTimes24(bg);
            bg.visitInsn(ICONST_1);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
            bg.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
            bg.visitInsn(IDIV);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("STATE_LINEAR", w.get("classification").getAsString());
        JsonObject divisor = w.getAsJsonObject("stateBinding").getAsJsonObject("divisor");
        assertEquals("field", divisor.get("kind").getAsString());
        assertEquals("heatMax", divisor.get("fieldName").getAsString());
        assertEquals(1.0, divisor.get("divisorFloor").getAsDouble());
    }

    @Test
    void minDivisorStaysUnresolved() {
        // Math.min as a divisor is a cap, not a floor - no honest host meaning, stays out.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            pushHeatTimes24(bg);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
            bg.visitIntInsn(BIPUSH, 100);
            bg.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
            bg.visitInsn(IDIV);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("dynamic", w.get("kind").getAsString());
        assertEquals("UNRESOLVED", w.get("classification").getAsString());
    }

    @Test
    void maxOfTwoFieldsStaysUnresolved() {
        // Math.max(fieldA, fieldB) - no constant floor to record, stays out.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            pushHeatTimes24(bg);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "max", "(II)I", false);
            bg.visitInsn(IDIV);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("UNRESOLVED", w.get("classification").getAsString());
    }

    @Test
    void ceilAppliesToWholeTerm() {
        // (int)Math.ceil(70.0 * this.machine.progress) - the assembler arrow width idiom
        // (javap: GUIMachineAssemblyMachine.func_146976_a, progress is a double field).
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            bg.visitLdcInsn(70.0d);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "progress", "D");
            bg.visitInsn(DMUL);
            bg.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "ceil", "(D)D", false);
            bg.visitInsn(D2I);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("STATE_LINEAR", w.get("classification").getAsString());
        JsonObject binding = w.getAsJsonObject("stateBinding");
        assertEquals(70.0, binding.get("multiplier").getAsDouble());
        assertEquals("progress", binding.getAsJsonObject("source").get("fieldName").getAsString());
        assertEquals(true, binding.get("ceilResult").getAsBoolean());
    }

    @Test
    void staticFieldDivisorBindsWithTileRootedRequirement() {
        // this.machine.heat * 24 / TileEntityFake.staticLimit - the turbine
        // getPowerScaled-style static divisor (javap: power*scale/maxPower where maxPower
        // is a static long on the tile class). The requirement roots the static at its
        // declaring class; a host snapshots it off the live tile (reflection finds
        // statics), failing closed when the static lives outside the tile hierarchy.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            pushHeatTimes24(bg);
            bg.visitFieldInsn(GETSTATIC, TE, "staticLimit", "I");
            bg.visitInsn(IDIV);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("STATE_LINEAR", w.get("classification").getAsString());
        JsonObject binding = w.getAsJsonObject("stateBinding");
        assertEquals("heat", binding.getAsJsonObject("source").get("fieldName").getAsString());
        JsonObject divisor = binding.getAsJsonObject("divisor");
        assertEquals("field", divisor.get("kind").getAsString());
        assertEquals("staticLimit", divisor.get("fieldName").getAsString());
        JsonObject req = divisor.getAsJsonObject("fieldRequirement");
        assertEquals("test.TileEntityFake", req.get("tileEntityClass").getAsString());
        assertEquals("staticLimit",
                req.getAsJsonArray("hops").get(0).getAsJsonObject().get("fieldName").getAsString());
    }

    @Test
    void plainFieldDivisorHasNoFloorOrCeilKeys() {
        // Regression: pre-existing rows must not sprout the new optional keys.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            pushHeatTimes24(bg);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
            bg.visitInsn(IDIV);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("STATE_LINEAR", w.get("classification").getAsString());
        JsonObject binding = w.getAsJsonObject("stateBinding");
        assertFalse(binding.has("ceilResult"));
        assertFalse(binding.getAsJsonObject("divisor").has("divisorFloor"));
    }

    /** Full row list (no single-rect assertion) for twin-branch tests. */
    private static java.util.List<JsonObject> drawRectRows(BodyWriter body) {
        JarIndex jar = new JarIndex();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "machine", "L" + TE + ";", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE, "flag", "Z", null, null).visitEnd();
        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        body.write(bg);
        bg.visitInsn(RETURN);
        bg.visitMaxs(16, 8);
        bg.visitEnd();
        cw.visitEnd();
        ClassNode guiNode = toNode(cw);
        jar.classes.put(GUI, guiNode);
        ClassWriter teCw = new ClassWriter(0);
        teCw.visit(V1_8, ACC_PUBLIC, TE, null, "java/lang/Object", null);
        teCw.visitField(ACC_PUBLIC, "heat", "I", null, null).visitEnd();
        teCw.visitField(ACC_PUBLIC, "heatMax", "I", null, null).visitEnd();
        teCw.visitField(ACC_PUBLIC, "progress", "D", null, null).visitEnd();
        teCw.visitField(ACC_PUBLIC, "mode", "Z", null, null).visitEnd();
        teCw.visitEnd();
        jar.classes.put(TE, toNode(teCw));
        FieldConstResolver resolver = new FieldConstResolver(jar);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, resolver, null, null);
        scanner.scan(guiNode, findMethod(guiNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));
        return scanner.drawRects;
    }

    @Test
    void booleanSelectedConstEmitsGuardedTwins() {        // v = 61 + (this.machine.mode ? 16 : 0): the assembler restrictedMode sprite-row
        // selector (javap: GUIMachineAssemblyMachine.func_146976_a). Two rects, identical
        // except v (61 vs 77) and complementary tileField guards on machine.mode.
        java.util.List<JsonObject> rows = drawRectRows(bg -> {
            bg.visitVarInsn(ALOAD, 0);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUILEFT, "I");
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUITOP, "I");
            bg.visitInsn(ICONST_0);
            bg.visitIntInsn(BIPUSH, 61);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "mode", "Z");
            org.objectweb.asm.Label lElse = new org.objectweb.asm.Label();
            org.objectweb.asm.Label lMerge = new org.objectweb.asm.Label();
            bg.visitJumpInsn(IFEQ, lElse);
            bg.visitIntInsn(BIPUSH, 16);
            bg.visitJumpInsn(GOTO, lMerge);
            bg.visitLabel(lElse);
            bg.visitInsn(ICONST_0);
            bg.visitLabel(lMerge);
            bg.visitInsn(IADD);
            bg.visitIntInsn(BIPUSH, 16);
            bg.visitIntInsn(BIPUSH, 16);
            bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        });
        assertEquals(2, rows.size(), "expected the twin pair, got: " + rows);
        JsonObject v0 = rows.get(0).getAsJsonArray("args").get(3).getAsJsonObject();
        JsonObject v1 = rows.get(1).getAsJsonArray("args").get(3).getAsJsonObject();
        int vv0 = v0.get("value").getAsInt();
        int vv1 = v1.get("value").getAsInt();
        assertTrue((vv0 == 77 && vv1 == 61) || (vv0 == 61 && vv1 == 77),
                "expected v=77 and v=61 twins, got " + vv0 + " and " + vv1);
        for (JsonObject row : rows) {
            JsonObject cond = row.getAsJsonObject("conditional");
            assertEquals(true, cond.get("guarded").getAsBoolean());
            JsonObject skip = cond.getAsJsonObject("skipCondition");
            assertNotNull(skip, "twin rects must carry a path guard");
        }
    }

    @Test
    void reusedLocalResolvesToItsUniqueReachingWrite() {
        // Slot 4 written twice in disjoint sequential regions (battery bar height, then a
        // tile short): the load after both regions provably sees the second write. Classic
        // reaching-definitions; javap: GUIMachineBattery redLow icon value vs bar height.
        java.util.List<JsonObject> rows = drawRectRows(bg -> {
            bg.visitVarInsn(ALOAD, 0);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUILEFT, "I");
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUITOP, "I");
            bg.visitInsn(ICONST_0);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitVarInsn(ISTORE, 4);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
            bg.visitVarInsn(ISTORE, 4);
            bg.visitVarInsn(ILOAD, 4);
            bg.visitIntInsn(BIPUSH, 18);
            bg.visitInsn(IMUL);
            bg.visitIntInsn(BIPUSH, 18);
            bg.visitIntInsn(BIPUSH, 18);
            bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        });
        assertEquals(1, rows.size());
        // v is args[3]: heatMax wins (second sequential write).
        JsonObject v = rows.get(0).getAsJsonArray("args").get(3).getAsJsonObject();
        assertEquals("STATE_LINEAR", v.get("classification").getAsString());
        assertEquals("heatMax",
                v.getAsJsonObject("stateBinding").getAsJsonObject("source").get("fieldName").getAsString());
    }

    @Test
    void diamondWritesStayPoisoned() {
        // if/else diamond writing different fields to the same slot: no unique reaching
        // definition exists, so the load stays honestly unresolved (never the linearly-last
        // write, which would be wrong on the other path).
        java.util.List<JsonObject> rows = drawRectRows(bg -> {
            bg.visitVarInsn(ALOAD, 0);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUILEFT, "I");
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUITOP, "I");
            bg.visitInsn(ICONST_0);
            bg.visitInsn(ICONST_0);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            org.objectweb.asm.Label lElse = new org.objectweb.asm.Label();
            org.objectweb.asm.Label lMerge = new org.objectweb.asm.Label();
            bg.visitJumpInsn(IFEQ, lElse);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitVarInsn(ISTORE, 4);
            bg.visitJumpInsn(GOTO, lMerge);
            bg.visitLabel(lElse);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
            bg.visitVarInsn(ISTORE, 4);
            bg.visitLabel(lMerge);
            bg.visitVarInsn(ILOAD, 4);
            bg.visitIntInsn(BIPUSH, 16);
            bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        });
        assertEquals(1, rows.size());
        JsonObject w = rows.get(0).getAsJsonArray("args").get(4).getAsJsonObject();
        assertEquals("UNRESOLVED", w.get("classification").getAsString());
    }
}
