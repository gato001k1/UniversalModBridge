package dev.umb.objbridge.block;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.geometry.QuadCollection;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * An OBJ mesh spliced in as a block-state model.
 *
 * <p>There is no data-driven registry for block geometry in 26.2 (verified: {@code
 * BlockStateModel$Unbaked.CODEC} is a fixed composition of {@code SingleVariant}/{@code
 * WeightedVariants}, no {@code LateBoundIdMapper} anywhere in the block model packages), so this is
 * installed as Java straight into the live {@code BlockStateModelSet.modelByState} IdentityHashMap by
 * {@link dev.umb.objbridge.ObjBridge#onModelsApplied}.
 *
 * <p>Every quad goes in UNCULLED. Mesh triangles do not sit on block faces, so culling any of them
 * against a neighbour would punch holes in the middle of the model. {@code getQuads(null)} is the
 * unculled bucket - verified from {@code QuadCollection.getQuads}' tableswitch (case {@code -1} ->
 * field {@code unculled}).
 */
public final class ObjBlockStateModel implements BlockStateModel, BlockStateModelPart {

    private final QuadCollection quads;
    private final Material.Baked particle;

    public ObjBlockStateModel(List<BakedQuad> quads, TextureAtlasSprite sprite) {
        QuadCollection.Builder b = new QuadCollection.Builder();
        for (BakedQuad q : quads) b.addUnculledFace(q);
        this.quads = b.build();
        this.particle = new Material.Baked(sprite, false);
    }

    // -------------------------------------------------------------- BlockStateModel

    @Override
    public void collectParts(RandomSource random, List<BlockStateModelPart> parts) {
        parts.add(this);
    }

    @Override
    public Material.Baked particleMaterial() {
        return particle;
    }

    @Override
    public int materialFlags() {
        return quads.materialFlags();
    }

    // ---------------------------------------------------------- BlockStateModelPart

    @Override
    public List<BakedQuad> getQuads(Direction direction) {
        return quads.getQuads(direction);
    }

    /**
     * Ambient occlusion off. AO shades a quad from the four block corners around its
     * {@code Direction}, which is meaningless for a triangle floating inside the block and shows up
     * as harsh per-triangle banding.
     */
    @Override
    public boolean useAmbientOcclusion() {
        return false;
    }

    public int quadCount() {
        return quads.getAll().size();
    }
}
