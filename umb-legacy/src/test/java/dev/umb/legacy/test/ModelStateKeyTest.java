package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import dev.umb.bridge.api.HostWorld;
import dev.umb.legacy.legacyside.UmbWorld;
import dev.umb.legacy.legacyside.render.LegacyRenderCapture;

/**
 * Same entity class, different vehicle types must not share a capture cache key:
 * two types sharing one entity class once rendered each other's cached mesh (same
 * verts and textures). The static model key was class + animation-state fields
 * only, and the token filter dropped every type-identity field.
 *
 * <p>Headless pin: two entities of one synthetic class carrying different
 * {@code displayName} values must produce different static model states. Uses
 * reflection because the key builder is an internal static; a real mod pair
 * cannot stage headlessly (no mod jar on the test classpath by design).</p>
 */
class ModelStateKeyTest {

    /** Same-class pair distinguished only by a name-ish identity field. */
    public static final class NamedEntity extends Entity {
        public String displayName;

        public NamedEntity(World world, String displayName) {
            super(world);
            this.displayName = displayName;
        }

        @Override
        protected void func_70088_a() {
        }

        @Override
        protected void func_70037_a(NBTTagCompound tag) {
        }

        @Override
        protected void func_70014_b(NBTTagCompound tag) {
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

    private static String modelState(Entity entity) throws Exception {
        java.lang.reflect.Method method = LegacyRenderCapture.class.getDeclaredMethod(
                "modelState", Entity.class, boolean.class);
        method.setAccessible(true);
        return (String) method.invoke(null, entity, false);
    }

    @Test
    void sameClassDifferentIdentitySplitsStaticModelState() throws Exception {
        UmbWorld world = UmbWorld.create(new FakeHostWorld(), 0);
        String a = modelState(new NamedEntity(world, "mk15"));
        String b = modelState(new NamedEntity(world, "s-75"));

        assertTrue(a.contains("mk15"), "identity value must survive into the static key");
        assertNotEquals(a, b, "same class with different type identity must not share a key");
    }
}
