package dev.umb.hostagent.content;

import dev.umb.bridge.api.EffectData;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostEntity;
import dev.umb.bridge.api.HostLevel;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.StackData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * The HostWorld a legacy universe keeps from boot. Eras boot once per JVM, but the integrated
 * server does not: leaving a world and opening another starts a new MinecraftServer. The
 * legacy World facade still held the first server's HostWorldImpl, so its writes went to a
 * stopped server. ServerChunkCache.getChunk from a thread that is not that server's main thread
 * BlockDummyable.onBlockPlacedBy -> UmbWorld.setBlockToAir -> HostWorldImpl.removeBlock).
 *
 * Every entry point already passes a fresh HostWorldImpl to {@link UmbBridgeHost#ensureBooted};
 * {@link #observe} moves the delegate to the new server's level of the same dimension.
 * It forwards every interface HostWorldImpl implements: legacy code finds entity spawning by
 * checking for HostLevel, so a HostWorld-only wrapper silently stops all legacy entities.
 */
final class LiveHostWorld implements HostWorld, HostLevel {

    private volatile HostWorldImpl delegate;

    LiveHostWorld(HostWorldImpl initial) {
        this.delegate = initial;
    }

    /** Retarget when {@code seen} belongs to a different server than the current delegate. */
    void observe(HostWorld seen) {
        if (!(seen instanceof HostWorldImpl fresh)) return;
        HostWorldImpl current = delegate;
        ServerLevel now = fresh.level();
        ServerLevel old = current.level();
        if (now == null || old == null) return;
        MinecraftServer server = now.getServer();
        if (server == null || server == old.getServer()) return;
        ServerLevel same = server.getLevel(old.dimension());
        delegate = same == now || same == null ? fresh : new HostWorldImpl(same);
        dev.umb.hostagent.AgentLog.line("LiveHostWorld: legacy host world moved to the new server ("
                + old.dimension() + ")");
    }

    HostWorldImpl delegate() {
        return delegate;
    }

    @Override public boolean isRemote() { return delegate.isRemote(); }
    @Override public long getTotalTime() { return delegate.getTotalTime(); }
    @Override public String getBlockId(int x, int y, int z) { return delegate.getBlockId(x, y, z); }
    @Override public int getMeta(int x, int y, int z) { return delegate.getMeta(x, y, z); }
    @Override public void setBlock(int x, int y, int z, String legacyId, int meta, int flags) { delegate.setBlock(x, y, z, legacyId, meta, flags); }
    @Override public void setMeta(int x, int y, int z, int meta, int flags) { delegate.setMeta(x, y, z, meta, flags); }
    @Override public void removeBlock(int x, int y, int z) { delegate.removeBlock(x, y, z); }
    @Override public void markBlockDirty(int x, int y, int z) { delegate.markBlockDirty(x, y, z); }
    @Override public void notifyNeighbors(int x, int y, int z) { delegate.notifyNeighbors(x, y, z); }
    @Override public void scheduleTick(int x, int y, int z, int delay) { delegate.scheduleTick(x, y, z, delay); }
    @Override public long randomSeed() { return delegate.randomSeed(); }
    @Override public void log(String msg) { delegate.log(msg); }
    @Override public List<HostPlayer> getPlayers() { return delegate.getPlayers(); }
    @Override public boolean isChunkWatched(String playerIdentity, int chunkX, int chunkZ) { return delegate.isChunkWatched(playerIdentity, chunkX, chunkZ); }
    @Override public void playSound(double x, double y, double z, String name, float volume, float pitch) { delegate.playSound(x, y, z, name, volume, pitch); }
    @Override public void enqueueClientEffect(EffectData effect) { delegate.enqueueClientEffect(effect); }
    @Override public List<EffectData> drainClientEffects() { return delegate.drainClientEffects(); }
    @Override public void spawnParticle(String name, double x, double y, double z, double vx, double vy, double vz) { delegate.spawnParticle(name, x, y, z, vx, vy, vz); }
    @Override public void explode(double x, double y, double z, float strength, boolean flaming, boolean breakBlocks) { delegate.explode(x, y, z, strength, flaming, breakBlocks); }
    @Override public int getRedstonePower(int x, int y, int z) { return delegate.getRedstonePower(x, y, z); }
    @Override public List<HostEntity> getEntities(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, String excludedIdentity) {
        return delegate.getEntities(minX, minY, minZ, maxX, maxY, maxZ, excludedIdentity);
    }
    @Override public void dropItem(double x, double y, double z, StackData stack) { delegate.dropItem(x, y, z, stack); }

    @Override public boolean spawnEntity(EntityHandle handle) { return delegate.spawnEntity(handle); }
    @Override public void prepareEntity(EntityHandle handle) { delegate.prepareEntity(handle); }
    @Override public void syncEntity(EntityHandle handle) { delegate.syncEntity(handle); }
    @Override public void removeEntity(EntityHandle handle) { delegate.removeEntity(handle); }
}
