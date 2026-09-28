package dev.umb.rendermap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact block -> tile-entity recovery for snapshots whose live dump could not instantiate a TE. */
final class TileEntityLinker {
    private TileEntityLinker() {}

    /**
     * Upgrades block -> TE linkages whose snapshot TE has no bound TESR (invisible in world)
     * when the block's own factory constructs another snapshot-known TE that DOES have one.
     * The multiblock/metadata shape: {@code createNewTileEntity} branches on metadata and
     * builds a lightweight proxy for some metas and the real, rendered TE for the core ones;
     * the live dump only ever instantiates the proxy, so the snapshot (honestly) names it and
     * the row goes unresolved. The visual row must follow the rendered TE: a TE with no
     * renderer contributes no model, no texture, and no TESR binding, while the bound one is
     * exactly what the block shows in world.
     *
     * <p>Strictly bounded and guess-free: only snapshot-known TE classes count as candidates,
     * only a TESR-bound candidate (from the caller's binding set) can win, and the current
     * linkage is left untouched unless such a candidate exists. Ties between bound candidates
     * go to the first in bytecode order, with every candidate named in the returned note.
     * Returns blockId -> linkage note for rows that moved (empty when nothing did).
     */
    static Map<String, String> relinkUnrendered(JarIndex jar, Snapshot snap, Set<String> boundTeClasses) {
        Map<String, String> notes = new LinkedHashMap<>();
        if (boundTeClasses == null || boundTeClasses.isEmpty()) return notes;
        Set<String> known = new LinkedHashSet<>();
        for (Snapshot.Named n : snap.tileEntities) if (n.className != null) known.add(n.className);
        if (known.isEmpty()) return notes;
        for (Snapshot.Blk b : snap.blocks) {
            if (!b.hasTileEntity || b.tileEntityClass == null || b.className == null) continue;
            if (boundTeClasses.contains(b.tileEntityClass)) continue;
            List<String> candidates = constructedTes(jar, b.className, known);
            String winner = null;
            for (String c : candidates) {
                if (boundTeClasses.contains(c)) { winner = c; break; }
            }
            if (winner == null || winner.equals(b.tileEntityClass)) continue;
            String from = b.tileEntityClass;
            b.tileEntityClass = winner;
            List<String> oldList = snap.blocksByTeClass.get(from);
            if (oldList != null) {
                oldList.remove(b.id);
                if (oldList.isEmpty()) snap.blocksByTeClass.remove(from);
            }
            snap.blocksByTeClass.computeIfAbsent(winner, k -> new java.util.ArrayList<>()).add(b.id);
            notes.put(b.id, "metadata-branched TE relinked " + shortName(from) + " -> "
                    + shortName(winner) + " (factory constructs "
                    + describe(candidates) + "; only " + shortName(winner) + " has a bound TESR)");
        }
        return notes;
    }

    /** Every snapshot-known TE class constructed in the block's factory methods, in order, deduped. */
    private static List<String> constructedTes(JarIndex jar, String dottedBlock, Set<String> known) {
        List<String> out = new ArrayList<>();
        for (ClassNode cn : jar.superChain(JarIndex.internal(dottedBlock))) {
            for (MethodNode mn : cn.methods) {
                if (!isFactory(mn.name)) continue;
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() != Opcodes.NEW) continue;
                    String dotted = JarIndex.dotted(((TypeInsnNode) in).desc);
                    if (known.contains(dotted) && !out.contains(dotted)) out.add(dotted);
                }
            }
        }
        return out;
    }

    private static String shortName(String dotted) {
        int i = dotted.lastIndexOf('.');
        return i < 0 ? dotted : dotted.substring(i + 1);
    }

    private static String describe(List<String> candidates) {
        List<String> shorted = new ArrayList<>();
        for (String c : candidates) shorted.add(shortName(c));
        return String.join(", ", shorted);
    }

    static void apply(JarIndex jar, Snapshot snap) {
        Set<String> known = new LinkedHashSet<>();
        for (Snapshot.Named n : snap.tileEntities) if (n.className != null) known.add(n.className);
        for (Snapshot.Blk b : snap.blocks) {
            if (!b.hasTileEntity || b.tileEntityClass != null || b.className == null) continue;
            String te = find(jar, b.className, known);
            if (te == null) continue;
            b.tileEntityClass = te;
            snap.blocksByTeClass.computeIfAbsent(te, k -> new java.util.ArrayList<>()).add(b.id);
        }
    }

    private static String find(JarIndex jar, String dottedBlock, Set<String> known) {
        for (ClassNode cn : jar.superChain(JarIndex.internal(dottedBlock))) {
            for (MethodNode mn : cn.methods) {
                if (!isFactory(mn.name)) continue;
                Type ret = Type.getReturnType(mn.desc);
                if (!ret.getClassName().equals("net.minecraft.tileentity.TileEntity")) continue;
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() != Opcodes.NEW) continue;
                    String candidate = ((TypeInsnNode) in).desc;
                    String dotted = JarIndex.dotted(candidate);
                    if (known.contains(dotted)) return dotted;
                }
            }
        }
        return null;
    }

    private static boolean isFactory(String name) {
        return "func_149915_a".equals(name) || "createNewTileEntity".equals(name)
                || "createTileEntity".equals(name);
    }
}
