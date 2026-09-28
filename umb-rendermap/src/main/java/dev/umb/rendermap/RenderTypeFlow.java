package dev.umb.rendermap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-instruction {@code IItemRenderer.ItemRenderType} attribution for ONE method body: which
 * render types can this instruction execute under?
 *
 * <p>The gap this closes (GUI-transforms lane, LIVE-GAP-ANALYSIS follow-up): a 1.7.10
 * {@code IItemRenderer} does its per-perspective GL work inside {@code renderItem(ItemRenderType,
 * ItemStack, Object...)} behind a dispatch on the {@code type} parameter, so
 * {@link RendererTransformExtractor}'s per-method records all said {@code method: "renderItem"}
 * and {@code PathClass} (umb-objbridge) could only classify them as OTHER — 19 of the 21 HBM
 * renderer classes with {@code renderItem} GL ops had NO other perspective-named helper at all,
 * covering 202 render-map rows (e.g. {@code ItemRenderMissilePart} alone serves 117). This class
 * recovers the branch attribution so those ops can land in the right INVENTORY/EQUIPPED/... bucket.
 *
 * <p><b>The two dispatch idioms, both verified against the real HBM jar</b> (javap on
 * {@code HBM-NTM-1.0.27_X5771.jar}; the survey over all 21 renderItem-bearing classes found 15
 * switch, 2 if_acmp, 4 with no dispatch at all, 0 anything else):
 * <ul>
 *   <li><b>javac enum switch:</b> {@code GETSTATIC Companion.$SwitchMap$...$ItemRenderType:[I;
 *       ALOAD type; INVOKEVIRTUAL ordinal()I; IALOAD; TABLESWITCH/LOOKUPSWITCH}. The case keys are
 *       REMAPPED indices whose meaning lives in the companion class's {@code <clinit>}
 *       ({@code $SwitchMap[CONST.ordinal()] = k} per constant, each wrapped in a
 *       NoSuchFieldError try/catch) — {@link #decodeSwitchMap} parses exactly that shape, so this
 *       class never needs to assume which remapped index means which constant.</li>
 *   <li><b>guard compare:</b> {@code ALOAD type; GETSTATIC ItemRenderType.X; IF_ACMPEQ/IF_ACMPNE}
 *       (either operand order) — the taken/fall-through edges refine the type set to {X} / all-but-X.</li>
 *   <li>A switch directly on {@code type.ordinal()} (no $SwitchMap — e.g. other compilers) is also
 *       recognized; there the raw case keys ARE ordinals, mapped through {@link #TYPES}, whose
 *       order is bytecode-verified (javap on forge-1.7.10-10.13.4.1614-universal.jar). Zero
 *       occurrences in the HBM corpus, kept for universality across differently-compiled mods.</li>
 * </ul>
 *
 * <p><b>Soundness stance:</b> refinement only ever NARROWS an edge the analysis positively
 * recognized; every unrecognized shape (a copied parameter, an exotic dispatch, an undecodable
 * companion) propagates UNREFINED, so the worst failure mode is an op keeping today's
 * "no attribution" status — never a wrong attribution. A method that reassigns the type parameter
 * ({@code ASTORE} to its slot) or contains JSR/RET aborts attribution entirely for that method,
 * with the reason counted by the caller ({@link Result#skipped}). Real fall-through between switch
 * cases (e.g. {@code ItemRenderMissilePart}: EQUIPPED cases fall into the ENTITY case's tail) is
 * handled naturally by the fixpoint: the shared tail's set is the UNION of every case that reaches
 * it. Code reachable under ALL types (before the dispatch, after the merge) stays unattributed —
 * only a PROPER, non-empty subset is meaningful to a consumer.
 */
public final class RenderTypeFlow {

    public static final String ENUM_INTERNAL = "net/minecraftforge/client/IItemRenderer$ItemRenderType";
    public static final String ENUM_DESC = "L" + ENUM_INTERNAL + ";";

    /**
     * Constant order bytecode-verified: {@code javap -p} on
     * {@code research/visual/mc1710-native/.../forge-1.7.10-10.13.4.1614-1.7.10-universal.jar}
     * lists exactly this order. Only the direct-ordinal switch idiom depends on it; the $SwitchMap
     * idiom decodes constants BY NAME from the companion's {@code <clinit>}.
     */
    public static final String[] TYPES = {
            "ENTITY", "EQUIPPED", "EQUIPPED_FIRST_PERSON", "INVENTORY", "FIRST_PERSON_MAP"
    };
    public static final int ALL_TYPES_MASK = (1 << TYPES.length) - 1;

    /** Named bound (project rule: bound every interprocedural/iterative step). The per-instruction
     *  lattice is a 5-bit union that only ever grows, so the fixpoint mathematically converges in
     *  at most 5 sweeps; this is a defensive ceiling on worklist pops, not a tuning knob. */
    static final int MAX_DATAFLOW_STEPS_PER_INSN = 64;

    private RenderTypeFlow() {
    }

    /** Outcome of analyzing one method: either a per-instruction mask map, or a counted skip. */
    public static final class Result {
        /** insn -&gt; 5-bit mask over {@link #TYPES}; empty when {@link #skipped} is non-null. */
        public final Map<AbstractInsnNode, Integer> masks;
        /** Non-null reason when no attribution ran: "no-type-param", "param-reassigned", "jsr". */
        public final String skipped;
        /** True when at least one edge was actually refined — i.e. a dispatch was recognized. */
        public final boolean dispatchFound;
        /** True when a $SwitchMap-shaped switch was seen whose companion could not be decoded —
         *  its edges propagated unrefined (honest no-attribution, counted by the caller). */
        public final boolean undecodableSwitch;

        Result(Map<AbstractInsnNode, Integer> masks, String skipped, boolean dispatchFound,
               boolean undecodableSwitch) {
            this.masks = masks;
            this.skipped = skipped;
            this.dispatchFound = dispatchFound;
            this.undecodableSwitch = undecodableSwitch;
        }

        static Result skip(String reason) {
            return new Result(Map.of(), reason, false, false);
        }
    }

    /** Render-type names for a mask, in {@link #TYPES} order (deterministic output). */
    public static List<String> names(int mask) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < TYPES.length; i++) {
            if ((mask & (1 << i)) != 0) out.add(TYPES[i]);
        }
        return out;
    }

    /**
     * Analyze one method. {@code jar} is only needed to decode a $SwitchMap companion class and
     * may be null (switch dispatch then propagates unrefined; if_acmp guards still work).
     */
    public static Result analyze(ClassNode cn, MethodNode mn, JarIndex jar) {
        if (mn.instructions == null || mn.instructions.size() == 0) return Result.skip("no-type-param");
        int slot = typeParamSlot(mn);
        if (slot < 0) return Result.skip("no-type-param");

        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ASTORE && v.var == slot) {
                return Result.skip("param-reassigned");
            }
            int op = insn.getOpcode();
            if (op == Opcodes.JSR || op == Opcodes.RET) return Result.skip("jsr");
        }

        AbstractInsnNode[] insns = mn.instructions.toArray();
        int n = insns.length;
        Map<AbstractInsnNode, Integer> indexOf = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) indexOf.put(insns[i], i);

        // ---- pre-recognize every refinable dispatch site --------------------------------------
        // if_acmp sites: insn index -> the compared constant's bit
        Map<Integer, Integer> acmpBit = new HashMap<>();
        // switch sites: insn index -> (case key -> bit); null value = recognized shape but
        // undecodable companion (edges stay unrefined, counted by the caller via dispatchFound)
        Map<Integer, Map<Integer, Integer>> switchKeyBits = new HashMap<>();
        boolean sawUndecodableSwitch = false;

        for (int i = 0; i < n; i++) {
            AbstractInsnNode insn = insns[i];
            int op = insn.getOpcode();
            if (op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) {
                int bit = acmpOperandBit(insn, slot);
                if (bit != 0) acmpBit.put(i, bit);
            } else if (op == Opcodes.TABLESWITCH || op == Opcodes.LOOKUPSWITCH) {
                SwitchShape shape = recognizeSwitchOperand(insn, slot);
                if (shape == SwitchShape.DIRECT_ORDINAL) {
                    Map<Integer, Integer> byKey = new HashMap<>();
                    for (int k = 0; k < TYPES.length; k++) byKey.put(k, 1 << k);
                    switchKeyBits.put(i, byKey);
                } else if (shape == SwitchShape.SWITCH_MAP) {
                    FieldInsnNode f = switchMapField(insn);
                    Map<Integer, Integer> byKey = f == null ? null : decodeSwitchMap(jar, f.owner, f.name);
                    if (byKey != null) {
                        switchKeyBits.put(i, byKey);
                    } else {
                        sawUndecodableSwitch = true;
                    }
                }
            }
        }
        boolean dispatchFound = !acmpBit.isEmpty() || !switchKeyBits.isEmpty();

        // ---- fixpoint propagation ---------------------------------------------------------------
        int[] in = new int[n];
        in[0] = ALL_TYPES_MASK;
        int[] steps = new int[n];
        java.util.ArrayDeque<Integer> work = new java.util.ArrayDeque<>();
        work.add(0);
        while (!work.isEmpty()) {
            int i = work.poll();
            if (steps[i]++ > MAX_DATAFLOW_STEPS_PER_INSN) continue; // defensive ceiling only
            int m = in[i];
            AbstractInsnNode insn = insns[i];
            int op = insn.getOpcode();

            if (insn instanceof JumpInsnNode j) {
                int target = indexOf.get(j.label);
                if (op == Opcodes.GOTO) {
                    propagate(in, work, target, m);
                } else if ((op == Opcodes.IF_ACMPEQ || op == Opcodes.IF_ACMPNE) && acmpBit.containsKey(i)) {
                    int bit = acmpBit.get(i);
                    int takenMask = op == Opcodes.IF_ACMPEQ ? bit : (ALL_TYPES_MASK & ~bit);
                    int fallMask = op == Opcodes.IF_ACMPEQ ? (ALL_TYPES_MASK & ~bit) : bit;
                    propagate(in, work, target, m & takenMask);
                    if (i + 1 < n) propagate(in, work, i + 1, m & fallMask);
                } else {
                    propagate(in, work, target, m);
                    if (i + 1 < n) propagate(in, work, i + 1, m);
                }
            } else if (insn instanceof TableSwitchInsnNode ts) {
                Map<Integer, Integer> byKey = switchKeyBits.get(i);
                int caseUnion = 0;
                for (int k = 0; k < ts.labels.size(); k++) {
                    int key = ts.min + k;
                    int refine = byKey == null ? ALL_TYPES_MASK : byKey.getOrDefault(key, 0);
                    caseUnion |= (byKey == null ? 0 : refine);
                    propagate(in, work, indexOf.get(ts.labels.get(k)), m & refine);
                }
                int dfltRefine = byKey == null ? ALL_TYPES_MASK : (ALL_TYPES_MASK & ~caseUnion);
                propagate(in, work, indexOf.get(ts.dflt), m & dfltRefine);
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                Map<Integer, Integer> byKey = switchKeyBits.get(i);
                int caseUnion = 0;
                for (int k = 0; k < ls.labels.size(); k++) {
                    int key = ls.keys.get(k);
                    int refine = byKey == null ? ALL_TYPES_MASK : byKey.getOrDefault(key, 0);
                    caseUnion |= (byKey == null ? 0 : refine);
                    propagate(in, work, indexOf.get(ls.labels.get(k)), m & refine);
                }
                int dfltRefine = byKey == null ? ALL_TYPES_MASK : (ALL_TYPES_MASK & ~caseUnion);
                propagate(in, work, indexOf.get(ls.dflt), m & dfltRefine);
            } else if (op >= Opcodes.IRETURN && op <= Opcodes.RETURN || op == Opcodes.ATHROW) {
                // no successors
            } else {
                if (i + 1 < n) propagate(in, work, i + 1, m);
            }

            // exception edges: any insn inside a protected range can transfer to its handler,
            // carrying its unrefined set (a throw does not tell us anything about the type param)
            if (mn.tryCatchBlocks != null) {
                for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                    int s = indexOf.get(tcb.start), e = indexOf.get(tcb.end);
                    if (i >= s && i < e) propagate(in, work, indexOf.get(tcb.handler), m);
                }
            }
        }

        Map<AbstractInsnNode, Integer> masks = new IdentityHashMap<>();
        for (int i = 0; i < n; i++) masks.put(insns[i], in[i]);
        return new Result(masks, null, dispatchFound, sawUndecodableSwitch);
    }

    private static void propagate(int[] in, java.util.ArrayDeque<Integer> work, int target, int mask) {
        if (mask == 0) return; // an impossible edge contributes nothing (and 0 marks "unreached")
        if ((in[target] | mask) != in[target]) {
            in[target] |= mask;
            work.add(target);
        }
    }

    /** Local-variable slot of the first {@code ItemRenderType} parameter, or -1 when absent. */
    static int typeParamSlot(MethodNode mn) {
        Type[] args = Type.getArgumentTypes(mn.desc);
        int slot = (mn.access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type a : args) {
            if (ENUM_DESC.equals(a.getDescriptor())) return slot;
            slot += a.getSize();
        }
        return -1;
    }

    /** The compared {@code ItemRenderType} constant's bit for an IF_ACMPEQ/IF_ACMPNE whose two
     *  operands are exactly (ALOAD typeSlot, GETSTATIC ItemRenderType.X) in either order; 0 when
     *  the shape is anything else. */
    static int acmpOperandBit(AbstractInsnNode acmp, int slot) {
        AbstractInsnNode p1 = prevReal(acmp);
        AbstractInsnNode p2 = p1 == null ? null : prevReal(p1);
        if (p1 == null || p2 == null) return 0;
        FieldInsnNode enumConst = null;
        VarInsnNode load = null;
        if (isEnumConst(p1) && isAload(p2, slot)) {
            enumConst = (FieldInsnNode) p1;
            load = (VarInsnNode) p2;
        } else if (isAload(p1, slot) && isEnumConst(p2)) {
            enumConst = (FieldInsnNode) p2;
            load = (VarInsnNode) p1;
        }
        if (enumConst == null || load == null) return 0;
        return bitOf(enumConst.name);
    }

    enum SwitchShape { NONE, SWITCH_MAP, DIRECT_ORDINAL }

    /** Recognize the operand chain feeding a switch: $SwitchMap[type.ordinal()] or type.ordinal(). */
    static SwitchShape recognizeSwitchOperand(AbstractInsnNode sw, int slot) {
        AbstractInsnNode p1 = prevReal(sw);
        if (p1 == null) return SwitchShape.NONE;
        if (p1.getOpcode() == Opcodes.IALOAD) {
            AbstractInsnNode p2 = prevReal(p1);
            AbstractInsnNode p3 = p2 == null ? null : prevReal(p2);
            AbstractInsnNode p4 = p3 == null ? null : prevReal(p3);
            if (isOrdinalCall(p2) && isAload(p3, slot) && isSwitchMapField(p4)) return SwitchShape.SWITCH_MAP;
            return SwitchShape.NONE;
        }
        if (isOrdinalCall(p1)) {
            AbstractInsnNode p2 = prevReal(p1);
            if (isAload(p2, slot)) return SwitchShape.DIRECT_ORDINAL;
        }
        return SwitchShape.NONE;
    }

    /** The GETSTATIC $SwitchMap field feeding a recognized SWITCH_MAP-shaped switch. */
    static FieldInsnNode switchMapField(AbstractInsnNode sw) {
        AbstractInsnNode p1 = prevReal(sw);                    // IALOAD
        AbstractInsnNode p2 = p1 == null ? null : prevReal(p1); // ordinal()
        AbstractInsnNode p3 = p2 == null ? null : prevReal(p2); // ALOAD
        AbstractInsnNode p4 = p3 == null ? null : prevReal(p3); // GETSTATIC [I
        return isSwitchMapField(p4) ? (FieldInsnNode) p4 : null;
    }

    /**
     * Decode a javac $SwitchMap companion array: its owner's {@code <clinit>} stores
     * {@code $SwitchMap[ItemRenderType.CONST.ordinal()] = k} per mapped constant (each wrapped in
     * a NoSuchFieldError guard). Recognized per IASTORE by walking back over the exact 4-insn
     * shape; anything else in the clinit is ignored. Returns case-key -&gt; bit, or null when the
     * companion class is unavailable or yields no mappings (caller then leaves edges unrefined).
     */
    static Map<Integer, Integer> decodeSwitchMap(JarIndex jar, String owner, String fieldName) {
        ClassNode comp = jar == null ? null : jar.cls(owner);
        if (comp == null) return null;
        MethodNode clinit = null;
        for (MethodNode m : comp.methods) {
            if ("<clinit>".equals(m.name)) { clinit = m; break; }
        }
        if (clinit == null || clinit.instructions == null) return null;
        Map<Integer, Integer> byKey = new HashMap<>();
        for (AbstractInsnNode insn = clinit.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() != Opcodes.IASTORE) continue;
            AbstractInsnNode pKey = prevReal(insn);                       // push k
            AbstractInsnNode pOrd = pKey == null ? null : prevReal(pKey); // ordinal()
            AbstractInsnNode pCon = pOrd == null ? null : prevReal(pOrd); // GETSTATIC CONST
            AbstractInsnNode pArr = pCon == null ? null : prevReal(pCon); // GETSTATIC $SwitchMap
            Integer key = intConst(pKey);
            if (key == null || !isOrdinalCall(pOrd) || !isEnumConst(pCon) || !isSwitchMapField(pArr)) continue;
            FieldInsnNode arr = (FieldInsnNode) pArr;
            if (!owner.equals(arr.owner) || !fieldName.equals(arr.name)) continue;
            int bit = bitOf(((FieldInsnNode) pCon).name);
            if (bit != 0) byKey.put(key, bit);
        }
        return byKey.isEmpty() ? null : byKey;
    }

    // ---------------------------------------------------------------- small syntactic predicates

    /** Previous REAL instruction (skipping labels/line numbers/frames), or null. */
    static AbstractInsnNode prevReal(AbstractInsnNode insn) {
        for (AbstractInsnNode p = insn.getPrevious(); p != null; p = p.getPrevious()) {
            if (p.getOpcode() >= 0) return p;
        }
        return null;
    }

    private static boolean isAload(AbstractInsnNode insn, int slot) {
        return insn instanceof VarInsnNode v && v.getOpcode() == Opcodes.ALOAD && v.var == slot;
    }

    private static boolean isEnumConst(AbstractInsnNode insn) {
        return insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
                && ENUM_INTERNAL.equals(f.owner) && ENUM_DESC.equals(f.desc);
    }

    private static boolean isOrdinalCall(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode m && m.getOpcode() == Opcodes.INVOKEVIRTUAL
                && ENUM_INTERNAL.equals(m.owner) && "ordinal".equals(m.name) && "()I".equals(m.desc);
    }

    private static boolean isSwitchMapField(AbstractInsnNode insn) {
        return insn instanceof FieldInsnNode f && f.getOpcode() == Opcodes.GETSTATIC
                && "[I".equals(f.desc) && f.name.startsWith("$SwitchMap$")
                && f.name.endsWith("ItemRenderType");
    }

    private static Integer intConst(AbstractInsnNode insn) {
        if (insn == null) return null;
        int op = insn.getOpcode();
        if (op >= Opcodes.ICONST_0 && op <= Opcodes.ICONST_5) return op - Opcodes.ICONST_0;
        if (insn instanceof IntInsnNode ii && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) return ii.operand;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) return i;
        return null;
    }

    private static int bitOf(String constName) {
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i].equals(constName)) return 1 << i;
        }
        return 0;
    }
}
