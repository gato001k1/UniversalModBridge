package dev.umb.rendermap;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Moving-parts lane: resolves the <em>dynamic</em> GL arguments of TESR render methods into
 * symbolic expressions over tile-entity fields plus frame time, per {@code renderPart} group.
 *
 * <p>What this is for: a legacy TESR computes per-frame transforms from TE fields
 * ({@code prevRotation + (rotation - prevRotation) * partialTicks}), but the base sidecar
 * ({@code renderer-transforms.json}) records those arguments as {@code null} and the runtime
 * skips the whole op (counted as {@code dynamicSkipped}). This resolver replays the same
 * method bodies with {@code MethodSim}'s opt-in provenance tracking (off for every other
 * caller) and binds each dynamic argument to one of a small set of evaluable leaves:</p>
 * <ul>
 *   <li>TE field reads (including short dotted hop chains), by (owner, name, desc);</li>
 *   <li>the {@code partialTicks} float parameter and the {@code x/y/z} double parameters;</li>
 *   <li>{@code System.currentTimeMillis()} (same-JVM wall clock on both sides);</li>
 *   <li>{@code world.getWorldTime()/getTotalWorldTime()} chains off the TE;</li>
 *   <li>a whitelist of pure numeric calls ({@code Math.toDegrees/toRadians/sin/cos/abs},
 *       {@code MathHelper.clamp_float/clamp_double/clamp_int}, matched by owner plus SRG
 *       <em>and</em> deobfuscated name with exact descriptor);</li>
 *   <li>float/int/double arithmetic, negation and numeric conversions as operator trees.</li>
 * </ul>
 *
 * <p>Everything else - interface dispatches (model/animation engines), loops and iterators,
 * call-rooted values, in-method field writes, missing parameters - stays an honest skip with a
 * per-reason count, exactly like the base sidecar's {@code null} convention. Bounds (see the
 * {@code MAX_*} constants): expression depth/width, groups and ops per group,
 * helper depth (reuses {@link RendererTransformExtractor#MAX_HELPER_CALL_DEPTH}). No mod names,
 * no per-mod branches anywhere: TE/partial/xyz roles come from the method descriptor plus
 * {@link JarIndex}'s own hierarchy walk to {@code net/minecraft/tileentity/TileEntity}.</p>
 */
public final class DynamicOpResolver {

    /** Maximum {@link Expr} tree depth (root = 1). Deeper trees become skips, counted. */
    static final int MAX_EXPR_DEPTH = 8;
    /** Maximum nodes in one argument's expression tree. Bigger trees become skips, counted. */
    static final int MAX_EXPR_NODES = 64;
    /** Maximum dotted hops in one field chain (e.g. {@code currentAnimation.startMillis}). */
    static final int MAX_FIELD_HOPS = 3;
    /** Maximum renderPart groups recorded per method; more become ungrouped, counted. */
    static final int MAX_GROUPS_PER_METHOD = 32;
    /** Maximum ops stored per group prefix; longer prefixes are truncated, counted. */
    static final int MAX_OPS_PER_GROUP = 256;

    private static final String TE_INTERNAL = "net/minecraft/tileentity/TileEntity";
    private static final String WORLD_INTERNAL = "net/minecraft/world/World";
    private static final String MATH_INTERNAL = "java/lang/Math";
    private static final String MATH_HELPER = "net/minecraft/util/MathHelper";
    private static final String SYSTEM_INTERNAL = "java/lang/System";

    private DynamicOpResolver() {
    }

    // ------------------------------------------------------------- expression model

    /** One server-evaluated animation channel (see class javadoc pattern). */
    public static final class Channel {
        public final String key;
        public final String staticOwner;
        public final String staticMethod;
        public final String stringArg;
        public final List<String> animHops;
        /** Null unless the animation object is packet-installed (see {@link AnimRecipe}). */
        public final AnimRecipe anim;
        Channel(String key, String staticOwner, String staticMethod, String stringArg,
                List<String> animHops) {
            this(key, staticOwner, staticMethod, stringArg, animHops, null);
        }
        Channel(String key, String staticOwner, String staticMethod, String stringArg,
                List<String> animHops, AnimRecipe anim) {
            this.key = key;
            this.staticOwner = staticOwner;
            this.staticMethod = staticMethod;
            this.stringArg = stringArg;
            this.animHops = animHops;
            this.anim = anim;
        }
    }

    /**
     * Door-live follow-up: how the server rebuilds a packet-installed animation object it
     * never receives (a door's {@code currentAnimation} is set only in the network
     * {@code handleNewState}, so the server-side field is always null and the track would
     * evaluate to its all-default array forever). Every element is proven in bytecode, never
     * mod-specific: the provider call is the one the packet method uses to assign the very
     * field the channel reads (its incoming-state argument proven by the same method storing
     * it to the state field); the clock is the static no-arg {@code ()J} call the evaluator
     * itself reads; the clock field is the {@code long} the evaluator reads off the animation
     * object. Absent (null) when the shape is not proven - the channel then keeps today's
     * null-means-default behaviour.
     */
    public static final class AnimRecipe {
        public final String providerOwner;
        public final String providerMethod;
        public final List<String> receiverHops;
        public final List<String> receiverKinds;
        /** Per provider argument: hops resolving the argument off the live tile. */
        public final List<List<String>> argHops;
        public final List<List<String>> argKinds;
        public final String clockOwner;
        public final String clockMethod;
        public final String clockField;
        AnimRecipe(String providerOwner, String providerMethod,
                   List<String> receiverHops, List<String> receiverKinds,
                   List<List<String>> argHops, List<List<String>> argKinds,
                   String clockOwner, String clockMethod, String clockField) {
            this.providerOwner = providerOwner;
            this.providerMethod = providerMethod;
            this.receiverHops = receiverHops;
            this.receiverKinds = receiverKinds;
            this.argHops = argHops;
            this.argKinds = argKinds;
            this.clockOwner = clockOwner;
            this.clockMethod = clockMethod;
            this.clockField = clockField;
        }
    }

    /**
     * Door-live follow-up round 3: the TESR-side base matrix a dispatched helper assumes.
     * The TESR centers the block and applies a meta-selected facing rotate, then calls the
     * helper - but helper-draw replay starts from the entry pose (block corner, no facing),
     * so without this every group renders rotated and shifted (live: the whole door as one
     * edge-on slab). Both parts are proven, never guessed: the centering is the constant
     * remainder of the leading anchored translate with world positions zeroed (the BER
     * positions by block itself), and the facing map comes from a tableswitch/
     * lookupswitch on tile-meta arithmetic with one constant rotate per case. Absent
     * (null) when unproven - today's replay then applies unchanged.
     */
    public static final class TesrPrefix {
        /** Centering translate with positions zeroed, or null. */
        public final double[] translate;
        /** The subtracted meta base of the facing switch, or null when no facing map. */
        public final Integer metaBase;
        /** Switch case -> const rotate args [angleDeg, x, y, z]; empty when none. */
        public final Map<Integer, double[]> facing;
        TesrPrefix(double[] translate, Integer metaBase, Map<Integer, double[]> facing) {
            this.translate = translate;
            this.metaBase = metaBase;
            this.facing = facing;
        }
    }

    /**
     * Door-live follow-up: a render dispatch through a helper object. The TESR does the
     * positioning (centering + facing) then calls {@code render(te, buf)} on an object from
     * a zero-arg call (a door's {@code doorType.getSEDNARenderer()}), and the motion lives in
     * the helper's method - which the per-renderer sidecar row never sees (live 2026-09-24:
     * the BER unioned every same-TE helper and drew five overlapping doors). The runtime asks
     * the live tile for the helper class through this exact call and draws only its draws.
     */
    public static final class Dispatch {
        /** Hops resolving the dispatch receiver off the live tile ("field"/"accessor"). */
        public final List<String> objectHops;
        public final List<String> objectKinds;
        /** Internal owner + name of the zero-arg call returning the helper. */
        public final String owner;
        public final String method;
        Dispatch(List<String> objectHops, List<String> objectKinds, String owner, String method) {
            this.objectHops = objectHops;
            this.objectKinds = objectKinds;
            this.owner = owner;
            this.method = method;
        }
    }

    static final class ChannelRefExpr extends Expr {
        final String key;
        /** True when substituted for a poisoned multi-written local (vs read directly). */
        boolean substituted;
        ChannelRefExpr(String key) {
            this(key, false);
        }
        ChannelRefExpr(String key, boolean substituted) {
            this.key = key;
            this.substituted = substituted;
        }
        @Override JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("k", "channel");
            o.addProperty("key", key);
            return o;
        }
        @Override void collectFields(Map<String, List<String>> out) { }
        @Override boolean isAnchored() { return false; }
    }

    /** One evaluable (or explicitly skipped) GL argument. Null {@link Arg#expr} = skip. */
    public static final class Arg {
        public final Expr expr;
        /** Present exactly when {@link #expr} is null: why this argument stays dynamic. */
        public final String skipReason;
        Arg(Expr expr, String skipReason) {
            this.expr = expr;
            this.skipReason = skipReason;
        }
    }

    /** Symbolic expression tree. Serialised to JSON by {@link #toJson()}. */
    public static abstract class Expr {
        abstract JsonObject toJson();
        /** All (owner, name) field leaves, for the sync field set. */
        abstract void collectFields(Map<String, List<String>> out);
        /** True when the tree reads a world-anchor positional (x/y/z) the BER must skip. */
        abstract boolean isAnchored();
    }

    static final class ConstExpr extends Expr {
        final double value;
        ConstExpr(double value) { this.value = value; }
        @Override JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("k", "const");
            o.addProperty("v", value);
            return o;
        }
        @Override void collectFields(Map<String, List<String>> out) { }
        @Override boolean isAnchored() { return false; }
    }

    static final class FieldExpr extends Expr {
        /** Dotted owner chain, e.g. {@code ["currentAnimation", "startMillis"]} (leaf last). */
        final List<String> owners;
        final List<String> names;
        final String desc;
        FieldExpr(List<String> owners, List<String> names, String desc) {
            this.owners = owners;
            this.names = names;
            this.desc = desc;
        }
        /** Sync key: simple owner names + field path, e.g. {@code TileEntityMachineRadarNT.rotation}. */
        String key() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < owners.size(); i++) {
                if (i > 0) sb.append('.');
                String o = owners.get(i);
                sb.append(o.substring(o.lastIndexOf('/') + 1));
            }
            sb.append('.').append(names.get(names.size() - 1));
            return sb.toString();
        }
        @Override JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("k", "field");
            o.addProperty("key", key());
            JsonArray hops = new JsonArray();
            for (String n : names) hops.add(n);
            o.add("hops", hops);
            o.addProperty("desc", desc);
            return o;
        }
        @Override void collectFields(Map<String, List<String>> out) { out.putIfAbsent(key(), names); }
        @Override boolean isAnchored() { return false; }
    }

    static final class ParamExpr extends Expr {
        /** "partial", "pos0", "pos1" or "pos2". */
        final String role;
        ParamExpr(String role) { this.role = role; }
        @Override JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("k", role);
            return o;
        }
        @Override void collectFields(Map<String, List<String>> out) { }
        @Override boolean isAnchored() { return role.startsWith("pos"); }
    }

    static final class NowExpr extends Expr {
        @Override JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("k", "nowMillis");
            return o;
        }
        @Override void collectFields(Map<String, List<String>> out) { }
        @Override boolean isAnchored() { return false; }
    }

    static final class WorldTimeExpr extends Expr {
        final boolean total;
        WorldTimeExpr(boolean total) { this.total = total; }
        @Override JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("k", "worldTime");
            o.addProperty("total", total);
            return o;
        }
        @Override void collectFields(Map<String, List<String>> out) { }
        @Override boolean isAnchored() { return false; }
    }

    static final class CallExpr extends Expr {
        final String owner;
        final String name;
        final List<Expr> args;
        CallExpr(String owner, String name, List<Expr> args) {
            this.owner = owner;
            this.name = name;
            this.args = args;
        }
        @Override JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("k", "call");
            o.addProperty("o", owner);
            o.addProperty("m", name);
            JsonArray a = new JsonArray();
            for (Expr e : args) a.add(e.toJson());
            o.add("a", a);
            return o;
        }
        @Override void collectFields(Map<String, List<String>> out) { for (Expr e : args) e.collectFields(out); }
        @Override boolean isAnchored() {
            for (Expr e : args) if (e.isAnchored()) return true;
            return false;
        }
    }

    static final class OpExpr extends Expr {
        final String op;
        final List<Expr> args;
        OpExpr(String op, List<Expr> args) {
            this.op = op;
            this.args = args;
        }
        @Override JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("k", "op");
            o.addProperty("o", op);
            JsonArray a = new JsonArray();
            for (Expr e : args) a.add(e.toJson());
            o.add("a", a);
            return o;
        }
        @Override void collectFields(Map<String, List<String>> out) { for (Expr e : args) e.collectFields(out); }
        @Override boolean isAnchored() {
            for (Expr e : args) if (e.isAnchored()) return true;
            return false;
        }
    }

    /** One GL op with per-argument resolution. */
    public static final class Op {
        public final String method;
        public final String gl;
        public final List<Arg> args;
        /** renderPart group drawn with the matrix in effect here, or null when unattributed. */
        public final String group;
        /**
         * True when any resolved argument reads a world-anchor positional (x/y/z): the BER
         * positions by block itself (like today's static path, which skips these ops), so it
         * must skip anchored ops too rather than evaluate them to a shifted pose.
         */
        public final boolean anchored;
        Op(String method, String gl, List<Arg> args, String group) {
            this.method = method;
            this.gl = gl;
            this.args = args;
            this.group = group;
            boolean anchor = false;
            for (Arg a : args) {
                if (a.expr != null && a.expr.isAnchored()) {
                    anchor = true;
                    break;
                }
            }
            this.anchored = anchor;
        }
        boolean resolved() {
            for (Arg a : args) if (a.expr == null) return false;
            return true;
        }
    }

    /** Everything resolved for one TESR render method. */
    public static final class Resolved {
        public final String teClass;
        public final int partialArg;
        public final List<Op> ops;
        public final Map<String, List<String>> fields;
        public final Map<String, Channel> channels;
        public final int resolvedArgs;
        public final int substituted;
        public final Map<String, Integer> skipped;
        /** Null when the method draws everything itself (no helper dispatch). */
        public final Dispatch dispatch;
        /** TESR-side base matrix for dispatched helpers; null when unproven or no dispatch. */
        public final TesrPrefix prefix;
        Resolved(String teClass, int partialArg, List<Op> ops, Map<String, List<String>> fields,
                 Map<String, Channel> channels, int resolvedArgs, int substituted,
                 Map<String, Integer> skipped) {
            this(teClass, partialArg, ops, fields, channels, resolvedArgs, substituted, skipped, null, null);
        }
        Resolved(String teClass, int partialArg, List<Op> ops, Map<String, List<String>> fields,
                 Map<String, Channel> channels, int resolvedArgs, int substituted,
                 Map<String, Integer> skipped, Dispatch dispatch) {
            this(teClass, partialArg, ops, fields, channels, resolvedArgs, substituted, skipped, dispatch, null);
        }
        Resolved(String teClass, int partialArg, List<Op> ops, Map<String, List<String>> fields,
                 Map<String, Channel> channels, int resolvedArgs, int substituted,
                 Map<String, Integer> skipped, Dispatch dispatch, TesrPrefix prefix) {
            this.teClass = teClass;
            this.partialArg = partialArg;
            this.ops = ops;
            this.fields = fields;
            this.channels = channels;
            this.resolvedArgs = resolvedArgs;
            this.substituted = substituted;
            this.skipped = skipped;
            this.dispatch = dispatch;
            this.prefix = prefix;
        }
    }

    // ------------------------------------------------------------------ entry

    /**
     * Resolves one TESR render method. {@code teArg} is the 0-based argument index of the
     * TileEntity parameter ({@link Val.Kind#PARAM} carries argument indices, not slots - mixing
     * the two silently unbinds every field), or -1 when the method has none (every dynamic arg
     * then skips); {@code partialArg} the trailing float parameter, {@code posArgs} the double
     * slots by role (possibly empty).
     */
    static Resolved resolve(ClassNode cn, MethodNode mn, JarIndex jar, int teArg,
                            int partialArg, Map<Integer, String> posArgs) {
        List<Op> ops = new ArrayList<>();
        Map<String, List<String>> fields = new LinkedHashMap<>();
        Map<String, Integer> skipped = new TreeMap<>();
        int[] resolvedArgs = {0};
        int[] substituted = {0};
        // Fields written anywhere in this method make same-name reads ambiguous (the value
        // depends on which write ran last) - pre-scan once, then refuse those reads honestly.
        Set<String> writtenFields = writtenFields(mn);
        // renderPart group tracking: each GL op remembers the group drawn at the NEXT renderPart
        // is unknowable in one pass, so record (opIndex, group) markers and expand prefixes after.
        List<Op> chronological = new ArrayList<>();
        List<GroupMark> marks = new ArrayList<>();
        final Map<String, Integer> groupCounts = new TreeMap<>();
        final String[] concreteTe = {null};
        final Map<String, Channel> channels = new LinkedHashMap<>();
        // Poisoned-local substitution (door-live lane): locals written from several
        // instructions collapse to UNKNOWN, which would hide a channel arm stored into them
        // (the door's `slide` local). Remember every stored value per slot; at a poisoned
        // use, a slot whose stores name exactly one distinct channel substitutes it.
        final Map<Integer, List<Val>> slotStores = new LinkedHashMap<>();
        // Door-live follow-up: zero-arg instance calls with their receivers, keyed by short
        // name. Lets a later interface-`render(te, buf)` call resolve which call produced its
        // helper object (the reason string on the collapsed UNKNOWN keeps only the short name,
        // so full owner + receiver are recorded here; ambiguous keys are disqualified).
        final Map<String, List<ZeroCall>> zeroArgCalls = new LinkedHashMap<>();
        final Dispatch[] dispatchFound = {null};
        // Door-live round 3: switch inputs for the TESR caller-prefix facing map.
        final List<SwitchSite> switchSites = new ArrayList<>();
        MethodSim.run(cn, mn, jar, false, true, (insn, stack, locals) -> {
            if (insn instanceof org.objectweb.asm.tree.VarInsnNode var
                    && isStore(insn.getOpcode()) && !stack.isEmpty()) {
                Val stored = stack.get(stack.size() - 1);
                slotStores.computeIfAbsent(var.var, k -> new ArrayList<>()).add(stored);
            }
            if (insn instanceof org.objectweb.asm.tree.TableSwitchInsnNode
                    || insn instanceof org.objectweb.asm.tree.LookupSwitchInsnNode) {
                if (!stack.isEmpty()) {
                    switchSites.add(new SwitchSite(stack.get(stack.size() - 1), insn));
                }
            }
            if (insn instanceof org.objectweb.asm.tree.TypeInsnNode type
                    && insn.getOpcode() == Opcodes.CHECKCAST) {
                // The TESR idiom `checkcast SpecificTE` on the TE parameter names the concrete
                // class whose fields the following getfields read (the declared parameter type
                // is usually the base TileEntity). Recorded for the sync mapping; harmless if
                // several appear (last wins, counted).
                if (!stack.isEmpty()) {
                    Val top = stack.get(stack.size() - 1);
                    if (top != null && top.kind == Val.Kind.PARAM
                            && top.numberValue.intValue() == teArg) {
                        concreteTe[0] = type.desc;
                    }
                }
                return;
            }
            if (!(insn instanceof MethodInsnNode call)) return;
            if ("org/lwjgl/opengl/GL11".equals(call.owner)) {
                if (glStateOp(call, stack, mn.name, chronological, skipped, resolvedArgs)) return;
                String op = glOp(call.name);
                if (op == null) return;
                int arity = arity(op);
                List<Arg> args = new ArrayList<>();
                for (int i = 0; i < arity; i++) {
                    Val v = stack.size() >= arity ? stack.get(stack.size() - arity + i) : null;
                    Arg a = toArg(v, teArg, partialArg, posArgs, writtenFields, channels,
                            slotStores);
                    if (a.expr == null) {
                        skipped.merge(a.skipReason, 1, Integer::sum);
                    } else {
                        a.expr.collectFields(fields);
                        resolvedArgs[0]++;
                        substituted[0] += countSubstituted(a.expr);
                    }
                    args.add(a);
                }
                chronological.add(new Op(mn.name, op, args, null));
                return;
            }
            if ("renderPart".equals(call.name) && "(Ljava/lang/String;)V".equals(call.desc)) {
                String group = null;
                if (!stack.isEmpty()) {
                    Val v = stack.get(stack.size() - 1);
                    if (v != null && v.kind == Val.Kind.STRING && v.stringValue != null) {
                        group = v.stringValue;
                    }
                }
                if (group == null) {
                    skipped.merge("non-const-part-name", 1, Integer::sum);
                } else {
                    groupCounts.merge(group, 1, Integer::sum);
                }
                marks.add(new GroupMark(chronological.size(), group));
            }
            recordZeroArgCall(call, stack, zeroArgCalls);
            Dispatch d = detectDispatch(call, stack, teArg, slotStores, zeroArgCalls);
            if (d != null) {
                if (dispatchFound[0] == null) {
                    dispatchFound[0] = d;
                } else {
                    skipped.merge("dispatch-extra", 1, Integer::sum);
                }
            }
        });
        // Expand group prefixes: group G drawn at op index i replays ops[0..i].
        Map<String, List<Op>> groupPrefixes = new LinkedHashMap<>();
        for (GroupMark mark : marks) {
            if (mark.group == null) continue;
            if (groupPrefixes.size() >= MAX_GROUPS_PER_METHOD
                    && !groupPrefixes.containsKey(mark.group)) {
                skipped.merge("too-many-groups", 1, Integer::sum);
                continue;
            }
            List<Op> prefix = groupPrefixes.computeIfAbsent(mark.group, k -> new ArrayList<>());
            int end = Math.min(mark.opIndex, MAX_OPS_PER_GROUP);
            if (mark.opIndex > MAX_OPS_PER_GROUP) skipped.merge("group-prefix-truncated", 1, Integer::sum);
            for (int i = prefix.size(); i < end; i++) {
                prefix.add(chronological.get(i));
            }
        }
        // Flatten: chronological ops unattributed + one copy per group with its prefix.
        for (Op op : chronological) ops.add(new Op(op.method, op.gl, op.args, null));
        for (Map.Entry<String, List<Op>> e : groupPrefixes.entrySet()) {
            for (Op op : e.getValue()) {
                if (ops.size() >= MAX_OPS_PER_GROUP * (MAX_GROUPS_PER_METHOD + 1)) break;
                ops.add(new Op(op.method, op.gl, op.args, e.getKey()));
            }
        }
        String teClass = teArg >= 0 ? teClassOf(cn, mn, teArg, jar) : null;
        if (concreteTe[0] != null) teClass = JarIndex.dotted(concreteTe[0]);
        TesrPrefix prefix = null;
        if (dispatchFound[0] != null) {
            prefix = tesrPrefix(mn, chronological, switchSites, skipped);
        }
        // Door-live follow-up: packet-installed animation recipes, proven per channel.
        if (teClass != null && !channels.isEmpty()) {
            String teInternal = teClass.replace('.', '/');
            for (Map.Entry<String, Channel> e : channels.entrySet()) {
                Channel ch = e.getValue();
                if (ch.anim != null) continue;
                AnimRecipe r = animRecipe(jar, teInternal, ch.animHops, ch.staticOwner,
                        ch.staticMethod);
                if (r != null) {
                    e.setValue(new Channel(ch.key, ch.staticOwner, ch.staticMethod, ch.stringArg,
                            ch.animHops, r));
                }
            }
        }
        return new Resolved(teClass, partialArg, ops, fields, channels, resolvedArgs[0], substituted[0], skipped,
                dispatchFound[0], prefix);
    }

    private static final class GroupMark {
        final int opIndex;
        final String group;
        GroupMark(int opIndex, String group) {
            this.opIndex = opIndex;
            this.group = group;
        }
    }

    private static final class NodeCounter {
        int nodes;
    }

    // ------------------------------------------------------------- role binding

    /** 0-based argument index of the TileEntity-typed parameter, or -1. Uses {@link JarIndex}'s own
     *  cycle-guarded hierarchy walk, so no bound of ours is needed here. */
    static int teArg(MethodNode mn, JarIndex jar) {
        if (jar == null) return -1;
        Type[] params = Type.getArgumentTypes(mn.desc);
        for (int ai = 0; ai < params.length; ai++) {
            if (params[ai].getSort() == Type.OBJECT
                    && jar.isSubclassOf(params[ai].getInternalName(), TE_INTERNAL)) {
                return ai;
            }
        }
        return -1;
    }

    /** Dotted TE class name for the argument, best effort (null when unresolvable). */
    private static String teClassOf(ClassNode cn, MethodNode mn, int teArg, JarIndex jar) {
        Type[] params = Type.getArgumentTypes(mn.desc);
        if (teArg < 0 || teArg >= params.length) return null;
        return JarIndex.dotted(params[teArg].getInternalName());
    }

    /** 0-based argument index of the trailing float parameter (partialTicks), or -1. */
    static int partialArg(MethodNode mn) {
        Type[] params = Type.getArgumentTypes(mn.desc);
        if (params.length == 0) return -1;
        if (params[params.length - 1].getSort() != Type.FLOAT) return -1;
        return params.length - 1;
    }

    /** 0-based argument indices of the double parameters by order (the x/y/z convention
     *  needs exactly three; anything else yields no positional roles, honestly). */
    static Map<Integer, String> posArgs(MethodNode mn) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Type[] params = Type.getArgumentTypes(mn.desc);
        List<Integer> doubleArgs = new ArrayList<>();
        for (int ai = 0; ai < params.length; ai++) {
            if (params[ai].getSort() == Type.DOUBLE) doubleArgs.add(ai);
        }
        if (doubleArgs.size() == 3) {
            out.put(doubleArgs.get(0), "pos0");
            out.put(doubleArgs.get(1), "pos1");
            out.put(doubleArgs.get(2), "pos2");
        }
        return out;
    }

    private static Set<String> writtenFields(MethodNode mn) {
        Set<String> out = new LinkedHashSet<>();
        if (mn.instructions == null) return out;
        for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof org.objectweb.asm.tree.FieldInsnNode f
                    && in.getOpcode() == Opcodes.PUTFIELD) {
                out.add(f.owner + "." + f.name);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ Val to Expr

    /**
     * Door-live follow-up (GL-state ops): cull-face toggles and clip planes as replayable
     * constant ops. HBM renders single-sided meshes with culling disabled and trims sliding
     * panels with fixed-function clip planes; the matrix-only extractor used to drop both,
     * so the replay diverged (panels vanishing per facing, full panels where HBM shows
     * slivers). Only constant caps/equations resolve - anything dynamic is counted and
     * skipped, never guessed. Consumed args are synthesised as constants, so nothing
     * downstream (prefix expansion, sidecar serialisation, replay evaluation) changes.
     * Returns true when the call was recognised (emitted or honestly skipped).
     */
    private static boolean glStateOp(MethodInsnNode call, List<Val> stack, String method,
            List<Op> chronological, Map<String, Integer> skipped, int[] resolvedArgs) {
        if (("glEnable".equals(call.name) || "glDisable".equals(call.name))
                && "(I)V".equals(call.desc) && !stack.isEmpty()) {
            Integer cap = constInt(stack.get(stack.size() - 1));
            if (cap == null) {
                skipped.merge(call.name + "-nonconst-cap", 1, Integer::sum);
                return true;
            }
            boolean on = "glEnable".equals(call.name);
            if (cap == 2884) { // GL_CULL_FACE
                chronological.add(new Op(method, "glCullFace",
                        List.of(new Arg(new ConstExpr(on ? 1.0 : 0.0), null)), null));
                resolvedArgs[0]++;
                return true;
            }
            if (cap == 12288 || cap == 12289) { // GL_CLIP_PLANE0/1
                chronological.add(new Op(method, "glClipEnable",
                        List.of(new Arg(new ConstExpr((double) cap), null),
                                new Arg(new ConstExpr(on ? 1.0 : 0.0), null)), null));
                resolvedArgs[0] += 2;
                return true;
            }
            skipped.merge(call.name + "-other-cap", 1, Integer::sum);
            return true;
        }
        if ("glClipPlane".equals(call.name) && "(ILjava/nio/DoubleBuffer;)V".equals(call.desc)
                && stack.size() >= 2) {
            Integer cap = constInt(stack.get(stack.size() - 2));
            Val buf = stack.get(stack.size() - 1);
            double[] eq = buf == null ? null : buf.bufferDoubles;
            if (cap == null || eq == null || eq.length != 4
                    || (cap != 12288 && cap != 12289)) {
                skipped.merge("clipplane-nonconst", 1, Integer::sum);
                return true;
            }
            List<Arg> args = new ArrayList<>(5);
            args.add(new Arg(new ConstExpr((double) cap), null));
            for (double d : eq) args.add(new Arg(new ConstExpr(d), null));
            chronological.add(new Op(method, "glClipPlane", args, null));
            resolvedArgs[0] += 5;
            return true;
        }
        return false;
    }

    /** Constant integer stack value, or null. */
    private static Integer constInt(Val v) {
        if (v != null && v.kind == Val.Kind.NUMBER && v.numberValue != null) {
            return Integer.valueOf(v.numberValue.intValue());
        }
        return null;
    }

    private static String glOp(String name) {        switch (name) {
            case "glScalef": case "glScaled": return name;
            case "glTranslatef": case "glTranslated": return name;
            case "glRotatef": case "glRotated": return name;
            case "glPushMatrix": return name;
            case "glPopMatrix": return name;
            default: return null;
        }
    }

    private static int arity(String op) {
        if (op.startsWith("glScale") || op.startsWith("glTranslate")) return 3;
        if (op.startsWith("glRotate")) return 4;
        return 0;
    }

    private static String skipReason(Val v, int teArg) {
        if (v == null) return "missing-stack-value";
        switch (v.kind) {
            case UNKNOWN: return v.reason != null ? v.reason : "unknown";
            case PARAM: return "param-not-te-or-time";
            case CALL: return "call:" + v.owner + "." + v.name;
            case STATIC_FIELD: return "static-field:" + v.owner + "." + v.name;
            case FIELD: return "field-not-te-rooted";
            case EXPR: return "expr-has-dynamic-leaf";
            default: return v.kind.toString().toLowerCase();
        }
    }

    /** Resolves one value to an evaluable argument, carrying the leaf skip reason outward. */
    private static Arg toArg(Val v, int teArg, int partialArg, Map<Integer, String> posArgs,
                             Set<String> writtenFields, Map<String, Channel> channels,
                             Map<Integer, List<Val>> slotStores) {
        NodeCounter counter = new NodeCounter();
        Expr e = toExpr(v, teArg, partialArg, posArgs, writtenFields, channels, slotStores,
                1, counter);
        if (e != null) return new Arg(e, null);
        return new Arg(null, deepReason(v, teArg, partialArg, posArgs, writtenFields, channels, 1,
                new NodeCounter()));
    }
    /**
     * Best-effort leaf reason: descends CALL/EXPR/FIELD nodes to name the first unresolvable
     * leaf instead of blaming the combinator (so counts distinguish "unknown engine call"
     * from "call on an unknown value").
     */
    private static String deepReason(Val v, int teArg, int partialArg, Map<Integer, String> posArgs,
                                     Set<String> writtenFields, Map<String, Channel> channels,
                                     int depth, NodeCounter counter) {
        if (v == null || depth > MAX_EXPR_DEPTH || counter.nodes >= MAX_EXPR_NODES) {
            return v == null ? "missing-stack-value" : "expr-too-big";
        }
        counter.nodes++;
        switch (v.kind) {
            case CALL: {
                if (v.ctorArgs != null) {
                    for (Val o : v.ctorArgs) {
                        Arg a = toArg(o, teArg, partialArg, posArgs, writtenFields, channels, null);
                        if (a.expr == null) return "call:" + v.owner + "." + v.name + "<" + a.skipReason + ">";
                    }
                }
                return "call:" + v.owner + "." + v.name;
            }
            case EXPR: {
                if (v.ctorArgs != null) {
                    for (Val o : v.ctorArgs) {
                        Arg a = toArg(o, teArg, partialArg, posArgs, writtenFields, channels, null);
                        if (a.expr == null) return a.skipReason;
                    }
                }
                return "expr-has-dynamic-leaf";
            }
            case CHANNEL: {
                return "channel-unresolvable";
            }
            case FIELD: {
                List<String> owners = new ArrayList<>();
                List<String> names = new ArrayList<>();
                Val recv = v;
                while (recv != null && recv.kind == Val.Kind.FIELD) {
                    if (owners.size() >= MAX_FIELD_HOPS) return "field-hops-too-deep";
                    owners.add(0, recv.owner);
                    names.add(0, recv.name);
                    recv = recv.inner;
                }
                if (recv == null || recv.kind != Val.Kind.PARAM
                        || recv.numberValue.intValue() != teArg) {
                    return "field-not-te-rooted";
                }
                for (int i = 0; i < owners.size(); i++) {
                    if (writtenFields.contains(owners.get(i) + "." + names.get(i))) {
                        return "field-written-in-method";
                    }
                }
                return "field-unresolvable";
            }
            default:
                return skipReason(v, teArg);
        }
    }

    /**
     * Converts one simulated stack value to an evaluable expression, or null (honest skip).
     * Only leaves rooted at the TE parameter, time sources, constants and whitelisted pure
     * calls resolve; depth and node count are bounded.
     */
    private static Expr toExpr(Val v, int teArg, int partialArg, Map<Integer, String> posArgs,
                               Set<String> writtenFields, Map<String, Channel> channels,
                               Map<Integer, List<Val>> slotStores, int depth, NodeCounter counter) {
        if (v == null || depth > MAX_EXPR_DEPTH || counter.nodes >= MAX_EXPR_NODES) return null;
        counter.nodes++;
        switch (v.kind) {
            case NUMBER:
                return new ConstExpr(v.numberValue.doubleValue());
            case PARAM: {
                int slot = v.numberValue.intValue();
                if (slot == teArg) return null; // the TE object itself is not a number
                if (slot == partialArg) return new ParamExpr("partial");
                String role = posArgs.get(slot);
                if (role != null) return new ParamExpr(role);
                return null;
            }
            case FIELD:
                return fieldExpr(v, teArg, writtenFields, depth, counter);
            case EXPR: {
                List<Expr> ops = new ArrayList<>();
                if (v.ctorArgs != null) {
                    for (Val o : v.ctorArgs) {
                        Expr e = toExpr(o, teArg, partialArg, posArgs, writtenFields, channels,
                                slotStores, depth + 1, counter);
                        if (e == null) return null;
                        ops.add(e);
                    }
                }
                return new OpExpr(v.stringValue, ops);
            }
            case CALL:
                return callExpr(v, teArg, partialArg, posArgs, writtenFields, channels,
                        slotStores, depth, counter);
            case CHANNEL: {
                Channel c = channelDecl(v, teArg);
                if (c == null) return null;
                channels.putIfAbsent(c.key, c);
                return new ChannelRefExpr(c.key);
            }
            default:
                return substitutePoisoned(v, teArg, partialArg, posArgs, writtenFields,
                        channels, slotStores);
        }
    }

    private static Expr fieldExpr(Val v, int teArg, Set<String> writtenFields, int depth,
                                   NodeCounter counter) {
        if (teArg < 0 || depth + 1 > MAX_EXPR_DEPTH) return null;
        // Collect the dotted hop chain root-first; every hop must be a plain field read.
        List<String> owners = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Val recv = v;
        String leafDesc = v.desc;
        int hops = 0;
        while (recv != null && recv.kind == Val.Kind.FIELD) {
            if (++hops > MAX_FIELD_HOPS) return null;
            owners.add(0, recv.owner);
            names.add(0, recv.name);
            recv = recv.inner;
        }
        if (recv == null || recv.kind != Val.Kind.PARAM || recv.numberValue.intValue() != teArg) {
            return null;
        }
        for (int i = 0; i < owners.size(); i++) {
            if (writtenFields.contains(owners.get(i) + "." + names.get(i))) {
                return null;
            }
        }
        return new FieldExpr(owners, names, leafDesc);
    }

    private static Expr callExpr(Val v, int teArg, int partialArg, Map<Integer, String> posArgs,
                                 Set<String> writtenFields, Map<String, Channel> channels,
                                 Map<Integer, List<Val>> slotStores, int depth, NodeCounter counter) {
        String key = v.owner + "." + v.name;
        // System.currentTimeMillis()J: same-JVM wall clock on both sides.
        if ("java/lang/System.currentTimeMillis".equals(key)) return new NowExpr();
        // Whitelisted pure numeric calls (owner + exact descriptor matched).
        String pure = pureCall(v.owner, v.name, v.desc);
        if (pure != null) {
            List<Expr> args = new ArrayList<>();
            if (v.ctorArgs != null) {
                for (Val o : v.ctorArgs) {
                    Expr e = toExpr(o, teArg, partialArg, posArgs, writtenFields, channels,
                            slotStores, depth + 1, counter);
                    if (e == null) return null;
                    args.add(e);
                }
            }
            return new CallExpr(v.owner, v.name, args);
        }
        // world.getWorldTime()/getTotalWorldTime() off tile.getWorldObj(): frame time.
        // Both calls are modelled with the receiver prepended (see MethodSim's opt-in
        // carve-out), so ctorArgs[0] is the world value and ctorArgs[0].ctorArgs[0] the TE.
        if (isWorldTimeCall(v)) {
            if (v.ctorArgs != null && !v.ctorArgs.isEmpty()) {
                Val recv = v.ctorArgs.get(0);
                if (recv != null && recv.kind == Val.Kind.CALL && isGetWorldObj(recv)
                        && recv.ctorArgs != null && !recv.ctorArgs.isEmpty()
                        && isTeRooted(recv.ctorArgs.get(0), teArg)) {
                    return new WorldTimeExpr(v.name.contains("Total"));
                }
            }
            return null;
        }
        return null;
    }

    private static boolean isGetWorldObj(Val v) {
        return v != null
                && ("func_145831_w".equals(v.name) || "getWorldObj".equals(v.name))
                && "()Lnet/minecraft/world/World;".equals(v.desc);
    }

    private static boolean isWorldTimeCall(Val v) {
        if (v == null || !"net/minecraft/world/World".equals(v.owner)) return false;
        if ("func_72820_D".equals(v.name) || "getWorldTime".equals(v.name)) {
            return "()J".equals(v.desc);
        }
        return ("func_82737_E".equals(v.name) || "getTotalWorldTime".equals(v.name))
                && "()J".equals(v.desc);
    }

    private static boolean isTeRooted(Val v, int teArg) {
        return v != null && v.kind == Val.Kind.PARAM && v.numberValue.intValue() == teArg;
    }

    /**
     * Validates a {@link Val.Kind#CHANNEL} node (constant-index read off a static pure call)
     * into a server-evaluated animation channel, or null. The shape is mechanical, never
     * mod-specific: an {@code INVOKESTATIC (String, single-object-arg)} returning
     * {@code double[]} with a string-constant first argument and a TE-field-rooted second
     * argument (bounded hops). Pure function of the tree - registers nothing; callers store.
     */
    private static Channel channelDecl(Val v, int teArg) {
        if (v == null || v.kind != Val.Kind.CHANNEL || v.inner == null
                || v.inner.kind != Val.Kind.CALL) {
            return null;
        }
        Val call = v.inner;
        int index = v.numberValue.intValue();
        if (index < 0 || index >= 32) return null;
        if (call.ctorArgs == null || call.ctorArgs.size() != 2) return null;
        Val strArg = call.ctorArgs.get(0);
        Val animArg = call.ctorArgs.get(1);
        if (strArg == null || strArg.kind != Val.Kind.STRING || strArg.stringValue == null) {
            return null;
        }
        if (call.desc == null || !call.desc.endsWith(")[D")) return null;
        List<String> hops = fieldHops(animArg, teArg);
        if (hops == null || hops.isEmpty()) return null;
        String key = JarIndex.dotted(call.owner) + "." + call.name + "." + strArg.stringValue
                + "[" + index + "]";
        return new Channel(key, JarIndex.dotted(call.owner), call.name, strArg.stringValue, hops);
    }

    /** Dotted hop names for a TE-field-rooted value, or null. Shared with {@link #fieldExpr}. */
    private static List<String> fieldHops(Val v, int teArg) {
        List<String> owners = new ArrayList<>();
        List<String> names = new ArrayList<>();
        Val recv = v;
        int hops = 0;
        while (recv != null && recv.kind == Val.Kind.FIELD) {
            if (++hops > MAX_FIELD_HOPS) return null;
            owners.add(0, recv.owner);
            names.add(0, recv.name);
            recv = recv.inner;
        }
        if (recv == null || recv.kind != Val.Kind.PARAM || recv.numberValue.intValue() != teArg) {
            return null;
        }
        return names;
    }

    private static boolean isStore(int opcode) {
        return opcode == Opcodes.ISTORE || opcode == Opcodes.FSTORE || opcode == Opcodes.ASTORE
                || opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE;
    }

    /**
     * Poisoned-local substitution for animation channels: a multi-written local used in a GL
     * argument resolves to the single channel stored into its slot, letting outer combinators
     * (clamp, negate, scale) compose normally around it. Anything else stays null (honest skip).
     */
    private static Expr substitutePoisoned(Val v, int teArg, int partialArg,
                                           Map<Integer, String> posArgs, Set<String> writtenFields,
                                           Map<String, Channel> channels,
                                           Map<Integer, List<Val>> slotStores) {
        if (v == null || slotStores == null || v.kind != Val.Kind.UNKNOWN || v.reason == null) {
            return null;
        }
        java.util.regex.Matcher m =
                java.util.regex.Pattern.compile("local (\\d+) written from").matcher(v.reason);
        if (!m.find()) return null;
        int slot;
        try {
            slot = Integer.parseInt(m.group(1));
        } catch (NumberFormatException ex) {
            return null;
        }
        List<Val> stores = slotStores.get(slot);
        if (stores == null || stores.isEmpty()) return null;
        String shape = null;
        Expr chosen = null;
        for (Val stored : stores) {
            if (!treeHasChannel(stored, teArg, channels)) continue;
            NodeCounter counter = new NodeCounter();
            Expr e = toExpr(stored, teArg, partialArg, posArgs, writtenFields, channels,
                    slotStores, 1, counter);
            if (e == null) return null;
            String fingerprint = e.toJson().toString();
            if (shape == null) {
                shape = fingerprint;
                chosen = e;
            } else if (!shape.equals(fingerprint)) {
                return null;
            }
        }
        if (chosen == null) return null;
        markSubstituted(chosen);
        return chosen;
    }

    private static boolean treeHasChannel(Val v, int teArg, Map<String, Channel> channels) {
        return !channelsInTree(v, teArg, channels).isEmpty();
    }

    private static void markSubstituted(Expr e) {
        if (e instanceof ChannelRefExpr) {
            ((ChannelRefExpr) e).substituted = true;
            return;
        }
        if (e instanceof CallExpr) {
            for (Expr a : ((CallExpr) e).args) markSubstituted(a);
        } else if (e instanceof OpExpr) {
            for (Expr a : ((OpExpr) e).args) markSubstituted(a);
        }
    }

    /** Channel keys in one stored value tree (registering each valid one). */
    private static Set<String> channelsInTree(Val v, int teArg, Map<String, Channel> channels) {
        Set<String> out = new LinkedHashSet<>();
        collectTreeChannels(v, teArg, channels, out, 0);
        return out;
    }

    private static void collectTreeChannels(Val v, int teArg, Map<String, Channel> channels,
                                            Set<String> out, int depth) {
        if (v == null || depth > MAX_EXPR_DEPTH) return;
        if (v.kind == Val.Kind.CHANNEL) {
            Channel c = channelDecl(v, teArg);
            if (c != null) {
                channels.putIfAbsent(c.key, c);
                out.add(c.key);
            }
            return;
        }
        if (v.ctorArgs != null) {
            for (Val o : v.ctorArgs) collectTreeChannels(o, teArg, channels, out, depth + 1);
        }
        if (v.inner != null
                && (v.kind == Val.Kind.FIELD || v.kind == Val.Kind.DERIVED)) {
            collectTreeChannels(v.inner, teArg, channels, out, depth + 1);
        }
    }

    /** Counts substituted channel refs in one resolved argument (for the honest tally). */
    static int countSubstituted(Expr e) {
        if (e == null) return 0;
        int n = (e instanceof ChannelRefExpr && ((ChannelRefExpr) e).substituted) ? 1 : 0;
        if (e instanceof CallExpr) {
            for (Expr a : ((CallExpr) e).args) n += countSubstituted(a);
        } else if (e instanceof OpExpr) {
            for (Expr a : ((OpExpr) e).args) n += countSubstituted(a);
        }
        return n;
    }

    // ------------------------------------------------- render-dispatch detection

    /** One zero-arg instance call site with its receiver, for dispatch resolution. */
    private static final class ZeroCall {
        final String owner;
        final String method;
        final Val receiver;
        ZeroCall(String owner, String method, Val receiver) {
            this.owner = owner;
            this.method = method;
            this.receiver = receiver;
        }
    }

    /** One hop of a tile-rooted object path: a field read or a zero-arg accessor call. */
    static final class Hop {
        final String name;
        final String kind;
        Hop(String name, String kind) {
            this.name = name;
            this.kind = kind;
        }
    }

    /**
     * Records every zero-arg instance call with the receiver on the stack (pre-consumption,
     * which is exactly when this visitor fires). Keyed by short owner + method because that
     * is all the collapsed result's reason string retains; ambiguous keys disqualify at use.
     */
    private static void recordZeroArgCall(MethodInsnNode call, List<Val> stack,
                                          Map<String, List<ZeroCall>> zeroArgCalls) {
        if (call.getOpcode() == Opcodes.INVOKESTATIC) return;
        if (Type.getReturnType(call.desc).getSort() == Type.VOID) return;
        if (Type.getArgumentTypes(call.desc).length != 0) return;
        if (stack == null || stack.isEmpty()) return;
        String shortOwner = call.owner.substring(call.owner.lastIndexOf('/') + 1);
        zeroArgCalls.computeIfAbsent(shortOwner + "." + call.name, k -> new ArrayList<>())
                .add(new ZeroCall(call.owner, call.name, stack.get(stack.size() - 1)));
    }

    /**
     * Detects a render dispatch through a helper: an interface/virtual {@code render} call
     * taking the tile plus one buffer, whose receiver comes from a zero-arg call. Returns
     * the dispatch descriptor with the receiver resolved to tile-rooted hops, or null.
     * Straight-line code only (locals resolve through unanimous single stores); anything
     * else stays null and the runtime keeps the union fallback.
     */
    private static Dispatch detectDispatch(MethodInsnNode call, List<Val> stack, int teArg,
                                           Map<Integer, List<Val>> slotStores,
                                           Map<String, List<ZeroCall>> zeroArgCalls) {
        int op = call.getOpcode();
        if (op != Opcodes.INVOKEINTERFACE && op != Opcodes.INVOKEVIRTUAL) return null;
        if (!"render".equals(call.name)) return null;
        if (Type.getArgumentTypes(call.desc).length != 2) return null;
        if (teArg < 0 || stack == null || stack.size() < 3) return null;
        Val arg0 = stack.get(stack.size() - 2);
        if (!isTeParam(arg0, teArg, slotStores)) return null;
        Val receiver = stack.get(stack.size() - 3);
        ZeroCall producer = dispatchProducer(receiver, zeroArgCalls);
        if (producer == null) return null;
        List<Hop> hops = hopChain(producer.receiver, teArg, slotStores, zeroArgCalls, 0,
                new java.util.HashSet<Val>());
        if (hops == null) return null;
        List<String> names = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        for (Hop h : hops) {
            names.add(h.name);
            kinds.add(h.kind);
        }
        return new Dispatch(names, kinds, producer.owner, producer.method);
    }

    /** The zero-arg call that produced a collapsed result, or null (absent/ambiguous). */
    private static ZeroCall dispatchProducer(Val receiver,
                                             Map<String, List<ZeroCall>> zeroArgCalls) {
        if (receiver == null) return null;
        if (receiver.kind == Val.Kind.CALL && receiver.ctorArgs != null
                && receiver.ctorArgs.size() == 1) {
            // Whitelisted receiver-prepended accessor (getWorldObj and friends).
            return null;
        }
        if (receiver.kind != Val.Kind.UNKNOWN || receiver.reason == null) return null;
        String reason = receiver.reason;
        String prefix = "result of ";
        if (!reason.startsWith(prefix) || !reason.endsWith("()")) return null;
        String shortKey = reason.substring(prefix.length(), reason.length() - 2);
        List<ZeroCall> cands = zeroArgCalls.get(shortKey);
        if (cands == null || cands.size() != 1) return null;
        return cands.get(0);
    }

    /**
     * Tile-rooted hop chain for an object value: field reads and zero-arg accessor calls
     * down to the tile parameter. Locals are transparent only through a single unanimous
     * store; cycles and depth beyond the field-hop bound fail null (honest skip).
     */
    static List<Hop> hopChain(Val v, int teArg, Map<Integer, List<Val>> slotStores,
                              Map<String, List<ZeroCall>> zeroArgCalls, int depth,
                              Set<Val> seen) {
        if (v == null || depth > MAX_FIELD_HOPS + 1 || !seen.add(v)) return null;
        switch (v.kind) {
            case PARAM:
                return v.numberValue.intValue() == teArg ? new ArrayList<>() : null;
            case FIELD: {
                List<Hop> rest = hopChain(v.inner, teArg, slotStores, zeroArgCalls, depth + 1, seen);
                if (rest == null) return null;
                rest.add(new Hop(v.name, "field"));
                return rest;
            }
            case CALL: {
                // A zero-arg call is an accessor hop when its receiver resolves.
                if (v.ctorArgs != null && v.ctorArgs.size() == 1) {
                    List<Hop> rest = hopChain(v.ctorArgs.get(0), teArg, slotStores, zeroArgCalls,
                            depth + 1, seen);
                    if (rest == null) return null;
                    rest.add(new Hop(v.name, "accessor"));
                    return rest;
                }
                return null;
            }
            case UNKNOWN: {
                ZeroCall producer = dispatchProducer(v, zeroArgCalls);
                if (producer != null) {
                    List<Hop> rest = hopChain(producer.receiver, teArg, slotStores, zeroArgCalls,
                            depth + 1, seen);
                    if (rest == null) return null;
                    rest.add(new Hop(producer.method, "accessor"));
                    return rest;
                }
                Val sole = singleStored(v, slotStores);
                if (sole != null && sole != v) {
                    return hopChain(sole, teArg, slotStores, zeroArgCalls, depth + 1, seen);
                }
                return null;
            }
            default:
                return null;
        }
    }

    /** True when the value is the tile parameter, directly or through one single store. */
    private static boolean isTeParam(Val v, int teArg, Map<Integer, List<Val>> slotStores) {        if (v == null) return false;
        if (v.kind == Val.Kind.PARAM) return v.numberValue.intValue() == teArg;
        Val sole = singleStored(v, slotStores);
        return sole != null && sole != v && sole.kind == Val.Kind.PARAM
                && sole.numberValue.intValue() == teArg;
    }

    /**
     * The single stored value for a local-held value (identity search over the slot stores),
     * or null when it is stored nowhere exactly-once. Locals are transparent aliases only in
     * the exactly-once case; anything else is genuinely ambiguous.
     */
    private static Val singleStored(Val v, Map<Integer, List<Val>> slotStores) {
        if (v == null || slotStores == null) return null;
        for (List<Val> stores : slotStores.values()) {
            if (stores == null) continue;
            int hits = 0;
            for (Val s : stores) if (s == v) hits++;
            if (hits > 0) return stores.size() == 1 ? stores.get(0) : null;
        }
        return null;
    }

    /**
     * Pure numeric calls this resolver evaluates, matched by owner plus SRG <em>and</em>
     * deobfuscated name with the exact descriptor (grounded in
     * research/repos/MinecraftForge/fml/conf/methods.csv). Returns a tag or null.
     */
    private static String pureCall(String owner, String name, String desc) {
        if (MATH_INTERNAL.equals(owner)) {
            if ("toDegrees".equals(name) && "(D)D".equals(desc)) return "toDegrees";
            if ("toRadians".equals(name) && "(D)D".equals(desc)) return "toRadians";
            if ("sin".equals(name) && "(D)D".equals(desc)) return "sin";
            if ("cos".equals(name) && "(D)D".equals(desc)) return "cos";
            if ("atan2".equals(name) && "(DD)D".equals(desc)) return "atan2";
            if ("sqrt".equals(name) && "(D)D".equals(desc)) return "sqrt";
            if ("abs".equals(name) && ("(D)D".equals(desc) || "(F)F".equals(desc))) return "abs";
            if ("min".equals(name) && ("(DD)D".equals(desc) || "(FF)F".equals(desc)
                    || "(II)I".equals(desc) || "(JJ)J".equals(desc))) return "min";
            if ("max".equals(name) && ("(DD)D".equals(desc) || "(FF)F".equals(desc)
                    || "(II)I".equals(desc) || "(JJ)J".equals(desc))) return "max";
            return null;
        }
        if (MATH_HELPER.equals(owner)) {
            if (("clamp_float".equals(name) || "func_76131_a".equals(name)) && "(FFF)F".equals(desc)) {
                return "clamp_float";
            }
            if (("clamp_double".equals(name) || "func_151237_a".equals(name)) && "(DDD)D".equals(desc)) {
                return "clamp_double";
            }
            if (("clamp_int".equals(name) || "func_76125_a".equals(name)) && "(III)I".equals(desc)) {
                return "clamp_int";
            }
            // NOTE: MathHelper.sin/cos (func_76126_a/func_76134_b) are deliberately NOT
            // whitelisted: they read a 64k lookup table, so a native re-evaluation could only
            // approximate them. An honest skip beats a subtly wrong angle; the count shows it.
            return null;
        }
        return null;
    }

    // ------------------------------------------- packet-installed animation recipe

    /**
     * Resolves the animation-object recipe for one channel's tile-field hops, or null. The
     * shape is proven, never guessed (see {@link AnimRecipe}): the field's declaring
     * hierarchy is scanned for the assigning method; the value must come from a call
     * returning the animation type with a this-rooted field receiver and arguments that are
     * each this-rooted fields or method params provably stored to tile fields in the same
     * method (the packet-new-state idiom); the evaluator must read a static {@code ()J}
     * clock and one {@code long} off the animation object. First proven site wins.
     */
    static AnimRecipe animRecipe(JarIndex jar, String teInternal, List<String> animHops,
                                 String evalOwnerDotted, String evalMethod) {
        if (jar == null || teInternal == null || animHops == null || animHops.isEmpty()) return null;
        String leaf = animHops.get(animHops.size() - 1);
        List<ClassNode> chain = teChain(jar, teInternal);
        String fieldDesc = null;
        for (ClassNode cn : chain) {
            if (cn.fields == null) continue;
            for (org.objectweb.asm.tree.FieldNode f : cn.fields) {
                if (!leaf.equals(f.name) || f.desc == null) continue;
                if (Type.getType(f.desc).getSort() != Type.OBJECT) return null;
                String t = Type.getType(f.desc).getInternalName();
                if (t.startsWith("java/")) return null;
                fieldDesc = f.desc;
                break;
            }
            if (fieldDesc != null) break;
        }
        if (fieldDesc == null) return null;
        String animInternal = Type.getType(fieldDesc).getInternalName();
        for (ClassNode cn : chain) {
            if (cn.methods == null) continue;
            for (MethodNode m : cn.methods) {
                AnimRecipe r = recipeFromAssigner(m, cn.name, leaf, fieldDesc, animInternal, jar,
                        evalOwnerDotted, evalMethod, chain);
                if (r != null) return r;
            }
        }
        return null;
    }

    /** The tile class plus superclasses up to (not including) the vanilla TileEntity. */
    private static List<ClassNode> teChain(JarIndex jar, String teInternal) {
        List<ClassNode> out = new ArrayList<>();
        String cn = teInternal;
        for (int i = 0; i < 5 && cn != null && !TE_INTERNAL.equals(cn); i++) {
            ClassNode node = jar.classes.get(cn);
            if (node == null) break;
            out.add(node);
            cn = node.superName;
        }
        return out;
    }

    /** Tries one assigning method as the recipe source; null when the shape is unproven. */
    private static AnimRecipe recipeFromAssigner(MethodNode m, String ownerInternal, String fieldName,
                                                 String fieldDesc, String animInternal, JarIndex jar,
                                                 String evalOwnerDotted, String evalMethod,
                                                 List<ClassNode> teChain) {
        if (m.instructions == null) return null;
        List<AbstractInsnNode> insns = new ArrayList<>();
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            insns.add(in);
        }
        Map<Integer, String> paramField = paramFieldStores(insns, m, teChain);
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode in = insns.get(i);
            if (!(in instanceof org.objectweb.asm.tree.FieldInsnNode put)
                    || in.getOpcode() != Opcodes.PUTFIELD) continue;
            if (!fieldName.equals(put.name) || !fieldDesc.equals(put.desc)) continue;
            AnimRecipe r = recipeFromPut(insns, i, m, put, animInternal, jar, evalOwnerDotted,
                    evalMethod, paramField, teChain);
            if (r != null) return r;
        }
        return null;
    }

    /**
     * Method params provably stored to tile fields in the same method (slot -&gt; field name):
     * a PUTFIELD whose value is a bare parameter load (the packet-new-state idiom stores the
     * incoming state exactly this way). Anything fancier is not a proof.
     */
    private static Map<Integer, String> paramFieldStores(List<AbstractInsnNode> insns, MethodNode m,
                                                           List<ClassNode> teChain) {
        Map<Integer, String> out = new LinkedHashMap<>();
        Set<Integer> params = paramSlots(m);
        Set<String> owners = new LinkedHashSet<>();
        for (ClassNode cn : teChain) owners.add(cn.name);
        for (int i = 0; i < insns.size(); i++) {
            AbstractInsnNode in = insns.get(i);
            if (!(in instanceof org.objectweb.asm.tree.FieldInsnNode put)
                    || in.getOpcode() != Opcodes.PUTFIELD) continue;
            if (!owners.contains(put.owner)) continue;
            int v = prevPorcelain(insns, i - 1);
            if (v < 0 || !(insns.get(v) instanceof org.objectweb.asm.tree.VarInsnNode var)) continue;
            int op = var.getOpcode();
            if (op < 21 || op > 45) continue;
            if (params.contains(var.var)) out.putIfAbsent(var.var, ((org.objectweb.asm.tree.FieldInsnNode) in).name);
        }
        return out;
    }

    /** Local slots holding the method parameters (slot 0 is {@code this} when non-static). */
    private static Set<Integer> paramSlots(MethodNode m) {
        Set<Integer> out = new LinkedHashSet<>();
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        int slot = isStatic ? 0 : 1;
        for (Type t : Type.getArgumentTypes(m.desc)) {
            out.add(slot);
            slot += t.getSize();
        }
        return out;
    }

    /**
     * True for any local-load opcode. Compared numerically, not via {@code Opcodes} constants:
     * the bundled ASM keeps only the opcodes existing code references (no {@code ILOAD_0}..
     * {@code ALOAD_3}); by the JVM spec these are exactly 21 ({@code ILOAD})..45
     * ({@code ALOAD_3}). {@link org.objectweb.asm.tree.VarInsnNode#var} still gives the slot
     * for every form.
     */
    private static boolean isAnyLoad(AbstractInsnNode in) {
        return in instanceof org.objectweb.asm.tree.VarInsnNode
                && in.getOpcode() >= 21 && in.getOpcode() <= 45;
    }

    private static int prevPorcelain(List<AbstractInsnNode> insns, int i) {
        while (i >= 0 && (insns.get(i) instanceof org.objectweb.asm.tree.LabelNode
                || insns.get(i) instanceof org.objectweb.asm.tree.LineNumberNode
                || insns.get(i) instanceof org.objectweb.asm.tree.FrameNode)) {
            i--;
        }
        return i;
    }

    /** One backward-collected push: a parameter slot or a this-rooted field chain. */
    private static final class Push {
        final Integer paramSlot;
        final List<String> fieldHops;
        int next;
        Push(Integer paramSlot, List<String> fieldHops) {
            this.paramSlot = paramSlot;
            this.fieldHops = fieldHops;
        }
    }

    /**
     * Proven-shape recipe from one PUTFIELD site: the value must be a call returning the
     * animation type, with a this-rooted field receiver (or a static call) and arguments
     * that are each params-with-field-stores or this-rooted fields. Cursor-based backward
     * walk over straight-line javac output; anything else fails null.
     */
    private static AnimRecipe recipeFromPut(List<AbstractInsnNode> insns, int putIdx, MethodNode m,
                                            org.objectweb.asm.tree.FieldInsnNode put,
                                            String animInternal, JarIndex jar,
                                            String evalOwnerDotted, String evalMethod,
                                            Map<Integer, String> paramField,
                                            List<ClassNode> teChain) {
        int c = prevPorcelain(insns, putIdx - 1);
        if (c < 0 || !(insns.get(c) instanceof MethodInsnNode call)) return null;
        if (!Type.getReturnType(call.desc).equals(Type.getObjectType(animInternal))) return null;
        Type[] formals = Type.getArgumentTypes(call.desc);
        List<Push> args = new ArrayList<>();
        int cur = c - 1;
        boolean instance = call.getOpcode() != Opcodes.INVOKESTATIC;
        for (int a = formals.length - 1; a >= 0; a--) {
            Push p = collectPush(insns, m, cur);
            if (p == null) return null;
            args.add(0, p);
            cur = p.next;
        }
        List<String> receiverHops = null;
        List<String> receiverKinds = null;
        if (instance) {
            Push r = collectThisFields(insns, m, cur);
            if (r == null || r.fieldHops == null) return null;
            receiverHops = r.fieldHops;
            receiverKinds = kindsFor(r.fieldHops);
        }
        // Resolve args: params only through their proven field stores.
        List<List<String>> argHops = new ArrayList<>();
        List<List<String>> argKinds = new ArrayList<>();
        for (Push p : args) {
            if (p.fieldHops != null) {
                argHops.add(p.fieldHops);
                argKinds.add(kindsFor(p.fieldHops));
            } else if (p.paramSlot != null) {
                String mapped = paramField.get(p.paramSlot);
                if (mapped == null) return null;
                List<String> h = new ArrayList<>();
                h.add(mapped);
                argHops.add(h);
                argKinds.add(kindsFor(h));
            } else {
                return null;
            }
        }
        String[] clock = evaluatorClock(jar, evalOwnerDotted, evalMethod, animInternal);
        if (clock == null) return null;
        return new AnimRecipe(JarIndex.dotted(call.owner), call.name, receiverHops, receiverKinds,
                argHops, argKinds, clock[0], clock[1], clock[2]);
    }

    private static List<String> kindsFor(List<String> hops) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < hops.size(); i++) out.add("field");
        return out;
    }

    /** Backward-collects one push: a parameter load or a this-rooted field chain. */
    private static Push collectPush(List<AbstractInsnNode> insns, MethodNode m, int cur) {
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        Set<Integer> params = paramSlots(m);
        cur = prevPorcelain(insns, cur);
        if (cur < 0) return null;
        AbstractInsnNode in = insns.get(cur);
        if (isAnyLoad(in)) {
            int slot = ((org.objectweb.asm.tree.VarInsnNode) in).var;
            if (isStatic ? params.contains(slot) : (slot != 0 && params.contains(slot))) {
                Push p = new Push(slot, null);
                p.next = cur - 1;
                return p;
            }
            return null;
        }
        Push f = collectThisFields(insns, m, cur);
        if (f != null) return f;
        return null;
    }

    /**
     * Backward-collects a this-rooted field chain (ALOAD 0 then GETFIELDs), or null.
     * locals, calls and constants on the path fail null - the proven shape only.
     */
    private static Push collectThisFields(List<AbstractInsnNode> insns, MethodNode m, int cur) {
        boolean isStatic = (m.access & Opcodes.ACC_STATIC) != 0;
        if (isStatic) return null;
        List<String> hops = new ArrayList<>();
        while (true) {
            cur = prevPorcelain(insns, cur);
            if (cur < 0) return null;
            AbstractInsnNode in = insns.get(cur);
            if (in instanceof org.objectweb.asm.tree.FieldInsnNode f
                    && in.getOpcode() == Opcodes.GETFIELD) {
                hops.add(0, f.name);
                cur--;
                continue;
            }
            if (isAnyLoad(in) && ((org.objectweb.asm.tree.VarInsnNode) in).var == 0) {
                Push p = new Push(null, hops);
                p.next = cur - 1;
                return p;
            }
            return null;
        }
    }

    /**
     * The evaluator's clock and animation-clock field: the static no-arg {@code ()J} call it
     * reads plus the {@code long} it reads off the animation object. Both required.
     */
    private static String[] evaluatorClock(JarIndex jar, String evalOwnerDotted, String evalMethod,
                                           String animInternal) {
        if (jar == null || evalOwnerDotted == null || evalMethod == null) return null;
        ClassNode cn = jar.classes.get(evalOwnerDotted.replace('.', '/'));
        if (cn == null || cn.methods == null) return null;
        for (MethodNode m : cn.methods) {
            if (!evalMethod.equals(m.name) || m.desc == null || !m.desc.endsWith(")[D")) continue;
            if (m.instructions == null) continue;
            String clockOwner = null;
            String clockMethod = null;
            String clockField = null;
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof MethodInsnNode call
                        && in.getOpcode() == Opcodes.INVOKESTATIC
                        && "()J".equals(call.desc) && clockOwner == null) {
                    clockOwner = call.owner;
                    clockMethod = call.name;
                } else if (in instanceof org.objectweb.asm.tree.FieldInsnNode f
                        && in.getOpcode() == Opcodes.GETFIELD
                        && animInternal.equals(f.owner) && "J".equals(f.desc)
                        && clockField == null) {
                    clockField = f.name;
                }
            }
            if (clockOwner != null && clockField != null) {
                return new String[]{clockOwner, clockMethod, clockField};
            }
            // Same name + array desc but no clock pair: keep looking at overloads.
        }
        return null;
    }

    // ------------------------------------------- TESR caller-prefix extraction

    /** One switch instruction with the simulated input value (pre-consumption). */
    private static final class SwitchSite {
        final Val input;
        final AbstractInsnNode node;
        SwitchSite(Val input, AbstractInsnNode node) {
            this.input = input;
            this.node = node;
        }
    }

    /**
     * The TESR-side base matrix for a dispatched helper: the constant remainder of the
     * first anchored translate (positions zeroed - the BER positions by block) plus the
     * facing map from a meta-arithmetic switch with one constant rotate per case. Only
     * built when a dispatch was found (scope); null when either part is unproven.
     */
    static TesrPrefix tesrPrefix(MethodNode mn, List<Op> chronological, List<SwitchSite> switches,
                                 Map<String, Integer> skipped) {
        double[] translate = null;
        for (Op op : chronological) {
            if (!op.anchored) continue;
            if (!("glTranslated".equals(op.gl) || "glTranslatef".equals(op.gl))) continue;
            if (op.args.size() != 3) continue;
            double[] t = new double[3];
            boolean ok = true;
            for (int i = 0; i < 3; i++) {
                Double v = op.args.get(i).expr != null ? constRemainder(op.args.get(i).expr) : null;
                if (v == null) {
                    ok = false;
                    break;
                }
                t[i] = v.doubleValue();
            }
            if (ok) {
                translate = t;
                break;
            }
        }
        Map<Integer, double[]> facing = new LinkedHashMap<>();
        Integer metaBase = null;
        boolean switchSeen = false;
        for (SwitchSite s : switches) {
            if (switchSeen) {
                skipped.merge("prefix-switch-extra", 1, Integer::sum);
                continue;
            }
            Integer base = switchMetaBase(s.input);
            if (base == null) continue;
            Map<Integer, double[]> cases = switchRotates(mn, s.node, skipped);
            if (cases.isEmpty()) continue;
            switchSeen = true;
            metaBase = base;
            facing = cases;
        }
        if (translate == null && facing.isEmpty()) return null;
        return new TesrPrefix(translate, metaBase, facing);
    }

    /**
     * The subtracted meta base of a facing switch: the input must be a single
     * {@code (nonConst - constInt)} tree (the {@code meta - 10} shape). Anything else is
     * not a proven meta selector.
     */
    private static Integer switchMetaBase(Val input) {
        if (input == null || input.kind != Val.Kind.EXPR) return null;
        if (!"isub".equals(input.stringValue) || input.ctorArgs == null
                || input.ctorArgs.size() != 2) return null;
        Val a = input.ctorArgs.get(0);
        Val b = input.ctorArgs.get(1);
        if (a == null || b == null) return null;
        if (b.kind != Val.Kind.NUMBER || b.numberValue == null) return null;
        if (a.kind != Val.Kind.UNKNOWN && a.kind != Val.Kind.FIELD && a.kind != Val.Kind.CALL
                && a.kind != Val.Kind.PARAM) return null;
        double d = b.numberValue.doubleValue();
        if (d != Math.floor(d) || d < -32768 || d > 32767) return null;
        return Integer.valueOf((int) d);
    }

    /**
     * Case key to constant rotate args from a tableswitch/lookupswitch: each case body must
     * hold exactly one constant rotate (angle + axis) in straight-line code; anything else
     * drops that case (counted). Shared tails, calls and field/local reads all disqualify.
     */
    private static Map<Integer, double[]> switchRotates(MethodNode mn, AbstractInsnNode node,
                                                        Map<String, Integer> skipped) {
        Map<Integer, double[]> out = new LinkedHashMap<>();
        List<Integer> keys = new ArrayList<>();
        List<org.objectweb.asm.tree.LabelNode> labels = new ArrayList<>();
        if (node instanceof org.objectweb.asm.tree.TableSwitchInsnNode t) {
            for (int k = t.min; k <= t.max; k++) {
                keys.add(k);
                labels.add(t.labels.get(k - t.min));
            }
        } else if (node instanceof org.objectweb.asm.tree.LookupSwitchInsnNode l) {
            keys.addAll(l.keys);
            labels.addAll(l.labels);
        } else {
            return out;
        }
        Set<org.objectweb.asm.tree.LabelNode> caseLabels = new LinkedHashSet<>(labels);
        AbstractInsnNode[] all = mn.instructions.toArray();
        Map<AbstractInsnNode, Integer> index = new java.util.IdentityHashMap<>();
        for (int i = 0; i < all.length; i++) index.put(all[i], i);
        for (int c = 0; c < keys.size(); c++) {
            Integer start = index.get(labels.get(c));
            if (start == null) continue;
            double[] rotate = caseRotate(all, start + 1, caseLabels);
            if (rotate != null) {
                out.put(keys.get(c), rotate);
            } else {
                skipped.merge("prefix-facing-case-skipped", 1, Integer::sum);
            }
        }
        return out;
    }

    /** Exactly one constant glRotatef/glRotated in straight-line code from a case label. */
    private static double[] caseRotate(AbstractInsnNode[] all, int start,
                                       Set<org.objectweb.asm.tree.LabelNode> caseLabels) {
        double[] found = null;
        for (int i = start; i < all.length && i < start + 16; i++) {
            AbstractInsnNode in = all[i];
            if (in instanceof org.objectweb.asm.tree.LabelNode ln) {
                if (caseLabels.contains(ln)) break;
                continue;
            }
            if (in instanceof org.objectweb.asm.tree.LineNumberNode
                    || in instanceof org.objectweb.asm.tree.FrameNode) continue;
            if (isConstPush(in)) continue;
            if (in instanceof MethodInsnNode call
                    && "org/lwjgl/opengl/GL11".equals(call.owner)
                    && ("glRotatef".equals(call.name) || "glRotated".equals(call.name))) {
                double[] args = constArgsBack(all, i - 1, 4);
                if (args == null || found != null) return null;
                found = args;
                continue;
            }
            break;
        }
        return found;
    }

    /** The constant pushes feeding one call, collected backward; null unless exact. */
    private static double[] constArgsBack(AbstractInsnNode[] all, int end, int count) {
        double[] out = new double[count];
        int i = end;
        for (int a = count - 1; a >= 0; a--) {
            while (i >= 0 && (all[i] instanceof org.objectweb.asm.tree.LabelNode
                    || all[i] instanceof org.objectweb.asm.tree.LineNumberNode
                    || all[i] instanceof org.objectweb.asm.tree.FrameNode)) {
                i--;
            }
            if (i < 0) return null;
            Double v = constPushValue(all[i]);
            if (v == null) return null;
            out[a] = v.doubleValue();
            i--;
        }
        return out;
    }

    /** True for constant-push instructions (JVM spec opcodes 2-20, minus ACONST_NULL). */
    private static boolean isConstPush(AbstractInsnNode in) {
        int op = in.getOpcode();
        if (op >= 2 && op <= 20) return op != 1;
        return false;
    }

    /**
     * The constant of one push instruction, or null. Opcodes numeric: the bundled ASM
     * keeps only referenced constants (see the local-load note); by the JVM spec 2-8 are
     * ICONST_M1..ICONST_5 (value = opcode - 3), 9-10 LCONST_0/1, 11-13 FCONST_0/1/2,
     * 14-15 DCONST_0/1, 16-17 BIPUSH/SIPUSH (IntInsnNode operand), 18-20 LDC variants
     * (LdcInsnNode numeric value only).
     */
    private static Double constPushValue(AbstractInsnNode in) {
        int op = in.getOpcode();
        if (op >= 2 && op <= 8) return Double.valueOf(op - 3);
        if (op >= 9 && op <= 10) return Double.valueOf(op - 9);
        if (op >= 11 && op <= 13) return Double.valueOf(op - 11);
        if (op >= 14 && op <= 15) return Double.valueOf(op - 14);
        if (op == 16 || op == 17) {
            if (in instanceof org.objectweb.asm.tree.IntInsnNode n) return Double.valueOf(n.operand);
            return null;
        }
        if (op >= 18 && op <= 20) {
            if (in instanceof org.objectweb.asm.tree.LdcInsnNode l && l.cst instanceof Number) {
                return Double.valueOf(((Number) l.cst).doubleValue());
            }
            return null;
        }
        return null;
    }

    /**
     * World positions zeroed, partial time rejected: a constant remainder is a const leaf,
     * a positional leaf (the BER positions by block itself), or a pure-numeric fold of
     * those. Calls, fields, channels and the clock all fail null (time-varying or opaque).
     */
    static Double constRemainder(Expr e) {
        if (e == null) return null;
        if (e instanceof ConstExpr) return Double.valueOf(((ConstExpr) e).value);
        if (e instanceof ParamExpr) {
            return ((ParamExpr) e).role.startsWith("pos") ? Double.valueOf(0.0) : null;
        }
        if (e instanceof OpExpr) {
            OpExpr o = (OpExpr) e;
            double[] a = new double[o.args.size()];
            for (int i = 0; i < a.length; i++) {
                Double v = constRemainder(o.args.get(i));
                if (v == null) return null;
                a[i] = v.doubleValue();
            }
            return constFold(o.op, a);
        }
        return null;
    }

    /** Pure-numeric fold for the remainder (float/double add/sub/mul/div/neg only). */
    private static Double constFold(String op, double[] a) {
        try {
            switch (op) {
                case "fadd": case "dadd":
                    if (a.length == 2) return Double.valueOf(a[0] + a[1]);
                    return null;
                case "fsub": case "dsub":
                    if (a.length == 2) return Double.valueOf(a[0] - a[1]);
                    return null;
                case "fmul": case "dmul":
                    if (a.length == 2) return Double.valueOf(a[0] * a[1]);
                    return null;
                case "fdiv": case "ddiv":
                    if (a.length == 2 && a[1] != 0.0) return Double.valueOf(a[0] / a[1]);
                    return null;
                case "fneg": case "dneg":
                    if (a.length == 1) return Double.valueOf(-a[0]);
                    return null;
                default:
                    return null;
            }
        } catch (ArithmeticException x) {
            return null;
        }
    }
}
