package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import java.util.HashMap;
import java.util.Map;

/**
 * E step 2 - the single highest-leverage fix the mass-tick harness (step 3) surfaced: {@code UmbWorld} had no override for {@code func_72863_F} (getChunkProvider) at all, so calling it ran {@code WorldServer}'s real body and returned {@code null} — 178 of...
 */
class UmbChunkProvider implements IChunkProvider {

    static final UmbChunkProvider INSTANCE = new UmbChunkProvider();
    private final World world;
    private final Map<Long, Chunk> chunks = new HashMap<Long, Chunk>();

    protected UmbChunkProvider() {
        this.world = null;
    }

    /** Per-facade provider used when vanilla code asks for the actual shell after
     * {@link #func_73149_a} reported the coordinates as loaded. */
    protected UmbChunkProvider(World world) {
        this.world = world;
    }

    /** func_73149_a - chunkExists. Always true: this facade has no unloaded-chunk concept. */
    @Override
    public boolean func_73149_a(int chunkX, int chunkZ) {
        return true;
    }

    /** func_73154_d - loadChunk. No real chunk objects exist in this facade. */
    @Override
    public synchronized Chunk func_73154_d(int chunkX, int chunkZ) {
        if (world == null) return null;
        long key = (((long) chunkX) << 32) ^ (chunkZ & 0xffffffffL);
        Chunk chunk = chunks.get(key);
        if (chunk == null) {
            // The facade has no generator or persisted chunk data. A real empty shell is still
            // required because vanilla entity queries call Chunk methods after checking the
            // provider. It contains air by default and never forces a host chunk load.
            chunk = new Chunk(world, chunkX, chunkZ);
            chunks.put(key, chunk);
        }
        return chunk;
    }

    /** func_73158_c - provideChunk. Same as above. */
    @Override
    public Chunk func_73158_c(int chunkX, int chunkZ) {
        return func_73154_d(chunkX, chunkZ);
    }

    /** func_73153_a - populate. No-op: nothing to generate. */
    @Override
    public void func_73153_a(IChunkProvider provider, int chunkX, int chunkZ) {
    }

    /** func_73151_a - unload100OldestChunks (despite the name, also FML's "save" hook signature). */
    @Override
    public boolean func_73151_a(boolean saveAll, IProgressUpdate progress) {
        return false;
    }

    /** func_73156_b - canSave. */
    @Override
    public boolean func_73156_b() {
        return false;
    }

    /** func_73157_c - unloadQueuedChunks. */
    @Override
    public boolean func_73157_c() {
        return false;
    }

    /** func_73148_d - makeString. */
    @Override
    public String func_73148_d() {
        return "UmbChunkProvider";
    }

    /** func_73155_a - getPossibleCreatures. */
    @Override
    @SuppressWarnings("rawtypes")
    public List func_73155_a(EnumCreatureType creatureType, int x, int y, int z) {
        return new ArrayList();
    }

    /** func_147416_a - findClosestStructure. */
    @Override
    public ChunkPosition func_147416_a(World world, String structureName, int x, int y, int z) {
        return null;
    }

    /** func_73152_e - getLoadedChunkCount. */
    @Override
    public int func_73152_e() {
        return 0;
    }

    /** func_82695_e - recreateStructures. No-op. */
    @Override
    public void func_82695_e(int chunkX, int chunkZ) {
    }

    /** func_104112_b - saveExtraData. No-op. */
    @Override
    public void func_104112_b() {
    }
}
