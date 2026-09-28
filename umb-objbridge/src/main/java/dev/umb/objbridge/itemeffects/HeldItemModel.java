package dev.umb.objbridge.itemeffects;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemModels;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;

/** Dynamic legacy-renderer wrapper for hand, GUI, ground, and fixed item contexts. */
public final class HeldItemModel implements ItemModel {
    private final ItemModel base;
    private HeldItemModel(ItemModel base) { this.base = base; }
    public static ItemModel wrap(ItemModel base) { return new HeldItemModel(base); }

    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver,
                       ItemDisplayContext context, ClientLevel level, ItemOwner owner, int seed) {
        if (!HeldItemRuntime.isHeldContext(context)) {
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
        layer.setupSpecialModel(new HeldItemSpecialRenderer(context, 0.0f), stack);
        state.setAnimated();
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
