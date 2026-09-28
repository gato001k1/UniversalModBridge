package dev.umb.objbridge.block;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;

import java.util.List;

/**
 * Empty visual model used for legacy getRenderType()==-1/TESR rows. Shapes/collision remain native.
 * It draws nothing but MUST still carry a particle material: vanilla reads it unguarded for the
 * in-wall screen overlay (ScreenEffectRenderer), breaking/landing particles and block-hit effects.
 * A null here crashed the client the moment the camera entered a door cell (2026-09-24).
 */
public final class InvisibleBlockStateModel implements BlockStateModel, BlockStateModelPart {
    private final Material.Baked particle;
    private InvisibleBlockStateModel(Material.Baked particle) { this.particle = particle; }
    /** @param particle the state's own particle (from the model being replaced); never null. */
    public static InvisibleBlockStateModel of(Material.Baked particle) {
        if (particle == null) throw new IllegalArgumentException("invisible legacy model needs a particle material");
        return new InvisibleBlockStateModel(particle);
    }
    @Override public void collectParts(RandomSource random, List<BlockStateModelPart> parts) { parts.add(this); }
    @Override public Material.Baked particleMaterial() { return particle; }
    @Override public int materialFlags() { return 0; }
    @Override public List<BakedQuad> getQuads(Direction direction) { return List.of(); }
    @Override public boolean useAmbientOcclusion() { return false; }
}
