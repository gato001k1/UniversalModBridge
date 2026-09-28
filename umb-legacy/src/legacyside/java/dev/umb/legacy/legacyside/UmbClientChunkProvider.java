package dev.umb.legacy.legacyside;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;

/** Minimal loaded-chunk view used only by the option-(b) client world facade. */
final class UmbClientChunkProvider extends UmbChunkProvider {
    private final World world;
    private final Map<Long, Chunk> chunks = new HashMap<Long, Chunk>();

    UmbClientChunkProvider(World world) {
        this.world = world;
    }

    @Override
    public synchronized Chunk func_73154_d(int chunkX, int chunkZ) {
        long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
        Chunk chunk = chunks.get(key);
        if (chunk == null) {
            chunk = new Chunk(world, chunkX, chunkZ);
            chunks.put(key, chunk);
        }
        return chunk;
    }

    @Override
    public Chunk func_73158_c(int chunkX, int chunkZ) {
        return func_73154_d(chunkX, chunkZ);
    }
}
