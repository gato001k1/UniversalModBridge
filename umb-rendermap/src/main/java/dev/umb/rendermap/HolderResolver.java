package dev.umb.rendermap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds every class that declares static {@code IModelCustom} / {@code IModelCustomLoader} /
 * {@code ResourceLocation}-typed fields and resolves each one to a concrete resource path by
 * simulating the declaring class's {@code <clinit>}.
 *
 * <p>Recognised construction shapes, generically (no mod-specific class name required — see
 * {@link #isModelLoaderCtor}/{@link #isRecognizedModelDesc}):
 * <pre>
 *   new ResourceLocation(ldc "&lt;ns&gt;", ldc "models/x.obj")
 *   new ResourceLocation(ldc "&lt;ns&gt;:textures/models/x.png")
 *   new AnyModImplementorOfIModelCustom(RL)[.chainedCall()]
 *   new AnyModImplementorOfIModelCustomLoader(RL) — recognised by interface, not by name
 *   AdvancedModelLoader.loadModel(RL)               -- Forge's own loader
 * </pre>
 * A static field of any other jar-declared class type is admitted lazily, by the VALUE
 * assigned to it rather than its declared type: when a {@code PUTSTATIC} stores a value that
 * unwraps to a resource path (a model-loader construction around a {@code ResourceLocation},
 * or a bare one), the field becomes a holder row on the spot. Declared-type gating alone
 * cannot see these (e.g. a bespoke animated-model holder implementing no vanilla/Forge
 * interface); value-based admission sees exactly what the class stores, nothing more.
 */
public class HolderResolver {

    public static final String T_IMODELCUSTOM = "Lnet/minecraftforge/client/model/IModelCustom;";
    public static final String T_RESLOC = "Lnet/minecraft/util/ResourceLocation;";

    private static final Set<String> MODEL_DESCS = Set.of(T_IMODELCUSTOM);

    private static final String IMC_INTERNAL = "net/minecraftforge/client/model/IModelCustom";
    /**
     * Forge's generic model-LOADER-factory interface ({@code loadInstance(ResourceLocation)
     * -> IModelCustom}) — distinct from {@link #IMC_INTERNAL} itself (a loader need not also
     * implement the model interface it produces). Previously only recognised by three literal HBM
     * class names ({@code HFRWavefrontObject}, {@code HFRWavefrontObjectVBO}, {@code
     * HmfModelLoader}); all three are real implementors of one of these two standard interfaces
     * (javap-confirmed: the first two implement {@code IModelCustomNamed extends IModelCustom}, the
     * third implements this interface directly), so testing the INTERFACES generically, exactly
     * like {@link #isModelCustomImplementor}, recognises the same shape for any mod's own
     * model-loader class without naming it.
     */
    private static final String IMC_LOADER_INTERNAL = "net/minecraftforge/client/model/IModelCustomLoader";

    /** field key ("owner/Class.field") -> resolved reference. */
    public final Map<String, ResRef> byField = new LinkedHashMap<>();
    /** classes that hold at least one such static field. */
    public final Set<String> holderClasses = new LinkedHashSet<>();

    private final JarIndex jar;
    /** Snapshot-derived namespace used only for model-loader constructors that receive a bare path. */
    private final String defaultNamespace;

    public HolderResolver(JarIndex jar) { this(jar, null); }

    public HolderResolver(JarIndex jar, String defaultNamespace) {
        this.jar = jar;
        this.defaultNamespace = defaultNamespace == null || defaultNamespace.isBlank()
                ? null : defaultNamespace.toLowerCase(java.util.Locale.ROOT);
    }

    public void scanAll() {
        for (ClassNode cn : jar.classes.values()) {
            boolean any = false;
            for (FieldNode f : cn.fields) {
                if ((f.access & Opcodes.ACC_STATIC) == 0) continue;
                if (isRecognizedModelDesc(f.desc) || T_RESLOC.equals(f.desc)) {
                    any = true;
                    ResRef r = new ResRef(JarIndex.dotted(cn.name) + "." + f.name,
                            T_RESLOC.equals(f.desc) ? ResRef.Kind.TEXTURE : ResRef.Kind.MODEL);
                    r.unresolvedReason = "no <clinit> assignment matched";
                    byField.put(cn.name + "." + f.name, r);
                }
            }
            if (any) holderClasses.add(cn.name);
        }
        // Holders are assigned from <clinit> *and* from ordinary static init methods, and
        // sometimes from a different class entirely, so scan every static method in the jar.
        for (ClassNode cn : jar.classes.values()) {
            for (MethodNode mn : cn.methods) {
                if ((mn.access & Opcodes.ACC_STATIC) == 0) continue;
                MethodSim.run(cn, mn, (insn, stack, locals) -> {
                    if (insn.getOpcode() != Opcodes.PUTSTATIC) return;
                    FieldInsnNode f = (FieldInsnNode) insn;
                    ResRef ref = byField.get(f.owner + "." + f.name);
                    if (ref == null) {
                        ref = tryLazyAdmit(f, stack.isEmpty() ? null : stack.get(stack.size() - 1));
                        if (ref == null) return;
                    }
                    if (ref.resolved()) return;
                    if (stack.isEmpty()) { ref.unresolvedReason = "empty stack at putstatic"; return; }
                    resolveInto(ref, stack.get(stack.size() - 1));
                });
            }
        }
        // ResourceLocation fields that turned out to point at a model file are re-tagged
        for (ResRef r : byField.values()) {
            if (r.path != null && r.path.endsWith(".obj")) r.kind = ResRef.Kind.MODEL;
            else if (r.path != null && (r.path.endsWith(".png") || r.path.endsWith(".jpg")))
                r.kind = ResRef.Kind.TEXTURE;
        }
    }

    private void resolveInto(ResRef ref, Val v) {
        String rl = asResourceLocation(v);
        if (rl != null) {
            ref.path = rl;
            ref.assetPath = ResRef.toAssetPath(rl);
            ref.unresolvedReason = null;
            if (v.kind == Val.Kind.NEW_OBJ && isModelLoaderCtor(v.typeName))
                ref.loader = MethodSim.shortName(v.typeName);
            return;
        }
        ref.unresolvedReason = describe(v);
    }

    /**
     * Admits a static field of any other jar-declared class type as a holder row when the
     * value stored into it unwraps to a resource path (a model-holder construction around a
     * {@code ResourceLocation} among its constructor arguments). Admission is by stored value,
     * never by type name: a bespoke holder implementing no vanilla/Forge interface resolves
     * exactly like a standard one, and a field whose value has no resource location is not
     * admitted at all. Returns the new row, or null when the value does not qualify.
     */
    private ResRef tryLazyAdmit(FieldInsnNode f, Val v) {
        if (v == null || v.kind != Val.Kind.NEW_OBJ) return null;
        String fdesc = f.desc;
        if (fdesc == null || fdesc.length() < 3 || fdesc.charAt(0) != 'L' || !fdesc.endsWith(";")) return null;
        if (jar.cls(fdesc.substring(1, fdesc.length() - 1)) == null) return null;
        if (isRecognizedModelDesc(fdesc) || T_RESLOC.equals(fdesc)) return null;
        String rl = firstResourceArg(v);
        if (rl == null) return null;
        ResRef ref = new ResRef(JarIndex.dotted(f.owner) + "." + f.name, ResRef.Kind.MODEL);
        ref.path = rl;
        ref.assetPath = ResRef.toAssetPath(rl);
        ref.unresolvedReason = null;
        byField.put(f.owner + "." + f.name, ref);
        holderClasses.add(f.owner);
        return ref;
    }

    /** First constructor argument (any position) that unwraps to a resource path, else null. */
    private String firstResourceArg(Val v) {
        if (v == null || v.kind != Val.Kind.NEW_OBJ || v.ctorArgs == null) return null;
        for (Val a : v.ctorArgs) {
            String rl = asResourceLocation(a);
            if (rl != null) return rl;
        }
        return null;
    }

    /**
     * Reduce a symbolic value to a "domain:path" string, unwrapping model-loader
     * constructors and {@code AdvancedModelLoader.loadModel} results.
     */
    public String asResourceLocation(Val v) {
        if (v == null) return null;
        switch (v.kind) {
            case NEW_OBJ: {
                if ("net/minecraft/util/ResourceLocation".equals(v.typeName)) {
                    List<Val> a = v.ctorArgs;
                    if (a != null && a.size() == 2 && a.get(0).kind == Val.Kind.STRING
                            && a.get(1).kind == Val.Kind.STRING)
                        return a.get(0).stringValue + ":" + a.get(1).stringValue;
                    if (a != null && a.size() == 1 && a.get(0).kind == Val.Kind.STRING) {
                        String s = a.get(0).stringValue;
                        return s.contains(":") ? s : "minecraft:" + s;
                    }
                    return null;
                }
                if (isModelLoaderCtor(v.typeName) && v.ctorArgs != null && !v.ctorArgs.isEmpty()) {
                    String path = asResourceLocation(v.ctorArgs.get(0));
                    if (path != null) return path;
                    // Some generic IModelCustom implementations expose a constructor taking a
                    // bare asset path ("models/x.obj") instead of a ResourceLocation.  The
                    // namespace is not guessed from the class/package: RenderMap supplies the
                    // snapshot-derived mod identity to this resolver.
                    Val first = v.ctorArgs.get(0);
                    if (defaultNamespace != null && first.kind == Val.Kind.STRING
                            && first.stringValue != null && !first.stringValue.isBlank())
                        return defaultNamespace + ":" + first.stringValue;
                    return null;
                }
                if (v.ctorArgs != null && v.ctorArgs.size() == 1)
                    return asResourceLocation(v.ctorArgs.get(0));
                return null;
            }
            case STATIC_FIELD: {
                ResRef other = byField.get(v.owner + "." + v.name);
                return other != null ? other.path : null;
            }
            default: return null;
        }
    }

    /**
     * True when {@code new T(rl, ...)} should be unwrapped to the {@code ResourceLocation} it
     * was constructed with — the mod-agnostic test: {@code T} implements Forge's generic
     * {@code IModelCustom} model interface, OR Forge's generic {@code IModelCustomLoader}
     * loader-factory interface, whatever the concrete class is named. No mod-specific class name
     * is tested here (see the class javadoc for why the three literal HBM class names this used to
     * check are redundant with these two interface tests).
     */
    private boolean isModelLoaderCtor(String typeName) {
        return isModelCustomImplementor(typeName) || isModelCustomLoaderImplementor(typeName);
    }

    /** True when {@code desc} (a field descriptor, {@code "Lpkg/Type;"}) is a recognised model-
     *  holder type: {@link #T_IMODELCUSTOM} itself or an implementor of it or of {@link
     *  #IMC_LOADER_INTERNAL} (any mod's own model/loader class or interface). Any other
     *  jar-declared holder type is admitted lazily by assigned value (see the class javadoc). */
    private boolean isRecognizedModelDesc(String desc) {
        if (T_IMODELCUSTOM.equals(desc)) return true;
        if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L' || !desc.endsWith(";")) return false;
        String internal = desc.substring(1, desc.length() - 1);
        return isModelCustomImplementor(internal) || isModelCustomLoaderImplementor(internal);
    }

    private Set<String> modelCustomImplementors;
    public boolean isModelCustomImplementor(String internalName) {
        if (modelCustomImplementors == null) {
            modelCustomImplementors = new LinkedHashSet<>();
            for (ClassNode cn : jar.implementorsOf(IMC_INTERNAL)) modelCustomImplementors.add(cn.name);
        }
        return modelCustomImplementors.contains(internalName);
    }

    private Set<String> modelCustomLoaderImplementors;
    public boolean isModelCustomLoaderImplementor(String internalName) {
        if (modelCustomLoaderImplementors == null) {
            modelCustomLoaderImplementors = new LinkedHashSet<>();
            for (ClassNode cn : jar.implementorsOf(IMC_LOADER_INTERNAL)) modelCustomLoaderImplementors.add(cn.name);
        }
        return modelCustomLoaderImplementors.contains(internalName);
    }

    public static String describe(Val v) {
        if (v == null) return "null value";
        if (v.kind == Val.Kind.UNKNOWN) return v.reason;
        return "unmodelled value shape: " + v;
    }

    static MethodNode find(ClassNode cn, String name) {
        if (cn == null) return null;
        for (MethodNode m : cn.methods) if (m.name.equals(name)) return m;
        return null;
    }
}
