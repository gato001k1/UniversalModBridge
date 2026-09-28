package dev.umb.guimap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * GUARD-EXPRESSIONS lane: {@code conditional.skipCondition}/{@code conditional.guardNeeds} — the
 * general AND/OR/COMPARE expression tree over a guard's real operands (superseding
 * {@code fieldCondition}'s single-frame-only scope, which stays untouched for backward
 * compatibility — see {@code StateLinearClassificationTest}'s existing guard tests). Hand-built ASM
 * bodies under a non-mod package name, same convention as {@code StateLinearClassificationTest}.
 * See {@code research/out/legacy/guimap-notes/GUARD-EXPRESSIONS.md} for the real-corpus evidence
 * and exact numbers this verifies the *mechanism* behind.
 */
class GuardExpressionTest {

    private static final String GUI = "test/GuiGuardFake";
    private static final String TE = "test/TileEntityGuardFake";

    private static ClassNode toNode(ClassWriter cw) {
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
        throw new IllegalStateException("method not found: " + name + desc);
    }

    private static void drawCall(MethodVisitor bg) {
        bg.visitVarInsn(ALOAD, 0);
        bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0);
        bg.visitIntInsn(BIPUSH, 16); bg.visitIntInsn(BIPUSH, 16);
        bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
    }

    /** Builds a GUI class (with a {@code machine} field of type {@link #TE}, which itself has an
     *  int {@code heat} field) whose {@code drawGuiContainerBackgroundLayer} body is {@code body},
     *  scans it, and returns the resulting single draw call's {@code conditional} object. */
    private static JsonObject guardedConditional(java.util.function.Consumer<MethodVisitor> body) {
        JarIndex jar = new JarIndex();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "machine", "L" + TE + ";", null, null).visitEnd();
        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        body.accept(bg);
        bg.visitInsn(RETURN);
        bg.visitMaxs(16, 8);
        bg.visitEnd();
        cw.visitEnd();
        ClassNode guiNode = toNode(cw);
        jar.classes.put(GUI, guiNode);

        ClassWriter teCw = new ClassWriter(0);
        teCw.visit(V1_8, ACC_PUBLIC, TE, null, "java/lang/Object", null);
        teCw.visitField(ACC_PUBLIC, "heat", "I", null, null).visitEnd();
        teCw.visitEnd();
        jar.classes.put(TE, toNode(teCw));

        DrawLayerScanner scanner = new DrawLayerScanner(jar, new FieldConstResolver(jar), GUI, null);
        scanner.scan(guiNode, findMethod(guiNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));
        assertEquals(1, scanner.drawRects.size());
        JsonObject cond = scanner.drawRects.get(0).getAsJsonObject().getAsJsonObject("conditional");
        assertTrue(cond.get("guarded").getAsBoolean(), "expected a guarded draw call: " + cond);
        return cond;
    }

    @Test
    void andedTwoComparisonGuardIsAnOrOfTwoRecoveredCompareLeavesClassifiedTileFieldsOnly() {
        // if (this.machine.heat <= 0) return; if (this.machine.heat >= 50) return; draw();
        // Real corpus shape (SYNC-BINDING/TILE-FIELD-REQUIREMENTS' own "ANDed multi-condition guard"
        // example, e.g. a mouse-hover range test's non-mouse analogue: two tile-field bounds). The
        // pre-existing fieldConditionJson gives up entirely here (guardStack.size()==2), but BOTH
        // real comparisons - one single-operand, one genuine two-operand IF_ICMPGE - must now be
        // recorded with their real operands.
        JsonObject cond = guardedConditional(bg -> {
            Label skip = new Label();
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitJumpInsn(IFLE, skip); // frame 1: single-operand, heat vs implicit 0

            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitIntInsn(BIPUSH, 50);
            bg.visitJumpInsn(IF_ICMPGE, skip); // frame 2: genuine two-operand, heat vs 50

            drawCall(bg);
            bg.visitLabel(skip);
        });

        assertFalse(cond.has("fieldCondition"),
                "an ANDed two-frame guard must still never emit the old single-frame fieldCondition: " + cond);

        JsonObject skipCondition = cond.getAsJsonObject("skipCondition");
        assertEquals("OR", skipCondition.get("op").getAsString(),
                "two simultaneously-active guard frames combine as OR under the skip-polarity convention: " + skipCondition);
        JsonArray operands = skipCondition.getAsJsonArray("operands");
        assertEquals(2, operands.size());

        boolean sawLe0 = false, sawGe50 = false;
        for (var e : operands) {
            JsonObject leaf = e.getAsJsonObject();
            assertEquals("COMPARE", leaf.get("op").getAsString());
            JsonObject left = leaf.getAsJsonObject("left");
            assertEquals("tileField", left.get("kind").getAsString(), "expected the recovered heat field: " + leaf);
            assertEquals("heat", left.getAsJsonObject("source").get("fieldName").getAsString());
            JsonObject right = leaf.getAsJsonObject("right");
            assertEquals("const", right.get("kind").getAsString());
            String op = leaf.get("compareOp").getAsString();
            if ("LE".equals(op) && right.get("value").getAsInt() == 0) sawLe0 = true;
            if ("GE".equals(op) && right.get("value").getAsInt() == 50) sawGe50 = true;
        }
        assertTrue(sawLe0, "expected the recovered 'heat LE 0' leaf: " + skipCondition);
        assertTrue(sawGe50, "expected the recovered 'heat GE 50' leaf: " + skipCondition);

        JsonObject needs = cond.getAsJsonObject("guardNeeds");
        assertEquals("tileFieldsOnly", needs.get("level").getAsString(), "both leaves are tile-field-vs-const: " + needs);
        assertFalse(needs.get("needsMethodCall").getAsBoolean());
    }

    @Test
    void twoOperandTileFieldVsNonZeroConstantCompareResolvesEvenThoughTheOldFieldConditionCannotSeeIt() {
        // if (this.machine.heat < 36) return; draw() — a genuine two-operand IF_ICMPxx against a
        // NON-zero constant (the real corpus shape reported as
        // "IF_ICMPLT getfield ...TileEntityTurretBaseNT.stattrak , 36"). fieldConditionJson only
        // ever handles a SINGLE-operand test against an implicit 0/null, so this must have NO
        // fieldCondition at all under the old mechanism, yet fully resolve under the new one.
        JsonObject cond = guardedConditional(bg -> {
            Label skip = new Label();
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitIntInsn(BIPUSH, 36);
            bg.visitJumpInsn(IF_ICMPLT, skip);
            drawCall(bg);
            bg.visitLabel(skip);
        });

        assertFalse(cond.has("fieldCondition"),
                "a two-operand IF_ICMPxx must never get the old single-operand fieldCondition: " + cond);

        JsonObject skipCondition = cond.getAsJsonObject("skipCondition");
        assertEquals("COMPARE", skipCondition.get("op").getAsString(), "a single frame is one leaf, no OR wrapper: " + skipCondition);
        assertEquals("LT", skipCondition.get("compareOp").getAsString());
        JsonObject left = skipCondition.getAsJsonObject("left");
        assertEquals("tileField", left.get("kind").getAsString());
        assertEquals("heat", left.getAsJsonObject("source").get("fieldName").getAsString());
        assertEquals("test.TileEntityGuardFake", left.getAsJsonObject("fieldRequirement").get("tileEntityClass").getAsString());
        JsonObject right = skipCondition.getAsJsonObject("right");
        assertEquals("const", right.get("kind").getAsString());
        assertEquals(36, right.get("value").getAsInt());

        JsonObject needs = cond.getAsJsonObject("guardNeeds");
        assertEquals("tileFieldsOnly", needs.get("level").getAsString());
        assertFalse(needs.get("needsMethodCall").getAsBoolean());
    }

    @Test
    void mouseCoordinateComparedAgainstAPanelOriginOffsetIsClassifiedTileFieldsAndMouse() {
        // if ((this.guiTop + 18) >= mouseY) return; draw() — the real corpus's hover-range idiom
        // (methods.csv/javap-verified: drawGuiContainerBackgroundLayer(float,int,int) -> arg1
        // mouseX, arg2 mouseY; field_147009_r = guiTop). Mouse position must resolve to its OWN
        // operand kind (never "tile field", never "unknown"), and escalate the guard's requirement
        // past tileFieldsOnly without needing any tile-entity snapshot at all.
        JsonObject cond = guardedConditional(bg -> {
            Label skip = new Label();
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUITOP, "I");
            bg.visitIntInsn(BIPUSH, 18);
            bg.visitInsn(IADD);
            bg.visitVarInsn(ILOAD, 3); // arg2 = mouseY, local slot 3 ((F,I,I) -> this=0, arg0 float=1, arg1 int=2, arg2 int=3)
            bg.visitJumpInsn(IF_ICMPGE, skip);
            drawCall(bg);
            bg.visitLabel(skip);
        });

        JsonObject skipCondition = cond.getAsJsonObject("skipCondition");
        assertEquals("COMPARE", skipCondition.get("op").getAsString());
        assertEquals("GE", skipCondition.get("compareOp").getAsString());
        JsonObject left = skipCondition.getAsJsonObject("left");
        assertEquals("panelOrigin", left.get("kind").getAsString(), "expected guiTop+18 to resolve as a panel-origin operand: " + left);
        assertEquals("guiTop", left.get("axis").getAsString());
        assertEquals(18, left.get("delta").getAsInt());
        JsonObject right = skipCondition.getAsJsonObject("right");
        assertEquals("mouse", right.get("kind").getAsString(), "expected arg2 to resolve as the mouseY coordinate: " + right);
        assertEquals("y", right.get("axis").getAsString());

        JsonObject needs = cond.getAsJsonObject("guardNeeds");
        assertEquals("tileFieldsAndMouse", needs.get("level").getAsString(),
                "a panelOrigin+mouse guard needs no tile snapshot at all, but DOES need the mouse position: " + needs);
        assertFalse(needs.get("needsMethodCall").getAsBoolean());
    }

    @Test
    void arithmeticOverTwoTileFieldsIsStillHonestlyReportedAsOpaqueNeverGuessed() {
        // if (this.machine.heat + this.machine.heat <= 0) return; draw() — adding two non-constant
        // runtime values together is a real, common shape this project has never modelled (neither
        // operand is a compile-time constant, so MethodSim's IADD enrichment has no rule that
        // applies) and must stay honestly "opaque" with a real, specific cause - never silently
        // downgraded to "tileFieldsOnly" just because both raw leaves happen to be tile fields.
        JsonObject cond = guardedConditional(bg -> {
            Label skip = new Label();
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitInsn(IADD);
            bg.visitJumpInsn(IFLE, skip);
            drawCall(bg);
            bg.visitLabel(skip);
        });

        JsonObject skipCondition = cond.getAsJsonObject("skipCondition");
        assertEquals("COMPARE", skipCondition.get("op").getAsString());
        JsonObject left = skipCondition.getAsJsonObject("left");
        assertEquals("unknown", left.get("kind").getAsString(), "arithmetic over two runtime fields must stay unknown: " + left);
        assertEquals("arithmetic", left.get("reason").getAsString());

        JsonObject needs = cond.getAsJsonObject("guardNeeds");
        assertEquals("opaque", needs.get("level").getAsString());
        JsonArray causes = needs.getAsJsonArray("opaqueCauses");
        assertEquals(1, causes.size());
        assertEquals("arithmetic", causes.get(0).getAsString());
    }
}
