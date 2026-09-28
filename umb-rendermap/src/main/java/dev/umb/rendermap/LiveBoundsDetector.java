package dev.umb.rendermap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Door-live lane: finds blocks whose collision bounds genuinely follow tile-entity state
 * (doors, hatches), so the host only queries live bounds where they can differ from the
 * statically extracted shape. A block qualifies when its
 * {@code getCollisionBoundingBoxFromPool} ({@code func_149668_a}),
 * {@code addCollisionBoxesToList} ({@code func_149743_a}), or {@code getBoundingBox}
 * override reads a field off a tile-entity-typed value - the value arriving as a method
 * parameter, out of a {@code World.getTileEntity} (or any TE-returning) call, or through
 * locals/checkcasts thereof. Pure positional math over metadata never qualifies, no matter
 * how many branches it has.
 *
 * <p>Bounded: only the two bounds methods per block class, single linear pass each (via
 * {@code MethodSim}'s opt-in provenance), expression depth capped by
 * {@link DynamicOpResolver#MAX_EXPR_DEPTH}. No mod names: TE-typed means "walks to
 * {@code net/minecraft/tileentity/TileEntity}" and nothing else. Anything unresolvable
 * simply does not flag the block (honest absence - the static shape stays).</p>
 */
public final class LiveBoundsDetector {

    private LiveBoundsDetector() {
    }

    /**
     * @param jar    the mod jar index (classes + hierarchy).
     * @param blocks block id to dotted block class, e.g. from the render map rows.
     * @return block ids whose bounds methods read tile-entity state, plus per-reason counts.
     */
    public static Result detect(JarIndex jar, Map<String, String> blocks) {
        Set<String> live = new LinkedHashSet<>();
        Map<String, Integer> counts = new TreeMap<>();
        counts.put("blocks-scanned", 0);
        if (jar == null || blocks == null) return new Result(live, counts);
        for (Map.Entry<String, String> e : blocks.entrySet()) {
            if (e.getValue() == null) continue;
            ClassNode cn = jar.cls(JarIndex.internal(e.getValue()));
            if (cn == null) continue;
            counts.merge("blocks-scanned", 1, Integer::sum);
            for (MethodNode mn : cn.methods) {
                if (!isBoundsMethod(mn.name)) continue;
                if (readsTileState(cn, mn, jar)) {
                    live.add(e.getKey());
                    counts.merge("live-bounds-blocks", 1, Integer::sum);
                    break;
                }
            }
        }
        return new Result(live, counts);
    }

    private static boolean isBoundsMethod(String name) {
        return "func_149668_a".equals(name) || "getCollisionBoundingBoxFromPool".equals(name)
                || "func_149743_a".equals(name) || "addCollisionBoxesToList".equals(name)
                || "getBoundingBox".equals(name);
    }

    /** True when the method reads any field off a tile-entity-rooted value. */
    static boolean readsTileState(ClassNode cn, MethodNode mn, JarIndex jar) {
        if (mn.instructions == null || mn.instructions.size() == 0) return false;
        final boolean[] hit = {false};
        Type[] params = Type.getArgumentTypes(mn.desc);
        MethodSim.run(cn, mn, jar, false, true, (insn, stack, locals) -> {
            if (hit[0] || !(insn instanceof FieldInsnNode f)
                    || insn.getOpcode() != Opcodes.GETFIELD) {
                return;
            }
            // The receiver sits on top of the stack before execution.
            if (stack.isEmpty()) return;
            Val recv = stack.get(stack.size() - 1);
            if (isTeRooted(recv, params, jar, 0)) hit[0] = true;
        });
        return hit[0];
    }

    /**
     * Receiver traces to tile-entity state: a TE-typed parameter, a call returning a TE
     * (e.g. {@code World.getTileEntity}), a checkcast-preserving alias of either through
     * locals, or a field of such a value (bounded depth).
     */
    private static boolean isTeRooted(Val v, Type[] params, JarIndex jar, int depth) {
        if (v == null || depth > DynamicOpResolver.MAX_EXPR_DEPTH) return false;
        switch (v.kind) {
            case PARAM: {
                int ai = v.numberValue.intValue();
                // MethodSim numbers PARAMs by argument index... except slot 0 (this) is not
                // an argument: PARAM n means the nth *argument*, matching params[n] here.
                // (Locals seeded by exec() put PARAM(ai) at the right slot; this-ref is separate.)
                if (ai < 0 || ai >= params.length) return false;
                Type t = params[ai];
                return t.getSort() == Type.OBJECT
                        && jar.isSubclassOf(t.getInternalName(), "net/minecraft/tileentity/TileEntity");
            }
            case CALL: {
                // The call's *declared* return type: TE-returning calls (getTileEntity and any
                // mod-specific equivalent) count without naming them.
                if (v.desc == null) return false;
                Type ret;
                try {
                    ret = Type.getReturnType(v.desc);
                } catch (Exception ex) {
                    return false;
                }
                return ret.getSort() == Type.OBJECT
                        && jar.isSubclassOf(ret.getInternalName(), "net/minecraft/tileentity/TileEntity");
            }
            case FIELD:
                return isTeRooted(v.inner, params, jar, depth + 1);
            default:
                return false;
        }
    }

    public static final class Result {
        public final Set<String> liveBlocks;
        public final Map<String, Integer> counts;
        Result(Set<String> liveBlocks, Map<String, Integer> counts) {
            this.liveBlocks = liveBlocks;
            this.counts = counts;
        }
    }
}
