package dev.umb.rendermap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Walks a renderer class (plus its HBM superclasses, its nested classes and, for a
 * nested renderer, its enclosing class) and records which model / texture holder
 * fields it reads, which OBJ group names it renders, and how it transforms them.
 */
public class RendererAnalyzer {

    /** One static helper-class hop is enough to capture render utility methods without a whole-program walk. */
    private static final int STATIC_HELPER_HOP_DEPTH = 1;

    private static final String IMC = "net/minecraftforge/client/model/IModelCustom";
    private static final String IRT = "net/minecraftforge/client/IItemRenderer$ItemRenderType";
    /** verified {@code javap -classpath forge-1.7.10-...-universal.jar net.minecraft.client.model.ModelBase}
     *  against the 1.7.10 SRG client jar: a Techne/ModelBase model is CODE (Java methods that issue GL
     *  calls), not a loadable mesh asset — detected and reported, never baked. */
    private static final String MODEL_BASE = "net/minecraft/client/model/ModelBase";
    /** verified {@code javap -classpath 1.7.10-forge-srg-runtime-fields.jar
     *  net.minecraft.client.renderer.entity.Render}: {@code protected abstract ResourceLocation
     *  func_110775_a(Entity)} — ABSTRACT on {@code Render} itself, so every concrete entity
     *  renderer (leaf class, or a shared abstract base class the mod itself declares) must
     *  override it somewhere within the jar's own class chain for the entity to have a texture
     *  at all. Cross-checked against {@code fml/conf/methods.csv:3069}: "func_110775_a,
     *  getEntityTexture,0,Returns the location of an entity's texture." */
    private static final String GET_ENTITY_TEXTURE_NAME = "func_110775_a";
    private static final String GET_ENTITY_TEXTURE_DESC =
            "(Lnet/minecraft/entity/Entity;)Lnet/minecraft/util/ResourceLocation;";
    /** verified {@code javap -classpath ... net.minecraft.client.model.ModelRenderer}: the vanilla
     *  Techne-model-part API this project's Java-model geometry extraction reads. */
    private static final String MODEL_RENDERER = "net/minecraft/client/model/ModelRenderer";
    private static final String ADD_BOX_NAME = "func_78789_a";           // addBox(x,y,z,w,h,d)
    private static final String ADD_BOX_DESC = "(FFFIII)Lnet/minecraft/client/model/ModelRenderer;";
    private static final String ADD_BOX_SCALE_NAME = "func_78790_a";     // addBox(x,y,z,w,h,d,scale)
    private static final String ADD_BOX_SCALE_DESC = "(FFFIIIF)V";
    private static final String SET_ROTATION_POINT_NAME = "func_78793_a"; // setRotationPoint(x,y,z)
    private static final String SET_ROTATION_POINT_DESC = "(FFF)V";

    private final JarIndex jar;
    private final HolderResolver holders;
    private final Map<String, RendererInfo> cache = new HashMap<>();

    public RendererAnalyzer(JarIndex jar, HolderResolver holders) {
        this.jar = jar; this.holders = holders;
    }

    public RendererInfo analyze(String internalName) {
        RendererInfo cached = cache.get(internalName);
        if (cached != null) return cached;
        RendererInfo info = new RendererInfo();
        info.rendererClass = JarIndex.dotted(internalName);
        cache.put(internalName, info); // guards against cycles

        for (ClassNode cn : scope(internalName)) {
            info.scannedClasses.add(JarIndex.dotted(cn.name));
            for (FieldNode fn : cn.fields) noteIfJavaModelType(fn.desc, info);
            for (MethodNode mn : cn.methods) scanMethod(cn, mn, info);
        }
        // Entity-renderer-specific: resolve getEntityTexture(Entity), if the jar overrides it
        // anywhere in this renderer's own class chain. Harmless (finds nothing) for a block/
        // item/TESR renderer class, since no such class ever declares this exact vanilla `Render`
        // method — no new gating needed to keep it entity-only.
        resolveEntityTexture(internalName, info);
        // Java-model (Techne) geometry: every ModelBase subclass this renderer references (found
        // above by field-type or `new` detection) gets its own, separately-bounded pass.
        for (String javaModelDotted : new ArrayList<>(info.javaModelClasses)) {
            resolveJavaModelGeometry(JarIndex.internal(javaModelDotted), info);
        }
        return info;
    }

    /** Resolve a static holder field, following {@link JarIndex#declaringClassOfField} first —
     *  a subclass's OWN bytecode can name a field it merely INHERITS (javac always emits the
     *  fieldref against the compile-time static type used at the call site, not necessarily the
     *  declaring class) — confirmed live on MC Helicopters' {@code MCH_RenderHeli.TEX_DEFAULT}
     *  (declared on a shared superclass within the same jar, referenced from a subclass with zero
     *  fields of its own — see ENTITY-RENDER-EXTRACTION.md). {@link BindingScanner} already uses
     *  this exact fix for block render-id fields; this reuses the SAME {@link JarIndex} method,
     *  not a new one. Strictly monotonic: a field that already resolved by its literal owner is
     *  unaffected (declaringClassOfField is a no-op when the field really is declared there). */
    private ResRef holderRef(String owner, String name) {
        return holders.byField.get(jar.declaringClassOfField(owner, name) + "." + name);
    }

    /**
     * A {@code ModelBase} subclass (vanilla's built-in modelling API, and what every
     * Techne/TCN-exported model compiles to) is Java code that issues GL calls directly —
     * there is no mesh file to resolve. Flag it by class name so the caller can report
     * {@code modelKind:"java"} instead of silently finding zero models/textures for it.
     */
    private void noteIfJavaModelType(String desc, RendererInfo info) {
        if (desc == null || desc.isEmpty() || desc.charAt(0) != 'L' || !desc.endsWith(";")) return;
        String t = desc.substring(1, desc.length() - 1);
        if (!MODEL_BASE.equals(t) && jar.isSubclassOf(t, MODEL_BASE)) info.javaModelClasses.add(JarIndex.dotted(t));
    }

    /**
     * renderer + its HBM supers + its own nested classes, and — when the renderer is
     * itself a nested class (a TESR's inline {@code getRenderer()} instance, or an
     * {@code ItemRenderLibrary$N}) — the enclosing class and its HBM supers, because
     * the inner renderer delegates back to the outer one's bind/draw helpers.
     *
     * <p>The enclosing class's *sibling* nested classes are deliberately excluded:
     * {@code ItemRenderLibrary} has 79 of them and pulling them all in would attribute
     * every model in the library to every item.
     */
    private List<ClassNode> scope(String internalName) {
        Set<String> names = new LinkedHashSet<>();
        collectWithSupers(internalName, names);
        for (String s : new ArrayList<>(names)) {
            String prefix = s + "$";
            for (String other : jar.classes.keySet())
                if (other.startsWith(prefix)) names.add(other);
        }
        int dollar = internalName.indexOf('$');
        if (dollar > 0) collectWithSupers(internalName.substring(0, dollar), names);
        // Renderers commonly delegate a complete draw section to a static utility (for example a
        // multiblock launcher pronter).  Follow exactly one hop of jar-local static calls so the
        // utility's exact model/texture fields participate in the same analysis.  The named bound
        // is deliberate: this is data extraction, not an unbounded call-graph walk.
        Set<String> helperSeeds = new LinkedHashSet<>(names);
        for (int depth = 0; depth < STATIC_HELPER_HOP_DEPTH; depth++) {
            Set<String> helpers = new LinkedHashSet<>();
            for (String owner : helperSeeds) {
                ClassNode cn = jar.classes.get(owner);
                if (cn == null) continue;
                for (MethodNode mn : cn.methods) {
                    for (AbstractInsnNode insn : mn.instructions) {
                        if (!(insn instanceof MethodInsnNode mi) || mi.getOpcode() != Opcodes.INVOKESTATIC)
                            continue;
                        if (jar.classes.containsKey(mi.owner)) helpers.add(mi.owner);
                    }
                }
            }
            for (String helper : helpers) collectWithSupers(helper, names);
            helperSeeds = helpers;
        }
        List<ClassNode> out = new ArrayList<>();
        for (String n : names) {
            // Deliberately jar.classes.get, NOT jar.cls (which also falls back to the engine/
            // vanilla classpath loaded for isSubclassOf/implementorsOf's OWN, different purpose —
            // see JarIndex#engine's javadoc). `names` should never contain a vanilla class name in
            // the first place (collectWithSupers below stops there on purpose), but this is kept
            // as a second, explicit guard so `scope()` can never scan real vanilla bytecode.
            ClassNode cn = jar.classes.get(n);
            if (cn != null) out.add(cn);
        }
        return out;
    }

    /**
     * Walk {@code internalName}'s superclass chain, stopping the moment we leave the mod jar
     * (a vanilla/Forge/library superclass has no {@link JarIndex#cls} entry, so {@code cn == null}
     * ends the walk on its own). This is mod-agnostic by construction — it used to be gated on a
     * hardcoded {@code com/hbm/} / {@code api/hbm/} package prefix, which meant a renderer class
     * belonging to any other mod never contributed anything here at all ({@link #scope} would
     * return empty and every model/texture/group lookup would silently see nothing). The jar
     * boundary is the only fence that is actually mod-agnostic.
     */
    private void collectWithSupers(String internalName, Set<String> out) {
        String cur = internalName;
        while (cur != null) {
            if (!out.add(cur)) break;
            // jar.classes.get, NOT jar.cls: this method's own javadoc (above) documents "stopping
            // the moment we leave the mod jar" as the whole point of the boundary check — but
            // JarIndex#cls ALSO falls back to the vanilla/Forge `engine` classpath (loaded by
            // RenderMap.main for a DIFFERENT reason: isSubclassOf/implementorsOf need it to
            // correctly classify a Block/Item subclass whose intermediate superclass is vanilla).
            // Using jar.cls here silently broke this method's own documented contract the moment
            // that engine classpath was introduced: the walk no longer stopped at the jar boundary
            // for a renderer whose superclass chain reaches all the way up to a real vanilla class
            // (confirmed live: an entity renderer's chain reaching vanilla
            // net.minecraft.client.renderer.entity.Render pulled Render's OWN real methods into
            // scanMethod, which fabricated bogus "resolved" boundTextureFields entries —
            // net.minecraft.client.renderer.texture.TextureMap.b / Render.a — that do not
            // correspond to ANY actual GETSTATIC in the mod's own bytecode at all, confirmed absent
            // by a full javap dump of every class in the jar). See ENTITY-RENDER-EXTRACTION.md.
            ClassNode cn = jar.classes.get(cur);
            if (cn == null) break;
            cur = cn.superName;
        }
    }

    private void scanMethod(ClassNode cn, MethodNode mn, RendererInfo info) {
        MethodSim.run(cn, mn, (insn, stack, locals) -> {
            int op = insn.getOpcode();
            if (op == Opcodes.NEW) {
                String t = ((TypeInsnNode) insn).desc;
                if (!MODEL_BASE.equals(t) && jar.isSubclassOf(t, MODEL_BASE)) info.javaModelClasses.add(JarIndex.dotted(t));
                return;
            }
            if (op == Opcodes.GETSTATIC) {
                FieldInsnNode f = (FieldInsnNode) insn;
                if (IRT.equals(f.owner)) { info.renderTypes.add(f.name); return; }
                ResRef ref = holderRef(f.owner, f.name);
                if (ref != null) {
                    if (ref.resolved()) {
                        if (ref.kind == ResRef.Kind.MODEL) info.modelFields.add(ref.field);
                        else info.textureFields.add(ref.field);
                    } else {
                        info.unresolvedFieldRefs.add(ref.field + " (" + ref.unresolvedReason + ")");
                    }
                }
                return;
            }
            if (op != Opcodes.INVOKESTATIC && op != Opcodes.INVOKEVIRTUAL
                    && op != Opcodes.INVOKEINTERFACE && op != Opcodes.INVOKESPECIAL) return;
            MethodInsnNode m = (MethodInsnNode) insn;

            if ("org/lwjgl/opengl/GL11".equals(m.owner)) {
                if (m.name.startsWith("glRotate")) info.glRotate++;
                else if (m.name.startsWith("glScale")) info.glScale++;
                else if (m.name.startsWith("glTranslate")) info.glTranslate++;
                return;
            }

            int argc = Type.getArgumentTypes(m.desc).length;
            boolean hasReceiver = op != Opcodes.INVOKESTATIC;
            Val recv = hasReceiver ? at(stack, argc) : null;

            switch (m.name) {
                case "renderAll":
                    info.usesRenderAll = true;
                    noteModelReceiver(info, recv, "renderAll");
                    return;
                case "renderPart": {
                    noteModelReceiver(info, recv, "renderPart");
                    Val g = at(stack, argc - 1);
                    if (g != null && g.kind == Val.Kind.STRING) info.groups.add(g.stringValue);
                    else markDynamic(info, "renderPart with non-constant group (" + g + ")");
                    return;
                }
                case "renderOnly":
                case "renderAllExcept": {
                    noteModelReceiver(info, recv, m.name);
                    Val arr = at(stack, argc - 1);
                    if (arr != null && arr.kind == Val.Kind.ARRAY && arr.elements != null) {
                        for (Val e : arr.elements) {
                            if (e.kind == Val.Kind.STRING) info.groups.add(e.stringValue);
                            else markDynamic(info, m.name + " with non-constant group element");
                        }
                    } else if (arr != null && arr.kind == Val.Kind.STRING) {
                        info.groups.add(arr.stringValue);
                    } else {
                        markDynamic(info, m.name + " with non-constant group array (" + arr + ")");
                    }
                    if ("renderAllExcept".equals(m.name)) info.usesRenderAll = true;
                    return;
                }
                default:
            }

            // texture binds: TextureManager.bindTexture / TESR.bindTexture / Render.bindTexture
            if ("func_110577_a".equals(m.name) || "bindTexture".equals(m.name)
                    || "func_147499_a".equals(m.name) || "func_110776_a".equals(m.name)) {
                Val t = at(stack, argc - 1);
                if (t != null && t.kind == Val.Kind.STATIC_FIELD) {
                    ResRef ref = holderRef(t.owner, t.name);
                    if (ref != null && ref.resolved()) info.boundTextureFields.add(ref.field);
                    else info.boundTextureFields.add(JarIndex.dotted(t.owner) + "." + t.name);
                } else {
                    markDynamic(info, "bindTexture with non-constant location (" + t + ")");
                }
            }
        });
    }

    private void noteModelReceiver(RendererInfo info, Val recv, String call) {
        if (recv == null) return;
        if (recv.kind == Val.Kind.STATIC_FIELD) {
            ResRef ref = holderRef(recv.owner, recv.name);
            if (ref != null && ref.resolved()) info.modelFields.add(ref.field);
            else if (ref != null) info.unresolvedFieldRefs.add(ref.field + " (" + ref.unresolvedReason + ")");
            return;
        }
        markDynamic(info, call + " on a non-static-field model (" + recv + ")");
    }

    private void markDynamic(RendererInfo info, String reason) {
        info.dynamic = true;
        if (info.dynamicReasons.size() < 12) info.dynamicReasons.add(reason);
    }

    private static Val at(List<Val> stack, int fromTop) {
        int i = stack.size() - 1 - fromTop;
        return i < 0 || i >= stack.size() ? null : stack.get(i);
    }

    /** Field descriptor test used to spot IModelCustom fields on `this`. */
    static boolean isModelDesc(String desc) { return ("L" + IMC + ";").equals(desc); }

    // ================================================================================
    // Entity-renderer texture: Render.func_110775_a(Entity) = getEntityTexture — the vanilla API
    // EVERY concrete entity renderer must implement somewhere in its own class chain (see the
    // GET_ENTITY_TEXTURE_* javadoc above). Deliberately a SEPARATE, dedicated scan rather than
    // folded into scanMethod/onGenericInsn: it needs `jar` passed into MethodSim (so a `return
    // this.field;` shaped body benefits from the SAME single-assignment instance-field resolver
    // already used elsewhere — see MethodSim#tryInstanceFieldSingleAssignment), and isolating it
    // here means enabling that keeps ZERO blast radius on scanMethod's existing block/item/TESR
    // resolution (which intentionally does not pass jar into its own MethodSim.run call).
    // ================================================================================

    private void resolveEntityTexture(String rendererInternalName, RendererInfo info) {
        // Java override semantics: the MOST-DERIVED declaration of func_110775_a wins at runtime
        // (a subclass overriding it shadows its superclass's own version completely — the
        // superclass's body never even runs). scope(internalName)'s own collectWithSupers walks
        // leaf-to-root, so the FIRST class encountered here that declares the method is the one
        // that actually executes; stop there. Without this, a renderer whose OWN override could
        // not be statically resolved (e.g. a runtime livery lookup) but whose ANCESTOR happens to
        // have a resolvable one would wrongly report the ancestor's texture as if it were this
        // entity's own — confirmed live on MC Helicopters, where every leaf renderer AND its
        // shared W_Render ancestor both declare func_110775_a (see ENTITY-RENDER-EXTRACTION.md).
        for (ClassNode cn : scope(rendererInternalName)) {
            MethodNode mn = null;
            for (MethodNode candidate : cn.methods) {
                if (GET_ENTITY_TEXTURE_NAME.equals(candidate.name) && GET_ENTITY_TEXTURE_DESC.equals(candidate.desc)) {
                    mn = candidate; break;
                }
            }
            if (mn == null) continue;
            info.getEntityTextureFound = true;
            MethodSim.run(cn, mn, jar, true, (insn, stack, locals) -> {
                if (insn.getOpcode() != Opcodes.ARETURN) return;
                Val v = at(stack, 0); // value about to be returned, before MethodSim pops it
                info.entityTextureRefs.add(resolveEntityTextureValue(v));
            });
            return; // most-derived override found and processed — do not also read ancestors'
        }
    }

    private RendererInfo.EntityTextureRef resolveEntityTextureValue(Val v) {
        RendererInfo.EntityTextureRef r = new RendererInfo.EntityTextureRef();
        if (v == null) { r.unresolvedReason = "empty stack at areturn"; return r; }
        if (v.kind == Val.Kind.STATIC_FIELD) {
            ResRef ref = holderRef(v.owner, v.name);
            if (ref != null && ref.resolved()) {
                r.kind = "static-field"; r.field = ref.field; r.path = ref.path; r.assetPath = ref.assetPath;
            } else {
                r.unresolvedReason = "static field " + JarIndex.dotted(v.owner) + "." + v.name
                        + (ref == null ? " is not a recognised ResourceLocation holder" : " (" + ref.unresolvedReason + ")");
            }
            return r;
        }
        if (v.kind == Val.Kind.NEW_OBJ) {
            String rl = holders.asResourceLocation(v);
            if (rl != null) {
                r.kind = "inline"; r.path = rl; r.assetPath = ResRef.toAssetPath(rl);
            } else {
                r.unresolvedReason = "getEntityTexture returns `new " + JarIndex.dotted(v.typeName)
                        + "(...)` with a non-literal argument";
            }
            return r;
        }
        // MethodSim#tryInstanceFieldSingleAssignment (enabled because `jar` was passed above)
        // already reduced a `return this.field;` shaped body all the way down to whatever THAT
        // field's own single constructor assignment was — if that in turn was a static field or
        // an inline ResourceLocation, the two branches above already handled it (the resolved
        // value, not the getfield, is what MethodSim hands back). Anything else here (a
        // genuinely dynamic computation — e.g. a runtime livery/damage lookup) is honestly
        // unresolved, matching the block-side ISBRH precedent: a wrong path is worse than a gap.
        r.unresolvedReason = HolderResolver.describe(v);
        return r;
    }

    // ================================================================================
    // Java-model (Techne) geometry: vanilla net.minecraft.client.model.ModelRenderer boxes and
    // rotation points, recovered from a Java-model class's OWN <init>(s) — bounded to that one
    // class's own declaration plus TECHNE_INIT_HOP_DEPTH hops into its own no-arg helper methods
    // (see the constant's javadoc). Never executes the model; every value here is a compile-time
    // bytecode operand.
    // ================================================================================

    private void resolveJavaModelGeometry(String javaModelInternalName, RendererInfo info) {
        ClassNode cn = jar.cls(javaModelInternalName);
        if (cn == null) return; // vanilla/library class, not in this jar — nothing to extract
        String dotted = JarIndex.dotted(javaModelInternalName);
        if (info.javaModelParts.containsKey(dotted)) return; // already done (a renderer can reference the same model twice)

        // ModelRenderer instance -> the part being built, threaded across this ONE <init>
        // method's single linear pass. IdentityHashMap: keyed by the exact Val object identity
        // MethodSim reuses across the PUTFIELD->GETFIELD reload round trip (see MethodSim's
        // `thisFieldValue`) and across ASTORE/ALOAD of a plain local variable.
        Map<Val, RendererInfo.TechnePart> partByReceiver = new java.util.IdentityHashMap<>();
        List<RendererInfo.TechnePart> parts = new ArrayList<>();

        MethodSim.Handler handler = (insn, stack, locals) -> {
            int op = insn.getOpcode();
            if (op == Opcodes.PUTFIELD) {
                FieldInsnNode f = (FieldInsnNode) insn;
                Val value = at(stack, 0), recv = at(stack, 1);
                if (recv != null && recv.kind == Val.Kind.THIS && value != null
                        && value.kind == Val.Kind.NEW_OBJ
                        && (MODEL_RENDERER.equals(value.typeName) || jar.isSubclassOf(value.typeName, MODEL_RENDERER))) {
                    partByReceiver.computeIfAbsent(value, k -> {
                        RendererInfo.TechnePart p = new RendererInfo.TechnePart();
                        p.field = dotted + "." + f.name;
                        noteCtorTexOffset(p, value);
                        parts.add(p);
                        return p;
                    });
                }
                return;
            }
            if (op != Opcodes.INVOKEVIRTUAL) return;
            MethodInsnNode m = (MethodInsnNode) insn;
            if (!MODEL_RENDERER.equals(m.owner)) return;
            if (ADD_BOX_NAME.equals(m.name) && ADD_BOX_DESC.equals(m.desc)) {
                Val recv = at(stack, 6);
                RendererInfo.TechnePart p = partFor(partByReceiver, recv);
                if (p == null) return;
                setBox(p, at(stack, 5), at(stack, 4), at(stack, 3), at(stack, 2), at(stack, 1), at(stack, 0), null);
                return;
            }
            if (ADD_BOX_SCALE_NAME.equals(m.name) && ADD_BOX_SCALE_DESC.equals(m.desc)) {
                Val recv = at(stack, 7);
                RendererInfo.TechnePart p = partFor(partByReceiver, recv);
                if (p == null) return;
                setBox(p, at(stack, 6), at(stack, 5), at(stack, 4), at(stack, 3), at(stack, 2), at(stack, 1), at(stack, 0));
                return;
            }
            if (SET_ROTATION_POINT_NAME.equals(m.name) && SET_ROTATION_POINT_DESC.equals(m.desc)) {
                Val recv = at(stack, 3);
                RendererInfo.TechnePart p = partFor(partByReceiver, recv);
                if (p == null) return;
                Val x = at(stack, 2), y = at(stack, 1), z = at(stack, 0);
                Float fx = asFloat(x), fy = asFloat(y), fz = asFloat(z);
                if (fx != null && fy != null && fz != null) { p.rotPointX = fx; p.rotPointY = fy; p.rotPointZ = fz; }
                else appendUnresolved(p, "setRotationPoint with a non-constant argument");
            }
        };

        // Bounded to each <init> method's OWN single linear pass — zero interprocedural hops.
        // Confirmed sufficient for every real Java-model class in this project's 5-mod corpus
        // (HBM's ModelBullet, MC Helicopters' MCH_ModelFlare/MCH_ModelTest — see
        // ENTITY-RENDER-EXTRACTION.md): all build their boxes directly in <init>. A Techne export
        // that splits part-building into a separate helper method called from <init> is a real,
        // disclosed, un-covered shape (this pass's own MethodSim.thisFieldValue tracking is
        // strictly per-method-execution, so a field written in <init> and only re-read in a
        // SEPARATE method loses identity at that boundary) — not attempted here per the "small,
        // bounded, generic step, not a whole-program walk" discipline; zero instances of it exist
        // in this project's own corpus, so nothing is silently lost by skipping it.
        for (MethodNode mn : cn.methods) if ("<init>".equals(mn.name)) MethodSim.run(cn, mn, jar, true, handler);
        if (!parts.isEmpty()) info.javaModelParts.put(dotted, parts);
    }

    /** Look up (or, if this exact receiver identity was never seen at a PUTFIELD, honestly
     *  decline rather than fabricate) the part a box/rotation call belongs to. A receiver that
     *  never reached a known {@code this.field = new ModelRenderer(...)} assignment (e.g. it is
     *  held only in a local array indexed dynamically, or handed in from outside this
     *  constructor) is a real, disclosed generic-analysis limit, not a bug. */
    private RendererInfo.TechnePart partFor(Map<Val, RendererInfo.TechnePart> partByReceiver, Val recv) {
        return recv == null ? null : partByReceiver.get(recv);
    }

    private void noteCtorTexOffset(RendererInfo.TechnePart p, Val ctorCall) {
        // ModelRenderer(ModelBase, int, int) — the only ctor overload that carries a texture
        // offset directly (javap-verified: the other two overloads take a name String, or
        // nothing, and rely on setTextureOffset/func_78784_a instead, which this pass does not
        // separately trace — a disclosed, narrower generic limit, not a guess).
        if (ctorCall.ctorArgs == null || ctorCall.ctorArgs.size() != 3) return;
        Val ox = ctorCall.ctorArgs.get(1), oy = ctorCall.ctorArgs.get(2);
        if (ox.kind == Val.Kind.NUMBER) p.texOffsetX = ox.numberValue.intValue();
        if (oy.kind == Val.Kind.NUMBER) p.texOffsetY = oy.numberValue.intValue();
    }

    private void setBox(RendererInfo.TechnePart p, Val x, Val y, Val z, Val w, Val h, Val d, Val scale) {
        Float fx = asFloat(x), fy = asFloat(y), fz = asFloat(z);
        Integer iw = asInt(w), ih = asInt(h), id = asInt(d);
        if (fx == null || fy == null || fz == null || iw == null || ih == null || id == null) {
            appendUnresolved(p, "addBox with a non-constant argument");
            return;
        }
        p.boxX = fx; p.boxY = fy; p.boxZ = fz; p.boxW = iw; p.boxH = ih; p.boxD = id;
        if (scale != null) {
            Float fs = asFloat(scale);
            if (fs != null) p.boxScale = fs; else appendUnresolved(p, "addBox scale is not a constant");
        }
    }

    private static void appendUnresolved(RendererInfo.TechnePart p, String reason) {
        p.unresolvedReason = p.unresolvedReason == null ? reason : (p.unresolvedReason + "; " + reason);
    }

    private static Float asFloat(Val v) { return v != null && v.kind == Val.Kind.NUMBER ? v.numberValue.floatValue() : null; }
    private static Integer asInt(Val v) { return v != null && v.kind == Val.Kind.NUMBER ? v.numberValue.intValue() : null; }
}
