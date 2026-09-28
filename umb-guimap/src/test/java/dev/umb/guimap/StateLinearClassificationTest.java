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
 * Focused tests for the dynamic-rects lane: {@code DrawLayerScanner}'s PANEL_RELATIVE/STATE_LINEAR/
 * UNRESOLVED classification of a single {@code drawTexturedModalRect} argument, exercised directly
 * against small hand-built method bodies (bypassing the full {@code GuiClassAnalyzer} pipeline,
 * same "split out a focused test" convention as {@code UtilTest}). See
 * {@code research/out/legacy/guimap-notes/DYNAMIC-RECTS.md} for the design this verifies against
 * real (javap-checked) HBM bytecode shapes.
 */
class StateLinearClassificationTest {

    private static final String GUI = "test/GuiFake";
    private static final String TE = "test/TileEntityFake";
    private static final String CONTAINER = "test/ContainerFake";

    /** Runs {@code body} as a {@code drawGuiContainerBackgroundLayer} override and returns the
     *  single resulting drawTexturedModalRect row's {@code args} array. */
    private static JsonArray drawRectArgs(BodyWriter body) { return drawRectArgs(body, null, null); }

    private static JsonArray drawRectArgs(BodyWriter body, String guiInternal, String containerInternal) {
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
        teCw.visitField(ACC_PUBLIC, "progress", "I", null, null).visitEnd();
        MethodVisitor getHeatScaled = teCw.visitMethod(ACC_PUBLIC, "getHeatScaled", "(I)I", null, null);
        getHeatScaled.visitCode();
        getHeatScaled.visitVarInsn(ALOAD, 0);
        getHeatScaled.visitFieldInsn(GETFIELD, TE, "heat", "I");
        getHeatScaled.visitVarInsn(ILOAD, 1);
        getHeatScaled.visitInsn(IMUL);
        getHeatScaled.visitVarInsn(ALOAD, 0);
        getHeatScaled.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
        getHeatScaled.visitInsn(IDIV);
        getHeatScaled.visitInsn(IRETURN);
        getHeatScaled.visitMaxs(4, 2);
        getHeatScaled.visitEnd();
        teCw.visitEnd();
        jar.classes.put(TE, toNode(teCw));

        ClassWriter contCw = new ClassWriter(0);
        contCw.visit(V1_8, ACC_PUBLIC, CONTAINER, null, Vanilla.CONTAINER, null);
        contCw.visitField(ACC_PUBLIC, "level", "I", null, null).visitEnd();
        contCw.visitEnd();
        jar.classes.put(CONTAINER, toNode(contCw));

        FieldConstResolver resolver = new FieldConstResolver(jar);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, resolver, guiInternal, containerInternal);
        MethodNode mn = findMethod(guiNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC);
        scanner.scan(guiNode, mn);
        assertEquals(1, scanner.drawRects.size(), "expected exactly one drawTexturedModalRect call");
        return scanner.drawRects.get(0).getAsJsonArray("args");
    }

    private interface BodyWriter { void write(MethodVisitor mv); }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
        throw new IllegalStateException("method not found: " + name + desc);
    }

    private static ClassNode toNode(ClassWriter cw) {
        ClassNode cn = new ClassNode();
        new ClassReader(cw.toByteArray()).accept(cn, 0);
        return cn;
    }

    /** {@code this.func_73729_b(guiLeft, guiTop, u, v, w, h)} setup shared by most tests below:
     *  x/y stay bare guiLeft/guiTop (index 0/1, uninteresting here), u/v are 0/0 constants, and the
     *  caller supplies the w argument's bytecode — the arg under test, at index 4. */
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

    @Test
    void panelRelativeResolvesToTheRightConstant() {
        // The CRITICAL INSIGHT worked example: drawTexturedModalRect(guiLeft + 170, ...) must
        // resolve to the fully static panel-relative constant 170, never "dynamic".
        JsonArray args = drawRectArgs(bg -> {
            bg.visitVarInsn(ALOAD, 0);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUILEFT, "I");
            bg.visitIntInsn(SIPUSH, 170); bg.visitInsn(IADD); // 170 exceeds BIPUSH's signed-byte range
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUITOP, "I");
            bg.visitInsn(ICONST_0);
            bg.visitInsn(ICONST_0);
            bg.visitIntInsn(BIPUSH, 16);
            bg.visitIntInsn(BIPUSH, 16);
            bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        });
        JsonObject x = args.get(0).getAsJsonObject();
        assertEquals("position", x.get("kind").getAsString());
        assertEquals("PANEL_RELATIVE", x.get("classification").getAsString());
        assertEquals("guiLeft", x.get("base").getAsString());
        assertEquals(170, x.get("delta").getAsInt());
    }

    @Test
    void stateLinearCapturesMultiplierAndConstantDivisor() {
        // this.machine.heat * 24 / 100 — constant divisor, origin: a TE reached via a plain GUI
        // field (not the Container) — the "tileEntityViaGui" bucket.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitIntInsn(BIPUSH, 24);
            bg.visitInsn(IMUL);
            bg.visitIntInsn(BIPUSH, 100);
            bg.visitInsn(IDIV);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("STATE_LINEAR", w.get("classification").getAsString());
        JsonObject binding = w.getAsJsonObject("stateBinding");
        assertEquals(24.0, binding.get("multiplier").getAsDouble());
        JsonObject divisor = binding.getAsJsonObject("divisor");
        assertEquals("const", divisor.get("kind").getAsString());
        assertEquals(100.0, divisor.get("value").getAsDouble());
        JsonObject src = binding.getAsJsonObject("source");
        assertEquals("heat", src.get("fieldName").getAsString());
        assertEquals("test.TileEntityFake", src.get("ownerClass").getAsString());
        assertEquals("tileEntityViaGui", src.get("origin").getAsString());
    }

    @Test
    void stateLinearCapturesMultiplierAndFieldDivisor() {
        // this.machine.heat * 24 / this.machine.heatMax — the `field * SCALE / maxField` shape
        // called out explicitly in the task: divisor must be reported as a FIELD, not guessed at
        // as a number, and its own source/origin must be recorded too.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitIntInsn(BIPUSH, 24);
            bg.visitInsn(IMUL);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
            bg.visitInsn(IDIV);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("STATE_LINEAR", w.get("classification").getAsString());
        JsonObject binding = w.getAsJsonObject("stateBinding");
        assertEquals(24.0, binding.get("multiplier").getAsDouble());
        JsonObject divisor = binding.getAsJsonObject("divisor");
        assertEquals("field", divisor.get("kind").getAsString());
        assertEquals("heatMax", divisor.get("fieldName").getAsString());
        assertEquals("test.TileEntityFake", divisor.get("ownerClass").getAsString());
        assertEquals("tileEntityViaGui", divisor.get("origin").getAsString());
    }

    @Test
    void stateLinearOriginIsContainerForAFieldReadDirectlyOffThePairedContainer() {
        // ((ContainerFake) this.field_147002_h).level — one hop through the vanilla container
        // reference; MethodSim's CHECKCAST is a no-op, so the subsequent GETFIELD's own owner
        // (ContainerFake, exactly as real javac-emitted bytecode would carry after the cast) is
        // what tells describeSource this is a Container-owned field, independent of receiver depth.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_CONTAINER, "L" + Vanilla.CONTAINER + ";");
            bg.visitTypeInsn(CHECKCAST, CONTAINER);
            bg.visitFieldInsn(GETFIELD, CONTAINER, "level", "I");
        }), GUI, CONTAINER);
        JsonObject src = args.get(4).getAsJsonObject().getAsJsonObject("stateBinding").getAsJsonObject("source");
        assertEquals("container", src.get("origin").getAsString());
        assertEquals("level", src.get("fieldName").getAsString());
    }

    @Test
    void accessorInliningResolvesAGetScaledStyleHelperWithACallSiteScaleArgument() {
        // this.machine.getHeatScaled(51) — the exact shape javap showed for
        // TileEntityMachineRTG.getHeatScaled(int)/getPowerScaled(long) in the real HBM jar: the
        // scale factor is a CALL-SITE argument, not a literal inside the callee. MethodSim must
        // re-simulate the callee with 51 substituted for its parameter to recover the multiplier.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitIntInsn(BIPUSH, 51);
            bg.visitMethodInsn(INVOKEVIRTUAL, TE, "getHeatScaled", "(I)I", false);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("STATE_LINEAR", w.get("classification").getAsString());
        JsonObject binding = w.getAsJsonObject("stateBinding");
        assertEquals(51.0, binding.get("multiplier").getAsDouble());
        assertEquals("field", binding.getAsJsonObject("divisor").get("kind").getAsString());
        assertEquals("heatMax", binding.getAsJsonObject("divisor").get("fieldName").getAsString());
        assertEquals("heat", binding.getAsJsonObject("source").get("fieldName").getAsString());
    }

    @Test
    void mixedPanelBaseAndStateLinearForGuiTopMinusAmountShape() {
        // (guiTop + 61) - this.machine.progress — the "grow a bar up from a fixed anchor" idiom
        // (confirmed against TileEntityMachineRTG's heat gauge): a position term combined with a
        // state-varying term in the SAME expression. Must resolve to STATE_LINEAR with the panel
        // base recorded, never collapse to plain "arithmetic"/dynamic.
        JsonArray args = drawRectArgs(bg -> {
            bg.visitVarInsn(ALOAD, 0);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUILEFT, "I");
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUITOP, "I");
            bg.visitIntInsn(BIPUSH, 61); bg.visitInsn(IADD);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "progress", "I");
            bg.visitInsn(ISUB);
            bg.visitIntInsn(BIPUSH, 16); // u
            bg.visitIntInsn(BIPUSH, 16); // v
            bg.visitIntInsn(BIPUSH, 16); // w
            bg.visitIntInsn(BIPUSH, 16); // h
            bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        });
        JsonObject y = args.get(1).getAsJsonObject();
        assertEquals("STATE_LINEAR", y.get("classification").getAsString());
        JsonObject binding = y.getAsJsonObject("stateBinding");
        assertEquals("progress", binding.getAsJsonObject("source").get("fieldName").getAsString());
        assertEquals(-1, binding.get("sign").getAsInt());
        JsonObject panelBase = binding.getAsJsonObject("panelBase");
        assertEquals("guiTop", panelBase.get("axis").getAsString());
        assertEquals(61, panelBase.get("delta").getAsInt());
    }

    @Test
    void guardedDrawCallIsMarkedConditionalButStillRecorded() {
        JarIndex jar = new JarIndex();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "flag", "Z", null, null).visitEnd();
        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        var skip = new org.objectweb.asm.Label();
        bg.visitVarInsn(ALOAD, 0);
        bg.visitFieldInsn(GETFIELD, GUI, "flag", "Z");
        bg.visitJumpInsn(IFEQ, skip);
        bg.visitVarInsn(ALOAD, 0);
        bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0);
        bg.visitIntInsn(BIPUSH, 16); bg.visitIntInsn(BIPUSH, 16);
        bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        bg.visitLabel(skip);
        bg.visitInsn(RETURN);
        bg.visitMaxs(8, 4);
        bg.visitEnd();
        cw.visitEnd();
        ClassNode guiNode = toNode(cw);
        jar.classes.put(GUI, guiNode);

        FieldConstResolver resolver = new FieldConstResolver(jar);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, resolver, GUI, null);
        scanner.scan(guiNode, findMethod(guiNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));

        assertEquals(1, scanner.drawRects.size(), "a guarded draw call must still be recorded, never dropped");
        JsonObject cond = scanner.drawRects.get(0).getAsJsonObject().getAsJsonObject("conditional");
        assertTrue(cond.get("guarded").getAsBoolean());
        assertTrue(cond.get("guardDescription").getAsString().contains("flag"),
                "expected the guard description to mention the guarding field: " + cond.get("guardDescription"));
    }

    @Test
    void mathMinClampIsRecordedOnAStateLinearBinding() {
        // Math.min(this.machine.heat * 24 / this.machine.heatMax, 24) — a very common progress-bar
        // clamp (vanilla java.lang.Math, never mod-specific). Must unwrap to the same STATE_LINEAR
        // binding as the unclamped expression, with the clamp additionally recorded.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitIntInsn(BIPUSH, 24);
            bg.visitInsn(IMUL);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
            bg.visitInsn(IDIV);
            bg.visitIntInsn(BIPUSH, 24);
            bg.visitMethodInsn(INVOKESTATIC, "java/lang/Math", "min", "(II)I", false);
        }));
        JsonObject w = args.get(4).getAsJsonObject();
        assertEquals("STATE_LINEAR", w.get("classification").getAsString());
        JsonObject binding = w.getAsJsonObject("stateBinding");
        assertEquals(24.0, binding.get("multiplier").getAsDouble());
        assertEquals("java.lang.Math.min", binding.get("clampFunction").getAsString());
        assertEquals(24.0, binding.get("clampValue").getAsDouble());
    }

    @Test
    void singleFieldVsZeroGuardIsCapturedAsAStructuredFieldCondition() {
        // SYNC-BINDING lane: the real com.hbm...GUIMachineTurbofan shape -
        // "if (this.turbofan.afterburner <= 0) return;" (IFLE, single operand, plain getfield) -
        // must be captured structurally, not just as a human-readable guardDescription, so a
        // consumer that has bound "afterburner" to a sync register can evaluate the guard.
        JarIndex jar = new JarIndex();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "machine", "L" + TE + ";", null, null).visitEnd();
        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        var skip = new org.objectweb.asm.Label();
        bg.visitVarInsn(ALOAD, 0);
        bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
        bg.visitFieldInsn(GETFIELD, TE, "afterburner", "I");
        bg.visitJumpInsn(IFLE, skip);
        bg.visitVarInsn(ALOAD, 0);
        bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0);
        bg.visitIntInsn(BIPUSH, 16); bg.visitIntInsn(BIPUSH, 16);
        bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        bg.visitLabel(skip);
        bg.visitInsn(RETURN);
        bg.visitMaxs(8, 4);
        bg.visitEnd();
        cw.visitEnd();
        ClassNode guiNode = toNode(cw);
        jar.classes.put(GUI, guiNode);

        FieldConstResolver resolver = new FieldConstResolver(jar);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, resolver, GUI, null);
        scanner.scan(guiNode, findMethod(guiNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));

        JsonObject cond = scanner.drawRects.get(0).getAsJsonObject().getAsJsonObject("conditional");
        assertTrue(cond.get("guarded").getAsBoolean());
        assertTrue(cond.has("fieldCondition"), "expected a structured fieldCondition: " + cond);
        JsonObject fc = cond.getAsJsonObject("fieldCondition");
        assertEquals("afterburner", fc.getAsJsonObject("source").get("fieldName").getAsString());
        assertEquals("LE", fc.get("skipOp").getAsString());
        assertEquals(0, fc.get("skipValue").getAsInt());
    }

    // ---------------- TILE-FIELD-REQUIREMENTS lane (schemaVersion 5) ----------------
    // Additive on top of the STATE_LINEAR/guard capture above: every source/divisor now also
    // carries "fieldDesc" (the leaf field's own JVM descriptor) and, when the receiver chain
    // resolves to a concrete tile-entity anchor within the bounded hop budget, a "fieldRequirement"
    // naming that tile-entity class and the exact field-access chain a host snapshot would need.

    @Test
    void fieldRequirementNamesTheConcreteTileEntityClassAndDescForAFieldReachedViaTheGuisOwnReference() {
        // this.machine.heat * 24 / 100 - the "field via the GUI's own tile-entity reference" shape
        // (tileEntityViaGui), now additionally required to carry fieldDesc + a resolved, 1-hop
        // fieldRequirement naming the concrete TileEntityFake class.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitIntInsn(BIPUSH, 24);
            bg.visitInsn(IMUL);
            bg.visitIntInsn(BIPUSH, 100);
            bg.visitInsn(IDIV);
        }));
        JsonObject src = args.get(4).getAsJsonObject().getAsJsonObject("stateBinding").getAsJsonObject("source");
        assertEquals("I", src.get("fieldDesc").getAsString(), "leaf field descriptor must be reported: " + src);
        JsonObject req = src.getAsJsonObject("fieldRequirement");
        assertNotNull(req, "expected a resolved fieldRequirement: " + src);
        assertEquals("gui", req.get("reachedVia").getAsString());
        assertEquals("test.TileEntityFake", req.get("tileEntityClass").getAsString());
        JsonArray hops = req.getAsJsonArray("hops");
        assertEquals(1, hops.size(), "a direct this.machine.heat read is a single hop off the anchor: " + hops);
        JsonObject hop = hops.get(0).getAsJsonObject();
        assertEquals("heat", hop.get("fieldName").getAsString());
        assertEquals("I", hop.get("desc").getAsString());
        assertEquals("field", hop.get("kind").getAsString());
    }

    @Test
    void fieldRequirementIsResolvedForAFieldDivisorTooNotJustTheNumerator() {
        // this.machine.heat * 24 / this.machine.heatMax - the divisor half of the requirement must
        // resolve to its OWN fieldRequirement, independent of the numerator's.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
            bg.visitIntInsn(BIPUSH, 24);
            bg.visitInsn(IMUL);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heatMax", "I");
            bg.visitInsn(IDIV);
        }));
        JsonObject binding = args.get(4).getAsJsonObject().getAsJsonObject("stateBinding");
        JsonObject divisor = binding.getAsJsonObject("divisor");
        assertEquals("I", divisor.get("fieldDesc").getAsString());
        JsonObject req = divisor.getAsJsonObject("fieldRequirement");
        assertNotNull(req, "expected the divisor field to resolve its own fieldRequirement: " + divisor);
        assertEquals("test.TileEntityFake", req.get("tileEntityClass").getAsString());
        assertEquals("heatMax", req.getAsJsonArray("hops").get(0).getAsJsonObject().get("fieldName").getAsString());
        // and the numerator's own fieldRequirement must still be present, independently
        assertNotNull(binding.getAsJsonObject("source").getAsJsonObject("fieldRequirement"));
    }

    @Test
    void guardFieldConditionCapturesItsOwnFieldRequirementToo() {
        // The real com.hbm...GUIMachineTurbofan shape - "if (this.turbofan.afterburner <= 0) return;"
        // - must resolve a fieldRequirement on the GUARD's field too, not just on gauge numerators/
        // divisors, so a bound guard field is equally ready for a live per-tile snapshot.
        JarIndex jar = new JarIndex();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "machine", "L" + TE + ";", null, null).visitEnd();
        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        var skip = new org.objectweb.asm.Label();
        bg.visitVarInsn(ALOAD, 0);
        bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
        bg.visitFieldInsn(GETFIELD, TE, "afterburner", "I");
        bg.visitJumpInsn(IFLE, skip);
        bg.visitVarInsn(ALOAD, 0);
        bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0); bg.visitInsn(ICONST_0);
        bg.visitIntInsn(BIPUSH, 16); bg.visitIntInsn(BIPUSH, 16);
        bg.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        bg.visitLabel(skip);
        bg.visitInsn(RETURN);
        bg.visitMaxs(8, 4);
        bg.visitEnd();
        cw.visitEnd();
        ClassNode guiNode = toNode(cw);
        jar.classes.put(GUI, guiNode);
        ClassWriter teCw = new ClassWriter(0);
        teCw.visit(V1_8, ACC_PUBLIC, TE, null, "java/lang/Object", null);
        teCw.visitField(ACC_PUBLIC, "afterburner", "I", null, null).visitEnd();
        teCw.visitEnd();
        jar.classes.put(TE, toNode(teCw));

        FieldConstResolver resolver = new FieldConstResolver(jar);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, resolver, GUI, null);
        scanner.scan(guiNode, findMethod(guiNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));

        JsonObject fc = scanner.drawRects.get(0).getAsJsonObject().getAsJsonObject("conditional").getAsJsonObject("fieldCondition");
        JsonObject src = fc.getAsJsonObject("source");
        assertEquals("I", src.get("fieldDesc").getAsString());
        JsonObject req = src.getAsJsonObject("fieldRequirement");
        assertNotNull(req, "expected the guard's own field to resolve a fieldRequirement: " + src);
        assertEquals("test.TileEntityFake", req.get("tileEntityClass").getAsString());
        assertEquals("afterburner", req.getAsJsonArray("hops").get(0).getAsJsonObject().get("fieldName").getAsString());
    }

    @Test
    void fieldRequirementWalksOneBoundedExtraHopForAnEmbeddedObjectField() {
        // this.machine.tank.fluid * 24 / this.machine.tank.maxFluid - the "tank levels are frequently
        // a fluid-tank OBJECT whose amount/capacity are separate reads" shape named explicitly in
        // this lane's brief: ONE hop beyond the tile-entity anchor (machine -> tank), then the leaf
        // field off THAT embedded object.
        JarIndex jar = new JarIndex();
        String TANK = "test/TankFake";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "machine", "L" + TE + ";", null, null).visitEnd();
        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "tank", "L" + TANK + ";");
            bg.visitFieldInsn(GETFIELD, TANK, "fluid", "I");
            bg.visitIntInsn(BIPUSH, 24);
            bg.visitInsn(IMUL);
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "tank", "L" + TANK + ";");
            bg.visitFieldInsn(GETFIELD, TANK, "maxFluid", "I");
            bg.visitInsn(IDIV);
        });
        bg.visitInsn(RETURN);
        bg.visitMaxs(16, 8);
        bg.visitEnd();
        cw.visitEnd();
        ClassNode guiNode = toNode(cw);
        jar.classes.put(GUI, guiNode);

        ClassWriter teCw = new ClassWriter(0);
        teCw.visit(V1_8, ACC_PUBLIC, TE, null, "java/lang/Object", null);
        teCw.visitField(ACC_PUBLIC, "tank", "L" + TANK + ";", null, null).visitEnd();
        teCw.visitEnd();
        jar.classes.put(TE, toNode(teCw));

        ClassWriter tankCw = new ClassWriter(0);
        tankCw.visit(V1_8, ACC_PUBLIC, TANK, null, "java/lang/Object", null);
        tankCw.visitField(ACC_PUBLIC, "fluid", "I", null, null).visitEnd();
        tankCw.visitField(ACC_PUBLIC, "maxFluid", "I", null, null).visitEnd();
        tankCw.visitEnd();
        jar.classes.put(TANK, toNode(tankCw));

        FieldConstResolver resolver = new FieldConstResolver(jar);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, resolver, GUI, null);
        scanner.scan(guiNode, findMethod(guiNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));
        JsonArray args = scanner.drawRects.get(0).getAsJsonArray("args");

        JsonObject binding = args.get(4).getAsJsonObject().getAsJsonObject("stateBinding");
        JsonObject src = binding.getAsJsonObject("source");
        assertEquals("fluid", src.get("fieldName").getAsString());
        JsonObject req = src.getAsJsonObject("fieldRequirement");
        assertNotNull(req, "expected a resolved fieldRequirement for a bounded one-extra-hop chain: " + src);
        assertEquals("gui", req.get("reachedVia").getAsString());
        assertEquals("test.TileEntityFake", req.get("tileEntityClass").getAsString());
        JsonArray hops = req.getAsJsonArray("hops");
        assertEquals(2, hops.size(), "machine->tank->fluid is the anchor plus ONE extra hop: " + hops);
        assertEquals("tank", hops.get(0).getAsJsonObject().get("fieldName").getAsString());
        assertEquals("L" + TANK + ";", hops.get(0).getAsJsonObject().get("desc").getAsString());
        assertEquals("field", hops.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals("fluid", hops.get(1).getAsJsonObject().get("fieldName").getAsString());

        JsonObject divisor = binding.getAsJsonObject("divisor");
        assertEquals("field", divisor.get("kind").getAsString());
        JsonObject divReq = divisor.getAsJsonObject("fieldRequirement");
        assertNotNull(divReq, "expected the divisor's own nested fieldRequirement too: " + divisor);
        assertEquals(2, divReq.getAsJsonArray("hops").size());
    }

    @Test
    void fieldRequirementIsAbsentWhenTheStateLinearFieldLivesOnTheGuiItselfNotATileEntity() {
        // guiLeft * 2 - resolves to STATE_LINEAR (multiplying a field forces this pass's general
        // "one runtime field" path, the same real shape javap showed for guiLeft/guiTop-derived
        // corpus-wide UI scaling in the real jar) but the field lives directly on the GUI, not any
        // tile entity - fieldRequirement must be absent rather than fabricated.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, Vanilla.F_GUILEFT, "I");
            bg.visitIntInsn(BIPUSH, 2);
            bg.visitInsn(IMUL);
        }));
        JsonObject src = args.get(4).getAsJsonObject().getAsJsonObject("stateBinding").getAsJsonObject("source");
        assertEquals("gui", src.get("origin").getAsString());
        assertFalse(src.has("fieldRequirement"), "a GUI-owned field must not report a tile-entity fieldRequirement: " + src);
    }

    @Test
    void fieldRequirementIsBoundedAndNeverWalksMoreThanOneExtraHopPastTheAnchor() {
        // this.machine.a.b.value - TWO extra hops past the tile-entity anchor (machine->a, a->b)
        // before the leaf. MAX_EXTRA_FIELD_HOPS=1 means this must stay unresolved rather than guess
        // arbitrarily deep - exactly the named-constant bound this lane's brief required.
        JarIndex jar = new JarIndex();
        String A = "test/AFake", B = "test/BFake";
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "machine", "L" + TE + ";", null, null).visitEnd();
        MethodVisitor bg = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg.visitCode();
        drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "a", "L" + A + ";");
            bg.visitFieldInsn(GETFIELD, A, "b", "L" + B + ";");
            bg.visitFieldInsn(GETFIELD, B, "value", "I");
        });
        bg.visitInsn(RETURN);
        bg.visitMaxs(16, 8);
        bg.visitEnd();
        cw.visitEnd();
        ClassNode guiNode = toNode(cw);
        jar.classes.put(GUI, guiNode);
        ClassWriter teCw = new ClassWriter(0);
        teCw.visit(V1_8, ACC_PUBLIC, TE, null, "java/lang/Object", null);
        teCw.visitField(ACC_PUBLIC, "a", "L" + A + ";", null, null).visitEnd();
        teCw.visitEnd();
        jar.classes.put(TE, toNode(teCw));
        ClassWriter aCw = new ClassWriter(0);
        aCw.visit(V1_8, ACC_PUBLIC, A, null, "java/lang/Object", null);
        aCw.visitField(ACC_PUBLIC, "b", "L" + B + ";", null, null).visitEnd();
        aCw.visitEnd();
        jar.classes.put(A, toNode(aCw));
        ClassWriter bCw = new ClassWriter(0);
        bCw.visit(V1_8, ACC_PUBLIC, B, null, "java/lang/Object", null);
        bCw.visitField(ACC_PUBLIC, "value", "I", null, null).visitEnd();
        bCw.visitEnd();
        jar.classes.put(B, toNode(bCw));

        FieldConstResolver resolver = new FieldConstResolver(jar);
        DrawLayerScanner scanner = new DrawLayerScanner(jar, resolver, GUI, null);
        scanner.scan(guiNode, findMethod(guiNode, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));
        JsonArray args = scanner.drawRects.get(0).getAsJsonArray("args");
        JsonObject src = args.get(4).getAsJsonObject().getAsJsonObject("stateBinding").getAsJsonObject("source");
        assertEquals("value", src.get("fieldName").getAsString());
        assertFalse(src.has("fieldRequirement"),
                "a chain needing 2 extra hops must stay unresolved (bounded), never guessed: " + src);
    }

    @Test
    void twoOperandOrMultiFrameGuardsNeverEmitAFieldConditionRatherThanGuessOne() {
        // Two-operand compares (IF_ICMPxx) and ANDed multi-frame guards are deliberately left
        // unstructured — combining them generically is out of scope; never fabricate a condition.
        JsonArray args = drawRectArgs(bg -> drawWithWidthArg(bg, () -> {
            bg.visitVarInsn(ALOAD, 0); bg.visitFieldInsn(GETFIELD, GUI, "machine", "L" + TE + ";");
            bg.visitFieldInsn(GETFIELD, TE, "heat", "I");
        }));
        // no exception, and the (single, unguarded here) rect still resolves normally - this test
        // exists to document the scope boundary via the dedicated two-frame case below.
        assertEquals("STATE_LINEAR", args.get(4).getAsJsonObject().get("classification").getAsString());

        JarIndex jar = new JarIndex();
        ClassWriter cw = new ClassWriter(0);
        cw.visit(V1_8, ACC_PUBLIC, GUI, null, Vanilla.GUI_CONTAINER, null);
        cw.visitField(ACC_PRIVATE, "flagA", "Z", null, null).visitEnd();
        cw.visitField(ACC_PRIVATE, "flagB", "Z", null, null).visitEnd();
        MethodVisitor bg2 = cw.visitMethod(ACC_PROTECTED, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC, null, null);
        bg2.visitCode();
        var skipOuter = new org.objectweb.asm.Label();
        var skipInner = new org.objectweb.asm.Label();
        bg2.visitVarInsn(ALOAD, 0);
        bg2.visitFieldInsn(GETFIELD, GUI, "flagA", "Z");
        bg2.visitJumpInsn(IFEQ, skipOuter);
        bg2.visitVarInsn(ALOAD, 0);
        bg2.visitFieldInsn(GETFIELD, GUI, "flagB", "Z");
        bg2.visitJumpInsn(IFEQ, skipInner);
        bg2.visitVarInsn(ALOAD, 0);
        bg2.visitInsn(ICONST_0); bg2.visitInsn(ICONST_0); bg2.visitInsn(ICONST_0); bg2.visitInsn(ICONST_0);
        bg2.visitIntInsn(BIPUSH, 16); bg2.visitIntInsn(BIPUSH, 16);
        bg2.visitMethodInsn(INVOKEVIRTUAL, GUI, Vanilla.M_DRAW_RECT, Vanilla.M_DRAW_RECT_DESC, false);
        bg2.visitLabel(skipInner);
        bg2.visitLabel(skipOuter);
        bg2.visitInsn(RETURN);
        bg2.visitMaxs(8, 4);
        bg2.visitEnd();
        cw.visitEnd();
        ClassNode guiNode2 = toNode(cw);
        jar.classes.put(GUI, guiNode2);
        DrawLayerScanner scanner2 = new DrawLayerScanner(jar, new FieldConstResolver(jar), GUI, null);
        scanner2.scan(guiNode2, findMethod(guiNode2, Vanilla.M_DRAW_BG, Vanilla.M_DRAW_BG_DESC));
        JsonObject cond2 = scanner2.drawRects.get(0).getAsJsonObject().getAsJsonObject("conditional");
        assertTrue(cond2.get("guarded").getAsBoolean());
        assertFalse(cond2.has("fieldCondition"), "an ANDed two-frame guard must never emit a fieldCondition: " + cond2);
    }
}
