package dev.umb.guimap;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * SYNC-BINDING lane: extracts the legacy-field &lt;-&gt; {@code ICrafting} sync-register-id map for
 * ONE {@code Container} class, using BOTH halves of the vanilla 1.7.10 progress-bar API (see
 * {@code research/out/legacy/guimap-notes/SYNC-BINDING.md} — MCP names verified against
 * {@code research/repos/MinecraftForge/fml/conf/methods.csv}):
 *
 * <ul>
 *   <li><b>server route</b> — every call to {@code ICrafting.func_71112_a(container, id, value)}
 *   found ANYWHERE in the class (the vanilla convention puts this inside
 *   {@code func_75142_b}/detectAndSendChanges, but some mods also mirror the initial full sync in
 *   {@code func_75132_a}/addCraftingToCrafters — both are scanned, and agreeing duplicates are
 *   harmless). {@code id} must be a compile-time constant; {@code value} must resolve to a plain
 *   getfield chain (any depth), and only the LEAF field's owner+name is recorded — the receiver
 *   chain doesn't matter for binding purposes, only "which field, on which class".</li>
 *   <li><b>client route</b> — {@code func_75137_b(int id, int value)} (updateProgressBar): a
 *   straight-line forward if-chain of the shape {@code if (id == CONST) receiver.field = value;}.
 *   Only counted when the stored value is the incoming {@code value} parameter COMPLETELY
 *   UNMODIFIED (no arithmetic) and the receiver is {@code this} or a direct {@code this.field}
 *   read — a transformed or indirect assignment is left unclaimed rather than guessed at.</li>
 * </ul>
 *
 * <p>Matches ONLY the fixed vanilla method names/descriptors in {@link Vanilla} — never a class or
 * member name specific to any one mod. When both routes see the same {@code id}, the two maps are
 * cross-checked and any disagreement is reported (never silently resolved by picking one) — see
 * {@link #scanContainer}'s JSON output. A conflict WITHIN one route (the same id sent under two
 * DIFFERENT fields by two different call sites) is likewise never guessed at: that id is dropped
 * from that route's map entirely and counted in {@link #conflictCount()}.
 */
public final class ContainerSyncScanner {

    /** One legacy field identity: the declaring class (dotted) + field name — deliberately NOT the
     *  receiver chain (a container's own "this.diFurnace.heat" and a hypothetical
     *  "this.someOtherPointer.heat" reaching the SAME class+field are the same binding target). */
    public static final class FieldRef {
        public final String ownerClass;
        public final String fieldName;

        FieldRef(String ownerClass, String fieldName) {
            this.ownerClass = ownerClass;
            this.fieldName = fieldName;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof FieldRef f && f.ownerClass.equals(ownerClass) && f.fieldName.equals(fieldName);
        }

        @Override
        public int hashCode() {
            return ownerClass.hashCode() * 31 + fieldName.hashCode();
        }

        @Override
        public String toString() {
            return ownerClass + "#" + fieldName;
        }
    }

    private final Map<Integer, FieldRef> serverMap = new LinkedHashMap<>();
    private final Set<Integer> serverConflicts = new LinkedHashSet<>();
    private final Map<Integer, FieldRef> clientMap = new LinkedHashMap<>();
    private final Set<Integer> clientConflicts = new LinkedHashSet<>();

    /** Runs both routes over one Container {@link ClassNode} and returns the additive
     *  {@code container.syncBindings} JSON array (empty when neither route found anything). */
    public static JsonArray scanContainer(ClassNode cn) {
        ContainerSyncScanner s = new ContainerSyncScanner();
        for (MethodNode mn : cn.methods) s.scanServerRoute(cn, mn);
        MethodNode client = findMethod(cn, Vanilla.M_UPDATE_PROGRESS_BAR, Vanilla.M_UPDATE_PROGRESS_BAR_DESC);
        if (client != null) s.scanClientRoute(cn, client);
        return s.toJson();
    }

    // ---------------- server route: ICrafting.func_71112_a(container, id, value) ----------------

    private void scanServerRoute(ClassNode cn, MethodNode mn) {
        MethodSim.run(cn, mn, (insn, stack, locals) -> {
            if (insn.getOpcode() != Opcodes.INVOKEINTERFACE) return;
            MethodInsnNode m = (MethodInsnNode) insn;
            if (!Vanilla.ICRAFTING.equals(m.owner) || !Vanilla.M_SEND_PROGRESS_BAR_UPDATE.equals(m.name)) return;
            int argc = Type.getArgumentTypes(m.desc).length;
            if (argc != 3) return; // not the (Container,int,int) overload
            Val idVal = argAt(stack, argc, 1);
            Val valueVal = argAt(stack, argc, 2);
            if (idVal.kind != Val.Kind.NUMBER) return; // id must be a compile-time constant
            int id = idVal.numberValue.intValue();
            FieldRef field = leafField(valueVal);
            if (field == null) return;
            FieldRef existing = serverMap.get(id);
            if (existing != null && !existing.equals(field)) {
                serverConflicts.add(id);
                serverMap.remove(id);
                return;
            }
            if (!serverConflicts.contains(id)) serverMap.put(id, field);
        });
    }

    private static FieldRef leafField(Val v) {
        if (v.kind == Val.Kind.UNKNOWN && v.owner != null && v.name != null
                && v.reason != null && v.reason.startsWith("getfield ")) {
            return new FieldRef(JarIndex.dotted(v.owner), v.name);
        }
        return null;
    }

    // ---------------- client route: func_75137_b(int id, int value) ----------------

    private static final class GuardFrame {
        final LabelNode target;
        final Integer id; // null = a guard shape this scanner doesn't recognise (blocks matches under it)

        GuardFrame(LabelNode target, Integer id) {
            this.target = target;
            this.id = id;
        }
    }

    private void scanClientRoute(ClassNode cn, MethodNode mn) {
        if (mn.instructions == null) return;
        Map<AbstractInsnNode, Integer> indexOf = new HashMap<>();
        AbstractInsnNode[] arr = mn.instructions.toArray();
        for (int i = 0; i < arr.length; i++) indexOf.put(arr[i], i);
        Deque<GuardFrame> guardStack = new ArrayDeque<>();

        MethodSim.run(cn, mn, (insn, stack, locals) -> {
            if (insn instanceof LabelNode ln) {
                while (!guardStack.isEmpty() && guardStack.peek().target == ln) guardStack.pop();
                return;
            }
            int op = insn.getOpcode();
            if (isForwardIdGuard(insn, indexOf)) {
                JumpInsnNode j = (JumpInsnNode) insn;
                guardStack.push(new GuardFrame(j.label, guardedId(op, stack)));
                return;
            }
            if (op != Opcodes.PUTFIELD) return;
            if (guardStack.isEmpty() || guardStack.peek().id == null) return;
            int id = guardStack.peek().id;
            if (stack.size() < 2) return;
            // PUTFIELD stack (pre-execution, top last): [..., objectref, value]
            Val value = stack.get(stack.size() - 1);
            Val objectref = stack.get(stack.size() - 2);
            // must be the raw, unmodified second int parameter - a transformed value is never guessed at
            if (value.kind != Val.Kind.PARAM || value.numberValue.intValue() != 1) return;
            boolean receiverIsThisOrDirectField = objectref.kind == Val.Kind.THIS
                    || (objectref.kind == Val.Kind.UNKNOWN && objectref.receiver != null && objectref.receiver.kind == Val.Kind.THIS);
            if (!receiverIsThisOrDirectField) return;
            FieldInsnNode f = (FieldInsnNode) insn;
            FieldRef field = new FieldRef(JarIndex.dotted(f.owner), f.name);
            FieldRef existing = clientMap.get(id);
            if (existing != null && !existing.equals(field)) {
                clientConflicts.add(id);
                clientMap.remove(id);
                return;
            }
            if (!clientConflicts.contains(id)) clientMap.put(id, field);
        });
    }

    /** Only the two shapes actually needed to express "guarded region == id CONST" without
     *  guessing: {@code IFNE} on the bare id parameter (implied CONST 0 - "skip unless id==0") and
     *  {@code IF_ICMPNE} comparing the id parameter against a pushed constant ("skip unless
     *  id==CONST"). The inverse comparisons ({@code IFEQ}/{@code IF_ICMPEQ}) would guard "id != X",
     *  which is not a single id and is deliberately left unrecognised. */
    private static boolean isForwardIdGuard(AbstractInsnNode insn, Map<AbstractInsnNode, Integer> indexOf) {
        if (!(insn instanceof JumpInsnNode j)) return false;
        if (j.getOpcode() != Opcodes.IFNE && j.getOpcode() != Opcodes.IF_ICMPNE) return false;
        Integer from = indexOf.get(insn), to = indexOf.get(j.label);
        return from != null && to != null && to > from;
    }

    private static Integer guardedId(int opcode, List<Val> stack) {
        if (opcode == Opcodes.IFNE) {
            if (stack.isEmpty()) return null;
            Val v = stack.get(stack.size() - 1);
            return isIdParam(v) ? Integer.valueOf(0) : null;
        }
        if (opcode == Opcodes.IF_ICMPNE) {
            if (stack.size() < 2) return null;
            Val a = stack.get(stack.size() - 2), b = stack.get(stack.size() - 1);
            if (isIdParam(a) && b.kind == Val.Kind.NUMBER) return b.numberValue.intValue();
            if (isIdParam(b) && a.kind == Val.Kind.NUMBER) return a.numberValue.intValue();
            return null;
        }
        return null;
    }

    private static boolean isIdParam(Val v) {
        return v.kind == Val.Kind.PARAM && v.numberValue.intValue() == 0;
    }

    // ---------------- shared ----------------

    private static Val argAt(List<Val> stack, int argc, int indexFromLeft) {
        int i = stack.size() - argc + indexFromLeft;
        return i >= 0 && i < stack.size() ? stack.get(i) : Val.unknown("stack underflow");
    }

    private static MethodNode findMethod(ClassNode cn, String name, String desc) {
        for (MethodNode mn : cn.methods) if (mn.name.equals(name) && mn.desc.equals(desc)) return mn;
        return null;
    }

    public int conflictCount() {
        return serverConflicts.size() + clientConflicts.size();
    }

    private JsonArray toJson() {
        JsonArray out = new JsonArray();
        Set<Integer> ids = new TreeSet<>();
        ids.addAll(serverMap.keySet());
        ids.addAll(clientMap.keySet());
        for (int id : ids) {
            FieldRef sv = serverMap.get(id), cv = clientMap.get(id);
            FieldRef chosen = sv != null ? sv : cv;
            JsonObject o = new JsonObject();
            o.addProperty("syncIndex", id);
            JsonObject field = new JsonObject();
            field.addProperty("ownerClass", chosen.ownerClass);
            field.addProperty("fieldName", chosen.fieldName);
            o.add("field", field);
            o.addProperty("serverRoute", sv != null);
            o.addProperty("clientRoute", cv != null);
            boolean both = sv != null && cv != null;
            if (both) {
                boolean agree = sv.equals(cv);
                o.addProperty("agree", agree);
                if (!agree) {
                    JsonObject disagreement = new JsonObject();
                    disagreement.addProperty("serverField", sv.toString());
                    disagreement.addProperty("clientField", cv.toString());
                    o.add("disagreement", disagreement);
                }
            } else {
                // single-route evidence only - "agree" is meaningless (nothing to cross-check
                // against), reported as JSON null rather than a misleading true/false.
                o.add("agree", JsonNull.INSTANCE);
            }
            out.add(o);
        }
        return out;
    }
}
