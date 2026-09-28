package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.StackData;
import dev.umb.legacy.legacyside.LegacyBridgeImpl;
import dev.umb.legacy.legacyside.UmbPlayer;
import dev.umb.legacy.legacyside.UmbWorld;

/**
 * the declined-use air fallback.
 * <p>Root cause : 1.7.10's {@code Minecraft.func_147121_ag} (rightClickMouse) runs {@code onItemRightClick} (via {@code PlayerControllerMP.func_78769_a}, server side the C08/-1/-1/-1/255 packet through {@code...
 */
class ItemUseAirFallbackTest {

    // ---------------------------------------------------------------- fixtures

    /** MCHeli shape: onItemUse declined (default body), onItemRightClick spends one. */
    static final class DecliningSpender extends Item {
        int rightClickCalls;

        @Override
        public boolean func_77648_a(ItemStack stack, EntityPlayer player, World world,
                int x, int y, int z, int side, float hitX, float hitY, float hitZ) {
            return false;
        }

        @Override
        public ItemStack func_77659_a(ItemStack stack, World world, EntityPlayer player) {
            rightClickCalls++;
            stack.field_77994_a--;
            return stack;
        }
    }

    /** Both halves decline: onItemRightClick returns the stack untouched (ray miss). */
    static final class DecliningNoOp extends Item {
        int rightClickCalls;

        @Override
        public boolean func_77648_a(ItemStack stack, EntityPlayer player, World world,
                int x, int y, int z, int side, float hitX, float hitY, float hitZ) {
            return false;
        }

        @Override
        public ItemStack func_77659_a(ItemStack stack, World world, EntityPlayer player) {
            rightClickCalls++;
            return stack;
        }
    }

    /** Shape-changing right click (empty bucket -&gt; full bucket): a DIFFERENT stack. */
    static final class ShapeChanger extends Item {
        final Item other;

        ShapeChanger(Item other) {
            this.other = other;
        }

        @Override
        public boolean func_77648_a(ItemStack stack, EntityPlayer player, World world,
                int x, int y, int z, int side, float hitX, float hitY, float hitZ) {
            return false;
        }

        @Override
        public ItemStack func_77659_a(ItemStack stack, World world, EntityPlayer player) {
            return new ItemStack(other, 1, 0);
        }
    }

    /** Right click throws: the whole dispatch must decline, never propagate. */
    static final class ThrowingRightClick extends Item {
        @Override
        public boolean func_77648_a(ItemStack stack, EntityPlayer player, World world,
                int x, int y, int z, int side, float hitX, float hitY, float hitZ) {
            return false;
        }

        @Override
        public ItemStack func_77659_a(ItemStack stack, World world, EntityPlayer player) {
            throw new RuntimeException("synthetic right-click failure");
        }
    }

    /** Bow shape: right click starts the item-in-use lifecycle on the facade. */
    static final class StartingBow extends Item {
        @Override
        public boolean func_77648_a(ItemStack stack, EntityPlayer player, World world,
                int x, int y, int z, int side, float hitX, float hitY, float hitZ) {
            return false;
        }

        @Override
        public ItemStack func_77659_a(ItemStack stack, World world, EntityPlayer player) {
            player.func_71008_a(stack, 32);
            return stack;
        }
    }

    static final class FakeHostWorld implements HostWorld {
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
            return "minecraft:air";
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
    }

    static final class FakeHostPlayer implements HostPlayer {
        final StackData[] inventory = new StackData[36];
        int startUsingItemCalls;

        FakeHostPlayer() {
            java.util.Arrays.fill(inventory, StackData.EMPTY);
        }

        @Override
        public String getName() {
            return "FallbackProbe";
        }

        @Override
        public boolean isSneaking() {
            return false;
        }

        @Override
        public double getX() {
            return 0.5D;
        }

        @Override
        public double getY() {
            return 67.0D;
        }

        @Override
        public double getZ() {
            return 0.5D;
        }

        @Override
        public float getYaw() {
            return 0.0F;
        }

        @Override
        public float getPitch() {
            return 45.0F;
        }

        @Override
        public StackData getHeldItem() {
            return StackData.EMPTY;
        }

        @Override
        public void setHeldItem(StackData s) {
        }

        @Override
        public void startUsingItem() {
            startUsingItemCalls++;
        }

        @Override
        public void sendMessage(String text) {
        }

        @Override
        public StackData getInventorySlot(int i) {
            return inventory[i];
        }

        @Override
        public void setInventorySlot(int i, StackData s) {
            inventory[i] = s;
        }

        @Override
        public int getInventorySize() {
            return inventory.length;
        }

        @Override
        public double getMotionX() {
            return 0.0D;
        }

        @Override
        public double getMotionY() {
            return 0.0D;
        }

        @Override
        public double getMotionZ() {
            return 0.0D;
        }

        @Override
        public void setMotion(double mx, double my, double mz) {
        }

        @Override
        public void hurt(String legacyDamageType, float amount) {
        }
    }

    private static UmbWorld world() {
        return UmbWorld.create(new FakeHostWorld(), 0);
    }

    // ---------------------------------------------------------------- criterion

    @Test
    void airUseConsumedMatchesThe1710SendUseItemCriterion() {
        Item item = new DecliningNoOp();
        ItemStack stack = new ItemStack(item, 3, 0);
        // Untouched same stack: not consumed (ray miss).
        assertFalse(LegacyBridgeImpl.airUseConsumed(stack, 3, stack));
        // Same stack, count changed: consumed (MCHeli spawn decrement).
        stack.field_77994_a = 2;
        assertTrue(LegacyBridgeImpl.airUseConsumed(stack, 3, stack));
        // Different object: consumed (bucket shape change), even at equal count.
        ItemStack other = new ItemStack(item, 3, 0);
        assertTrue(LegacyBridgeImpl.airUseConsumed(stack, 2, other));
        // Null return differs from the passed stack: consumed.
        assertTrue(LegacyBridgeImpl.airUseConsumed(stack, 2, null));
    }

    // ---------------------------------------------------------------- fallback

    @Test
    void declinedUseRunsRightClickOnTheSameStackAndConsumes() {
        UmbWorld world = world();
        FakeHostPlayer host = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, host);
        DecliningSpender item = new DecliningSpender();
        ItemStack stack = new ItemStack(item, 3, 0);

        ItemUseResult result = LegacyBridgeImpl.runDeclinedAirFallback(
                world, item, stack, player, host, "umbtest:fallback_spender");

        assertEquals(1, item.rightClickCalls, "declined onItemUse must reach onItemRightClick");
        assertTrue(result.handled, "spent stack (count 3->2) must map to handled");
        assertEquals(2, result.stack.count, "the SAME mutated stack must cross back");
        assertEquals(0, host.startUsingItemCalls, "no use-state was requested, none starts");
    }

    @Test
    void declinedBothHalvesStaysDeclined() {
        UmbWorld world = world();
        FakeHostPlayer host = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, host);
        DecliningNoOp item = new DecliningNoOp();
        ItemStack stack = new ItemStack(item, 1, 0);

        ItemUseResult result = LegacyBridgeImpl.runDeclinedAirFallback(
                world, item, stack, player, host, "umbtest:fallback_noop");

        assertEquals(1, item.rightClickCalls, "the air half must still run");
        assertFalse(result.handled, "untouched stack must map to declined (ray miss)");
        assertEquals(1, result.stack.count);
    }

    @Test
    void shapeChangingRightClickCountsAsHandled() {
        UmbWorld world = world();
        FakeHostPlayer host = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, host);
        Item other = new DecliningNoOp();
        ShapeChanger item = new ShapeChanger(other);
        ItemStack stack = new ItemStack(item, 1, 0);

        ItemUseResult result = LegacyBridgeImpl.runDeclinedAirFallback(
                world, item, stack, player, host, "umbtest:fallback_shape");

        assertTrue(result.handled, "a different returned stack must map to handled");
        assertEquals(1, result.stack.count);
    }

    @Test
    void throwingRightClickDeclinesInsteadOfPropagating() {
        UmbWorld world = world();
        FakeHostPlayer host = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, host);
        ThrowingRightClick item = new ThrowingRightClick();
        ItemStack stack = new ItemStack(item, 1, 0);

        ItemUseResult result = LegacyBridgeImpl.runDeclinedAirFallback(
                world, item, stack, player, host, "umbtest:fallback_throw");

        assertNull(result, "a throwing air half must decline the whole dispatch");
    }

    @Test
    void rightClickUseStateReachesTheHost() {
        UmbWorld world = world();
        FakeHostPlayer host = new FakeHostPlayer();
        UmbPlayer player = UmbPlayer.create(world, host);
        StartingBow item = new StartingBow();
        ItemStack stack = new ItemStack(item, 1, 0);

        ItemUseResult result = LegacyBridgeImpl.runDeclinedAirFallback(
                world, item, stack, player, host, "umbtest:fallback_bow");

        assertEquals(1, host.startUsingItemCalls,
                "setItemInUse from the air half must start the host use lifecycle");
        assertFalse(result.handled, "an unchanged bow stack still maps to declined");
    }
}
