package dev.umb.bridge.api;

import java.util.Collections;
import java.util.List;

/** Implemented by the HOST (26.2 side). Called by legacy code through the facades. */
public interface HostWorld {
    boolean isRemote();
    long    getTotalTime();
    String  getBlockId(int x, int y, int z);   // legacy registry name; "minecraft:air" if empty
    int     getMeta(int x, int y, int z);
    void    setBlock(int x, int y, int z, String legacyId, int meta, int flags);
    void    setMeta(int x, int y, int z, int meta, int flags);
    void    removeBlock(int x, int y, int z);
    void    markBlockDirty(int x, int y, int z);
    /** Propagates a legacy neighbor/comparator notification through the host graph. */
    default void notifyNeighbors(int x, int y, int z) {
        markBlockDirty(x + 1, y, z);
        markBlockDirty(x - 1, y, z);
        markBlockDirty(x, y + 1, z);
        markBlockDirty(x, y - 1, z);
        markBlockDirty(x, y, z + 1);
        markBlockDirty(x, y, z - 1);
    }
    void    scheduleTick(int x, int y, int z, int delay);
    long    randomSeed();
    void    log(String msg);

    /** Players currently connected to this host world, for legacy client player-list views. */
    default List<HostPlayer> getPlayers() {
        return Collections.emptyList();
    }

    /** Whether the host's native chunk tracker watches this chunk for a player. */
    default boolean isChunkWatched(String playerIdentity, int chunkX, int chunkZ) {
        return false;
    }

    // default no-ops so every existing implementer
    // stays source-compatible; the real host overrides them. The host maps legacy names to modern
    // equivalents; unknown particles use a counted native smoke fallback, while unknown sounds
    // are skipped-and-counted because a guessed sound identity is not honest.

    /** name is either "modid:key" (a mod sounds.json key, host lowercases it - modern resource ids
     *  forbid uppercase) or a 1.7.10 vanilla sound name (e.g. "random.explode"). */
    default void playSound(double x, double y, double z, String name, float volume, float pitch) {}

    /** Queue a client-local one-shot or loop effect for the player currently executing legacy
     * client code. The host owns delivery to the native 26.2 SoundManager. */
    default void enqueueClientEffect(EffectData effect) {}

    /** Drain client-local effects produced while a bounded legacy client callback ran. */
    default List<EffectData> drainClientEffects() { return Collections.emptyList(); }

    /** name is a 1.7.10 vanilla particle name (e.g. "largesmoke"); (vx,vy,vz) is the single
     *  particle's motion, matching 1.7.10 World.spawnParticle semantics. */
    default void spawnParticle(String name, double x, double y, double z, double vx, double vy, double vz) {}

    /** strength in legacy units (TNT=4.0F); breakBlocks mirrors 1.7.10's "isSmoking" flag
     *  (false = flash/damage only, no terrain change). */
    default void explode(double x, double y, double z, float strength, boolean flaming, boolean breakBlocks) {}

    /** Best redstone signal reaching (x,y,z) from any neighbour, 0..15; 0 when the host has no
     *  redstone information (the honest pre-existing default). */
    default int getRedstonePower(int x, int y, int z) { return 0; }

    /**
     * Returns native host entities whose AABBs intersect the supplied legacy query box.  The
     * default is empty so headless adapters remain source-compatible; the real 26.2 adapter
     * overrides it.  Native players and UMB's own legacy twins are excluded by that adapter
     * because UmbWorld already exposes those through its legacy-side lists.
     */
    default List<HostEntity> getEntities(double minX, double minY, double minZ,
                                         double maxX, double maxY, double maxZ,
                                         String excludedIdentity) {
        return Collections.emptyList();
    }

    // default no-ops so every existing implementer (era hosts
    // probes, test fakes) stays source-compatible; the real host overrides them.

    /**
     * Spawns one legacy block-drop stack as a native host item entity at the given
     * (block-center) position. Called by the legacy side when 1.7.10 code breaks a block
     * with drops (World.destroyBlock, explosions, vehicle crush): the host applies its own
     * drop rules (notably the BLOCK_DROPS gamerule, 1.7.10's doTileDrops) and physics.
     * The default is a no-op so headless adapters stay source-compatible; an empty stack
     * must always be ignored, even by overrides.
     */
    default void dropItem(double x, double y, double z, StackData stack) {}
}
