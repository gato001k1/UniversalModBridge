package dev.umb.hostagent.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Door-live follow-up (poison transparency): the automation status carries the
 * poison reason, and only deterministic poisons are permanent - transient ones
 * clear through the rate-limited retry gate. Same furnace-type idiom as
 * {@link UmbLegacyBlockEntityTest} (no level needed: poison/status paths never
 * touch one).
 */
class UmbLegacyBlockEntityPoisonTest {

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

    @Test
    void statusCarriesReason() {
        UmbLegacyBlockEntity be = newEntity();
        assertEquals("pending", be.legacyTileStatus());
        be.poisonForTest("ensureHandle threw: boom", true);
        assertEquals("poisoned:ensureHandle threw: boom", be.legacyTileStatus());
        assertTrue(be.isPoisoned());
    }

    @Test
    void reasonDropsControlCharsAndTruncates() {
        UmbLegacyBlockEntity be = newEntity();
        be.poisonForTest("a\nb\rc\td" + "x".repeat(200), false);
        String status = be.legacyTileStatus();
        assertTrue(status.startsWith("poisoned:"));
        String reason = status.substring("poisoned:".length());
        assertTrue(reason.chars().noneMatch(Character::isISOControl), "no control chars: " + reason);
        assertTrue(reason.length() <= 96, "truncated, was " + reason.length());
        assertTrue(reason.startsWith("abcd"), "keeps the readable head: " + reason);
    }

    @Test
    void retryableClearsAfterBackoff() {
        UmbLegacyBlockEntity be = newEntity();
        be.poisonForTest("legacy bridge not booted (no bridge installed, or boot failed)", true);
        assertFalse(be.tryClearRetryablePoison(0L), "backoff not elapsed");
        assertTrue(be.isPoisoned());
        assertTrue(be.tryClearRetryablePoison(UmbLegacyBlockEntity.POISON_RETRY_TICKS),
                "clears once backoff elapsed");
        assertFalse(be.isPoisoned());
        assertEquals("pending", be.legacyTileStatus());
        assertFalse(be.tryClearRetryablePoison(Long.MAX_VALUE), "already clear: no-op");
    }

    @Test
    void deterministicNeverClears() {
        UmbLegacyBlockEntity be = newEntity();
        be.poisonForTest("no legacy id for block at BlockPos{x=1, y=2, z=3}", false);
        assertFalse(be.tryClearRetryablePoison(0L));
        assertFalse(be.tryClearRetryablePoison(Long.MAX_VALUE));
        assertTrue(be.isPoisoned());
        assertEquals("poisoned:no legacy id for block at BlockPos{x=1, y=2, z=3}",
                be.legacyTileStatus());
    }
}
