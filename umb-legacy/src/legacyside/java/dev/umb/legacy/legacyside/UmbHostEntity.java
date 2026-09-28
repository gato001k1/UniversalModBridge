package dev.umb.legacy.legacyside;

import dev.umb.bridge.api.HostEntity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.DamageSource;

/**
 * A transient legacy facade for one native host entity.  It is intentionally not tracked in
 * UmbWorld's legacy entity list: each AABB query gets a fresh view of the host's current box and
 * health, while attackEntityFrom/getHealth/setDead cross back through the HostEntity capability.
 */
final class UmbHostEntity extends EntityLivingBase {
    private HostEntity hostEntity;

    UmbHostEntity(UmbWorld world, HostEntity hostEntity) {
        super(world);
        this.hostEntity = hostEntity;
        refreshState();
    }

    String hostIdentity() {
        return hostEntity.getIdentityKey();
    }

    private void refreshState() {
        double minX = hostEntity.getMinX();
        double minY = hostEntity.getMinY();
        double minZ = hostEntity.getMinZ();
        double maxX = hostEntity.getMaxX();
        double maxY = hostEntity.getMaxY();
        double maxZ = hostEntity.getMaxZ();
        func_70105_a((float) (maxX - minX), (float) (maxY - minY));
        func_70107_b((minX + maxX) * 0.5D, minY, (minZ + maxZ) * 0.5D);
        field_70746_aG = hostEntity.getHealth();
        field_70180_af.func_75692_b(6, Float.valueOf(field_70746_aG));
        field_70128_L = field_70746_aG <= 0.0F && hostEntity.isLiving();
    }

    @Override
    public boolean func_70097_a(DamageSource source, float amount) {
        float before = hostEntity.getHealth();
        boolean hurt = hostEntity.hurt(source == null ? null : source.field_76373_n, amount);
        refreshState();
        ((UmbWorld) field_70170_p).host().log("ENTITY-DIAG host facade attackEntityFrom type="
                + (source == null ? "generic" : source.field_76373_n)
                + " amount=" + amount + " before=" + before + " after=" + hostEntity.getHealth()
                + " accepted=" + hurt);
        return hurt;
    }

    @Override
    public void func_70606_j(float health) {
        if (hostEntity == null) {
            field_70746_aG = health;
            field_70180_af.func_75692_b(6, Float.valueOf(health));
        } else {
            float before = hostEntity.getHealth();
            hostEntity.setHealth(health);
            refreshState();
            ((UmbWorld) field_70170_p).host().log("ENTITY-DIAG host facade setHealth before="
                    + before + " requested=" + health + " after=" + hostEntity.getHealth());
        }
    }

    @Override
    public void func_70106_y() {
        hostEntity.setDead();
        super.func_70106_y();
    }

    /** A query result is a view, never a tickable legacy entity. */
    @Override
    public void func_70071_h_() {
        refreshState();
    }

    /**
     * mountEntity (1.7.10 real vanilla; called ON the rider, per EntityHandleImpl's own confirmed
     * comment - it sets both sides, this.field_70154_o and entityIn.field_70153_n).  A fresh
     * per-query facade must never become an actual rider of a legacy entity: it is never "a host
     * passenger really riding its twin" (its own class javadoc: intentionally not tracked, a new
     * instance every AABB query), so accepting a mount here would let a legacy entity end up with
     * a rider that no dismount/setHostRider(null) call will ever clear.
     *
     * This closes the mcheli-seats phantom-rider bug at its second layer (see
     * UmbLegacyPartTwin collider reaching legacy AABB scans and being auto-mounted by MCHeli's own
     * mountMobToSeats (fixed at the source in HostWorldImpl.getEntities). Refusing the mount here
     * too is defense in depth - it protects against ANY legacy mod's "auto-mount a nearby
     * EntityLivingBase" pattern finding a UmbHostEntity through some other AABB path, universally,
     * not just MCHeli's one call site.
     */
    @Override
    public void func_70078_a(net.minecraft.entity.Entity entityIn) {
        ((UmbWorld) field_70170_p).host().log("ENTITY-DIAG host facade mount refused identity="
                + hostIdentity() + " target="
                + (entityIn == null ? "null" : entityIn.getClass().getName()));
    }

    @Override
    protected void func_70088_a() {
        super.func_70088_a();
    }

    @Override
    public ItemStack func_70694_bm() {
        return null;
    }

    @Override
    public ItemStack func_71124_b(int slot) {
        return null;
    }

    @Override
    public void func_70062_b(int slot, ItemStack stack) {
    }

    @Override
    public ItemStack[] func_70035_c() {
        return new ItemStack[5];
    }

    @Override
    public void func_70037_a(NBTTagCompound tag) {
    }

    @Override
    public void func_70014_b(NBTTagCompound tag) {
    }
}
