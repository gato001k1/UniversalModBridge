package dev.umb.hostagent.content;

import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ENTITY-BRIDGE headless gate: the opaque legacy NBT blob round-trips through 26.2's real
 * ValueOutput/ValueInput exactly like {@code UmbLegacyBlockEntity} already does, and the
 * host-vs-legacy removal-propagation decision is a pure, directly-testable predicate.
 *
 * {@code Entity}'s constructor requires a live {@code Level} - not constructible headlessly (R8;
 * same gap {@code HostWorldImplTest}'s own javadoc already documents for {@code ServerLevel}). This
 * suite therefore uses {@code TestSupport.allocate} (Unsafe, no constructor run - the SAME idiom
 * the legacy facades use for {@code UmbWorld}/{@code UmbPlayer}) for every test that does not
 * itself need a live {@code Level}, and documents by omission which parts of
 * {@link UmbLegacyEntity} (baseTick's live per-tick sync, onRemoval's real dispatch,
 * {@code HostWorldImpl.spawnEntity}'s full construction path) are consequently UNPROVEN here.
 */
class UmbLegacyEntityTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
    }

    private static UmbLegacyEntity newEntity() {
        return TestSupport.allocate(UmbLegacyEntity.class);
    }

    private static ValueOutput freshOutput() {
        return TagValueOutput.createWithoutContext(ProblemReporter.DISCARDING);
    }

    private static ValueInput inputFrom(CompoundTag tag) {
        return TagValueInput.create(ProblemReporter.DISCARDING, RegistryAccess.EMPTY, tag);
    }

    @Test
    void nbtBlobRoundTripsThroughValueOutputAndValueInput() {
        UmbLegacyEntity e = newEntity();
        FakeLegacyBridge.FakeEntityHandle handle = new FakeLegacyBridge.FakeEntityHandle("umbtest:fixture", 1, 2, 3);
        handle.saveNbtResult = "hello-entity-blob".getBytes(StandardCharsets.UTF_8);
        e.setHandleForTest(handle);

        ValueOutput out = freshOutput();
        e.addAdditionalSaveData(out);
        CompoundTag tag = ((TagValueOutput) out).buildResult();

        UmbLegacyEntity fresh = newEntity();
        fresh.readAdditionalSaveData(inputFrom(tag));

        byte[] pending = fresh.pendingNbtForTest();
        org.junit.jupiter.api.Assertions.assertNotNull(pending);
        org.junit.jupiter.api.Assertions.assertEquals("hello-entity-blob", new String(pending, StandardCharsets.UTF_8));
    }

    @Test
    void noBlobMeansNothingIsWrittenAndPendingStaysAbsent() {
        UmbLegacyEntity e = newEntity();
        ValueOutput out = freshOutput();
        e.addAdditionalSaveData(out);
        CompoundTag tag = ((TagValueOutput) out).buildResult();
        assertTrue(tag.getString("umb_legacy_nbt").isEmpty());

        UmbLegacyEntity fresh = newEntity();
        fresh.readAdditionalSaveData(inputFrom(tag));
        org.junit.jupiter.api.Assertions.assertNull(fresh.pendingNbtForTest());
    }

    @Test
    void readAdditionalSaveDataDoesNotOverwriteAnAlreadyLiveHandle() {
        // If a handle already exists (a freshly spawned twin, never persisted-and-reloaded), a
        // later NBT read must not clobber it - the live handle is the source of truth, per
        // EntityHandle's own javadoc (no loadNbt exists on the contract for exactly this reason).
        UmbLegacyEntity e = newEntity();
        FakeLegacyBridge.FakeEntityHandle liveHandle = new FakeLegacyBridge.FakeEntityHandle("umbtest:fixture", 0, 0, 0);
        e.setHandleForTest(liveHandle);

        CompoundTag tag = new CompoundTag();
        tag.putString("umb_legacy_nbt", Base64.getEncoder().encodeToString("ignored".getBytes(StandardCharsets.UTF_8)));
        e.readAdditionalSaveData(inputFrom(tag));

        assertTrue(e.handleForTest() == liveHandle);
        org.junit.jupiter.api.Assertions.assertNull(e.pendingNbtForTest());
    }

    @Test
    void ensureHandleDoesNothingWithoutALiveServerLevel() {
        // Unsafe-allocated: the `level` field is null, so level() instanceof ServerLevel is false
        // (instanceof on null never throws) - this is the exact "not ready yet" degrade path a
        // reloaded-from-disk twin takes before the universe has booted (ENTITY-LANE.md).
        UmbLegacyEntity e = newEntity();
        // no handle set, no pendingNbt set - ensureHandle must simply return
        e.ensureHandleForTest();
        assertFalse(e.isPoisoned());
        org.junit.jupiter.api.Assertions.assertNull(e.handleForTest());
    }

    @Test
    void dimensionsFollowLegacyHandle() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:fixture", 0, 0, 0);
        handle.width = 2.75F;
        handle.height = 1.25F;

        EntityDimensions dimensions = UmbLegacyEntity.dimensionsFor(handle);

        org.junit.jupiter.api.Assertions.assertNotNull(dimensions);
        org.junit.jupiter.api.Assertions.assertEquals(2.75F, dimensions.width());
        org.junit.jupiter.api.Assertions.assertEquals(1.25F, dimensions.height());
    }

    @Test
    void collisionBoundsFollowLegacyWorldSpaceBox() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.collidable = true;
        handle.boundingBox = new double[] { 10.25, 20.5, 30.75, 12.5, 22.0, 34.0 };

        AABB bounds = UmbLegacyEntity.boundsFor(handle);

        org.junit.jupiter.api.Assertions.assertNotNull(bounds);
        org.junit.jupiter.api.Assertions.assertEquals(10.25, bounds.minX);
        org.junit.jupiter.api.Assertions.assertEquals(20.5, bounds.minY);
        org.junit.jupiter.api.Assertions.assertEquals(30.75, bounds.minZ);
        org.junit.jupiter.api.Assertions.assertEquals(12.5, bounds.maxX);
        org.junit.jupiter.api.Assertions.assertEquals(22.0, bounds.maxY);
        org.junit.jupiter.api.Assertions.assertEquals(34.0, bounds.maxZ);
    }

    @Test
    void malformedCollisionBoundsAreIgnored() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.boundingBox = new double[] { 1, 2, 3, Double.NaN, 5, 6 };

        org.junit.jupiter.api.Assertions.assertNull(UmbLegacyEntity.boundsFor(handle));
    }

    @Test
    void parentKeepsBaseBoxDespiteExtras() {
        // Extras never union into the parent (a union swallows the aim ray for
        // interior seats - the picker takes the nearest box). The parent keeps its base box;
        // extras reconcile into UmbLegacyPartTwin children instead.
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.boundingBox = new double[] { 0, 0, 0, 2, 1, 2 };
        handle.collisionBoxes = java.util.Collections.singletonList(
                new double[] { 10, 0, 10, 26, 4, 12 });

        UmbLegacyEntity twin = newEntity();
        twin.setHandleForTest(handle);
        twin.applyLegacyBounds(handle);

        AABB bounds = twin.getBoundingBox();
        org.junit.jupiter.api.Assertions.assertEquals(0, bounds.minX);
        org.junit.jupiter.api.Assertions.assertEquals(0, bounds.minY);
        org.junit.jupiter.api.Assertions.assertEquals(0, bounds.minZ);
        org.junit.jupiter.api.Assertions.assertEquals(2, bounds.maxX);
        org.junit.jupiter.api.Assertions.assertEquals(1, bounds.maxY);
        org.junit.jupiter.api.Assertions.assertEquals(2, bounds.maxZ);
    }

    @Test
    void parentKeepsBaseBoxWithoutExtras() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.boundingBox = new double[] { 10.25, 20.5, 30.75, 12.5, 22.0, 34.0 };
        handle.collisionBoxes = null;

        AABB bounds = UmbLegacyEntity.boundsFor(handle);

        org.junit.jupiter.api.Assertions.assertNotNull(bounds);
        org.junit.jupiter.api.Assertions.assertEquals(10.25, bounds.minX);
        org.junit.jupiter.api.Assertions.assertEquals(12.5, bounds.maxX);
    }

    @Test
    void malformedExtrasNeverReachTheParentBox() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.boundingBox = new double[] { 0, 0, 0, 2, 1, 2 };
        handle.collisionBoxes = java.util.Arrays.asList(
                new double[] { 1, 2, 3, Double.NaN, 5, 6 },
                new double[] { 5, 5, 5, 4, 4, 4 },
                new double[] { 10, 0, 10, 26, 4, 12 });

        UmbLegacyEntity twin = newEntity();
        twin.setHandleForTest(handle);
        twin.applyLegacyBounds(handle);

        AABB bounds = twin.getBoundingBox();
        org.junit.jupiter.api.Assertions.assertEquals(2, bounds.maxX);
        org.junit.jupiter.api.Assertions.assertEquals(1, bounds.maxY);
        org.junit.jupiter.api.Assertions.assertEquals(2, bounds.maxZ);
    }

    @Test
    void absentBaseBoxStaysAbsent() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.boundingBox = null;
        handle.collisionBoxes = java.util.Collections.singletonList(
                new double[] { 10, 0, 10, 26, 4, 12 });

        org.junit.jupiter.api.Assertions.assertNull(UmbLegacyEntity.boundsFor(handle));
    }

    @Test
    void nullBaseBoxIsNull() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.boundingBox = null;
        handle.collisionBoxes = null;

        org.junit.jupiter.api.Assertions.assertNull(UmbLegacyEntity.boundsFor(handle));
    }

    @Test
    void clientBoxCodecRoundTrips() {
        float[] encoded = UmbLegacyEntity.boxOffsets(
                new double[] {-8.4, 91.1, -8.4, -6.6, 91.6, -6.6}, -7.5, 91.35, -7.5);

        org.junit.jupiter.api.Assertions.assertNotNull(encoded);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new float[] {-0.9F, -0.25F, -0.9F, 1.8F, 0.5F, 1.8F}, encoded, 0.001F);

        double[] rebuilt = UmbLegacyEntity.rebuildBox(-7.5, 91.35, -7.5,
                new org.joml.Vector3f(encoded[0], encoded[1], encoded[2]),
                new org.joml.Vector3f(encoded[3], encoded[4], encoded[5]));

        org.junit.jupiter.api.Assertions.assertNotNull(rebuilt);
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new double[] {-8.4, 91.1, -8.4, -6.6, 91.6, -6.6}, rebuilt, 0.01);
    }

    @Test
    void clientBoxCodecRejectsGarbage() {
        org.junit.jupiter.api.Assertions.assertNull(UmbLegacyEntity.boxOffsets(null, 0, 0, 0));
        org.junit.jupiter.api.Assertions.assertNull(UmbLegacyEntity.boxOffsets(
                new double[] {0, 0, 0, Double.NaN, 1, 1}, 0, 0, 0));
        org.junit.jupiter.api.Assertions.assertNull(UmbLegacyEntity.boxOffsets(
                new double[] {0, 0, 0, 0, 1, 1}, 0, 0, 0));
        org.junit.jupiter.api.Assertions.assertNull(UmbLegacyEntity.rebuildBox(0, 0, 0, null,
                new org.joml.Vector3f(1, 1, 1)));
        org.junit.jupiter.api.Assertions.assertNull(UmbLegacyEntity.rebuildBox(0, 0, 0,
                new org.joml.Vector3f(0, 0, 0), new org.joml.Vector3f(0, 0, 0)));
    }

    @Test
    void partReconcileIsHeadlessSafeWithoutAServerLevel() {
        // Unsafe-allocated twin has a null level: the server-only reconcile must no-op, never
        // throw, and leave no children behind.
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.collisionBoxes = java.util.Collections.singletonList(
                new double[] { 10, 0, 10, 26, 4, 12 });

        UmbLegacyEntity twin = newEntity();
        twin.setHandleForTest(handle);
        twin.syncPartTwins(handle);

        org.junit.jupiter.api.Assertions.assertEquals(0, twin.partTwinCountForTest());
    }

    @Test
    void legacyNullCannotEjectAnExistingHostPassenger() {
        assertTrue(UmbLegacyEntity.shouldPreserveHostPassenger(false, null, 1));
        assertFalse(UmbLegacyEntity.shouldPreserveHostPassenger(false, null, 0));
        assertFalse(UmbLegacyEntity.shouldPreserveHostPassenger(false, "Pilot", 1));
        assertFalse(UmbLegacyEntity.shouldPreserveHostPassenger(true, null, 1));
    }

    @Test
    void riderIdentityWinsOverFormattedScoreboardName() {
        assertTrue(UmbLegacyEntity.matchesLegacyRider("uuid-7", "[red]Pilot", "uuid-7", "Pilot"));
        assertTrue(UmbLegacyEntity.matchesLegacyRider("uuid-7", "Pilot", "uuid-8", "Pilot"));
        assertFalse(UmbLegacyEntity.matchesLegacyRider("uuid-7", "Pilot", "uuid-8", "Other"));
        assertTrue(UmbLegacyEntity.matchesLegacyRider(null, "Pilot", "uuid-8", "Pilot"));
    }

    // ---------------------------------------------------------------- shouldPropagateRemoval (pure predicate)

    @Test
    void propagatesOnHostKilled() {
        assertTrue(UmbLegacyEntity.shouldPropagateRemoval(false, Entity.RemovalReason.KILLED));
    }

    @Test
    void propagatesOnHostDiscarded() {
        assertTrue(UmbLegacyEntity.shouldPropagateRemoval(false, Entity.RemovalReason.DISCARDED));
    }

    @Test
    void doesNotPropagateOnChunkUnload() {
        assertFalse(UmbLegacyEntity.shouldPropagateRemoval(false, Entity.RemovalReason.UNLOADED_TO_CHUNK));
    }

    @Test
    void doesNotPropagateOnPlayerUnload() {
        assertFalse(UmbLegacyEntity.shouldPropagateRemoval(false, Entity.RemovalReason.UNLOADED_WITH_PLAYER));
    }

    @Test
    void doesNotPropagateOnDimensionChange() {
        assertFalse(UmbLegacyEntity.shouldPropagateRemoval(false, Entity.RemovalReason.CHANGED_DIMENSION));
    }

    @Test
    void neverPropagatesWhenLegacyAlreadyInitiatedTheRemoval() {
        // even a genuine KILLED/DISCARDED reason must not propagate back if the removal itself
        // originated from the legacy entity already being dead (UmbLegacyEntity.baseTick's own
        // discard() call) - see the flag's javadoc for why this ordering matters.
        assertFalse(UmbLegacyEntity.shouldPropagateRemoval(true, Entity.RemovalReason.KILLED));
        assertFalse(UmbLegacyEntity.shouldPropagateRemoval(true, Entity.RemovalReason.DISCARDED));
    }

    // ------------------------------------------------------- shouldPropagateHostDismount (pure predicate)

    @Test
    void hostDismountPropagatesOnlyForLiveServerPlayerRemovals() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        HostWorldImpl world = new HostWorldImpl(null);
        net.minecraft.server.level.ServerPlayer player =
                TestSupport.allocate(net.minecraft.server.level.ServerPlayer.class);

        assertTrue(UmbLegacyEntity.shouldPropagateHostDismount(false, player, false, handle, world));
        // The client twin mirrors removals from SetPassengers packets and owns no legacy state.
        assertFalse(UmbLegacyEntity.shouldPropagateHostDismount(true, player, false, handle, world));
        // A twin being discarded keeps its legacy lifecycle on the onRemoval/hostRemoved path.
        assertFalse(UmbLegacyEntity.shouldPropagateHostDismount(false, player, true, handle, world));
        // No passenger (or a non-player seat/part removal) carries no host rider to propagate.
        assertFalse(UmbLegacyEntity.shouldPropagateHostDismount(false, null, false, handle, world));
        assertFalse(UmbLegacyEntity.shouldPropagateHostDismount(false,
                TestSupport.allocate(UmbLegacyEntity.class), false, handle, world));
        // Nothing to propagate to.
        assertFalse(UmbLegacyEntity.shouldPropagateHostDismount(false, player, false, null, world));
        assertFalse(UmbLegacyEntity.shouldPropagateHostDismount(false, player, false, handle, null));
    }

    // ---------------------------------------------------------------- client interpolation glide

    @Test
    void clientTwinOptsIntoMovePacketInterpolation() {
        // A raw Entity's getInterpolation() is null (javap-verified), so every server move
        // packet snaps the client twin with no glide; the generic twin must return the same
        // handler on every call so packet targets and tick pumps never split a glide in two.
        UmbLegacyEntity e = newEntity();
        Object first = e.getInterpolation();
        org.junit.jupiter.api.Assertions.assertNotNull(first,
                "moveOrInterpolateTo glides only when getInterpolation() is non-null");
        org.junit.jupiter.api.Assertions.assertSame(first, e.getInterpolation());
    }
}
