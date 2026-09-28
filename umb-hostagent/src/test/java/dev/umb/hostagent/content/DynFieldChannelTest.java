package dev.umb.hostagent.content;

import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.TileFieldSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityTypes;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Moving-parts lane: per-block dynamic field sets come from the extract-time sidecar, the
 * server tick publishes changed snapshots to the frame channel, and removal clears them.
 */
class DynFieldChannelTest {

    private static final String SIDECAR = """
            {"schema":"umb.renderer-dynamic-ops.v1",
             "bounds":{},
             "renderers":{},
             "blocks":{
               "hbm:tile.machine_radar":{
                 "teClass":"com.hbm.tileentity.machine.TileEntityMachineRadarNT",
                 "fields":[
                   {"key":"TileEntityMachineRadarNT.prevRotation","hops":["prevRotation"]},
                   {"key":"TileEntityMachineRadarNT.rotation","hops":["rotation"]}]}}}""";

    @BeforeAll
    static void boot() {
        // Fresh UmbLegacyBlock instances need unfrozen registries (same as UmbLegacyBlockTest).
        TestSupport.ensureBootstrappedWithoutFreezing();
    }

    @BeforeEach
    void reset() {
        DynFieldChannel.resetForTests();
    }

    private static Path writeSidecar(Path dir) throws Exception {
        Path p = dir.resolve("renderer-dynamic-ops.json");
        Files.writeString(p, SIDECAR, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void sidecarParsesToFieldPaths(@TempDir Path tmp) throws Exception {
        DynFieldChannel.setSidecarForTests(writeSidecar(tmp));
        List<DynFieldChannel.FieldSpec> specs =
                DynFieldChannel.fieldsFor("hbm:tile.machine_radar");
        assertEquals(2, specs.size());
        assertEquals("TileEntityMachineRadarNT.rotation", specs.get(1).key);
        FieldPath path = specs.get(1).path;
        assertEquals(1, path.hopNames.length);
        assertEquals("rotation", path.hopNames[0]);
        assertEquals("field", path.hopKinds[0]);
        assertTrue(DynFieldChannel.fieldsFor("hbm:nope").isEmpty());
        assertTrue(DynFieldChannel.fieldsFor(null).isEmpty());
    }

    @Test
    void absentSidecarMeansNoFields(@TempDir Path tmp) throws Exception {
        Path empty = tmp.resolve("renderer-dynamic-ops.json");
        Files.writeString(empty, "{}", StandardCharsets.UTF_8);
        DynFieldChannel.setSidecarForTests(empty);
        assertTrue(DynFieldChannel.fieldsFor("hbm:tile.machine_radar").isEmpty());
    }

    @Test
    void liveBoundsFindsRepositorySidecarBeforeUmbRepoIsInstalled() {
        String oldRepo = System.getProperty("umb.repo");
        String oldSidecar = System.getProperty("umb.legacy.dynamicsSidecar");
        try {
            System.clearProperty("umb.repo");
            System.clearProperty("umb.legacy.dynamicsSidecar");
            DynLiveBounds.resetForTests();
            assertTrue(DynLiveBounds.isLiveBounds("hbm:tile.sliding_blast_door"),
                    "registry-time sidecar lookup must not depend on lazy UmbUniverse boot");
        } finally {
            if (oldRepo == null) System.clearProperty("umb.repo");
            else System.setProperty("umb.repo", oldRepo);
            if (oldSidecar == null) System.clearProperty("umb.legacy.dynamicsSidecar");
            else System.setProperty("umb.legacy.dynamicsSidecar", oldSidecar);
            DynLiveBounds.resetForTests();
        }
    }

    @Test
    void sameSnapshotComparesKeysPresenceAndValues() {
        TileFieldSnapshot a = new TileFieldSnapshot(
                new String[]{"k"}, new double[]{1.0}, new boolean[]{true});
        TileFieldSnapshot b = new TileFieldSnapshot(
                new String[]{"k"}, new double[]{1.0}, new boolean[]{true});
        TileFieldSnapshot c = new TileFieldSnapshot(
                new String[]{"k"}, new double[]{2.0}, new boolean[]{true});
        TileFieldSnapshot d = new TileFieldSnapshot(
                new String[]{"k"}, new double[]{0.0}, new boolean[]{false});
        assertTrue(DynFieldChannel.sameSnapshot(a, b));
        assertFalse(DynFieldChannel.sameSnapshot(a, c));
        assertFalse(DynFieldChannel.sameSnapshot(a, d));
        assertFalse(DynFieldChannel.sameSnapshot(a, TileFieldSnapshot.EMPTY));
    }

    @Test
    void publishGetClearRoundTrip() {
        BlockPos pos = new BlockPos(1, 2, 3);
        assertTrue(DynFieldChannel.get(pos) == TileFieldSnapshot.EMPTY);
        TileFieldSnapshot s = new TileFieldSnapshot(
                new String[]{"k"}, new double[]{5.0}, new boolean[]{true});
        DynFieldChannel.publish(pos, s);
        assertTrue(DynFieldChannel.get(pos) == s);
        DynFieldChannel.clear(pos);
        assertTrue(DynFieldChannel.get(pos) == TileFieldSnapshot.EMPTY);
        DynFieldChannel.publish(pos, null);
        assertTrue(DynFieldChannel.get(pos) == TileFieldSnapshot.EMPTY);
    }

    private static UmbLegacyBlockEntity radarEntity() {
        return entityForBlock("hbm:tile.machine_radar");
    }

    private static UmbLegacyBlockEntity entityForBlock(String blockId) {
        BlockRec rec = new BlockRec();
        rec.id = blockId;
        rec.hasTileEntity = true;
        ResourceKey<net.minecraft.world.level.block.Block> key =
                ResourceKey.create(Registries.BLOCK, Identifier.parse(blockId));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
        UmbLegacyBlock block = new UmbLegacyBlock(props, rec);
        BlockState state = block.defaultBlockState();
        @SuppressWarnings("unchecked")
        BlockEntityType<UmbLegacyBlockEntity>[] holder = new BlockEntityType[1];
        BlockEntityType<UmbLegacyBlockEntity> type = new BlockEntityType<>(
                (pos, st) -> new UmbLegacyBlockEntity(holder[0], pos, st), java.util.Set.of(block));
        holder[0] = type;
        return new UmbLegacyBlockEntity(type, BlockPos.ZERO, state);
    }

    @Test
    void serverTickPublishesChangedFieldsAndClearsOnRemove(@TempDir Path tmp) throws Exception {
        DynFieldChannel.setSidecarForTests(writeSidecar(tmp));
        UmbLegacyBlockEntity be = radarEntity();
        FakeLegacyBridge.FakeTileHandle handle =
                new FakeLegacyBridge.FakeTileHandle("hbm:tile.machine_radar", 0, 0, 0);
        handle.fields.put("TileEntityMachineRadarNT.rotation", 10.0);
        // prevRotation absent from the fake map: honest present=false, never a zero.
        be.setHandleForTest(handle);

        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, be.getBlockState(), be);
        TileFieldSnapshot s = DynFieldChannel.get(BlockPos.ZERO);
        assertEquals(2, s.keys.length);
        assertEquals("TileEntityMachineRadarNT.rotation", s.keys[1]);
        assertTrue(s.present[1]);
        assertEquals(10.0, s.values[1]);
        assertFalse(s.present[0]);

        // Unchanged values republish nothing (same object stays in the channel).
        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, be.getBlockState(), be);
        assertTrue(DynFieldChannel.get(BlockPos.ZERO) == s);

        // Changed values publish a new snapshot.
        handle.fields.put("TileEntityMachineRadarNT.rotation", 11.0);
        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, be.getBlockState(), be);
        TileFieldSnapshot s2 = DynFieldChannel.get(BlockPos.ZERO);
        assertTrue(s2 != s);
        assertEquals(11.0, s2.values[1]);

        // Removal clears instead of freezing the last pose.
        be.setRemoved();
        assertTrue(DynFieldChannel.get(BlockPos.ZERO) == TileFieldSnapshot.EMPTY);
    }

    @Test
    void blocksWithoutSidecarEntriesSyncNothing(@TempDir Path tmp) throws Exception {
        DynFieldChannel.setSidecarForTests(writeSidecar(tmp));
        UmbLegacyBlockEntity be = entityForBlock("hbm:tile.stone");
        FakeLegacyBridge.FakeTileHandle handle =
                new FakeLegacyBridge.FakeTileHandle("hbm:tile.stone", 0, 0, 0);
        be.setHandleForTest(handle);
        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, be.getBlockState(), be);
        assertTrue(DynFieldChannel.get(BlockPos.ZERO) == TileFieldSnapshot.EMPTY);
    }

    private static final String CHANNEL_SIDECAR = """
            {"schema":"umb.renderer-dynamic-ops.v1",
             "bounds":{},
             "renderers":{},
             "blocks":{
               "hbm:tile.large_vehicle_door":{
                 "teClass":"com.hbm.tileentity.TileEntityDoorGeneric",
                 "fields":[],
                 "channels":[
                   {"key":"com.hbm.render.tileentity.door.IRenderDoors.getRelevantTransformation.DOOR[1]",
                    "staticOwner":"com.hbm.render.tileentity.door.IRenderDoors",
                    "staticMethod":"getRelevantTransformation",
                    "stringArg":"DOOR",
                    "animHops":["currentAnimation"]}]}}}""";

    private static Path writeChannelSidecar(Path dir) throws Exception {
        Path p = dir.resolve("renderer-dynamic-ops.json");
        Files.writeString(p, CHANNEL_SIDECAR, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void sidecarParsesToChannelSpecs(@TempDir Path tmp) throws Exception {
        DynFieldChannel.setSidecarForTests(writeChannelSidecar(tmp));
        List<DynFieldChannel.ChannelSpec> specs =
                DynFieldChannel.channelsFor("hbm:tile.large_vehicle_door");
        assertEquals(1, specs.size());
        DynFieldChannel.ChannelSpec c = specs.get(0);
        assertEquals("com.hbm.render.tileentity.door.IRenderDoors.getRelevantTransformation.DOOR[1]",
                c.key);
        assertEquals(1, c.index);
        assertEquals(1, c.objectPath.hopNames.length);
        assertEquals("currentAnimation", c.objectPath.hopNames[0]);
        assertTrue(DynFieldChannel.channelsFor("hbm:nope").isEmpty());
        assertTrue(DynFieldChannel.channelsFor(null).isEmpty());
        // Blocks without channels still parse their fields.
        DynFieldChannel.setSidecarForTests(writeSidecar(tmp));
        assertTrue(DynFieldChannel.channelsFor("hbm:tile.machine_radar").isEmpty());
        assertEquals(2, DynFieldChannel.fieldsFor("hbm:tile.machine_radar").size());
    }

    @Test
    void serverTickPublishesEvaluatedChannelValues(@TempDir Path tmp) throws Exception {        DynFieldChannel.setSidecarForTests(writeChannelSidecar(tmp));
        UmbLegacyBlockEntity be = entityForBlock("hbm:tile.large_vehicle_door");
        FakeLegacyBridge.FakeTileHandle handle =
                new FakeLegacyBridge.FakeTileHandle("hbm:tile.large_vehicle_door", 0, 0, 0);
        handle.evalTrack = new double[]{0.0, 2.5};
        be.setHandleForTest(handle);

        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, be.getBlockState(), be);
        TileFieldSnapshot s = DynFieldChannel.get(BlockPos.ZERO);
        assertEquals(1, s.keys.length);
        assertEquals("com.hbm.render.tileentity.door.IRenderDoors.getRelevantTransformation.DOOR[1]",
                s.keys[0]);
        assertTrue(s.present[0]);
        assertEquals(2.5, s.values[0]);
        assertEquals(1, handle.evalCalls);

        // A changed track republishes; an unchanged one does not.
        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, be.getBlockState(), be);
        assertTrue(DynFieldChannel.get(BlockPos.ZERO) == s);
        handle.evalTrack = new double[]{0.0, 2.75};
        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, be.getBlockState(), be);
        TileFieldSnapshot s2 = DynFieldChannel.get(BlockPos.ZERO);
        assertTrue(s2 != s);
        assertEquals(2.75, s2.values[0]);

        // A null track (evaluator missing) leaves the index absent, never a zero.
        handle.evalTrack = null;
        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, be.getBlockState(), be);
        TileFieldSnapshot s3 = DynFieldChannel.get(BlockPos.ZERO);
        assertEquals(1, s3.keys.length);
        assertFalse(s3.present[0]);
    }

    private static final String DISPATCH_SIDECAR = """
            {"schema":"umb.renderer-dynamic-ops.v1",
             "bounds":{},
             "renderers":{},
             "blocks":{
               "hbm:tile.large_vehicle_door":{
                 "teClass":"com.hbm.tileentity.TileEntityDoorGeneric",
                 "fields":[],
                 "dispatch":{"owner":"com.hbm.tileentity.DoorDecl",
                             "method":"getSEDNARenderer",
                             "objectHops":["getDoorType"],
                             "objectKinds":["accessor"]},
                 "channels":[
                   {"key":"com.hbm.render.tileentity.door.IRenderDoors.getRelevantTransformation.DOOR[1]",
                    "staticOwner":"com.hbm.render.tileentity.door.IRenderDoors",
                    "staticMethod":"getRelevantTransformation",
                    "stringArg":"DOOR",
                    "animHops":["currentAnimation"],
                    "anim":{"providerOwner":"com.hbm.tileentity.DoorDecl",
                            "providerMethod":"getSEDNAAnim",
                            "receiverHops":["doorType"],
                            "receiverKinds":["field"],
                            "argHops":[["state"],["skinIndex"]],
                            "argKinds":[["field"],["field"]],
                            "clockOwner":"com.hbm.util.Clock",
                            "clockMethod":"get_ms",
                            "clockField":"startMillis"}}]}}}""";

    private static Path writeDispatchSidecar(Path dir) throws Exception {
        Path p = dir.resolve("renderer-dynamic-ops.json");
        Files.writeString(p, DISPATCH_SIDECAR, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void sidecarParsesDispatchAndAnimRecipe(@TempDir Path tmp) throws Exception {
        DynFieldChannel.setSidecarForTests(writeDispatchSidecar(tmp));
        DynFieldChannel.DispatchSpec d =
                DynFieldChannel.dispatchFor("hbm:tile.large_vehicle_door");
        assertNotNull(d);
        assertEquals("com.hbm.tileentity.DoorDecl", d.owner);
        assertEquals("getSEDNARenderer", d.method);
        assertEquals(1, d.objectPath.hopNames.length);
        assertEquals("getDoorType", d.objectPath.hopNames[0]);
        assertEquals("accessor", d.objectPath.hopKinds[0]);
        assertNull(DynFieldChannel.dispatchFor("hbm:nope"));
        assertNull(DynFieldChannel.dispatchFor(null));

        List<DynFieldChannel.ChannelSpec> specs =
                DynFieldChannel.channelsFor("hbm:tile.large_vehicle_door");
        assertEquals(1, specs.size());
        DynFieldChannel.AnimSpec a = specs.get(0).anim;
        assertNotNull(a);
        assertEquals("com.hbm.tileentity.DoorDecl", a.providerOwner);
        assertEquals("getSEDNAAnim", a.providerMethod);
        assertEquals(1, a.providerReceiver.hopNames.length);
        assertEquals("doorType", a.providerReceiver.hopNames[0]);
        assertEquals(2, a.providerArgs.length);
        assertEquals("state", a.providerArgs[0].hopNames[0]);
        assertEquals("skinIndex", a.providerArgs[1].hopNames[0]);
        assertEquals("com.hbm.util.Clock", a.clockOwner);
        assertEquals("get_ms", a.clockMethod);
        assertEquals("startMillis", a.clockField);
    }

    @Test
    void serverTickRoutesAnimChannelsToEvalAnim(@TempDir Path tmp) throws Exception {
        DynFieldChannel.setSidecarForTests(writeDispatchSidecar(tmp));
        UmbLegacyBlockEntity be = entityForBlock("hbm:tile.large_vehicle_door");
        FakeLegacyBridge.FakeTileHandle handle =
                new FakeLegacyBridge.FakeTileHandle("hbm:tile.large_vehicle_door", 0, 0, 0);
        handle.evalAnimTrack = new double[]{0.0, 1.5};
        be.setHandleForTest(handle);

        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, be.getBlockState(), be);
        TileFieldSnapshot s = DynFieldChannel.get(BlockPos.ZERO);
        assertEquals(1, s.keys.length);
        assertTrue(s.present[0]);
        assertEquals(1.5, s.values[0]);
        assertEquals(1, handle.evalAnimCalls);
        assertEquals(0, handle.evalCalls);

        // A null anim track leaves the index absent, never a zero.
        handle.evalAnimTrack = null;
        UmbLegacyBlockEntity.serverTick(null, BlockPos.ZERO, be.getBlockState(), be);
        assertFalse(DynFieldChannel.get(BlockPos.ZERO).present[0]);
    }

    private static final String TE_CLASS_SIDECAR = """
            {"schema":"umb.renderer-dynamic-ops.v1",
             "bounds":{},
             "renderers":{},
             "blocks":{
               "hbm:tile.turret_chekhov":{
                 "teClass":"com.hbm.tileentity.turret.TileEntityTurretChekhov",
                 "fields":[
                   {"key":"TileEntityTurretChekhov.rotationYaw","hops":["rotationYaw"]}],
                 "channels":[]}}}""";

    private static Path writeTeClassSidecar(Path dir) throws Exception {
        Path p = dir.resolve("renderer-dynamic-ops.json");
        Files.writeString(p, TE_CLASS_SIDECAR, StandardCharsets.UTF_8);
        return p;
    }

    @Test
    void teClassParsesPerBlock(@TempDir Path tmp) throws Exception {
        DynFieldChannel.setSidecarForTests(writeTeClassSidecar(tmp));
        assertEquals("com.hbm.tileentity.turret.TileEntityTurretChekhov",
                DynFieldChannel.teClassFor("hbm:tile.turret_chekhov"));
        assertNull(DynFieldChannel.teClassFor("hbm:nope"));
        assertNull(DynFieldChannel.teClassFor(null));
    }

    @Test
    void coreRequiresRenderedTileClass(@TempDir Path tmp) throws Exception {
        DynFieldChannel.setSidecarForTests(writeTeClassSidecar(tmp));
        UmbLegacyBlockEntity be = entityForBlock("hbm:tile.turret_chekhov");
        FakeLegacyBridge.FakeTileHandle handle =
                new FakeLegacyBridge.FakeTileHandle("hbm:tile.turret_chekhov", 0, 0, 0);
        be.setHandleForTest(handle);
        // No constraint answered (null hook): live handle is core, as before.
        assertTrue(be.isLegacyCore());
        // Proxy/dummy tile: live handle, but not the rendered class -> not core, so the
        // filler stops double-rendering the model (vanilla never drew it either).
        handle.rendersAsResult = Boolean.FALSE;
        assertFalse(be.isLegacyCore());
        handle.rendersAsResult = Boolean.TRUE;
        assertTrue(be.isLegacyCore());
    }

    @Test
    void coreWithoutSidecarEntryKeepsOldBehavior(@TempDir Path tmp) throws Exception {
        DynFieldChannel.setSidecarForTests(writeSidecar(tmp));
        UmbLegacyBlockEntity be = entityForBlock("hbm:tile.stone");
        FakeLegacyBridge.FakeTileHandle handle =
                new FakeLegacyBridge.FakeTileHandle("hbm:tile.stone", 0, 0, 0);
        be.setHandleForTest(handle);
        handle.rendersAsResult = Boolean.FALSE;
        // No teClass on record: no constraint, live handle stays core.
        assertTrue(be.isLegacyCore());
    }
}
