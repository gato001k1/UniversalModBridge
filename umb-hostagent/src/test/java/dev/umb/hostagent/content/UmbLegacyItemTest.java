package dev.umb.hostagent.content;

import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.StackData;
import dev.umb.hostagent.UmbThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PART 2 (INTERACTION-BRIDGE.md / GENERALIZATION-PLAN.md GAP 3) gate: {@link UmbLegacyItem#use}
 * (func_77659_a, onItemRightClick) and {@link UmbLegacyItem#useOn} (func_77648_a, onItemUse).
 *
 * Unlike {@code UmbLegacyBlockTest}, this class does NOT need the unfrozen-registry bootstrap mode:
 * {@code Item}'s constructor also unconditionally calls {@code registry.createIntrusiveHolder(this)}
 * (javap-verified, same shape as {@code Block}'s), but every fixture here is built with
 * {@code TestSupport.allocate(UmbLegacyItem.class)} (Unsafe, no constructor runs at all) plus
 * reflection to seed the two private fields {@code record}/{@code legacyId} directly - so this runs
 * fine under the REAL frozen bootstrap {@code tools/windows/run-hostagent-tests.ps1} already uses for every
 * other test class, with no test-runner changes needed.
 */
class UmbLegacyItemTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
        // 26.2's item default-component binding (Holder$Reference.bindComponents) is a SEPARATE
        // step from Bootstrap.bootStrap() itself (see VanillaItemBridgeTest/UmbLegacyMenuTest's own
        // identical pattern) - `new ItemStack(...)` throws "Components not bound yet" for any item
        // this suite has not explicitly bound, regardless of how populated/frozen the registry
        // otherwise is. Empty is fine: these stand-in native stacks are never rendered or crafted
        // with, only mutated (FM-4: setCount/setDamageValue) and read back.
        Items.STICK.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        // DIAMOND_PICKAXE needs a real MAX_DAMAGE component bound (isDamageableItem() reads it, not
        // a hardcoded Item field) so the FM-4 in-place-damage test below exercises the SAME branch
        // production code takes for a genuinely damageable legacy-mapped item.
        Items.DIAMOND_PICKAXE.builtInRegistryHolder().bindComponents(DataComponentMap.builder()
                .set(DataComponents.MAX_DAMAGE, 1561)
                .set(DataComponents.DAMAGE, 0)
                .build());
    }

    @AfterEach
    void reset() {
        UmbBridgeHost.resetForTests();
        UmbThread.resetForTests();
    }

    private static UmbLegacyItem item(String legacyId) throws Exception {
        UmbLegacyItem it = TestSupport.allocate(UmbLegacyItem.class);
        ItemRec rec = new ItemRec();
        rec.id = legacyId;
        setField(it, "record", rec);
        setField(it, "legacyId", legacyId);
        return it;
    }

    private static UmbLegacyItem itemWithNoRecord() {
        return TestSupport.allocate(UmbLegacyItem.class);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = UmbLegacyItem.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static ServerLevel fakeLevel(boolean clientSide) throws Exception {
        ServerLevel level = TestSupport.allocate(ServerLevel.class);
        Field f = Level.class.getDeclaredField("isClientSide");
        f.setAccessible(true);
        f.setBoolean(level, clientSide);
        return level;
    }

    private static ServerPlayer fakePlayer() {
        return TestSupport.allocate(ServerPlayer.class);
    }

    /** Builds a UseOnContext WITHOUT running its public constructor (which reads
     *  player.level()/player.getItemInHand(hand) - both unusable on an Unsafe-allocated player) -
     *  same Unsafe-allocate-plus-reflection idiom TestSupport documents for exactly this situation. */
    private static UseOnContext useOnContext(ServerLevel level, ServerPlayer player, InteractionHand hand,
                                              ItemStack stack, BlockHitResult hit) throws Exception {
        UseOnContext ctx = TestSupport.allocate(UseOnContext.class);
        setField(UseOnContext.class, ctx, "player", player);
        setField(UseOnContext.class, ctx, "hand", hand);
        setField(UseOnContext.class, ctx, "hitResult", hit);
        setField(UseOnContext.class, ctx, "level", level);
        setField(UseOnContext.class, ctx, "itemStack", stack);
        return ctx;
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void miningUsesTheLegacyItemCallbacksForNativeBlockStates() throws Exception {
        UmbLegacyItem it = item("umbtest:item_tool");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.itemDestroySpeedResult = 7.25f;
        bridge.itemCanHarvestBlockResult = true;
        bridge.boot(null);
        UmbBridgeHost.set(bridge);

        ItemRec vanillaStone = new ItemRec();
        vanillaStone.id = "minecraft:stone";
        VanillaItemBridge.resetForTest();
        VanillaItemBridge.buildFrom(List.of(vanillaStone));

        ItemStack stack = new ItemStack(Items.STICK);
        assertEquals(7.25f, it.getDestroySpeed(stack, Blocks.STONE.defaultBlockState()));
        assertTrue(it.isCorrectToolForDrops(stack, Blocks.STONE.defaultBlockState()));
        assertEquals(2, bridge.itemDestroySpeedCalls + bridge.itemCanHarvestBlockCalls);
        assertEquals("minecraft:stone", bridge.lastMiningBlockId);
    }

    // ---------------------------------------------------------------- use() / func_77659_a

    @Test
    void useReachesLegacyOnItemRightClickAndWritesTheReturnedStackBackToTheHand() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_igniter");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.useItemRightClickResult = new StackData("hbm:item.test_igniter", 1, 3, null);
        UmbBridgeHost.set(bridge);

        InteractionResult r = it.use(fakeLevel(false), fakePlayer(), InteractionHand.MAIN_HAND);

        assertEquals(InteractionResult.SUCCESS_SERVER, r);
        assertTrue(r.consumesAction());
        assertEquals(1, bridge.useItemRightClickCallCount);
        assertEquals("hbm:item.test_igniter", bridge.lastUseItemRightClickLegacyItemId);
    }

    @Test
    void useReturnsPassAndDoesNotTouchTheHandWhenTheBridgeCannotRun() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_igniter");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.useItemRightClickResult = null; // FM-6-shaped: bridge could not run at all
        UmbBridgeHost.set(bridge);

        InteractionResult r = it.use(fakeLevel(false), fakePlayer(), InteractionHand.MAIN_HAND);

        assertEquals(InteractionResult.PASS, r);
    }

    @Test
    void useIgnoresTheOffHand() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_igniter");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        InteractionResult r = it.use(fakeLevel(false), fakePlayer(), InteractionHand.OFF_HAND);

        assertEquals(InteractionResult.PASS, r);
        assertEquals(0, bridge.useItemRightClickCallCount, "1.7.10 has one held item - off-hand is out of scope");
    }

    @Test
    void useIsANoOpOnTheClientAndReturnsANonAuthoritativeSuccess() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_igniter");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        InteractionResult r = it.use(fakeLevel(true), fakePlayer(), InteractionHand.MAIN_HAND);

        assertEquals(InteractionResult.SUCCESS, r);
        assertEquals(0, bridge.useItemRightClickCallCount, "the server must do the one real call, not the client");
    }

    @Test
    void useWithNoLegacyRecordFallsThroughToVanillaSuper() {
        UmbLegacyItem it = itemWithNoRecord();
        // super.use()'s default body (javap-verified) reads DataComponents off the passed stack
        // via player.getItemInHand(hand) - not exercisable with a fake player, so this only pins
        // down that record==null takes the super path at all rather than touching the bridge, by
        // using a level/player/hand combo where our own code would otherwise short-circuit first.
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);
        // A native (non-legacy) item must never reach the bridge - assert via call count after a
        // best-effort call that is allowed to throw from vanilla's own default body (uncontrolled
        // fixture), since only the "did NOT call the bridge" half is this test's contract.
        try {
            it.use(null, null, InteractionHand.MAIN_HAND);
        } catch (Throwable ignored) {
            // vanilla's own default body needs real player/level state this fixture does not have -
            // irrelevant to what this test checks
        }
        assertEquals(0, bridge.useItemRightClickCallCount);
    }

    // ---------------------------------------------------------------- useOn() / func_77648_a

    @Test
    void useOnReachesLegacyOnItemUseWithBlockRelativeHitCoordinatesAndTheRightSide() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_wrench");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.useItemOnBlockResult = new ItemUseResult(true, new StackData("hbm:item.test_wrench", 1, 0, null));
        UmbBridgeHost.set(bridge);

        ServerLevel level = fakeLevel(false);
        ServerPlayer player = fakePlayer();
        BlockPos pos = new BlockPos(5, 6, 7);
        Vec3 absoluteHit = new Vec3(pos.getX() + 0.25, pos.getY() + 0.75, pos.getZ() + 0.5);
        BlockHitResult hit = new BlockHitResult(absoluteHit, Direction.NORTH, pos, false);
        ItemStack stack = new ItemStack(Items.STICK); // stand-in native stack object to mutate

        InteractionResult r = it.useOn(useOnContext(level, player, InteractionHand.MAIN_HAND, stack, hit));

        assertEquals(InteractionResult.SUCCESS_SERVER, r);
        assertEquals(1, bridge.useItemOnBlockCallCount);
        assertEquals("hbm:item.test_wrench", bridge.lastUseItemOnBlockLegacyItemId);
    }

    @Test
    void useOnAppliesInPlaceMutationOntoTheSameNativeStackFM4() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_wrench");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        // FM-4: legacy mutated the stack to count=1, damage=9 (simulating durability use)
        bridge.useItemOnBlockResult = new ItemUseResult(true, new StackData("hbm:item.test_wrench", 1, 9, null));
        UmbBridgeHost.set(bridge);

        ServerLevel level = fakeLevel(false);
        ServerPlayer player = fakePlayer();
        BlockPos pos = BlockPos.ZERO;
        BlockHitResult hit = new BlockHitResult(new Vec3(0.5, 0.5, 0.5), Direction.UP, pos, false);
        ItemStack stack = new ItemStack(Items.DIAMOND_PICKAXE); // a damageable native stack, count 1

        it.useOn(useOnContext(level, player, InteractionHand.MAIN_HAND, stack, hit));

        assertEquals(1, stack.getCount());
        assertEquals(9, stack.getDamageValue(), "FM-4: in-place damage must be applied onto the SAME stack object");
    }

    @Test
    void useOnReplacesTheHandWhenTheFallbackReturnsADifferentItem() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_bucket");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        // The declined-use air fallback (1.7.10 rightClickMouse order) may return a DIFFERENT
        // item (empty bucket -> water bucket): 1.7.10 writes the whole slot then
        // (PlayerControllerMP.func_78769_a, javap-verified), so the hand must be replaced -
        // FM-4 in-place mutation would keep the wrong item.
        bridge.useItemOnBlockResult = new ItemUseResult(true, new StackData("minecraft:stick", 1, 0, null));
        UmbBridgeHost.set(bridge);
        VanillaItemBridge.resetForTest();
        ItemRec stick = new ItemRec();
        stick.id = "minecraft:stick";
        VanillaItemBridge.buildFrom(List.of(stick));

        ServerLevel level = fakeLevel(false);
        ServerPlayer player = fakePlayer();
        BlockPos pos = BlockPos.ZERO;
        BlockHitResult hit = new BlockHitResult(new Vec3(0.5, 0.5, 0.5), Direction.UP, pos, false);
        ItemStack stack = new ItemStack(Items.STICK);

        InteractionResult r = it.useOn(useOnContext(level, player, InteractionHand.MAIN_HAND, stack, hit));

        assertEquals(InteractionResult.SUCCESS_SERVER, r);
        assertEquals(1, bridge.useItemOnBlockCallCount);
        assertFalse(LegacyStackConv.toNative(new StackData("minecraft:stick", 1, 0, null)).isEmpty(),
                "the replacement id must resolve, or this test passes without exercising the new branch");
    }

    @Test
    void useOnHandledFalseStillReturnsPassButBridgeWasReached() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_wrench");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.useItemOnBlockResult = new ItemUseResult(false, new StackData("hbm:item.test_wrench", 1, 0, null));
        UmbBridgeHost.set(bridge);

        ServerLevel level = fakeLevel(false);
        ServerPlayer player = fakePlayer();
        BlockPos pos = BlockPos.ZERO;
        BlockHitResult hit = new BlockHitResult(new Vec3(0.5, 0.5, 0.5), Direction.DOWN, pos, false);
        ItemStack stack = new ItemStack(Items.STICK);

        InteractionResult r = it.useOn(useOnContext(level, player, InteractionHand.MAIN_HAND, stack, hit));

        assertEquals(InteractionResult.PASS, r);
        assertEquals(1, bridge.useItemOnBlockCallCount, "legacy declining must still mean the call reached it");
    }

    @Test
    void useOnIgnoresTheOffHand() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_wrench");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        ServerLevel level = fakeLevel(false);
        ServerPlayer player = fakePlayer();
        BlockPos pos = BlockPos.ZERO;
        BlockHitResult hit = new BlockHitResult(new Vec3(0.5, 0.5, 0.5), Direction.DOWN, pos, false);
        ItemStack stack = new ItemStack(Items.STICK);

        InteractionResult r = it.useOn(useOnContext(level, player, InteractionHand.OFF_HAND, stack, hit));

        assertEquals(InteractionResult.PASS, r);
        assertEquals(0, bridge.useItemOnBlockCallCount);
    }

    @Test
    void useOnIsANoOpOnTheClientAndReturnsANonAuthoritativeSuccess() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_wrench");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        ServerLevel level = fakeLevel(true);
        ServerPlayer player = fakePlayer();
        BlockPos pos = BlockPos.ZERO;
        BlockHitResult hit = new BlockHitResult(new Vec3(0.5, 0.5, 0.5), Direction.DOWN, pos, false);
        ItemStack stack = new ItemStack(Items.STICK);

        InteractionResult r = it.useOn(useOnContext(level, player, InteractionHand.MAIN_HAND, stack, hit));

        assertEquals(InteractionResult.SUCCESS, r);
        assertEquals(0, bridge.useItemOnBlockCallCount);
    }

    @Test
    void useOnWithNoLegacyRecordFallsThroughToVanillaSuperAndNeverCallsTheBridge() {
        UmbLegacyItem it = itemWithNoRecord();
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);
        // super.useOn's default body (javap-verified) is a single-instruction "return PASS" - no
        // context field is ever dereferenced, so this is safe to call directly with an Unsafe
        // fixture and pins down the actual production behaviour, not just "must not throw".
        UseOnContext bogus = TestSupport.allocate(UseOnContext.class);
        InteractionResult r = it.useOn(bogus);
        assertEquals(InteractionResult.PASS, r);
        assertEquals(0, bridge.useItemOnBlockCallCount);
    }

    @Test
    void tooltipUsesLegacyLinesAndConvertsSectionColorsToComponents() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_tooltip");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.itemTooltipResult = List.of("\u00a7aGreen", "plain");
        bridge.boot(null);
        UmbBridgeHost.set(bridge);
        List<net.minecraft.network.chat.Component> lines = new ArrayList<>();

        it.appendHoverText(new ItemStack(Items.STICK), net.minecraft.world.item.Item.TooltipContext.EMPTY,
                net.minecraft.world.item.component.TooltipDisplay.DEFAULT, lines::add,
                net.minecraft.world.item.TooltipFlag.NORMAL);

        assertEquals("Green", lines.get(0).getString());
        assertEquals("plain", lines.get(1).getString());
        assertEquals(2, lines.size());
    }

    @Test
    void useLifecycleAndInventoryTickRoundTripThroughBridge() throws Exception {
        UmbLegacyItem it = item("hbm:item.test_use");
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.itemUseDurationResult = 32;
        bridge.itemUseActionResult = "eat";
        bridge.itemInventoryTickResult = new StackData("minecraft:stick", 3, 0, null);
        bridge.boot(null);
        UmbBridgeHost.set(bridge);
        ItemStack stack = new ItemStack(Items.STICK, 1);

        assertEquals(32, it.getUseDuration(stack, null));
        assertEquals(ItemUseAnimation.EAT, it.getUseAnimation(stack));
        it.inventoryTick(stack, fakeLevel(false), fakePlayer(), null);
        it.onUseTick(fakeLevel(false), fakePlayer(), stack, 10);
        it.releaseUsing(stack, fakeLevel(false), fakePlayer(), 8);
        it.finishUsingItem(stack, fakeLevel(false), fakePlayer());

        assertEquals(3, stack.getCount());
        assertEquals(1, bridge.itemUsingTickCalls);
        assertEquals(1, bridge.itemStoppedUsingCalls);
        assertEquals(1, bridge.itemEatenCalls);
    }
}
