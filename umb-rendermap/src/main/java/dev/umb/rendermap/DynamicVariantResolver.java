package dev.umb.rendermap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the handful of dynamic-renderer shapes that {@link RendererAnalyzer} correctly
 * reports as "dynamic" (the renderer reads a per-instance/per-array-slot field, not a static
 * holder) but that are in fact a compile-time-constant table indexed by
 * {@code ItemStack.getItemDamage()} (SRG {@code func_77960_j}). Because the table size and every
 * entry's contents are fixed at compile time, and because umb-hostagent now registers one item id
 * per metadata damage value instead of collapsing them onto the base id, each table slot maps
 * onto one real, resolvable (model, texture, groups) row.
 *
 * Every value produced here comes from re-running {@link MethodSim} over real bytecode (the
 * table-building method and the element class's own {@code <init>}), never from a literal baked
 * into this file. A slot whose bytecode does not reduce to a known value is left absent — the
 * caller must not fabricate one.
 *
 * <h2>Family A — enum table via {@code EnumUtil.grabEnumSafely(Class, int)}</h2>
 * {@code ItemBatteryPack.EnumBatteryPack}: each constant's {@code <clinit>} call site pins its
 * name/ordinal/extra-arg literals; its instance {@code <init>} builds a {@code ResourceLocation}
 * field from those literals via a {@code StringBuilder} chain (see the {@code builtString}
 * support added to {@link Val}/{@link MethodSim} for this).
 *
 * <h2>Family B — a static array of instances built by index (AASTORE)</h2>
 * {@code ItemAmmoHIMARS.itemTypes}: {@code init()} does
 * {@code itemTypes[k] = new ItemAmmoHIMARS$N(this, "name", "key", modelType)}, an anonymous
 * subclass of the abstract {@code HIMARSRocket} that only overrides {@code onImpact} and forwards
 * every constructor argument, unchanged and in order, to {@code HIMARSRocket}'s own
 * {@code <init>} — verified structurally by {@link #isTrivialForwardingCtor}, not assumed.
 *
 * <h2>Family C — a plain {@code damage == 0} branch}</h2>
 * {@code ItemRenderLibrary$77} (gear_large): {@code getItemDamage()==0 ? texA : texB} with no
 * table at all — only two branches, each a {@code GETSTATIC} of a resolved holder field.
 */
public final class DynamicVariantResolver {
    private DynamicVariantResolver() {}

    // ==================================================================== Family A: enum table

    /** One {@code NEW enumClass ... INVOKESPECIAL <init> ... PUTSTATIC name} site in an enum's {@code <clinit>}. */
    public static final class EnumConst {
        public final String name;
        public final int ordinal;
        public final String ctorDesc;
        public final List<Val> ctorArgs;
        EnumConst(String name, int ordinal, String ctorDesc, List<Val> ctorArgs) {
            this.name = name; this.ordinal = ordinal; this.ctorDesc = ctorDesc; this.ctorArgs = ctorArgs;
        }
    }

    /**
     * Reads every enum constant an enum class's {@code <clinit>} constructs, in declaration
     * (= ordinal) order, together with the literal/resolved arguments passed to whichever
     * {@code <init>} overload built it.
     */
    public static List<EnumConst> readEnumClinit(ClassNode enumCn) {
        List<EnumConst> out = new ArrayList<>();
        MethodNode clinit = findMethod(enumCn, "<clinit>");
        if (clinit == null) return out;
        String selfDesc = "L" + enumCn.name + ";";
        String[] pendingDesc = new String[1];
        @SuppressWarnings("unchecked")
        List<Val>[] pendingArgs = new List[1];
        MethodSim.run(enumCn, clinit, (insn, stack, locals) -> {
            if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode min = (MethodInsnNode) insn;
                if (enumCn.name.equals(min.owner) && "<init>".equals(min.name)) {
                    int argc = Type.getArgumentTypes(min.desc).length;
                    if (stack.size() >= argc) {
                        pendingDesc[0] = min.desc;
                        pendingArgs[0] = new ArrayList<>(stack.subList(stack.size() - argc, stack.size()));
                    }
                }
            } else if (insn.getOpcode() == Opcodes.PUTSTATIC) {
                FieldInsnNode f = (FieldInsnNode) insn;
                if (enumCn.name.equals(f.owner) && selfDesc.equals(f.desc) && pendingDesc[0] != null) {
                    out.add(new EnumConst(f.name, out.size(), pendingDesc[0], pendingArgs[0]));
                    pendingDesc[0] = null;
                    pendingArgs[0] = null;
                }
            }
        });
        return out;
    }

    /**
     * Re-simulates the {@code <init>} overload {@code ec} used, with its parameter slots bound to
     * the exact literal/resolved values captured from the {@code <clinit>} call site, and returns
     * whatever value the body stores into {@code instanceFieldName}. When that overload only
     * delegates to another {@code this(...)} constructor (common for enums with several source
     * constructors funnelling into one field-assigning one, as in {@code EnumBatteryPack}), the
     * delegation is followed with the arguments actually passed at that call site — themselves
     * resolved as far as {@link MethodSim} can take them — so field assignments in the *real*
     * constructor are still found. Returns {@code null} if no reachable constructor assigns the
     * field to something resolvable.
     */
    public static Val resolveInstanceField(ClassNode declaringCn, EnumConst ec, String instanceFieldName) {
        // Prefer the overload that assigns the field *directly*, fed with the invoked overload's
        // own leading arguments (an enum's constructor overloads always share a name/ordinal/...
        // prefix). This sidesteps a real MethodSim limitation: a delegating overload that computes
        // one of its *other* trailing arguments with a ternary (`cond ? a : b`) crosses a jump
        // target mid-expression, and MethodSim conservatively clears the whole operand stack at
        // every jump target — which would also wipe the earlier-pushed, perfectly-constant
        // name/ordinal/string args before they reach the delegate's invokespecial. Going straight
        // to the assigning constructor avoids that branch entirely.
        MethodNode assigning = findFieldAssigningCtor(declaringCn, instanceFieldName);
        if (assigning != null) {
            List<Val> mapped = mapArgsByCommonPrefix(ec.ctorDesc, ec.ctorArgs, assigning.desc);
            if (!mapped.isEmpty()) {
                Val direct = runInitAndCapture(declaringCn, assigning, mapped, instanceFieldName);
                if (direct != null) return direct;
            }
        }
        MethodNode init = findInit(declaringCn, ec.ctorDesc);
        if (init == null) return null;
        return chaseCtorAndCapture(declaringCn, init, ec.ctorArgs, instanceFieldName, 0);
    }

    /** The {@code <init>} overload whose OWN body contains {@code PUTFIELD declaringCn.fieldName}. */
    private static MethodNode findFieldAssigningCtor(ClassNode cn, String fieldName) {
        for (MethodNode m : cn.methods) {
            if (!"<init>".equals(m.name)) continue;
            for (AbstractInsnNode in : m.instructions.toArray()) {
                if (in.getOpcode() == Opcodes.PUTFIELD) {
                    FieldInsnNode f = (FieldInsnNode) in;
                    if (cn.name.equals(f.owner) && fieldName.equals(f.name)) return m;
                }
            }
        }
        return null;
    }

    /**
     * Maps {@code fromArgs} (captured against {@code fromDesc}) onto {@code toDesc}'s parameter
     * list by common leading-type prefix, stopping at the first position where the two
     * descriptors' argument types diverge (or either runs out). Never guesses past that point.
     */
    private static List<Val> mapArgsByCommonPrefix(String fromDesc, List<Val> fromArgs, String toDesc) {
        Type[] fromT = Type.getArgumentTypes(fromDesc);
        Type[] toT = Type.getArgumentTypes(toDesc);
        int n = Math.min(Math.min(fromT.length, toT.length), fromArgs.size());
        List<Val> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!fromT[i].equals(toT[i])) break;
            out.add(fromArgs.get(i));
        }
        return out;
    }

    private static Val chaseCtorAndCapture(ClassNode owner, MethodNode init, List<Val> argVals,
                                            String fieldName, int depth) {
        if (depth > 4) return null;
        Val[] captured = new Val[1];
        String[] delegateDesc = new String[1];
        @SuppressWarnings("unchecked")
        List<Val>[] delegateArgs = new List[1];
        MethodSim.run(owner, init, seedParams(init, argVals), (insn, stack, locals) -> {
            if (insn.getOpcode() == Opcodes.PUTFIELD) {
                FieldInsnNode f = (FieldInsnNode) insn;
                if (owner.name.equals(f.owner) && fieldName.equals(f.name) && !stack.isEmpty())
                    captured[0] = stack.get(stack.size() - 1);
            } else if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode m = (MethodInsnNode) insn;
                if ("<init>".equals(m.name) && owner.name.equals(m.owner) && !m.desc.equals(init.desc)) {
                    int argc = Type.getArgumentTypes(m.desc).length;
                    if (stack.size() >= argc) {
                        delegateDesc[0] = m.desc;
                        delegateArgs[0] = new ArrayList<>(stack.subList(stack.size() - argc, stack.size()));
                    }
                }
            }
        });
        if (captured[0] != null) return captured[0];
        if (delegateDesc[0] != null) {
            MethodNode delegate = findInit(owner, delegateDesc[0]);
            if (delegate != null) return chaseCtorAndCapture(owner, delegate, delegateArgs[0], fieldName, depth + 1);
        }
        return null;
    }

    /**
     * Evaluates a no-arg boolean predicate of the exact shape
     * {@code return this.ordinal() <cmp> OTHER_CONSTANT.ordinal();} for one enum constant, given
     * the name->ordinal table already read from {@code <clinit>}. Returns {@code null} when the
     * method body is not this shape (never guesses a boolean).
     */
    public static Boolean evalOrdinalThresholdPredicate(ClassNode enumCn, String predicateMethodName,
                                                          Map<String, Integer> nameToOrdinal, int selfOrdinal) {
        MethodNode mn = findMethod(enumCn, predicateMethodName);
        if (mn == null) return null;
        AbstractInsnNode[] ins = realInsns(mn);
        for (int i = 0; i < ins.length; i++) {
            if (ins[i].getOpcode() != Opcodes.GETSTATIC) continue;
            FieldInsnNode gf = (FieldInsnNode) ins[i];
            if (!enumCn.name.equals(gf.owner) || !nameToOrdinal.containsKey(gf.name)) continue;
            // expect: ALOAD 0 ; INVOKEVIRTUAL ordinal ; GETSTATIC other ; INVOKEVIRTUAL ordinal ; IF_ICMPxx
            if (i < 2 || i + 2 >= ins.length) continue;
            if (!(ins[i - 2] instanceof VarInsnNode v0) || v0.getOpcode() != Opcodes.ALOAD || v0.var != 0) continue;
            if (!isOrdinalCall(ins[i - 1])) continue;
            if (!isOrdinalCall(ins[i + 1])) continue;
            int cmpOp = ins[i + 2].getOpcode();
            if (!isIntCompare(cmpOp)) continue;
            int other = nameToOrdinal.get(gf.name);
            boolean jumpTaken = compare(cmpOp, selfOrdinal, other);
            // shape: IF_ICMPxx L1 ; ICONST_a ; GOTO L2 ; L1: ICONST_b ; L2: IRETURN
            Boolean fallthroughVal = literalBooleanAfterJump(ins, i + 2, false);
            Boolean targetVal = literalBooleanAfterJump(ins, i + 2, true);
            if (fallthroughVal == null || targetVal == null) return null;
            return jumpTaken ? targetVal : fallthroughVal;
        }
        return null;
    }

    private static boolean isOrdinalCall(AbstractInsnNode in) {
        return in.getOpcode() == Opcodes.INVOKEVIRTUAL && "ordinal".equals(((MethodInsnNode) in).name);
    }
    private static boolean isIntCompare(int op) {
        return op == Opcodes.IF_ICMPEQ || op == Opcodes.IF_ICMPNE || op == Opcodes.IF_ICMPLT
                || op == Opcodes.IF_ICMPGE || op == Opcodes.IF_ICMPGT || op == Opcodes.IF_ICMPLE;
    }
    private static boolean compare(int op, int a, int b) {
        return switch (op) {
            case Opcodes.IF_ICMPEQ -> a == b;
            case Opcodes.IF_ICMPNE -> a != b;
            case Opcodes.IF_ICMPLT -> a < b;
            case Opcodes.IF_ICMPGE -> a >= b;
            case Opcodes.IF_ICMPGT -> a > b;
            case Opcodes.IF_ICMPLE -> a <= b;
            default -> false;
        };
    }

    /**
     * Given the index of an {@code IF_ICMPxx}, reads the boolean literal from either the
     * fallthrough path ({@code wantTargetBranch=false}: the very next instruction, expected to be
     * {@code ICONST_0}/{@code ICONST_1}) or the jump target's path ({@code wantTargetBranch=true}:
     * the instruction at the jump label). Returns {@code null} if the shape does not match.
     */
    private static Boolean literalBooleanAfterJump(AbstractInsnNode[] ins, int ifIdx, boolean wantTargetBranch) {
        if (!(ins[ifIdx] instanceof org.objectweb.asm.tree.JumpInsnNode jmp)) return null;
        AbstractInsnNode probe;
        if (!wantTargetBranch) {
            probe = ifIdx + 1 < ins.length ? ins[ifIdx + 1] : null;
        } else {
            probe = jmp.label.getNext();
            while (probe != null && isNoOp(probe)) probe = probe.getNext();
        }
        if (probe == null) return null;
        int op = probe.getOpcode();
        if (op == Opcodes.ICONST_0) return Boolean.FALSE;
        if (op == Opcodes.ICONST_1) return Boolean.TRUE;
        return null;
    }
    private static boolean isNoOp(AbstractInsnNode in) {
        return in instanceof org.objectweb.asm.tree.LabelNode || in instanceof org.objectweb.asm.tree.LineNumberNode
                || in instanceof org.objectweb.asm.tree.FrameNode;
    }

    // ================================================================== Family B: array table

    public static final class ArrayEntry {
        public final int index;
        public final Val value; // NEW_OBJ of the concrete (possibly anonymous-subclass) element type
        ArrayEntry(int index, Val value) { this.index = index; this.value = value; }
    }

    /** Every {@code ownerField[k] = new Foo(...)} site found in {@code ownerCn}'s own methods. */
    public static List<ArrayEntry> readArrayPopulation(ClassNode ownerCn, String arrayFieldName) {
        List<ArrayEntry> out = new ArrayList<>();
        for (MethodNode mn : ownerCn.methods) {
            MethodSim.run(ownerCn, mn, (insn, stack, locals) -> {
                if (insn.getOpcode() != Opcodes.AASTORE || stack.size() < 3) return;
                Val arr = stack.get(stack.size() - 3);
                Val idx = stack.get(stack.size() - 2);
                Val val = stack.get(stack.size() - 1);
                if (arr.kind == Val.Kind.STATIC_FIELD && ownerCn.name.equals(arr.owner)
                        && arrayFieldName.equals(arr.name)
                        && idx.kind == Val.Kind.NUMBER && val.kind == Val.Kind.NEW_OBJ) {
                    out.add(new ArrayEntry(idx.numberValue.intValue(), val));
                }
            });
        }
        return out;
    }

    /**
     * True when {@code sub}'s constructor does nothing but forward every argument, unchanged and
     * in the same order, to a super/sibling constructor with descriptor {@code targetDesc} — the
     * shape javac emits for an anonymous subclass that adds no fields of its own. Verified by
     * reading the bytecode, not assumed from the class being anonymous.
     */
    public static boolean isTrivialForwardingCtor(ClassNode subCn, String subCtorDesc, String targetOwner,
                                                   String targetDesc) {
        if (!subCtorDesc.equals(targetDesc)) return false; // this lane only trusts an identical shape
        MethodNode init = findInit(subCn, subCtorDesc);
        if (init == null) return false;
        AbstractInsnNode[] ins = realInsns(init);
        int i = 0;
        // javac sometimes has an anonymous subclass re-store an outer-instance parameter into its
        // own synthetic field *before* calling super — harmless, and does not change any value
        // that reaches the target constructor, so skip any number of these first.
        while (i + 2 < ins.length
                && ins[i] instanceof VarInsnNode a0 && a0.getOpcode() == Opcodes.ALOAD && a0.var == 0
                && ins[i + 1] instanceof VarInsnNode && ins[i + 1].getOpcode() == Opcodes.ALOAD
                && ins[i + 2].getOpcode() == Opcodes.PUTFIELD) {
            i += 3;
        }
        Type[] params = Type.getArgumentTypes(subCtorDesc);
        int expectedSlot = 0; // slot 0 = this
        for (int p = 0; p <= params.length; p++, i++) {
            if (i >= ins.length || !(ins[i] instanceof VarInsnNode v)) return false;
            if (v.var != expectedSlot) return false;
            expectedSlot += (p == 0) ? 1 : params[p - 1].getSize();
        }
        if (i >= ins.length || !(ins[i] instanceof MethodInsnNode call) || call.getOpcode() != Opcodes.INVOKESPECIAL
                || !"<init>".equals(call.name) || !call.desc.equals(targetDesc) || !call.owner.equals(targetOwner))
            return false;
        i++;
        return i < ins.length && ins[i].getOpcode() == Opcodes.RETURN;
    }

    // ============================================================== shared: run <init>, capture

    /** slot -> Val map matching {@code mn}'s own parameter layout, for {@link MethodSim}'s seeded-run entry point. */
    static Map<Integer, Val> seedParams(MethodNode mn, List<Val> argVals) {
        Map<Integer, Val> out = new LinkedHashMap<>();
        boolean isStatic = (mn.access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        Type[] params = Type.getArgumentTypes(mn.desc);
        for (int i = 0; i < params.length; i++) {
            if (i < argVals.size()) out.put(slot, argVals.get(i));
            slot += params[i].getSize();
        }
        return out;
    }

    /** Runs {@code init} with {@code argVals} bound to its parameters; returns the value stored into {@code fieldName}. */
    public static Val runInitAndCapture(ClassNode owner, MethodNode init, List<Val> argVals, String fieldName) {
        Val[] captured = new Val[1];
        MethodSim.run(owner, init, seedParams(init, argVals), (insn, stack, locals) -> {
            if (insn.getOpcode() != Opcodes.PUTFIELD) return;
            FieldInsnNode f = (FieldInsnNode) insn;
            if (!fieldName.equals(f.name)) return;
            if (!stack.isEmpty()) captured[0] = stack.get(stack.size() - 1);
        });
        return captured[0];
    }

    // ==================================================================== small ASM utilities

    public static MethodNode findMethod(ClassNode cn, String name) {
        if (cn == null) return null;
        for (MethodNode m : cn.methods) if (m.name.equals(name)) return m;
        return null;
    }

    public static MethodNode findInit(ClassNode cn, String desc) {
        if (cn == null) return null;
        for (MethodNode m : cn.methods) if ("<init>".equals(m.name) && m.desc.equals(desc)) return m;
        return null;
    }

    static AbstractInsnNode[] realInsns(MethodNode mn) {
        List<AbstractInsnNode> out = new ArrayList<>();
        for (AbstractInsnNode in : mn.instructions.toArray()) if (!isNoOp(in)) out.add(in);
        return out.toArray(new AbstractInsnNode[0]);
    }

    // ============================================================ Family C: damage==0 branch

    public static final class DamageZeroBranch {
        public final boolean matched;
        public final FieldInsnNode zeroBranchField;    // texture GETSTATIC used when damage == 0
        public final FieldInsnNode nonZeroBranchField; // texture GETSTATIC used when damage != 0
        DamageZeroBranch(FieldInsnNode z, FieldInsnNode nz) {
            this.matched = z != null && nz != null; this.zeroBranchField = z; this.nonZeroBranchField = nz;
        }
        static final DamageZeroBranch NONE = new DamageZeroBranch(null, null);
    }

    /**
     * Finds {@code stack.func_77960_j() == 0 ? A : B} where {@code A}/{@code B} are each the
     * first {@code GETSTATIC ...ResourceLocation;} reachable on their branch — the shape
     * {@code ItemRenderLibrary$77.renderCommonWithStack} uses to pick between two textures. Only
     * ever reports a match for an unconditional two-way branch (no table, no third value).
     */
    public static DamageZeroBranch findDamageZeroTextureBranch(ClassNode rendererCn, String resLocDesc) {
        for (MethodNode mn : rendererCn.methods) {
            AbstractInsnNode[] ins = realInsns(mn);
            for (int i = 0; i < ins.length; i++) {
                if (ins[i].getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
                MethodInsnNode call = (MethodInsnNode) ins[i];
                if (!"func_77960_j".equals(call.name) && !"getItemDamage".equals(call.name)) continue;
                if (i + 1 >= ins.length) continue;
                int branchOp = ins[i + 1].getOpcode();
                if (branchOp != Opcodes.IFEQ && branchOp != Opcodes.IFNE) continue;
                org.objectweb.asm.tree.JumpInsnNode jmp = (org.objectweb.asm.tree.JumpInsnNode) ins[i + 1];
                FieldInsnNode fallthrough = firstGetstaticOfType(ins, i + 2, resLocDesc, jmp.label);
                FieldInsnNode atLabel = firstGetstaticOfTypeFrom(mn, jmp.label, resLocDesc);
                if (fallthrough == null || atLabel == null) continue;
                // IFEQ jumps to label when damage==0 -> label branch is damage==0.
                // IFNE jumps to label when damage!=0 -> label branch is damage!=0.
                if (branchOp == Opcodes.IFEQ) return new DamageZeroBranch(atLabel, fallthrough);
                else return new DamageZeroBranch(fallthrough, atLabel);
            }
        }
        return DamageZeroBranch.NONE;
    }

    private static FieldInsnNode firstGetstaticOfType(AbstractInsnNode[] ins, int from, String desc,
                                                         org.objectweb.asm.tree.LabelNode stopAt) {
        for (int i = from; i < ins.length; i++) {
            if (ins[i] == stopAt) return null;
            if (ins[i].getOpcode() == Opcodes.GOTO) return null;
            if (ins[i].getOpcode() == Opcodes.GETSTATIC && desc.equals(((FieldInsnNode) ins[i]).desc))
                return (FieldInsnNode) ins[i];
        }
        return null;
    }

    private static FieldInsnNode firstGetstaticOfTypeFrom(MethodNode mn, org.objectweb.asm.tree.LabelNode label,
                                                            String desc) {
        AbstractInsnNode cur = label;
        while (cur != null) {
            if (!isNoOp(cur)) {
                if (cur.getOpcode() == Opcodes.GETSTATIC && desc.equals(((FieldInsnNode) cur).desc))
                    return (FieldInsnNode) cur;
                if (cur.getOpcode() == Opcodes.GOTO || cur.getOpcode() == Opcodes.ARETURN
                        || cur.getOpcode() == Opcodes.RETURN) return null;
            }
            cur = cur.getNext();
        }
        return null;
    }
}
