package dev.umb.rendermap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recovers which static {@code Item}/{@code Block} holder fields — in ANY class, under ANY name,
 * not just HBM's own {@code ModItems}/{@code ModBlocks} — are actually registered, by walking
 * every {@code GameRegistry.registerItem} / {@code registerBlock} call site in the jar and
 * backtracking the operand stack to the {@code getstatic} that fed it.
 *
 * <p>On HBM specifically, two shapes exist:
 * <ul>
 *   <li>direct: {@code GameRegistry.registerItem(ModItems.x, ModItems.x.getUnlocalizedName())}
 *       — 1809 item and 636 block sites;</li>
 *   <li>forwarded: {@code ModBlocks.register(ModBlocks.x)} where the private helper
 *       does the {@code GameRegistry} call on its own parameter — 339 further block sites.</li>
 * </ul>
 * HBM's registry name is never a literal (it is always {@code thing.getUnlocalizedName()}), so on
 * HBM the id comes entirely from {@link ModRegistryResolver}'s builder-chain scan. Other mods DO
 * pass a literal name directly (Iron Chests: {@code registerBlock(block, ItemBlock.class,
 * "BlockIronChest")}) — this class records that literal in {@link Reg#registryName} either way,
 * and {@link ModRegistryResolver#resolveIds} uses it as a fallback local-name candidate for any
 * field the builder-chain scan could not name.
 */
public class RegistryScanner {

    public static class Reg {
        public String fieldOwner;   // "com/hbm/items/ModItems"
        public String fieldName;    // "gun_darter"
        /** The LOCAL registry-name fragment actually visible in the bytecode, set only when
         *  {@code nameSource.equals("literal")} — e.g. Iron Chests' {@code registerBlock(block,
         *  ItemBlock.class, "BlockIronChest")}. This is never namespaced: the namespace a live
         *  game assigns comes from the active mod's own modid at call time (via
         *  {@code Loader.instance().activeModContainer()}), which is not recoverable from the
         *  jar's bytecode by name, so resolvers in this package always join a local name against
         *  the snapshot's real ids ({@link Snapshot#resolveLocalName}) instead of guessing one. */
        public String registryName;
        public String site;         // declaring method
        public String unresolvedReason;
        public String rawArg;       // shape of arg0 when unresolved
        public String nameSource;   // "literal" | "getUnlocalizedName" | "unmodelled"
        public boolean viaForwarder;
        /** How many forwarding calls sat between the site that actually feeds
         *  {@code GameRegistry.register*} and the call this row's {@link #site} names — 0 for a
         *  direct call, 1 for a single-hop forwarder (HBM's own {@code ModBlocks.register(block)}
         *  static helper, present before this mandate), 2 for a forwarder that itself calls another
         *  forwarder (Chisel's {@code CarvableHelper.registerBlock(Block,String)} ->
         *  {@code registerBlock(Block,String,Class)} -> {@code GameRegistry.registerBlock}) — see
         *  {@link #FORWARDER_HOP_DEPTH}. Reported per-row so the effect of the deeper hop is
         *  measurable, per the mandate. */
        public int hopsUsed;
    }

    /** "owner/Class.field" -> registration (items). */
    public final Map<String, Reg> itemFieldToReg = new LinkedHashMap<>();
    /** "owner/Class.field" -> registration (blocks). */
    public final Map<String, Reg> blockFieldToReg = new LinkedHashMap<>();
    public final List<Reg> unresolved = new ArrayList<>();
    public int itemCallSites, blockCallSites, forwardedCallSites;

    /** A forwarder that was found via {@link #FORWARDER_HOP_DEPTH}-bounded call-graph tracing. */
    static final class Forwarder {
        final boolean isItem;
        final int hops; // 1 = calls GameRegistry directly itself; 2 = via one more forwarder; ...
        Forwarder(boolean isItem, int hops) { this.isItem = isItem; this.hops = hops; }
    }
    /** "owner.name.desc" of helpers that forward their first argument to GameRegistry, directly or
     *  (bounded, see {@link #FORWARDER_HOP_DEPTH}) through one further forwarding call. */
    final Map<String, Forwarder> forwarders = new LinkedHashMap<>();

    /**
     * Bounded interprocedural step (mandate #1): a "forwarder" may itself call ANOTHER in-jar
     * method that forwards the same value again before it ever reaches {@code GameRegistry} — e.g.
     * Chisel's public {@code CarvableHelper.registerBlock(Block,String)} calls a private
     * 3-arg overload, which is the one that actually calls {@code GameRegistry.registerBlock}
     * (confirmed by direct bytecode inspection, not inferred). {@link #findForwarders()} follows
     * exactly this many additional forwarding calls beyond the entry method's own body — a NAMED,
     * FIXED bound, not an unbounded whole-program call-graph walk.
     */
    static final int FORWARDER_HOP_DEPTH = 1;

    private static final String GR = "cpw/mods/fml/common/registry/GameRegistry";

    private final JarIndex jar;

    public RegistryScanner(JarIndex jar) { this.jar = jar; }

    public void scanAll() {
        findForwarders();
        for (ClassNode cn : jar.classes.values()) {
            for (MethodNode mn : cn.methods) {
                final String site = JarIndex.dotted(cn.name) + "." + mn.name;
                // Pass `jar` so a getstatic of a Block[]/Item[] content-holder field is tracked
                // as a mutable virtual array within this one method's pass (see MethodSim's
                // content-array javadoc) — recovers registrations fed by
                // `array[i] = new Foo(...); ...; GameRegistry.registerBlock(array[i], ...)`
                // inside the same loop, which previously lost the array's identity across the
                // getstatic and fell back to a bare unresolved value.
                MethodSim.run(cn, mn, jar, (insn, stack, locals) -> {
                    int op = insn.getOpcode();
                    if (op != Opcodes.INVOKESTATIC && op != Opcodes.INVOKEVIRTUAL
                            && op != Opcodes.INVOKESPECIAL && op != Opcodes.INVOKEINTERFACE) return;
                    MethodInsnNode m = (MethodInsnNode) insn;
                    if (op == Opcodes.INVOKESTATIC && GR.equals(m.owner)) { direct(site, m, stack); return; }
                    Forwarder fwd = forwarders.get(m.owner + "." + m.name + m.desc);
                    if (fwd != null) forwarded(site, m, stack, fwd);
                });
            }
        }
    }

    /**
     * A forwarder is any method — static OR instance; Chisel's own registration helper
     * ({@code CarvableHelper.registerBlock(Block,String)}) is a plain instance method on a small
     * helper object, a common 1.7.10 idiom, not a static utility like HBM's — whose first formal
     * parameter is an {@code Item}/{@code Block} and which passes that SAME value as the object
     * argument of {@code GameRegistry.registerItem}/{@code registerBlock}, either directly or
     * (bounded by {@link #FORWARDER_HOP_DEPTH}) through exactly one further forwarding call.
     */
    private void findForwarders() {
        for (ClassNode cn : jar.classes.values()) {
            for (MethodNode mn : cn.methods) {
                if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) continue;
                Type[] p = Type.getArgumentTypes(mn.desc);
                if (p.length == 0) continue;
                String p0 = p[0].getDescriptor();
                if (!"Lnet/minecraft/item/Item;".equals(p0) && !"Lnet/minecraft/block/Block;".equals(p0)) continue;
                Forwarder f = traceForwarding(cn, mn, 0, FORWARDER_HOP_DEPTH);
                if (f != null) forwarders.put(cn.name + "." + mn.name + mn.desc, f);
            }
        }
    }

    /**
     * Does {@code mn} — whose formal parameter {@code paramIndex} carries the tracked Item/Block —
     * eventually pass that exact value as the object argument of a {@code GameRegistry.register*}
     * call, either directly in its own body or, bounded by {@code hopsLeft}, via one further
     * forwarding call to another in-jar method? Returns the resolved {@link Forwarder} (with an
     * accurate hop count), or {@code null} — never guesses.
     */
    private Forwarder traceForwarding(ClassNode cn, MethodNode mn, int paramIndex, int hopsLeft) {
        boolean[] hit = new boolean[2]; // [0]=found direct call, [1]=isItem
        List<Object[]> hops = new ArrayList<>(); // {ClassNode target, String name, String desc, int newParamIndex}
        MethodSim.run(cn, mn, (insn, stack, locals) -> {
            if (hit[0]) return;
            int op = insn.getOpcode();
            if (op != Opcodes.INVOKESTATIC && op != Opcodes.INVOKEVIRTUAL
                    && op != Opcodes.INVOKESPECIAL && op != Opcodes.INVOKEINTERFACE) return;
            MethodInsnNode m = (MethodInsnNode) insn;
            int argc = Type.getArgumentTypes(m.desc).length;
            if (op == Opcodes.INVOKESTATIC && GR.equals(m.owner)) {
                boolean isItem = "registerItem".equals(m.name);
                boolean isBlock = "registerBlock".equals(m.name);
                if (!isItem && !isBlock) return;
                Val obj = at(stack, argc - 1);
                if (obj != null && obj.kind == Val.Kind.PARAM && obj.numberValue.intValue() == paramIndex) {
                    hit[0] = true; hit[1] = isItem;
                }
                return;
            }
            if (hopsLeft <= 0) return;
            ClassNode targetCn = jar.cls(m.owner);
            if (targetCn == null) return; // can't look inside a class we don't have (engine/vanilla)
            for (int i = 0; i < argc; i++) {
                Val a = at(stack, argc - 1 - i);
                if (a != null && a.kind == Val.Kind.PARAM && a.numberValue.intValue() == paramIndex)
                    hops.add(new Object[]{targetCn, m.name, m.desc, i});
            }
        });
        if (hit[0]) return new Forwarder(hit[1], 1);
        for (Object[] h : hops) {
            ClassNode targetCn = (ClassNode) h[0];
            String name = (String) h[1], desc = (String) h[2];
            int newParamIndex = (int) h[3];
            MethodNode target = null;
            for (MethodNode m : targetCn.methods) if (m.name.equals(name) && m.desc.equals(desc)) { target = m; break; }
            if (target == null) continue;
            Forwarder deeper = traceForwarding(targetCn, target, newParamIndex, hopsLeft - 1);
            if (deeper != null) return new Forwarder(deeper.isItem, deeper.hops + 1);
        }
        return null;
    }

    private void direct(String site, MethodInsnNode m, List<Val> stack) {
        boolean isItem = "registerItem".equals(m.name);
        boolean isBlock = "registerBlock".equals(m.name);
        if (!isItem && !isBlock) return;
        Type[] at = Type.getArgumentTypes(m.desc);
        int argc = at.length;
        if (argc < 2) return;
        if (isItem) itemCallSites++; else blockCallSites++;

        // the registry name is the (single) String parameter; the 3-arg registerBlock
        // overload puts an ItemBlock Class between the block and the name.
        int nameIdx = -1;
        for (int i = 0; i < argc; i++)
            if ("Ljava/lang/String;".equals(at[i].getDescriptor())) nameIdx = i;

        Val obj = at(stack, argc - 1);
        Val name = nameIdx < 0 ? null : at(stack, argc - 1 - nameIdx);
        // Gap 3 / A4 (mandate #3): a registration call fed by a loop variable over a compile-time
        // array — e.g. `for (...) GameRegistry.registerBlock(BLOCKS[i], NAMES[i]);` — expands to
        // one record per array element instead of collapsing to a single unresolved row, reusing
        // the same mod-agnostic "loop/array registration" support BindingScanner already has for
        // renderer bindings (BindingScanner#expand/#zipExpand). When neither side is array-shaped
        // (the ordinary case) this is exactly one iteration with the original values — unchanged
        // behavior.
        for (Val[] pair : BindingScanner.zipExpand(obj, name)) record(site, pair[0], pair[1], isItem, false, 0);
    }

    private void forwarded(String site, MethodInsnNode m, List<Val> stack, Forwarder fwd) {
        Type[] at = Type.getArgumentTypes(m.desc);
        int argc = at.length;
        forwardedCallSites++;
        // The real registry name is very often ALSO visible right here, at the outermost call
        // site, as a literal String argument to the forwarder itself (Chisel:
        // carverHelper.registerBlock(block, "blockBookshelf")) — even when the forwarder's own
        // deeper body never sees a literal (it may build the real registry name from this argument
        // via string concatenation one hop further in, which this bounded analysis does not chase;
        // ModRegistryResolver's dominant-local-prefix heuristic bridges that remaining gap
        // generically, without hardcoding any mod's own prefix convention).
        int nameIdx = -1;
        for (int i = 0; i < argc; i++)
            if ("Ljava/lang/String;".equals(at[i].getDescriptor())) nameIdx = i;
        Val name = nameIdx < 0 ? null : at(stack, argc - 1 - nameIdx);
        String fsite = site + " -> " + JarIndex.dotted(m.owner) + "." + m.name;
        for (Val[] pair : BindingScanner.zipExpand(at(stack, argc - 1), name))
            record(fsite, pair[0], pair[1], fwd.isItem, true, fwd.hops);
    }

    private void record(String site, Val obj, Val name, boolean isItem, boolean viaForwarder, int hopsUsed) {
        Reg r = new Reg();
        r.site = site;
        r.viaForwarder = viaForwarder;
        r.hopsUsed = hopsUsed;
        if (name != null && name.kind == Val.Kind.STRING) {
            r.registryName = name.stringValue;
            r.nameSource = "literal";
        } else if (name != null && name.kind == Val.Kind.DERIVED
                && "getUnlocalizedName".equals(name.stringValue)) {
            r.nameSource = "getUnlocalizedName";
        } else {
            r.nameSource = viaForwarder ? "getUnlocalizedName (inside forwarder)" : "unmodelled";
        }
        if (obj != null && obj.kind == Val.Kind.STATIC_FIELD) {
            r.fieldOwner = obj.owner;
            r.fieldName = obj.name;
        } else {
            r.rawArg = String.valueOf(obj);
            r.unresolvedReason = "object argument is not a getstatic (" + obj + ")";
            unresolved.add(r);
            return;
        }
        (isItem ? itemFieldToReg : blockFieldToReg).putIfAbsent(r.fieldOwner + "." + r.fieldName, r);
    }

    /** All fields seen at a registration site, item and block together. */
    public Set<String> allRegisteredFields() {
        Set<String> s = new LinkedHashSet<>(itemFieldToReg.keySet());
        s.addAll(blockFieldToReg.keySet());
        return s;
    }

    private static Val at(List<Val> stack, int fromTop) {
        int i = stack.size() - 1 - fromTop;
        return i < 0 || i >= stack.size() ? null : stack.get(i);
    }
}
