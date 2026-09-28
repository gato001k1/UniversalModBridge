package dev.umb.guimap;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scans one method body for the vanilla drawing calls GENERALIZATION-PLAN.md GAP 2 asks for:
 * {@code TextureManager.bindTexture}, {@code Gui.drawTexturedModalRect}, and
 * {@code FontRenderer.drawString}/{@code drawStringWithShadow} (optionally through
 * {@code StatCollector.translateToLocal}/{@code I18n.format}). Every argument is classified
 * generically off the {@link Val} the stack backtracker produced — never off a class or method
 * name specific to any one mod.
 *
 * <p>Every {@code drawTexturedModalRect} argument is classified into exactly one of four buckets
 * (see {@code research/out/legacy/guimap-notes/DYNAMIC-RECTS.md} for the full design and evidence):
 * <ul>
 *   <li>{@code CONST} ({@code kind:"const"}) — a compile-time constant.</li>
 *   <li>{@code PANEL_RELATIVE} ({@code kind:"position"}) — linear over {@code guiLeft}/{@code guiTop}
 *       and constants; the renderer computes these itself, so the resolved panel-relative int is
 *       emitted.</li>
 *   <li>{@code STATE_LINEAR} ({@code kind:"stateLinear"}) — a linear expression over ONE runtime
 *       field (the general 1.7.10 progress-bar shape {@code field * SCALE / DIVISOR}), optionally
 *       combined with a {@code guiLeft}/{@code guiTop} panel base. The runtime VALUE is never
 *       resolved — a structured {@code stateBinding} names the source field, multiplier, divisor
 *       (constant or another field) and any constant offset, so the 26.2 host can wire it to a
 *       container sync index later.</li>
 *   <li>{@code UNRESOLVED} ({@code kind:"dynamic"}) — anything else, always with a precise
 *       human-readable {@code reason}.</li>
 * </ul>
 */
public final class DrawLayerScanner {

    private final JarIndex jar;
    private final FieldConstResolver resolver;
    /** Internal name of the GUI class being analysed, and its paired Container (may be null when
     *  the pairing is not exact/inferred) — used only to classify a STATE_LINEAR source field's
     *  origin (gui / container / a tile entity reached through either). Never used for anything
     *  mod-specific: both are generic per-jar identifiers, not fixed names. */
    private final String guiClassInternal;
    private final String containerClassInternal;

    public final List<JsonObject> textureBinds = new ArrayList<>();
    public final List<JsonObject> drawRects = new ArrayList<>();
    public final List<JsonObject> labels = new ArrayList<>();
    /**
     * GUI-fidelity lane: value-selecting twin-const branches found during the main scan
     * (see {@link #detectTwinBranch}), expanded afterwards by {@link #mergeTwinVariants}.
     * Never read during the main scan itself.
     */
    final List<TwinCandidate> twinCandidates = new ArrayList<>();

    public DrawLayerScanner(JarIndex jar, FieldConstResolver resolver) {
        this(jar, resolver, null, null);
    }

    public DrawLayerScanner(JarIndex jar, FieldConstResolver resolver, String guiClassInternal, String containerClassInternal) {
        this.jar = jar; this.resolver = resolver;
        this.guiClassInternal = guiClassInternal; this.containerClassInternal = containerClassInternal;
    }

    public void scan(ClassNode cn, MethodNode mn) {
        // GUI-fidelity lane: one scan() call covers exactly one method body, but the same
        // scanner instance is reused across every method of a class (see GuiClassAnalyzer) -
        // twin candidates recorded while scanning method A must never leak into method B's
        // merge (stale indices crash the variant builder). Clear per call, always.
        twinCandidates.clear();
        // GUARD-EXPRESSIONS lane: recognise the exact vanilla draw-method override this method IS
        // (by name+desc, not by heuristic), so a guard's PARAM operand can be identified as the
        // mouseX/mouseY the renderer already receives every frame - every one of these three
        // signatures is javap/methods.csv-verified (see Vanilla.java): drawGuiContainerBackgroundLayer
        // (FII: partialTicks,mouseX,mouseY), drawGuiContainerForegroundLayer (II: mouseX,mouseY), and
        // a GuiScreen-only class's own drawScreen (IIF: mouseX,mouseY,partialTicks). Any other method
        // (a private helper, an unrelated override) gets {-1,-1} - its PARAM operands are honestly
        // reported as "unrecognised", never guessed at.
        final int mouseXIdx, mouseYIdx;
        if (Vanilla.M_DRAW_BG.equals(mn.name) && Vanilla.M_DRAW_BG_DESC.equals(mn.desc)) { mouseXIdx = 1; mouseYIdx = 2; }
        else if (Vanilla.M_DRAW_FG.equals(mn.name) && Vanilla.M_DRAW_FG_DESC.equals(mn.desc)) { mouseXIdx = 0; mouseYIdx = 1; }
        else if (Vanilla.M_DRAW_SCREEN.equals(mn.name) && Vanilla.M_DRAW_SCREEN_DESC.equals(mn.desc)) { mouseXIdx = 0; mouseYIdx = 1; }
        else { mouseXIdx = -1; mouseYIdx = -1; }
        // umb-guimap addition: a lightweight guard-region tracker. Real 1.7.10 GUI draw methods are
        // straight-line if-chains (no loops), so "push a guard frame on a forward conditional jump,
        // pop every frame targeting a label once that label is reached" is a sound approximation of
        // "is this instruction inside an `if (...)` block" without needing a real CFG/dominator pass.
        // A precomputed instruction index lets us tell a forward jump (a guard) from a backward one
        // (a loop back-edge, which this deliberately does not model as a guard).
        Map<AbstractInsnNode, Integer> indexOf = new HashMap<>();
        AbstractInsnNode[] arr = mn.instructions != null ? mn.instructions.toArray()
                : new AbstractInsnNode[0];
        for (int i = 0; i < arr.length; i++) indexOf.put(arr[i], i);
        Deque<GuardFrame> guardStack = new ArrayDeque<>();

        // NOTEXTURE-GAP lane: guiClassInternal is the CONCRETE class this scan is for (may differ
        // from cn.name — a mod frequently puts a shared draw method on an abstract base class, see
        // GuiClassAnalyzer's own javadoc on GUITurretBase). Passing it lets MethodSim's accessor
        // inlining resolve a `this.someOverride()` call against the actual runtime type instead of
        // the base class's own override (see MethodSim#selfClassInternal).
        MethodSim.run(cn, mn, jar, guiClassInternal, (insn, stack, locals) -> {
            if (insn instanceof LabelNode ln) {
                while (!guardStack.isEmpty() && guardStack.peek().target == ln) guardStack.pop();
                return;
            }
            int op = insn.getOpcode();
            if (isForwardConditionalJump(insn, indexOf)) {
                JumpInsnNode j = (JumpInsnNode) insn;
                boolean twoOperand = op >= Opcodes.IF_ICMPEQ && op <= Opcodes.IF_ACMPNE;
                Val singleOperand = !twoOperand && !stack.isEmpty() ? stack.get(stack.size() - 1) : null;
                // GUARD-EXPRESSIONS lane: for a genuine two-operand IF_ICMPxx/IF_ACMPxx, keep BOTH
                // real operands (not just the human-readable description built from them below) so
                // the new guard-expression tree can report a proper two-operand comparison instead
                // of collapsing it to an opaque "two-operand compare" the way fieldConditionJson
                // (unchanged, still single-operand-only) always has.
                Val twoOpLeft = twoOperand && stack.size() >= 2 ? stack.get(stack.size() - 2) : null;
                Val twoOpRight = twoOperand && stack.size() >= 2 ? stack.get(stack.size() - 1) : null;
                guardStack.push(new GuardFrame(j.label, describeGuard(op, stack), singleOperand, twoOpLeft, twoOpRight, op));
                // GUI-fidelity lane: value-selecting twin-const branch (assembler restrictedMode
                // sprite selector). Recorded, never acted on mid-scan - mergeTwinVariants expands it
                // afterwards into two straight-line variant scans.
                if (!twoOperand && (op == Opcodes.IFEQ || op == Opcodes.IFNE) && singleOperand != null) {
                    detectTwinBranch(arr, indexOf, insn, op, singleOperand);
                }
                return;
            }
            if (op != Opcodes.INVOKEVIRTUAL && op != Opcodes.INVOKESTATIC
                    && op != Opcodes.INVOKESPECIAL && op != Opcodes.INVOKEINTERFACE) return;
            MethodInsnNode m = (MethodInsnNode) insn;
            int argc = Type.getArgumentTypes(m.desc).length;

            if (Vanilla.isBindTextureCall(m.owner, m.name) && argc >= 1) {
                Val t = argAt(stack, argc, 0);
                textureBinds.add(classifyTexture(t));
                return;
            }
            if (Vanilla.M_DRAW_RECT.equals(m.name) && argc == 6) {
                JsonObject row = new JsonObject();
                JsonArray args = new JsonArray();
                boolean dynamic = false;
                for (int i = 0; i < 6; i++) {
                    JsonObject a = classifyInt(argAt(stack, argc, i), 0);
                    args.add(a);
                    // The static/dynamic split is about what art gets drawn and how big — u,v,w,h
                    // (indices 2-5). x,y (0,1) are excluded: EVERY draw call in EVERY 1.7.10 GUI
                    // positions relative to the panel's own runtime guiLeft/guiTop-equivalent, so
                    // an x/y that only resolves to "dynamic" because a GuiScreen-only class uses
                    // its own (non-vanilla-named) position fields would otherwise misclassify an
                    // otherwise fully static sprite. A position that is GENUINELY data-driven
                    // (e.g. a lever/indicator whose x moves with tile state) still shows up in the
                    // per-argument data below with kind:"dynamic" — only the call-level summary
                    // flag ignores it.
                    if (i >= 2 && "dynamic".equals(a.get("kind").getAsString())) dynamic = true;
                }
                row.add("args", args); // order: x,y,u,v,w,h
                row.addProperty("dynamic", dynamic);
                // Per-rect texture association (GENERALIZATION-PLAN.md GAP 2 / SCREEN-RENDER lane):
                // a 1.7.10 GUI can bindTexture() several times in one draw method, and every
                // drawTexturedModalRect call implicitly uses whichever bind most recently executed
                // before it. textureBinds and drawRects are both appended in the SAME single
                // instruction-order pass (MethodSim.run visits the method's bytecode once, in
                // order), so "how many binds have been recorded so far" at the moment this rect is
                // emitted is exactly the index of the texture this rect actually uses — no name
                // matching, no guessing. -1 means the rect's method drew without ever binding a
                // texture of its own (relies on whatever texture was already bound by the caller,
                // e.g. a shared helper method) - a real, reportable case, not an error.
                row.addProperty("textureBindIndex", textureBinds.isEmpty() ? -1 : textureBinds.size() - 1);
                JsonObject cond = new JsonObject();
                boolean guarded = !guardStack.isEmpty();
                cond.addProperty("guarded", guarded);
                if (guarded) {
                    StringBuilder sb = new StringBuilder();
                    for (GuardFrame f : guardStack) { if (sb.length() > 0) sb.append(" && "); sb.append(f.description); }
                    cond.addProperty("guardDescription", sb.toString());
                    // SYNC-BINDING lane: only when the rect is guarded by EXACTLY ONE frame (no
                    // ANDed conditions to combine) AND that frame is a plain "field vs 0" test do we
                    // emit a structured, machine-evaluable condition — see fieldConditionJson.
                    if (guardStack.size() == 1) {
                        GuardFrame only = guardStack.peek();
                        if (only.singleOperand != null) {
                            JsonObject fc = fieldConditionJson(only.singleOperand, only.opcode);
                            if (fc != null) cond.add("fieldCondition", fc);
                        }
                    }
                    // GUARD-EXPRESSIONS lane (schemaVersion 6): a general boolean expression tree
                    // over EVERY frame's comparison (AND/OR of two-operand COMPARE leaves), with
                    // BOTH real operands recorded for each - not just the single-frame
                    // getfield-vs-0 shape `fieldCondition` above handles. Purely additive: never
                    // reads or changes `fieldCondition`/`guardDescription` above. See
                    // research/out/legacy/guimap-notes/GUARD-EXPRESSIONS.md.
                    JsonObject skipCondition = skipConditionJson(guardStack, mouseXIdx, mouseYIdx);
                    cond.add("skipCondition", skipCondition);
                    cond.add("guardNeeds", classifyGuardNeed(skipCondition));
                } else {
                    cond.addProperty("guardDescription", (String) null);
                }
                row.add("conditional", cond);
                drawRects.add(row);
                return;
            }
            if (Vanilla.isDrawStringCall(m.owner, m.name) && argc == 4) {
                Val text = argAt(stack, argc, 0);
                Val x = argAt(stack, argc, 1), y = argAt(stack, argc, 2);
                labels.add(classifyLabel(text, x, y));
            }
        });
        mergeTwinVariants(cn, mn);
    }

    private static Val argAt(List<Val> stack, int argc, int indexFromLeft) {
        int i = stack.size() - argc + indexFromLeft;
        return i >= 0 && i < stack.size() ? stack.get(i) : Val.unknown("stack underflow");
    }

    // ---------------- GUI-fidelity lane: twin-const value branches ----------------
    //
    // A draw argument selected by a boolean tile field between two constants (assembler
    // `v = 61 + (restrictedMode ? 16 : 0)`) cannot survive MethodSim's single-pass scan: both
    // arms execute linearly, the join label clears the stack, and every arg of the consuming
    // draw call degrades to "stack underflow" (provably never drawn - the host requires
    // PANEL_RELATIVE/CONST/bound STATE_LINEAR for every arg). Instead of guessing, this pass
    // proves BOTH outcomes by straight-line-izing each arm into its own variant method scan:
    //
    //   IFEQ/IFNE X, L_else | PUSH c1 | GOTO M | L_else: PUSH c2 | M: REST
    //
    // becomes variant A [..., PUSH c1, REST] (valid iff X true for IFEQ-taken... precisely:
    // valid on the fallthrough path) and variant B [..., PUSH c2, REST]. Draws identical in
    // both variants are the same rect (emitted once - the branch provably does not affect
    // them); draws that DIFFER are the twin pair, each guarded by its own path condition
    // (the difference itself is the consumption proof - no taint tracking needed).
    //
    // Bounds (every number below is a NAMED CONSTANT): MAX_TWIN_PATTERNS_PER_METHOD (at most
    // that many branch sites expand, else the first - no exponential blowup), MAX_TWIN_SPAN
    // (arm instructions), no backward jumps and no exception handlers anywhere in the method,
    // arms are single const-pushes, the condition is a single-operand IFEQ/IFNE over an
    // int-like tile field with provable snapshot hops. Anything else stays exactly as the main
    // scan left it - never guessed at.

    /** At most this many twin-branch sites expand per method (each doubles the variant scans). */
    static final int MAX_TWIN_PATTERNS_PER_METHOD = 2;
    /** Max instructions from the IFcc to the merge label (arms + labels + goto). */
    static final int MAX_TWIN_SPAN = 12;

    /** One proven twin-const value branch site. */
    static final class TwinCandidate {
        /** The IFEQ/IFNE instruction (index in the method's insn array). */
        int ifIdx;
        /** Taken-path const (fallthrough arm) and else-path const. */
        int constTaken;
        int constElse;
        /** Proven instruction indices (single source of truth from detection). */
        int pushTakenIdx = -1;
        int gotoIdx = -1;
        int elseLabelIdx = -1;
        int pushElseIdx = -1;
        int mergeIdx = -1;
        /** The merge label both arms join at. */
        LabelNode mergeLabel;
        /** Skip-guards for each path (fieldCondition-shaped, host-evaluable). */
        JsonObject takenGuard;
        JsonObject elseGuard;
    }

    /** False for variant rescans (nested twin patterns inside a variant stay unresolved). */
    boolean twinExpansionEnabled = true;

    /**
     * Tries to match the strict twin-const shape at a forward IFEQ/IFNE. Returns the candidate
     * or null. The operand must already prove a bindable boolean/int-like tile field (via the
     * same fieldConditionJson the guard path uses - plus a snapshot-hops requirement the plain
     * guard path does not need, since these guards MUST evaluate live for the twins to draw).
     */
    private void detectTwinBranch(AbstractInsnNode[] arr, Map<AbstractInsnNode, Integer> indexOf,
            AbstractInsnNode insn, int op, Val operand) {
        if (twinCandidates.size() >= MAX_TWIN_PATTERNS_PER_METHOD) return;
        Integer ifIdx = indexOf.get(insn);
        if (ifIdx == null) return;
        if (operand.desc == null || "ZSIB".indexOf(operand.desc.charAt(0)) < 0
                || operand.desc.length() != 1) return;
        JsonObject takenFc = fieldConditionJson(operand, op);
        JsonObject elseFc = fieldConditionJson(operand,
                op == Opcodes.IFEQ ? Opcodes.IFNE : Opcodes.IFEQ);
        if (System.getenv("UMB_GUIMAP_DEBUG") != null)
            System.err.println("[twin] fc taken=" + (takenFc != null) + " else=" + (elseFc != null)
                    + " operand=" + operand.reason);
        if (takenFc == null || elseFc == null) return;
        if (!hasSnapshotHops(takenFc)) {
            if (System.getenv("UMB_GUIMAP_DEBUG") != null)
                System.err.println("[twin] no snapshot hops");
            return;
        }
        // Strict linear shape (labels significant - only line numbers/frames may intervene):
        // IFcc, PUSH c1, GOTO M, L_else:, PUSH c2, M:. nextNodeIdx skips trivia but never
        // labels (a label between means control flow this pattern does not model).
        int i = nextNodeIdx(arr, ifIdx + 1);
        Integer c1 = constPushAt(arr, i);
        boolean dbgR = System.getenv("UMB_GUIMAP_DEBUG") != null && operand.reason != null
                && operand.reason.contains("restrictedMode");
        if (dbgR) System.err.println("[twin] restrictedMode walk: i=" + i + " c1=" + c1);
        if (c1 == null) return;
        int pushTakenIdx = i;
        i = nextNodeIdx(arr, i + 1);
        boolean okGoto = i >= 0 && (arr[i] instanceof JumpInsnNode gotoInsn)
                && gotoInsn.getOpcode() == Opcodes.GOTO;
        if (dbgR) System.err.println("[twin] restrictedMode goto@" + i + " ok=" + okGoto);
        if (!okGoto) return;
        JumpInsnNode gotoInsn2 = (JumpInsnNode) arr[i];
        int gotoIdx = i;
        Integer gotoTarget = indexOf.get(gotoInsn2.label);
        if (gotoTarget == null || gotoTarget <= i) return;
        i = nextNodeIdx(arr, i + 1);
        boolean okElse = i >= 0 && (arr[i] instanceof LabelNode)
                && arr[i].equals(((JumpInsnNode) insn).label);
        if (dbgR) System.err.println("[twin] restrictedMode else@" + i + " ok=" + okElse);
        if (!okElse) return;
        int elseLabelIdx = i;
        i = nextNodeIdx(arr, i + 1);
        Integer c2 = constPushAt(arr, i);
        boolean dbgR2 = System.getenv("UMB_GUIMAP_DEBUG") != null && operand.reason != null
                && operand.reason.contains("restrictedMode");
        if (dbgR2) System.err.println("[twin] restrictedMode c2@" + i + " =" + c2);
        if (c2 == null) return;
        int pushElseIdx = i;
        i = nextNodeIdx(arr, i + 1);
        boolean okMerge = i >= 0 && (arr[i] instanceof LabelNode)
                && arr[i].equals(gotoInsn2.label);
        if (dbgR2) System.err.println("[twin] restrictedMode merge@" + i + " ok=" + okMerge
                + " span=" + (i - ifIdx));
        if (!okMerge) return;
        int mergeIdx = i;
        if (i - ifIdx > MAX_TWIN_SPAN) return;
        TwinCandidate tc = new TwinCandidate();
        tc.ifIdx = ifIdx.intValue();
        tc.constTaken = c1.intValue();
        tc.constElse = c2.intValue();
        tc.pushTakenIdx = pushTakenIdx;
        tc.gotoIdx = gotoIdx;
        tc.elseLabelIdx = elseLabelIdx;
        tc.pushElseIdx = pushElseIdx;
        tc.mergeIdx = mergeIdx;
        tc.mergeLabel = gotoInsn2.label;
        tc.takenGuard = takenFc;
        tc.elseGuard = elseFc;
        twinCandidates.add(tc);
    }

    /** True when a fieldCondition's source carries snapshot hops (host-bindable). */
    private static boolean hasSnapshotHops(JsonObject fieldCondition) {
        JsonObject source = fieldCondition.getAsJsonObject("source");
        if (source == null) return false;
        return source.has("fieldRequirement") && source.get("fieldRequirement").isJsonObject();
    }

    /** Next index holding real code (skips labels and line numbers), or -1. */
    private static int nextCodeIdx(AbstractInsnNode[] arr, int from) {
        for (int i = from; i < arr.length; i++) {
            int t = arr[i].getType();
            if (t != AbstractInsnNode.LABEL && t != AbstractInsnNode.LINE
                    && t != AbstractInsnNode.FRAME) return i;
        }
        return -1;
    }

    /** Next index skipping only line-number/frame trivia (labels significant), or -1. */
    private static int nextNodeIdx(AbstractInsnNode[] arr, int from) {
        for (int i = from; i < arr.length; i++) {
            int t = arr[i].getType();
            if (t != AbstractInsnNode.LINE && t != AbstractInsnNode.FRAME) return i;
        }
        return -1;
    }

    /** Integer constant pushed by a single instruction, or null. */
    private static Integer constPushAt(AbstractInsnNode[] arr, int idx) {
        if (idx < 0 || idx >= arr.length) return null;
        AbstractInsnNode in = arr[idx];
        int op = in.getOpcode();
        if (op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
            return Integer.valueOf(op - Opcodes.ICONST_0);
        }
        if ((op == Opcodes.BIPUSH || op == Opcodes.SIPUSH) && in instanceof IntInsnNode) {
            return Integer.valueOf(((IntInsnNode) in).operand);
        }
        if (op == Opcodes.LDC && in instanceof LdcInsnNode
                && ((LdcInsnNode) in).cst instanceof Integer) {
            return (Integer) ((LdcInsnNode) in).cst;
        }
        return null;
    }

    /**
     * Post-scan twin expansion (see the section javadoc above). For every proven candidate:
     * rescan one straight-line variant per arm, keep draws identical in both variants once,
     * and keep differing draws as the twin pair with their path guards ANDed into their
     * skipConditions. Phase-1 rows with any stack-underflow arg are dropped - they can never
     * bind at runtime (the host requires PANEL_RELATIVE/CONST/bound STATE_LINEAR for every
     * arg), so dropping is behavior-preserving cleanup, never a loss. Scoped to twinned
     * methods only: every other method's rows are byte-identical to before this lane.
     */
    private void mergeTwinVariants(ClassNode cn, MethodNode mn) {
        if (!twinExpansionEnabled || twinCandidates.isEmpty()) return;
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) return;
        List<JsonObject> keptRects = new ArrayList<>();
        for (JsonObject r : drawRects) if (!hasUnderflowArg(r)) keptRects.add(r);
        drawRects.clear();
        drawRects.addAll(keptRects);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (JsonObject r : drawRects) seen.add(r.toString());
        java.util.Set<String> seenLabels = new java.util.HashSet<>();
        for (JsonObject l : labels) seenLabels.add(l.toString());
        for (TwinCandidate tc : twinCandidates) {
            // Loops far outside the pattern are irrelevant (each variant rescans linearly,
            // same fidelity as the main scan); a loop touching the pattern region itself
            // aborts just this candidate - duplicated loop bodies would mislead the
            // straight-line variant reasoning.
            if (loopTouchesRegion(mn, tc.ifIdx, tc.mergeIdx)) continue;
            DrawLayerScanner takenScan = new DrawLayerScanner(jar, resolver, guiClassInternal,
                    containerClassInternal);
            takenScan.twinExpansionEnabled = false;
            DrawLayerScanner elseScan = new DrawLayerScanner(jar, resolver, guiClassInternal,
                    containerClassInternal);
            elseScan.twinExpansionEnabled = false;
            MethodNode variantTaken = buildVariant(mn, tc, true);
            MethodNode variantElse = buildVariant(mn, tc, false);
            if (variantTaken == null || variantElse == null) continue;
            takenScan.scan(cn, variantTaken);
            elseScan.scan(cn, variantElse);
            java.util.Map<String, JsonObject> takenByKey = new java.util.LinkedHashMap<>();
            for (JsonObject r : takenScan.drawRects) takenByKey.put(stripGuard(r).toString(), r);
            java.util.Map<String, JsonObject> elseByKey = new java.util.LinkedHashMap<>();
            for (JsonObject r : elseScan.drawRects) elseByKey.put(stripGuard(r).toString(), r);
            java.util.Set<String> allKeys =
                    new java.util.LinkedHashSet<>(takenByKey.keySet());
            allKeys.addAll(elseByKey.keySet());
            for (String key : allKeys) {
                JsonObject a = takenByKey.get(key);
                JsonObject b = elseByKey.get(key);
                if (a != null && b != null) {
                    if (!hasUnderflowArg(a) && seen.add(a.toString())) drawRects.add(a);
                } else if (a != null) {
                    if (hasUnderflowArg(a)) continue;
                    JsonObject twin = andGuard(a, tc.takenGuard);
                    if (seen.add(twin.toString())) drawRects.add(twin);
                } else {
                    if (hasUnderflowArg(b)) continue;
                    JsonObject twin = andGuard(b, tc.elseGuard);
                    if (seen.add(twin.toString())) drawRects.add(twin);
                }
            }
            for (JsonObject l : takenScan.labels) if (seenLabels.add(l.toString())) labels.add(l);
            for (JsonObject l : elseScan.labels) if (seenLabels.add(l.toString())) labels.add(l);
        }
    }

    /** True when any draw arg carries a stack-underflow reason (provably never drawable). */
    private static boolean hasUnderflowArg(JsonObject row) {
        JsonArray args = row.getAsJsonArray("args");
        if (args == null) return false;
        for (int i = 0; i < args.size(); i++) {
            JsonObject a = args.get(i).getAsJsonObject();
            String reason = a.has("reason") && !a.get("reason").isJsonNull()
                    ? a.get("reason").getAsString() : "";
            String desc = a.has("desc") && !a.get("desc").isJsonNull()
                    ? a.get("desc").getAsString() : "";
            if (reason.contains("stack underflow") || desc.contains("stack underflow")) return true;
        }
        return false;
    }

    /** True when the method contains any backward jump (loops - twin expansion stays out). */
    private static boolean hasBackwardJump(MethodNode mn) {
        return loopTouchesRegion(mn, -1, Integer.MAX_VALUE);
    }

    /**
     * True when any backward jump (loop back-edge) has an endpoint strictly inside
     * [startIdx, endIdx] (or the range is unbounded). A loop far outside the twin pattern
     * region is harmless - each variant rescans linearly with the same fidelity as the main
     * scan - but a loop touching the pattern would invalidate the straight-line variant
     * reasoning, so that candidate is skipped.
     */
    private static boolean loopTouchesRegion(MethodNode mn, int startIdx, int endIdx) {
        if (mn.instructions == null) return false;
        Map<AbstractInsnNode, Integer> indexOf = new HashMap<>();
        AbstractInsnNode[] arr = mn.instructions.toArray();
        for (int i = 0; i < arr.length; i++) indexOf.put(arr[i], i);
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] instanceof JumpInsnNode j) {
                Integer to = indexOf.get(j.label);
                // A backward edge whose range overlaps the region (loop enclosing it, loop
                // inside it, or partial overlap) invalidates straight-line variant reasoning.
                if (to != null && to <= i && to <= endIdx && i >= startIdx) return true;
            }
        }
        return false;
    }

    /** Row copy without its guard (for twin diffing - guards are re-attached per path). */
    private static JsonObject stripGuard(JsonObject row) {
        JsonObject copy = row.deepCopy();
        copy.remove("conditional");
        return copy;
    }

    /**
     * ANDs a twin path guard into a row's skipCondition (existing guards stay - both must
     * pass, e.g. the arrow needs progress>0 AND its sprite row's mode state). Never touches
     * fieldCondition (the host prefers it exclusively - overwriting it would drop the
     * progress guard).
     */
    private JsonObject andGuard(JsonObject row, JsonObject twinGuardFc) {
        JsonObject copy = row.deepCopy();
        JsonObject twinNode = twinSkipNode(twinGuardFc);
        if (twinNode == null) return copy;
        JsonObject cond = copy.getAsJsonObject("conditional");
        if (cond == null) {
            cond = new JsonObject();
            cond.addProperty("guarded", true);
            copy.add("conditional", cond);
        }
        JsonObject existing = cond.getAsJsonObject("skipCondition");
        JsonObject combined;
        if (existing != null) {
            combined = new JsonObject();
            combined.addProperty("op", "AND");
            JsonArray ops = new JsonArray();
            ops.add(existing);
            ops.add(twinNode);
            combined.add("operands", ops);
        } else {
            combined = twinNode;
        }
        cond.add("skipCondition", combined);
        cond.add("guardNeeds", classifyGuardNeed(combined));
        String desc = cond.has("guardDescription") && !cond.get("guardDescription").isJsonNull()
                ? cond.get("guardDescription").getAsString() : "";
        String twinDesc = "twin-" + (twinGuardFc.has("skipOp") ? twinGuardFc.get("skipOp").getAsString() : "?");
        cond.addProperty("guardDescription", desc.isEmpty() ? twinDesc : desc + " && " + twinDesc);
        cond.addProperty("guarded", true);
        return copy;
    }

    /** Converts a fieldCondition-shaped guard ({source, skipOp, skipValue}) into a skipCondition
     *  COMPARE node the host's parseSkipConditionNode accepts. */
    private static JsonObject twinSkipNode(JsonObject fieldCondition) {
        JsonObject source = fieldCondition.getAsJsonObject("source");
        String skipOp = fieldCondition.has("skipOp") ? fieldCondition.get("skipOp").getAsString() : null;
        if (source == null || skipOp == null) return null;
        JsonObject req = source.getAsJsonObject("fieldRequirement");
        if (req == null) return null;
        JsonObject node = new JsonObject();
        node.addProperty("op", "COMPARE");
        node.addProperty("compareOp", skipOp);
        JsonObject left = new JsonObject();
        left.addProperty("kind", "tileField");
        left.add("fieldRequirement", req);
        left.add("source", source);
        node.add("left", left);
        JsonObject right = new JsonObject();
        right.addProperty("kind", "const");
        double skipValue = fieldCondition.has("skipValue") ? fieldCondition.get("skipValue").getAsDouble() : 0.0;
        right.addProperty("value", skipValue);
        node.add("right", right);
        return node;
    }

    /**
     * Builds one straight-line variant of a twinned method: the IFcc, its GOTO and the
     * discarded arm's const-push are removed; both labels stay as harmless no-ops. Aborts
     * (null) unless the pattern region is provably self-contained: no jump with source
     * outside [patternStart, merge] may target inside it (labels kept below would otherwise
     * silently rewire foreign control flow).
     */
    private static MethodNode buildVariant(MethodNode mn, TwinCandidate tc, boolean keepTaken) {
        AbstractInsnNode[] orig = mn.instructions.toArray();
        Map<AbstractInsnNode, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < orig.length; i++) indexOf.put(orig[i], i);
        // Re-verify the detection-time indices (defensive: the method must be unchanged).
        if (tc.pushTakenIdx < 0 || tc.gotoIdx < 0 || tc.elseLabelIdx < 0
                || tc.pushElseIdx < 0 || tc.mergeIdx < 0) {
            return null;
        }
        if (!(orig[tc.ifIdx] instanceof JumpInsnNode)) {
            return null;
        }
        if (constPushAt(orig, tc.pushTakenIdx) == null
                || constPushAt(orig, tc.pushTakenIdx).intValue() != tc.constTaken) return null;
        if (!(orig[tc.gotoIdx] instanceof JumpInsnNode)
                || ((JumpInsnNode) orig[tc.gotoIdx]).getOpcode() != Opcodes.GOTO) return null;
        if (!(orig[tc.elseLabelIdx] instanceof LabelNode)) return null;
        if (constPushAt(orig, tc.pushElseIdx) == null
                || constPushAt(orig, tc.pushElseIdx).intValue() != tc.constElse) return null;
        if (!(orig[tc.mergeIdx] instanceof LabelNode)
                || !orig[tc.mergeIdx].equals(((JumpInsnNode) orig[tc.gotoIdx]).label)) return null;
        int patStart = tc.ifIdx;
        int patEnd = tc.mergeIdx;
        java.util.Set<AbstractInsnNode> remove = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        remove.add(orig[tc.gotoIdx]);
        remove.add(orig[keepTaken ? tc.pushElseIdx : tc.pushTakenIdx]);
        // The IFcc itself becomes a POP: the branch consumed its condition operand, and the
        // straight-line variant must too - otherwise the leftover operand pollutes every
        // downstream stack slot (caught live: v classified as mode+16 instead of 77).
        AbstractInsnNode popForIf = new org.objectweb.asm.tree.InsnNode(Opcodes.POP);
        for (int i = 0; i < orig.length; i++) {
            if (orig[i] instanceof JumpInsnNode j) {
                if (i == tc.ifIdx || i == tc.gotoIdx) continue;
                Integer to = indexOf.get(j.label);
                if (to != null && to > patStart && to <= patEnd && i <= patStart) {
                    return null;
                }
            }
        }
        InsnList variant = new InsnList();
        Map<LabelNode, LabelNode> labelMap = new HashMap<>();
        // Pre-register EVERY label: ASM's LabelNode.clone(Map) returns labels.get(this),
        // i.e. null for a label no cloned jump references - but the twin pattern leaves
        // exactly such unreferenced labels behind (the removed IFcc/GOTO's targets), and
        // InsnList.add(null) throws. Pre-mapping keeps them as harmless no-op markers.
        for (AbstractInsnNode in : orig) {
            if (in instanceof LabelNode ln && !labelMap.containsKey(ln)) {
                labelMap.put(ln, new LabelNode());
            }
        }
        for (AbstractInsnNode in : orig) {
            if (remove.contains(in)) continue;
            if (in.equals(orig[tc.ifIdx])) {
                variant.add(popForIf.clone(labelMap));
                continue;
            }
            AbstractInsnNode copy = in.clone(labelMap);
            if (copy == null) return null;
            variant.add(copy);
        }
        MethodNode out = new MethodNode(mn.access, mn.name, mn.desc, mn.signature, null);
        out.instructions = variant;
        out.maxStack = mn.maxStack;
        out.maxLocals = mn.maxLocals;
        return out;
    }

    // ---------------- guard tracking ----------------

    private static final class GuardFrame {
        final LabelNode target; final String description;
        /** Only set for a single-operand IFxx form (never a two-operand IF_ICMPxx/IF_ACMPxx) whose
         *  operand is a plain getfield-chain value — lets a rect guarded by EXACTLY ONE such frame
         *  report a structured {@code conditional.fieldCondition} (SYNC-BINDING lane) instead of
         *  only a human-readable description, so a 26.2 consumer that has since bound this same
         *  field to a sync register can evaluate the guard instead of skipping unconditionally. */
        final Val singleOperand; final int opcode;
        /** Two-operand form only (IF_ICMPxx/IF_ACMPxx) — the exact stack operands, captured the
         *  same way {@link #describeGuard} already read them for the text description
         *  (GUARD-EXPRESSIONS lane addition; never read by the pre-existing single-operand
         *  {@code fieldCondition} path above). Both null for a single-operand form. */
        final Val twoOpLeft, twoOpRight;
        GuardFrame(LabelNode target, String description, Val singleOperand, Val twoOpLeft, Val twoOpRight, int opcode) {
            this.target = target; this.description = description;
            this.singleOperand = singleOperand; this.twoOpLeft = twoOpLeft; this.twoOpRight = twoOpRight;
            this.opcode = opcode;
        }
    }

    private static boolean isForwardConditionalJump(AbstractInsnNode insn, Map<AbstractInsnNode, Integer> indexOf) {
        if (!(insn instanceof JumpInsnNode j)) return false;
        switch (j.getOpcode()) {
            case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT: case Opcodes.IFGE:
            case Opcodes.IFGT: case Opcodes.IFLE: case Opcodes.IFNULL: case Opcodes.IFNONNULL:
            case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE: case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE: case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE:
            case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
                break;
            default: return false; // GOTO/JSR/table-switch etc. are not "guards" in this sense
        }
        Integer from = indexOf.get(insn), to = indexOf.get(j.label);
        return from != null && to != null && to > from;
    }

    private static String describeGuard(int opcode, List<Val> stack) {
        String opName = jumpOpcodeName(opcode);
        boolean twoOperand = opcode >= Opcodes.IF_ICMPEQ && opcode <= Opcodes.IF_ACMPNE;
        if (twoOperand && stack.size() >= 2) {
            return opName + " " + describeVal(stack.get(stack.size() - 2)) + " , " + describeVal(stack.get(stack.size() - 1));
        }
        if (!stack.isEmpty()) return opName + " " + describeVal(stack.get(stack.size() - 1));
        return opName;
    }

    /**
     * Structured form of a single-operand guard {@code IFxx <getfield chain>} test — e.g. the real
     * {@code IFLE getfield TileEntityMachineTurbofan.afterburner} guard on HBM's Turbofan afterburner
     * indicator. {@code op}/{@code value} describe the bytecode's own SKIP test (the guarded region
     * runs when this test is FALSE — a consumer evaluating this must negate it, e.g. {@code LE 0}
     * means "skip when <= 0", so draw only when the field is {@code > 0}). Null for anything that
     * isn't a plain field read (a two-operand compare, a method-call result, arithmetic, ...) —
     * never guessed at, exactly like every other classification in this file.
     */
    private JsonObject fieldConditionJson(Val v, int opcode) {
        if (v.kind != Val.Kind.UNKNOWN || v.owner == null || v.name == null
                || v.reason == null || !v.reason.startsWith("getfield ")) return null;
        String opName;
        switch (opcode) {
            case Opcodes.IFEQ: opName = "EQ"; break;
            case Opcodes.IFNE: opName = "NE"; break;
            case Opcodes.IFLT: opName = "LT"; break;
            case Opcodes.IFGE: opName = "GE"; break;
            case Opcodes.IFGT: opName = "GT"; break;
            case Opcodes.IFLE: opName = "LE"; break;
            default: return null; // IFNULL/IFNONNULL - not a numeric compare, nothing to bind
        }
        JsonObject o = new JsonObject();
        o.add("source", describeSource(v.owner, v.name, v.desc, v.receiver));
        o.addProperty("skipOp", opName);
        o.addProperty("skipValue", 0);
        return o;
    }

    // ---------------- GUARD-EXPRESSIONS lane: general boolean expression tree over a guard ----------------
    //
    // `conditional.skipCondition` generalises `conditional.fieldCondition` above to EVERY guard
    // shape this scanner tracks, not just "exactly one frame whose one operand is a plain getfield
    // chain": an AND'd multi-frame guard, a genuine two-operand IF_ICMPxx/IF_ACMPxx compare, and an
    // LCMP/FCMPx/DCMPx-then-IFxx compare (whose two real operands MethodSim now preserves via
    // Val#cmpLeft/cmpRight instead of discarding them). It uses the SAME "true = the original
    // bytecode SKIPS this draw" polarity `fieldCondition.skipOp`/SyncGuard#shouldSkip already
    // document, generalised: a single frame is one COMPARE leaf; N simultaneously-active frames are
    // combined with OR, because ANY one of them tripping already causes the method to return early
    // (skip) before the draw call is ever reached - the human-readable `guardDescription` above
    // joins the SAME frames with "&&" only because that text describes what must ALL be FALSE
    // (pass-through) to reach the draw, which is the negation of this OR. `NOT` is a supported node
    // shape in this schema (for a resolver's generality) but is never emitted by this extractor -
    // every real shape this straight-line guard-frame tracker produces reduces to OR-of-COMPAREs,
    // and nothing here fabricates a shape the bytecode doesn't show.

    /** Builds the full expression tree for one rect's guard: the single frame's comparison when
     *  there is only one, else an {@code OR} of every simultaneously-active frame's comparison
     *  (see the polarity note above). */
    private JsonObject skipConditionJson(Deque<GuardFrame> guardStack, int mouseX, int mouseY) {
        List<JsonObject> nodes = new ArrayList<>();
        for (GuardFrame f : guardStack) nodes.add(frameCompareNode(f, mouseX, mouseY));
        if (nodes.size() == 1) return nodes.get(0);
        JsonObject or = new JsonObject();
        or.addProperty("op", "OR");
        JsonArray operands = new JsonArray();
        for (JsonObject n : nodes) operands.add(n);
        or.add("operands", operands);
        return or;
    }

    /** One {@code GuardFrame} to one {@code COMPARE} leaf: a genuine two-operand IF_ICMPxx/IF_ACMPxx
     *  uses its two real stack operands directly; a single-operand IFxx whose operand is itself an
     *  {@code LCMP}/{@code FCMPx}/{@code DCMPx} result recovers ITS two real operands from
     *  {@code Val#cmpLeft}/{@code cmpRight} (the fix this lane made to {@code MethodSim}) instead of
     *  reporting the bare "compare" placeholder {@code fieldConditionJson}/{@code guardDescription}
     *  are stuck with; anything else compares its one real operand against an implicit constant
     *  {@code 0} (or {@code null} for {@code IFNULL}/{@code IFNONNULL}), exactly the SAME implicit
     *  right-hand side {@code fieldConditionJson} already assumes for that shape. */
    private JsonObject frameCompareNode(GuardFrame f, int mouseX, int mouseY) {
        String cmpOp = skipOpName(f.opcode);
        if (f.twoOpLeft != null || f.twoOpRight != null) {
            return compareNode(cmpOp, f.twoOpLeft, f.twoOpRight, mouseX, mouseY);
        }
        Val operand = f.singleOperand;
        if (operand != null && operand.kind == Val.Kind.UNKNOWN && "compare".equals(operand.reason)
                && operand.cmpLeft != null) {
            return compareNode(cmpOp, operand.cmpLeft, operand.cmpRight, mouseX, mouseY);
        }
        boolean isNullTest = f.opcode == Opcodes.IFNULL || f.opcode == Opcodes.IFNONNULL;
        return compareNode(cmpOp, operand, isNullTest ? Val.NULL_V : Val.number(0), mouseX, mouseY);
    }

    private JsonObject compareNode(String compareOp, Val left, Val right, int mouseX, int mouseY) {
        JsonObject o = new JsonObject();
        o.addProperty("op", "COMPARE");
        o.addProperty("compareOp", compareOp);
        o.add("left", operandJson(left, mouseX, mouseY));
        o.add("right", operandJson(right, mouseX, mouseY));
        return o;
    }

    /** Extends {@code fieldConditionJson}'s EQ/NE/LT/GE/GT/LE mapping to also name the two null-test
     *  opcodes (never bound there — "not a numeric compare, nothing to bind" — but fully
     *  representable in this general tree) and to accept the two-operand IF_ICMPxx/IF_ACMPxx forms,
     *  which compare to the SAME letters as their single-operand IFxx counterparts one JVM opcode
     *  range over. A private, independent copy — {@code fieldConditionJson} itself is untouched. */
    private static String skipOpName(int opcode) {
        switch (opcode) {
            case Opcodes.IFEQ: case Opcodes.IF_ICMPEQ: case Opcodes.IF_ACMPEQ: return "EQ";
            case Opcodes.IFNE: case Opcodes.IF_ICMPNE: case Opcodes.IF_ACMPNE: return "NE";
            case Opcodes.IFLT: case Opcodes.IF_ICMPLT: return "LT";
            case Opcodes.IFGE: case Opcodes.IF_ICMPGE: return "GE";
            case Opcodes.IFGT: case Opcodes.IF_ICMPGT: return "GT";
            case Opcodes.IFLE: case Opcodes.IF_ICMPLE: return "LE";
            case Opcodes.IFNULL: return "ISNULL";
            case Opcodes.IFNONNULL: return "NOTNULL";
            default: return "UNKNOWN(" + opcode + ")";
        }
    }

    /**
     * Classifies one guard-comparison OPERAND generically off the {@link Val} shape — never off a
     * mod-specific name. Six kinds, in resolution order:
     * <ul>
     *   <li>{@code const} — a compile-time constant (including {@code null}).</li>
     *   <li>{@code mouse} — a method PARAM slot this scan already proved (by the enclosing method's
     *       own name+desc, javap/methods.csv-verified in {@code scan()}) is the vanilla mouseX/
     *       mouseY the renderer receives every frame.</li>
     *   <li>{@code panelOrigin} — {@code guiLeft}/{@code guiTop} (+constant delta), the SAME
     *       PANEL_RELATIVE shape {@code classifyInt} already recognises for a draw-call x/y arg.</li>
     *   <li>{@code guiField} — a field/call whose declaring class is a vanilla {@code Gui}-family
     *       widget ({@link Vanilla#GUI_BASE}, which covers the analysed GUI class itself as well as
     *       {@code GuiTextField}/{@code GuiButton}/...), or a plain {@code this.field} read that
     *       {@link #fieldRequirementJson} correctly leaves unresolved because there is no tile
     *       entity in the chain at all — client-owned UI state the host already has, not a tile
     *       field.</li>
     *   <li>{@code tileField} — resolves via {@link #fieldRequirementJson}, the SAME bounded
     *       receiver-chain walk the TILE-FIELD-REQUIREMENTS lane built.</li>
     *   <li>{@code unknown} — everything else (arithmetic, a poisoned/multiply-written local, a
     *       non-constant array index, an unresolvable method-call result, ...), with the same honest
     *       {@code reason} text this file has always reported for such a value — never guessed at.</li>
     * </ul>
     */
    private JsonObject operandJson(Val v, int mouseX, int mouseY) {
        JsonObject o = new JsonObject();
        if (v == null) {
            o.addProperty("kind", "unknown");
            o.addProperty("reason", "missing operand (stack underflow)");
            return o;
        }
        if (v.kind == Val.Kind.NUMBER) {
            o.addProperty("kind", "const");
            o.addProperty("value", v.numberValue.intValue());
            return o;
        }
        if (v.kind == Val.Kind.NULL) {
            o.addProperty("kind", "const");
            o.add("value", JsonNull.INSTANCE);
            return o;
        }
        if (v.kind == Val.Kind.PARAM) {
            int idx = v.numberValue.intValue();
            if (idx == mouseX) { o.addProperty("kind", "mouse"); o.addProperty("axis", "x"); return o; }
            if (idx == mouseY) { o.addProperty("kind", "mouse"); o.addProperty("axis", "y"); return o; }
            o.addProperty("kind", "unknown");
            o.addProperty("reason", "arg" + idx + " (not a recognised mouse coordinate for this method's signature)");
            return o;
        }
        if (v.kind == Val.Kind.UNKNOWN) {
            ExprEval.Decoded d = ExprEval.decode(v.reason);
            if (d != null) {
                FieldConstResolver.IntField cf = resolver.intField(d.owner, d.fieldName);
                if (cf != null && !cf.conflict) {
                    o.addProperty("kind", "const");
                    o.addProperty("value", cf.value + d.delta);
                    return o;
                }
                if (Vanilla.F_GUILEFT.equals(d.fieldName) || Vanilla.F_GUITOP.equals(d.fieldName)) {
                    o.addProperty("kind", "panelOrigin");
                    o.addProperty("axis", Vanilla.F_GUILEFT.equals(d.fieldName) ? "guiLeft" : "guiTop");
                    o.addProperty("delta", d.delta);
                    return o;
                }
            }
            if (v.owner != null && v.name != null && v.reason != null
                    && (v.reason.startsWith("getfield ") || v.reason.startsWith("result of "))) {
                return fieldOrCallOperandJson(v.owner, v.name, v.desc, v.receiver);
            }
            o.addProperty("kind", "unknown");
            o.addProperty("reason", v.reason != null ? v.reason : "unrecognised value shape");
            return o;
        }
        // A static field (a registered Item/Block/Fluid singleton constant, e.g.
        // `ModItems.explosive_lenses`) or a freshly-constructed object (`new Item(...)`) used
        // directly as a compare operand - real, evidenced shapes in this corpus (an item/fluid
        // IDENTITY check, e.g. "is this slot's stack exactly this registered item"), and honestly
        // neither a tile-entity field nor GUI-owned client state: naming the specific cause here
        // (rather than falling through to a bare, unexplained `v.toString()`) is purely additive -
        // this whole operandJson/opaqueCause path is new in this lane.
        if (v.kind == Val.Kind.STATIC_FIELD) {
            o.addProperty("kind", "unknown");
            o.addProperty("reason", "static field " + JarIndex.dotted(v.owner) + "." + v.name
                    + " (a registry/singleton constant, not tile-entity or GUI-owned state)");
            return o;
        }
        if (v.kind == Val.Kind.NEW_OBJ || v.kind == Val.Kind.NEW_UNINIT) {
            o.addProperty("kind", "unknown");
            o.addProperty("reason", "newly constructed " + JarIndex.dotted(v.typeName)
                    + " (an identity/constant comparison, not tile-entity or GUI-owned state)");
            return o;
        }
        o.addProperty("kind", "unknown");
        o.addProperty("reason", v.toString());
        return o;
    }

    /** A getfield- or (uninlined) call-shaped operand — generalises {@link #fieldRequirementJson}
     *  to a guard operand rather than only a STATE_LINEAR source/divisor, and additionally
     *  intercepts the vanilla-Gui-widget case {@code fieldRequirementJson} has no reason to know
     *  about (it was written for tile-entity data, where this ambiguity doesn't arise). */
    private JsonObject fieldOrCallOperandJson(String owner, String name, String desc, Val receiver) {
        boolean isCall = desc != null && desc.startsWith("(");
        JsonObject o = new JsonObject();
        if (isVanillaGuiWidget(owner)) {
            o.addProperty("kind", "guiField");
            o.addProperty("needsMethodCall", isCall);
            o.add("source", minimalSourceJson(owner, name, desc, "declared on a vanilla Gui-family widget class"));
            return o;
        }
        JsonObject req = fieldRequirementJson(owner, name, desc, receiver);
        if (req != null) {
            o.addProperty("kind", "tileField");
            JsonArray hops = req.getAsJsonArray("hops");
            boolean accessor = hops.size() > 0
                    && "accessor".equals(hops.get(hops.size() - 1).getAsJsonObject().get("kind").getAsString());
            o.addProperty("needsMethodCall", accessor);
            o.add("fieldRequirement", req);
            o.add("source", minimalSourceJson(owner, name, desc, null));
            return o;
        }
        if (receiver != null && receiver.kind == Val.Kind.THIS) {
            o.addProperty("kind", "guiField");
            o.addProperty("needsMethodCall", isCall);
            o.add("source", minimalSourceJson(owner, name, desc, "read directly off the GUI itself, no tile entity in the chain"));
            return o;
        }
        o.addProperty("kind", "unknown");
        o.addProperty("needsMethodCall", isCall);
        o.addProperty("reason", (isCall ? "result of " : "getfield ") + JarIndex.dotted(owner) + "." + name
                + "() does not reduce to a tile-entity field or a GUI-owned reference");
        o.add("source", minimalSourceJson(owner, name, desc, null));
        return o;
    }

    private static JsonObject minimalSourceJson(String owner, String name, String desc, String note) {
        JsonObject o = new JsonObject();
        o.addProperty("ownerClass", JarIndex.dotted(owner));
        o.addProperty("fieldName", name);
        if (desc != null) o.addProperty("fieldDesc", desc);
        if (note != null) o.addProperty("note", note);
        return o;
    }

    /** {@link Vanilla#GUI_BASE}'s own vanilla PACKAGE (not a superclass walk: a mod jar's own
     *  {@code JarIndex} has no classpath knowledge of vanilla superclass chains at all — it indexes
     *  only the mod jar's classes, so {@code isSubclassOf(GuiTextField, Gui)} could never resolve
     *  even though it's true; {@code GuiTextField}/{@code GuiButton}/{@code GuiSlider}/{@code Gui}/
     *  {@code GuiScreen}/{@code GuiContainer} all literally live in
     *  {@code net.minecraft.client.gui}, and nothing that is tile-entity or world data ever does —
     *  a generic, vanilla-package-grounded rule, not a mod-specific one). See the field javadoc on
     *  {@link Vanilla#GUI_BASE} for why this, not the receiver-chain shape, is what correctly
     *  separates a GUI-owned widget reference (e.g. a held {@code GuiTextField}) from a tile-entity
     *  reference reached the identical bytecode-shape way. */
    private static final String GUI_PACKAGE_PREFIX =
            Vanilla.GUI_BASE.substring(0, Vanilla.GUI_BASE.lastIndexOf('/') + 1); // "net/minecraft/client/gui/"

    private static boolean isVanillaGuiWidget(String owner) {
        return owner != null && owner.startsWith(GUI_PACKAGE_PREFIX);
    }

    /**
     * Walks a {@code skipCondition} tree and reports the minimal capability level a host would need
     * to evaluate it live, per the GUARD-EXPRESSIONS lane brief's three cumulative tiers:
     * {@code tileFieldsOnly} (const/panelOrigin/tileField leaves only) &lt;
     * {@code tileFieldsAndMouse} (also uses a mouse coordinate) &lt;
     * {@code tileFieldsMouseAndGuiState} (also uses GUI-owned client state) &lt;
     * {@code opaque} (at least one leaf this project cannot attribute to any of the above at all).
     * {@code needsMethodCall} is orthogonal (a {@code tileField}/{@code guiField} leaf can still
     * need one, e.g. an accessor-shaped tile field or {@code GuiTextField.isFocused()} — the
     * TILE-FIELD-REQUIREMENTS lane's own recommendation is that calling such an accessor live is
     * fine, so it does NOT by itself force the {@code opaque} tier). {@code opaqueCauses} names WHY,
     * per distinct leaf reason, for every guard that lands in the {@code opaque} tier.
     */
    private JsonObject classifyGuardNeed(JsonObject expr) {
        Need n = new Need();
        visitExpr(expr, n);
        JsonObject o = new JsonObject();
        o.addProperty("level", n.levelName());
        o.addProperty("needsMethodCall", n.methodCall);
        if (!n.opaqueCauses.isEmpty()) {
            JsonArray causes = new JsonArray();
            for (String c : n.opaqueCauses) causes.add(c);
            o.add("opaqueCauses", causes);
        }
        return o;
    }

    private static final class Need {
        int rank = 0; // 0 tileFieldsOnly, 1 +mouse, 2 +guiState, 3 opaque
        boolean methodCall = false;
        final java.util.Set<String> opaqueCauses = new java.util.LinkedHashSet<>();
        void escalate(int r) { if (r > rank) rank = r; }
        String levelName() {
            return switch (rank) {
                case 0 -> "tileFieldsOnly";
                case 1 -> "tileFieldsAndMouse";
                case 2 -> "tileFieldsMouseAndGuiState";
                default -> "opaque";
            };
        }
    }

    private void visitExpr(JsonObject expr, Need n) {
        String op = expr.get("op").getAsString();
        switch (op) {
            case "COMPARE" -> {
                visitOperand(expr.getAsJsonObject("left"), n);
                visitOperand(expr.getAsJsonObject("right"), n);
            }
            case "NOT" -> visitExpr(expr.getAsJsonObject("operand"), n);
            default -> { // AND / OR
                for (var e : expr.getAsJsonArray("operands")) visitExpr(e.getAsJsonObject(), n);
            }
        }
    }

    private void visitOperand(JsonObject operand, Need n) {
        String kind = operand.get("kind").getAsString();
        switch (kind) {
            case "const", "panelOrigin" -> { /* already fully evaluable today - no requirement */ }
            case "tileField" -> {
                if (bool(operand, "needsMethodCall")) n.methodCall = true;
            }
            case "mouse" -> n.escalate(1);
            case "guiField" -> {
                if (bool(operand, "needsMethodCall")) n.methodCall = true;
                n.escalate(2);
            }
            default -> { // "unknown"
                n.escalate(3);
                n.opaqueCauses.add(opaqueCause(operand.has("reason") ? operand.get("reason").getAsString() : null));
            }
        }
    }

    private static boolean bool(JsonObject o, String key) {
        return o.has(key) && o.get(key).getAsBoolean();
    }

    /** Buckets an {@code unknown}-operand reason string into a small, stable set of CAUSES for
     *  reporting (see GUARD-EXPRESSIONS.md's opaque-cause breakdown) — matches against the literal
     *  reason strings {@link MethodSim} itself already defines (never a mod-specific string). */
    private static String opaqueCause(String reason) {
        if (reason == null) return "unrecognisedValueShape";
        if (reason.startsWith("static field ") || reason.startsWith("newly constructed ")) return "staticRegistryConstantComparison";
        if (reason.contains("does not reduce to a tile-entity field")) return "unresolvableMethodCallOrFieldChain";
        if (reason.startsWith("result of ")) return "unresolvableMethodCall";
        if (reason.startsWith("arg")) return "unrecognisedMethodParameter";
        if (reason.equals("arithmetic")) return "arithmetic";
        if (reason.equals("compare")) return "rawCompareOperandsNotModelled"; // defensive; should not occur
        if (reason.equals("negate") || reason.equals("conversion")) return "arithmetic";
        if (reason.startsWith("array element")) return "nonConstantArrayIndexOrUnknownArray";
        if (reason.contains("written from multiple instructions")) return "poisonedLocal";
        if (reason.contains("read before any modelled write")) return "localReadBeforeWrite";
        if (reason.equals("stack underflow") || reason.contains("stack underflow")) return "stackUnderflow";
        if (reason.equals("instanceof")) return "instanceofResult";
        if (reason.startsWith("newarray") || reason.startsWith("anewarray")) return "nonConstantArrayLength";
        if (reason.equals("arraylength")) return "arrayLength";
        if (reason.equals("jsr") || reason.equals("caught exception") || reason.startsWith("multianewarray")
                || reason.equals("invokedynamic result")) return "rareBytecodeShape";
        return "other";
    }

    private static String describeVal(Val v) {
        return v.kind == Val.Kind.UNKNOWN && v.reason != null ? v.reason : v.toString();
    }

    private static String jumpOpcodeName(int op) {
        switch (op) {
            case Opcodes.IFEQ: return "IFEQ"; case Opcodes.IFNE: return "IFNE";
            case Opcodes.IFLT: return "IFLT"; case Opcodes.IFGE: return "IFGE";
            case Opcodes.IFGT: return "IFGT"; case Opcodes.IFLE: return "IFLE";
            case Opcodes.IFNULL: return "IFNULL"; case Opcodes.IFNONNULL: return "IFNONNULL";
            case Opcodes.IF_ICMPEQ: return "IF_ICMPEQ"; case Opcodes.IF_ICMPNE: return "IF_ICMPNE";
            case Opcodes.IF_ICMPLT: return "IF_ICMPLT"; case Opcodes.IF_ICMPGE: return "IF_ICMPGE";
            case Opcodes.IF_ICMPGT: return "IF_ICMPGT"; case Opcodes.IF_ICMPLE: return "IF_ICMPLE";
            case Opcodes.IF_ACMPEQ: return "IF_ACMPEQ"; case Opcodes.IF_ACMPNE: return "IF_ACMPNE";
            default: return "IF(" + op + ")";
        }
    }

    // ---------------- classification ----------------

    JsonObject classifyInt(Val v) { return classifyInt(v, 0); }

    private static final int MAX_RESOLVE_DEPTH = 4;

    private JsonObject classifyInt(Val v, int depth) {
        if (depth > MAX_RESOLVE_DEPTH) return unresolved(v, "resolution recursion limit reached");

        if (v.kind == Val.Kind.NUMBER) {
            JsonObject o = new JsonObject();
            o.addProperty("kind", "const");
            o.addProperty("classification", "CONST");
            o.addProperty("value", v.numberValue.intValue());
            return o;
        }

        // java.lang.Math.min/max(expr, const) — a very common progress-bar clamp. Vanilla JDK, not
        // mod-specific. Unwrap whichever side is resolvable and record the clamp.
        if (v.kind == Val.Kind.CALL && isMathMinMax(v) && v.ctorArgs != null && v.ctorArgs.size() == 2 && depth < MAX_RESOLVE_DEPTH) {
            JsonObject clamped = classifyMathClamp(v, depth);
            if (clamped != null) return clamped;
        }

        if (v.kind == Val.Kind.UNKNOWN) {
            // Accessor inlining (the `this.rtg.getHeatScaled(51)` idiom) already happened inside
            // MethodSim, which was run with jar access (see `scan()` below) — it re-simulates a
            // same-jar accessor's body with its actual receiver/arguments substituted, bounded by
            // MethodSim.MAX_INLINE_DEPTH. A Val that still carries a bare "result of ..." reason
            // here genuinely could not be inlined (not jar-local, abstract, or an ambiguous
            // multi-return method) — handled below by falling through to UNRESOLVED.
            ExprEval.Decoded d = ExprEval.decode(v.reason);
            if (d != null) {
                FieldConstResolver.IntField f = resolver.intField(d.owner, d.fieldName);
                if (f != null && !f.conflict) {
                    JsonObject o = new JsonObject();
                    o.addProperty("kind", "const");
                    o.addProperty("classification", "CONST");
                    o.addProperty("value", f.value + d.delta);
                    o.addProperty("viaField", d.fieldName + (d.delta != 0 ? (d.delta > 0 ? "+" + d.delta : String.valueOf(d.delta)) : ""));
                    return o;
                }
                if (Vanilla.F_GUILEFT.equals(d.fieldName) || Vanilla.F_GUITOP.equals(d.fieldName)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("kind", "position");
                    o.addProperty("classification", "PANEL_RELATIVE");
                    o.addProperty("base", Vanilla.F_GUILEFT.equals(d.fieldName) ? "guiLeft" : "guiTop");
                    o.addProperty("delta", d.delta);
                    return o;
                }
            }

            // STATE_LINEAR: an explicit compound expression (multiply/divide/offset/panel-mix), or a
            // bare single-field read with none of the above — the degenerate multiplier=1/divisor=1
            // case of the exact same "linear over one runtime field" shape.
            if (v.linear != null) return stateLinearJson(v.linear);
            if (v.owner != null && v.name != null && v.reason != null && v.reason.startsWith("getfield ")) {
                return stateLinearJson(LinearExpr.baseOf(v));
            }
        }

        return unresolved(v, null);
    }

    private JsonObject unresolved(Val v, String reasonOverride) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "dynamic");
        o.addProperty("classification", "UNRESOLVED");
        o.addProperty("desc", v.toString());
        String reason = reasonOverride != null ? reasonOverride
                : v.kind == Val.Kind.UNKNOWN && v.reason != null ? v.reason : v.toString();
        o.addProperty("reason", reason);
        return o;
    }

    private boolean isMathMinMax(Val v) {
        return "java/lang/Math".equals(v.owner) && ("min".equals(v.name) || "max".equals(v.name));
    }

    /** Tries both orderings of a {@code Math.min(a,b)}/{@code Math.max(a,b)} call: if one argument
     *  is a compile-time constant and the other resolves (recursively) to PANEL_RELATIVE or
     *  STATE_LINEAR, records the clamp and returns that classification; else null (caller falls
     *  back to the generic UNRESOLVED description, unchanged from before this feature existed). */
    private JsonObject classifyMathClamp(Val call, int depth) {
        Val a = call.ctorArgs.get(0), b = call.ctorArgs.get(1);
        Val constSide = null, otherSide = null;
        if (a.kind == Val.Kind.NUMBER) { constSide = a; otherSide = b; }
        else if (b.kind == Val.Kind.NUMBER) { constSide = b; otherSide = a; }
        if (constSide == null) return null;
        JsonObject inner = classifyInt(otherSide, depth + 1);
        String cls = inner.get("classification").getAsString();
        if (!"STATE_LINEAR".equals(cls) && !"PANEL_RELATIVE".equals(cls)) return null;
        if ("PANEL_RELATIVE".equals(cls)) return inner; // clamping a pure panel offset isn't interesting to record
        JsonObject binding = inner.getAsJsonObject("stateBinding");
        binding.addProperty("clampFunction", "java.lang.Math." + call.name);
        binding.addProperty("clampValue", constSide.numberValue.doubleValue());
        return inner;
    }

    private JsonObject stateLinearJson(LinearExpr e) {
        JsonObject o = new JsonObject();
        o.addProperty("kind", "stateLinear");
        o.addProperty("classification", "STATE_LINEAR");
        JsonObject binding = new JsonObject();
        binding.add("source", describeSource(e.fieldOwner, e.fieldName, e.fieldDesc, e.fieldReceiver));
        binding.addProperty("multiplier", e.mulNum);
        JsonObject divisor = new JsonObject();
        if (e.hasFieldDivisor()) {
            divisor.addProperty("kind", "field");
            JsonObject dsrc = describeSource(e.divOwner, e.divName, e.divDesc, e.divReceiver);
            for (var entry : dsrc.entrySet()) divisor.add(entry.getKey(), entry.getValue());
            // GUI-fidelity lane: divide-by-guarded-maximum floor (furnace arrows).
            if (e.divFloor != null) divisor.addProperty("divisorFloor", e.divFloor.doubleValue());
        } else {
            divisor.addProperty("kind", "const");
            divisor.addProperty("value", e.mulDen);
        }
        binding.add("divisor", divisor);
        binding.addProperty("offset", e.constOffset);
        binding.addProperty("sign", e.sign);
        if (e.hasPanelBase) {
            JsonObject pb = new JsonObject();
            pb.addProperty("axis", e.panelAxis);
            pb.addProperty("delta", e.panelDelta);
            binding.add("panelBase", pb);
        } else {
            binding.add("panelBase", null);
        }
        if (e.clampMax != null) {
            binding.addProperty("clampFunction", "java.lang.Math.min/max");
            binding.addProperty("clampValue", e.clampMax);
        }
        // GUI-fidelity lane: whole-term Math.ceil (assembler arrow width).
        if (e.ceilResult) binding.addProperty("ceilResult", true);
        o.add("stateBinding", binding);
        o.addProperty("desc", e.toString());
        return o;
    }

    /**
     * Classifies where a STATE_LINEAR source field lives relative to the GUI being analysed, by
     * walking the getfield receiver chain {@link MethodSim} preserved — never by name-matching any
     * class. Four buckets, from the most specific evidence to the least:
     * <ul>
     *   <li>{@code gui} — {@code this.field} directly (zero hops).</li>
     *   <li>{@code container} — the field's declaring class IS the paired Container (or a
     *       superclass of it) — the {@code this.container.field} shape, one hop through the
     *       vanilla {@code inventorySlots} reference (a checkcast erases the hop count but not the
     *       declaring-class evidence).</li>
     *   <li>{@code tileEntityViaContainer} — the field's immediate receiver was itself read off the
     *       Container (one hop further out) — {@code this.container.someRef.field}.</li>
     *   <li>{@code tileEntityViaGui} — the field's immediate receiver was itself a direct
     *       {@code this.someRef} read (not the Container) — {@code this.someRef.field}, e.g. a GUI
     *       that keeps its own pointer to the tile entity it displays.</li>
     * </ul>
     * Anything else (a static field, a method parameter, a further call result, ...) is reported as
     * {@code other} with the honest chain description rather than guessed at.
     */
    private JsonObject describeSource(String owner, String name, Val receiver) {
        return describeSource(owner, name, null, receiver);
    }

    /**
     * Like {@link #describeSource(String, String, Val)}, plus (TILE-FIELD-REQUIREMENTS lane,
     * schemaVersion 5) the leaf field's own JVM descriptor (when known) and a {@code
     * fieldRequirement} object naming the concrete tile-entity class and field-access chain a host
     * would need to snapshot to evaluate this field live — see {@link #fieldRequirementJson}.
     * <b>Purely additive:</b> the pre-existing {@code origin}/{@code originPath} computation above
     * is untouched, so every schemaVersion-4 {@code stateLinearOrigin*} coverage count stays
     * byte-identical; {@code fieldRequirement} is a second, independent resolution attempt over the
     * SAME already-tracked receiver chain, never consulted by the origin/coverage logic.
     */
    private JsonObject describeSource(String owner, String name, String desc, Val receiver) {
        JsonObject o = new JsonObject();
        o.addProperty("ownerClass", JarIndex.dotted(owner));
        o.addProperty("fieldName", name);

        String origin; String path;
        if (receiver != null && receiver.kind == Val.Kind.THIS) {
            origin = "gui";
            path = "this." + name;
        } else if (isContainerOwned(owner)) {
            origin = "container";
            path = "this.<container>." + name;
        } else if (receiver != null && receiver.kind == Val.Kind.UNKNOWN && receiver.owner != null && isContainerOwned(receiver.owner)) {
            origin = "tileEntityViaContainer";
            path = "this.<container>." + receiver.name + "." + name;
        } else if (receiver != null && receiver.kind == Val.Kind.UNKNOWN && receiver.owner != null
                && receiver.reason != null && receiver.reason.startsWith("getfield ") && receiver.receiver != null
                && receiver.receiver.kind == Val.Kind.THIS) {
            origin = "tileEntityViaGui";
            path = "this." + receiver.name + "." + name;
        } else {
            origin = "other";
            path = (receiver == null ? "?" : describeVal(receiver)) + "." + name;
        }
        o.addProperty("origin", origin);
        o.addProperty("originPath", path);
        if (desc != null) {
            o.addProperty("fieldDesc", desc);
            JsonObject req = fieldRequirementJson(owner, name, desc, receiver);
            if (req != null) o.add("fieldRequirement", req);
        }
        return o;
    }

    /** Bounded beyond the immediate tile-entity anchor — see {@link #fieldRequirementJson}: one
     *  additional {@code getfield} hop past the anchor (e.g. {@code this.machine.feed.fluid}, the
     *  fluid-tank-object idiom) is modelled; a second is not — never an unbounded whole-program walk. */
    private static final int MAX_EXTRA_FIELD_HOPS = 1;

    /**
     * TILE-FIELD-REQUIREMENTS lane: names exactly which tile-entity field(s) a host-side snapshot
     * would need to expose to evaluate this STATE_LINEAR field (or guard field) live, by walking the
     * SAME {@code Val#receiver} chain {@link MethodSim} already built for {@code describeSource}'s
     * origin classification above — but independently, and never feeding back into it.
     *
     * <p>Resolves in exactly two shapes, both real in the corpus:
     * <ul>
     *   <li><b>0 extra hops</b> — the field lives directly on the tile entity reached via the GUI's
     *   own reference ({@code this.rtg.heat}) or the paired Container's ({@code
     *   this.<container>.field}/{@code this.<container>.te.field}) — the existing {@code gui}/
     *   {@code container}/{@code tileEntityViaContainer}/{@code tileEntityViaGui} shapes.</li>
     *   <li><b>1 extra hop</b> (bounded by {@link #MAX_EXTRA_FIELD_HOPS}) — an object-typed field ON
     *   the tile entity is read first, then the actual value off THAT object ({@code
     *   this.machine.feed.fluid}, a {@code FluidTank}'s level; {@code
     *   this.machine.leftStack.amount}, a material-stack amount; {@code
     *   this.machine.priority.ordinal}) — the "tank levels are frequently a fluid-tank object whose
     *   amount and capacity are separate reads" shape named explicitly in this lane's brief.</li>
     * </ul>
     * Returns {@code null} (never guesses) when: the field lives directly on the GUI/Container
     * itself (no tile entity involved at all — most of the real corpus's {@code gui}-origin bucket,
     * e.g. vanilla {@code guiLeft}/{@code width}/{@code height}, is exactly this — not a tile-entity
     * field, so correctly has no snapshot requirement), the chain needs MORE than one extra hop, or
     * it leaves the modelled shape entirely (an array element with a non-constant index, a further
     * method-call result, a static field, a poisoned/multiply-written local, ...).
     */
    private JsonObject fieldRequirementJson(String owner, String name, String desc, Val receiver) {
        if (isContainerOwned(owner)) {
            // 0-hop: the field lives directly on the paired Container itself (real HBM corpus: 0
            // occurrences — HBM never syncs/reads a gauge straight off its Container — but modelled
            // for symmetry with describeSource's own "container" origin bucket).
            JsonObject o = new JsonObject();
            o.addProperty("reachedVia", "container");
            o.addProperty("tileEntityClass", (String) null);
            JsonArray hopsArr = new JsonArray();
            hopsArr.add(hopJson(owner, name, desc));
            o.add("hops", hopsArr);
            return o;
        }
        if (receiver == null) {
            // GUI-fidelity lane: a static field with no receiver (turbine `maxPower`, read via
            // GETSTATIC inside an inlined getPowerScaled). Rooted at its declaring class: the
            // host snapshots it off the live tile, and reflection finds statics anywhere in the
            // tile's hierarchy - a static outside that hierarchy fails closed to absent at
            // runtime, never a wrong value. GUI/container-owned statics are NOT tile state.
            if (owner != null && (owner.equals(guiClassInternal) || isContainerOwned(owner))) return null;
            if (owner == null || name == null) return null;
            JsonObject o = new JsonObject();
            o.addProperty("reachedVia", "gui");
            o.addProperty("tileEntityClass", JarIndex.dotted(owner));
            JsonArray hopsArr = new JsonArray();
            hopsArr.add(hopJson(owner, name, desc));
            o.add("hops", hopsArr);
            return o;
        }
        if (receiver.kind == Val.Kind.THIS) return null; // on the GUI itself - no TE involved

        List<JsonObject> hops = new ArrayList<>();
        hops.add(hopJson(owner, name, desc));
        Val cur = receiver;
        int extraHops = 0;
        while (true) {
            if (cur.kind != Val.Kind.UNKNOWN || cur.owner == null || cur.name == null
                    || cur.reason == null || !cur.reason.startsWith("getfield ")) {
                return null; // chain leaves the modelled shape (array index, call result, static, a
                             // poisoned local, ...) - report nothing rather than guess
            }
            boolean anchorViaGui = cur.receiver != null && cur.receiver.kind == Val.Kind.THIS;
            boolean anchorViaContainer = isContainerOwned(cur.owner);
            if (anchorViaGui || anchorViaContainer) {
                String teClass = fieldTypeClass(cur.desc);
                if (teClass == null) return null; // anchor field's own type isn't a plain object ref
                JsonObject o = new JsonObject();
                o.addProperty("reachedVia", anchorViaContainer ? "container" : "gui");
                o.addProperty("tileEntityClass", teClass);
                JsonArray hopsArr = new JsonArray();
                for (int i = hops.size() - 1; i >= 0; i--) hopsArr.add(hops.get(i));
                o.add("hops", hopsArr);
                return o;
            }
            if (extraHops >= MAX_EXTRA_FIELD_HOPS) return null; // bounded - stop, never guess further
            hops.add(hopJson(cur.owner, cur.name, cur.desc));
            extraHops++;
            cur = cur.receiver;
            if (cur == null) return null;
        }
    }

    private static JsonObject hopJson(String owner, String name, String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("ownerClass", JarIndex.dotted(owner));
        o.addProperty("fieldName", name);
        o.addProperty("desc", desc);
        // A JVM method descriptor always starts with '(' and a field descriptor never does - so this
        // needs no extra plumbing from the Val that produced it. Every INTERMEDIATE hop this walk
        // discovers is already guaranteed getfield-shaped (see the loop's own reason-prefix check
        // below), but the LEAF hop (added before the loop runs) can be a same-jar-but-uninlinable
        // zero-arg accessor call whose result MethodSim/isStateShaped still treats as "one runtime
        // value" (a real, common shape: TileEntityBatteryREDD.priority.ordinal() is a genuine
        // java.lang.Enum#ordinal() call, not a field) - honestly labelled "accessor", never silently
        // presented as a plain field read, since a host snapshot needs to INVOKE it, not just copy a
        // memory slot.
        o.addProperty("kind", desc != null && desc.startsWith("(") ? "accessor" : "field");
        return o;
    }

    /** Decodes an object-type JVM field descriptor ({@code "Lfoo/Bar;"}) to a dotted class name;
     *  null for a primitive/array descriptor (a tile-entity anchor is always an object reference). */
    private static String fieldTypeClass(String desc) {
        if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L' || desc.charAt(desc.length() - 1) != ';') return null;
        return JarIndex.dotted(desc.substring(1, desc.length() - 1));
    }

    private boolean isContainerOwned(String owner) {
        if (containerClassInternal != null && (owner.equals(containerClassInternal) || jar.isSubclassOf(containerClassInternal, owner))) return true;
        return jar.isSubclassOf(owner, Vanilla.CONTAINER) || Vanilla.CONTAINER.equals(owner);
    }

    JsonObject classifyTexture(Val v) {
        return classifyTexture(v, false);
    }

    /**
     * NOTEXTURE-GAP lane: {@code alreadyHopped} bounds the one extra accessor-call hop this method
     * may take (see the {@code result of } branch below) to exactly one — matching every other
     * bounded, named-constant walk in this codebase, never a whole-program search.
     */
    private JsonObject classifyTexture(Val v, boolean alreadyHopped) {
        JsonObject o = new JsonObject();
        FieldConstResolver.TexField t = null;
        String fieldDesc = null;
        if (v.kind == Val.Kind.NEW_OBJ && Vanilla.RESOURCE_LOCATION.equals(v.typeName)) {
            t = FieldConstResolver.resolveResourceLocationCtor(v);
            fieldDesc = "inline `new ResourceLocation(...)`";
        } else if (v.kind == Val.Kind.STATIC_FIELD) {
            t = resolver.texField(v.owner, v.name);
            fieldDesc = JarIndex.dotted(v.owner) + "." + v.name + " (static)";
        } else if (v.kind == Val.Kind.UNKNOWN && v.reason != null) {
            ExprEval.Decoded d = ExprEval.decode(v.reason);
            if (d != null) {
                t = resolver.texField(d.owner, d.fieldName);
                fieldDesc = JarIndex.dotted(d.owner) + "." + d.fieldName + " (instance)";
            } else if (!alreadyHopped && v.reason.startsWith("result of ")) {
                // NOTEXTURE-GAP lane: a bindTexture argument that is itself the unresolved result of
                // a same-jar accessor call (e.g. HBM's turret family: `this.bindTexture(this.getTexture())`
                // where every concrete turret GUI overrides `getTexture()` to return ITS OWN static,
                // constructor-time-constant ResourceLocation field - see
                // research/out/legacy/guimap-notes/NOTEXTURE-GAP.md for the full javap evidence).
                // This ONE bounded hop is texture-classification-only (see
                // MethodSim#resolveAccessorForTextureClassification's own javadoc for exactly why it
                // is never applied to the automatic scan every other consumer - guards, labels,
                // int-arg classification - relies on): re-simulate the callee's body and, if it
                // resolves to exactly one value, classify THAT instead, one level of recursion only.
                Val inlined = MethodSim.resolveAccessorForTextureClassification(jar, guiClassInternal, v);
                if (inlined != null) return classifyTexture(inlined, true);
            }
        }
        if (t != null && t.resolved != null && !t.conflict) {
            o.addProperty("path", t.resolved);
            o.addProperty("source", fieldDesc);
            o.addProperty("resolved", true);
            String assetPath = toAssetPath(t.resolved);
            o.addProperty("assetPath", assetPath);
            boolean exists = assetPath != null && jar.assets.containsKey(assetPath);
            o.addProperty("existsInJar", exists);
            if (exists) {
                PngUtil.Dim dim = PngUtil.dimensions(jar.assets.get(assetPath));
                if (dim != null) { o.addProperty("sheetWidth", dim.w); o.addProperty("sheetHeight", dim.h); }
            }
        } else {
            o.addProperty("resolved", false);
            String reason = t != null && t.conflict ? "field written with more than one distinct texture constant"
                    : t != null && t.reason != null ? t.reason
                    : "bindTexture argument is " + v + (fieldDesc != null ? " (" + fieldDesc + ")" : "");
            o.addProperty("reason", reason);
        }
        return o;
    }

    JsonObject classifyLabel(Val text, Val x, Val y) {
        JsonObject o = new JsonObject();
        String literal = null; boolean translated = false; String key = null;
        if (text.kind == Val.Kind.STRING) {
            literal = text.stringValue;
        } else if (text.kind == Val.Kind.CALL && Vanilla.isTranslateCall(text.owner, text.name)
                && text.ctorArgs != null && !text.ctorArgs.isEmpty() && text.ctorArgs.get(0).kind == Val.Kind.STRING) {
            key = text.ctorArgs.get(0).stringValue;
            translated = true;
        }
        if (literal != null || key != null) {
            o.addProperty("dynamic", false);
            o.addProperty("translated", translated);
            o.addProperty("text", translated ? key : literal);
        } else {
            o.addProperty("dynamic", true);
            o.addProperty("desc", text.toString());
        }
        o.add("x", classifyInt(x, 0));
        o.add("y", classifyInt(y, 0));
        return o;
    }

    private static String toAssetPath(String domainColonPath) {
        int c = domainColonPath.indexOf(':');
        if (c < 0) return null;
        return "assets/" + domainColonPath.substring(0, c) + "/" + domainColonPath.substring(c + 1);
    }
}
