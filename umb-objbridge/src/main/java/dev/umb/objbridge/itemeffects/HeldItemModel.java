package dev.umb.objbridge.itemeffects;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umb.objbridge.item.ObjItemModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;

import java.util.List;
import java.util.Optional;

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
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        if (!HeldItemRuntime.hasHeldData(id)) {
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

    /** Client-item type {@code umb:held}; the pack generator may select it explicitly. */
    public record Unbaked(String model, Identifier texture, Optional<Float> fit, List<String> groups)
            implements ItemModel.Unbaked {
        public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("model").forGetter(Unbaked::model),
                Identifier.CODEC.fieldOf("texture").forGetter(Unbaked::texture),
                Codec.FLOAT.optionalFieldOf("fit").forGetter(Unbaked::fit),
                Codec.STRING.listOf().optionalFieldOf("groups", List.of()).forGetter(Unbaked::groups)
        ).apply(i, Unbaked::new));
        @Override public MapCodec<? extends ItemModel.Unbaked> type() { return MAP_CODEC; }
        @Override public void resolveDependencies(ResolvableModel.Resolver resolver) { }
        @Override public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            return wrap(new ObjItemModel.Unbaked(model, texture, fit, groups).bake(context, transformation));
        }
    }
}
