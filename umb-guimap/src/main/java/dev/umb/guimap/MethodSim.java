package dev.umb.guimap;

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

    /** Receives every instruction together with the operand stack as it is *before* execution.
     *  Also invoked, with an empty-ish stack snapshot, for every {@link LabelNode} the walk passes
     *  (umb-guimap addition) so a caller can track lexical scope (e.g. "have we left the region a
     *  forward conditional jump guards") without needing a real control-flow graph. */
    public interface Handler {
        void onInsn(AbstractInsnNode insn, List<Val> stack, Map<Integer, Val> locals);
    }

    /** Maximum recursion depth for accessor inlining (see {@link #tryInlineAccessor}) — bounds the
     *  work for a pathological call chain instead of needing cycle detection. */
    private static final int MAX_INLINE_DEPTH = 3;

    private final List<Val> stack = new ArrayList<>();
    private final Map<Integer, Val> locals = new HashMap<>();
    private final Map<Integer, AbstractInsnNode> localWriter = new HashMap<>();
    private final Set<Integer> poisoned = new HashSet<>();
    private final Map<Val, Boolean> wide = new java.util.IdentityHashMap<>();
    private int uninitSeq = 0;
    /** Non-null only when this MethodSim was constructed with jar access (umb-guimap addition):
     *  enables inlining a same-jar accessor method's body at its call site instead of collapsing
     *  the call result to a generic unknown — see {@link #tryInlineAccessor}. */
    private final JarIndex jar;
    private final int depth;
    /**
     * NOTEXTURE-GAP lane: the internal name of the CONCRETE class this scan is conceptually for —
     * e.g. {@code GUITurretSentry}, one of several sibling GUI subclasses that all share one
     * ancestor's method body ({@code GuiClassAnalyzer}'s own {@code findDeclaringClass} walk finds
     * that ancestor and hands ITS bytecode to this class to execute, while the scan is still "for"
     * the concrete leaf). Null for every call site that never supplies it. Only ever consulted when
     * {@link #extendedAccessorInlining} is also true (see that field) — carrying it through the
     * ordinary scan is otherwise inert.
     */
    private final String selfClassInternal;
    /**
     * NOTEXTURE-GAP lane: true ONLY for the short-lived, on-demand {@link MethodSim} instance
     * {@link #resolveAccessorForTextureClassification} creates — never for the instance that scans
     * a whole method body for {@code DrawLayerScanner} (every {@code run(...)} entry point below
     * leaves this {@code false}). Gates TWO extensions to {@link #tryInlineAccessor}, both scoped to
     * texture-bind classification only:
     * <ol>
     *   <li>An {@code ARETURN} (object-reference return — e.g. a {@code getTexture(): ResourceLocation}
     *   accessor) is inlineable, not just the four numeric return opcodes.</li>
     *   <li>A {@code this}-receiver virtual/interface call starts its override search at
     *   {@link #selfClassInternal} (the receiver's ACTUAL runtime type) instead of the compile-time
     *   owner recorded in the invoke instruction — the compile-time owner is only a lower bound (the
     *   declaring class of whichever ancestor's method body happens to contain the call), and true
     *   Java virtual dispatch always starts at the runtime type. {@code selfClassInternal} is
     *   guaranteed to be a (possibly indirect) subclass of that owner by construction (see
     *   {@code GuiClassAnalyzer#findDeclaringClass}), so this can only find an override CLOSER to
     *   the leaf, never an unrelated method.</li>
     * </ol>
     * <b>Deliberately NOT enabled for the automatic, every-call-site inlining {@code invoke()} uses</b>
     * — a demonstrated regression during development: a guard/label consumer elsewhere in this
     * codebase (see {@code DrawLayerScanner#fieldRequirementJson}/{@code ExprEval}) specifically
     * relies on certain accessor calls staying an UNRESOLVED {@code "result of X.y()"} {@link Val} so
     * they can be recognised afterwards as one ACCESSOR HOP in a tile-entity field chain (the actual
     * value is read live, reflectively, at render time — see {@code TileFieldReader}), rather than
     * being eagerly resolved to whatever this bounded interpreter can compute inside the callee's own
     * body (which, for an accessor over a non-constant runtime argument such as
     * {@code IInventory.getStackInSlot(int)}, is frequently a strictly WORSE, less-recognisable Val
     * shape than the plain call-result placeholder). Enabling both extensions only for the dedicated,
     * texture-classification-only entry point below keeps every other consumer byte-for-byte
     * unchanged — proven by the full corpus leaf-diff in
     * {@code research/out/legacy/guimap-notes/NOTEXTURE-GAP.md}.
     */
    private final boolean extendedAccessorInlining;

    private MethodSim() { this(null, 0, null, false); }
    private MethodSim(JarIndex jar, int depth) { this(jar, depth, null, false); }
    private MethodSim(JarIndex jar, int depth, String selfClassInternal, boolean extendedAccessorInlining) {
        this.jar = jar; this.depth = depth; this.selfClassInternal = selfClassInternal;
        this.extendedAccessorInlining = extendedAccessorInlining;
    }

    public static void run(ClassNode cn, MethodNode mn, Handler h) {
        new MethodSim().exec(cn, mn, null, h);
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
        new MethodSim().exec(cn, mn, seedLocals, h);
    }

    /**
     * Like {@link #run(ClassNode, MethodNode, Handler)}, but with jar access enabled so that a call
     * to a zero-or-more-arg accessor method declared elsewhere IN THE JAR (e.g. the 1.7.10
     * {@code getFooScaled(24)} idiom) is inlined — the callee is re-simulated with its receiver and
     * actual arguments substituted, and its single return value is pushed in place of a generic
     * {@code "result of ..."} unknown. umb-guimap addition, used by {@code DrawLayerScanner}.
     */
    public static void run(ClassNode cn, MethodNode mn, JarIndex jar, Handler h) {
        new MethodSim(jar, 0, null, false).exec(cn, mn, null, h);
    }

    /**
     * Like {@link #run(ClassNode, MethodNode, JarIndex, Handler)}, but additionally tells this scan
     * which CONCRETE class it is conceptually for — see {@link #selfClassInternal}'s own javadoc.
     * {@code selfClassInternal} may be null (falls back to the exact pre-existing behavior of the
     * 4-arg overload above). {@link #extendedAccessorInlining} is always {@code false} for a scan
     * started this way — carrying {@code selfClassInternal} here is otherwise inert; it exists only
     * so {@code DrawLayerScanner} can later hand the SAME concrete-class name to
     * {@link #resolveAccessorForTextureClassification} for one specific, already-unresolved
     * texture-bind {@link Val}, without a second jar-wide lookup.
     */
    public static void run(ClassNode cn, MethodNode mn, JarIndex jar, String selfClassInternal, Handler h) {
        new MethodSim(jar, 0, selfClassInternal, false).exec(cn, mn, null, h);
    }

    /**
     * NOTEXTURE-GAP lane: on-demand, texture-classification-only re-resolution of ONE already-
     * unresolved call-result {@link Val} (the {@code "result of Owner.name()"} shape {@code invoke()}
     * produces below when its own, unextended {@link #tryInlineAccessor} attempt fails) — see
     * {@code DrawLayerScanner#classifyTexture}, the only caller. Never invoked automatically during
     * a normal scan; enables {@link #extendedAccessorInlining} (ARETURN + this-receiver virtual
     * dispatch from {@code selfClassInternal}) for exactly this one call, bounded the same way any
     * other accessor inlining is ({@link #MAX_INLINE_DEPTH}). Returns {@code null} (never guesses)
     * under the exact same conditions {@link #tryInlineAccessor} always has: not declared in the jar,
     * abstract/bodyless, zero or more than one return site, or {@code call} isn't actually a
     * same-jar call-result shape ({@code owner}/{@code name}/{@code desc}/{@code receiver} all set).
     */
    public static Val resolveAccessorForTextureClassification(JarIndex jar, String selfClassInternal, Val call) {
        if (jar == null || call == null || call.kind != Val.Kind.UNKNOWN
                || call.owner == null || call.name == null || call.desc == null || call.receiver == null) {
            return null;
        }
        List<Val> actuals = call.callArgs != null ? call.callArgs : new ArrayList<>();
        return new MethodSim(jar, 0, selfClassInternal, true)
                .tryInlineAccessor(call.owner, call.name, call.desc, call.receiver, actuals, call.invokeOp);
    }

    private void exec(ClassNode cn, MethodNode mn, Map<Integer, Val> seedLocals, Handler h) {
        if (mn.instructions == null || mn.instructions.size() == 0) return;

        // GUI-fidelity lane: per-exec reaching-definitions state (see load(int, AbstractInsnNode)).
        currentMethod = mn;
        currentIndex.clear();
        rdInSets.clear();
        writeVals.clear();
        writeSlots.clear();
        localWriter.clear();
        rdBlocks = null;
        rdImpossible = false;
        AbstractInsnNode[] execArr = mn.instructions.toArray();
        for (int i = 0; i < execArr.length; i++) currentIndex.put(execArr[i], Integer.valueOf(i));

        Set<LabelNode> targets = new HashSet<>();
        Set<LabelNode> handlers = new HashSet<>();
        for (AbstractInsnNode in : mn.instructions.toArray()) {
            if (in instanceof JumpInsnNode) targets.add(((JumpInsnNode) in).label);
            else if (in instanceof TableSwitchInsnNode t) { targets.add(t.dflt); targets.addAll(t.labels); }
            else if (in instanceof LookupSwitchInsnNode l) { targets.add(l.dflt); targets.addAll(l.labels); }
        }
        if (mn.tryCatchBlocks != null)
            for (TryCatchBlockNode tc : mn.tryCatchBlocks) { handlers.add(tc.handler); targets.add(tc.handler); }

        boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
        int slot = 0;
        if (!isStatic) locals.put(slot++, Val.thisRef());
        Type[] params = Type.getArgumentTypes(mn.desc);
        for (int ai = 0; ai < params.length; ai++) {
            locals.put(slot, Val.param(ai));
            slot += params[ai].getSize();
        }
        if (seedLocals != null) {
            for (Map.Entry<Integer, Val> e : seedLocals.entrySet()) locals.put(e.getKey(), e.getValue());
        }

        for (AbstractInsnNode in : mn.instructions.toArray()) {
            if (in instanceof LabelNode ln) {
                // umb-guimap addition: let the handler see label boundaries too (before clearing
                // the stack) so it can track lexical scope, e.g. "this forward conditional's target
                // was reached" — see DrawLayerScanner's guard-region tracking.
                h.onInsn(in, stack, locals);
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

    private void store(int idx, Val v, AbstractInsnNode at) {
        writeVals.put(at, v);
        writeSlots.put(at, Integer.valueOf(idx));
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
        return load(idx, null);
    }

    /**
     * GUI-fidelity lane: a poisoned (multiply-written) local resolves to its unique reaching
     * definition when classic reaching-definitions dataflow proves exactly one write reaches
     * this load (battery `redLow` icon value vs the bar height sharing slot 4 in disjoint
     * regions; assembler arrow width vs the later recipe local sharing slot 5). Otherwise -
     * genuine diamonds, loops with competing writes - the honest poisoned unknown stays.
     * {@code at} is the load instruction (null when the caller has no position, which keeps
     * today's behavior unconditionally).
     */
    private Val load(int idx, AbstractInsnNode at) {
        Val v = locals.get(idx);
        if (v == null) return Val.unknown("local " + idx + " read before any modelled write");
        if (!poisoned.contains(idx) || at == null) return v;
        Val reaching = uniqueReachingWrite(idx, at);
        return reaching != null ? reaching : v;
    }

    /** One reaching-definition fact: local slot written by one instruction. */
    private static final class WriteDef {
        final int slot;
        final AbstractInsnNode at;

        WriteDef(int slot, AbstractInsnNode at) {
            this.slot = slot;
            this.at = at;
        }

        @Override public boolean equals(Object o) {
            return o instanceof WriteDef w && w.slot == slot && w.at == at;
        }

        @Override public int hashCode() {
            return slot * 31 + System.identityHashCode(at);
        }
    }

    /** Bound: blocks per method analysed for reaching definitions (draw methods are tiny). */
    private static final int MAX_RD_BLOCKS = 256;
    /** Bound: fixpoint iterations (reaching definitions converges in depth+1 passes). */
    private static final int MAX_RD_ITERATIONS = 1024;

    /** Every ISTORE-family value ever stored, by writing instruction (for reaching-def reads). */
    private final Map<AbstractInsnNode, Val> writeVals = new HashMap<>();
    /** Local slot written by each writing instruction. */
    private final Map<AbstractInsnNode, Integer> writeSlots = new HashMap<>();
    /** Instruction index per node for the method under exec (rebuilt per exec call). */
    private final Map<AbstractInsnNode, Integer> currentIndex = new HashMap<>();
    /** RD_in per block (reaching writes at block entry). */
    private final Map<Integer, java.util.Set<WriteDef>> rdInSets = new HashMap<>();
    /** Lazily built per method: block leaders, successors, and the RD fixpoint. Null until
     *  the first poisoned load needs it, or when the method is out of scope (handlers). */
    private RdBlocks rdBlocks = null;
    private boolean rdImpossible = false;
    /** Method under exec (for RD block building). */
    private MethodNode currentMethod = null;

    private static final class RdBlocks {
        /** Block id per instruction index. */
        final Map<Integer, Integer> blockOf = new HashMap<>();
        /** Successor block ids per block. */
        final Map<Integer, java.util.Set<Integer>> succs = new HashMap<>();
        /** RD_out per block: the set of writes live at its end. */
        final Map<Integer, java.util.Set<WriteDef>> outSets = new HashMap<>();
        /** Block id containing a given instruction index (-1 when unknown). */
        int blockAt(int insnIdx) { return blockOf.getOrDefault(insnIdx, -1); }
    }

    private Val uniqueReachingWrite(int slot, AbstractInsnNode at) {
        if (rdImpossible) return null;
        if (rdBlocks == null) rdBlocks = buildRdBlocks();
        if (rdBlocks == null) {
            rdImpossible = true;
            return null;
        }
        Integer loadIdx = currentIndex.get(at);
        if (loadIdx == null) return null;
        int loadBlock = rdBlocks.blockAt(loadIdx);
        if (loadBlock < 0) return null;
        // Reaching set at the load = RD_in[loadBlock] plus the LATEST write earlier in the
        // SAME block (straight-line code between joins executes in order, so only the last
        // same-block write can be live - older ones are unconditionally overwritten).
        java.util.Set<WriteDef> reaching = new java.util.HashSet<>();
        WriteDef latestSameBlock = null;
        int latestIdx = -1;
        for (Map.Entry<AbstractInsnNode, Val> e : writeVals.entrySet()) {
            Integer wIdx = currentIndex.get(e.getKey());
            if (wIdx != null && rdBlocks.blockAt(wIdx) == loadBlock && wIdx < loadIdx
                    && slotOfWrite(e.getKey()) == slot && wIdx.intValue() > latestIdx) {
                latestIdx = wIdx.intValue();
                latestSameBlock = new WriteDef(slot, e.getKey());
            }
        }
        if (latestSameBlock != null) reaching.add(latestSameBlock);
        // Plus RD_in[loadBlock]: every write live at block entry EXCEPT those killed by an
        // earlier same-block write to the same slot (the loop above already picked the
        // latest same-block one - remove the older same-slot entries from the in-set).
        java.util.Set<WriteDef> inSet = rdInSets.get(loadBlock);
        if (inSet != null) {
            boolean killed = false;
            for (WriteDef w : reaching) {
                if (w.slot == slot) { killed = true; break; }
            }
            for (WriteDef w : inSet) {
                if (w.slot == slot && !killed) reaching.add(w);
                else if (w.slot != slot) reaching.add(w);
            }
        }
        java.util.List<WriteDef> mine = new ArrayList<>();
        for (WriteDef w : reaching) if (w.slot == slot) mine.add(w);
        if (mine.size() != 1) return null;
        return writeVals.get(mine.get(0).at);
    }

    private int slotOfWrite(AbstractInsnNode at) {
        Integer s = writeSlots.get(at);
        return s == null ? -1 : s.intValue();
    }

    /**
     * Classic reaching-definitions over the method's bytecode blocks (leaders: method start,
     * every jump target, every instruction after a jump/return/switch). Exception-handler
     * methods stay out of scope (unknown edges - poison as today). Loops are FINE (the
     * fixpoint is exact for them; competing writes simply yield no unique def). Bounded by
     * {@link #MAX_RD_BLOCKS} blocks and {@link #MAX_RD_ITERATIONS} passes - anything bigger
     * is not a GUI draw method and keeps today's behavior.
     */
    private RdBlocks buildRdBlocks() {
        MethodNode mn = currentMethod;
        if (mn == null || mn.instructions == null) return null;
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) return null;
        AbstractInsnNode[] arr = mn.instructions.toArray();
        if (arr.length == 0) return null;
        Map<AbstractInsnNode, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < arr.length; i++) indexOf.put(arr[i], i);
        java.util.Set<Integer> leaders = new java.util.TreeSet<>();
        leaders.add(Integer.valueOf(0));
        for (int i = 0; i < arr.length; i++) {
            AbstractInsnNode in = arr[i];
            if (in instanceof JumpInsnNode j) {
                Integer to = indexOf.get(j.label);
                if (to != null) leaders.add(to);
                if (i + 1 < arr.length) leaders.add(Integer.valueOf(i + 1));
            } else if (in instanceof TableSwitchInsnNode t) {
                Integer d = indexOf.get(t.dflt);
                if (d != null) leaders.add(d);
                for (LabelNode l : t.labels) {
                    Integer to = indexOf.get(l);
                    if (to != null) leaders.add(to);
                }
                if (i + 1 < arr.length) leaders.add(Integer.valueOf(i + 1));
            } else if (in instanceof LookupSwitchInsnNode l) {
                Integer d = indexOf.get(l.dflt);
                if (d != null) leaders.add(d);
                for (LabelNode ll : l.labels) {
                    Integer to = indexOf.get(ll);
                    if (to != null) leaders.add(to);
                }
                if (i + 1 < arr.length) leaders.add(Integer.valueOf(i + 1));
            } else {
                int op = in.getOpcode();
                if (op == Opcodes.RETURN || op == Opcodes.ATHROW || op == Opcodes.RET
                        || (op >= Opcodes.IRETURN && op <= Opcodes.RETURN)) {
                    if (i + 1 < arr.length) leaders.add(Integer.valueOf(i + 1));
                }
            }
        }
        List<Integer> leaderList = new ArrayList<>(leaders);
        if (leaderList.size() > MAX_RD_BLOCKS) return null;
        RdBlocks rd = new RdBlocks();
        for (int b = 0; b < leaderList.size(); b++) {
            int start = leaderList.get(b).intValue();
            int end = (b + 1 < leaderList.size()) ? leaderList.get(b + 1).intValue() : arr.length;
            for (int i = start; i < end; i++) rd.blockOf.put(Integer.valueOf(i), Integer.valueOf(b));
            rd.succs.put(Integer.valueOf(b), new java.util.HashSet<Integer>());
        }
        for (int b = 0; b < leaderList.size(); b++) {
            int start = leaderList.get(b).intValue();
            int end = (b + 1 < leaderList.size()) ? leaderList.get(b + 1).intValue() : arr.length;
            if (end - 1 < start) continue;
            AbstractInsnNode last = arr[end - 1];
            java.util.Set<Integer> succ = rd.succs.get(Integer.valueOf(b));
            if (last instanceof JumpInsnNode j) {
                Integer to = indexOf.get(j.label);
                if (to != null) succ.add(Integer.valueOf(rd.blockOf.getOrDefault(to, -1)));
                if (j.getOpcode() != Opcodes.GOTO && b + 1 < leaderList.size()) {
                    succ.add(Integer.valueOf(b + 1));
                }
            } else if (last instanceof TableSwitchInsnNode t) {
                Integer d = indexOf.get(t.dflt);
                if (d != null) succ.add(Integer.valueOf(rd.blockOf.getOrDefault(d, -1)));
                for (LabelNode l : t.labels) {
                    Integer to = indexOf.get(l);
                    if (to != null) succ.add(Integer.valueOf(rd.blockOf.getOrDefault(to, -1)));
                }
            } else if (last instanceof LookupSwitchInsnNode l) {
                Integer d = indexOf.get(l.dflt);
                if (d != null) succ.add(Integer.valueOf(rd.blockOf.getOrDefault(d, -1)));
                for (LabelNode ll : l.labels) {
                    Integer to = indexOf.get(ll);
                    if (to != null) succ.add(Integer.valueOf(rd.blockOf.getOrDefault(to, -1)));
                }
            } else {
                int op = last.getOpcode();
                boolean terminal = op == Opcodes.RETURN || op == Opcodes.ATHROW || op == Opcodes.RET
                        || (op >= Opcodes.IRETURN && op <= Opcodes.RETURN);
                if (!terminal && b + 1 < leaderList.size()) succ.add(Integer.valueOf(b + 1));
            }
            succ.remove(Integer.valueOf(-1));
        }
        Map<Integer, java.util.Set<WriteDef>> genSets = new HashMap<>();
        Map<Integer, java.util.Set<Integer>> killSets = new HashMap<>();
        for (int b = 0; b < leaderList.size(); b++) {
            int start = leaderList.get(b).intValue();
            int end = (b + 1 < leaderList.size()) ? leaderList.get(b + 1).intValue() : arr.length;
            Map<Integer, WriteDef> lastWrite = new HashMap<>();
            java.util.Set<Integer> killed = new java.util.HashSet<>();
            for (int i = start; i < end; i++) {
                Integer s = writeSlots.get(arr[i]);
                if (s != null) {
                    lastWrite.put(s, new WriteDef(s.intValue(), arr[i]));
                    killed.add(s);
                }
            }
            genSets.put(Integer.valueOf(b), new java.util.HashSet<>(lastWrite.values()));
            killSets.put(Integer.valueOf(b), killed);
            rdInSets.put(Integer.valueOf(b), new java.util.HashSet<WriteDef>());
            rd.outSets.put(Integer.valueOf(b), new java.util.HashSet<WriteDef>());
        }
        Map<Integer, java.util.Set<Integer>> preds = new HashMap<>();
        for (int b = 0; b < leaderList.size(); b++) preds.put(Integer.valueOf(b), new java.util.HashSet<Integer>());
        for (Map.Entry<Integer, java.util.Set<Integer>> e : rd.succs.entrySet()) {
            for (Integer s : e.getValue()) preds.get(s).add(e.getKey());
        }
        for (int iter = 0; iter < MAX_RD_ITERATIONS; iter++) {
            boolean changed = false;
            for (int b = 0; b < leaderList.size(); b++) {
                Integer bb = Integer.valueOf(b);
                java.util.Set<WriteDef> inSet = new java.util.HashSet<>();
                for (Integer p : preds.get(bb)) inSet.addAll(rd.outSets.get(p));
                java.util.Set<WriteDef> outSet = new java.util.HashSet<>(genSets.get(bb));
                java.util.Set<Integer> killed = killSets.get(bb);
                for (WriteDef w : inSet) {
                    if (!killed.contains(Integer.valueOf(w.slot))) outSet.add(w);
                }
                if (!outSet.equals(rd.outSets.get(bb))) {
                    rd.outSets.put(bb, outSet);
                    changed = true;
                }
                if (!inSet.equals(rdInSets.get(bb))) {
                    rdInSets.put(bb, inSet);
                    changed = true;
                }
            }
            if (!changed) break;
        }
        return rd;
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
            case Opcodes.ILOAD: case Opcodes.FLOAD: case Opcodes.ALOAD:
                push(load(((VarInsnNode) in).var, in)); return;
            case Opcodes.LLOAD: case Opcodes.DLOAD:
                push(load(((VarInsnNode) in).var, in), true); return;
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
                if (arr.kind == Val.Kind.ARRAY && idx.kind == Val.Kind.NUMBER) {
                    int i = idx.numberValue.intValue();
                    if (i >= 0 && i < arr.elements.size()) arr.elements.set(i, v);
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
            case Opcodes.IADD: case Opcodes.ISUB: {
                Val b = pop(), a = pop();
                Val r = foldOrTagInt(op, a, b);
                push(enrichIntAddSub(op, a, b, r));
                return;
            }
            case Opcodes.IMUL: case Opcodes.IDIV: case Opcodes.FMUL: case Opcodes.FDIV: {
                Val b = pop(), a = pop();
                push(foldMulDiv(op, a, b));
                return;
            }
            case Opcodes.LMUL: case Opcodes.LDIV: case Opcodes.DMUL: case Opcodes.DDIV: {
                Val b = pop(), a = pop();
                push(foldMulDiv(op, a, b), true);
                return;
            }
            case Opcodes.IREM: case Opcodes.ISHL: case Opcodes.ISHR: case Opcodes.IUSHR:
            case Opcodes.IAND: case Opcodes.IOR: case Opcodes.IXOR:
            case Opcodes.FADD: case Opcodes.FSUB: case Opcodes.FREM:
                popN(2); push(Val.unknown("arithmetic")); return;
            case Opcodes.LADD: case Opcodes.LSUB:
            case Opcodes.LREM: case Opcodes.LAND: case Opcodes.LOR: case Opcodes.LXOR:
            case Opcodes.DADD: case Opcodes.DSUB: case Opcodes.DREM:
                popN(2); push(Val.unknown("arithmetic"), true); return;
            case Opcodes.LSHL: case Opcodes.LSHR: case Opcodes.LUSHR:
                popN(2); push(Val.unknown("arithmetic"), true); return;
            case Opcodes.INEG: case Opcodes.FNEG: popN(1); push(Val.unknown("negate")); return;
            case Opcodes.LNEG: case Opcodes.DNEG: popN(1); push(Val.unknown("negate"), true); return;
            case Opcodes.IINC: return;
            // umb-guimap addition: a numeric conversion is transparent to a value we're tracking
            // symbolically (a constant, a getfield-shaped/linear-expr unknown, or a generic call
            // result) — the extremely common `(int)((float) this.heat * 22.0F / this.heatMax)`
            // scaled-bar cast chain must not collapse the linear expression it wraps. A value we
            // have no symbolic handle on still collapses to the original generic "conversion".
            case Opcodes.I2L: case Opcodes.I2D: case Opcodes.F2L: case Opcodes.F2D:
                push(passthroughConversion(pop()), true); return;
            case Opcodes.L2D: case Opcodes.D2L: push(passthroughConversion(pop()), true); return;
            case Opcodes.I2F: case Opcodes.F2I: case Opcodes.I2B: case Opcodes.I2C: case Opcodes.I2S:
                push(passthroughConversion(pop())); return;
            case Opcodes.L2I: case Opcodes.L2F: case Opcodes.D2I: case Opcodes.D2F:
                push(passthroughConversion(pop())); return;
            case Opcodes.LCMP: case Opcodes.DCMPL: case Opcodes.DCMPG:
            case Opcodes.FCMPL: case Opcodes.FCMPG: {
                // GUARD-EXPRESSIONS lane fix: previously this discarded both operands outright
                // (`popN(2); push(Val.unknown("compare"))`), which is exactly why a subsequent
                // `IFLE`/`IFLT`/... guard on the result could only ever report the bare word
                // "compare" with no operands - a real, fixable extraction gap (~23% of a sampled
                // guard-description bucket per the lane brief), not evidence the guard is
                // unresolvable. `Val.compare` keeps `reason=="compare"` byte-identical (so every
                // existing `describeVal`/`classifyInt`/textual `guardDescription` output this
                // produced before is completely unchanged) while ALSO recording the two real
                // operands (`cmpLeft`/`cmpRight`) for DrawLayerScanner's new guard-expression tree
                // to recover. `a` is the first-pushed (lower) operand, `b` the second (matching the
                // same stack-order convention `describeGuard` already uses for IF_ICMPxx's own
                // two-operand text: `stack.get(size-2)` then `stack.get(size-1)`).
                Val b = pop(), a = pop();
                push(Val.compare(a, b));
                return;
            }
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
                push(Val.staticField(f.owner, f.name, f.desc), isWideDesc(f.desc));
                return;
            }
            case Opcodes.PUTSTATIC: pop(); return;
            case Opcodes.GETFIELD: {
                FieldInsnNode f = (FieldInsnNode) in;
                Val recv = pop();
                Val v = Val.unknown("getfield " + f.owner + "." + f.name);
                // umb-guimap addition: keep owner/name/desc/receiver structured (not just embedded
                // in the reason string) so a caller can walk the access chain and classify its
                // origin (GUI object / paired Container / a tile entity reached through either)
                // without re-parsing text — see DrawLayerScanner's describeSource.
                v.owner = f.owner; v.name = f.name; v.desc = f.desc; v.receiver = recv;
                push(v, isWideDesc(f.desc));
                return;
            }
            case Opcodes.PUTFIELD: popN(2); return;
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
        // builder chains: `new X().setFoo(..).setBar(..)` keeps returning the same object.
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
            // GUI-fidelity lane: Math.ceil(term) over a linear/state-shaped argument (assembler
            // `(int)Math.ceil(70.0*progress)` arrow width). Vanilla-JDK fixed semantics; only the
            // whole-term ceil is modelled, never a partially-known argument. Checked BEFORE the
            // generic static-call collapse below, which would otherwise hide the argument shape.
            if ("java/lang/Math".equals(m.owner) && "ceil".equals(m.name) && actuals.size() == 1) {
                Val a = actuals.get(0);
                LinearExpr base = a.linear != null ? a.linear
                        : (isBareGetfield(a) ? LinearExpr.baseOf(a) : null);
                if (base != null) {
                    LinearExpr merged = base.withCeil();
                    if (merged != null) { push(wrapLinear(merged, a), ret.getSize() == 2); return; }
                }
            }
            // keep the arguments: factory helpers such as
            // ItemRenderMissileGeneric.generateStandard(texture, model) carry the resources.
            push(Val.call(m.owner, m.name, actuals), ret.getSize() == 2);
            return;
        }
        // umb-guimap addition: a same-jar, zero-or-more-arg accessor called on a receiver we have
        // a symbolic handle on is the near-universal shape of a 1.7.10 scaled-progress-bar helper
        // (e.g. `this.rtg.getHeatScaled(51)` => `this.heat * 51 / this.heatMax`, confirmed with
        // javap against the real HBM jar). Re-simulate its body with the actual receiver/arguments
        // substituted instead of collapsing straight to a generic unknown, bounded by MAX_INLINE_DEPTH.
        if (jar != null && depth < MAX_INLINE_DEPTH && receiver != null) {
            Val inlined = tryInlineAccessor(m.owner, m.name, m.desc, receiver, actuals, op);
            if (inlined != null) { push(inlined, ret.getSize() == 2); return; }
        }
        Val v = Val.unknown("result of " + shortName(m.owner) + "." + m.name + "()");
        // umb-guimap addition: keep owner/name/desc/receiver/callArgs structured, mirroring the
        // GETFIELD case above, so a caller with jar access (DrawLayerScanner) can attempt the same
        // inlining lazily even when this MethodSim instance itself has no jar, and so origin-chain
        // walking works uniformly whether or not inlining happened here.
        v.owner = m.owner; v.name = m.name; v.desc = m.desc; v.receiver = receiver; v.callArgs = actuals;
        // NOTEXTURE-GAP lane: the exact invoke opcode, so a later on-demand
        // resolveAccessorForTextureClassification call can tell a true virtual/interface dispatch
        // (eligible for the this-receiver runtime-type search) from an INVOKESPECIAL/INVOKESTATIC
        // call (never eligible - no dispatch to resolve, owner is already the exact target).
        v.invokeOp = op;
        push(v, ret.getSize() == 2);
    }

    /**
     * Re-simulates {@code owner.name(desc)} — found by walking {@code owner}'s jar-declared
     * superclass chain, same principle as {@code GuiClassAnalyzer.findDeclaringClass} — with
     * {@code receiver} bound to its {@code this} and {@code actuals} bound to its parameters, and
     * returns the single value every {@code IRETURN}/{@code LRETURN}/{@code FRETURN}/{@code DRETURN}/
     * {@code ARETURN} produced, so an outer caller can classify it exactly as if it were inlined.
     * Returns {@code null} (never guesses) when: the method isn't declared anywhere in the jar (a
     * vanilla/library call), it's abstract/bodyless, or it has zero or more than one return site.
     *
     * <p>NOTEXTURE-GAP lane: ONLY when {@link #extendedAccessorInlining} is true (never for the
     * automatic, every-call-site inlining {@code invoke()} uses — see that field's own javadoc for
     * the regression this avoids) — a virtual/interface call on a {@code this} receiver instead
     * starts the search at {@link #selfClassInternal} (the ACTUAL runtime type this scan is for)
     * rather than at {@code owner} (only the compile-time type recorded at the call site — see
     * {@link #selfClassInternal}'s own javadoc for why this can only find a closer, still-correct
     * override, never a wrong one). Every other call shape (a field/another object as receiver, an
     * {@code INVOKESPECIAL}/{@code INVOKESTATIC} call, or no {@link #selfClassInternal} known) keeps
     * the exact original owner-only walk regardless of {@link #extendedAccessorInlining}.
     */
    private Val tryInlineAccessor(String owner, String name, String desc, Val receiver, List<Val> actuals, int op) {
        if ("<init>".equals(name) || "<clinit>".equals(name)) return null;
        boolean virtualDispatch = extendedAccessorInlining
                && (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKEINTERFACE)
                && receiver.kind == Val.Kind.THIS && selfClassInternal != null;
        ClassNode targetClass = null; MethodNode targetMethod = null;
        String cur = virtualDispatch ? selfClassInternal : owner;
        Set<String> seen = new HashSet<>();
        while (cur != null && seen.add(cur)) {
            ClassNode cn = jar.cls(cur);
            if (cn == null) break;
            for (MethodNode m : cn.methods) {
                if (m.name.equals(name) && m.desc.equals(desc)) { targetClass = cn; targetMethod = m; break; }
            }
            if (targetMethod != null) break;
            cur = cn.superName;
        }
        if (targetMethod == null || targetMethod.instructions == null || targetMethod.instructions.size() == 0
                || (targetMethod.access & Opcodes.ACC_ABSTRACT) != 0) return null;

        Map<Integer, Val> seed = new HashMap<>();
        int slot = 0;
        boolean isStatic = (targetMethod.access & Opcodes.ACC_STATIC) != 0;
        if (!isStatic) { seed.put(0, receiver); slot = 1; }
        Type[] pTypes = Type.getArgumentTypes(targetMethod.desc);
        for (int i = 0; i < pTypes.length; i++) {
            Val a = i < actuals.size() ? actuals.get(i) : Val.unknown("missing inlined accessor argument");
            seed.put(slot, a);
            slot += pTypes[i].getSize();
        }

        List<Val> returns = new ArrayList<>();
        // selfClassInternal/extendedAccessorInlining propagated unchanged: `this` never changes
        // identity/runtime type while inlining walks up through ancestor method bodies, and a
        // texture-classification-only resolution session stays texture-classification-only through
        // every nested hop it takes (see both fields' own javadoc).
        MethodSim inner = new MethodSim(jar, depth + 1, selfClassInternal, extendedAccessorInlining);
        inner.exec(targetClass, targetMethod, seed, (insn, s, l) -> {
            int iop = insn.getOpcode();
            // ARETURN (NOTEXTURE-GAP lane addition, extendedAccessorInlining only): an
            // object-reference return - e.g. a `getTexture(): ResourceLocation`-shaped accessor - is
            // exactly as inlineable as the four numeric return opcodes this already handled; nothing
            // about "exactly one return site" is specific to numbers. Gated because a call that
            // returns a value THIS interpreter can't fully resolve inside its own body (e.g. an
            // array read at a non-constant index) is often a WORSE Val shape for a guard/label
            // consumer than the plain "result of X.y()" placeholder it replaces - see the field's
            // own javadoc.
            boolean isReturn = iop == Opcodes.IRETURN || iop == Opcodes.LRETURN || iop == Opcodes.FRETURN
                    || iop == Opcodes.DRETURN || (extendedAccessorInlining && iop == Opcodes.ARETURN);
            if (isReturn && !s.isEmpty()) {
                returns.add(s.get(s.size() - 1));
            }
        });
        return returns.size() == 1 ? returns.get(0) : null; // 0 or >1 return sites: don't guess
    }

    private Val arrayGet(Val arr, Val idx) {
        if (arr.kind == Val.Kind.ARRAY && idx.kind == Val.Kind.NUMBER) {
            int i = idx.numberValue.intValue();
            if (i >= 0 && i < arr.elements.size()) return arr.elements.get(i);
        }
        return Val.unknown("array element with non-constant index or unknown array");
    }

    /**
     * IADD/ISUB-only enhancement (umb-guimap addition, not in umb-rendermap's copy): fold two
     * known constants outright, and when exactly one side is a known constant and the other is
     * an {@link Val.Kind#UNKNOWN} value carrying a {@code "getfield ..."} reason (a GUI's
     * {@code guiLeft}/{@code guiTop}/{@code xSize}/{@code ySize} read is exactly this shape),
     * append a {@code "+N"}/{@code "-N"} suffix to that reason instead of collapsing to a bare
     * {@code "arithmetic"} unknown. This lets a caller that already knows a field's constant
     * value (read straight out of the constructor, never guessed) substitute it back in and
     * recover an absolute pixel offset such as {@code drawGuiContainerForegroundLayer}'s
     * {@code ySize - 96 + 2} label position. Any other shape (both sides unknown, a non-getfield
     * unknown, etc.) still collapses to the original generic {@code "arithmetic"} unknown.
     */
    private static Val foldOrTagInt(int op, Val a, Val b) {
        if (a.kind == Val.Kind.NUMBER && b.kind == Val.Kind.NUMBER) {
            int ai = a.numberValue.intValue(), bi = b.numberValue.intValue();
            return Val.number(op == Opcodes.IADD ? ai + bi : ai - bi);
        }
        Val sym = null; int n = 0; boolean symIsLeft = true;
        if (b.kind == Val.Kind.NUMBER && a.kind == Val.Kind.UNKNOWN && a.reason != null
                && a.reason.startsWith("getfield ")) {
            sym = a; n = b.numberValue.intValue(); symIsLeft = true; // a (op) n
        } else if (a.kind == Val.Kind.NUMBER && b.kind == Val.Kind.UNKNOWN && b.reason != null
                && b.reason.startsWith("getfield ") && op == Opcodes.IADD) {
            sym = b; n = a.numberValue.intValue(); symIsLeft = false; // n + b (subtraction with the
            // symbolic side second, e.g. `n - field`, is rare enough in practice to leave unmodelled)
        }
        if (sym != null) {
            if (op == Opcodes.ISUB && !symIsLeft) return Val.unknown("arithmetic"); // n - field: not modelled
            // umb-guimap fix: normalise to one signed delta before formatting instead of always
            // prefixing "+"/"-" — `n` itself can be negative (e.g. `guiLeft + (-5)`, an IADD with a
            // negative literal), and blindly prepending "+" produced a malformed "+-5" suffix that
            // ExprEval's delta regex (rightly) refuses to parse, silently discarding the offset.
            int effective = op == Opcodes.ISUB ? -n : n;
            String suffix = effective >= 0 ? "+" + effective : String.valueOf(effective);
            Val r = Val.unknown(sym.reason + suffix);
            // umb-guimap addition: propagate the structured owner/name/receiver forward too, not
            // just the human-readable reason string, so a chain like `this.container.field + 5`
            // still carries enough to walk its access path after the delta folds in.
            r.owner = sym.owner; r.name = sym.name; r.desc = sym.desc; r.receiver = sym.receiver;
            return r;
        }
        return Val.unknown("arithmetic");
    }

    /**
     * umb-guimap addition: extends the IADD/ISUB result with {@link LinearExpr} tracking whenever
     * one operand is already known to vary over exactly one runtime field (has {@code .linear} set,
     * or is itself a getfield-shaped unknown) and the other is either a compile-time constant (a
     * plain offset) or a {@code guiLeft}/{@code guiTop}(+delta) panel-relative quantity (the very
     * common {@code guiTop + 61 - amt} "grow a bar from a fixed anchor" shape). Returns {@code r}
     * unchanged when neither shape applies, so a plain arithmetic collapse is untouched.
     */
    private static Val enrichIntAddSub(int op, Val a, Val b, Val r) {
        if (r.kind == Val.Kind.NUMBER) return r; // two known constants: nothing to enrich
        Integer aPanelDelta = panelDeltaOf(a), bPanelDelta = panelDeltaOf(b);
        boolean aState = isStateShaped(a) && aPanelDelta == null;
        boolean bState = isStateShaped(b) && bPanelDelta == null;

        if (aPanelDelta != null && bState) { // guiX(+d) +/- field
            LinearExpr base = b.linear != null ? b.linear : LinearExpr.baseOf(b);
            LinearExpr merged = base.withPanelBase(panelAxisOf(a), aPanelDelta, op == Opcodes.ISUB ? -1 : 1);
            return withLinear(r, merged, b);
        }
        if (bPanelDelta != null && aState && op == Opcodes.IADD) { // field + guiX(+d)
            LinearExpr base = a.linear != null ? a.linear : LinearExpr.baseOf(a);
            LinearExpr merged = base.withPanelBase(panelAxisOf(b), bPanelDelta, 1);
            return withLinear(r, merged, a);
        }
        if (aState && b.kind == Val.Kind.NUMBER) { // field +/- const
            LinearExpr base = a.linear != null ? a.linear : LinearExpr.baseOf(a);
            LinearExpr merged = base.withOffset(op == Opcodes.ISUB ? -b.numberValue.intValue() : b.numberValue.intValue());
            return withLinear(r, merged, a);
        }
        if (a.kind == Val.Kind.NUMBER && bState && op == Opcodes.IADD) { // const + field
            LinearExpr base = b.linear != null ? b.linear : LinearExpr.baseOf(b);
            LinearExpr merged = base.withOffset(a.numberValue.intValue());
            return withLinear(r, merged, b);
        }
        if (a.kind == Val.Kind.NUMBER && bState && op == Opcodes.ISUB) { // const - field (e.g. an
            // inverse/"remaining" quantity, or the `10 + (51 - amt)` shape seen combined with a
            // bar-height term in the real HBM jar) — withOffset can't express this since it leaves
            // the field's sign untouched; withConstBase sets both together.
            LinearExpr base = b.linear != null ? b.linear : LinearExpr.baseOf(b);
            LinearExpr merged = base.withConstBase(a.numberValue.intValue(), -1);
            if (merged != null) return withLinear(r, merged, b);
        }
        return r;
    }

    /** True for anything {@link #enrichIntAddSub} may treat as "varies over one runtime field":
     *  already has a linear expr, or is a bare getfield-shaped/generic-call-result unknown. */
    private static boolean isStateShaped(Val v) {
        if (v.linear != null) return true;
        return v.kind == Val.Kind.UNKNOWN && v.owner != null && v.reason != null
                && (v.reason.startsWith("getfield ") || v.reason.startsWith("result of "));
    }

    /** Non-null (the accumulated delta, 0 if bare) exactly when {@code v} is a {@code guiLeft}/
     *  {@code guiTop} read, possibly already folded with a constant offset via {@link #foldOrTagInt}. */
    private static Integer panelDeltaOf(Val v) {
        if (v.kind != Val.Kind.UNKNOWN || v.owner == null || v.name == null) return null;
        if (!Vanilla.F_GUILEFT.equals(v.name) && !Vanilla.F_GUITOP.equals(v.name)) return null;
        ExprEval.Decoded d = ExprEval.decode(v.reason);
        return d != null ? d.delta : Integer.valueOf(0);
    }

    private static String panelAxisOf(Val v) { return Vanilla.F_GUILEFT.equals(v.name) ? "guiLeft" : "guiTop"; }

    /** Wraps {@code merged} (or falls back to {@code r} unchanged if the merge was rejected, e.g.
     *  two panel bases or two divisors already set) into a fresh UNKNOWN carrying the same
     *  owner/name/receiver as the state-shaped operand, for further chaining. */
    private static Val withLinear(Val r, LinearExpr merged, Val stateOperand) {
        if (merged == null) return r;
        Val out = Val.unknown(r.reason);
        out.owner = merged.fieldOwner; out.name = merged.fieldName; out.desc = stateOperand.desc;
        out.receiver = merged.fieldReceiver;
        out.linear = merged;
        return out;
    }

    /**
     * umb-guimap addition: folds IMUL/IDIV (and their L/F/D-typed siblings) into a {@link LinearExpr}
     * when exactly one operand is a compile-time constant and the other varies over one runtime
     * field (bare, or already {@code .linear}-tracked), or when both operands are bare fields and
     * the operation is a division (the {@code field / maxField} divisor-is-itself-a-field shape).
     * Folds two known constants outright. Falls back to the original generic "arithmetic" unknown
     * for every other shape (field*field multiply, dividing a constant by a field, a second divisor
     * once one is already fixed, ...) — never guesses.
     */
    private static Val foldMulDiv(int op, Val a, Val b) {
        boolean mul = op == Opcodes.IMUL || op == Opcodes.LMUL || op == Opcodes.FMUL || op == Opcodes.DMUL;
        if (a.kind == Val.Kind.NUMBER && b.kind == Val.Kind.NUMBER) {
            double av = a.numberValue.doubleValue(), bv = b.numberValue.doubleValue();
            double res = mul ? av * bv : (bv == 0 ? Double.NaN : av / bv);
            boolean intLike = op == Opcodes.IMUL || op == Opcodes.IDIV;
            return intLike ? Val.number((int) res) : Val.number(res);
        }
        boolean aState = isStateShaped(a), bState = isStateShaped(b);
        if (aState && b.kind == Val.Kind.NUMBER) {
            LinearExpr base = a.linear != null ? a.linear : LinearExpr.baseOf(a);
            LinearExpr merged = mul ? base.withMul(b.numberValue.doubleValue()) : base.withDiv(b.numberValue.doubleValue());
            if (merged != null) return wrapLinear(merged, a);
        } else if (mul && a.kind == Val.Kind.NUMBER && bState
                && (b.linear != null || isBareGetfield(b))) {
            // GUI-fidelity lane: multiplication commutes (`70.0 * progress` assembler arrow
            // width) - same linear shape as the field-first order, never a guess. Bare-getfield
            // only: a call-result operand has no field identity to bind.
            LinearExpr base = b.linear != null ? b.linear : LinearExpr.baseOf(b);
            LinearExpr merged = base.withMul(a.numberValue.doubleValue());
            if (merged != null) return wrapLinear(merged, b);
        } else if (!mul && a.kind == Val.Kind.NUMBER && bState) {
            return Val.unknown("arithmetic"); // const / field: reciprocal shape, not modelled
        } else if (!mul && aState && isMaxFloorCall(b)) {
            // GUI-fidelity lane: `fieldA * K / max(fieldB, C)` divide-by-guarded-maximum
            // (furnace progress/burnTime arrows). Math.max is vanilla-JDK with fixed
            // semantics; the floor constant and the bare getfield divisor are both
            // compile-time evidence. Math.min as a divisor (a cap, not a floor) stays
            // unresolved - no invented meaning.
            LinearExpr base = a.linear != null ? a.linear : LinearExpr.baseOf(a);
            Val[] parts = maxFloorParts(b);
            LinearExpr merged = base.withDivField(parts[0]);
            if (merged != null) merged = merged.withDivFloor(parts[1].numberValue.doubleValue());
            if (merged != null) return wrapLinear(merged, a);
        } else if (!mul && aState && b.kind == Val.Kind.STATIC_FIELD && b.owner != null && b.name != null) {
            // GUI-fidelity lane: `fieldA * K / ClassName.STATIC` (turbine
            // `power*scale/maxPower` via inlined getPowerScaled - maxPower is a static long on
            // the tile class). The host snapshots it off the live tile like any field
            // (reflection finds statics too); if the static lives outside the tile's
            // hierarchy the read fails closed to absent at runtime, never a wrong value.
            LinearExpr base = a.linear != null ? a.linear : LinearExpr.baseOf(a);
            LinearExpr merged = base.withDivField(b);
            if (merged != null) return wrapLinear(merged, a);
        } else if (!mul && aState && bState && b.linear == null) {
            // b must be a BARE field (no scale of its own) — `field / (otherField * k)` isn't a
            // shape this module models; better to report it unresolved than drop otherField's `k`.
            LinearExpr base = a.linear != null ? a.linear : LinearExpr.baseOf(a);
            LinearExpr merged = base.withDivField(b);
            if (merged != null) return wrapLinear(merged, a);
        }
        return Val.unknown("arithmetic");
    }

    private static Val wrapLinear(LinearExpr merged, Val stateOperand) {
        Val out = Val.unknown("arithmetic");
        out.owner = merged.fieldOwner; out.name = merged.fieldName; out.desc = stateOperand.desc;
        out.receiver = merged.fieldReceiver;
        out.linear = merged;
        return out;
    }

    /**
     * GUI-fidelity lane: true for a {@code Math.max(x, C)} / {@code Math.max(C, x)} call result
     * where C is a compile-time constant and x is a bare getfield read (no scale of its own -
     * {@code max(field*k, C)} is not a shape this module models). {@code Math.min} is deliberately
     * excluded: as a divisor it is a cap, not a floor, with no honest host meaning.
     */
    private static boolean isMaxFloorCall(Val b) {
        return maxFloorParts(b) != null;
    }

    /** Splits a max-floor call into [fieldVal, constVal], or null when the shape differs. */
    private static Val[] maxFloorParts(Val b) {
        if (b == null || b.kind != Val.Kind.CALL) return null;
        if (!"java/lang/Math".equals(b.owner) || !"max".equals(b.name)) return null;
        if (b.ctorArgs == null || b.ctorArgs.size() != 2) return null;
        Val x = b.ctorArgs.get(0), y = b.ctorArgs.get(1);
        if (isBareGetfield(x) && y.kind == Val.Kind.NUMBER) return new Val[]{x, y};
        if (isBareGetfield(y) && x.kind == Val.Kind.NUMBER) return new Val[]{y, x};
        return null;
    }

    private static boolean isBareGetfield(Val v) {
        return v != null && v.kind == Val.Kind.UNKNOWN && v.linear == null
                && v.owner != null && v.name != null && v.reason != null
                && v.reason.startsWith("getfield ");
    }

    /** umb-guimap addition: a numeric conversion is transparent to a value tracked symbolically
     *  (constant, linear/getfield-shaped unknown, or generic call result); anything else still
     *  collapses to a generic "conversion" unknown exactly as before. */
    private static Val passthroughConversion(Val v) {
        if (v.kind == Val.Kind.NUMBER) return v;
        if (v.kind == Val.Kind.UNKNOWN && (v.linear != null || (v.owner != null && v.reason != null
                && (v.reason.startsWith("getfield ") || v.reason.startsWith("result of "))))) return v;
        return Val.unknown("conversion");
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
