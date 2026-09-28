package dev.umb.rendermap;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Collects every item / block / tile-entity / entity renderer binding in the jar.
 *
 * <p><b>Resolver structure (mandate: "no class-name literals from any specific mod anywhere in
 * the resolution logic... structure it as a list of pluggable resolvers with the generic ones
 * first"):</b> {@link #scanAll()} runs, in order:
 * <ol>
 *   <li>{@link #scanGenericCallSites()} — the four registration APIs every 1.7.10 Forge mod must
 *       go through to have a custom renderer at all: {@code MinecraftForgeClient.registerItemRenderer},
 *       {@code ClientRegistry.bindTileEntitySpecialRenderer}, {@code RenderingRegistry.registerBlockHandler}
 *       (both overloads), {@code RenderingRegistry.registerEntityRenderingHandler}. Zero mod-specific
 *       literals. Handles the loop/array-registration shape (see {@link #expand}/{@link #zipExpand}).</li>
 *   <li>{@link #scanGenericSelfImplementingItemRenderers()} — any {@code Item} subclass that
 *       directly implements {@code IItemRenderer} is its own renderer, independent of how (or
 *       whether) it is ever explicitly registered. Zero mod-specific literals.</li>
 *   <li>{@link #scanGenericIsbrhImplementors()} — every class in the jar implementing
 *       {@code ISimpleBlockRenderingHandler}, regardless of registration call site (a mod may
 *       hold instances in a field, a list, build them lazily, ...). Zero mod-specific literals.</li>
 *   <li>{@link #scanGenericOrphanRenderers()} — every {@code TileEntitySpecialRenderer} /
 *       {@code Render} (entity) subclass that steps 1-3 above could not bind to any TE/entity
 *       class, reported honestly rather than silently dropped (1.7.10 has no generic way for a TE
 *       or Entity to self-report its renderer the way Items/ISBRHs can, so these two truly need a
 *       registration call site — this is a real, reportable limit of static analysis, not a bug).</li>
 *   <li>{@link #scanBonusIndirection()} (optional, runs LAST, {@link #includeBonusResolvers}
 *       gates it) — purely STRUCTURAL indirection shapes, detected by bytecode type flow with no
 *       mod-name, class-name, or method-name literals: static item-keyed renderer maps filled by
 *       {@code put}, static model-texture registry methods called with (item, model, texture)
 *       arguments, map-dispatch loops that bind those registries to renderers, and
 *       item-provider interfaces (an interface with a no-arg method returning
 *       {@code IItemRenderer} plus a no-arg method returning {@code Item}/{@code Item[]}).
 *       Turning it off must not regress anything the generic pass already found.</li>
 * </ol>
 * Every binding is tagged {@link ItemBinding#resolverKind} / etc. so {@code RenderMap} can report
 * the honest generic-only vs. generic+bonus coverage delta (mandate requirement 3).
 */
public class BindingScanner {

    public static final String GENERIC = "generic";
    public static final String BONUS_INDIRECTION = "bonus-indirection";

    // ---------- record types ----------

    public static class ItemBinding {
        public String producer;          // which call shape produced this
        public String site;              // declaring method
        public String itemFieldOwner;    // internal holder class, e.g. com/example/ModItems
        public String itemFieldName;
        public boolean viaBlock;         // came through Item.getItemFromBlock
        public String rendererClass;     // dotted
        public List<String> ctorArgFields = new ArrayList<>();
        public String directModelField;  // indirect-registry rows only
        public String directTextureField;
        public String unresolvedReason;
        public String rawItemArg;
        public String rendererFactory;   // generateStandard / generateLarge / ...
        /** set when getItemForRenderer() returns `this`: the item *is* an instance of this class. */
        public String itemSelfClass;
        /**
         * Set only by the indirect model-texture registry scan: the internal class that owns
         * the static register-shape method this row came from. Used to join the row with the
         * map-dispatch loop that binds the registry to its renderer class.
         */
        public String registryOwner;
        /** {@link #GENERIC} or {@link #BONUS_INDIRECTION} — which resolver tier produced this row. */
        public String resolverKind = GENERIC;
    }

    public static class TesrBinding {
        public String teClass;           // dotted
        public String rendererClass;     // dotted
        public String unresolvedReason;
        public String resolverKind = GENERIC;
    }

    public static class IsbrhBinding {
        public String handlerClass;      // dotted
        public String renderIdFieldOwner; // internal
        public String renderIdFieldName;
        public String unresolvedReason;
        public String producer;
        public String resolverKind = GENERIC;
    }

    public static class EntityBinding {
        public String entityClass;
        public String rendererClass;
        public String unresolvedReason;
        public String resolverKind = GENERIC;
    }

    /** getRenderType() analysis of one Block subclass. */
    public static class BlockRenderId {
        public String blockClass;                 // dotted
        public String defaultFieldOwner, defaultFieldName;
        public Integer defaultConstant;
        /** ModBlocks field -> render-id static field ("owner#name"). */
        public Map<String, String> perBlockField = new LinkedHashMap<>();
        public String note;
    }

    /** A renderer class found by implementor-scan that no resolver could bind to any content. */
    public static class OrphanRenderer {
        public String rendererClass;
        public String interfaceOrSuper; // "TileEntitySpecialRenderer" | "Render"
        public String reason;
    }

    public final List<ItemBinding> itemBindings = new ArrayList<>();
    public final List<TesrBinding> tesrBindings = new ArrayList<>();
    public final List<IsbrhBinding> isbrhBindings = new ArrayList<>();
    public final List<EntityBinding> entityBindings = new ArrayList<>();
    public final Map<String, BlockRenderId> blockRenderIds = new LinkedHashMap<>();
    public final List<OrphanRenderer> orphanRenderers = new ArrayList<>();
    public final List<String> notes = new ArrayList<>();

    /** When false, {@link #scanBonusIndirection()} does not run — used to measure the honest
     *  generic-only coverage delta (mandate requirement 3) without a second full jar re-read. */
    public boolean includeBonusResolvers = true;

    private final JarIndex jar;

    // ---- the universal Forge/vanilla APIs (verified with javap against the 1.7.10 Forge
    // universal jar + SRG client jar — see umb-rendermap/README.md "Verified signatures") ----
    private static final String MFC = "net/minecraftforge/client/MinecraftForgeClient";
    private static final String CLIENT_REG = "cpw/mods/fml/client/registry/ClientRegistry";
    private static final String RENDER_REG = "cpw/mods/fml/client/registry/RenderingRegistry";
    private static final String IITEM_RENDERER = "net/minecraftforge/client/IItemRenderer";
    /** NOTE: this interface lives under {@code cpw.mods.fml.client.registry}, not
     *  {@code net.minecraftforge.client} — verified with
     *  {@code javap -classpath forge-1.7.10-...-universal.jar cpw.mods.fml.client.registry.ISimpleBlockRenderingHandler}. */
    private static final String ISBRH = "cpw/mods/fml/client/registry/ISimpleBlockRenderingHandler";
    private static final String TESR = "net/minecraft/client/renderer/tileentity/TileEntitySpecialRenderer";
    private static final String RENDER_ENTITY = "net/minecraft/client/renderer/entity/Render";
    private static final String T_ITEM = "net/minecraft/item/Item";
    private static final String D_ITEM = "Lnet/minecraft/item/Item;";
    private static final String D_ITEM_ARRAY = "[Lnet/minecraft/item/Item;";
    private static final String D_IITEM_RENDERER = "Lnet/minecraftforge/client/IItemRenderer;";
    private static final String D_RESLOC = "Lnet/minecraft/util/ResourceLocation;";
    private static final String D_IMODELCUSTOM = "net/minecraftforge/client/model/IModelCustom";

    public BindingScanner(JarIndex jar) { this.jar = jar; }

    public void scanAll() {
        scanGenericCallSites();
        scanGenericSelfImplementingItemRenderers();
        scanGenericIsbrhImplementors();
        scanGenericOrphanRenderers();
        scanBlockRenderTypes();
        if (includeBonusResolvers) scanBonusIndirection();
    }

    // ================================================================================
    // GENERIC resolver 1: the four universal registration call sites
    // ================================================================================

    private void scanGenericCallSites() {
        for (ClassNode cn : jar.classes.values()) {
            for (MethodNode mn : cn.methods) {
                final String site = JarIndex.dotted(cn.name) + "." + mn.name;
                // Pass `jar` so a `getfield` off a parameter whose OWN declared type is a
                // jar-declared enum expands to one value per enum constant (see MethodSim's
                // tryEnumFieldPerConstant javadoc) — recovers e.g. Iron Chests'
                // `ClientRegistry.bindTileEntitySpecialRenderer(type.clazz, new Tesr())` called
                // once per IronChestType constant, where `type` is this method's own enum-typed
                // parameter, not a compile-time array element.
                MethodSim.run(cn, mn, jar, (insn, stack, locals) -> onGenericInsn(site, insn, stack));
            }
        }
    }

    private void onGenericInsn(String site, AbstractInsnNode insn, List<Val> stack) {
        int op = insn.getOpcode();
        if (op != Opcodes.INVOKESTATIC) return;
        MethodInsnNode m = (MethodInsnNode) insn;
        int argc = Type.getArgumentTypes(m.desc).length;

        // MinecraftForgeClient.registerItemRenderer(Item, IItemRenderer)
        if (MFC.equals(m.owner) && "registerItemRenderer".equals(m.name) && argc == 2) {
            for (Val[] pair : zipExpand(at(stack, 1), at(stack, 0)))
                addItemBinding("MinecraftForgeClient.registerItemRenderer", site, pair[0], pair[1], GENERIC);
            return;
        }
        // ClientRegistry.bindTileEntitySpecialRenderer(Class, TileEntitySpecialRenderer)
        if (CLIENT_REG.equals(m.owner) && "bindTileEntitySpecialRenderer".equals(m.name) && argc == 2) {
            for (Val[] pair : zipExpand(at(stack, 1), at(stack, 0))) {
                TesrBinding t = new TesrBinding();
                Val cls = pair[0], rend = pair[1];
                if (cls != null && cls.kind == Val.Kind.CLASS) t.teClass = JarIndex.dotted(cls.typeName);
                else t.unresolvedReason = "TE class arg is not an ldc class (" + cls + ")";
                if (rend != null && rend.kind == Val.Kind.NEW_OBJ) t.rendererClass = JarIndex.dotted(rend.typeName);
                else t.unresolvedReason = append(t.unresolvedReason, "renderer arg is not a `new` (" + rend + ")");
                tesrBindings.add(t);
            }
            return;
        }
        // RenderingRegistry.registerBlockHandler([int,] ISimpleBlockRenderingHandler)
        if (RENDER_REG.equals(m.owner) && "registerBlockHandler".equals(m.name)) {
            for (Val h : expand(at(stack, 0))) {
                IsbrhBinding b = new IsbrhBinding();
                b.producer = "RenderingRegistry.registerBlockHandler";
                if (h != null && h.kind == Val.Kind.NEW_OBJ) b.handlerClass = JarIndex.dotted(h.typeName);
                else b.unresolvedReason = "handler arg is not a `new` (" + h + ")";
                isbrhBindings.add(b);
            }
            return;
        }
        // RenderingRegistry.registerEntityRenderingHandler(Class, Render)
        if (RENDER_REG.equals(m.owner) && "registerEntityRenderingHandler".equals(m.name) && argc == 2) {
            for (Val[] pair : zipExpand(at(stack, 1), at(stack, 0))) {
                EntityBinding e = new EntityBinding();
                Val cls = pair[0], rend = pair[1];
                if (cls != null && cls.kind == Val.Kind.CLASS) e.entityClass = JarIndex.dotted(cls.typeName);
                else e.unresolvedReason = "entity class arg is not an ldc class (" + cls + ")";
                if (rend != null && rend.kind == Val.Kind.NEW_OBJ) e.rendererClass = JarIndex.dotted(rend.typeName);
                else e.unresolvedReason = append(e.unresolvedReason, "renderer arg is not a `new` (" + rend + ")");
                entityBindings.add(e);
            }
        }
    }

    private void addItemBinding(String producer, String site, Val itemArg, Val rendererArg, String kind) {
        ItemBinding b = new ItemBinding();
        b.producer = producer;
        b.site = site;
        b.resolverKind = kind;
        setItemSource(b, itemArg);
        if (rendererArg != null && rendererArg.kind == Val.Kind.NEW_OBJ) {
            b.rendererClass = JarIndex.dotted(rendererArg.typeName);
            if (rendererArg.ctorArgs != null)
                for (Val a : rendererArg.ctorArgs)
                    if (a.kind == Val.Kind.STATIC_FIELD)
                        b.ctorArgFields.add(JarIndex.dotted(a.owner) + "." + a.name);
        } else if (rendererArg != null && rendererArg.kind == Val.Kind.STATIC_FIELD) {
            b.rendererClass = null;
            b.unresolvedReason = append(b.unresolvedReason,
                    "renderer is a static field (" + rendererArg + "), not a `new`");
        } else {
            b.unresolvedReason = append(b.unresolvedReason,
                    "renderer arg not resolvable (" + rendererArg + ")");
        }
        itemBindings.add(b);
    }

    private void setItemSource(ItemBinding b, Val itemArg) {
        if (itemArg == null) { b.unresolvedReason = append(b.unresolvedReason, "no item argument on stack"); return; }
        Val v = itemArg;
        if (v.kind == Val.Kind.ITEM_FROM_BLOCK) { b.viaBlock = true; v = v.inner; }
        if (v.kind == Val.Kind.STATIC_FIELD) {
            b.itemFieldOwner = v.owner;
            b.itemFieldName = v.name;
        } else {
            b.rawItemArg = String.valueOf(v);
            b.unresolvedReason = append(b.unresolvedReason, "item source is not a getstatic: " + v);
        }
    }

    // ---------- loop/array registration support (mod-agnostic) ----------

    /**
     * {@code v} as a list of values to bind, one per call. A plain value expands to a
     * one-element list; a value that came from {@code array[i]} at a non-constant index (a
     * {@code for} loop over a compile-time array — see {@link MethodSim#arrayGet}) expands to
     * every element of that array, so {@code for (ISBRH h : handlers) registerBlockHandler(h);}
     * is treated exactly like {@code n} separate direct calls.
     */
    static List<Val> expand(Val v) {
        List<Val> out = new ArrayList<>();
        if (v != null && v.kind == Val.Kind.ARRAY_UNKNOWN_INDEX
                && v.arraySource != null && v.arraySource.elements != null
                && !v.arraySource.elements.isEmpty()) {
            out.addAll(v.arraySource.elements);
        } else {
            out.add(v);
        }
        return out;
    }

    /**
     * Pairs up two call-site arguments, each independently possibly array-valued (the "parallel
     * arrays" loop shape: {@code for (i) register(items[i], renderers[i]);}). When only one side
     * is array-valued, the other side is broadcast to every slot (one shared renderer/class
     * reused across many array entries). When both are array-valued but of different length, the
     * shorter side pads with an explicit unresolved marker rather than guessing a pairing.
     */
    static List<Val[]> zipExpand(Val a, Val b) {
        List<Val> as = expand(a), bs = expand(b);
        int n = Math.max(as.size(), bs.size());
        List<Val[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            Val av = as.size() == 1 ? as.get(0)
                    : i < as.size() ? as.get(i) : Val.unknown("array shorter than its paired array");
            Val bv = bs.size() == 1 ? bs.get(0)
                    : i < bs.size() ? bs.get(i) : Val.unknown("array shorter than its paired array");
            out.add(new Val[]{av, bv});
        }
        return out;
    }

    // ================================================================================
    // GENERIC resolver 2: an Item subclass that directly implements IItemRenderer is its own
    // renderer, independent of any registration call site. This subsumes HBM's own
    // IItemRendererProvider "getItemForRenderer() { return this; }" pattern with the STANDARD
    // Forge interface instead of a mod-invented one, so it works on any mod that writes this
    // (fairly common) shape.
    // ================================================================================

    private void scanGenericSelfImplementingItemRenderers() {
        for (ClassNode cn : jar.implementorsOf(IITEM_RENDERER)) {
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || (cn.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            if (!jar.isSubclassOf(cn.name, T_ITEM)) continue;
            ItemBinding b = new ItemBinding();
            b.producer = "self-implementing IItemRenderer (Item subclass directly implements IItemRenderer)";
            b.site = JarIndex.dotted(cn.name);
            b.rendererClass = JarIndex.dotted(cn.name);
            b.itemSelfClass = JarIndex.dotted(cn.name);
            b.resolverKind = GENERIC;
            itemBindings.add(b);
        }
    }

    // ================================================================================
    // GENERIC resolver 3: every ISBRH implementor in the jar, regardless of how (or whether we
    // could prove) it is registered. getRenderId()'s static-field return is later matched against
    // each block's getRenderType() (scanBlockRenderTypes/RenderMap.buildBlocks) independent of the
    // registration call site, so this works even when a mod builds/holds its handlers in ways the
    // call-site scanner above cannot see (a field, a list, constructed lazily, ...).
    // ================================================================================

    private void scanGenericIsbrhImplementors() {
        Set<String> already = new LinkedHashSet<>();
        for (IsbrhBinding b : isbrhBindings) if (b.handlerClass != null) already.add(b.handlerClass);
        for (ClassNode cn : jar.implementorsOf(ISBRH)) {
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || (cn.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            String dotted = JarIndex.dotted(cn.name);
            if (!already.add(dotted)) continue;
            IsbrhBinding b = new IsbrhBinding();
            b.handlerClass = dotted;
            b.producer = "implementor-scan (implements ISimpleBlockRenderingHandler;"
                    + " no registerBlockHandler call site required)";
            b.resolverKind = GENERIC;
            isbrhBindings.add(b);
        }
    }

    // ================================================================================
    // GENERIC resolver 4: report, honestly, every TESR/Render implementor that no resolver above
    // could bind to a TE class / entity class. Unlike ISBRH (which self-reports its render id) or
    // Item (which can self-report by implementing IItemRenderer), 1.7.10 has no generic API for a
    // TileEntity or Entity to name its own renderer — the ClientRegistry/RenderingRegistry call
    // site is the ONLY place that link is ever made. When that call site could not be resolved
    // (or genuinely does not exist because the mod uses reflection/a data-driven registry we
    // cannot see statically), the implementor is a real, reportable gap rather than a silent zero.
    // ================================================================================

    private void scanGenericOrphanRenderers() {
        Set<String> boundTesr = new LinkedHashSet<>();
        for (TesrBinding t : tesrBindings) if (t.rendererClass != null) boundTesr.add(JarIndex.internal(t.rendererClass));
        // TileEntitySpecialRenderer is an ABSTRACT CLASS, not an interface — jar.implementorsOf
        // (which only ever walks `interfaces` lists) cannot see its subclasses. jar.isSubclassOf
        // (a plain superName-chain walk) is the correct, mod-agnostic test here, exactly like
        // net/minecraft/client/renderer/entity/Render below.
        for (ClassNode cn : jar.classes.values()) {
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || (cn.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            if (!jar.isSubclassOf(cn.name, TESR) || cn.name.equals(TESR)) continue;
            if (boundTesr.contains(cn.name)) continue;
            OrphanRenderer o = new OrphanRenderer();
            o.rendererClass = JarIndex.dotted(cn.name);
            o.interfaceOrSuper = "TileEntitySpecialRenderer";
            o.reason = "implements TileEntitySpecialRenderer but no bindTileEntitySpecialRenderer"
                    + " call site in the jar resolved to this class";
            orphanRenderers.add(o);
        }
        Set<String> boundEntity = new LinkedHashSet<>();
        for (EntityBinding e : entityBindings) if (e.rendererClass != null) boundEntity.add(JarIndex.internal(e.rendererClass));
        for (ClassNode cn : jar.classes.values()) {
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0 || (cn.access & Opcodes.ACC_ABSTRACT) != 0) continue;
            if (!jar.isSubclassOf(cn.name, RENDER_ENTITY) || cn.name.equals(RENDER_ENTITY)) continue;
            if (boundEntity.contains(cn.name)) continue;
            OrphanRenderer o = new OrphanRenderer();
            o.rendererClass = JarIndex.dotted(cn.name);
            o.interfaceOrSuper = "Render";
            o.reason = "extends net.minecraft.client.renderer.entity.Render but no"
                    + " registerEntityRenderingHandler call site in the jar resolved to this class";
            orphanRenderers.add(o);
        }
    }

    // ================================================================================
    // BONUS tier: structural renderer indirection (no mod-name, class-name, or method-name
    // literals anywhere below). Four shapes, each detected purely by bytecode type flow:
    //  1. renderer maps: a static java/util map field filled by `put(itemKey, rendererValue)`
    //     where the value is a model-factory call result (a static call taking both a
    //     ResourceLocation field and a model field) or a genuine IItemRenderer instance. Map
    //     keys that wrap the item in a single-constructor-arg holder object are unwrapped.
    //  2. indirect model-texture registries: a static method (any owner) called with an item
    //     argument plus static model + texture fields. The renderer class is never a literal
    //     here — it comes from the shape-3 join below.
    //  3. map-dispatch loops: a `registerItemRenderer` call site that constructs its renderer
    //     as `new R(registryEntry...)` while iterating a static map. Joined with shape 2 by
    //     registry-owner class.
    //  4. item-provider interfaces: any jar-declared interface with a no-arg method returning
    //     IItemRenderer plus a no-arg method returning Item/Item[]; implementors bind by
    //     simulating those methods (`this` binds every snapshot item of the implementor class).

    private void scanBonusIndirection() {
        for (ClassNode cn : jar.classes.values()) {
            for (MethodNode mn : cn.methods) {
                final String site = JarIndex.dotted(cn.name) + "." + mn.name;
                MethodSim.run(cn, mn, (insn, stack, locals) -> onIndirectionInsn(site, insn, stack));
            }
        }
        scanMapDispatchBindings();
        joinRegistryRenderers();
        scanProviderInterfaces();
    }

    /** A field descriptor for a static item-keyed renderer registry: any java/util map type. */
    static boolean isMapFieldDesc(String desc) {
        if (desc == null || !desc.startsWith("Ljava/util/") || !desc.endsWith(";")) return false;
        String simple = desc.substring("Ljava/util/".length(), desc.length() - 1);
        return simple.contains("Map");
    }

    private Set<String> modelCustomImplementors;
    private boolean isModelType(String desc) {
        if (desc == null || desc.length() < 3 || desc.charAt(0) != 'L' || !desc.endsWith(";")) return false;
        if (D_RESLOC.equals(desc)) return false;
        if (("L" + D_IMODELCUSTOM + ";").equals(desc)) return true;
        String internal = desc.substring(1, desc.length() - 1);
        if (modelCustomImplementors == null) {
            modelCustomImplementors = new LinkedHashSet<>();
            for (ClassNode cn : jar.implementorsOf(D_IMODELCUSTOM)) modelCustomImplementors.add(cn.name);
        }
        return modelCustomImplementors.contains(internal);
    }
    private void onIndirectionInsn(String site, AbstractInsnNode insn, List<Val> stack) {
        int op = insn.getOpcode();
        if (op != Opcodes.INVOKESTATIC && op != Opcodes.INVOKEVIRTUAL) return;
        MethodInsnNode m = (MethodInsnNode) insn;
        int argc = Type.getArgumentTypes(m.desc).length;

        // Shape 1: <static map field>.put(itemKey, rendererValue)
        if (op == Opcodes.INVOKEVIRTUAL && "java/util/HashMap".equals(m.owner) && "put".equals(m.name) && argc == 2) {
            Val recv = at(stack, 2);
            if (recv == null || recv.kind != Val.Kind.STATIC_FIELD || !isMapFieldDesc(recv.desc)) return;
            String producer = MethodSim.shortName(recv.owner) + "." + recv.name + ".put";
            Val key = at(stack, 1), value = at(stack, 0);

            // A wrapper/newtype around the item (single-constructor-arg holder): unwrap to
            // it. Anything unresolvable downstream stays an explicitly unresolved row
            // (buildItems drops ids absent from the snapshot), so this never misattributes.
            Val realKey = key;
            if (key != null && key.kind == Val.Kind.NEW_OBJ
                    && key.ctorArgs != null && !key.ctorArgs.isEmpty())
                realKey = key.ctorArgs.get(0);

            // value is a model-factory application: a static call combining a
            // ResourceLocation field with a model field into the registered renderer object.
            if (value != null && value.kind == Val.Kind.CALL) {
                Val texArg = null, modelArg = null;
                if (value.ctorArgs != null) for (Val a : value.ctorArgs) {
                    if (a.kind != Val.Kind.STATIC_FIELD) continue;
                    if (D_RESLOC.equals(a.desc)) texArg = a;
                    else if (isModelType(a.desc) && modelArg == null) modelArg = a;
                }
                if (texArg != null && modelArg != null) {
                    ItemBinding b = new ItemBinding();
                    b.producer = producer;
                    b.site = site;
                    b.resolverKind = BONUS_INDIRECTION;
                    b.rendererClass = JarIndex.dotted(value.owner);
                    b.rendererFactory = value.name;
                    setItemSource(b, realKey);
                    b.directTextureField =
                            JarIndex.dotted(texArg.owner) + "." + texArg.name;
                    b.directModelField =
                            JarIndex.dotted(modelArg.owner) + "." + modelArg.name;
                    itemBindings.add(b);
                    return;
                }
            }
            // otherwise only accept a genuine IItemRenderer instance as the value
            if (value != null && value.kind == Val.Kind.NEW_OBJ && isItemRenderer(value.typeName))
                addItemBinding(producer, site, realKey, value, BONUS_INDIRECTION);
            return;
        }
        // Shape 2: an indirect model-texture registry call carrying (item, model, texture).
        // The renderer class is deliberately NOT set here — it comes from the shape-3 join
        // below, never from a literal.
        if (op == Opcodes.INVOKESTATIC && argc >= 3) {
            Val itemArg = at(stack, argc - 1);
            Val modelArg = null, texArg = null;
            for (int a = 0; a < argc; a++) {
                Val v = at(stack, a);
                if (v != null && v.kind == Val.Kind.STATIC_FIELD) {
                    if (isModelType(v.desc) && modelArg == null) modelArg = v;
                    else if (D_RESLOC.equals(v.desc) && texArg == null) texArg = v;
                }
            }
            if (modelArg == null || texArg == null) return;
            ItemBinding b = new ItemBinding();
            b.producer = MethodSim.shortName(m.owner) + "." + m.name;
            b.site = site;
            b.resolverKind = BONUS_INDIRECTION;
            b.registryOwner = m.owner;
            setItemSource(b, itemArg);
            b.directModelField = JarIndex.dotted(modelArg.owner) + "." + modelArg.name;
            b.directTextureField = JarIndex.dotted(texArg.owner) + "." + texArg.name;
            itemBindings.add(b);
        }
    }

    /** owner internal name -> renderer class from shape-3 dispatch loops. */
    private final Map<String, String> registryDispatchRenderer = new LinkedHashMap<>();

    /**
     * Shape 3: a {@code registerItemRenderer} call site that constructs its renderer as
     * {@code new R(registryEntry...)} while iterating a static map. Direct instruction walk
     * (no sim types needed): the method must contain the register call, a {@code NEW R} whose
     * constructor takes a registry-owner-typed argument, and a static-map iteration
     * (entrySet/values/keySet) over that same owner's map field.
     */
    private void scanMapDispatchBindings() {
        Set<String> registryOwners = new LinkedHashSet<>();
        for (ItemBinding b : itemBindings)
            if (b.registryOwner != null) registryOwners.add(b.registryOwner);
        if (registryOwners.isEmpty()) return;
        for (ClassNode cn : jar.classes.values()) {
            for (MethodNode mn : cn.methods) {
                if (mn.instructions == null) continue;
                boolean hasRegisterCall = false;
                for (AbstractInsnNode in = mn.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in.getOpcode() == Opcodes.INVOKESTATIC) {
                        MethodInsnNode m = (MethodInsnNode) in;
                        if (MFC.equals(m.owner) && "registerItemRenderer".equals(m.name)) { hasRegisterCall = true; break; }
                    }
                }
                if (!hasRegisterCall) continue;
                AbstractInsnNode[] ins = mn.instructions.toArray();
                for (int i = 0; i < ins.length; i++) {
                    if (ins[i].getOpcode() != Opcodes.NEW) continue;
                    String r = ((org.objectweb.asm.tree.TypeInsnNode) ins[i]).desc;
                    if (!isItemRenderer(r)) continue;
                    String owner = dispatchRegistryOwner(ins, i, registryOwners);
                    if (owner != null && !registryDispatchRenderer.containsKey(owner))
                        registryDispatchRenderer.put(owner, JarIndex.dotted(r));
                }
            }
        }
    }

    /**
     * From a {@code NEW R} site, find a constructor argument of a registry-owner type plus a
     * static-map iteration over that same owner's map field in the same method.
     */
    private String dispatchRegistryOwner(AbstractInsnNode[] ins, int newIdx, Set<String> registryOwners) {
        String ctorOwner = null;
        for (int i = newIdx + 1; i < Math.min(ins.length, newIdx + 12); i++) {
            if (ins[i].getOpcode() != Opcodes.INVOKESPECIAL) continue;
            MethodInsnNode m = (MethodInsnNode) ins[i];
            if (!"<init>".equals(m.name)) continue;
            for (Type t : Type.getArgumentTypes(m.desc)) {
                if (t.getSort() != Type.OBJECT) continue;
                String internal = t.getInternalName();
                if (registryOwners.contains(internal)) { ctorOwner = internal; break; }
            }
            break;
        }
        if (ctorOwner == null) return null;
        boolean iterated = false;
        for (AbstractInsnNode in : ins) {
            if (in.getOpcode() != Opcodes.GETSTATIC) continue;
            FieldInsnNode f = (FieldInsnNode) in;
            if (!ctorOwner.equals(f.owner) || !isMapFieldDesc(f.desc)) continue;
            iterated = true;
            break;
        }
        if (!iterated) return null;
        for (AbstractInsnNode in : ins) {
            if (in instanceof MethodInsnNode) {
                String n = ((MethodInsnNode) in).name;
                if ("entrySet".equals(n) || "values".equals(n) || "keySet".equals(n)) return ctorOwner;
            }
        }
        return null;
    }

    /** Joins shape-2 registry rows with their shape-3 dispatch renderer (never a literal). */
    private void joinRegistryRenderers() {
        for (ItemBinding b : itemBindings) {
            if (b.registryOwner == null || b.rendererClass != null) continue;
            String r = registryDispatchRenderer.get(b.registryOwner);
            if (r != null) b.rendererClass = r;
        }
    }

    // ---------- item-provider interfaces (shape 4; fully structural) ----------

    /**
     * Any jar-declared interface with a no-arg method returning {@code IItemRenderer} plus a
     * no-arg method returning {@code Item}/{@code Item[]} is an item-provider interface: its
     * implementors bind items to renderers by simulating those methods. Returning
     * {@code this} binds every snapshot item of the implementor's own class.
     */
    private void scanProviderInterfaces() {
        for (ClassNode cn : jar.classes.values()) {
            if ((cn.access & Opcodes.ACC_INTERFACE) == 0) continue;
            boolean hasRendererMethod = false, hasItemMethod = false;
            for (MethodNode mn : cn.methods) {
                if (mn == null || mn.desc == null) continue;
                if (Type.getArgumentTypes(mn.desc).length != 0) continue;
                String ret = Type.getReturnType(mn.desc).getDescriptor();
                if (D_IITEM_RENDERER.equals(ret)) hasRendererMethod = true;
                if (D_ITEM.equals(ret) || D_ITEM_ARRAY.equals(ret)) hasItemMethod = true;
            }
            if (!hasRendererMethod || !hasItemMethod) continue;
            scanProviderImplementors(cn);
        }
    }

    private void scanProviderImplementors(ClassNode iface) {
        for (ClassNode cn : jar.implementorsOf(iface.name)) {
            if (cn.name.equals(iface.name)) continue;
            if ((cn.access & Opcodes.ACC_INTERFACE) != 0) continue;
            MethodNode multi = findNoArgReturning(cn, D_ITEM_ARRAY);
            MethodNode single = multi == null ? findNoArgReturning(cn, D_ITEM) : null;
            if (single == null && multi == null) {
                for (MethodNode mn : cn.methods) {
                    if (Type.getArgumentTypes(mn.desc).length != 0) continue;
                    String ret = Type.getReturnType(mn.desc).getDescriptor();
                    if (D_ITEM.equals(ret)) { single = mn; break; }
                }
            }
            MethodNode getR = findNoArgReturning(cn, D_IITEM_RENDERER);

            String producer = "provider-interface:" + JarIndex.dotted(iface.name);
            String rendererClass = JarIndex.dotted(cn.name);
            if (getR != null) {
                String[] found = new String[1];
                MethodSim.run(cn, getR, (insn, stack, locals) -> {
                    if (insn.getOpcode() == Opcodes.ARETURN && !stack.isEmpty()) {
                        Val v = stack.get(stack.size() - 1);
                        if (v.kind == Val.Kind.NEW_OBJ) found[0] = JarIndex.dotted(v.typeName);
                    }
                });
                if (found[0] != null) rendererClass = found[0];
            }

            List<Val> sources = new ArrayList<>();
            List<String> sourceMethods = new ArrayList<>();
            if (multi != null) {
                for (Val v : returnedItemArray(cn, multi)) { sources.add(v); sourceMethods.add(multi.name); }
            }
            if (sources.isEmpty() && single != null) {
                Val v = returnedValue(cn, single);
                if (v != null) { sources.add(v); sourceMethods.add(single.name); }
            }
            if (sources.isEmpty()) {
                ItemBinding b = new ItemBinding();
                b.producer = producer;
                b.site = JarIndex.dotted(cn.name) + ".itemSource";
                b.rendererClass = rendererClass;
                b.resolverKind = BONUS_INDIRECTION;
                b.unresolvedReason = "could not resolve the returned Item(s)";
                itemBindings.add(b);
                continue;
            }
            for (int i = 0; i < sources.size(); i++) {
                Val s = sources.get(i);
                ItemBinding b = new ItemBinding();
                b.producer = producer;
                b.site = JarIndex.dotted(cn.name) + "." + sourceMethods.get(i);
                b.rendererClass = rendererClass;
                b.resolverKind = BONUS_INDIRECTION;
                if (s != null && s.kind == Val.Kind.THIS) {
                    // the Item class implements the provider interface itself
                    // (`getItemForRenderer() { return this; }`), so every registry entry whose
                    // className is this class is bound to this renderer.
                    b.itemSelfClass = JarIndex.dotted(cn.name);
                } else {
                    setItemSource(b, s);
                }
                itemBindings.add(b);
            }
        }
    }

    /** First no-arg method on cn (declared only) with exactly this return descriptor. */
    private static MethodNode findNoArgReturning(ClassNode cn, String retDesc) {
        if (cn == null || cn.methods == null) return null;
        for (MethodNode mn : cn.methods) {
            if (mn == null || mn.desc == null) continue;
            if (Type.getArgumentTypes(mn.desc).length != 0) continue;
            if (retDesc.equals(Type.getReturnType(mn.desc).getDescriptor())) return mn;
        }
        return null;
    }

    private Val returnedValue(ClassNode cn, MethodNode mn) {
        Val[] out = new Val[1];
        MethodSim.run(cn, mn, (insn, stack, locals) -> {
            if (insn.getOpcode() == Opcodes.ARETURN && !stack.isEmpty() && out[0] == null)
                out[0] = stack.get(stack.size() - 1);
        });
        return out[0];
    }

    private List<Val> returnedItemArray(ClassNode cn, MethodNode mn) {
        List<Val> out = new ArrayList<>();
        Val v = returnedValue(cn, mn);
        if (v != null && v.kind == Val.Kind.ARRAY && v.elements != null) out.addAll(v.elements);
        return out;
    }

    // ================================================================================
    // block render ids (generic — no mod-specific literals; unchanged)
    // ================================================================================

    /**
     * For each Block subclass that overrides {@code getRenderType()} (func_149645_b),
     * recover the returned render-id static field(s). Two shapes are modelled:
     * <pre>
     *   aload_0; getstatic ModBlocks.F; if_acmpne L; getstatic OWNER.R:I; ireturn   (per-block)
     *   getstatic OWNER.R:I; ireturn                                                (class default)
     *   iconst_N / bipush N; ireturn                                                (constant)
     * </pre>
     */
    private void scanBlockRenderTypes() {
        for (ClassNode cn : jar.classes.values()) {
            MethodNode mn = null;
            for (MethodNode c : cn.methods)
                if (("func_149645_b".equals(c.name) || "getRenderType".equals(c.name)) && "()I".equals(c.desc))
                    mn = c;
            if (mn == null || mn.instructions == null) continue;

            BlockRenderId br = new BlockRenderId();
            br.blockClass = JarIndex.dotted(cn.name);

            AbstractInsnNode[] ins = mn.instructions.toArray();
            List<AbstractInsnNode> real = new ArrayList<>();
            for (AbstractInsnNode i : ins)
                if (i.getOpcode() >= 0 || i instanceof org.objectweb.asm.tree.LabelNode) real.add(i);

            String pendingBlockField = null;
            for (int i = 0; i < real.size(); i++) {
                AbstractInsnNode a = real.get(i);
                if (a instanceof org.objectweb.asm.tree.LabelNode) { pendingBlockField = null; continue; }
                // this == ModBlocks.F  ?
                if (a.getOpcode() == Opcodes.ALOAD && ((VarInsnNode) a).var == 0
                        && i + 2 < real.size()
                        && real.get(i + 1).getOpcode() == Opcodes.GETSTATIC
                        && (real.get(i + 2).getOpcode() == Opcodes.IF_ACMPNE
                            || real.get(i + 2).getOpcode() == Opcodes.IF_ACMPEQ)) {
                    FieldInsnNode f = (FieldInsnNode) real.get(i + 1);
                    boolean positive = real.get(i + 2).getOpcode() == Opcodes.IF_ACMPNE;
                    pendingBlockField = positive ? f.owner + "." + f.name : null;
                    i += 2;
                    continue;
                }
                if (a.getOpcode() == Opcodes.GETSTATIC && i + 1 < real.size()
                        && real.get(i + 1).getOpcode() == Opcodes.IRETURN) {
                    FieldInsnNode f = (FieldInsnNode) a;
                    String decl = jar.declaringClassOfField(f.owner, f.name);
                    if (pendingBlockField != null) br.perBlockField.put(pendingBlockField, decl + "#" + f.name);
                    else if (br.defaultFieldName == null) { br.defaultFieldOwner = decl; br.defaultFieldName = f.name; }
                    pendingBlockField = null;
                    i++;
                    continue;
                }
                if (a instanceof InsnNode && a.getOpcode() >= Opcodes.ICONST_M1 && a.getOpcode() <= Opcodes.ICONST_5
                        && i + 1 < real.size() && real.get(i + 1).getOpcode() == Opcodes.IRETURN) {
                    if (pendingBlockField == null && br.defaultConstant == null && br.defaultFieldName == null)
                        br.defaultConstant = a.getOpcode() - Opcodes.ICONST_0;
                    pendingBlockField = null;
                    i++;
                    continue;
                }
                if (a.getOpcode() == Opcodes.BIPUSH && i + 1 < real.size()
                        && real.get(i + 1).getOpcode() == Opcodes.IRETURN) {
                    if (pendingBlockField == null && br.defaultConstant == null && br.defaultFieldName == null)
                        br.defaultConstant = ((org.objectweb.asm.tree.IntInsnNode) a).operand;
                    pendingBlockField = null;
                    i++;
                    continue;
                }
                if (a instanceof JumpInsnNode) pendingBlockField = null;
            }
            if (br.defaultFieldName == null && br.defaultConstant == null && br.perBlockField.isEmpty())
                br.note = "getRenderType() shape not modelled";
            blockRenderIds.put(cn.name, br);
        }
    }

    // ---------- ISBRH getRenderId ----------

    /** Resolve each registered ISBRH's {@code getRenderId()} to the static int field it returns. */
    public void resolveIsbrhRenderIds() {
        for (IsbrhBinding b : isbrhBindings) {
            if (b.handlerClass == null) continue;
            ClassNode cn = jar.cls(JarIndex.internal(b.handlerClass));
            if (cn == null) { b.unresolvedReason = "handler class not in jar"; continue; }
            MethodNode mn = null;
            for (ClassNode c : jar.superChain(cn.name)) {
                mn = HolderResolver.find(c, "getRenderId");
                if (mn != null) break;
            }
            if (mn == null) { b.unresolvedReason = "no getRenderId() found"; continue; }
            for (AbstractInsnNode i : mn.instructions.toArray()) {
                if (i.getOpcode() == Opcodes.GETSTATIC) {
                    FieldInsnNode f = (FieldInsnNode) i;
                    if ("I".equals(f.desc)) {
                        b.renderIdFieldOwner = jar.declaringClassOfField(f.owner, f.name);
                        b.renderIdFieldName = f.name;
                        break;
                    }
                }
            }
            if (b.renderIdFieldName == null) b.unresolvedReason = "getRenderId() returns no static int field";
        }
    }

    /** Set of every ISBRH-owned render-id field, "owner#name". */
    public Set<String> isbrhRenderIdKeys() {
        Set<String> s = new LinkedHashSet<>();
        for (IsbrhBinding b : isbrhBindings)
            if (b.renderIdFieldName != null) s.add(b.renderIdFieldOwner + "#" + b.renderIdFieldName);
        return s;
    }

    private boolean isItemRenderer(String internalName) {
        ClassNode cn = jar.cls(internalName);
        if (cn == null) return false;
        for (ClassNode c : jar.superChain(internalName)) {
            if (c.interfaces == null) continue;
            for (String i : c.interfaces)
                if (IITEM_RENDERER.equals(i)) return true;
        }
        return false;
    }

    private static Val at(List<Val> stack, int fromTop) {
        int i = stack.size() - 1 - fromTop;
        return i < 0 || i >= stack.size() ? null : stack.get(i);
    }

    private static String append(String a, String b) { return a == null ? b : a + "; " + b; }
}
