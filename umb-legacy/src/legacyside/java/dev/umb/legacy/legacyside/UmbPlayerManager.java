package dev.umb.legacy.legacyside;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.world.WorldServer;

/**
 * Native-free 1.7.10 PlayerManager facade. Block updates cross to HostWorld;
 * watcher queries consult the host's real ChunkMap; lifecycle calls are safe.
 */
final class UmbPlayerManager extends PlayerManager {
    private UmbWorld world;
    private Set<Long> legacyWatchers;

    private UmbPlayerManager() {
        super(null);
    }

    static UmbPlayerManager create(UmbWorld world) {
        UmbPlayerManager manager = UmbUnsafe.allocate(UmbPlayerManager.class);
        manager.world = world;
        manager.legacyWatchers = new HashSet<Long>();
        return manager;
    }

    @Override
    public WorldServer func_72688_a() {
        return world;
    }

    @Override
    public void func_72693_b() {
    }

    @Override
    public boolean func_152621_a(int chunkX, int chunkZ) {
        long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
        if (legacyWatchers.contains(key)) return true;
        for (dev.umb.bridge.api.HostPlayer player : world.host().getPlayers()) {
            if (world.host().isChunkWatched(player.getIdentityKey(), chunkX, chunkZ)) return true;
        }
        return false;
    }

    /** Vanilla func_151250_a: markBlockForUpdate. */
    @Override
    public void func_151250_a(int x, int y, int z) {
        world.host().markBlockDirty(x, y, z);
    }

    @Override
    public void func_72683_a(EntityPlayerMP player) {
        if (player == null) return;
        legacyWatchers.add((((long) ((int) player.field_70165_t >> 4)) << 32)
                ^ (((int) player.field_70161_v >> 4) & 0xffffffffL));
    }

    @Override public void func_72691_b(EntityPlayerMP player) {}
    @Override public void func_72695_c(EntityPlayerMP player) {}
    @Override public void func_72685_d(EntityPlayerMP player) {}
    @Override public void func_152622_a(int radius) {}

    @Override
    public boolean func_72694_a(EntityPlayerMP player, int chunkX, int chunkZ) {
        return func_152621_a(chunkX, chunkZ);
    }
}
