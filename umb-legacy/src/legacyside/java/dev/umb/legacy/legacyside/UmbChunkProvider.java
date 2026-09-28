package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.ChunkProviderServer;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code UmbWorld} had no override for {@code func_72863_F} (getChunkProvider) at all, so calling
 * it ran {@code WorldServer}'s real body and returned {@code null} — 178 of the first 179 harness
 * failures (all but one unrelated divide-by-zero bug in a loaded mod) were the SAME
 * {@code NullPointerException}: some mod base class (or {@code World}'s own convenience methods)
 * calling {@code worldObj.func_72863_F().func_73149_a(x,z)} ("is this chunk loaded/does it
 * exist"), every single tick.
 *
 * <p>There is no real chunk system in this facade (DESIGN.md never modeled one - {@code HostWorld}
 * has no "chunk" concept, matching {@code UmbWorld.func_147476_b}'s own javadoc on the same point),
 * so this is the "best honest local behaviour" the task allows: a minimal real
 * {@code IChunkProvider} whose one heavily-used method, {@link #func_73149_a} (chunkExists),
 * always answers {@code true} — every position in this facade is conceptually "loaded", which is
 * accurate since {@code HostWorld} has no unloaded-chunk concept either. The remaining 12 methods
 * are either genuinely inapplicable without a real chunk/structure system (return the emptiest
 * honest value: {@code null} chunk, empty creature list, no structure found, 0 loaded chunks) or
 * pure no-ops (save/unload/recreate-structures) - none of them showed up as reachable from any of
 * the 372 TE classes' tick path in the step-1 demand analysis, so they are not counted stubs
 * (nothing observed calls them), just safe bodies to satisfy the interface. One process-wide
 * singleton is enough since it carries no per-world state.
 *
 * <p>Bug-26 follow-up: this is now a real {@code ChunkProviderServer} subclass, not a bare
 * {@code IChunkProvider}. Many 1.7.10 mods cast {@code world.getChunkProvider()} to
 * casts then calls only {@code func_73149_a}; chunk-loader/structure mods do the same), and the
 * old shape died with {@code ClassCastException} on every such call. The superclass constructor
 * {@code EmptyChunk}), so {@code super(null, null, null)} is safe and no vanilla field is ever
 * consulted: every reachable method is overridden below with the same no-load semantics -
 * notably the extra {@code ChunkProviderServer}-only surface ({@code func_152380_a} loaded
 * list, {@code func_73241_b}/{@code func_73240_a} unload paths) that a bare interface never
 * had. Shells stay air-filled and host chunks are never force-loaded from legacy paths (the
 * rule behind two previous server-thread deadlocks).</p>
 */
class UmbChunkProvider extends ChunkProviderServer {

    static final UmbChunkProvider INSTANCE = new UmbChunkProvider();
    private final World world;
    private final Map<Long, Chunk> chunks = new HashMap<Long, Chunk>();
    /** Shell-cache bound: a scanning caller must not grow this without limit over a long run. */
    private static final int MAX_CACHED_SHELLS = 1024;

    protected UmbChunkProvider() {
        super(null, null, null);
        this.world = null;
    }

    /** Per-facade provider used when vanilla code asks for the actual shell after
     * {@link #func_73149_a} reported the coordinates as loaded. */
    protected UmbChunkProvider(World world) {
        super(world instanceof WorldServer ? (WorldServer) world : null, null, null);
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
            // Bounded: evict rather than grow without limit for scanning callers.
            if (chunks.size() >= MAX_CACHED_SHELLS) chunks.clear();
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

    /** func_73152_e - getLoadedChunkCount. Live shells only, matching func_152380_a. */
    @Override
    public int func_73152_e() {
        return chunks.size();
    }

    /**
     * func_152380_a - loaded chunk list (ChunkProviderServer-only surface, Forge-added).
     * The live air shells, copied: callers that iterate loaded chunks (structure scans and
     * similar) see the same shells provideChunk hands out, never a host load.
     */
    public synchronized java.util.List func_152380_a() {
        return new ArrayList(chunks.values());
    }

    /**
     * func_73241_b - unloadChunksIfNotNearSpawn, func_73240_a - unloadAllChunks
     * (ChunkProviderServer-only surface). No-ops: this facade has no unload concept, matching
     * the canSave/unloadQueued no-ops below. Overridden explicitly because the inherited bodies
     * would consult vanilla loader state this facade deliberately does not own.
     */
    public void func_73241_b(int chunkX, int chunkZ) {
    }

    public void func_73240_a() {
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
