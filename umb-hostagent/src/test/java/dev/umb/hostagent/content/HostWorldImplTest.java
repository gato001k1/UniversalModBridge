package dev.umb.hostagent.content;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Entity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * BUG-3 (INTERACTION-BRIDGE.md): {@code HostWorldImpl.setMeta} used to write ONLY the meta side
 * table, never resolving a variant twin or notifying the client - so a mod's standard
 * setBlockMetadataWithNotify call (func_72921_c, the 1.7.10 rotate/toggle/change-active-state
 * call) genuinely changed state that the world never showed. The fix makes {@code setMeta} share
 * {@code setBlock}'s variant resolution ({@link HostWorldImpl#resolveVariantOrBase}) and its real
 * write path - this pins down the resolver both share.
 *
 * A live end-to-end exercise of {@code setMeta} against a real {@code ServerLevel} is NOT possible
 * headlessly (R8: 26.2 world/chunk storage needs a live MinecraftServer, same constraint that left
 * {@code HostWorldImpl} with zero JUnit coverage before this fix); see LANE-REPORT.md for the
 * honest gap. This test instead pins the exact resolution both {@code setBlock} and {@code setMeta}
 * now share, and that {@code setMeta}'s identity-preservation reads the CURRENT block's own legacy
 * id, never a caller-supplied different one (there is no such parameter on
 * {@code HostWorld.setMeta} at all - the contract only allows preserving identity, not changing it).
 */
class HostWorldImplTest {

    private static final String LEGACY_ID = "umbtest:variant_block";

    @BeforeEach
    void bootstrapVanillaRegistries() {
        TestSupport.ensureBootstrapped();
    }

    @AfterEach
    void cleanup() {
        Registrar.LEGACY_BLOCKS.remove(LEGACY_ID);
        Registrar.LEGACY_VARIANT_BLOCKS.remove(LEGACY_ID + "@0");
        Registrar.LEGACY_VARIANT_BLOCKS.remove(LEGACY_ID + "@1");
    }

    @Test
    void prefersAPerMetaVariantOverTheBaseBlock() {
        Registrar.LEGACY_BLOCKS.put(LEGACY_ID, Blocks.STONE);
        Registrar.LEGACY_VARIANT_BLOCKS.put(LEGACY_ID + "@1", Blocks.GRANITE);

        Block resolved = HostWorldImpl.resolveVariantOrBase(LEGACY_ID, 1);

        assertSame(Blocks.GRANITE, resolved, "a registered meta-1 variant must win over the base block");
    }

    @Test
    void fallsBackToTheBaseBlockWhenNoVariantIsRegisteredForThatMeta() {
        Registrar.LEGACY_BLOCKS.put(LEGACY_ID, Blocks.STONE);
        Registrar.LEGACY_VARIANT_BLOCKS.put(LEGACY_ID + "@1", Blocks.GRANITE);

        // meta 2 has no registered variant -- must fall back to the SAME legacy id's base block,
        // never a different legacy id and never null just because meta 2 specifically has no twin.
        Block resolved = HostWorldImpl.resolveVariantOrBase(LEGACY_ID, 2);

        assertSame(Blocks.STONE, resolved, "an unregistered meta must fall back to the base block, preserving identity");
    }

    @Test
    void furnaceLitAndUnlitVariantsResolveToTheirOwnHostBlocks() {
        String id = "umbtest:furnace_swap";
        Registrar.LEGACY_BLOCKS.put(id, Blocks.FURNACE);
        Registrar.LEGACY_VARIANT_BLOCKS.put(id + "@0", Blocks.FURNACE);
        Registrar.LEGACY_VARIANT_BLOCKS.put(id + "@1", Blocks.SMOKER);

        Block off = HostWorldImpl.resolveVariantOrBase(id, 0);
        Block lit = HostWorldImpl.resolveVariantOrBase(id, 1);

        assertSame(Blocks.FURNACE, off, "off metadata must select the unlit host block/model");
        assertSame(Blocks.SMOKER, lit, "lit metadata must select the lit host block/model");
        assertTrue(off != lit, "the furnace swap must change the host block state, not only legacy metadata");
    }

    @Test
    void returnsNullForACompletelyUnknownLegacyId() {
        assertNull(HostWorldImpl.resolveVariantOrBase("umbtest:does_not_exist", 0));
    }

    @Test
    void legacyExplosionStrengthIsBoundedAndNonFiniteInputIsSafe() {
        assertEquals(4.0F, HostWorldImpl.clampExplosionStrength(100.0F, 4.0F));
        assertEquals(4.0F, HostWorldImpl.clampExplosionStrength(Float.POSITIVE_INFINITY, 4.0F));
        assertEquals(0.0F, HostWorldImpl.clampExplosionStrength(Float.NaN, 4.0F));
        assertEquals(0.0F, HostWorldImpl.clampExplosionStrength(-1.0F, 4.0F));
    }

    @Test
    void legacyExplosionBlockDamageRequiresMobGriefing() {
        assertTrue(HostWorldImpl.allowsExplosionBlockDamage(true, true));
        assertFalse(HostWorldImpl.allowsExplosionBlockDamage(true, false));
        assertFalse(HostWorldImpl.allowsExplosionBlockDamage(false, true));
    }

    // ---------------------------------------------------------------- ENTITY-BRIDGE: spawnEntity
    //
    // A live end-to-end exercise of spawnEntity's success path is NOT possible headlessly either
    // (it needs a real ServerLevel to call addFreshEntity on - same R8 constraint as setBlock/
    // setMeta above). This pins the one branch reachable without one: spawnEntity's null-check on
    // Registrar.LEGACY_ENTITY_TYPE returns BEFORE ever touching `level`, so passing a null
    // ServerLevel here is safe and does not paper over the untested branch - see ENTITY-LANE.md.

    @org.junit.jupiter.api.AfterEach
    void resetEntityType() {
        Registrar.LEGACY_ENTITY_TYPE = null;
        Registrar.LEGACY_ENTITY_TYPES.clear();
    }

    @Test
    void entityTypeLookupIsNamespaceOwned() {
        EntityType<UmbLegacyEntity> first = TestSupport.allocate(EntityType.class);
        EntityType<UmbLegacyEntity> second = TestSupport.allocate(EntityType.class);
        Registrar.LEGACY_ENTITY_TYPES.put("umbtest_first", first);
        Registrar.LEGACY_ENTITY_TYPES.put("umbtest_second", second);

        assertSame(first, Registrar.entityTypeFor("umbtest_first"));
        assertSame(second, Registrar.entityTypeFor("umbtest_second"));
    }

    @Test
    void spawnEntityDeclinesWhenNoEntityTypeIsRegisteredYet() {
        Registrar.LEGACY_ENTITY_TYPE = null;
        HostWorldImpl world = new HostWorldImpl(null);
        FakeLegacyBridge.FakeEntityHandle handle = new FakeLegacyBridge.FakeEntityHandle("umbtest:fixture", 0, 0, 0);

        assertFalse(world.spawnEntity(handle));
    }

    @Test
    void spawnEntityDeclinesForANullHandle() {
        HostWorldImpl world = new HostWorldImpl(null);
        assertFalse(world.spawnEntity(null));
    }

    @Test
    void legacyMountRemainsAuthoritativeAcrossRepeatedEmptyHostReads() {
        HostWorldImpl.RiderSyncState state = new HostWorldImpl.RiderSyncState();
        state.observeHostRider(new HostPlayerImpl(null));

        // This is the live failure shape: legacy still has a rider while the host passenger list
        // is empty during native passenger validation/repair.
        assertFalse(state.observeMissingHostRider(false));
        assertEquals(0, state.missingTicks());
        assertFalse(state.observeMissingHostRider(false));
        assertEquals(0, state.missingTicks());
        assertFalse(state.observeMissingHostRider(false));
        assertEquals(0, state.missingTicks());

        // A native absence alone is not a dismount; only an explicit legacy interaction clears.
        assertFalse(state.observeMissingHostRider(false, true));
        state.observeLegacyInteraction(null, true);
        assertTrue(state.observeMissingHostRider(false, true));
        assertNull(state.lastHostRider());
        assertTrue(state.allowLegacyMirror(null));
    }

    @Test
    void legacyRiderSurvivesMissingNativePassengerUntilHostDismountIsConfirmed() {
        HostWorldImpl.RiderSyncState state = new HostWorldImpl.RiderSyncState();
        state.observeHostRider(new HostPlayerImpl(null));

        for (int i = 0; i < 20; i++) {
            assertFalse(state.observeMissingHostRider(true, false));
        }
        assertNotNull(state.lastHostRider());
        assertFalse(state.observeMissingHostRider(true, true));
        state.observeLegacyInteraction(null, true);
        assertTrue(state.observeMissingHostRider(false, true));
        assertNull(state.lastHostRider());
    }

    @Test
    void acceptedNonMountInteractionIsNotAnExplicitDismount() {
        HostWorldImpl.RiderSyncState state = new HostWorldImpl.RiderSyncState();
        state.observeLegacyInteraction(null, false);
        assertFalse(state.explicitDismount());
    }

    @Test
    void explicitLegacyDismountClearsRiderWithoutWaitingForGrace() {
        HostWorldImpl.RiderSyncState state = new HostWorldImpl.RiderSyncState();
        state.observeHostRider(new HostPlayerImpl(null));
        state.observeLegacyInteraction(null);
        assertTrue(state.explicitDismount());
        assertTrue(state.observeMissingHostRider(false));
        assertNull(state.lastHostRider());
    }

    @Test
    void riderStateDoesNotTreatVanillaValidationRepairAsARealDismount() {
        HostWorldImpl.RiderSyncState state = new HostWorldImpl.RiderSyncState();
        state.observeLegacyInteraction();
        assertTrue(state.allowLegacyMirror("Pilot"));
        state.observeHostRider();
        assertEquals(0, state.missingTicks());
        assertTrue(state.allowLegacyMirror("Pilot"));
    }

    @Test
    void riderStateRetainsLastHostRiderAcrossNonEmptyNestedPassengerGap() {
        HostWorldImpl.RiderSyncState state = new HostWorldImpl.RiderSyncState();
        HostPlayerImpl rider = new HostPlayerImpl(null);

        state.observeHostRider(rider);

        assertSame(rider, state.lastHostRider());
        assertFalse(state.observeMissingHostRider(true));
        assertSame(rider, state.lastHostRider());
        assertTrue(state.allowLegacyMirror("Pilot"));

        state.observeLegacyInteraction();
        assertNull(state.lastHostRider());
    }

    @Test
    void vehicleChainLookupAcceptsDirectAndNestedSeatLinks() throws Exception {
        Entity root = TestSupport.allocate(UmbLegacyEntity.class);
        Entity seat = TestSupport.allocate(UmbLegacyEntity.class);
        Entity player = TestSupport.allocate(UmbLegacyEntity.class);
        java.lang.reflect.Field vehicle = Entity.class.getDeclaredField("vehicle");
        vehicle.setAccessible(true);

        vehicle.set(player, seat);
        vehicle.set(seat, root);
        assertTrue(HostWorldImpl.vehicleChainContains(player.getVehicle(), root));
        assertTrue(HostWorldImpl.vehicleChainContains(player.getVehicle(), seat));
        assertFalse(HostWorldImpl.vehicleChainContains(null, root));
    }

    @Test
    void legacyChildPassengerIsNotFlattenedOntoItsParentTwin() {
        FakeLegacyBridge.FakeEntityHandle parent =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:aircraft", 0, 0, 0);
        FakeLegacyBridge.FakeEntityHandle child =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:seat", 0, 0, 0);
        child.passengerIdentity = "player";

        assertTrue(HostWorldImpl.shouldMirrorDirectRider(parent));
        assertFalse(HostWorldImpl.shouldMirrorDirectRider(child));
    }

    @Test
    void hostDismountClearsRetainedRiderWithoutWaitingForGrace() {
        HostWorldImpl.RiderSyncState state = new HostWorldImpl.RiderSyncState();
        state.observeHostRider(new HostPlayerImpl(null));

        // A native absence alone is retained, even when confirmed: empty reads are only
        // ordering artifacts until something explicitly dismounts.
        assertFalse(state.observeMissingHostRider(false, true));
        assertNotNull(state.lastHostRider());

        // An actual passenger removal is an explicit dismount: the retained rider is
        // dropped, so the next prepareEntity propagates null to legacy instead of
        // re-asserting the stale link for the rider sync to re-mount (Shift-stuck loop).
        state.observeHostDismount();
        assertTrue(state.explicitDismount());
        assertTrue(state.observeMissingHostRider(false, true));
        assertNull(state.lastHostRider());
        assertTrue(state.allowLegacyMirror(null));
    }

    // ---------------------------------------------------------------- drops-waterlogged lane

    @Test
    void waterloggedStairsReadsAsWater() {
        net.minecraft.world.level.block.state.BlockState logged = Blocks.OAK_STAIRS.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true);

        assertTrue(HostWorldImpl.isWaterloggedWater(logged),
                "a waterlogged host state holds source water where 1.7.10 has a water block");
    }

    @Test
    void dryStairsAndPlainWaterAreNotWaterlogged() {
        assertFalse(HostWorldImpl.isWaterloggedWater(Blocks.OAK_STAIRS.defaultBlockState()));
        assertFalse(HostWorldImpl.isWaterloggedWater(Blocks.WATER.defaultBlockState()),
                "plain water maps through the normal fluid entry, not the waterlogged branch");
        assertFalse(HostWorldImpl.isWaterloggedWater(Blocks.STONE.defaultBlockState()));
        assertFalse(HostWorldImpl.isWaterloggedWater(null));
    }

    @Test
    void dropItemNeverThrowsAndIgnoresEmptyStacks() {
        // No live ServerLevel exists headlessly: the empty stack must return before any
        // level touch, and a real stack on a level-less world must be swallowed, never thrown.
        HostWorldImpl world = TestSupport.allocate(HostWorldImpl.class);
        world.dropItem(0.5D, 64.5D, 0.5D, dev.umb.bridge.api.StackData.EMPTY);
        world.dropItem(0.5D, 64.5D, 0.5D,
                new dev.umb.bridge.api.StackData("umbtest:unmapped_drop", 1, 0, null));
    }
}
