package dev.umb.hostagent.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless gate for step 4/7: the opaque legacy NBT blob round-trips through 26.2's real
 * ValueOutput/ValueInput, and a bridge failure poisons only the one block entity.
 *
 * BlockEntity's own constructor requires {@code type.isValid(state)} to hold (verified via
 * javap -c: validateBlockState throws IllegalStateException otherwise). Rather than construct a
 * throwaway {@code BlockEntityType} (whose ctor unconditionally calls
 * {@code registry.createIntrusiveHolder(this)}, which only works pre-freeze -- see TestSupport),
 * this reuses the REAL, already-registered {@code BlockEntityTypes.FURNACE} together with
 * {@code Blocks.FURNACE.defaultBlockState()}: reading an existing static field never re-triggers
 * construction, so it works fine after the real (freezing) bootstrap that {@code new ItemStack}
 * elsewhere in this suite needs.
 */
class UmbLegacyBlockEntityTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
    }

    private static UmbLegacyBlockEntity newEntity() {
        BlockEntityType<net.minecraft.world.level.block.entity.FurnaceBlockEntity> vanillaType = BlockEntityTypes.FURNACE;
        @SuppressWarnings("unchecked")
        BlockEntityType<UmbLegacyBlockEntity> type = (BlockEntityType<UmbLegacyBlockEntity>) (BlockEntityType<?>) vanillaType;
        BlockState state = Blocks.FURNACE.defaultBlockState();
        return new UmbLegacyBlockEntity(type, BlockPos.ZERO, state);
    }

    private static ValueOutput freshOutput() {
        return TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
    }

    private static ValueInput inputFrom(CompoundTag tag) {
        return TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag);
    }

    @Test
    void nbtBlobRoundTripsThroughValueOutputAndValueInput() {
        UmbLegacyBlockEntity be = newEntity();
        FakeLegacyBridge.FakeTileHandle handle = new FakeLegacyBridge.FakeTileHandle("hbm:tile.machine_furnace_brick_off", 1, 2, 3);
        handle.ticks = 42;
        be.setHandleForTest(handle);

        ValueOutput out = freshOutput();
        be.saveAdditional(out);
        CompoundTag tag = ((TagValueOutput) out).buildResult();

        UmbLegacyBlockEntity fresh = newEntity();
        fresh.loadAdditional(inputFrom(tag));

        assertArrayEquals("ticks=42".getBytes(StandardCharsets.UTF_8), fresh.pendingNbtForTest(),
                "the blob decoded back out must be byte-identical to what the handle produced");
    }

    @Test
    void getUpdateTagCarriesTheSameBlobForClientSync() {
        UmbLegacyBlockEntity be = newEntity();
        FakeLegacyBridge.FakeTileHandle handle = new FakeLegacyBridge.FakeTileHandle("hbm:x", 0, 0, 0);
        handle.ticks = 7;
        be.setHandleForTest(handle);

        CompoundTag tag = be.getUpdateTag(RegistryAccess.EMPTY);
        assertTrue(tag.getString("umb_legacy_nbt").isPresent());
    }

    @Test
    void noBlobMeansNothingIsWritten() {
        UmbLegacyBlockEntity be = newEntity();
        ValueOutput out = freshOutput();
        be.saveAdditional(out);
        CompoundTag tag = ((TagValueOutput) out).buildResult();
        assertTrue(tag.getString("umb_legacy_nbt").isEmpty());
    }

    @Test
    void loadAdditionalReplaysIntoHandleCreatedBeforeSavedNbtIsRead() {
        UmbLegacyBlockEntity be = newEntity();
        FakeLegacyBridge.FakeTileHandle handle =
                new FakeLegacyBridge.FakeTileHandle("hbm:tile.machine_furnace_brick_off", 1, 2, 3);
        handle.ticks = 3;
        be.setHandleForTest(handle);

        ValueOutput out = freshOutput();
        out.putString("umb_legacy_nbt", java.util.Base64.getEncoder()
                .encodeToString("ticks=97".getBytes(StandardCharsets.UTF_8)));
        be.loadAdditional(inputFrom(((TagValueOutput) out).buildResult()));

        assertEquals(97, handle.ticks,
                "the 26.2 setLevel-before-loadAdditional ordering must not drop persisted tile state");
        assertNull(be.pendingNbtForTest());
    }

    @Test
    void serverTickAdvancesTheLiveHandle() {
        UmbLegacyBlockEntity be = newEntity();
        FakeLegacyBridge.FakeTileHandle handle = new FakeLegacyBridge.FakeTileHandle("hbm:x", 0, 0, 0);
        be.setHandleForTest(handle);

        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState(), be);
        assertEquals(1, handle.ticks);
        assertFalse(be.isPoisoned());
    }

    @Test
    void sameLegacyClassPreservesOpaqueStateAcrossVariantReplacement() {
        UmbLegacyBlockEntity previous = newEntity();
        FakeLegacyBridge.FakeTileHandle oldHandle =
                new FakeLegacyBridge.FakeTileHandle("hbm:machine", 0, 0, 0);
        oldHandle.ticks = 100000;
        previous.setHandleForTest(oldHandle);

        UmbLegacyBlockEntity current = newEntity();
        FakeLegacyBridge.FakeTileHandle newHandle =
                new FakeLegacyBridge.FakeTileHandle("hbm:machine", 0, 0, 0);
        current.setHandleForTest(newHandle);

        current.preserveLegacyStateFrom(previous);

        assertEquals(100000, newHandle.ticks,
                "an off/on native block swap must rehydrate the replacement legacy tile");
    }

    @Test
    void differentLegacyClassDoesNotCarryStateAcrossReplacement() {
        UmbLegacyBlockEntity previous = newEntity();
        FakeLegacyBridge.FakeTileHandle oldHandle =
                new FakeLegacyBridge.FakeTileHandle("hbm:machine_a", 0, 0, 0);
        oldHandle.ticks = 100000;
        previous.setHandleForTest(oldHandle);

        UmbLegacyBlockEntity current = newEntity();
        FakeLegacyBridge.FakeTileHandle newHandle =
                new FakeLegacyBridge.FakeTileHandle("hbm:machine_b", 0, 0, 0);
        current.setHandleForTest(newHandle);

        current.preserveLegacyStateFrom(previous);

        assertEquals(0, newHandle.ticks,
                "unrelated legacy block replacement must not inherit the old tile");
    }

    @Test
    void duplicateModernNeighborHooksAreCollapsedPerTickAndNeighborBlock() {
        UmbLegacyBlockEntity be = newEntity();

        assertTrue(be.claimNeighborNotification(10L, Blocks.REDSTONE_TORCH));
        assertFalse(be.claimNeighborNotification(10L, Blocks.REDSTONE_TORCH),
                "Block.neighborChanged plus onNeighborChange must not toggle a door twice");
        assertTrue(be.claimNeighborNotification(10L, Blocks.LEVER));
        assertTrue(be.claimNeighborNotification(11L, Blocks.REDSTONE_TORCH));
    }

    @Test
    void anInvalidHandlePoisonsOnlyThisBlockEntityAndStopsTicking() {
        UmbLegacyBlockEntity be = newEntity();
        FakeLegacyBridge.FakeTileHandle handle = new FakeLegacyBridge.FakeTileHandle("hbm:x", 0, 0, 0);
        handle.valid = false;
        be.setHandleForTest(handle);

        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState(), be);
        assertTrue(be.isPoisoned());
        assertEquals(0, handle.ticks, "a poisoned handle must never be ticked");

        // once poisoned, further serverTick calls are complete no-ops
        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState(), be);
        assertEquals(0, handle.ticks);
    }

    @Test
    void withNoLevelAndNoHandleServerTickIsANoOpNeverAPoisonOrACrash() {
        UmbLegacyBlockEntity be = newEntity();
        assertNotNull(be);
        // be.level was never set (setLevel() is never called headlessly) and no handle exists,
        // so ensureHandle's `!(level instanceof ServerLevel)` guard returns quietly -- this must
        // never throw and must not poison the block entity just because a level isn't attached.
        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, Blocks.FURNACE.defaultBlockState(), be);
        assertFalse(be.isPoisoned());
    }

    // ---------------------------------------------------------------- PART 1 ordering fix
    //
    // UmbLegacyBlock.setPlacedBy.javadoc: setLevel/ensureHandle runs DURING the initial placement's
    // level.setBlock call, strictly before onBlockPlacedBy (func_149689_a) has a chance to raise
    // this position's own metadata to its real, tile-entity-bearing value (a real BlockDummyable
    // pattern, confirmed live). retryAfterPlacement is the fix: give ensureHandle a second,
    // correctly-timed attempt right after onBlockPlacedBy returns.

    @Test
    void retryAfterPlacementIsANoOpWhenAHandleAlreadyExists() {
        UmbLegacyBlockEntity be = newEntity();
        FakeLegacyBridge.FakeTileHandle handle = new FakeLegacyBridge.FakeTileHandle("hbm:x", 0, 0, 0);
        be.setHandleForTest(handle);

        be.retryAfterPlacement();

        assertSame(handle, be.handleForTest(), "an existing handle must never be replaced or dropped");
        assertFalse(be.isPoisoned());
    }

    @Test
    void retryAfterPlacementClearsStalePoisonAndReattemptsWhenNoHandleExistsYet() throws Exception {
        UmbLegacyBlockEntity be = newEntity();
        java.lang.reflect.Field poisonedField = UmbLegacyBlockEntity.class.getDeclaredField("poisoned");
        poisonedField.setAccessible(true);
        poisonedField.set(be, true);
        assertTrue(be.isPoisoned(), "test setup: must start poisoned, simulating the too-early ensureHandle failure");

        // No level is attached headlessly (R8, same constraint as the rest of this class), so the
        // re-attempted ensureHandle's own `!(level instanceof ServerLevel)` guard returns quietly
        // WITHOUT re-poisoning - this still proves the important half of the fix: the stale poison
        // from the premature first attempt is actually cleared, not permanent, so a real level's
        // later retry (live game) genuinely gets a fresh chance instead of being stuck forever.
        be.retryAfterPlacement();

        assertFalse(be.isPoisoned(), "retryAfterPlacement must clear the stale poison before re-attempting");
        assertNull(be.handleForTest());
    }
}
