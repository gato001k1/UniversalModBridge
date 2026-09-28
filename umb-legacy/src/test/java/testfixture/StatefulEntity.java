package testfixture;

import net.minecraft.entity.Entity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

/** Mod-shaped test entity used to verify generic helper-state isolation. */
public final class StatefulEntity extends Entity {
    public final Helper helper = new Helper();

    public StatefulEntity(World world) {
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

    public static final class Helper {
        public int ammo = 900;
        public int heat = 0;
        public int countWait = 0;
        public float rotation = 0.0F;
    }
}
