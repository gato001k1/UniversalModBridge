package dev.umb.rendermap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A deliberately small forward abstract interpreter over one method body.
 *
 * <p>It models exactly the instruction shapes that appear in HBM's registration
 * and rendering code (constants, static field reads, {@code new}+{@code <init>},
 * small arrays, local shuffling) and collapses everything else into
 * {@link Val#unknown(String)} carrying a reason. Control flow is handled by
 * clearing the operand stack at every jump target (a statement boundary in
 * javac output) and by poisoning any local that is written from more than one
 * instruction, so we never fabricate a value that a real execution could not
 * have produced along the path we walked.
 *
 * <p>One {@link Val} is pushed per <em>value</em>, not per JVM slot; category-2
 * values carry {@code wide} so POP2/DUP2 stay honest.
 */
public final class MethodSim {

    /** Receives every instruction together with the operand stack as it is *before* execution. */
    public interface Handler {
        void onInsn(AbstractInsnNode insn, List<Val> stack, Map<Integer, Val> locals);
    }

    private final List<Val> stack = new ArrayList<>();
    private final Map<Integer, Val> locals = new HashMap<>();
    private final Map<Integer, AbstractInsnNode> localWriter = new HashMap<>();
    private final Set<Integer> poisoned = new HashSet<>();
    /** Same-method {@code this.field} write/read tracking (see the {@code GETFIELD}/{@code
     *  PUTFIELD} cases below) — the field-typed analog of {@link #locals}/{@link #localWriter}/
     *  {@link #poisoned}, scoped to the CURRENT method execution only (a fresh {@link MethodSim}
     *  is created per {@link #run} call, so nothing here survives past one method body). Keyed by
     *  {@code "owner.name"} exactly like {@link #thisFieldWriter}/{@link #thisFieldPoisoned}.
     *  Gated by {@link #trackThisFields} — see that field's javadoc for why this is opt-in rather
     *  than always-on. */
    private final Map<String, Val> thisFieldValue = new HashMap<>();
    private final Map<String, AbstractInsnNode> thisFieldWriter = new HashMap<>();
    private final Set<String> thisFieldPoisoned = new HashSet<>();
    /** Opt-in (see {@link #run(ClassNode, MethodNode, JarIndex, boolean, Handler)}), {@code false}
     *  for every pre-existing caller/overload. Enables the {@code this.field}-write-then-read
     *  tracking above. Deliberately NOT folded into the existing {@code jar != null} opt-in this
     *  class already has (enum-per-constant / single-assignment-instance-field {@code getfield}
     *  resolution): several existing callers ({@link BindingScanner#scanGenericCallSites},
     *  {@link BindingScanner#scanBonusHbmIndirection}, {@link RegistryScanner}) already pass a
     *  non-null {@code jar} for THOSE two resolvers, and this project's own zero-regression
     *  discipline (see CONTENT-RESOLUTION.md/-2.md) is to isolate a new capability's blast radius
     *  to exactly the code path that motivated it — here, {@link RendererAnalyzer}'s new entity
     *  getEntityTexture/Techne-geometry resolvers only. A prior version of this fix folded the new
     *  tracking into the existing {@code jar != null} branch and was caught, by this lane's own
     *  before/after diff across all five corpus mods, silently moving unrelated item/block/TESR
     *  resolution counts on HBM/mcheli/Railcraft/Chisel — a real, adversarially-caught mistake,
     *  reverted in favour of this explicit separate flag. */
    private final boolean trackThisFields;
    /** Local slots that {@code iinc} ever mutates anywhere in the method — i.e. loop induction
     *  variables. See the loop/array note on {@link #step}'s {@code ILOAD} case. */
    private final Set<Integer> loopCounters = new HashSet<>();
    private final Map<Val, Boolean> wide = new java.util.IdentityHashMap<>();
    private int uninitSeq = 0;
    /** Opt-in (see {@link #run(ClassNode, MethodNode, JarIndex, Handler)}) jar context that
     *  enables the content-array {@code getstatic} tracking below. {@code null} for every other
     *  caller, which keeps their behaviour byte-for-byte identical to before this existed. */
    private final JarIndex jar;
    /**
     * One canonical marker {@link Val} per loop-counter local slot, reused for EVERY {@code iload}
     * of that slot within this single method execution (instead of a fresh {@link Val#unknown}
     * each time). Nothing outside this class ever compared these by reference before, so sharing
     * identity changes nothing for existing callers — it only enables
     * {@link Val#symbolicWrites}'s identity-keyed lookup to recognise "the same symbolic index"
     * across an {@code aastore} and a later {@code aaload} in one straight-line pass.
     */
    private final Map<Integer, Val> loopVarMarkers = new HashMap<>();
    /** "owner.name" -> the virtual content-array {@link Val} synthesised for that field's FIRST
     *  {@code getstatic} in this method execution (see {@link #isContentArrayField}); every later
     *  {@code getstatic} of the same field in the same method reuses the identical object so
     *  writes made through one read are visible to a later read. */
    private final Map<String, Val> contentArrayFields = new HashMap<>();
    /** The current method's own formal parameter types, by logical (0-based) argument index —
     *  matches {@link Val#numberValue} on a {@link Val.Kind#PARAM}. Set once per {@link #exec}
     *  call; used only by the opt-in ({@code jar != null}) enum-field {@code getfield} expansion
     *  below. */
    private Type[] paramTypes;

    public static void run(ClassNode cn, MethodNode mn, Handler h) {
        new MethodSim(null, false).exec(cn, mn, null, h);
    }

    /**
     * Like {@link #run(ClassNode, MethodNode, Handler)}, but the given local slots start out
     * bound to concrete values instead of generic {@link Val#param(int)} placeholders. Used to
     * re-simulate a small constructor with the exact literal arguments a particular call site
     * passed it (e.g. one enum constant's name/ordinal/extra args, captured from {@code <clinit>}),
     * so a {@code new ResourceLocation("hbm:textures/.../" + arg + ".png")} body resolves to one
     * concrete path per call site instead of staying symbolic.
     */
    public static void run(ClassNode cn, MethodNode mn, Map<Integer, Val> seedLocals, Handler h) {
        new MethodSim(null, false).exec(cn, mn, seedLocals, h);
    }

    /**
     * Like {@link #run(ClassNode, MethodNode, Handler)}, but with {@code jar} available so a
     * {@code getstatic} of a static {@code Block[]}/{@code Item[]} content-holder field (any
     * class the jar declares that is a {@code Block}/{@code Item} subclass, per the SAME
     * hierarchy test {@link ModRegistryResolver#blockOrItem} already uses for scalar fields — no
     * mod-specific literal) is treated as a mutable virtual array instead of an opaque
     * {@code STATIC_FIELD} placeholder, for the lifetime of this ONE method execution only (see
     * {@link Val#contentArray}). This closes a real, previously-disclosed limit
     * (CONTENT-RESOLUTION.md: "MethodSim does not persist an array's constructed identity across
     * a PUTSTATIC/GETSTATIC round trip") for the common 1.7.10 idiom where a mod pre-allocates an
     * array field in {@code <clinit>} and then, in a LATER method, populates it element-by-element
     * with a loop and immediately re-reads the just-written element (e.g. to register it) in that
     * SAME later method — Chisel's {@code blockPlanks}/{@code blockStainedGlass}/
     * {@code blockStainedGlassPane}, confirmed by direct bytecode inspection. Bounded: this is
     * still exactly one method's own single linear pass, with zero interprocedural hops — a
     * mod-agnostic capability, not a Chisel-specific one.
     */
    public static void run(ClassNode cn, MethodNode mn, JarIndex jar, Handler h) {
        new MethodSim(jar, false).exec(cn, mn, null, h);
    }

    /**
     * Like {@link #run(ClassNode, MethodNode, JarIndex, Handler)}, plus (when {@code
     * trackThisFields} is {@code true}) same-method {@code this.field = X; ...; this.field.foo()}
     * write-then-read tracking — see {@link #trackThisFields}'s javadoc for why this is a
     * SEPARATE opt-in flag rather than folded into {@code jar != null}. Used only by {@link
     * RendererAnalyzer}'s entity-specific {@code resolveEntityTexture}/{@code
     * resolveJavaModelGeometry}.
     */
    public static void run(ClassNode cn, MethodNode mn, JarIndex jar, boolean trackThisFields, Handler h) {
        new MethodSim(jar, trackThisFields, false).exec(cn, mn, null, h);
    }

    /**
     * Moving-parts lane opt-in: like {@link #run(ClassNode, MethodNode, JarIndex, boolean,
     * Handler)} plus structured {@code GETFIELD}/arithmetic provenance (see {@link Val.Kind#FIELD}
     * and {@link Val.Kind#EXPR}) instead of collapsing to {@code UNKNOWN}. Byte-identical to
     * {@code trackProvenance=false} for every pre-existing caller: the flag defaults off and the
     * only behavioural deltas sit behind it.
     */
    public static void run(ClassNode cn, MethodNode mn, JarIndex jar, boolean trackThisFields,
                           boolean trackProvenance, Handler h) {
        new MethodSim(jar, trackThisFields, trackProvenance).exec(cn, mn, null, h);
    }

    /** When true, {@code GETFIELD} and pure numeric combinators keep operand trees (see above). */
    private final boolean trackProvenance;

    private MethodSim(JarIndex jar, boolean trackThisFields) {
        this(jar, trackThisFields, false);
    }

    private MethodSim(JarIndex jar, boolean trackThisFields, boolean trackProvenance) {
        this.jar = jar;
        this.trackThisFields = trackThisFields;
        this.trackProvenance = trackProvenance;
    }

    private void exec(ClassNode cn, MethodNode mn, Map<Integer, Val> seedLocals, Handler h) {
        if (mn.instructions == null || mn.instructions.size() == 0) return;

        Set<LabelNode> targets = new HashSet<>();
        Set<LabelNode> handlers = new HashSet<>();
        for (AbstractInsnNode in : mn.instructions.toArray()) {
            if (in instanceof JumpInsnNode) targets.add(((JumpInsnNode) in).label);
            else if (in instanceof TableSwitchInsnNode t) { targets.add(t.dflt); targets.addAll(t.labels); }
            else if (in instanceof LookupSwitchInsnNode l) { targets.add(l.dflt); targets.addAll(l.labels); }
        }
        if (mn.tryCatchBlocks != null)
            for (TryCatchBlockNode tc : mn.tryCatchBlocks) { handlers.add(tc.handler); targets.add(tc.handler); }
        for (AbstractInsnNode in : mn.instructions.toArray())
            if (in instanceof IincInsnNode ii) loopCounters.add(ii.var);

        boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
        int slot = 0;
        if (!isStatic) locals.put(slot++, Val.thisRef());
        Type[] params = Type.getArgumentTypes(mn.desc);
        paramTypes = params;
        for (int ai = 0; ai < params.length; ai++) {
            locals.put(slot, Val.param(ai));
            slot += params[ai].getSize();
        }
        if (seedLocals != null) {
            for (Map.Entry<Integer, Val> e : seedLocals.entrySet()) locals.put(e.getKey(), e.getValue());
        }

        for (AbstractInsnNode in : mn.instructions.toArray()) {
            if (in instanceof LabelNode ln) {
                if (targets.contains(ln)) {
                    stack.clear();
                    if (handlers.contains(ln)) push(Val.unknown("caught exception"), false);
                }
                continue;
            }
            if (in instanceof LineNumberNode || in instanceof FrameNode) continue;
            try {
                h.onInsn(in, stack, locals);
                step(in);
            } catch (RuntimeException e) {
                // never let one weird method abort the whole scan
                stack.clear();
            }
        }
    }

    // ---------------- stack helpers ----------------

    private void push(Val v, boolean isWide) { stack.add(v); if (isWide) wide.put(v, Boolean.TRUE); }
    private void push(Val v) { push(v, false); }
    private boolean isWide(Val v) { return wide.containsKey(v); }

    private Val pop() {
        if (stack.isEmpty()) return Val.unknown("stack underflow");
        return stack.remove(stack.size() - 1);
    }
    private Val peek(int fromTop) {
        int i = stack.size() - 1 - fromTop;
        return i < 0 ? Val.unknown("stack underflow") : stack.get(i);
    }
    private void popN(int n) { for (int i = 0; i < n; i++) pop(); }

    /**
     * Opt-in provenance only: pops two operands and pushes a structured {@link Val.Kind#EXPR}
     * node named after the JVM opcode (fadd, ddiv, imul, ...), preserving operand order and
     * wideness, instead of collapsing to {@code UNKNOWN}. Never used unless
     * {@link #trackProvenance} is on.
     */
    private void pushArith(AbstractInsnNode in, boolean wideResult) {
        Val b = pop();
        Val a = pop();
        boolean wide = wideResult || isWide(a) || isWide(b);
        java.util.List<Val> ops = new java.util.ArrayList<>(2);
        ops.add(a);
        ops.add(b);
        push(Val.expr(arithName(in.getOpcode()), ops), wide);
    }

    /** Opt-in provenance only: the unary (negate/convert) analog of {@link #pushArith}. */
    private void pushUnary(AbstractInsnNode in, boolean wideResult) {
        Val a = pop();
        java.util.List<Val> ops = new java.util.ArrayList<>(1);
        ops.add(a);
        push(Val.expr(convName(in.getOpcode()), ops), wideResult || isWide(a));
    }

    private static String arithName(int op) {
        switch (op) {
            case Opcodes.IADD: return "iadd"; case Opcodes.ISUB: return "isub";
            case Opcodes.IMUL: return "imul"; case Opcodes.IDIV: return "idiv";
            case Opcodes.IREM: return "irem"; case Opcodes.ISHL: return "ishl";
            case Opcodes.ISHR: return "ishr"; case Opcodes.IUSHR: return "iushr";
            case Opcodes.IAND: return "iand"; case Opcodes.IOR: return "ior";
            case Opcodes.IXOR: return "ixor";
            case Opcodes.FADD: return "fadd"; case Opcodes.FSUB: return "fsub";
            case Opcodes.FMUL: return "fmul"; case Opcodes.FDIV: return "fdiv";
            case Opcodes.FREM: return "frem";
            case Opcodes.LADD: return "ladd"; case Opcodes.LSUB: return "lsub";
            case Opcodes.LMUL: return "lmul"; case Opcodes.LDIV: return "ldiv";
            case Opcodes.LREM: return "lrem"; case Opcodes.LAND: return "land";
            case Opcodes.LOR: return "lor"; case Opcodes.LXOR: return "lxor";
            case Opcodes.DADD: return "dadd"; case Opcodes.DSUB: return "dsub";
            case Opcodes.DMUL: return "dmul"; case Opcodes.DDIV: return "ddiv";
            case Opcodes.DREM: return "drem";
            case Opcodes.LSHL: return "lshl"; case Opcodes.LSHR: return "lshr";
            case Opcodes.LUSHR: return "lushr";
            default: return "arith";
        }
    }

    private static String convName(int op) {
        switch (op) {
            case Opcodes.INEG: return "ineg"; case Opcodes.FNEG: return "fneg";
            case Opcodes.LNEG: return "lneg"; case Opcodes.DNEG: return "dneg";
            case Opcodes.I2L: return "i2l"; case Opcodes.I2D: return "i2d";
            case Opcodes.F2L: return "f2l"; case Opcodes.F2D: return "f2d";
            case Opcodes.L2D: return "l2d"; case Opcodes.D2L: return "d2l";
            case Opcodes.I2F: return "i2f"; case Opcodes.F2I: return "f2i";
            case Opcodes.I2B: return "i2b"; case Opcodes.I2C: return "i2c";
            case Opcodes.I2S: return "i2s";
            case Opcodes.L2I: return "l2i"; case Opcodes.L2F: return "l2f";
            case Opcodes.D2I: return "d2i"; case Opcodes.D2F: return "d2f";
            default: return "conv";
        }
    }

    private void store(int idx, Val v, AbstractInsnNode at) {
        AbstractInsnNode prev = localWriter.get(idx);
        if (prev != null && prev != at) {
            poisoned.add(idx);
            locals.put(idx, Val.unknown("local " + idx + " written from multiple instructions"));
            return;
        }
        localWriter.put(idx, at);
        if (poisoned.contains(idx)) return;
        locals.put(idx, v);
    }

    private Val load(int idx) {
        Val v = locals.get(idx);
        return v != null ? v : Val.unknown("local " + idx + " read before any modelled write");
    }

    // ---------------- the interpreter ----------------

    private void step(AbstractInsnNode in) {
        int op = in.getOpcode();
        switch (op) {
            case Opcodes.NOP: return;
            case Opcodes.ACONST_NULL: push(Val.NULL_V); return;
            case Opcodes.ICONST_M1: case Opcodes.ICONST_0: case Opcodes.ICONST_1:
            case Opcodes.ICONST_2: case Opcodes.ICONST_3: case Opcodes.ICONST_4:
            case Opcodes.ICONST_5: push(Val.number(op - Opcodes.ICONST_0)); return;
            case Opcodes.LCONST_0: push(Val.number(0L), true); return;
            case Opcodes.LCONST_1: push(Val.number(1L), true); return;
            case Opcodes.FCONST_0: push(Val.number(0f)); return;
            case Opcodes.FCONST_1: push(Val.number(1f)); return;
            case Opcodes.FCONST_2: push(Val.number(2f)); return;
            case Opcodes.DCONST_0: push(Val.number(0d), true); return;
            case Opcodes.DCONST_1: push(Val.number(1d), true); return;
            case Opcodes.BIPUSH: case Opcodes.SIPUSH: push(Val.number(((IntInsnNode) in).operand)); return;
            case Opcodes.LDC: {
                Object c = ((LdcInsnNode) in).cst;
                if (c instanceof String s) push(Val.string(s));
                else if (c instanceof Type t) push(Val.clazz(t.getInternalName()));
                else if (c instanceof Long l) push(Val.number(l), true);
                else if (c instanceof Double d) push(Val.number(d), true);
                else if (c instanceof Number n) push(Val.number(n));
                else push(Val.unknown("ldc constant " + c));
                return;
            }
            case Opcodes.ILOAD: {
                int idx = ((VarInsnNode) in).var;
                // This simulator walks the bytecode exactly once, in textual order — it never
                // actually re-executes a backward branch. A classic `for (int i = 0; i < n; i++)`
                // loop's `iinc` sits AFTER the loop body in the instruction stream, so a read of
                // `i` *inside* the body always sees the single pre-loop value (0) baked in by
                // `istore`, never the value any later iteration would have had. Silently returning
                // that stale constant would make `array[i]` inside the loop look like a normal,
                // single, correctly-resolved call — and mod-agnostically hide every iteration but
                // the first. So a load of any local that `iinc` touches ANYWHERE in this method is
                // never trusted as a constant: it is mod-agnostic loop-shape detection by
                // construction (no class/field names involved), and it is what lets
                // {@code arrayGet} fall through to {@link Val#arrayUnknownIndex}, which is what
                // lets a registration-site resolver enumerate the whole array instead of guessing
                // one slot (see BindingScanner.expand/zipExpand — mandate: "renderers registered
                // in a loop or from an array").
                if (loopCounters.contains(idx)) {
                    // One shared marker object per slot (not a fresh Val each load): nothing
                    // outside this class ever compared these by kind==UNKNOWN identity before, so
                    // this changes no existing behaviour — it only lets Val#symbolicWrites
                    // recognise "the same symbolic index" between an aastore and a later aaload
                    // in this same straight-line pass. See the content-array javadoc on
                    // #run(ClassNode, MethodNode, JarIndex, Handler).
                    push(loopVarMarkers.computeIfAbsent(idx, k -> Val.unknown("local " + k
                            + " is a loop induction variable (mutated by iinc elsewhere in this"
                            + " method; not trusted as a compile-time constant)")));
                    return;
                }
                push(load(idx));
                return;
            }
            case Opcodes.FLOAD: case Opcodes.ALOAD:
                push(load(((VarInsnNode) in).var)); return;
            case Opcodes.LLOAD: case Opcodes.DLOAD:
                push(load(((VarInsnNode) in).var), true); return;
            case Opcodes.ISTORE: case Opcodes.FSTORE: case Opcodes.ASTORE:
            case Opcodes.LSTORE: case Opcodes.DSTORE:
                store(((VarInsnNode) in).var, pop(), in); return;
            case Opcodes.IALOAD: case Opcodes.FALOAD: case Opcodes.BALOAD:
            case Opcodes.CALOAD: case Opcodes.SALOAD: {
                Val idx = pop(); Val arr = pop();
                push(arrayGet(arr, idx));
                return;
            }
            case Opcodes.AALOAD: { Val idx = pop(); Val arr = pop(); push(arrayGet(arr, idx)); return; }
            case Opcodes.LALOAD: case Opcodes.DALOAD: {
                Val idx = pop(); Val arr = pop(); push(arrayGet(arr, idx), true); return;
            }
            case Opcodes.IASTORE: case Opcodes.FASTORE: case Opcodes.AASTORE:
            case Opcodes.BASTORE: case Opcodes.CASTORE: case Opcodes.SASTORE:
            case Opcodes.LASTORE: case Opcodes.DASTORE: {
                Val v = pop(); Val idx = pop(); Val arr = pop();
                if (arr.kind == Val.Kind.ARRAY) {
                    if (idx.kind == Val.Kind.NUMBER) {
                        int i = idx.numberValue.intValue();
                        if (i >= 0 && i < arr.elements.size()) {
                            arr.elements.set(i, v);
                        } else if (i >= 0 && arr.symbolicWrites != null) {
                            // a virtual content array (see Val#contentArray) has no real
                            // compile-time length; grow it lazily so a constant-index store past
                            // its current size is not silently dropped.
                            while (arr.elements.size() <= i) arr.elements.add(Val.unknown("array slot never stored"));
                            arr.elements.set(i, v);
                        }
                    } else if (arr.symbolicWrites != null) {
                        // Non-constant index (a loop induction variable) into a virtual content
                        // array: remember it by the index Val's OWN identity (stable per slot for
                        // this whole method execution, see ILOAD above) so a later aaload with
                        // "the same" symbolic index in this same pass can recover it.
                        arr.symbolicWrites.put(idx, v);
                    }
                }
                return;
            }
            case Opcodes.POP: pop(); return;
            case Opcodes.POP2: { Val t = pop(); if (!isWide(t)) pop(); return; }
            case Opcodes.DUP: { Val t = peek(0); push(t, isWide(t)); return; }
            case Opcodes.DUP_X1: { Val a = pop(), b = pop(); push(a); push(b); push(a); return; }
            case Opcodes.DUP_X2: { Val a = pop(), b = pop(), c = pop(); push(a); push(c); push(b); push(a); return; }
            case Opcodes.DUP2: {
                Val a = peek(0);
                if (isWide(a)) { push(a, true); }
                else { Val b = peek(1); push(b); push(a); }
                return;
            }
            case Opcodes.DUP2_X1: case Opcodes.DUP2_X2: {
                // rare in this corpus; keep arity roughly sane rather than guess
                Val a = peek(0); push(a, isWide(a)); return;
            }
            case Opcodes.SWAP: { Val a = pop(), b = pop(); push(a); push(b); return; }
            case Opcodes.IADD: case Opcodes.ISUB: case Opcodes.IMUL: case Opcodes.IDIV:
            case Opcodes.IREM: case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR:
            case Opcodes.IAND: case Opcodes.IOR: case Opcodes.IXOR:
            case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FMUL: case Opcodes.FDIV: case Opcodes.FREM:
                if (trackProvenance) { pushArith(in, false); return; }
                popN(2); push(Val.unknown("arithmetic")); return;
            case Opcodes.LADD: case Opcodes.LSUB: case Opcodes.LMUL: case Opcodes.LDIV:
            case Opcodes.LREM: case Opcodes.LAND: case Opcodes.LOR: case Opcodes.LXOR:
            case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DMUL: case Opcodes.DDIV: case Opcodes.DREM:
                if (trackProvenance) { pushArith(in, true); return; }
                popN(2); push(Val.unknown("arithmetic"), true); return;
            case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR:
                if (trackProvenance) { pushArith(in, true); return; }
                popN(2); push(Val.unknown("arithmetic"), true); return;
            case Opcodes.INEG: case Opcodes.FNEG:
                if (trackProvenance) { pushUnary(in, false); return; }
                popN(1); push(Val.unknown("negate")); return;
            case Opcodes.LNEG: case Opcodes.DNEG:
                if (trackProvenance) { pushUnary(in, true); return; }
                popN(1); push(Val.unknown("negate"), true); return;
            case Opcodes.IINC: return;
            case Opcodes.I2L: case Opcodes.I2D: case Opcodes.F2L: case Opcodes.F2D:
                if (trackProvenance) { pushUnary(in, true); return; }
                popN(1); push(Val.unknown("conversion"), true); return;
            case Opcodes.L2D: case Opcodes.D2L:
                if (trackProvenance) { pushUnary(in, true); return; }
                popN(1); push(Val.unknown("conversion"), true); return;
            case Opcodes.I2F: case Opcodes.F2I: case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
                if (trackProvenance) { pushUnary(in, false); return; }
                popN(1); push(Val.unknown("conversion")); return;
            case Opcodes.L2I: case Opcodes.L2F: case Opcodes.D2I: case Opcodes.D2F:
                if (trackProvenance) { pushUnary(in, false); return; }
                popN(1); push(Val.unknown("conversion")); return;
            case Opcodes.LCMP: case Opcodes.DCMPL: case Opcodes.DCMPG:
                popN(2); push(Val.unknown("compare")); return;
            case Opcodes.FCMPL: case Opcodes.FCMPG: popN(2); push(Val.unknown("compare")); return;
            case Opcodes.IFEQ: case Opcodes.IFNE: case Opcodes.IFLT: case Opcodes.IFGE:
            case Opcodes.IFGT: case Opcodes.IFLE: case Opcodes.IFNULL: case Opcodes.IFNONNULL:
                popN(1); return;
            case Opcodes.IF_ICMPEQ: case Opcodes.IF_ICMPNE: case Opcodes.IF_ICMPLT:
            case Opcodes.IF_ICMPGE: case Opcodes.IF_ICMPGT: case Opcodes.IF_ICMPLE:
            case Opcodes.IF_ACMPEQ: case Opcodes.IF_ACMPNE:
                popN(2); return;
            case Opcodes.GOTO: return;
            case Opcodes.JSR: push(Val.unknown("jsr")); return;
            case Opcodes.RET: return;
            case Opcodes.TABLESWITCH: case Opcodes.LOOKUPSWITCH: popN(1); return;
            case Opcodes.IRETURN: case Opcodes.FRETURN: case Opcodes.ARETURN:
            case Opcodes.LRETURN: case Opcodes.DRETURN: popN(1); stack.clear(); return;
            case Opcodes.RETURN: stack.clear(); return;
            case Opcodes.ATHROW: stack.clear(); return;
            case Opcodes.GETSTATIC: {
                FieldInsnNode f = (FieldInsnNode) in;
                if (jar != null && isContentArrayField(f.desc)) {
                    String key = f.owner + "." + f.name;
                    Val v = contentArrayFields.computeIfAbsent(key,
                            k -> Val.contentArray(f.desc.substring(2, f.desc.length() - 1)));
                    push(v, isWideDesc(f.desc));
                    return;
                }
                push(Val.staticField(f.owner, f.name, f.desc), isWideDesc(f.desc));
                return;
            }
            case Opcodes.PUTSTATIC: pop(); return;
            case Opcodes.GETFIELD: {
                FieldInsnNode f = (FieldInsnNode) in;
                Val recv = pop();
                if (trackThisFields && recv.kind == Val.Kind.THIS) {
                    // Same-method "this.field = X; ...; this.field.foo()" round trip — a plain
                    // linear-pass analog of the local-variable store/load tracking above, not an
                    // interprocedural hop (the write and the read are both in THIS one method
                    // execution). Common 1.7.10 shape this previously lost: a Techne-generated
                    // model's <init> does `this.bullet = new ModelRenderer(this, 0, 0);` then
                    // re-reads `this.bullet` via a fresh ALOAD/GETFIELD before every addBox/
                    // setRotationPoint call (javap-confirmed on HBM's own ModelBullet — see
                    // ENTITY-RENDER-EXTRACTION.md) — without this, every one of those reads
                    // produced only an opaque UNKNOWN, discarding the constructed object's
                    // identity between statements. See {@link #thisFieldValue} for the
                    // write-ambiguity guard (mirrors {@link #store}/{@link #poisoned} exactly).
                    String key = f.owner + "." + f.name;
                    if (!thisFieldPoisoned.contains(key)) {
                        Val cached = thisFieldValue.get(key);
                        if (cached != null) { push(cached, isWideDesc(f.desc)); return; }
                    }
                }
                if (jar != null && recv.kind == Val.Kind.PARAM) {
                    Val perConst = tryEnumFieldPerConstant(recv.numberValue.intValue(), f.name);
                    if (perConst != null) { push(Val.arrayUnknownIndex(perConst), isWideDesc(f.desc)); return; }
                }
                if (jar != null && recv.kind == Val.Kind.THIS) {
                    Val single = tryInstanceFieldSingleAssignment(f.owner, f.name);
                    if (single != null) { push(single, isWideDesc(f.desc)); return; }
                }
                if (trackProvenance) {
                    push(Val.field(f.owner, f.name, f.desc, recv), isWideDesc(f.desc));
                    return;
                }
                push(Val.unknown("getfield " + f.owner + "." + f.name), isWideDesc(f.desc));
                return;
            }
            case Opcodes.PUTFIELD: {
                FieldInsnNode f = (FieldInsnNode) in;
                Val v = pop(); Val recv = pop();
                if (trackThisFields && recv.kind == Val.Kind.THIS) {
                    String key = f.owner + "." + f.name;
                    AbstractInsnNode prev = thisFieldWriter.get(key);
                    if (prev != null && prev != in) {
                        // Written from more than one instruction in this method (e.g. two
                        // different branches, or a loop body) — which value is actually present
                        // at any later read depends on control flow this single linear pass does
                        // not model, so degrade to "unknown from here on" exactly like a poisoned
                        // local, rather than guessing whichever write happened to run last in
                        // textual order.
                        thisFieldPoisoned.add(key);
                        thisFieldValue.remove(key);
                    } else {
                        thisFieldWriter.put(key, in);
                        if (!thisFieldPoisoned.contains(key)) thisFieldValue.put(key, v);
                    }
                }
                return;
            }
            case Opcodes.INVOKEVIRTUAL: case Opcodes.INVOKESPECIAL:
            case Opcodes.INVOKESTATIC: case Opcodes.INVOKEINTERFACE: {
                MethodInsnNode m = (MethodInsnNode) in;
                invoke(m, op);
                return;
            }
            case Opcodes.INVOKEDYNAMIC: {
                InvokeDynamicInsnNode d = (InvokeDynamicInsnNode) in;
                popN(Type.getArgumentTypes(d.desc).length);
                Type r = Type.getReturnType(d.desc);
                if (r.getSort() != Type.VOID) push(Val.unknown("invokedynamic result"), r.getSize() == 2);
                return;
            }
            case Opcodes.NEW: push(Val.newUninit(((TypeInsnNode) in).desc, uninitSeq++)); return;
            case Opcodes.NEWARRAY: {
                Val n = pop();
                push(n.kind == Val.Kind.NUMBER && n.numberValue.intValue() >= 0 && n.numberValue.intValue() < 4096
                        ? Val.array(primName(((IntInsnNode) in).operand), n.numberValue.intValue())
                        : Val.unknown("newarray with non-constant length"));
                return;
            }
            case Opcodes.ANEWARRAY: {
                Val n = pop();
                push(n.kind == Val.Kind.NUMBER && n.numberValue.intValue() >= 0 && n.numberValue.intValue() < 4096
                        ? Val.array(((TypeInsnNode) in).desc, n.numberValue.intValue())
                        : Val.unknown("anewarray with non-constant length"));
                return;
            }
            case Opcodes.ARRAYLENGTH: popN(1); push(Val.unknown("arraylength")); return;
            case Opcodes.CHECKCAST: return; // type refinement only
            case Opcodes.INSTANCEOF: popN(1); push(Val.unknown("instanceof")); return;
            case Opcodes.MONITORENTER: case Opcodes.MONITOREXIT: popN(1); return;
            case Opcodes.MULTIANEWARRAY: {
                popN(((MultiANewArrayInsnNode) in).dims);
                push(Val.unknown("multianewarray"));
                return;
            }
            default:
                if (op >= 0) { stack.clear(); }
        }
    }

    /** Constant double[] contents of a folded ARRAY value, or null when not all-constant. */
    private static double[] constDoubleArray(Val arr) {
        if (arr == null || arr.kind != Val.Kind.ARRAY || arr.elements == null) return null;
        double[] out = new double[arr.elements.size()];
        for (int i = 0; i < out.length; i++) {
            Val e = arr.elements.get(i);
            if (e == null || e.kind != Val.Kind.NUMBER || e.numberValue == null) return null;
            out[i] = e.numberValue.doubleValue();
        }
        return out;
    }

    private void invoke(MethodInsnNode m, int op) {
        Type[] args = Type.getArgumentTypes(m.desc);
        List<Val> actuals = new ArrayList<>();
        for (int i = 0; i < args.length; i++) actuals.add(0, pop());
        Val receiver = null;
        if (op != Opcodes.INVOKESTATIC) receiver = pop();

        if (op == Opcodes.INVOKESPECIAL && "<init>".equals(m.name)) {
            // materialise the object: replace every copy of the uninit marker
            if (receiver != null && receiver.kind == Val.Kind.NEW_UNINIT) {
                Val obj = Val.newObj(receiver.typeName, actuals);
                if (isStringBuilderType(receiver.typeName)) {
                    obj.builtString = actuals.isEmpty() ? ""
                            : (actuals.get(0).kind == Val.Kind.STRING ? actuals.get(0).stringValue : null);
                }
                int id = receiver.uninitId;
                for (int i = 0; i < stack.size(); i++) {
                    Val s = stack.get(i);
                    if (s.kind == Val.Kind.NEW_UNINIT && s.uninitId == id) stack.set(i, obj);
                }
                for (Map.Entry<Integer, Val> e : locals.entrySet()) {
                    Val s = e.getValue();
                    if (s != null && s.kind == Val.Kind.NEW_UNINIT && s.uninitId == id) e.setValue(obj);
                }
            }
            return; // <init> returns void
        }

        Type ret = Type.getReturnType(m.desc);
        if (ret.getSort() == Type.VOID) return;

        // modelled helper: Item.getItemFromBlock(Block)
        if (op == Opcodes.INVOKESTATIC && "net/minecraft/item/Item".equals(m.owner)
                && ("func_150898_a".equals(m.name) || "getItemFromBlock".equals(m.name))
                && actuals.size() == 1) {
            push(Val.itemFromBlock(actuals.get(0)));
            return;
        }
        // modelled helper: HFRWavefrontObject.asVBO() and friends are identity for our purposes
        if (op == Opcodes.INVOKEVIRTUAL && receiver != null
                && ("asVBO".equals(m.name) || "asVBO2".equals(m.name))) {
            push(receiver);
            return;
        }
        // modelled helper: AdvancedModelLoader.loadModel(ResourceLocation) -> the location itself
        if (op == Opcodes.INVOKESTATIC
                && "net/minecraftforge/client/model/AdvancedModelLoader".equals(m.owner)
                && "loadModel".equals(m.name) && actuals.size() == 1) {
            push(actuals.get(0));
            return;
        }
        // modelled accessors: Item.getUnlocalizedName() / Block.getUnlocalizedName()
        if (receiver != null && actuals.isEmpty()
                && ("func_77658_a".equals(m.name) || "func_149739_a".equals(m.name)
                    || "getUnlocalizedName".equals(m.name))) {
            push(Val.derived("getUnlocalizedName", receiver));
            return;
        }
        // java.lang.StringBuilder/StringBuffer#toString(): resolve to a concrete Val.STRING
        // exactly when every piece appended so far was itself a known string (never a guess -
        // builtString is poisoned to null the moment an unknown piece was appended).
        if (op == Opcodes.INVOKEVIRTUAL && receiver != null && "toString".equals(m.name) && actuals.isEmpty()
                && isStringBuilderType(receiver.typeName) && receiver.builtString != null) {
            push(Val.string(receiver.builtString));
            return;
        }
        // Door-live follow-up (GL-state ops): DoubleBuffer.put(double[]) mutates the
        // buffer in place and returns it (HBM discards the result and reuses the
        // parameter), so the constant payload attaches to the RECEIVER object itself,
        // where a later glClipPlane reads it. A non-constant payload poisons it back
        // to null (honest drop). Buffer.rewind() is the identity on the same object.
        // NOTE: before the builder-chains block below - put() matches that shape.
        if (op == Opcodes.INVOKEVIRTUAL && "java/nio/DoubleBuffer".equals(m.owner)
                && "put".equals(m.name) && "([D)Ljava/nio/DoubleBuffer;".equals(m.desc)
                && actuals.size() == 1 && receiver != null) {
            receiver.bufferDoubles = constDoubleArray(actuals.get(0));
            push(receiver, false);
            return;
        }
        if (op == Opcodes.INVOKEVIRTUAL && "rewind".equals(m.name)
                && "()Ljava/nio/Buffer;".equals(m.desc) && receiver != null) {
            push(receiver, false);
            return;
        }
        // builder chains: `new X().setFoo(..).setBar(..)` keeps returning the same object.
        // (DoubleBuffer.put/rewind modelling lives just above: put() matches this
        // builder shape, so it must be recognised first.)
        if (op == Opcodes.INVOKEVIRTUAL && receiver != null && ret.getSort() == Type.OBJECT) {
            String rn = ret.getInternalName();
            boolean builderShape = rn.equals(m.owner)
                    || "net/minecraft/item/Item".equals(rn)
                    || "net/minecraft/block/Block".equals(rn)
                    || (receiver.kind == Val.Kind.NEW_OBJ && rn.equals(receiver.typeName));
            if (builderShape) {
                String arg = null;
                for (Val a : actuals) if (a.kind == Val.Kind.STRING) { arg = a.stringValue; break; }
                Val target = receiver;
                if (receiver.kind != Val.Kind.NEW_OBJ) {
                    // e.g. `ModItems.base.copy().setUnlocalizedName("variant")` — the chain
                    // yields a *different* object, so synthesise one rather than aliasing.
                    List<Val> from = new ArrayList<>();
                    from.add(receiver);
                    target = Val.newObj(rn, from);
                    target.syntheticBuilder = true;
                }
                if (target.calls == null) target.calls = new ArrayList<>();
                target.calls.add(new String[]{m.name, arg});
                if ("append".equals(m.name) && isStringBuilderType(target.typeName) && target.builtString != null) {
                    target.builtString = (!actuals.isEmpty() && actuals.get(0).kind == Val.Kind.STRING)
                            ? target.builtString + actuals.get(0).stringValue
                            : null; // poisoned: an appended piece we cannot resolve to a literal
                }
                push(target);
                return;
            }
        }
        if (op == Opcodes.INVOKESTATIC) {
            // keep the arguments: factory helpers such as
            // ItemRenderMissileGeneric.generateStandard(texture, model) carry the resources.
            Val call = Val.call(m.owner, m.name, actuals);
            call.desc = m.desc;
            push(call, ret.getSize() == 2);
            return;
        }
        // Opt-in provenance only (moving-parts lane): model a few virtual pure calls as CALL
        // nodes WITH the receiver prepended, so the dynamic-op resolver can recognise
        // tile.getWorldObj()/world.getWorldTime()/getTotalWorldTime() chains. Every other
        // caller keeps the opaque UNKNOWN below, byte-identical to before.
        if (trackProvenance && op == Opcodes.INVOKEVIRTUAL && receiver != null && actuals.isEmpty()) {
            if (isGetWorldObjCall(m) || isWorldTimeCall(m)) {
                List<Val> withRecv = new ArrayList<>(1);
                withRecv.add(receiver);
                Val call = Val.call(m.owner, m.name, withRecv);
                call.desc = m.desc;
                push(call, ret.getSize() == 2);
                return;
            }
        }
        // Opt-in provenance only (door-live lane): World.getTileEntity(x,y,z) is how block
        // collision methods reach the tile whose fields gate the bounds. Modelled with the
        // receiver (world) plus coordinates prepended so the live-bounds detector can tell
        // "reads a tile-entity field" from pure positional math.
        if (trackProvenance && op == Opcodes.INVOKEVIRTUAL && receiver != null) {
            if (isGetTileEntityCall(m)) {
                List<Val> withRecv = new ArrayList<>(actuals.size() + 1);
                withRecv.add(receiver);
                withRecv.addAll(actuals);
                Val call = Val.call(m.owner, m.name, withRecv);
                call.desc = m.desc;
                push(call, ret.getSize() == 2);
                return;
            }
        }
        push(Val.unknown("result of " + shortName(m.owner) + "." + m.name + "()"), ret.getSize() == 2);
    }

    private static boolean isGetWorldObjCall(MethodInsnNode m) {
        return ("func_145831_w".equals(m.name) || "getWorldObj".equals(m.name))
                && "()Lnet/minecraft/world/World;".equals(m.desc);
    }

    private static boolean isWorldTimeCall(MethodInsnNode m) {
        if (!"net/minecraft/world/World".equals(m.owner)) return false;
        if ("func_72820_D".equals(m.name) || "getWorldTime".equals(m.name)) return "()J".equals(m.desc);
        return ("func_82737_E".equals(m.name) || "getTotalWorldTime".equals(m.name)) && "()J".equals(m.desc);
    }

    private static boolean isGetTileEntityCall(MethodInsnNode m) {
        return ("func_147438_o".equals(m.name) || "getTileEntity".equals(m.name))
                && "(III)Lnet/minecraft/tileentity/TileEntity;".equals(m.desc);
    }

    private Val arrayGet(Val arr, Val idx) {
        // Opt-in provenance only (door-live lane): a constant-index read off a static pure
        // call returning an array (the animation-track shape) stays a structured CHANNEL node
        // carrying the call plus the index, instead of collapsing - the resolver turns it
        // into a server-evaluated channel. Everything else falls through unchanged.
        if (trackProvenance && arr != null && arr.kind == Val.Kind.CALL
                && idx != null && idx.kind == Val.Kind.NUMBER) {
            return Val.channel(arr, idx.numberValue.intValue());
        }
        if (arr.kind == Val.Kind.ARRAY) {if (idx.kind == Val.Kind.NUMBER) {
                int i = idx.numberValue.intValue();
                if (i >= 0 && i < arr.elements.size()) return arr.elements.get(i);
            } else if (arr.symbolicWrites != null) {
                // A virtual content array (Val#contentArray): was THIS exact symbolic index
                // (same loop-counter slot, see the ILOAD case) written earlier in this same
                // method's single pass? If so, that concrete value — not a placeholder — is what
                // a real execution of this loop iteration would read back.
                Val hit = arr.symbolicWrites.get(idx);
                if (hit != null) return hit;
            }
            // the array itself is fully known (e.g. built by a compile-time
            // `new T[]{...}` or a run of constant-index AASTOREs) but this particular read
            // uses a non-constant index — typically a `for` loop's induction variable. Keep the
            // whole array around instead of collapsing to a bare UNKNOWN, so a registration-site
            // resolver that recognises this shape can enumerate every element (mod-agnostic
            // support for "renderers registered in a loop over an array").
            return Val.arrayUnknownIndex(arr);
        }
        return Val.unknown("array element with non-constant index or unknown array");
    }

    /**
     * Is {@code desc} (a field descriptor) a one-dimensional array of a class THIS jar declares
     * that is itself a {@code net.minecraft.block.Block}/{@code net.minecraft.item.Item}
     * subclass? The same hierarchy test {@link ModRegistryResolver#blockOrItem} already applies
     * to scalar holder fields, lifted one array dimension — deliberately narrow (never matches
     * {@code String[]}, {@code int[]}, a vanilla/engine array, ...) so the content-array tracking
     * above only ever engages for the one shape it exists to fix.
     */
    private boolean isContentArrayField(String desc) {
        if (desc.length() < 4 || desc.charAt(0) != '[' || desc.charAt(1) != 'L' || !desc.endsWith(";")) return false;
        String internal = desc.substring(2, desc.length() - 1);
        if (jar.cls(internal) == null) return false;
        return jar.isSubclassOf(internal, "net/minecraft/block/Block")
                || jar.isSubclassOf(internal, "net/minecraft/item/Item");
    }

    /** Guards {@link #tryInstanceFieldSingleAssignment} against a pathological cycle (field A's
     *  own constructor reads field B, whose resolution reads field A back) — shared across every
     *  {@link MethodSim} instance spawned while resolving one top-level {@code getfield}, since
     *  each recursive step runs its own fresh instance. 1.7.10 mod jars have no legitimate reason
     *  to have such a cycle; this exists purely so a pathological one degrades to an honest
     *  "unresolved" instead of a stack overflow that would abort the whole scan. */
    private static final ThreadLocal<Set<String>> RESOLVING_INSTANCE_FIELDS = ThreadLocal.withInitial(HashSet::new);
    /**
     * {@code getfield this.fieldName} where the field's OWN declaring class assigns it from
     * EXACTLY ONE place, in one of its OWN {@code <init>} overloads (a plain
     * {@code this.field = new Foo(...);} in the constructor — a common 1.7.10 idiom for "hold my
     * renderer/helper in an instance field instead of a static one," distinct from the static-field
     * shape {@link ModRegistryResolver} already handles): resolves to that one value instead of
     * the usual opaque {@code UNKNOWN}. Confirmed by direct bytecode inspection of Chisel's own
     * {@code ClientProxy} (its constructor does {@code this.renderer = new ItemChiselRenderer();},
     * and {@code init()} later does {@code MinecraftForgeClient.registerItemRenderer(chisel,
     * this.renderer)} — {@link BindingScanner} could not see the renderer class at all before this,
     * because a {@code getfield} on {@code this} produced only an opaque placeholder). When the
     * field is instead assigned from more than one place (a real ambiguity — which one wins
     * depends on which constructor ran, or execution order this static analysis cannot know),
     * this stays honestly {@code null} rather than guessing. Bounded: only the field's OWN
     * declaring class's OWN constructors are scanned — no interprocedural hop into any other
     * class — and {@link #RESOLVING_INSTANCE_FIELDS} guards against a pathological cycle.
     */
    private Val tryInstanceFieldSingleAssignment(String ownerInternal, String fieldName) {
        String cacheKey = ownerInternal + "#" + fieldName;
        if (jar.instanceFieldSingleAssignmentCache.containsKey(cacheKey)) return jar.instanceFieldSingleAssignmentCache.get(cacheKey);
        Set<String> resolving = RESOLVING_INSTANCE_FIELDS.get();
        if (!resolving.add(cacheKey)) return null; // cycle guard: already resolving this one
        Val result = null;
        try {
            ClassNode owner = jar.cls(ownerInternal);
            if (owner != null) {
                List<Val> found = new ArrayList<>();
                for (MethodNode m : owner.methods) {
                    if (!"<init>".equals(m.name)) continue;
                    MethodSim.run(owner, m, jar, (insn, stack, locals) -> {
                        if (insn.getOpcode() != Opcodes.PUTFIELD) return;
                        FieldInsnNode pf = (FieldInsnNode) insn;
                        if (!ownerInternal.equals(pf.owner) || !fieldName.equals(pf.name) || stack.size() < 2) return;
                        Val v = stack.get(stack.size() - 1), recv = stack.get(stack.size() - 2);
                        if (recv.kind == Val.Kind.THIS) found.add(v);
                    });
                }
                if (found.size() == 1) result = found.get(0);
            }
        } finally {
            resolving.remove(cacheKey);
        }
        jar.instanceFieldSingleAssignmentCache.put(cacheKey, result);
        return result;
    }

    /**
     * {@code getfield receiver.fieldName} where {@code receiver} is formal parameter
     * {@code paramIndex} of the CURRENT method, and that parameter's OWN declared type is an enum
     * class the jar declares: rather than the usual opaque {@code UNKNOWN} (a single instance's
     * field value truly cannot be known without knowing which constant flows in — that would need
     * tracing every caller, an unbounded-in-general search this project deliberately avoids), this
     * recognises that we already know the FULL, fixed set of values the receiver could ever be —
     * every constant the enum declares — and resolves {@code fieldName} for EACH of them via
     * {@link DynamicVariantResolver#readEnumClinit}/{@link DynamicVariantResolver#resolveInstanceField}
     * (both already-generic, already-tested machinery; no new capability invented here, just a new
     * call site). The one-instance value at THIS call site is therefore left honestly unresolved
     * per-se, but the array of "every value it could be" is exactly what a caller who registers
     * one row per array element (see {@code ClientRegistry.bindTileEntitySpecialRenderer} in
     * {@link BindingScanner}) needs: Iron Chests' {@code CommonProxy}/{@code ClientProxy} binds
     * its TESR once per {@code IronChestType} constant, passing {@code type.clazz} — a getfield on
     * a parameter of enum type {@code IronChestType} — confirmed by direct bytecode inspection.
     * Bounded: one enum's own {@code <clinit>} plus (via {@code resolveInstanceField}) at most a
     * few of its own constructor overloads — zero interprocedural hops beyond the enum's own
     * declaration, and zero mod-specific literals (any jar-declared enum qualifies).
     */
    private Val tryEnumFieldPerConstant(int paramIndex, String fieldName) {
        if (paramTypes == null || paramIndex < 0 || paramIndex >= paramTypes.length) return null;
        Type t = paramTypes[paramIndex];
        if (t.getSort() != Type.OBJECT) return null;
        String internal = t.getInternalName();
        String cacheKey = internal + "." + fieldName;
        if (jar.enumFieldPerConstantCache.containsKey(cacheKey)) return jar.enumFieldPerConstantCache.get(cacheKey);
        Val result = null;
        ClassNode enumCn = jar.cls(internal);
        if (enumCn != null && (enumCn.access & Opcodes.ACC_ENUM) != 0) {
            List<DynamicVariantResolver.EnumConst> consts = DynamicVariantResolver.readEnumClinit(enumCn);
            if (!consts.isEmpty()) {
                List<Val> vals = new ArrayList<>();
                for (DynamicVariantResolver.EnumConst ec : consts) {
                    Val v = DynamicVariantResolver.resolveInstanceField(enumCn, ec, fieldName);
                    vals.add(v != null ? v : Val.unknown("enum constant " + JarIndex.dotted(enumCn.name) + "."
                            + ec.name + " does not resolve field " + fieldName));
                }
                result = Val.arrayLiteral(vals);
            }
        }
        jar.enumFieldPerConstantCache.put(cacheKey, result);
        return result;
    }

    private static boolean isWideDesc(String desc) { return "J".equals(desc) || "D".equals(desc); }

    private static boolean isStringBuilderType(String internalName) {
        return "java/lang/StringBuilder".equals(internalName) || "java/lang/StringBuffer".equals(internalName);
    }

    private static String primName(int operand) {
        switch (operand) {
            case Opcodes.T_BOOLEAN: return "Z"; case Opcodes.T_CHAR: return "C";
            case Opcodes.T_FLOAT: return "F";   case Opcodes.T_DOUBLE: return "D";
            case Opcodes.T_BYTE: return "B";    case Opcodes.T_SHORT: return "S";
            case Opcodes.T_INT: return "I";     case Opcodes.T_LONG: return "J";
            default: return "?";
        }
    }

    static String shortName(String internal) {
        int i = internal.lastIndexOf('/');
        return i < 0 ? internal : internal.substring(i + 1);
    }
}
