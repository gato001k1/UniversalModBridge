package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;

import org.junit.jupiter.api.Test;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.StackData;
import dev.umb.legacy.legacyside.UmbWorld;

/**
 * legacy block drops reach the host.
 * <p>Vanilla 1.7.10 {@code World.destroyBlock} refuses air, fires the 2001 aux event, runs {@code Block.getDrops} + the HarvestDrops event when the drop flag is set, and removes the block.
 */
class BlockDropBridgeTest {

    static final class TestDropItem extends Item {
    }

    /** Synthetic legacy block whose drops are fully scripted: no registry needed. */
    static final class TestDropBlock extends Block {
        final java.util.List<ItemStack> scripted;
        boolean throwOnDrops;

        TestDropBlock(java.util.List<ItemStack> scripted) {
            super(Material.field_151576_e);
            this.scripted = scripted;
        }

        @Override
        public ArrayList<ItemStack> getDrops(World world, int x, int y, int z, int meta, int fortune) {
            if (throwOnDrops) {
                throw new RuntimeException("synthetic getDrops failure");
            }
            return new ArrayList<ItemStack>(scripted);
        }
    }

    static final class FakeHostWorld implements HostWorld {
        final java.util.List<double[]> removed = new ArrayList<double[]>();
        final java.util.List<StackData> dropped = new ArrayList<StackData>();
        final java.util.List<double[]> dropPos = new ArrayList<double[]>();

        @Override
        public boolean isRemote() {
            return false;
        }

        @Override
        public long getTotalTime() {
            return 0L;
        }

        @Override
        public String getBlockId(int x, int y, int z) {
            return "umbtest:unmapped_drop_block";
        }

        @Override
        public int getMeta(int x, int y, int z) {
            return 0;
        }

        @Override
        public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) {
        }

        @Override
        public void setMeta(int x, int y, int z, int meta, int flags) {
        }

        @Override
        public void removeBlock(int x, int y, int z) {
            removed.add(new double[] {x, y, z});
        }

        @Override
        public void markBlockDirty(int x, int y, int z) {
        }

        @Override
        public void scheduleTick(int x, int y, int z, int delay) {
        }

        @Override
        public long randomSeed() {
            return 1L;
        }

        @Override
        public void log(String msg) {
        }

        @Override
        public void dropItem(double x, double y, double z, StackData stack) {
            dropped.add(stack);
            dropPos.add(new double[] {x, y, z});
        }
    }

    @Test
    void dropsCrossToHostAtBlockCenter() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);
        TestDropBlock block = new TestDropBlock(
                java.util.Collections.singletonList(new ItemStack(new TestDropItem(), 2, 0)));

        UmbWorld.dropBlockDrops(world, block, 0, 1, 2, 3);

        assertEquals(1, host.dropped.size(), "one scripted stack must cross exactly once");
        assertEquals(2, host.dropped.get(0).count);
        assertEquals(1.5D, host.dropPos.get(0)[0], 0.0001D);
        assertEquals(2.5D, host.dropPos.get(0)[1], 0.0001D);
        assertEquals(3.5D, host.dropPos.get(0)[2], 0.0001D);
    }

    @Test
    void emptyAndNullDropsAreSkipped() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);
        java.util.List<ItemStack> scripted = new ArrayList<ItemStack>();
        scripted.add(null);
        scripted.add(new ItemStack(new TestDropItem(), 0, 0));
        scripted.add(new ItemStack(new TestDropItem(), 1, 0));
        TestDropBlock block = new TestDropBlock(scripted);

        UmbWorld.dropBlockDrops(world, block, 0, 4, 5, 6);

        assertEquals(1, host.dropped.size(), "only the live stack must cross");
        assertEquals(1, host.dropped.get(0).count);
    }

    @Test
    void throwingGetDropsDropsNothingAndNeverPropagates() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);
        TestDropBlock block = new TestDropBlock(
                java.util.Collections.singletonList(new ItemStack(new TestDropItem(), 1, 0)));
        block.throwOnDrops = true;

        UmbWorld.dropBlockDrops(world, block, 0, 7, 8, 9);

        assertTrue(host.dropped.isEmpty(), "a throwing block must drop nothing, not poison the tick");
    }

    @Test
    void destroyBlockOnUnmappedBlockRemovesWithoutDrops() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);

        // Headless the GameData registry is empty, so every id is unmapped: the block
        // cannot be resolved for drops, but removal must still happen (legacy behaviour
        // preserved) and report success.
        boolean result = world.func_147480_a(1, 2, 3, true);

        assertTrue(result);
        assertEquals(1, host.removed.size());
        assertTrue(host.dropped.isEmpty(), "no block, no drops to compute");
    }

    @Test
    void spawnedEntityItemCrossesAsHostDropWithoutATwin() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);
        net.minecraft.entity.item.EntityItem dropped = new net.minecraft.entity.item.EntityItem(
                world, 10.5D, 64.0D, -3.5D, new ItemStack(new TestDropItem(), 3, 0));

        // Vanilla dropBlockAsItem (explosions, dispensers, every mod drop path) spawns
        // EntityItems through spawnEntityInWorld; the host has no EntityItem twin, so
        // the drop must cross as a host item instead of ticking invisibly forever.
        boolean result = world.func_72838_d(dropped);

        assertTrue(result, "vanilla treats the drop as spawned either way");
        assertEquals(1, host.dropped.size(), "the EntityItem must cross exactly once");
        assertEquals(3, host.dropped.get(0).count);
        assertEquals(10.5D, host.dropPos.get(0)[0], 0.0001D);
        assertEquals(64.0D, host.dropPos.get(0)[1], 0.0001D);
        assertEquals(-3.5D, host.dropPos.get(0)[2], 0.0001D);
    }

    @Test
    void emptyEntityItemSpawnsQuietly() {
        FakeHostWorld host = new FakeHostWorld();
        UmbWorld world = UmbWorld.create(host, 0);
        // A depleted stack (vanilla forbids a null one — the EntityItem ctor itself
        // dereferences it) converts to nothing but still reports success.
        net.minecraft.entity.item.EntityItem dropped = new net.minecraft.entity.item.EntityItem(
                world, 0.0D, 64.0D, 0.0D, new ItemStack(new TestDropItem(), 0, 0));

        assertTrue(world.func_72838_d(dropped));
        assertTrue(host.dropped.isEmpty(), "a stackless EntityItem converts to nothing");
    }
}
