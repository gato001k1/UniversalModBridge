package dev.umb.objbridge.itemeffects;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Dynamic legacy-renderer wrapper for hand, GUI, ground, and fixed item contexts. */
public final class HeldItemModel implements ItemModel {
    private final ItemModel base;
    /** Nullable: only known when {@code base} is an {@link dev.umb.objbridge.item.ObjItemModel}
     *  (see {@link #wrap(ItemModel, ItemTransforms, Supplier, Matrix4fc)}'s own javadoc for why). */
    private final ItemTransforms transforms;
    private final Supplier<Vector3fc[]> extents;
    private final Matrix4fc localTransform;

    private HeldItemModel(ItemModel base, ItemTransforms transforms, Supplier<Vector3fc[]> extents,
                          Matrix4fc localTransform) {
        this.base = base;
        this.transforms = transforms;
        this.extents = extents;
        this.localTransform = localTransform;
    }

    public static ItemModel wrap(ItemModel base) { return new HeldItemModel(base, null, null, null); }

    /**
     * Live bug (HBM Uzi playtest, round 4): {@code update}'s live-capture branch never applied
     * ANY per-context {@code ItemTransform} - unlike {@code base.update()} (see
     * {@code CuboidItemModelWrapper.update}), which always does. A live-captured mesh is raw HBM
     * model-space geometry (the exact size/position the legacy renderer itself draws it at, e.g.
     * HBM's own {@code setupInv}/{@code setupFirstPerson} scale) - GROUND rendering tolerates that
     * fine (a world-space entity has no tight "slot" to fit), but GUI does not: a model whose
     * legacy renderer needed extra room even after auto-fit ({@code "oversized_in_gui": true} -
     * gun_uzi's own flag, from {@code ObjPackGen}'s report) draws far outside the 16x16 icon's
     * viewport with no per-context scale-down applied at all, landing completely off the visible
     * area - "handled=true", real non-empty draws (confirmed live: 5 draws/3560 vertices), nothing
     * visible, no error anywhere. {@code ObjItemModel.Unbaked.bake} passes its own already-computed
     * {@code transforms} (per-context GUI/hand/ground scale, {@code ObjTransforms.fitGuiToSlot}) and
     * {@code transformation} (baking-time local transform) through here so the live-capture branch
     * can apply the SAME correction {@code base.update()} would have. Only {@code ObjItemModel}
     * bakes call this overload; the generic {@code Unbaked} codec path below (wrapping an arbitrary
     * nested model with no OBJ geometry to derive a transform from) keeps calling the 1-arg
     * {@link #wrap(ItemModel)} unchanged - it never had a meaningful static transform to reuse
     * either way, so there is nothing to regress there.
     */
    public static ItemModel wrap(ItemModel base, ItemTransforms transforms, Supplier<Vector3fc[]> extents,
                                 Matrix4fc localTransform) {
        return new HeldItemModel(base, transforms, extents, localTransform);
    }

    private static final Set<String> LOGGED_ROUTE = ConcurrentHashMap.newKeySet();

    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver,
                       ItemDisplayContext context, ClientLevel level, ItemOwner owner, int seed) {
        if (!HeldItemRuntime.isHeldContext(context)) {
            logRouteOnce(stack, context, "not-held-context", base.getClass(), null);
            base.update(state, stack, resolver, context, level, owner, seed);
            return;
        }
        // checked HeldItemRuntime.hasHeldData(id) - presence in a build-time static-extraction
        // sidecar (held-render.md's P4 census) - which only covers items whose renderer had SOME
        // statically recoverable OBJ/transform data. That is a real but much narrower set than
        // "this item has a registered legacy IItemRenderer": the correct, universal, live check.
        // A static sidecar can never be complete (a build-time census cannot see every renderer
        // shape), and gating on it silently skipped this entire capture path for every item it
        // missed, no matter how correctly the rest of the WIP worked.
        if (!LegacyItemCaptureClient.hasCustomRenderer(stack, context)) {
            logRouteOnce(stack, context, "no-custom-renderer", base.getClass(), null);
            base.update(state, stack, resolver, context, level, owner, seed);
            return;
        }

        // The legacy renderer is the complete model for these contexts.  Clearing the OBJ
        // layer is important: otherwise the static replacement and the captured Forge draw
        // would be submitted together.  The captured matrices carry the legacy item transform.
        state.clear();
        state.appendModelIdentityElement(this);
        ItemStackRenderState.LayerRenderState layer = state.newLayer();
        layer.setUsesBlockLight(true);
        // See this class's own wrap(ItemModel, ItemTransforms, Supplier, Matrix4fc) javadoc:
        // without these, GUI-context geometry that needed extra room even after auto-fit draws
        // entirely outside the icon viewport - present, but never visible.
        ItemTransform applied = transforms == null ? null : transforms.getTransform(context);
        if (applied != null) layer.setItemTransform(applied);
        if (extents != null) layer.setExtents(extents);
        if (localTransform != null) layer.setLocalTransform(localTransform);
        logRouteOnce(stack, context, "live-capture", HeldItemSpecialRenderer.class, applied);
        layer.setupSpecialModel(new HeldItemSpecialRenderer(context, 0.0f), stack);
        state.setAnimated();
    }

    /**
     * ItemModel class renders it per context and the transform applied" - since round 4's fix
     * (an {@code ItemTransform} now genuinely being computed and handed to the layer) still did
     * not make the Uzi visible, and nothing in the existing logs said whether this code path is
     * even reached at all, let alone what transform came out of it. One line per item+context
     * (not rate-limited into silence - this is exactly the kind of one-shot fact a live check
     * needs, not a recurring diagnostic).
     */
    private static void logRouteOnce(ItemStack stack, ItemDisplayContext context, String route,
                                     Class<?> rendererClass, ItemTransform applied) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        String key = id + "|" + context;
        if (!LOGGED_ROUTE.add(key)) return;
        System.out.println("[UMB-OBJBRIDGE] held-item route id=" + id + " context=" + context
                + " route=" + route + " renderer=" + rendererClass.getName()
                + " itemTransform=" + (applied == null ? "none" : applied));
    }

    /**
     * Client-item type {@code umb:held}: wraps an ARBITRARY nested model declaration - not
     * specifically an OBJ one - checked against a live legacy {@code IItemRenderer} lookup at
     * each held/GUI/ground/fixed render (see {@link #update}).
     *
     * <pre>
     * { "model": { "type": "umb:held",
     *              "base": { "type": "minecraft:model", "model": "mcheli:item/some_weapon" } } }
     * </pre>
     *
     * {@code base} decodes through {@code ItemModels.CODEC} - vanilla's own top-level dispatch
     * {@code ItemModels} class) - so this never hardcodes or duplicates vanilla's own model
     * baking for any particular {@code "type"}; it can wrap {@code "minecraft:model"} (the
     * common case: an item whose renderer has no recoverable OBJ geometry, so the pack generator
     * has nothing better to reference than the same flat model the base pack already emitted)
     * exactly as well as any other registered type.
     *
     * <p>This is a SEPARATE mechanism from {@code ObjItemModel.Unbaked.bake}, which wraps itself
     * in {@link #wrap} programmatically (a direct Java call, never through this codec) for items
     * that DO have recoverable OBJ geometry - both converge on the same {@link #update}, so the
     * live-renderer gate below applies identically either way.</p>
     */
    public record Unbaked(ItemModel.Unbaked base) implements ItemModel.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ItemModels.CODEC.fieldOf("base").forGetter(Unbaked::base)
        ).apply(i, Unbaked::new));
        @Override public MapCodec<? extends ItemModel.Unbaked> type() { return MAP_CODEC; }
        @Override public void resolveDependencies(ResolvableModel.Resolver resolver) {
            base.resolveDependencies(resolver);
        }
        @Override public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            return wrap(base.bake(context, transformation));
        }
    }
}
