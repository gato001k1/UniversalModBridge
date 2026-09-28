package dev.umb.hostagent.content.fluid;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import java.util.EnumMap;
import java.util.Map;

/**
 * One member of a source/flowing pair. Split into {@link Source}/{@link Flowing} subclasses exactly
 * like vanilla WaterFluid (javap: WaterFluid$Flowing overrides createFluidStateDefinition to add
 * FlowingFluid.LEVEL; the source never has LEVEL). createFluidStateDefinition runs inside Fluid's
 * constructor before any field is assigned, so a single class cannot branch on a field there - the
 * previous single-class version set LEVEL on a definition that lacked it, threw for every fluid,
 * and left constructed-but-unregistered intrusive holders that made BuiltInRegistries.freeze()
 * crash the whole client ("Some intrusive holders were not registered").
 */
abstract class GeneratedFluid extends FlowingFluid {
    static GeneratedFluid create(FluidEntry entry, boolean source) {
        return source ? new Source(entry) : new Flowing(entry);
    }

    static final class Source extends GeneratedFluid {
        Source(FluidEntry entry) { super(entry, true); }
    }

    static final class Flowing extends GeneratedFluid {
        Flowing(FluidEntry entry) {
            super(entry, false);
            registerDefaultState(defaultFluidState().setValue(LEVEL, 7));
        }
        @Override
        protected void createFluidStateDefinition(
                net.minecraft.world.level.block.state.StateDefinition.Builder<Fluid, FluidState> builder) {
            super.createFluidStateDefinition(builder);
            builder.add(LEVEL);
        }
    }

    private final boolean source;
    private final FluidEntry entry;
    private GeneratedFluid pair;
    private Block legacyBlock;
    private Item bucket;

    private GeneratedFluid(FluidEntry entry, boolean source) {
        this.entry = entry;
        this.source = source;
    }
    void pair(GeneratedFluid pair) { this.pair = pair; }
    void legacyBlock(Block block) { this.legacyBlock = block; }
    void bucket(Item bucket) { this.bucket = bucket; }
    FluidEntry entry() { return entry; }
    boolean sourceMember() { return source; }

    @Override public Item getBucket() { return bucket; }
    @Override public Fluid getFlowing() { return pair; }
    @Override public Fluid getSource() { return source ? this : pair; }
    @Override protected boolean canConvertToSource(ServerLevel level) { return false; }
    @Override protected void beforeDestroyingBlock(LevelAccessor level, BlockPos pos, BlockState state) {}
    @Override protected int getSlopeFindDistance(LevelReader level) { return 4; }
    @Override protected int getDropOff(LevelReader level) { return Math.max(1, Math.min(8, entry.viscosity / 1000)); }
    @Override protected Map<Direction, FluidState> getSpread(ServerLevel level, BlockPos pos, BlockState state) {
        Map<Direction, FluidState> spread = super.getSpread(level, pos, state);
        if (!FluidFlowPolicy.isGaseous(entry)) return spread;
        // FlowingFluid's verified 26.2 spread contract returns destination directions as map keys.
        // Preserve its horizontal obstruction/source-distance checks and only invert the vertical
        // destination; this is materially safer than duplicating its private spread algorithm.
        Map<Direction, FluidState> upward = new EnumMap<>(Direction.class);
        for (Map.Entry<Direction, FluidState> e : spread.entrySet()) {
            upward.put(e.getKey() == Direction.DOWN ? Direction.UP : e.getKey(), e.getValue());
        }
        return upward;
    }
    @Override public int getTickDelay(net.minecraft.world.level.LevelReader level) {
        return Math.max(1, Math.min(40, entry.viscosity / 200));
    }
    @Override protected boolean canBeReplacedWith(FluidState state, BlockGetter level, BlockPos pos,
                                                   Fluid fluid, Direction direction) {
        return false;
    }
    @Override protected float getExplosionResistance() { return 100.0F; }
    @Override protected BlockState createLegacyBlock(FluidState state) {
        return legacyBlock.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL,
                getLegacyLevel(state));
    }
    @Override public boolean isSource(FluidState state) { return source; }
    @Override public int getAmount(FluidState state) { return source ? 8 : state.getValue(LEVEL); }
}
