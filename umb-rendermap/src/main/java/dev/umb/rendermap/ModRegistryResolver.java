package dev.umb.rendermap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves any static {@code Item}/{@code Block} holder field — in ANY class, under ANY name, not
 * just HBM's own {@code ModItems}/{@code ModBlocks} — to the registry id the game hands it.
 *
 * <p>HBM never passes a literal name to {@code GameRegistry.register*}: it passes
 * {@code thing.getUnlocalizedName()}. The literal therefore lives in the holder field's own
 * construction expression, in the builder chain {@code new Foo().setUnlocalizedName("bar")} (SRG
 * {@code func_77655_b}) or {@code new Foo().setBlockName("bar")} (SRG {@code func_149663_c}).
 * Vanilla's own {@code Item.getUnlocalizedName()}/{@code Block.getUnlocalizedName()} prepend
 * {@code item.}/{@code tile.} to whatever was set — that part IS genuine 1.7.10 vanilla behaviour,
 * not an HBM convention — so the local registry-name fragment becomes {@code item.bar}/
 * {@code tile.bar}.
 *
 * <p><b>What this class does NOT do (mandate: no mod-specific literals, and Iron Chests proved why
 * this matters):</b> it never fabricates a namespace. A registry id's namespace is decided by FML
 * at call time from the active mod's own modid, which is not recoverable from the jar's bytecode
 * by name — it is not the jar's file name, nor its main package, nor any string literal in the
 * registration call (Iron Chests keeps its block reference on its {@code @Mod} class, not a
 * separately-named holder class, and registers it with a literal registry NAME that has nothing to
 * do with a namespace: {@code registerBlock(block, ItemBlock.class, "BlockIronChest")}). Instead,
 * {@link #resolveIds} computes only the LOCAL fragment actually visible in the bytecode and joins
 * it against {@link Snapshot#resolveLocalName}, which already knows every id's real, live-game
 * namespace. When {@code setUnlocalizedName}/{@code setBlockName} were never called at all (as for
 * Iron Chests' block — the name is set inside its own constructor, invisible from the field's
 * assignment expression), {@link RegistryScanner}'s literal registration-call name is tried too.
 */
public class ModRegistryResolver {

    public static class Entry {
        public String fieldOwner;    // internal
        public String fieldName;
        public String concreteClass; // dotted class actually instantiated
        public String rawName;       // literal passed to setUnlocalizedName / setBlockName
        public String id;            // resolved against the snapshot by resolveIds(); null until then
        public boolean isBlock;
        public String unresolvedReason;
        public String valueShape;
        public String aliasOf;       // "owner.field" this entry was copied from
        /** 0 = the naming literal was visible directly in this field's own construction
         *  expression; 1 = it was recovered only by the bounded one-hop scan of the constructed
         *  class's own {@code <init>} (see {@link #scanOwnConstructorForName}) — set so the
         *  report can show exactly how much the hop bought, per the mandate. */
        public int hopsUsed;
    }

    /** Bounded interprocedural step (mandate #1): when a holder field's construction expression
     *  sets no name itself, follow exactly this many hops into the constructed class's OWN
     *  {@code <init>} looking for a self-call to {@code setUnlocalizedName}/{@code setBlockName}
     *  (idiomatic "the class names itself" OOP style — see class javadoc and Railcraft's
     *  {@code BlockCube}, confirmed by direct bytecode inspection: its 0-arg constructor calls
     *  {@code this.func_149663_c("railcraft.cube")} directly). A NAMED, FIXED bound — this is
     *  deliberately NOT an unbounded whole-program analysis. */
    static final int CTOR_HOP_DEPTH = 1;

    /** Minimum number of {@link Entry#rawName}-bearing entries required before
     *  {@link #dominantRawNamePrefix} will report a segment at all — mirrors
     *  {@link Snapshot#dominantLocalPrefix}'s own {@code >= 4} sample-size floor so a tiny mod
     *  (or one where the ctor-hop only ever recovers one or two names) never has a "majority"
     *  manufactured out of noise. */
    static final int RAW_PREFIX_MIN_SAMPLES = 4;

    private static final String T_ITEM = "Lnet/minecraft/item/Item;";
    private static final String T_BLOCK = "Lnet/minecraft/block/Block;";

    /** "owner.field" -> entry. */
    public final Map<String, Entry> byField = new LinkedHashMap<>();
    public final List<Entry> unresolved = new ArrayList<>();

    private final JarIndex jar;

    public ModRegistryResolver(JarIndex jar) { this.jar = jar; }

    /**
     * ITEM / BLOCK / null for a field descriptor, following the jar's class hierarchy.
     *
     * <p>This used to require the field's type to be under a hardcoded {@code com/hbm/} /
     * {@code api/hbm/} package prefix — i.e. it only ever recognised *HBM's own* holder classes.
     * Any other mod's {@code ModItems}/{@code ModBlocks}-equivalent, in its own package, was
     * invisible to this resolver no matter what it was named. The only mod-agnostic test is the
     * class hierarchy itself: any concrete field type in the jar that is actually an
     * {@code Item}/{@code Block} subclass counts, regardless of package.
     */
    private Boolean blockOrItem(String desc) {
        if (T_ITEM.equals(desc)) return Boolean.FALSE;
        if (T_BLOCK.equals(desc)) return Boolean.TRUE;
        if (desc.length() < 3 || desc.charAt(0) != 'L' || !desc.endsWith(";")) return null;
        String internal = desc.substring(1, desc.length() - 1);
        if (jar.cls(internal) == null) return null; // not a class declared in this jar
        if (jar.isSubclassOf(internal, "net/minecraft/block/Block")) return Boolean.TRUE;
        if (jar.isSubclassOf(internal, "net/minecraft/item/Item")) return Boolean.FALSE;
        return null;
    }

    public void scanAll() {
        for (ClassNode cn : jar.classes.values()) {
            for (FieldNode f : cn.fields) {
                if ((f.access & Opcodes.ACC_STATIC) == 0) continue;
                Boolean isBlock = blockOrItem(f.desc);
                if (isBlock == null) continue;
                Entry e = new Entry();
                e.fieldOwner = cn.name;
                e.fieldName = f.name;
                e.isBlock = isBlock;
                e.unresolvedReason = "no modelled assignment found anywhere in the jar";
                byField.put(cn.name + "." + f.name, e);
            }
        }
        // HBM assigns ModItems/ModBlocks fields from ordinary static methods
        // (ModItems.initializeItem(), and — for the sedna guns — from
        // com.hbm.items.weapon.sedna.factory.XFactory*.init()), so scan every class.
        for (ClassNode cn : jar.classes.values()) {
            for (MethodNode mn : cn.methods) {
                if ((mn.access & Opcodes.ACC_STATIC) == 0) continue;
                MethodSim.run(cn, mn, (insn, stack, locals) -> {
                    if (insn.getOpcode() != Opcodes.PUTSTATIC) return;
                    FieldInsnNode f = (FieldInsnNode) insn;
                    Entry e = byField.get(f.owner + "." + f.name);
                    if (e == null || e.id != null || stack.isEmpty()) return;
                    fill(e, stack.get(stack.size() - 1));
                });
            }
        }
        // alias fixpoint: `ModItems.a = ModItems.b` may be seen before b is resolved. This
        // propagates rawName/concreteClass only — actual ids are not computed until
        // resolveIds(Snapshot, RegistryScanner) runs, since that needs the snapshot.
        for (int pass = 0; pass < 5; pass++) {
            boolean changed = false;
            for (Entry e : byField.values()) {
                if (e.rawName != null || e.aliasOf == null) continue;
                Entry src = byField.get(e.aliasOf);
                if (src != null && src.rawName != null) {
                    e.rawName = src.rawName; e.concreteClass = src.concreteClass;
                    e.unresolvedReason = null;
                    changed = true;
                }
            }
            if (!changed) break;
        }
    }

    private void fill(Entry e, Val v) {
        e.valueShape = String.valueOf(v);
        if (v.kind == Val.Kind.NEW_OBJ) {
            e.concreteClass = JarIndex.dotted(v.typeName);
            if (v.calls != null) {
                for (String[] c : v.calls) {
                    if (("func_77655_b".equals(c[0]) || "setUnlocalizedName".equals(c[0])
                            || "func_149663_c".equals(c[0]) || "setBlockName".equals(c[0])) && c[1] != null) {
                        e.rawName = c[1];
                    }
                }
            }
            if (e.rawName != null) { e.unresolvedReason = null; return; }
            // a builder chain that never renamed the thing is just an alias of its source
            if (v.syntheticBuilder && v.ctorArgs != null && v.ctorArgs.size() == 1
                    && v.ctorArgs.get(0).kind == Val.Kind.STATIC_FIELD) {
                Val src = v.ctorArgs.get(0);
                e.aliasOf = src.owner + "." + src.name;
                Entry alias = byField.get(e.aliasOf);
                if (alias != null && alias.rawName != null) {
                    e.rawName = alias.rawName; e.concreteClass = alias.concreteClass;
                    e.unresolvedReason = null;
                } else {
                    e.unresolvedReason = "builder chain from " + JarIndex.dotted(src.owner) + "." + src.name
                            + " with no setUnlocalizedName/setBlockName literal (yet — may resolve once " + src
                            + " does, or via the registration call site's own literal name in resolveIds())";
                }
                return;
            }
            String hopName = scanOwnConstructorForName(v.typeName, v.ctorArgs == null ? 0 : v.ctorArgs.size(),
                    CTOR_HOP_DEPTH);
            if (hopName != null) {
                e.rawName = hopName;
                e.hopsUsed = CTOR_HOP_DEPTH;
                e.unresolvedReason = null;
                return;
            }
            e.unresolvedReason = "constructed " + e.concreteClass + " but no setUnlocalizedName/setBlockName"
                    + " literal in this expression, and the class's own constructor (a " + CTOR_HOP_DEPTH
                    + "-hop bounded scan) did not set one either — see resolveIds(), which also tries the"
                    + " registration call site's literal name";
            return;
        }
        if (v.kind == Val.Kind.STATIC_FIELD) {
            e.aliasOf = v.owner + "." + v.name;
            Entry alias = byField.get(e.aliasOf);
            if (alias != null && alias.rawName != null) {
                e.rawName = alias.rawName; e.concreteClass = alias.concreteClass;
                e.unresolvedReason = null;
                return;
            }
            e.unresolvedReason = "aliases " + JarIndex.dotted(v.owner) + "." + v.name + " which is itself unresolved";
            return;
        }
        e.unresolvedReason = "value is " + v;
    }

    /**
     * One bounded hop into {@code internalClassName}'s own {@code <init>} (matching {@code argc}
     * formal parameters, since a field-assignment site's constructor call is what tells us which
     * overload ran): does it call {@code this.setUnlocalizedName(...)}/{@code this.setBlockName(...)}
     * with a literal string anywhere in its own body? Returns that literal, or {@code null} —
     * never guesses. {@code hopsLeft} is always {@link #CTOR_HOP_DEPTH} today (a single hop); the
     * parameter exists so the bound stays an explicit, named, easily-audited constant rather than a
     * magic {@code 1} buried in the call site.
     */
    private String scanOwnConstructorForName(String internalClassName, int argc, int hopsLeft) {
        if (hopsLeft <= 0) return null;
        ClassNode cn = jar.cls(internalClassName);
        if (cn == null) return null;
        for (MethodNode mn : cn.methods) {
            if (!"<init>".equals(mn.name)) continue;
            if (Type.getArgumentTypes(mn.desc).length != argc) continue;
            String found = selfNameLiteralIn(cn, mn);
            if (found != null) return found;
        }
        return null;
    }

    /** Scans one method body for {@code this.setUnlocalizedName("lit")}/{@code this.setBlockName("lit")}. */
    private String selfNameLiteralIn(ClassNode cn, MethodNode mn) {
        String[] found = new String[1];
        MethodSim.run(cn, mn, (insn, stack, locals) -> {
            if (found[0] != null) return;
            int op = insn.getOpcode();
            if (op != Opcodes.INVOKEVIRTUAL && op != Opcodes.INVOKESPECIAL) return;
            MethodInsnNode m = (MethodInsnNode) insn;
            if (!("func_77655_b".equals(m.name) || "setUnlocalizedName".equals(m.name)
                    || "func_149663_c".equals(m.name) || "setBlockName".equals(m.name))) return;
            if (Type.getArgumentTypes(m.desc).length != 1 || stack.size() < 2) return;
            Val arg = stack.get(stack.size() - 1);
            Val recv = stack.get(stack.size() - 2);
            if (recv.kind == Val.Kind.THIS && arg.kind == Val.Kind.STRING) found[0] = arg.stringValue;
        });
        return found[0];
    }

    /**
     * Joins every entry's LOCAL registry-name fragment against the snapshot's real, already-
     * namespaced ids ({@link Snapshot#resolveLocalName}) — see the class javadoc for why a
     * namespace is never fabricated here. Tries, in order: the vanilla {@code item.}/{@code tile.}
     * -prefixed convention (when {@code setUnlocalizedName}/{@code setBlockName} was captured),
     * that same raw name unprefixed (in case a mod overrides {@code getUnlocalizedName()} to skip
     * the vanilla prefix), and — when the field was never renamed via a builder chain at all — the
     * literal name {@link RegistryScanner} saw passed directly into the registration call itself
     * (Iron Chests' shape: the name is set inside the holder's own constructor, and the
     * registration call takes an explicit literal, not {@code getUnlocalizedName()}).
     */
    public void resolveIds(Snapshot snap, RegistryScanner reg) { resolveIds(snap, reg, null); }

    /**
     * Like {@link #resolveIds(Snapshot, RegistryScanner)}, but also tries
     * {@link Snapshot#dominantLocalPrefix} candidates, hinted by {@code modIdHint} (the target
     * mod's own id, when known — e.g. derived from the snapshot file's {@code <modid>-snapshot.json}
     * naming convention already used throughout this harness). This closes a distinct generic gap
     * from the {@code tile./item.} vanilla convention: some mods prepend their OWN short tag to
     * every local registry name instead (Chisel: {@code "chisel." + name}), which no static/local
     * literal capture alone can recover — the tag is only visible by looking at the mod's OWN
     * already-registered ids, never guessed or hardcoded.
     */
    public void resolveIds(Snapshot snap, RegistryScanner reg, String modIdHint) {
        String commonPrefix = snap.dominantLocalPrefix(modIdHint);
        String rawPrefix = dominantRawNamePrefix();
        for (Entry e : byField.values()) {
            List<String> candidates = new ArrayList<>();
            if (e.rawName != null) {
                candidates.add((e.isBlock ? "tile." : "item.") + e.rawName);
                candidates.add(e.rawName);
            }
            RegistryScanner.Reg site = e.isBlock ? reg.blockFieldToReg.get(e.fieldOwner + "." + e.fieldName)
                    : reg.itemFieldToReg.get(e.fieldOwner + "." + e.fieldName);
            if (site != null && site.registryName != null) candidates.add(site.registryName);
            if (commonPrefix != null) {
                if (e.rawName != null) candidates.add(commonPrefix + "." + e.rawName);
                if (site != null && site.registryName != null) candidates.add(commonPrefix + "." + site.registryName);
            }
            // Naming-convention gap (mandate #1, Railcraft's remaining unattributed items):
            // Railcraft's own registration wrapper (mods.railcraft.common.plugins.forge.
            // ItemRegistry.registerItem, confirmed by direct bytecode inspection) runs the
            // ctor-hop-captured raw name through MiscTools.cleanTag(String), which strips the
            // mod's OWN "railcraft." self-tag out of the local name entirely before ever applying
            // vanilla's item./tile. convention — so "railcraft.dust" registers as plain "dust",
            // not "item.railcraft.dust" nor "railcraft.dust". No literal ("railcraft", the regex,
            // the class name) is hardcoded here: {@link #dominantRawNamePrefix} derives the same
            // self-tag segment purely from this mod's OWN already-captured raw names (the same
            // ones the vanilla-prefix candidates above already tried and failed with), so this is
            // a second, LOWER-PRIORITY candidate tried only once those fail, not a replacement.
            if (rawPrefix != null && e.rawName != null && e.rawName.startsWith(rawPrefix + ".")) {
                candidates.add(e.rawName.substring(rawPrefix.length() + 1));
            }

            String resolved = null;
            for (String c : candidates) {
                resolved = snap.resolveLocalName(c);
                if (resolved != null) break;
            }
            if (resolved != null) {
                e.id = resolved;
                e.unresolvedReason = null;
            } else if (e.unresolvedReason == null) {
                e.unresolvedReason = candidates.isEmpty()
                        ? "no local registry-name candidate at all (not renamed, and no literal"
                          + " registration-call name found either)"
                        : "no snapshot id matches local name candidate(s) " + candidates;
            }
        }
        // alias fixpoint for ids specifically (a field whose own rawName never resolved but whose
        // alias source did, once the source's id is known).
        for (int pass = 0; pass < 5; pass++) {
            boolean changed = false;
            for (Entry e : byField.values()) {
                if (e.id != null || e.aliasOf == null) continue;
                Entry src = byField.get(e.aliasOf);
                if (src != null && src.id != null) {
                    e.id = src.id; e.unresolvedReason = null;
                    changed = true;
                }
            }
            if (!changed) break;
        }
        for (Entry e : byField.values()) if (e.id == null) unresolved.add(e);
    }

    /**
     * The single most common first {@code "."}-delimited segment across every entry's own
     * {@link Entry#rawName} (the literal captured directly from bytecode — a builder-chain call,
     * or the bounded ctor-hop scan — BEFORE any vanilla {@code item./tile.} prefix is applied or
     * any namespace is joined). This is the mirror image of {@link Snapshot#dominantLocalPrefix}:
     * that method looks at the target's OWN already-registered ids (and explicitly excludes
     * {@code tile}/{@code item} because those come from vanilla, not the mod); this one looks at
     * the mod's OWN capture of what it told the game to call things, which never carries a vanilla
     * prefix at all (those are added elsewhere, by the candidate list in {@link #resolveIds}), so
     * there is nothing vanilla to exclude here. A mod that tags every internal name with its own
     * short id (Railcraft: {@code "railcraft.cube"}, {@code "railcraft.dust"}) surfaces that tag
     * here regardless of whether the REGISTERED id keeps it (Railcraft's blocks do) or strips it
     * (Railcraft's items do, via a registration wrapper that runs the raw name through a
     * tag-cleaning helper before ever calling {@code GameRegistry} — confirmed by direct bytecode
     * inspection of {@code mods.railcraft.common.plugins.forge.ItemRegistry.registerItem} and
     * {@code mods.railcraft.common.util.misc.MiscTools.cleanTag}, neither of which is named here:
     * the segment itself comes only from this mod's own already-captured raw names, never from a
     * hardcoded literal). Returns {@code null} — never a guess — unless one segment is a clear
     * majority (&gt;=50%) of at least {@link #RAW_PREFIX_MIN_SAMPLES} sampled raw names.
     */
    String dominantRawNamePrefix() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        int total = 0;
        for (Entry e : byField.values()) {
            if (e.rawName == null) continue;
            int d = e.rawName.indexOf('.');
            if (d <= 0) continue; // no segment to split off, or an empty leading segment
            counts.merge(e.rawName.substring(0, d), 1, Integer::sum);
            total++;
        }
        if (total < RAW_PREFIX_MIN_SAMPLES) return null;
        String best = null; int bestCount = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet())
            if (e.getValue() > bestCount) { best = e.getKey(); bestCount = e.getValue(); }
        return best != null && bestCount * 2 >= total ? best : null;
    }
}
