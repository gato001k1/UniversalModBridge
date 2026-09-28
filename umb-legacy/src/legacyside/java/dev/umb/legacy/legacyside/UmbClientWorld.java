package dev.umb.legacy.legacyside;

import dev.umb.bridge.api.HostWorld;
import net.minecraft.block.Block;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.Explosion;
import dev.umb.legacy.legacyside.input.LegacyInputDiag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Generic option-(b) client facade world. Client legacy handlers must not call the raw WorldClient
 * implementation: its sound/particle paths expect a real networked client world and otherwise
 * disappear into uninitialised client state. This subclass keeps the normal WorldClient type
 * visible to legacy code while forwarding presentation primitives to the authoritative HostWorld.
 * No renderer or GL code is entered here.
 */
public final class UmbClientWorld extends WorldClient {
    private HostWorld host;
    private UmbWorld authoritative;
    private Map<Long, TileEntity> clientTiles;
    private static final Map<UmbWorld, Map<Long, TileEntity>> CLIENT_TILE_CACHES =
            new WeakHashMap<UmbWorld, Map<Long, TileEntity>>();

    private UmbClientWorld() {
        super(null, null, 0, null, null);
    }

    void bindHost(HostWorld value) {
        host = value;
    }

    void bindAuthoritative(UmbWorld value) {
        authoritative = value;
        synchronized (CLIENT_TILE_CACHES) {
            clientTiles = value == null ? new HashMap<Long, TileEntity>()
                    : CLIENT_TILE_CACHES.get(value);
            if (clientTiles == null) {
                clientTiles = new HashMap<Long, TileEntity>();
                if (value != null) CLIENT_TILE_CACHES.put(value, clientTiles);
            }
            if (value != null) {
                Map<Long, Boolean> live = new HashMap<Long, Boolean>();
                for (TileEntity serverTile : value.tileSnapshot()) {
                    if (serverTile == null) continue;
                    Long key = Long.valueOf(pack(serverTile.field_145851_c,
                            serverTile.field_145848_d, serverTile.field_145849_e));
                    live.put(key, Boolean.TRUE);
                    TileEntity clientTile = clientTiles.get(key);
                    if (clientTile == null || clientTile.getClass() != serverTile.getClass()) {
                        clientTile = copyTile(serverTile);
                        if (clientTile != null) clientTiles.put(key, clientTile);
                    } else {
                        clientTile.func_145834_a(this);
                        clientTile.field_145851_c = serverTile.field_145851_c;
                        clientTile.field_145848_d = serverTile.field_145848_d;
                        clientTile.field_145849_e = serverTile.field_145849_e;
                    }
                    // The legacy GUI reads TE fields directly on the client (HBM power/progress
                    // is the concrete example).  Vanilla description packets and a mod's custom
                    // PacketDispatcher path are not universal: many legacy TEs omit transient
                    // counters from NBT, while custom wrappers may not have a real network peer.
                    // Keep the persistent client twin server-authoritative at the existing
                    // per-client-tick rebind boundary.  The helper is type-based and also copies
                    // the NBT state, so this remains universal for tanks, progress, power, and
                    // mod-owned fields without naming a mod or a field.
                    if (clientTile != null) {
                        LegacyClientTilePresenter.syncPersistentState(serverTile, clientTile);
                    }
                }
                clientTiles.keySet().retainAll(live.keySet());
            }
        }
    }

    /**
     * The authoritative facade that owns this client view, or null for a standalone probe view.
     * Capture can be entered with a tile whose world is this client view; callers must not treat
     * that view as a new authoritative universe or discard its shared tile cache.
     */
    UmbWorld authoritativeWorld() {
        return authoritative;
    }

    int clientTileCount() {
        return clientTiles == null ? 0 : clientTiles.size();
    }

    /**
     * One-shot bridge diagnostic. The render thread must not dump the whole tile object graph;
     * report only the owning dimension, requested packed key, hit/miss, and a bounded sample of
     * decoded client-cache keys. This makes namespace/era mistakes distinguishable from a tile
     * that was never created in the authoritative UmbWorld.
     */
    String clientTileDiagnostic(String requestedDimension, int x, int y, int z) {
        synchronized (CLIENT_TILE_CACHES) {
            long requested = pack(x, y, z);
            int authoritativeDimension = Integer.MIN_VALUE;
            try {
                if (authoritative != null && authoritative.field_73011_w != null) {
                    authoritativeDimension = authoritative.field_73011_w.field_76574_g;
                }
            } catch (Throwable ignored) {
                // Keep the diagnostic useful even when a synthetic provider is incomplete.
            }
            StringBuilder keys = new StringBuilder();
            int shown = 0;
            if (clientTiles != null) {
                for (Long value : clientTiles.keySet()) {
                    if (shown++ != 0) keys.append('|');
                    keys.append(unpackX(value.longValue())).append(',')
                            .append(unpackY(value.longValue())).append(',')
                            .append(unpackZ(value.longValue()));
                    if (shown >= 16) break;
                }
            }
            int total = clientTiles == null ? 0 : clientTiles.size();
            return "provider=present requestedDim=" + String.valueOf(requestedDimension)
                    + " authoritativeDim=" + authoritativeDimension
                    + " requested=" + x + "," + y + "," + z
                    + " requestedKey=" + requested
                    + " present=" + (clientTiles != null && clientTiles.containsKey(requested))
                    + " tiles=" + total + " keys=" + keys;
        }
    }

    List<TileEntity> clientTileSnapshot() {
        return clientTiles == null ? new ArrayList<TileEntity>()
                : new ArrayList<TileEntity>(clientTiles.values());
    }

    private TileEntity copyTile(TileEntity source) {
        if (source == null) return null;
        try {
            // Prefer the real no-arg constructor (same rule as the presentation pass): an
            // Unsafe-only twin never runs its constructor, so constructor-created helpers
            // (inventory slot arrays and the like) stay null, and every later NBT re-read
            // that sizes state from those helpers throws before assigning anything - leaving
            // inventory-reading GUIs frozen on empty overlays forever. The allocation fallback
            // inside construct() covers tiles whose constructor cannot run headless.
            TileEntity target = LegacyClientTilePresenter.construct(source);
            target.func_145834_a(this);
            target.field_145851_c = source.field_145851_c;
            target.field_145848_d = source.field_145848_d;
            target.field_145849_e = source.field_145849_e;
            try {
                NBTTagCompound tag = new NBTTagCompound();
                source.func_145841_b(tag);
                target.func_145839_a(tag);
            } catch (Throwable t) {
                // Some test or partially registered tiles cannot resolve their saved-id mapping
                // before FML registry bootstrap. The twin is still a valid target; the first
                // server packet supplies its client state, so do not discard the twin.
                if (LegacyInputDiag.oncePer("client-tile-twin-state:" + source.getClass().getName(),
                        60_000_000_000L)) {
                    LegacyInputDiag.log("client tile twin state seed skipped tile="
                            + source.getClass().getName() + " cause=" + t.getClass().getName());
                }
            }
            return target;
        } catch (Throwable t) {
            if (LegacyInputDiag.oncePer("client-tile-twin:" + source.getClass().getName(),
                    60_000_000_000L)) {
                LegacyInputDiag.log("client tile twin skipped tile=" + source.getClass().getName()
                        + " cause=" + t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
            }
            return null;
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26
                | ((long) z & 0x3FFFFFFL);
    }

    private static int unpack26(long value) {
        int result = (int) (value & 0x3FFFFFFL);
        return (result & 0x2000000) == 0 ? result : result | ~0x3FFFFFF;
    }

    private static int unpackX(long value) { return unpack26(value >>> 38); }
    private static int unpackY(long value) { return (int) ((value >>> 26) & 0xFFFL); }
    private static int unpackZ(long value) { return unpack26(value); }

    @Override
    public void func_72956_a(Entity entity, String name, float volume, float pitch) {
        if (host != null && entity != null && name != null) {
            host.playSound(entity.field_70165_t, entity.field_70163_u, entity.field_70161_v,
                    name, volume, pitch);
        }
    }

    @Override
    public void func_72908_a(double x, double y, double z, String name, float volume, float pitch) {
        if (host != null && name != null) host.playSound(x, y, z, name, volume, pitch);
    }

    /** func_72926_e - vanilla auxiliary sound/effect event; use the same host seam as server code. */
    @Override
    public void func_72926_e(int eventId, int x, int y, int z, int data) {
        LegacyAuxSfx.dispatch(host, eventId, x, y, z, data);
    }

    @Override
    public void func_72980_b(double x, double y, double z, String name, float volume,
                             float pitch, boolean distanceDelay) {
        if (host != null && name != null) host.playSound(x, y, z, name, volume, pitch);
    }

    @Override
    public void func_85173_a(EntityPlayer player, String name, float volume, float pitch) {
        if (host != null && player != null && name != null) {
            host.playSound(player.field_70165_t, player.field_70163_u, player.field_70161_v,
                    name, volume, pitch);
        }
    }

    @Override
    public void func_72869_a(String name, double x, double y, double z,
                             double vx, double vy, double vz) {
        if (host != null && name != null) host.spawnParticle(name, x, y, z, vx, vy, vz);
    }

    @Override
    public Explosion func_72876_a(Entity entity, double x, double y, double z,
                                  float strength, boolean isSmoking) {
        if (host != null) host.explode(x, y, z, strength, false, isSmoking);
        return new Explosion(this, entity, x, y, z, strength);
    }

    @Override
    public Explosion func_72885_a(Entity entity, double x, double y, double z,
                                  float strength, boolean isFlaming, boolean isSmoking) {
        if (host != null) host.explode(x, y, z, strength, isFlaming, isSmoking);
        return new Explosion(this, entity, x, y, z, strength);
    }

    @Override
    public TileEntity func_147438_o(int x, int y, int z) {
        return clientTiles == null ? null : clientTiles.get(Long.valueOf(pack(x, y, z)));
    }

    /**
     * Client TESRs still derive renderer state from the block at the tile position.  The
     * constructor-free WorldClient has no native chunks, so its inherited lookup reports air;
     * that made generic renderers such as HBM doors lose their declaration even though the
     * client tile twin was present.  Read the already-authoritative, non-loading facade instead.
     */
    @Override
    public Block func_147439_a(int x, int y, int z) {
        return authoritative == null ? net.minecraft.init.Blocks.field_150350_a
                : authoritative.func_147439_a(x, y, z);
    }

    @Override
    public int func_72805_g(int x, int y, int z) {
        return authoritative == null ? 0 : authoritative.func_72805_g(x, y, z);
    }

    @Override
    public boolean func_72899_e(int x, int y, int z) {
        return authoritative == null ? y >= 0 && y < 256
                : authoritative.func_72899_e(x, y, z);
    }

    @Override
    public void func_147455_a(int x, int y, int z, TileEntity tile) {
        if (clientTiles != null && tile != null) {
            tile.func_145834_a(this);
            tile.field_145851_c = x;
            tile.field_145848_d = y;
            tile.field_145849_e = z;
            clientTiles.put(Long.valueOf(pack(x, y, z)), tile);
        }
    }
}
