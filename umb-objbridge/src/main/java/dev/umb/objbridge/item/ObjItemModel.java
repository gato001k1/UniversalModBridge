package dev.umb.objbridge.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.umb.objbridge.ObjBridge;
import dev.umb.objbridge.ObjLog;
import dev.umb.objbridge.itemeffects.HeldItemModel;
import dev.umb.objbridge.bake.Fit;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.CuboidItemModelWrapper;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ResolvableModel;
import net.minecraft.client.resources.model.cuboid.ItemTransforms;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4fc;
import org.joml.Vector3fc;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * A baked OBJ mesh as an {@code ItemModel}. Registered under the client-item type {@code umb:obj} in
 * {@code ItemModels.ID_MAPPER} by {@link ObjBridge#registerItemModelType()}.
 *
 * <p>{@link #update} mirrors {@code CuboidItemModelWrapper.update} exactly (javap-verified): identity
 * element, one new layer, extents, local transform, then the three things
 * {@code ModelRenderProperties.applyToLayer} sets, then the quads.
 */
public final class ObjItemModel implements ItemModel {

    private final List<BakedQuad> quads;
    private final Material.Baked particle;
    private final ItemTransforms transforms;
    private final Matrix4fc transformation;
    private final Supplier<Vector3fc[]> extents;

    ObjItemModel(List<BakedQuad> quads, Material.Baked particle, ItemTransforms transforms,
                 Matrix4fc transformation) {
        this.quads = quads;
        this.particle = particle;
        this.transforms = transforms;
        this.transformation = transformation;
        Vector3fc[] computed = CuboidItemModelWrapper.computeExtents(quads);
        this.extents = () -> computed;
    }

    public List<BakedQuad> quads() { return quads; }

    @Override
    public void update(ItemStackRenderState state, ItemStack stack, ItemModelResolver resolver,
                       ItemDisplayContext displayContext, ClientLevel level, ItemOwner owner, int seed) {
        // The generated base pack can leave a vanilla cuboid layer in the same render state when
        // this OBJ definition is resolved as an overlay.  An OBJ-backed item is the replacement
        // model, not an additional layer; clear that stale layer before installing ours.
        state.clear();
        state.appendModelIdentityElement(this);
        ItemStackRenderState.LayerRenderState layer = state.newLayer();
        layer.setExtents(extents);
        layer.setLocalTransform(transformation);
        layer.setUsesBlockLight(true);
        layer.setParticleMaterial(particle);
        layer.setItemTransform(transforms.getTransform(displayContext));
        layer.prepareQuadList().addAll(quads);
    }

    // ------------------------------------------------------------------ unbaked

    /**
     * The {@code "type": "umb:obj"} client-item model.
     *
     * <pre>
     * { "model": { "type": "umb:obj",
     *              "model":   "hbm:models/weapons/minigun.obj",
     *              "texture": "hbm:models/weapons/minigun",
     *              "fit": 1.0,
     *              "groups": ["Gun","Grip"] } }
     * </pre>
     *
     * <p>{@code model} is a plain STRING, not an {@code Identifier}: 269 of HBM's 507 OBJ file names
     * contain uppercase letters ({@code models/BombGeneric.obj}) which {@code Identifier.isValidPath}
     * rejects. The OBJ is read straight off the filesystem from the {@code assets=} agent argument,
     * never through the {@code ResourceManager} - {@code ItemModel$BakingContext} carries no
     * {@code ResourceManager} at all (javap-verified), so there is no other option.
     *
     * <p>{@code texture} IS an {@code Identifier}: it is a sprite name on the block atlas, and the
     * overlay pack copies every model texture to a lowercased path so the name is always valid.
     */
    public record Unbaked(String model, Identifier texture, Optional<Float> fit, List<String> groups)
            implements ItemModel.Unbaked {

        public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("model").forGetter(Unbaked::model),
                Identifier.CODEC.fieldOf("texture").forGetter(Unbaked::texture),
                Codec.FLOAT.optionalFieldOf("fit").forGetter(Unbaked::fit),
                Codec.STRING.listOf().optionalFieldOf("groups", List.of()).forGetter(Unbaked::groups)
        ).apply(i, Unbaked::new));

        @Override
        public MapCodec<? extends ItemModel.Unbaked> type() {
            return MAP_CODEC;
        }

        /** Nothing to resolve: this model references no vanilla model JSON. */
        @Override
        public void resolveDependencies(ResolvableModel.Resolver resolver) { }

        @Override
        public ItemModel bake(ItemModel.BakingContext context, Matrix4fc transformation) {
            // NOT context.sprites(): during the model bake that SpriteGetter is the AtlasManager,
            // whose spriteLookup is only rebuilt later (in its own reload apply), so every lookup
            // fell through to TextureAtlas.missingSprite() and threw
            //   NullPointerException: Atlas not initialized
            // for all 545 defs. Vanilla's own CuboidItemModelWrapper$Unbaked.bake never touches
            // context.sprites() either - it goes through blockModelBaker().materials(), which
            // ModelManager.loadModels hands a CombinedBlockItemMaterialBaker(blockAtlas, itemAtlas)
            // resolving against the freshly stitched SpriteLoader$Preparations of BOTH atlases.
            // MaterialBaker.get returns the missing-texture material instead of throwing when a
            // sprite is absent, and records the miss for vanilla's own logMissingTextures().
            Material.Baked baked = context.blockModelBaker().materials()
                    .get(new Material(texture), () -> "umb:obj " + model);
            TextureAtlasSprite sprite = baked.sprite();
            String spriteId = texture.getNamespace() + ":" + texture.getPath();
            // Block-item-in-slot rule (laneInv-progress.md): a block's own held/inventory geometry is
            // shaped by its in-world (WORLD-path) transform when one resolves, not by the raw OBJ's own
            // (generally unrelated) aspect ratio - see ObjBridge.itemGeometryFit. Every other item keeps
            // the original plain auto-fit (unchanged from before this lane).
            Fit f = ObjBridge.itemGeometryFit(model, spriteId, fit.orElse(1.0f));
            List<BakedQuad> quads;
            try {
                quads = ObjBridge.quads(model, sprite, f, groups);
            } catch (Throwable t) {
                ObjLog.error("bake item obj " + model, t);
                quads = List.of();
            }
            if (quads.isEmpty()) {
                ObjLog.line("ITEM-BAKE-EMPTY model=" + model + " texture=" + texture);
            }
            // The wrapper delegates GUI/ground/fixed unchanged and activates the held sidecar only
            // for first-/third-person contexts. This keeps the existing transform-lane results.
            return HeldItemModel.wrap(new ObjItemModel(quads, baked,
                    ObjTransforms.fitGuiToSlot(ObjTransforms.forItem(model, spriteId), quads), transformation));
        }
    }
}
