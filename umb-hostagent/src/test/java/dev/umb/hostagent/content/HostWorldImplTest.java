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
    void vehicleChainLookupAcceptsDirectLinksButNotAcrossANestedTwinBoundary() throws Exception {
        // TRUE for the "root" case below - that assertion was pinning the EXACT live bug (a fresh
        // AH-1Z's gunner ended up positioned at the pilot's spot the moment a rider boarded the
        // seat). vehicleChainContains(seat, root) walking straight through the nested `seat` twin
        // to reach its OWN ancestor `root` let the aircraft's prepareEntity/findServerPassenger
        // treat the gunner (whose real, direct vehicle is the SEAT) as if it were the aircraft's
        // own rider, corrupting the aircraft's legacy riddenByEntity and re-running its
        // hardcoded-to-the-pilot updateRiderPosition on the real player. A rider's DIRECT vehicle
        // link (one hop) must still be recognized; only walking PAST a nested legacy twin to a
        // more distant ancestor is now rejected.
        Entity root = TestSupport.allocate(UmbLegacyEntity.class);
        Entity seat = TestSupport.allocate(UmbLegacyEntity.class);
        Entity player = TestSupport.allocate(UmbLegacyEntity.class);
        java.lang.reflect.Field vehicle = Entity.class.getDeclaredField("vehicle");
        vehicle.setAccessible(true);

        vehicle.set(player, seat);
        vehicle.set(seat, root);
        assertFalse(HostWorldImpl.vehicleChainContains(player.getVehicle(), root),
                "a rider whose vehicle is a NESTED legacy twin must not be attributed to that twin's own ancestor");
        assertTrue(HostWorldImpl.vehicleChainContains(player.getVehicle(), seat),
                "a rider's DIRECT vehicle link must still be recognized");
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

    // ------------------------------------------------ ENTITY-BRIDGE: getEntities native AABB scan
    //
    // UmbLegacyPartTwin (a per-collision-box native collider, e.g. one MCH_EntitySeat's own
    // extra hitbox) was missing from getEntities' native-entity filter - only Player and
    // UmbLegacyEntity were excluded - so it leaked into legacy AABB scans as a generic "wild"
    // HostEntity. MCHeli's real MCH_EntityAircraft.mountMobToSeats (called once, server-side,
    // right after a player boards as pilot) scans exactly such a box for stray EntityLivingBase
    // mobs to auto-crew into empty seats, excludes real EntityPlayers by instanceof, but had no
    // way to know the leaked facade was a collision-box shadow of the very aircraft it was
    // scanning around - so it auto-mounted it into the empty gunner seat, producing the
    // ("directPassenger=dev.umb.legacy.legacyside.UmbHostEntity@..."). This pins the extracted
    // filter predicate directly - a real ServerLevel AABB scan is not possible headlessly (same
    // R8 constraint as setBlock/setMeta/spawnEntity above).

    @Test
    void queryableNativeEntityExcludesPlayersAndBothLegacyTwinFlavors() {
        net.minecraft.server.level.ServerPlayer player =
                TestSupport.allocate(net.minecraft.server.level.ServerPlayer.class);
        UmbLegacyEntity legacyTwin = TestSupport.allocate(UmbLegacyEntity.class);
        UmbLegacyPartTwin partTwin = TestSupport.allocate(UmbLegacyPartTwin.class);
        // A concrete, otherwise-unrelated Entity subclass standing in for "a genuinely wild
        // native entity" (e.g. Pig) - the actual mob class does not matter to the filter, only
        // that it is NOT one of the three excluded categories.
        net.minecraft.world.entity.LightningBolt wildEntity =
                TestSupport.allocate(net.minecraft.world.entity.LightningBolt.class);

        assertFalse(HostWorldImpl.isQueryableNativeEntity(player, null),
                "a real player is already exposed as the legacy side's own UmbPlayer facade");
        assertFalse(HostWorldImpl.isQueryableNativeEntity(legacyTwin, null),
                "a legacy entity's own host twin must not be exposed back to legacy code as a stray HostEntity");
        assertFalse(HostWorldImpl.isQueryableNativeEntity(partTwin, null),
                "a part-twin collider is exactly the mcheli-seats phantom-rider leak (seats-live.md SS D)");
        assertTrue(HostWorldImpl.isQueryableNativeEntity(wildEntity, null),
                "a genuinely wild native entity must still reach legacy AABB scans (real-mob auto-crew stays faithful)");
    }

    @Test
    void queryableNativeEntityRejectsANullEntity() {
        assertFalse(HostWorldImpl.isQueryableNativeEntity(null, null));
    }

    // ------------------------------------------ ENTITY-BRIDGE: nested legacy-twin rider boundary
    //
    // evidence): a fresh AH-1Z's gunner seat placed BOTH the seat and its player rider at the
    // PILOT's offset - once, and only once, a rider actually boarded the seat. Root cause:
    // findServerPassenger's downward recursion (used by prepareEntity/syncEntity to discover
    // "who is MY own direct native rider") walked straight PAST the seat twin (a UmbLegacyEntity,
    // native passenger of the aircraft twin per the multiseat "aircraft twins keep their seat-twin
    // passengers" design) to find the real gunner PLAYER three hops down, and handed that player
    // to the AIRCRAFT's own EntityHandle.setHostRider - corrupting the real MCHeli aircraft's own
    // field_70153_n with a rider it was never meant to have, which made
    // EntityHandleImpl.riderOffset() run the AIRCRAFT's own updateRiderPosition (hardcoded to
    // seatsInfo[0], the pilot) and re-mounted the real native player directly onto the aircraft
    // twin. vehicleChainContains had the matching upward-walk version of the same bug. These
    // tests pin the fix: a nested legacy twin (a seat) owns its own rider independently - an
    // ancestor legacy twin (the aircraft) must never see through it.
    //
    // A real ServerLevel entity graph is not available headlessly (R8); Unsafe-allocated
    // instances with only the two fields these methods actually read (`vehicle`, `passengers`)
    // reflectively seeded is the same idiom UmbLegacyPartTwinTest already established for this
    // exact class of test.

    private static void seedVehicle(Entity child, Entity vehicle) throws Exception {
        java.lang.reflect.Field f = Entity.class.getDeclaredField("vehicle");
        f.setAccessible(true);
        f.set(child, vehicle);
    }

    private static void seedPassengers(Entity parent, Entity... passengers) throws Exception {
        java.lang.reflect.Field f = Entity.class.getDeclaredField("passengers");
        f.setAccessible(true);
        f.set(parent, com.google.common.collect.ImmutableList.copyOf(passengers));
    }

    private static java.util.Set<Entity> identitySet() {
        return java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    }

    @Test
    void findServerPassengerDoesNotSeeThroughANestedLegacyTwin() throws Exception {
        UmbLegacyEntity aircraftTwin = TestSupport.allocate(UmbLegacyEntity.class);
        UmbLegacyEntity seatTwin = TestSupport.allocate(UmbLegacyEntity.class);
        net.minecraft.server.level.ServerPlayer gunner =
                TestSupport.allocate(net.minecraft.server.level.ServerPlayer.class);
        seedPassengers(aircraftTwin, seatTwin);
        seedPassengers(seatTwin, gunner);
        seedVehicle(seatTwin, aircraftTwin);
        seedVehicle(gunner, seatTwin);

        assertNull(HostWorldImpl.findServerPassenger(aircraftTwin, identitySet(), 0),
                "the aircraft must not discover the gunner riding its child seat twin as its own direct rider");
        assertSame(gunner, HostWorldImpl.findServerPassenger(seatTwin, identitySet(), 0),
                "the seat's own search must still find its own direct rider");
    }

    @Test
    void findServerPassengerStillWalksThroughANonLegacyTwinPassthroughEntity() throws Exception {
        // The recursion must stay intact for genuinely non-legacy-twin helper entities (the case
        // it originally existed for) - only a nested UmbLegacyEntity is a new stop boundary.
        UmbLegacyEntity ancestorTwin = TestSupport.allocate(UmbLegacyEntity.class);
        UmbLegacyPartTwin passthroughHelper = TestSupport.allocate(UmbLegacyPartTwin.class);
        net.minecraft.server.level.ServerPlayer rider =
                TestSupport.allocate(net.minecraft.server.level.ServerPlayer.class);
        seedPassengers(ancestorTwin, passthroughHelper);
        seedPassengers(passthroughHelper, rider);

        assertSame(rider, HostWorldImpl.findServerPassenger(ancestorTwin, identitySet(), 0),
                "a non-legacy-twin passthrough entity must still be walked through, unchanged");
    }

    @Test
    void vehicleChainContainsStopsAtANestedLegacyTwinBoundary() throws Exception {
        UmbLegacyEntity aircraftTwin = TestSupport.allocate(UmbLegacyEntity.class);
        UmbLegacyEntity seatTwin = TestSupport.allocate(UmbLegacyEntity.class);
        seedVehicle(seatTwin, aircraftTwin);

        assertFalse(HostWorldImpl.vehicleChainContains(seatTwin, aircraftTwin),
                "a rider whose vehicle is the SEAT must not be attributed to the aircraft, even"
                        + " though the seat natively rides the aircraft too");
        assertTrue(HostWorldImpl.vehicleChainContains(seatTwin, seatTwin),
                "a chain trivially contains its own starting root");
    }
}
