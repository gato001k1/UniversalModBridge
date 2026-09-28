package dev.umb.hostagent.content;

import dev.umb.bridge.api.HostEntity;
import dev.umb.hostagent.AgentLog;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageSources;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;

/** Host-side capability wrapper for one native 26.2 entity. */
final class HostNativeEntity implements HostEntity {
    private final Entity entity;
    private final ServerLevel level;

    HostNativeEntity(Entity entity, ServerLevel level) {
        this.entity = entity;
        this.level = level;
    }

    @Override
    public String getIdentityKey() {
        return entity.getStringUUID();
    }

    @Override
    public double getMinX() {
        return entity.getBoundingBox().minX;
    }

    @Override
    public double getMinY() {
        return entity.getBoundingBox().minY;
    }

    @Override
    public double getMinZ() {
        return entity.getBoundingBox().minZ;
    }

    @Override
    public double getMaxX() {
        return entity.getBoundingBox().maxX;
    }

    @Override
    public double getMaxY() {
        return entity.getBoundingBox().maxY;
    }

    @Override
    public double getMaxZ() {
        return entity.getBoundingBox().maxZ;
    }

    @Override
    public boolean isLiving() {
        return entity instanceof LivingEntity;
    }

    @Override
    public float getHealth() {
        return entity instanceof LivingEntity living ? living.getHealth() : 0.0F;
    }

    @Override
    public float getMaxHealth() {
        return entity instanceof LivingEntity living ? living.getMaxHealth() : 0.0F;
    }

    @Override
    public boolean hurt(String legacyDamageType, float amount) {
        if (!(amount > 0.0F) || entity.isRemoved()) {
            return false;
        }
        try {
            DamageSource source = resolveDamage(level.damageSources(), legacyDamageType);
            boolean accepted = entity.hurtServer(level, source, amount);
            AgentLog.line("ENTITY-DIAG host native hurt id=" + entity.getStringUUID()
                    + " type=" + legacyDamageType + " amount=" + amount + " accepted=" + accepted
                    + " health=" + (entity instanceof LivingEntity living ? living.getHealth() : "n/a"));
            return accepted;
        } catch (Throwable t) {
            AgentLog.error("HostNativeEntity.hurt", t, 2);
            return false;
        }
    }

    @Override
    public void setDead() {
        if (!entity.isRemoved()) {
            entity.discard();
        }
    }

    @Override
    public void setHealth(float health) {
        if (entity instanceof LivingEntity living) {
            living.setHealth(health);
        }
    }

    /**
     * Keep the same honest policy as HostPlayerImpl: map stable vanilla names, and use generic
     * damage for mod-specific or source-shaped legacy names that need a native attacker object.
     * The hit is never silently discarded merely because attribution is unavailable.
     */
    static DamageSource resolveDamage(DamageSources sources, String legacyDamageType) {
        if (legacyDamageType != null) {
            switch (legacyDamageType) {
                case "inFire": return sources.inFire();
                case "onFire": return sources.onFire();
                case "lava": return sources.lava();
                case "inWall": return sources.inWall();
                case "drown": return sources.drown();
                case "starve": return sources.starve();
                case "cactus": return sources.cactus();
                case "fall": return sources.fall();
                case "outOfWorld": return sources.fellOutOfWorld();
                case "magic": return sources.magic();
                case "wither": return sources.wither();
                case "lightningBolt": return sources.lightningBolt();
                case "generic": return sources.generic();
                default: return sources.generic();
            }
        }
        return sources.generic();
    }
}
