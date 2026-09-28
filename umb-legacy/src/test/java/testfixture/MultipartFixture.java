package testfixture;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.world.World;

/**
 * Mod-shaped multipart test entities used to verify the generic part/helper collision-box scan.
 * Deliberately non-final and free of any mod identity: the scan under test may only rely on the
 * vanilla contracts ({@code func_70021_al} parts, each part's box, helper-owned
 * {@code AxisAlignedBB} state).
 */
public final class MultipartFixture {
    private MultipartFixture() {
    }

    /** An entity exposing extra parts through the vanilla {@code func_70021_al} contract. */
    public static class PartedFixture extends Entity {
        private final Entity[] parts;

        public PartedFixture(World world, Entity[] parts) {
            super(world);
            this.parts = parts;
        }

        @Override
        public Entity[] func_70021_al() {
            return parts;
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

    /** A stand-in for a helper-owned volume object (never a mod type). */
    public static final class HelperVolume {
        public AxisAlignedBB volume;
    }

    /** An entity holding a helper-owned world-space volume one level deep. */
    public static class HelpedFixture extends Entity {
        public final HelperVolume helper = new HelperVolume();

        public HelpedFixture(World world) {
            super(world);
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
}
