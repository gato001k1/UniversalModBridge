package dev.umb.hostagent.content;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.hostagent.UmbThread;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 3 gate: UmbLegacyBlock implements EntityBlock unconditionally, gated internally by
 * {@code record.hasTileEntity} -- a twin without a tile entity returns null from
 * newBlockEntity/getTicker (vanilla's own "no block entity for this placement" contract) and
 * falls through to the plain super.useWithoutItem() (== InteractionResult.PASS, javap-verified
 * body of BlockBehaviour's default).
 *
 * A live ServerLevel cannot be constructed headlessly (R8), so getTicker's client/server branch
 * is exercised over an {@code Unsafe.allocateInstance(ServerLevel.class)} whose private final
 * `isClientSide` field is flipped by reflection -- getTicker only ever calls
 * {@code level.isClientSide()}, a trivial field read, so this is a faithful stand-in without
 * needing a real world.
 */
class UmbLegacyBlockTest {

    @BeforeAll
    static void boot() {
        // This class constructs FRESH UmbLegacyBlock/BlockEntityType instances repeatedly, which
        // needs the registries to stay unfrozen (see TestSupport.ensureBootstrappedWithoutFreezing
        // javadoc) -- it therefore runs in its own separate java invocation from the rest of the
        // suite (tools/run-hostagent-tests.ps1), since that mode is incompatible with new
        // ItemStack(...) elsewhere in the process.
        TestSupport.ensureBootstrappedWithoutFreezing();
    }

    private static BlockRec rec(boolean hasTileEntity) {
        BlockRec r = new BlockRec();
        r.id = "hbm:tile.test";
        r.hasTileEntity = hasTileEntity;
        return r;
    }

    private static int nextId = 0;

    private static UmbLegacyBlock block(boolean hasTileEntity) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "b" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
        return new UmbLegacyBlock(props, rec(hasTileEntity));
    }

    private static UmbLegacyBlock tesrBlock() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "tesr" + (nextId++)));
        BlockRec r = rec(true);
        r.opaqueCube = false;
        r.renderAsNormalBlock = false;
        r.renderType = 22;
        return new UmbLegacyBlock(BlockBehaviour.Properties.of().setId(key), r);
    }

    private static ServerLevel fakeLevel(boolean clientSide) throws Exception {
        ServerLevel level = TestSupport.allocate(ServerLevel.class);
        Field f = Level.class.getDeclaredField("isClientSide");
        f.setAccessible(true);
        f.setBoolean(level, clientSide);
        return level;
    }

    @Test
    void newBlockEntityIsNullWithoutHasTileEntity() {
        UmbLegacyBlock b = block(false);
        assertNull(b.newBlockEntity(net.minecraft.core.BlockPos.ZERO, b.defaultBlockState()));
    }

    @Test
    void newBlockEntityIsNullWhenNoTileTypeIsRegisteredYet() {
        Registrar.LEGACY_TILE_TYPE = null;
        UmbLegacyBlock b = block(true);
        assertNull(b.newBlockEntity(net.minecraft.core.BlockPos.ZERO, b.defaultBlockState()));
    }

    @Test
    void newBlockEntityBuildsOneWhenHasTileEntityAndATypeExists() {
        UmbLegacyBlock b = block(true);
        BlockState state = b.defaultBlockState();
        @SuppressWarnings("unchecked")
        BlockEntityType<UmbLegacyBlockEntity>[] holder = new BlockEntityType[1];
        BlockEntityType<UmbLegacyBlockEntity> type = new BlockEntityType<>(
                (pos, st) -> new UmbLegacyBlockEntity(holder[0], pos, st), Set.of(b));
        holder[0] = type;
        Registrar.LEGACY_TILE_TYPE = type;
        try {
            BlockEntity be = b.newBlockEntity(net.minecraft.core.BlockPos.ZERO, state);
            assertInstanceOf(UmbLegacyBlockEntity.class, be);
        } finally {
            Registrar.LEGACY_TILE_TYPE = null;
        }
    }

    @Test
    void tesrChestContractLeavesNeighbourOcclusionEmpty() {
        UmbLegacyBlock b = tesrBlock();

        VoxelShape occlusion = b.getOcclusionShape(b.defaultBlockState());

        assertTrue(occlusion.isEmpty(),
                "a TESR/chest legacy block is not an opaque normal cube, so floor neighbours must not cull faces");
    }

    @Test
    void getTickerIsNullWithoutHasTileEntityRegardlessOfSide() throws Exception {
        UmbLegacyBlock b = block(false);
        BlockEntityType<UmbLegacyBlockEntity> dummyType = new BlockEntityType<>((p, s) -> null, Set.of(b));
        assertNull(b.getTicker(fakeLevel(false), b.defaultBlockState(), dummyType));
        assertNull(b.getTicker(fakeLevel(true), b.defaultBlockState(), dummyType));
    }

    @Test
    void getTickerIsNullOnTheClientAndNonNullOnTheServerWhenHasTileEntity() throws Exception {
        UmbLegacyBlock b = block(true);
        BlockEntityType<UmbLegacyBlockEntity> dummyType = new BlockEntityType<>((p, s) -> null, Set.of(b));
        assertNull(b.getTicker(fakeLevel(true), b.defaultBlockState(), dummyType));
        BlockEntityTicker<UmbLegacyBlockEntity> ticker = b.getTicker(fakeLevel(false), b.defaultBlockState(), dummyType);
        org.junit.jupiter.api.Assertions.assertNotNull(ticker);
    }

    /**
     * M1 visual fix (see g2-integration-progress.md "POST-M1 FIX" / this session's title fix):
     * {@code UmbLegacyBlock.useWithoutItem} builds the menu title from {@code this.getName()},
     * NOT {@code Component.literal(handle.title())} -- the latter is frequently just the legacy
     * container's own raw id text (observed live: "hbm:tile.machine_furnace_brick_off"). This
     * does not call useWithoutItem itself (that needs a live ServerLevel/ServerPlayer past what
     * R8 allows headlessly); it instead pins down the exact resolvable API the fix now relies on:
     * {@code Block.getName() == Component.translatable(getDescriptionId())} (javap-verified), and
     * {@code getDescriptionId()} for a block registered with {@code Properties.setId(key)} and no
     * {@code overrideDescription(...)} is the standard {@code "block.<namespace>.<path>"} --
     * exactly the key PackGen's {@code langOut.put("block." + ns + "." + path, e.displayName)}
     * writes a humanised row for, with no dev.umb.bridge.api contract change and no umb-legacy edit.
     */
    @Test
    void nameResolvesToATranslatableComponentKeyedByItsOwnDescriptionIdNotARawLiteral() {
        UmbLegacyBlock b = block(true);
        Component name = b.getName();

        assertInstanceOf(TranslatableContents.class, name.getContents());
        assertEquals(b.getDescriptionId(), ((TranslatableContents) name.getContents()).getKey());
        // b.getDescriptionId() is "block.umbtest.b<N>" (Properties.setId's default), which is
        // never equal to the legacy container id text ("hbm:tile.test", from rec(true)) the old
        // Component.literal(handle.title()) code path would have shown instead.
        assertNotEquals(rec(true).id, ((TranslatableContents) name.getContents()).getKey());
    }

    @Test
    void useWithoutItemFallsThroughToVanillaSuperWhenThereIsNoLegacyRecordAtAll() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "norecord" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
        UmbLegacyBlock b = new UmbLegacyBlock(props, null);
        // record == null means this instance has no legacy provenance to activate at all -- the
        // ONLY case left that still falls straight to `super.useWithoutItem(...)`, whose javap'd
        // body ignores every argument and returns InteractionResult.PASS unconditionally. BUG-1
        // removed the OTHER early return (!record.hasTileEntity) that used to also take this path.
        InteractionResult r = b.useWithoutItem(b.defaultBlockState(), null, null, null, null);
        assertEquals(InteractionResult.PASS, r);
    }

    // ---- BUG-1/BUG-2/FM-1/E3/left-click: INTERACTION-BRIDGE.md gates ----

    @AfterEach
    void resetBridgeState() throws Exception {
        UmbBridgeHost.resetForTests();
        DynLiveBounds.resetForTests();
        UmbThread.resetForTests();
        Field marker = UmbLegacyBlock.class.getDeclaredField("RECENTLY_DECLINED");
        marker.setAccessible(true);
        ((ThreadLocal<?>) marker.get(null)).remove();
        UmbLegacyBlock.resetReentrancyGuardForTests();
    }

    @Test
    void liveEmptyCollisionBoxesOverrideTheStaticShape() throws Exception {
        Path sidecar = Files.createTempFile("umb-live-bounds", ".json");
        Files.writeString(sidecar,
                "{\"schema\":\"umb.renderer-dynamic-ops.v1\",\"blocks\":{"
                        + "\"hbm:tile.test\":{\"liveBounds\":true}}}",
                StandardCharsets.UTF_8);
        DynLiveBounds.setSidecarForTests(sidecar);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.collisionBoxesResult = java.util.Collections.emptyList();
        UmbBridgeHost.set(bridge);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "live" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().dynamicShape().setId(key);
        UmbLegacyBlock b = new UmbLegacyBlock(props, rec(false), Shapes.block(), Shapes.block());

        VoxelShape collision = b.getCollisionShape(b.defaultBlockState(), fakeLevel(false),
                BlockPos.ZERO, net.minecraft.world.phys.shapes.CollisionContext.empty());
        assertTrue(collision.isEmpty(),
                "an empty live bridge result must not fall back to the cached full-cube shape");
        Files.deleteIfExists(sidecar);
    }

    @Test
    void liveSelectionBoxesOverrideTheStaticOutlineAndMayBeEmpty() throws Exception {
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.selectionBoxesResult = java.util.Collections.singletonList(
                new double[] {0.125, 0.0, 0.125, 0.875, 0.75, 0.875});
        UmbBridgeHost.set(bridge);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "selection" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().dynamicShape().setId(key);
        UmbLegacyBlock b = new UmbLegacyBlock(props, rec(false), Shapes.block(), Shapes.block());

        VoxelShape outline = b.getShape(b.defaultBlockState(), fakeLevel(false), BlockPos.ZERO,
                net.minecraft.world.phys.shapes.CollisionContext.empty());
        assertTrue(Shapes.equal(Shapes.box(0.125, 0.0, 0.125, 0.875, 0.75, 0.875), outline));

        bridge.selectionBoxesResult = java.util.Collections.emptyList();
        VoxelShape empty = b.getShape(b.defaultBlockState(), fakeLevel(false), BlockPos.ZERO,
                net.minecraft.world.phys.shapes.CollisionContext.empty());
        assertTrue(empty.isEmpty(), "an empty live selection answer must not use the static outline");
    }

    /**
     * Shape queries also arrive from the client/render thread (outline picking), concurrently
     * with the server thread's own queries for the same position. The live bridge calls are
     * not safe to run there: they mutate the one shared legacy world and per-block instance
     * state no other thread synchronizes against. So a client-side level must fall back to
     * the static shape on every channel and never invoke the bridge at all.
     */
    @Test
    void liveShapeQueriesAreSkippedOnTheClientAndNeverReachTheBridge() throws Exception {
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        // If the client-side guard regresses, these live results (deliberately different from the
        // static shapes below) would be the ones observed instead of the static fallback.
        bridge.collisionBoxesResult = java.util.Collections.emptyList();
        bridge.selectionBoxesResult = java.util.Collections.singletonList(
                new double[] {0.125, 0.0, 0.125, 0.875, 0.75, 0.875});
        UmbBridgeHost.set(bridge);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "clientshape" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().dynamicShape().setId(key);
        VoxelShape staticShape = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.5, 1.0);
        VoxelShape staticCollision = Shapes.box(0.0, 0.0, 0.0, 1.0, 0.25, 1.0);
        UmbLegacyBlock b = new UmbLegacyBlock(props, rec(false), staticShape, staticCollision);
        ServerLevel client = fakeLevel(true);

        VoxelShape outline = b.getShape(b.defaultBlockState(), client, BlockPos.ZERO,
                net.minecraft.world.phys.shapes.CollisionContext.empty());
        VoxelShape collision = b.getCollisionShape(b.defaultBlockState(), client, BlockPos.ZERO,
                net.minecraft.world.phys.shapes.CollisionContext.empty());
        VoxelShape support = b.getBlockSupportShape(b.defaultBlockState(), client, BlockPos.ZERO);

        assertTrue(Shapes.equal(staticShape, outline),
                "a client-side outline query must use the static shape, not the live selection answer");
        assertTrue(Shapes.equal(staticCollision, collision),
                "a client-side collision query must use the static shape, not the live collision answer");
        // getBlockSupportShape's own non-live fallback is Shapes.block() (a full unit cube) when
        // hasCollision && !collisionShape.isEmpty() -- see its javadoc -- not the raw static
        // collisionShape; the property under test here is only that it is NOT the live answer.
        assertTrue(Shapes.equal(Shapes.block(), support),
                "a client-side support query must use the static (non-live) fallback");
        assertEquals(0, bridge.selectionBoxesCallCount,
                "the client thread must never call the shared legacy bridge's selectionBoxes");
        assertEquals(0, bridge.collisionBoxesCallCount,
                "the client thread must never call the shared legacy bridge's collisionBoxes");

        // Server-side behavior is unchanged: the same block, on a real server-side level, still
        // gets the live answers (this is the existing, already-covered contract).
        ServerLevel server = fakeLevel(false);
        VoxelShape liveOutline = b.getShape(b.defaultBlockState(), server, BlockPos.ZERO,
                net.minecraft.world.phys.shapes.CollisionContext.empty());
        assertTrue(Shapes.equal(Shapes.box(0.125, 0.0, 0.125, 0.875, 0.75, 0.875), liveOutline),
                "server-side outline queries must still see the live selection answer");
        assertEquals(1, bridge.selectionBoxesCallCount);
    }

    @Test
    void clientShapeQueriesReadTheServersCachedAnswer() throws Exception {
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.collisionBoxesResult = java.util.Collections.singletonList(
                new double[] {0.0, 0.0, 0.0, 1.0, 1.0, 1.0});
        bridge.cachedShapeResult = java.util.Collections.emptyList();
        UmbBridgeHost.set(bridge);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "cachedshape" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().dynamicShape().setId(key);
        UmbLegacyBlock b = new UmbLegacyBlock(props, rec(false), Shapes.block(), Shapes.block());

        VoxelShape collision = b.getCollisionShape(b.defaultBlockState(), fakeLevel(true),
                BlockPos.ZERO, net.minecraft.world.phys.shapes.CollisionContext.empty());

        assertTrue(collision.isEmpty(), "an open door cached by the server must be passable on the client");
        assertEquals(0, bridge.collisionBoxesCallCount);
    }

    private static ServerPlayer fakePlayer() {
        return TestSupport.allocate(ServerPlayer.class);
    }

    private static BlockHitResult hitAt(BlockPos pos, Direction dir, double relX, double relY, double relZ) {
        Vec3 absolute = new Vec3(pos.getX() + relX, pos.getY() + relY, pos.getZ() + relZ);
        return new BlockHitResult(absolute, dir, pos, false);
    }

    /** BUG-1: a block with NO tile entity must still reach legacy activation via useWithoutItem. */
    @Test
    void nonTileEntityBlockReachesLegacyActivation() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.activateResult = new ActivationResult(true, null);
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(1, 2, 3);
        ServerLevel level = fakeLevel(false);
        ServerPlayer player = fakePlayer();
        BlockHitResult hit = hitAt(pos, Direction.UP, 0.5, 1.0, 0.5);

        b.useWithoutItem(b.defaultBlockState(), level, pos, player, hit);

        assertEquals(1, bridge.activateCallCount, "a non-tile-entity twin must still call bridge.activate");
        assertEquals(b.getLegacyId(), bridge.lastActivateLegacyBlockId);
    }

    /** BUG-2: legacy true must map to a result whose consumesAction() is true (PART E2: SUCCESS_SERVER). */
    @Test
    void legacyTrueMapsToAConsumingResult() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.activateResult = new ActivationResult(true, null);
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(4, 5, 6);
        InteractionResult r = b.useWithoutItem(b.defaultBlockState(), fakeLevel(false), pos, fakePlayer(),
                hitAt(pos, Direction.NORTH, 0.5, 0.5, 0.0));

        assertEquals(InteractionResult.SUCCESS_SERVER, r);
        assertTrue(r.consumesAction(), "legacy handled=true must consume the action so vanilla never places the held block");
    }

    /** BUG-2: legacy false must map to PASS (not the old CONSUME-nothing-happened bug, and not FAIL). */
    @Test
    void legacyFalseMapsToPass() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.activateResult = new ActivationResult(false, null);
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(7, 8, 9);
        InteractionResult r = b.useWithoutItem(b.defaultBlockState(), fakeLevel(false), pos, fakePlayer(),
                hitAt(pos, Direction.SOUTH, 0.5, 0.5, 1.0));

        assertEquals(InteractionResult.PASS, r);
        assertFalse(r.consumesAction());
    }

    /** FM-1: 26.2's absolute BlockHitResult location must convert to 1.7.10's block-relative 0..1 floats. */
    @Test
    void hitCoordinatesArriveBlockRelativeInZeroToOne() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.activateResult = new ActivationResult(false, null);
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(10, 20, 30);
        InteractionResult ignored = b.useWithoutItem(b.defaultBlockState(), fakeLevel(false), pos, fakePlayer(),
                hitAt(pos, Direction.EAST, 0.25, 0.75, 0.9));

        assertEquals(0.25f, bridge.lastActivateHitX, 1e-4);
        assertEquals(0.75f, bridge.lastActivateHitY, 1e-4);
        assertEquals(0.9f, bridge.lastActivateHitZ, 1e-4);
        assertTrue(bridge.lastActivateHitX >= 0f && bridge.lastActivateHitX <= 1f);
        assertTrue(bridge.lastActivateHitY >= 0f && bridge.lastActivateHitY <= 1f);
        assertTrue(bridge.lastActivateHitZ >= 0f && bridge.lastActivateHitZ <= 1f);
    }

    /** PART E1's side ordinal (already correct, do not change) must still reach the bridge unchanged. */
    @Test
    void sideOrdinalIsPassedThroughFromTheDirection() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.activateResult = new ActivationResult(false, null);
        UmbBridgeHost.set(bridge);

        BlockPos pos = BlockPos.ZERO;
        b.useWithoutItem(b.defaultBlockState(), fakeLevel(false), pos, fakePlayer(),
                hitAt(pos, Direction.UP, 0.5, 1.0, 0.5));

        assertEquals(Direction.UP.get3DDataValue(), bridge.lastActivateSide);
    }

    /**
     * E3: useItemOn must reach the SAME activation, and when legacy declines it must return
     * TRY_WITH_EMPTY_HAND so vanilla still reaches useWithoutItem (PART E1/E2) - but the
     * subsequent useWithoutItem call for the SAME click must NOT run onBlockActivated a second
     * time (a legacy method with a decline-path side effect would otherwise fire twice).
     */
    @Test
    void useItemOnDeclineFallsThroughWithoutDoubleActivating() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.activateResult = new ActivationResult(false, null);
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(11, 12, 13);
        ServerLevel level = fakeLevel(false);
        ServerPlayer player = fakePlayer();
        BlockHitResult hit = hitAt(pos, Direction.DOWN, 0.5, 0.0, 0.5);

        InteractionResult first = b.useItemOn(ItemStack.EMPTY, b.defaultBlockState(), level, pos, player,
                InteractionHand.MAIN_HAND, hit);
        assertEquals(InteractionResult.TRY_WITH_EMPTY_HAND, first);
        assertEquals(1, bridge.activateCallCount);

        InteractionResult second = b.useWithoutItem(b.defaultBlockState(), level, pos, player, hit);
        assertEquals(InteractionResult.PASS, second);
        assertEquals(1, bridge.activateCallCount, "useWithoutItem must not re-run onBlockActivated for the same click");
    }

    /** E3: when legacy handles it via useItemOn, that result must be returned directly (consuming). */
    @Test
    void useItemOnHandledReturnsAConsumingResultDirectly() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.activateResult = new ActivationResult(true, null);
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(14, 15, 16);
        InteractionResult r = b.useItemOn(ItemStack.EMPTY, b.defaultBlockState(), fakeLevel(false), pos,
                fakePlayer(), InteractionHand.MAIN_HAND, hitAt(pos, Direction.WEST, 0.0, 0.5, 0.5));

        assertEquals(InteractionResult.SUCCESS_SERVER, r);
        assertEquals(1, bridge.activateCallCount);
    }

    /** Left click: attack() must bridge onBlockClicked (func_149699_a) via LegacyBridge.clicked. */
    @Test
    void attackBridgesLeftClickToClicked() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(17, 18, 19);
        // attack() is `protected`, accessible directly from this same-package test (same idiom the
        // pre-existing useWithoutItem/getShape/getTicker calls above already use).
        b.attack(b.defaultBlockState(), fakeLevel(false), pos, fakePlayer());

        assertEquals(1, bridge.clickedCallCount);
        assertEquals(b.getLegacyId(), bridge.lastClickedLegacyBlockId);
    }

    /**
     * Task A gate: the 2-arg constructor (every pre-Task-A caller, and every OTHER test in this
     * class) must keep getting the plain default full cube - {@link BlockShapes} itself is
     * exercised separately in {@code BlockShapesTest}.
     */
    @Test
    void twoArgConstructorDefaultsToTheFullCubeShape() {
        UmbLegacyBlock b = block(false);
        VoxelShape shape = b.getShape(b.defaultBlockState(), null, net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.phys.shapes.CollisionContext.empty());
        assertTrue(Shapes.equal(Shapes.block(), shape));
    }

    @Test
    void threeArgConstructorReturnsTheSuppliedShapeFromGetShape() {
        VoxelShape custom = Shapes.box(0, 0, 0, 1, 0.5, 1);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "shaped" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
        UmbLegacyBlock b = new UmbLegacyBlock(props, rec(false), custom);

        VoxelShape shape = b.getShape(b.defaultBlockState(), null, net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.phys.shapes.CollisionContext.empty());
        assertTrue(Shapes.equal(custom, shape));
        assertTrue(!Shapes.equal(Shapes.block(), shape), "must not silently be the default full cube");
    }

    @Test
    void threeArgConstructorWithANullShapeFallsBackToTheFullCube() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "shapednull" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
        UmbLegacyBlock b = new UmbLegacyBlock(props, rec(false), null);

        VoxelShape shape = b.getShape(b.defaultBlockState(), null, net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.phys.shapes.CollisionContext.empty());
        assertTrue(Shapes.equal(Shapes.block(), shape));
    }

    /** No-collision lane (LIVE-GAP-ANALYSIS.md section 4): collision and outline are now separate.
     *  A walk-through block (1.7.10 func_149668_a returned null - fire, gases, spikes) gets an
     *  EMPTY collision shape while its outline stays clickable. */
    @Test
    void fourArgConstructorSeparatesEmptyCollisionFromAClickableOutline() {
        VoxelShape outline = Shapes.box(0, 0, 0, 1, 0.5, 1);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "nocoll" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
        UmbLegacyBlock b = new UmbLegacyBlock(props, rec(false), outline, Shapes.empty());

        VoxelShape shape = b.getShape(b.defaultBlockState(), null, net.minecraft.core.BlockPos.ZERO,
                net.minecraft.world.phys.shapes.CollisionContext.empty());
        assertTrue(Shapes.equal(outline, shape), "the outline must keep the supplied non-empty shape");
        VoxelShape collision = b.getCollisionShape(b.defaultBlockState(), null,
                net.minecraft.core.BlockPos.ZERO, net.minecraft.world.phys.shapes.CollisionContext.empty());
        assertTrue(collision.isEmpty(), "the collision shape must be EMPTY - walk-through, like vanilla torches");
    }

    @Test
    void extractedFullCollisionShapeSupportsNativeNeighbourPlacement() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "support" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key).noOcclusion();
        UmbLegacyBlock b = new UmbLegacyBlock(props, rec(false), Shapes.block(), Shapes.block());

        assertTrue(b.defaultBlockState().isFaceSturdy(null, BlockPos.ZERO, Direction.UP),
                "a full extracted collision box must support vanilla fire/flint placement");
    }

    @Test
    void extractedInsetCollisionShapeKeepsLegacyNeighbourPlacementSupport() {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "support_inset" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key).noOcclusion();
        VoxelShape inset = Shapes.box(0.125, 0.0, 0.125, 0.875, 1.0, 0.875);
        UmbLegacyBlock b = new UmbLegacyBlock(props, rec(false), inset, inset);

        assertTrue(b.defaultBlockState().isFaceSturdy(null, BlockPos.ZERO, Direction.UP),
                "a non-empty extracted inset collision box must retain 1.7.10 fire placement");
    }

    /** Regression: the 3-arg constructor (every pre-lane caller) keeps collision == outline. */
    @Test
    void threeArgConstructorKeepsCollisionIdenticalToTheOutline() {
        VoxelShape custom = Shapes.box(0, 0, 0, 1, 0.25, 1);
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "collsame" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
        UmbLegacyBlock b = new UmbLegacyBlock(props, rec(false), custom);

        VoxelShape collision = b.getCollisionShape(b.defaultBlockState(), null,
                net.minecraft.core.BlockPos.ZERO, net.minecraft.world.phys.shapes.CollisionContext.empty());
        assertTrue(Shapes.equal(custom, collision), "3-arg construction must behave exactly as before this lane");
    }

    // ---- PART 1 (INTERACTION-BRIDGE.md / GENERALIZATION-PLAN.md GAP 3): placement / break /
    // neighbor-change gates ----

    /** setPlacedBy: the fix for "a multiblock places one tiny crushed block" - lets the mod's own
     *  onBlockPlacedBy (func_149689_a) run and write its own filler blocks. */
    @Test
    void setPlacedByReachesLegacyOnBlockPlacedBy() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(1, 2, 3);
        b.setPlacedBy(fakeLevel(false), pos, b.defaultBlockState(), fakePlayer(), ItemStack.EMPTY);

        assertEquals(1, bridge.placedByCallCount);
        assertEquals(b.getLegacyId(), bridge.lastPlacedByLegacyBlockId);
    }

    @Test
    void setPlacedByDoesNothingOnTheClient() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        b.setPlacedBy(fakeLevel(true), BlockPos.ZERO, b.defaultBlockState(), fakePlayer(), ItemStack.EMPTY);

        assertEquals(0, bridge.placedByCallCount);
    }

    /** onPlace: fires for a genuine new placement (old state is air / a different block). */
    @Test
    void onPlaceReachesLegacyOnBlockAddedForAGenuinelyNewBlock() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(4, 5, 6);
        b.onPlace(b.defaultBlockState(), fakeLevel(false), pos, Blocks.AIR.defaultBlockState(), false);

        assertEquals(1, bridge.addedCallCount);
        assertEquals(b.getLegacyId(), bridge.lastAddedLegacyBlockId);
    }

    /** onPlace: must NOT fire again for a meta-driven variant swap of the SAME legacy identity -
     *  otherwise every redstone toggle would look like a brand-new placement (wrong per 1.7.10). */
    @Test
    void onPlaceSkipsAMetaVariantSwapOfTheSameLegacyIdentity() throws Exception {
        UmbLegacyBlock oldVariant = block(false); // rec(false).id == "hbm:tile.test", same as newVariant
        UmbLegacyBlock newVariant = block(false); // a DIFFERENT Block instance, SAME legacy id
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(7, 8, 9);
        newVariant.onPlace(newVariant.defaultBlockState(), fakeLevel(false), pos,
                oldVariant.defaultBlockState(), false);

        assertEquals(0, bridge.addedCallCount, "same legacy id old->new must be treated as a meta swap, not a new placement");
    }

    @Test
    void onPlaceDoesNothingOnTheClient() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        b.onPlace(b.defaultBlockState(), fakeLevel(true), BlockPos.ZERO, Blocks.AIR.defaultBlockState(), false);

        assertEquals(0, bridge.addedCallCount);
    }

    /** neighborChanged: reaches legacy onNeighborBlockChange with this block's OWN position and the
     *  neighbor's legacy id when the neighbor is itself a known twin. */
    @Test
    void neighborChangedReachesLegacyBridgeWithTheNeighborsLegacyId() throws Exception {
        UmbLegacyBlock b = block(false);
        UmbLegacyBlock neighbor = block(false); // shares legacyId "hbm:tile.test" - fine, only its id is read
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(10, 11, 12);
        b.neighborChanged(b.defaultBlockState(), fakeLevel(false), pos, neighbor, null, false);

        assertEquals(1, bridge.neighborChangedCallCount);
        assertEquals(b.getLegacyId(), bridge.lastNeighborChangedLegacyBlockId);
        assertEquals(neighbor.getLegacyId(), bridge.lastNeighborLegacyBlockId);
    }

    @Test
    void neighborChangedReportsAnUnknownNeighborAsNull() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        b.neighborChanged(b.defaultBlockState(), fakeLevel(false), BlockPos.ZERO, Blocks.STONE, null, false);

        assertEquals(1, bridge.neighborChangedCallCount);
        assertNull(bridge.lastNeighborLegacyBlockId, "a plain vanilla neighbor has no legacy twin");
    }

    /** canSurvive: bridges canPlaceBlockAt (func_149742_c) both ways. */
    @Test
    void canSurviveDelegatesToBridgeCanPlaceAt() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        bridge.canPlaceAtResult = false;
        UmbBridgeHost.set(bridge);

        boolean result = b.canSurvive(b.defaultBlockState(), fakeLevel(false), BlockPos.ZERO);

        assertFalse(result);
    }

    @Test
    void canSurviveFallsBackToVanillaDefaultWhenThereIsNoLegacyRecord() throws Exception {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK,
                Identifier.fromNamespaceAndPath("umbtest", "norecordcansurvive" + (nextId++)));
        BlockBehaviour.Properties props = BlockBehaviour.Properties.of().setId(key);
        UmbLegacyBlock b = new UmbLegacyBlock(props, null);

        // record == null -> straight to super.canSurvive, javap-verified default body "return true".
        assertTrue(b.canSurvive(b.defaultBlockState(), fakeLevel(false), BlockPos.ZERO));
    }

    /** playerWillDestroy: CRITICAL RISK "break must undo placement" - bridges onBlockDestroyedByPlayer
     *  then breakBlock BEFORE the block is actually removed, so the mod's own cleanup/drop code runs
     *  while the world state it reads is still valid. */
    @Test
    void playerWillDestroyReachesLegacyBrokenBeforeRemoval() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        BlockPos pos = new BlockPos(13, 14, 15);
        BlockState result = b.playerWillDestroy(fakeLevel(false), pos, b.defaultBlockState(), fakePlayer());

        assertEquals(1, bridge.brokenCallCount);
        assertEquals(b.getLegacyId(), bridge.lastBrokenLegacyBlockId);
        assertEquals(b.defaultBlockState(), result, "playerWillDestroy must still return the (super's) state");
    }

    @Test
    void playerWillDestroyDoesNothingOnTheClient() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);

        b.playerWillDestroy(fakeLevel(true), BlockPos.ZERO, b.defaultBlockState(), fakePlayer());

        assertEquals(0, bridge.brokenCallCount);
    }

    /**
     * CRITICAL RISK "re-entrancy" proof of termination: wires FakeLegacyBridge.added() to call
     * BACK into onPlace unconditionally (old state always "air", so the meta-swap filter never
     * skips it) - simulating a legacy mod whose onBlockAdded always triggers another placement.
     * Without the depth guard this recurses forever (StackOverflowError, or simply never returns);
     * with it, the call count must be bounded by exactly MAX_REENTRANCY_DEPTH and the outer call
     * must still return normally.
     */
    @Test
    void reentrancyGuardBoundsUnboundedRecursionAndStillReturns() throws Exception {
        UmbLegacyBlock b = block(false);
        FakeLegacyBridge bridge = new FakeLegacyBridge();
        UmbBridgeHost.set(bridge);
        ServerLevel level = fakeLevel(false);
        BlockPos pos = new BlockPos(20, 21, 22);
        BlockState air = Blocks.AIR.defaultBlockState();

        bridge.onAddedCallback = () -> b.onPlace(b.defaultBlockState(), level, pos, air, false);

        // must return promptly (no StackOverflowError, no hang) - this line completing at all is
        // half the proof; the exact bound is the other half.
        b.onPlace(b.defaultBlockState(), level, pos, air, false);

        Field maxDepth = UmbLegacyBlock.class.getDeclaredField("MAX_REENTRANCY_DEPTH");
        maxDepth.setAccessible(true);
        int cap = maxDepth.getInt(null);

        assertEquals(cap, bridge.addedCallCount,
                "recursion must be bounded by EXACTLY MAX_REENTRANCY_DEPTH, neither less (guard too eager) nor more (guard not enforced)");
    }
}
