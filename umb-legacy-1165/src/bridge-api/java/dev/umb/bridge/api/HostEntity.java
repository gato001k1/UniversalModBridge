package dev.umb.bridge.api;

/**
 * A native host entity observed inside a legacy-world query.  The object is a deliberately small
 * capability boundary: legacy code receives a facade, never the host's 26.2 Entity instance.
 */
public interface HostEntity {
    /** Stable host identity used to exclude the entity that initiated a query. */
    String getIdentityKey();

    /** Native bounding box in host coordinates. */
    double getMinX();
    double getMinY();
    double getMinZ();
    double getMaxX();
    double getMaxY();
    double getMaxZ();

    /** Living state mirrored into EntityLivingBase's legacy health field. */
    boolean isLiving();
    float getHealth();
    float getMaxHealth();

    /** Maps a legacy DamageSource across the boundary and applies it to the native entity. */
    boolean hurt(String legacyDamageType, float amount);

    /** Mirrors legacy Entity.setDead (func_70106_y) to the native entity. */
    void setDead();

    /** Mirrors legacy EntityLivingBase.setHealth (func_70606_j) when supported. */
    default void setHealth(float health) {}
}
