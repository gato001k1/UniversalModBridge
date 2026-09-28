package dev.umb.bridge.api;

/**
 * Implemented by the HOST, called by legacy code through {@code UmbWorld}'s now-real
 * {@code spawnEntityInWorld} (func_72838_d) - the entity twin of {@link HostWorld}/{@link HostPlayer}.
 * Legacy code has ALREADY constructed the real legacy {@code Entity} (e.g.
 * "new EntityBullet(world,...)") before this is called; {@link #spawnEntity} does not construct
 * anything legacy-side, it only tells the host "stand up a native twin for this one, backed by
 * this handle". Must never throw across the boundary.
 *
 * <p>A single object may implement both {@link HostWorld} and {@link HostLevel} (this is how the
 * host wires it up); legacy code degrades to a locally-ticked, host-invisible "orphan" entity
 * (see {@code UmbWorld.func_72838_d}'s javadoc) when the installed {@link HostWorld} does not also
 * implement this interface, or when {@link #spawnEntity} itself returns false.</p>
 */
public interface HostLevel {
    /**
     * Returns true if a native twin was created (the host has a registered generic entity type to
     * back it with) and successfully added to the level. A false return is not an error: the
     * legacy entity keeps existing and ticking in the legacy universe either way, it is just not
     * visible host-side.
     */
    boolean spawnEntity(EntityHandle handle);

    /** Host-side passenger state is copied into the legacy entity before its tick. */
    default void prepareEntity(EntityHandle handle) {}

    /** Copies legacy transform/passenger state to the already-created host twin. */
    default void syncEntity(EntityHandle handle) {}

    /** Removes a host twin immediately after legacy setDead/onUpdate marks it dead. */
    default void removeEntity(EntityHandle handle) {}
}
