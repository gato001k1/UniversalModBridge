package dev.umb.hostagent.content;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Part-collider twins are pure host-side boxes that delegate every decision to
 * the parent twin. Headless like {@link UmbLegacyEntityTest} (Unsafe allocation,
 * no live Level): only paths that never touch a null level/registries are
 * exercised here.
 */
class UmbLegacyPartTwinTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
    }

    private static UmbLegacyPartTwin newPart() {
        return TestSupport.allocate(UmbLegacyPartTwin.class);
    }

    private static UmbLegacyEntity newParentWithHandle(FakeLegacyBridge.FakeEntityHandle handle) {
        UmbLegacyEntity parent = TestSupport.allocate(UmbLegacyEntity.class);
        parent.setHandleForTest(handle);
        return parent;
    }

    /**
     * Unsafe allocation skips field initializers, so vanilla mechanics ({@code setPos},
     * {@code getPassengers}) hit null {@code position}/{@code bb}/{@code passengers} fields.
     * Seed the vanilla defaults; production instances always have them from the constructor.
     */
    private static void seedTransform(Entity entity) {
        try {
            java.lang.reflect.Field position = Entity.class.getDeclaredField("position");
            position.setAccessible(true);
            position.set(entity, Vec3.ZERO);
            java.lang.reflect.Field bb = Entity.class.getDeclaredField("bb");
            bb.setAccessible(true);
            bb.set(entity, new AABB(0, 0, 0, 0, 0, 0));
            java.lang.reflect.Field passengers = Entity.class.getDeclaredField("passengers");
            passengers.setAccessible(true);
            passengers.set(entity, com.google.common.collect.ImmutableList.of());
            java.lang.reflect.Field blockPosition = Entity.class.getDeclaredField("blockPosition");
            blockPosition.setAccessible(true);
            blockPosition.set(entity, new net.minecraft.core.BlockPos.MutableBlockPos());
            java.lang.reflect.Field dimensions = Entity.class.getDeclaredField("dimensions");
            dimensions.setAccessible(true);
            dimensions.set(entity, net.minecraft.world.entity.EntityDimensions.scalable(0.5F, 0.5F));
            java.lang.reflect.Field chunkPosition = Entity.class.getDeclaredField("chunkPosition");
            chunkPosition.setAccessible(true);
            chunkPosition.set(entity, new net.minecraft.world.level.ChunkPos(0, 0));
            java.lang.reflect.Field levelCallback = Entity.class.getDeclaredField("levelCallback");
            levelCallback.setAccessible(true);
            levelCallback.set(entity,
                    net.minecraft.world.level.entity.EntityInLevelCallback.NULL);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError(ex);
        }
    }

    @Test
    void boxValidationRejectsGarbage() {
        assertTrue(UmbLegacyPartTwin.isValidBox(new double[] {0, 0, 0, 16, 4, 16}));
        assertFalse(UmbLegacyPartTwin.isValidBox(null));
        assertFalse(UmbLegacyPartTwin.isValidBox(new double[] {0, 0, 0}));
        assertFalse(UmbLegacyPartTwin.isValidBox(new double[] {0, 0, 0, Double.NaN, 1, 1}));
        assertFalse(UmbLegacyPartTwin.isValidBox(new double[] {5, 0, 0, 4, 1, 1}));
        assertFalse(UmbLegacyPartTwin.isValidBox(new double[] {0, 0, 0, 0, 1, 1}));
    }

    @Test
    void applyBoxSetsTheWorldBox() {
        UmbLegacyPartTwin part = newPart();
        seedTransform(part);
        part.applyBox(new double[] {10, 20, 30, 14, 23, 36});

        AABB box = part.getBoundingBox();
        assertEquals(10, box.minX);
        assertEquals(20, box.minY);
        assertEquals(30, box.minZ);
        assertEquals(14, box.maxX);
        assertEquals(23, box.maxY);
        assertEquals(36, box.maxZ);
    }

    @Test
    void applyBoxIgnoresGarbage() {
        UmbLegacyPartTwin part = newPart();
        seedTransform(part);
        part.applyBox(new double[] {10, 20, 30, 14, 23, 36});
        part.applyBox(null);
        part.applyBox(new double[] {0, 0, 0, Double.NaN, 1, 1});

        assertEquals(14, part.getBoundingBox().maxX);
    }

    @Test
    void dimensionsFollowTheBoxFootprint() {
        UmbLegacyPartTwin part = newPart();
        seedTransform(part);
        part.applyBox(new double[] {0, 0, 0, 16, 4, 6});

        EntityDimensions dimensions = part.getDimensions(Pose.STANDING);
        assertNotNull(dimensions);
        assertEquals(16.0F, dimensions.width());
        assertEquals(4.0F, dimensions.height());
    }

    @Test
    void orphanPartIsInert() {
        UmbLegacyPartTwin part = newPart();
        seedTransform(part);
        part.applyBox(new double[] {0, 0, 0, 2, 2, 2});

        assertFalse(part.isPickable());
        assertFalse(part.isPushable());
        assertFalse(part.canBeCollidedWith(null));
        assertEquals(InteractionResult.PASS, part.interact(
                TestSupport.allocate(net.minecraft.server.level.ServerPlayer.class),
                InteractionHand.MAIN_HAND, new Vec3(0, 0, 0)));
        assertFalse(part.hurtServer(null, null, 1.0F));
        assertNull(part.parentForTest());
    }

    @Test
    void collisionFlagsMirrorALiveParent() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.collidable = true;
        UmbLegacyPartTwin part = newPart();
        part.bindParent(newParentWithHandle(handle));
        assertNotNull(part.parentForTest());

        assertTrue(part.isPickable());
        assertTrue(part.isPushable());
        assertTrue(part.canBeCollidedWith(null));
    }

    @Test
    void deadParentMakesThePartInert() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.collidable = true;
        handle.valid = false;
        UmbLegacyPartTwin part = newPart();
        part.bindParent(newParentWithHandle(handle));

        assertFalse(part.isPickable());
        assertFalse(part.isPushable());
    }

    @Test
    void interactDelegatesToTheParentHandle() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.collidable = true;
        handle.interactAccepted = true;
        UmbLegacyEntity parent = newParentWithHandle(handle);
        seedTransform(parent);
        UmbLegacyPartTwin part = newPart();
        part.bindParent(parent);

        assertEquals(InteractionResult.SUCCESS, part.interact(
                TestSupport.allocate(net.minecraft.server.level.ServerPlayer.class),
                InteractionHand.MAIN_HAND, new Vec3(0, 0, 0)));
    }

    @Test
    void damageDelegatesToTheParentHandle() {
        FakeLegacyBridge.FakeEntityHandle handle =
                new FakeLegacyBridge.FakeEntityHandle("umbtest:vehicle", 0, 0, 0);
        handle.attackAccepted = true;
        UmbLegacyPartTwin part = newPart();
        part.bindParent(newParentWithHandle(handle));

        assertTrue(part.hurtServer(null, null, 2.0F));
    }
}
