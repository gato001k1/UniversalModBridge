package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Random;
import java.util.UUID;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import cpw.mods.fml.common.registry.GameData;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.command.IEntitySelector;
import net.minecraft.profiler.Profiler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraft.block.material.Material;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import dev.umb.legacy.legacyside.render.LegacyRenderCapture;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.storage.ISaveHandler;

import dev.umb.bridge.api.HostLevel;
import dev.umb.bridge.api.HostEntity;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;

/**
 * The World facade .
 * One {@code UmbWorld} per host {@code ServerLevel}, allocated with {@code Unsafe.allocateInstance} - {@code World}'s two public constructors both need a real {@code ISaveHandler}/{@code WorldSettings}/{@code WorldProvider}/{@code...
 */
public final class UmbWorld extends WorldServer {

    private static final String FACADE = "UmbWorld";

/** Legacy compatibility behavior. */
    private static final String[] STUBBED_MEMBERS = {
            "func_72962_a(canMineBlock)",
            "func_147464_a(scheduleBlockUpdate)",
            // E step 2: added after the demand ranking (g2-facade-demand. named
            // these as high-distinct-TE-class-count World members with no HostWorld primitive to
            // back a real implementation. Each is a safe default, not a crash - see per-method
            // javadoc below for the exact contract gap and the proposed dev.umb.bridge.api addition.
            // upgraded five members OUT of this list (playSoundAtEntity
            // playSoundEffect, spawnParticle, isBlockIndirectlyGettingPowered, newExplosion) -
            // HostWorld now carries playSound/spawnParticle/explode/getRedstonePower for real.
            "func_73040_p(getPlayerManager)",
            "func_72972_b(getSavedLightValue)",
            "func_147447_a(rayTraceBlocks/clip)",
            "func_72976_f(getHeightValue)",
    };

    static {
        // 4 fields + 10 methods + 3 bonus real methods (M1) + 7 E step-2
        // real methods (func_72863_F, func_72872_a, func_72839_b, func_147480_a, func_147444_c,
        // func_147459_d, func_147476_b - the last two upgraded FROM the stub list, see their
        // javadoc) + 1 ENTITY-BRIDGE upgrade (func_72838_d, spawnEntityInWorld, upgraded FROM the
        // stub list below - see its javadoc) + 6 PRESENTATION upgrades (func_72956_a, func_72908_a,
        // func_72869_a, func_72864_z, func_72876_a, func_72885_a - all upgraded FROM the stub list, see their
        // javadoc) = 31 implemented; the curated stub set above is what this build additionally
        // covers with a safe, counted default instead of an inherited-body crash.
        UmbStub.declare(FACADE, 31, STUBBED_MEMBERS.length);
    }

    private HostWorld host;
    /** Non-null only when {@link #host} also implements {@link HostLevel} - see {@link #create}. */
    private HostLevel hostLevel;
    // NOT a field initializer, deliberately: Unsafe.allocateInstance runs NO constructor, so any
    // "= new ..." on a field declaration (which javac weaves into every constructor) would never
    // execute and this map would stay null. create() below assigns it explicitly instead.
    private Map<Long, TileEntity> tiles;
    /**
     * ENTITY-BRIDGE §3.4/§1.3: every legacy Entity this facade has ever seen spawned, so
     * {@link #func_72872_a}/{@link #func_72839_b} (getEntitiesWithinAABB[Excluding]) can be real
     * instead of an always-empty stub. Pruned lazily (dead entries dropped when scanned) rather
     * than eagerly, so a normal tick never pays for cleanup unless a query actually runs. NOT a
     * field initializer, same reason as {@code tiles} above.
     */
    private List<Entity> entities;
    /** One stable boundary handle per legacy entity; prevents duplicate host twins on alternate add paths. */
    private Map<Entity, EntityHandleImpl> entityHandles;
    /** Cached player facades, keyed by the host adapter's stable identity key. */
    private Map<String, UmbPlayer> players;
    /** Native-free replacement for WorldServer.field_73063_M. */
    private UmbPlayerManager playerManager;
    /** Bounded live diagnostics for the MC Heli placement ladder. */
    private int entityDiagLogs;
    private static final int ENTITY_DIAG_LOG_LIMIT = 64;
    private int collisionDiagLogs;
    private static final int COLLISION_DIAG_LOG_LIMIT = 48;
    /** The MC-style aircraft helper calls func_147439_a directly, bypassing func_72945_a. */
    private int collisionLookupDiagLogs;
    private static final int COLLISION_LOOKUP_DIAG_LOG_LIMIT = 64;
    private long lastCollisionLookupDiagNanos;
    private static final long COLLISION_LOOKUP_DIAG_PERIOD_NANOS = 500_000_000L;
    private long entityTickCount;
    private long lastEntityTickLogNanos;
    private Map<Entity, Long> entityTickDiagNanos;
    /** Legacy entities whose host twin was temporarily declined; retried on later ticks. */
    private Set<Entity> orphanEntityDiag;

    /** Never actually invoked - instances come from {@link #create(HostWorld, int)}. Exists only so
     *  javac accepts a WorldServer subclass (it has no no-arg constructor); dead code. */
    @SuppressWarnings("unused")
    private UmbWorld(MinecraftServer server, ISaveHandler saveHandler, String name, int dimensionId,
                      WorldSettings settings, Profiler profiler) {
        super(server, saveHandler, name, dimensionId, settings, profiler);
    }

    public static UmbWorld create(HostWorld host, int dimensionId) {
        UmbWorld w = UmbUnsafe.allocate(UmbWorld.class);
        w.host = host;
        w.hostLevel = (host instanceof HostLevel) ? (HostLevel) host : null;
        w.tiles = new HashMap<Long, TileEntity>();
        w.entities = new ArrayList<Entity>();
        w.entityHandles = new java.util.IdentityHashMap<Entity, EntityHandleImpl>();
        w.players = new LinkedHashMap<String, UmbPlayer>();
        w.entityDiagLogs = 0;
        w.collisionDiagLogs = 0;
        w.collisionLookupDiagLogs = 0;
        w.entityTickCount = 0L;
        w.lastEntityTickLogNanos = 0L;
        w.entityTickDiagNanos = new java.util.IdentityHashMap<Entity, Long>();
        w.orphanEntityDiag = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Entity, Boolean>());
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_73012_v"), new Random(host.randomSeed()));
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_73011_w"), new UmbWorldProvider(dimensionId));
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_72984_F"), new Profiler());
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_72986_A"), new UmbWorldInfo());
        // World.func_72860_G() returns this backing field directly.  Seed the
        // same no-disk handler used by perWorldStorage so WorldEvent.Load and
        // mods that use the vanilla save-handler accessor see the facade.
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_73019_z"),
                new UmbSaveHandler());
        // E step 4: field_73013_u (difficultySetting) was never seeded, so any code path
        // reading it (e.g. a legacy mob-cap check) NPE'd on
        // EnumDifficulty.ordinal() - found by the step-3 mass-tick harness.
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_73013_u"), EnumDifficulty.NORMAL);
        // E step 4: perWorldStorage (unusually, an already-deobfuscated field name - not
        // SRG-mapped) was never seeded either, NPEing legacy saved-data helpers
        // (AnnihilatorSavedData.getData, TomSaveData.forWorld, SatelliteSavedData.getData) the
        // first time any of them ran - found by the step-3 mass-tick harness. See
        // UmbSaveHandler's javadoc for why a null-returning ISaveHandler is the honest fix.
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "perWorldStorage"),
                new net.minecraft.world.storage.MapStorage(new UmbSaveHandler()));
        // E step 4, : func_72863_F/getChunkProvider (the virtual method) was
        // overridden to return UmbChunkProvider.INSTANCE, but some World-internal methods
        // read the backing field
        // field_73020_y DIRECTLY rather than through the getter - seed it too, same instance.
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_73020_y"),
                new UmbChunkProvider(w));
        // WorldServer field_73066_T (entityIdMap): Entity construction and removal
        // paths consult this map even though the facade does not run vanilla chunk updates.
        UmbUnsafe.setField(w, UmbUnsafe.field(WorldServer.class, "field_73066_T"),
                new net.minecraft.util.IntHashMap());
        // WorldServer field_73062_L backs func_73039_n (EntityTracker). Entity
        // implementations are allowed to broadcast their motion; a null tracker poisoned the
        // first real helicopter tick even though the entity had spawned successfully. The real
        // constructor requires a MinecraftServer, which this isolated universe intentionally does
        // not own. Allocate the no-op tracker without that constructor, then replay every
        // constructor-owned field: HBM's TrackerUtil reflectively reads field_72794_c on the
        // projectile's first tick, so leaving the IntHashMap null poisons every real projectile.
        NoopEntityTracker tracker = UmbUnsafe.allocate(NoopEntityTracker.class);
        UmbUnsafe.setField(tracker, UmbUnsafe.field(net.minecraft.entity.EntityTracker.class, "field_72793_b"),
                new java.util.HashSet<Object>());
        UmbUnsafe.setField(tracker, UmbUnsafe.field(net.minecraft.entity.EntityTracker.class, "field_72794_c"),
                new net.minecraft.util.IntHashMap());
        UmbUnsafe.setField(tracker, UmbUnsafe.field(net.minecraft.entity.EntityTracker.class, "field_72795_a"), w);
        UmbUnsafe.setInt(tracker, UmbUnsafe.field(net.minecraft.entity.EntityTracker.class, "field_72792_d"), 512);
        UmbUnsafe.setField(w, UmbUnsafe.field(WorldServer.class, "field_73062_L"), tracker);
        UmbPlayerManager playerManager = UmbPlayerManager.create(w);
        UmbUnsafe.setField(w, UmbUnsafe.field(WorldServer.class, "field_73063_M"), playerManager);
        w.playerManager = playerManager;
        UmbUnsafe.setBoolean(w, UmbUnsafe.field(World.class, "field_72995_K"), false);
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_147482_g"), new ArrayList<TileEntity>());
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_72996_f"), new ArrayList<Object>());
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_73010_i"), new ArrayList<Object>());
        // field_72998_d (collidingBoundingBoxes, fields.csv) was never
        // seeded, so the inherited World.func_72945_a (getCollidingBoundingBoxes, methods.csv -
        // its first statement is field_72998_d.clear()) NPE'd the moment any
        // mod code ran it - MC Heli's MCH_ItemAircraft.onTileClick calls it on every placement
        // attempt. Same seed-a-vanilla-list fix as the three lines above.
        UmbUnsafe.setField(w, UmbUnsafe.field(World.class, "field_72998_d"), new ArrayList<Object>());
        seedUniverseState(w);
        seedChunkSaveLocation(w, dimensionId);
        return w;
    }

    private static final class NoopEntityTracker extends net.minecraft.entity.EntityTracker {
        private NoopEntityTracker() { super(null); }
        @Override public void func_151247_a(Entity entity, net.minecraft.network.Packet packet) {}
        @Override public void func_151248_b(Entity entity, net.minecraft.network.Packet packet) {}
    }

/** Legacy compatibility behavior. */
    private static void seedUniverseState(UmbWorld w) {
        try {
            Class<?> world = World.class;
            UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_72997_g"), new ArrayList<Object>());
            UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_147484_a"), new ArrayList<Object>());
            UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_147483_b"), new ArrayList<Object>());
            UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_73007_j"), new ArrayList<Object>());
            UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_73021_x"), new ArrayList<Object>());
            try {
                UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_72983_E"),
                        new net.minecraft.village.VillageSiege(w));
            } catch (Throwable t) {
                w.host().log("[UMB] UmbWorld village siege failed (non-fatal): " + t);
            }
            UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_83016_L"),
                    java.util.Calendar.getInstance());
            try {
                UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_96442_D"),
                        new net.minecraft.scoreboard.Scoreboard());
            } catch (Throwable t) {
                w.host().log("[UMB] UmbWorld scoreboard failed (non-fatal): " + t);
            }
            UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_72993_I"),
                    new java.util.HashSet<Object>());
            UmbUnsafe.setField(w, UmbUnsafe.field(world, "capturedBlockSnapshots"),
                    new ArrayList<Object>());
            UmbUnsafe.setInt(w, UmbUnsafe.field(world, "field_72990_M"),
                    w.field_73012_v.nextInt(12000));
            UmbUnsafe.setBoolean(w, UmbUnsafe.field(world, "field_72985_G"), true);
            UmbUnsafe.setBoolean(w, UmbUnsafe.field(world, "field_72992_H"), true);
            UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_72994_J"), new int[32768]);
            UmbUnsafe.setLong(w, UmbUnsafe.field(world, "field_73001_c"), 16777215L);
            // finishSetup() never runs in-universe, but direct readers of the vanilla
            // storage field must not NPE: vanilla ctor equivalent is new MapStorage(null).
            UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_72988_C"),
                    new net.minecraft.world.storage.MapStorage(null));
            UmbUnsafe.setInt(w, UmbUnsafe.field(world, "field_73005_l"),
                    new java.util.Random().nextInt());
            UmbUnsafe.setInt(w, UmbUnsafe.field(world, "field_73006_m"), 1013904223);
            try {
                UmbUnsafe.setField(w, UmbUnsafe.field(world, "field_72982_D"),
                        new net.minecraft.village.VillageCollection(w));
            } catch (Throwable t) {
                w.host().log("[UMB] UmbWorld villages failed (non-fatal): " + t);
            }
        } catch (Throwable t) {
            w.host().log("[UMB] UmbWorld seedUniverseState failed (non-fatal): " + t);
        }
    }

    /**
 * {@code WorldServer.getChunkSaveLocation} (methods.csv - body: {@code ((AnvilChunkLoader) field_73059_b.field_73247_e).field_75825_d}) NPE'd inside {@code ForgeChunkManager.loadWorld} - which Forge itself calls from OUR synthetically posted {@code...
 */
    private static volatile boolean chunkDirLogged;
    private static java.io.File chunkSaveLocation(int dimensionId) {
        String gameDir = System.getProperty("umb.legacy.gameDir");
        java.io.File base = (gameDir != null && !gameDir.isEmpty())
                ? new java.io.File(gameDir)
                : new java.io.File(System.getProperty("java.io.tmpdir", "."));
        java.io.File dir = new java.io.File(new java.io.File(base, "umb-legacy-chunks"),
                "DIM" + dimensionId);
        try {
            if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
                // Unusable after all: hand back the base itself. ForgeChunkManager only
                // probes new File(dir, "forcedchunks.dat").exists() (false here) and moves
                // on, so a non-directory can never crash it - and returning null WOULD.
                return base;
            }
        } catch (Throwable t) {
            return base;
        }
        if (!chunkDirLogged) {
            chunkDirLogged = true;
            System.out.println("[umb-legacy] chunk ticket dir: " + dir.getAbsolutePath());
        }
        return dir;
    }

    private static void seedChunkSaveLocation(UmbWorld w, int dimensionId) {
        java.io.File dir = chunkSaveLocation(dimensionId);
        UmbUnsafe.setField(w, UmbUnsafe.field(UmbWorld.class, "chunkSaveDir"), dir);
    }

    /** Set by {@link #create} (see {@link #seedChunkSaveLocation}); always non-null. */
    private java.io.File chunkSaveDir;

    /** See {@link #seedChunkSaveLocation}: a real per-dimension dir, never a throw. */
    @Override
    public java.io.File getChunkSaveLocation() {
        java.io.File dir = chunkSaveDir;
        return dir != null ? dir : super.getChunkSaveLocation();
    }

    /** Registers/reuses exactly one facade per host identity and keeps playerEntities canonical. */
    UmbPlayer registerPlayer(HostPlayer hostPlayer) {
        if (hostPlayer == null) {
            return null;
        }
        String identity = playerIdentity(hostPlayer);
        UmbPlayer player = null;
        for (UmbPlayer candidate : players.values()) {
            if (candidate != null && identity.equals(candidate.hostIdentityKey())) {
                player = preferMounted(player, candidate);
            }
        }
        if (player == null) {
            player = UmbPlayer.allocate(this, hostPlayer);
        } else {
            // Rebinding must not replace either side of the legacy riding graph.  A new
            // HostPlayer adapter is expected on every host dispatch; the UmbPlayer facade is
            // the session identity that legacy Entity code holds in field_70153_n/o.
            player.bindHost(hostPlayer);
        }
        removeDuplicatePlayerEntries(identity, player);
        players.put(identity, player);
        if (!field_73010_i.contains(player)) {
            field_73010_i.add(player);
        }
        player.refreshPosition();
        return player;
    }

    /** Prefer the facade that already participates in a legacy riding graph. */
    private static UmbPlayer preferMounted(UmbPlayer current, UmbPlayer candidate) {
        if (current == null) return candidate;
        boolean currentMounted = current.field_70154_o != null || current.field_70153_n != null;
        boolean candidateMounted = candidate.field_70154_o != null || candidate.field_70153_n != null;
        return candidateMounted && !currentMounted ? candidate : current;
    }

    /** Removes stale aliases and duplicate facade objects without breaking the selected graph. */
    private void removeDuplicatePlayerEntries(String identity, UmbPlayer keep) {
        for (java.util.Iterator<Map.Entry<String, UmbPlayer>> it = players.entrySet().iterator(); it.hasNext();) {
            Map.Entry<String, UmbPlayer> entry = it.next();
            UmbPlayer value = entry.getValue();
            if (value == keep || identity.equals(value == null ? null : value.hostIdentityKey())) {
                it.remove();
            }
        }
        for (java.util.Iterator<EntityPlayer> it = field_73010_i.iterator(); it.hasNext();) {
            EntityPlayer value = it.next();
            if (value == keep) {
                continue;
            }
            if (value instanceof UmbPlayer
                    && identity.equals(((UmbPlayer) value).hostIdentityKey())) {
                it.remove();
            }
        }
    }

    private static String playerIdentity(HostPlayer hostPlayer) {
        String identity = hostPlayer.getIdentityKey();
        if (identity == null || identity.length() == 0) {
            identity = hostPlayer.getName();
        }
        return identity == null ? "<unnamed>" : identity;
    }

    /** Refreshes all cached facades before a vanilla player query. */
    private void refreshPlayers() {
        Set<UmbPlayer> unique = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<UmbPlayer, Boolean>());
        for (UmbPlayer player : new ArrayList<UmbPlayer>(players.values())) {
            if (player != null && unique.add(player)) {
                player.refreshPosition();
            }
        }
    }

/** Legacy compatibility behavior. */
    @Override
    public EntityPlayer func_72977_a(double x, double y, double z, double distance) {
        refreshPlayers();
        return super.func_72977_a(x, y, z, distance);
    }

/** Legacy compatibility behavior. */
    @Override
    public EntityPlayer func_72846_b(double x, double y, double z, double distance) {
        refreshPlayers();
        return super.func_72846_b(x, y, z, distance);
    }

/** Legacy compatibility behavior. */
    @Override
    public EntityPlayer func_72924_a(String name) {
        refreshPlayers();
        return super.func_72924_a(name);
    }

/** Legacy compatibility behavior. */
    @Override
    public EntityPlayer func_152378_a(UUID uuid) {
        refreshPlayers();
        return super.func_152378_a(uuid);
    }

    /** Explicit removal hook for the host-side player lifecycle callback. */
    void unregisterPlayer(HostPlayer hostPlayer) {
        if (hostPlayer == null) {
            return;
        }
        String identity = playerIdentity(hostPlayer);
        UmbPlayer player = players.get(identity);
        if (player != null) {
            for (java.util.Iterator<Map.Entry<String, UmbPlayer>> it = players.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, UmbPlayer> entry = it.next();
                if (entry.getValue() == player || identity.equals(entry.getValue().hostIdentityKey())) it.remove();
            }
            while (field_73010_i.remove(player)) {
                // Remove aliases left by an older adapter boundary, if any.
            }
        }
    }

    public HostWorld host() {
        return host;
    }

    // ---- local tile-entity map: populated by LegacyBridgeImpl, not by setTileEntity ----

    public void putTile(int x, int y, int z, TileEntity te) {
        tiles.put(Long.valueOf(pack(x, y, z)), te);
    }

    public TileEntity getTileAt(int x, int y, int z) {
        return tiles.get(Long.valueOf(pack(x, y, z)));
    }

    /** Snapshot for the generic client facade; callers receive tile identities, never the map. */
    public List<TileEntity> tileSnapshot() {
        return new ArrayList<TileEntity>(tiles.values());
    }

    /** Snapshot used by the synthetic client presentation pass; callers never mutate this map. */
    List<TileEntity> clientTileSnapshot() {
        return new ArrayList<TileEntity>(tiles.values());
    }

    public void removeTileAt(int x, int y, int z) {
        TileEntity removed = tiles.remove(Long.valueOf(pack(x, y, z)));
        if (removed != null) {
            // Tile captures are class/model keyed and must not outlive the authoritative tile's
            // removal.  The next tile of the same class will recapture safely.
            LegacyRenderCapture.invalidateTileCaptures(removed);
        }
    }

    private static long pack(int x, int y, int z) {
        return ((long) x & 0x3FFFFFFL) << 38 | ((long) y & 0xFFFL) << 26 | ((long) z & 0x3FFFFFFL);
    }

    // ---- ENTITY-BRIDGE: local entity list (mirrors the `tiles` map's role for func_147438_o) ----

    /**
     * Registers an already-existing legacy Entity so {@link #func_72872_a}/{@link #func_72839_b}
     * can see it. Called from {@link #func_72838_d} for a fresh spawn, and from
     * {@code LegacyBridgeImpl.restoreEntity} for one reconstructed from a saved twin's NBT blob -
     * both cases hand this an Entity that already fully exists; this method never constructs one.
     */
    public void trackEntity(Entity e) {
        if (e != null) {
            if (!entities.contains(e)) {
                entities.add(e);
            }
            if (!field_72996_f.contains(e)) {
                field_72996_f.add(e);
            }
        }
    }

    /**
 * Registers a restored entity, or returns the already-live handle with the same vanilla UUID.
 * Native chunk reload can construct a new UmbLegacyEntity while the legacy object retained across an unload is still in this universe's loadedEntityList.
 */
    public EntityHandleImpl trackEntityOrExisting(Entity e) {
        if (e == null) return null;
        UUID id = null;
        try {
            id = e.func_110124_au();
        } catch (Throwable ignored) {
            // An entity without a readable UUID cannot participate in deduplication; retain the
            // normal track path rather than inventing an identity.
        }
        if (id != null) {
            for (Entity existing : new ArrayList<Entity>(entities)) {
                if (existing == null || existing == e || existing.field_70128_L) continue;
                try {
                    if (id.equals(existing.func_110124_au())) {
                        EntityHandleImpl existingHandle = entityHandles.get(existing);
                        if (existingHandle == null) {
                            existingHandle = new EntityHandleImpl(existing);
                            entityHandles.put(existing, existingHandle);
                        }
                        return existingHandle;
                    }
                } catch (Throwable ignored) {
                    // One malformed candidate must not prevent the rest of the loaded list from
                    // being checked or turn a reload into a bridge failure.
                }
            }
        }
        trackEntity(e);
        EntityHandleImpl handle = entityHandles.get(e);
        if (handle == null) {
            handle = new EntityHandleImpl(e);
            entityHandles.put(e, handle);
        }
        return handle;
    }

    /** Dead entries are dropped as they are found, not eagerly - see the field's own javadoc. */
    private List<Entity> liveEntitiesSnapshot() {
        List<Entity> live = new ArrayList<Entity>(entities.size());
        java.util.Iterator<Entity> it = entities.iterator();
        while (it.hasNext()) {
            Entity e = it.next();
            if (e == null || e.field_70128_L) {
                if (e != null) {
                    removeHostTwin(e);
                    field_72996_f.remove(e);
                    entityHandles.remove(e);
                }
                it.remove();
            } else {
                live.add(e);
            }
        }
        return live;
    }

    /** One server-tick legacy lifecycle pass. The host twin never calls the legacy tick itself. */
    public void tickEntities() {
        int ticked = 0;
        try {
            // Entity ticks can query World players before tickEvents(START) runs. Refresh every
            // stable facade here so teleports and look changes are visible to legacy entities.
            refreshPlayers();
            List<Entity> snapshot = new ArrayList<Entity>(entities);
            for (Entity entity : snapshot) {
                if (entity == null || entity.field_70128_L) {
                    removeTrackedEntity(entity);
                    continue;
                }
                EntityHandleImpl handle = entityHandles.get(entity);
                if (handle == null) {
                    handle = new EntityHandleImpl(entity);
                    entityHandles.put(entity, handle);
                }
                // Child seats/hitboxes are ordinary legacy entities and can be created before
                // their owner namespace's native type is ready. Retry the generic twin path on
                // every tick so a transient decline cannot become a silent no-mount orphan.
                if (hostLevel != null && !ensureHostTwin(entity)
                        && orphanEntityDiag.add(entity)) {
                    host.log("ENTITY-DIAG orphan legacy entity awaiting host twin class="
                            + entity.getClass().getName() + " id="
                            + EntityList.func_75621_b(entity));
                }
                ticked++;
                boolean traceTick = shouldTraceEntityTick(entity);
                String beforeTick = traceTick ? entityTickState(entity) : null;
                try {
                    if (hostLevel != null) {
                        hostLevel.prepareEntity(handle);
                    }
                    // Vanilla World.func_72866_a snapshots the render baselines at tick start,
                    // before onUpdate ; this loop is that method's analog and
                    // did none of it, so lastTickPos stayed at its one-time seed and every
                    // camera-relative render input lagged the entity. See snapshotTickBaseline.
                    snapshotTickBaseline(entity);
                    float yawBeforeTick = entity.field_70177_z;
                    float pitchBeforeTick = entity.field_70125_A;
                    entity.field_70173_aa++;
                    handle.tick();
                    // Mods that call the vanilla super late in their own onUpdate (MCHeli runs
                    // half its tick before Entity.func_70071_h_) clobber prevRotationYaw/Pitch
                    // mid-tick, freezing every prev/current/partialTick render interpolation
                    // built on them. Restore the start-of-tick values afterwards so the render
                    // always sweeps the full tick delta, exactly like vanilla's super-first
                    // convention - a no-op for mods that already call super first.
                    entity.field_70126_B = yawBeforeTick;
                    entity.field_70127_C = pitchBeforeTick;
                } catch (Throwable t) {
                    host.log("UmbWorld entity tick failed for " + entity.getClass().getName() + ": " + t);
                }
                if (traceTick) {
                    host.log("[UMB-ENTITY] legacy tick identity=0x"
                            + Integer.toHexString(System.identityHashCode(entity))
                            + " class=" + entity.getClass().getName()
                            + " before=" + beforeTick
                            + " after=" + entityTickState(entity)
                            + " valid=" + handle.isValid()
                            + " poison=" + handle.poisonReason());
                }
                if (!handle.isValid()) {
                    removeHostTwin(entity);
                    removeTrackedEntity(entity);
                } else if (hostLevel != null) {
                    hostLevel.syncEntity(handle);
                }
            }
        } finally {
            entityTickCount += ticked;
            long now = System.nanoTime();
            if (now - lastEntityTickLogNanos >= 5_000_000_000L) {
                lastEntityTickLogNanos = now;
                try {
                    host.log("[UMB-ENTITY] ticked " + ticked + " entities total="
                            + entityTickCount + " live=" + (entities == null ? 0 : entities.size()));
                } catch (Throwable ignored) {
                    // Diagnostics must never break the entity lifecycle pass.
                }
            }
        }
    }

    /**
 * Tick-start render-baseline snapshot: the exact five writes vanilla 1.7.10 World.func_72866_a performs before onUpdate ( against build/legacy/1.7.10-forge-srg-runtime-fields.jar: lastTickPosX/Y/Z = pos, prevRotationYaw = rotationYaw, prevRotationPitch =...
 */
    private static void snapshotTickBaseline(Entity entity) {
        entity.field_70142_S = entity.field_70165_t;
        entity.field_70137_T = entity.field_70163_u;
        entity.field_70136_U = entity.field_70161_v;
        entity.field_70126_B = entity.field_70177_z;
        entity.field_70127_C = entity.field_70125_A;
    }

    /** Per-entity boundary trace; reflection keeps this diagnostic universal across mod entities. */
    private boolean shouldTraceEntityTick(Entity entity) {
        if (entity == null || entityTickDiagNanos == null) return false;
        long now = System.nanoTime();
        Long previous = entityTickDiagNanos.get(entity);
        if (previous != null && now - previous.longValue() < 5_000_000_000L) return false;
        entityTickDiagNanos.put(entity, Long.valueOf(now));
        return true;
    }

    private static String entityTickState(Entity entity) {
        StringBuilder out = new StringBuilder("age=").append(entity.field_70173_aa)
                .append(" dead=").append(entity.field_70128_L)
                .append(" pos=").append(entity.field_70165_t).append(',')
                .append(entity.field_70163_u).append(',').append(entity.field_70161_v)
                .append(" motion=").append(entity.field_70159_w).append(',')
                .append(entity.field_70181_x).append(',').append(entity.field_70179_y)
                .append(" onGround=").append(entity.field_70122_E)
                .append(" box=").append(boxText(entity.field_70121_D));
        Class<?> type = entity.getClass();
        while (type != null && type != Object.class) {
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                fields = new Field[0];
            }
            for (Field field : fields) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (field.getType() != Integer.TYPE || !name.contains("cooldown")
                        || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    if (!field.isAccessible()) field.setAccessible(true);
                    out.append(' ').append(field.getName()).append('=').append(field.getInt(entity));
                } catch (Throwable ignored) {
                    out.append(' ').append(field.getName()).append("=<unreadable>");
                }
            }
            type = type.getSuperclass();
        }
        return out.toString();
    }

    private void removeTrackedEntity(Entity entity) {
        if (entity == null) return;
        entities.remove(entity);
        if (orphanEntityDiag != null) orphanEntityDiag.remove(entity);
        if (entityTickDiagNanos != null) entityTickDiagNanos.remove(entity);
        field_72996_f.remove(entity);
        EntityHandleImpl handle = entityHandles.remove(entity);
        if (handle != null) removeHostTwin(handle);
    }

    private void removeHostTwin(Entity entity) {
        if (entity == null) return;
        removeHostTwin(entityHandles.get(entity));
    }

    private void removeHostTwin(EntityHandleImpl handle) {
        if (handle != null && hostLevel != null) {
            hostLevel.removeEntity(handle);
        }
    }

    private boolean ensureHostTwin(Entity entity) {
        if (entity == null || entity.field_70128_L) return false;
        trackEntity(entity);
        EntityHandleImpl handle = entityHandles.get(entity);
        if (handle == null) {
            handle = new EntityHandleImpl(entity);
            entityHandles.put(entity, handle);
        }
        if (hostLevel == null) {
            return true;
        }
        try {
            return hostLevel.spawnEntity(handle);
        } catch (Throwable t) {
            host.log("UmbWorld entity twin spawn failed for " + entity.getClass().getName() + ": " + t);
            return false;
        }
    }

    /**
 * field_70121_D (boundingBox) / func_72326_a (AxisAlignedBB.intersectsWith) - both , real vanilla bytecode, no facade involved on either side
 */
    private static boolean intersects(Entity e, AxisAlignedBB box) {
        AxisAlignedBB bb = e.field_70121_D;
        return bb != null && bb.func_72326_a(box);
    }

    /** Adds the current host-native view for one legacy AABB query. */
    @SuppressWarnings("rawtypes")
    private void addHostEntities(Class entityClass, AxisAlignedBB box, Entity excluded, List result) {
        addHostEntities(entityClass, box, excluded, null, result);
    }

    @SuppressWarnings("rawtypes")
    private void addHostEntities(Class entityClass, AxisAlignedBB box, Entity excluded,
                                 IEntitySelector selector, List result) {
        if (box == null) return;
        String excludedIdentity = excluded instanceof UmbHostEntity
                ? ((UmbHostEntity) excluded).hostIdentity() : null;
        List<HostEntity> nativeEntities;
        try {
            nativeEntities = host.getEntities(box.field_72340_a, box.field_72338_b, box.field_72339_c,
                    box.field_72336_d, box.field_72337_e, box.field_72334_f, excludedIdentity);
        } catch (Throwable t) {
            host.log("UmbWorld host entity query failed: " + t.getClass().getName());
            return;
        }
        if (nativeEntities == null) return;
        for (HostEntity nativeEntity : nativeEntities) {
            if (nativeEntity == null) continue;
            UmbHostEntity facade;
            try {
                facade = new UmbHostEntity(this, nativeEntity);
            } catch (Throwable t) {
                host.log("UmbWorld host entity facade failed: " + t.getClass().getName());
                continue;
            }
            if ((entityClass == null || entityClass.isInstance(facade))
                    && (selector == null || selector.func_82704_a(facade))
                    && (excluded == null || facade != excluded)) {
                result.add(facade);
            }
        }
    }

    private String legacyIdOf(Block b) {
        String id = GameData.getBlockRegistry().func_148750_c(b);
        return id == null ? "minecraft:air" : id;
    }

// Legacy compatibility behavior.

    /** func_147439_a - getBlock */
    @Override
    public Block func_147439_a(int x, int y, int z) {
        String id = host.getBlockId(x, y, z);
        Block b = GameData.getBlockRegistry().get(id);
        long now = System.nanoTime();
        if (collisionLookupDiagLogs < COLLISION_LOOKUP_DIAG_LOG_LIMIT
                && (collisionLookupDiagLogs == 0
                    || now - lastCollisionLookupDiagNanos >= COLLISION_LOOKUP_DIAG_PERIOD_NANOS)) {
            collisionLookupDiagLogs++;
            lastCollisionLookupDiagNanos = now;
            try {
                host.log("[UMB-ENTITY] collision block x=" + x + " y=" + y + " z=" + z
                        + " id=" + id + " legacy=" + (b == null ? "null" : b.getClass().getName())
                        + " shape=" + (b == null ? "null" : boxText(b.func_149668_a(this, x, y, z)))
                        + " caller=" + collisionLookupCaller());
            } catch (Throwable ignored) {
                // Evidence must never change the block lookup result or poison a tick.
            }
        }
        return b;
    }

    /** Top non-facade caller for the bounded terrain seam diagnostic. */
    private static String collisionLookupCaller() {
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String owner = frame.getClassName();
            if (owner.equals(Thread.class.getName())
                    || owner.equals(UmbWorld.class.getName())) {
                continue;
            }
            return owner + '#' + frame.getMethodName() + ':' + frame.getLineNumber();
        }
        return "unknown";
    }

    /** func_72805_g - getBlockMetadata */
    @Override
    public int func_72805_g(int x, int y, int z) {
        return host.getMeta(x, y, z);
    }

    /**
     * func_72899_e - blockExists. The vanilla body asks the chunk provider whether a native
     * chunk is loaded. This facade deliberately has no native chunks: HostWorld is the loaded
     * terrain source, and MC-style collision helpers use this predicate as the gate before
     * calling func_147439_a/Block.func_149743_a. Returning the host-backed 1.7.10 height range
     * keeps that gate open; an empty host coordinate still resolves to registered air and adds
     * no collision shape.
     */
    @Override
    public boolean func_72899_e(int x, int y, int z) {
        return y >= 0 && y < 256;
    }

    /** func_147438_o - getTileEntity, backed by the local map (see class javadoc) */
    @Override
    public TileEntity func_147438_o(int x, int y, int z) {
        return getTileAt(x, y, z);
    }

    /** func_147465_d - setBlock(x,y,z,block,meta,flags) */
    @Override
    public boolean func_147465_d(int x, int y, int z, Block block, int meta, int flags) {
        host.setBlock(x, y, z, legacyIdOf(block), meta, flags);
        return true;
    }

    /** func_72921_c - setBlockMetadataWithNotify */
    @Override
    public boolean func_72921_c(int x, int y, int z, int meta, int flags) {
        host.setMeta(x, y, z, meta, flags);
        return true;
    }

    /** func_147449_b - setBlock(x,y,z,block) simple, meta defaults to 0, standard notify flags */
    @Override
    public boolean func_147449_b(int x, int y, int z, Block block) {
        host.setBlock(x, y, z, legacyIdOf(block), 0, 3);
        return true;
    }

    /** func_147468_f - setBlockToAir */
    @Override
    public boolean func_147468_f(int x, int y, int z) {
        host.removeBlock(x, y, z);
        removeTileAt(x, y, z);
        return true;
    }

    /** func_147471_g - markBlockForUpdate */
    @Override
    public void func_147471_g(int x, int y, int z) {
        host.markBlockDirty(x, y, z);
    }

    /** func_147453_f - notify comparator/output neighbors through the host's real neighbor graph. */
    @Override
    public void func_147453_f(int x, int y, int z, Block block) {
        host.notifyNeighbors(x, y, z);
    }

    /** func_82737_E - getTotalWorldTime */
    @Override
    public long func_82737_E() {
        return host.getTotalTime();
    }

    // ---- bonus reals: trivial, directly useful, not counted in the 14 ----

    /** func_147455_a - setTileEntity(with notify); safe no-op on the host side (block/meta already
     *  went through the real setBlock path), but keeps the local map authoritative for late binds. */
    @Override
    public void func_147455_a(int x, int y, int z, TileEntity te) {
        if (te != null) {
            putTile(x, y, z, te);
        }
    }

    /** func_147475_p - removeTileEntity */
    @Override
    public void func_147475_p(int x, int y, int z) {
        removeTileAt(x, y, z);
    }

    /** func_147437_c - isAirBlock */
    @Override
    public boolean func_147437_c(int x, int y, int z) {
        return "minecraft:air".equals(host.getBlockId(x, y, z));
    }

    // E step 2: additional real implementations, driven by g2-facade-demand.md

    /** func_72863_F - getChunkProvider. The single highest-leverage fix the step-3 mass-tick
     *  harness surfaced: with NO override here at all, this fell through to WorldServer's real
     *  body (returns the unseeded {@code field_73020_y}, i.e. null), and 178 of the harness's
     *  first 179 failures were the identical NullPointerException from some caller doing
     *  {@code func_72863_F().func_73149_a(x,z)} every tick. See {@link UmbChunkProvider}'s javadoc
     *  for why a minimal always-loaded chunk provider is the honest fix. */
    @Override
    public IChunkProvider func_72863_F() {
        return UmbChunkProvider.INSTANCE;
    }

    /** func_72872_a - getEntitiesWithinAABB over legacy entities plus native host facades. */
    @Override
    @SuppressWarnings("rawtypes")
    public List func_72872_a(Class entityClass, AxisAlignedBB box) {
        refreshPlayers();
        List result = new ArrayList();
        for (Object player : field_73010_i) {
            if ((entityClass == null || entityClass.isInstance(player))
                    && intersects((Entity) player, box)) {
                result.add(player);
            }
        }
        for (Entity e : liveEntitiesSnapshot()) {
            if ((entityClass == null || entityClass.isInstance(e)) && intersects(e, box)) {
                result.add(e);
            }
        }
        addHostEntities(entityClass, box, null, result);
        return result;
    }

    /** func_82733_a - selectEntities. Keep the selector on the legacy facade, then apply it to
     * native host facades as well; callers such as projectile and AoE code use this overload
     * instead of func_72872_a when they need a selector predicate. */
    @Override
    @SuppressWarnings("rawtypes")
    public List func_82733_a(Class entityClass, AxisAlignedBB box, IEntitySelector selector) {
        refreshPlayers();
        List result = new ArrayList();
        for (Object player : field_73010_i) {
            Entity e = (Entity) player;
            if ((entityClass == null || entityClass.isInstance(e))
                    && (selector == null || selector.func_82704_a(e))
                    && intersects(e, box)) {
                result.add(e);
            }
        }
        for (Entity e : liveEntitiesSnapshot()) {
            if ((entityClass == null || entityClass.isInstance(e))
                    && (selector == null || selector.func_82704_a(e))
                    && intersects(e, box)) {
                result.add(e);
            }
        }
        addHostEntities(entityClass, box, null, selector, result);
        return result;
    }

    /** func_72839_b - getEntitiesWithinAABBExcludingEntity. Same as {@link #func_72872_a} but
     *  skips the passed-in entity (and its whole "ridden by" chain is NOT modeled - out of scope). */
    @Override
    @SuppressWarnings("rawtypes")
    public List func_72839_b(net.minecraft.entity.Entity entity, AxisAlignedBB box) {
        refreshPlayers();
        List result = new ArrayList();
        for (Object player : field_73010_i) {
            if (player != entity && intersects((Entity) player, box)) {
                result.add(player);
            }
        }
        for (Entity e : liveEntitiesSnapshot()) {
            if (e != entity && intersects(e, box)) {
                result.add(e);
            }
        }
        addHostEntities(null, box, entity, result);
        entityDiag("ENTITY-DIAG func_72839_b size=" + result.size()
                + " box=" + boxText(box)
                + " first=" + (result.isEmpty() ? "none" : result.get(0).getClass().getName()));
        return result;
    }

    /**
 * func_72945_a - getCollidingBoundingBoxes.
 * Keep vanilla's block/entity collision contract, but expose the result at the entity-placement seam.
 */
    @Override
    @SuppressWarnings("rawtypes")
    public List func_72945_a(Entity entity, AxisAlignedBB box) {
        List result = super.func_72945_a(entity, box);
        if (box != null) {
            addHostEntityBoxes(entity, box, result);
        }
        entityDiag("ENTITY-DIAG func_72945_a size=" + result.size()
                + " box=" + boxText(box)
                + " first=" + (result.isEmpty() ? "none" : boxText((AxisAlignedBB) result.get(0))));
        collisionDiag(entity, box, result);
        return result;
    }

    @SuppressWarnings("rawtypes")
    private void addHostEntityBoxes(Entity excluded, AxisAlignedBB box, List result) {
        String excludedIdentity = excluded instanceof UmbHostEntity
                ? ((UmbHostEntity) excluded).hostIdentity() : null;
        List<HostEntity> nativeEntities;
        try {
            nativeEntities = host.getEntities(box.field_72340_a, box.field_72338_b,
                    box.field_72339_c, box.field_72336_d, box.field_72337_e, box.field_72334_f,
                    excludedIdentity);
        } catch (Throwable t) {
            host.log("UmbWorld host collision query failed: " + t.getClass().getName());
            return;
        }
        if (nativeEntities == null) return;
        for (HostEntity nativeEntity : nativeEntities) {
            if (nativeEntity == null) continue;
            try {
                UmbHostEntity facade = new UmbHostEntity(this, nativeEntity);
                if (excluded != facade && intersects(facade, box)) {
                    result.add(facade.field_70121_D);
                }
            } catch (Throwable t) {
                host.log("UmbWorld host collision facade failed: " + t.getClass().getName());
            }
        }
    }

    /** Bounded evidence for host block translation at the exact collision seam. */
    private void collisionDiag(Entity entity, AxisAlignedBB box, List result) {
        if (collisionDiagLogs >= COLLISION_DIAG_LOG_LIMIT || box == null) {
            return;
        }
        collisionDiagLogs++;
        try {
            int minY = MathHelper.func_76128_c(box.field_72338_b);
            int maxY = MathHelper.func_76128_c(box.field_72337_e + 1.0D);
            int centerX = MathHelper.func_76128_c((box.field_72340_a + box.field_72336_d) * 0.5D);
            int centerZ = MathHelper.func_76128_c((box.field_72339_c + box.field_72334_f) * 0.5D);
            StringBuilder blocks = new StringBuilder();
            for (int y = minY - 1; y < maxY; y++) {
                if (blocks.length() > 0) blocks.append(';');
                String id = host.getBlockId(centerX, y, centerZ);
                Block block = func_147439_a(centerX, y, centerZ);
                blocks.append(centerX).append(',').append(y).append(',').append(centerZ)
                        .append('=').append(id).append(" legacy=").append(block == null ? "null"
                                : block.getClass().getName());
                if (block != null) {
                    try {
                        blocks.append(" shape=").append(boxText(block.func_149668_a(this,
                                centerX, y, centerZ)));
                    } catch (Throwable t) {
                        blocks.append(" shape=error:").append(t.getClass().getSimpleName());
                    }
                }
            }
            host.log("[UMB-ENTITY] collision seam entity=" + (entity == null ? "null"
                    : entity.getClass().getName()) + " box=" + boxText(box)
                    + " result=" + result.size() + " centerBlocks=" + blocks);
        } catch (Throwable ignored) {
            // Collision diagnostics are evidence only. A host/logger/block lookup failure must
            // never poison the vanilla collision result or the entity tick that requested it.
        }
    }

    /**
 * func_147480_a - destroyBlock(x,y,z,dropBlock).
 * Vanilla body, (build/legacy/1.7.10-forge-srg-runtime-fields.jar): air refuses (false), otherwise the 2001 aux event fires, {@code Block.getDrops} runs when {@code dropBlock} is set, and the block is removed.
 */
    @Override
    public boolean func_147480_a(int x, int y, int z, boolean dropBlock) {
        Block block = func_147439_a(x, y, z);
        if (block == null) {
            // Unmapped id: legacy behaviour preserved (remove, no drops to compute).
            host.removeBlock(x, y, z);
            removeTileAt(x, y, z);
            return true;
        }
        if (block == net.minecraft.init.Blocks.field_150350_a) {
            return false;
        }
        int meta = func_72805_g(x, y, z);
        func_72926_e(2001, x, y, z, Block.func_149682_b(block) + (meta << 12));
        if (dropBlock) {
            dropBlockDrops(this, block, meta, x, y, z);
        }
        host.removeBlock(x, y, z);
        removeTileAt(x, y, z);
        return true;
    }

    /**
     * The drop half of {@link #func_147480_a}, vanilla
     * {@code Block.dropBlockAsItemWithChance} order without the EntityItem: HarvestDrops
     * event (fortune 0, chance 1, null harvester - exactly what vanilla passes outside a
     * real harvest), then one {@link HostWorld#dropItem} per surviving stack. Public and
     * static so headless tests can drive it with a synthetic Block (no GameData registry
     * needed).
     */
    public static void dropBlockDrops(UmbWorld world, Block block, int meta, int x, int y, int z) {
        java.util.ArrayList<ItemStack> drops;
        try {
            drops = block.getDrops(world, x, y, z, meta, 0);
        } catch (Throwable t) {
            world.host().log("destroyBlock: getDrops threw for " + block.getClass().getName() + ": " + t);
            return;
        }
        if (drops == null) {
            return;
        }
        float chance;
        try {
            chance = net.minecraftforge.event.ForgeEventFactory.fireBlockHarvesting(
                    drops, world, block, x, y, z, meta, 0, 1.0F, false, null);
        } catch (Throwable t) {
            world.host().log("destroyBlock: HarvestDrops event threw for "
                    + block.getClass().getName() + ": " + t);
            return;
        }
        for (ItemStack drop : drops) {
            if (drop == null || drop.field_77994_a <= 0) {
                continue;
            }
            try {
                if (world.field_73012_v.nextFloat() <= chance) {
                    world.host().dropItem(x + 0.5D, y + 0.5D, z + 0.5D,
                            UmbItemConv.toStackData(drop));
                }
            } catch (Throwable t) {
                world.host().log("destroyBlock: dropItem threw for "
                        + block.getClass().getName() + ": " + t);
                return;
            }
        }
    }

    /** func_147444_c - notifyBlockChange. Real: the closest HostWorld primitive is markBlockDirty,
     *  same mapping choice already used by func_147453_f/notifyBlockOfNeighborChange's javadoc. */
    @Override
    public void func_147444_c(int x, int y, int z, Block block) {
        host.notifyNeighbors(x, y, z);
    }

/** Legacy compatibility behavior. */
    @Override
    public void func_147459_d(int x, int y, int z, Block block) {
        host.notifyNeighbors(x, y, z);
    }

/** Legacy compatibility behavior. */
    @Override
    public void func_147476_b(int x, int y, int z, TileEntity te) {
        host.markBlockDirty(x, y, z);
    }

    /**
 * func_72864_z - isBlockIndirectlyGettingPowered.
 * PRESENTATION upgrade FROM the stub list: real, backed by the {@code HostWorld.getRedstonePower} contract addition
 */
    @Override
    public boolean func_72864_z(int x, int y, int z) {
        return host.getRedstonePower(x, y, z) > 0;
    }

/** Legacy compatibility behavior. */
    @Override
    public Explosion func_72876_a(net.minecraft.entity.Entity entity, double x, double y, double z,
                                   float strength, boolean isSmoking) {
        host.explode(x, y, z, strength, false, isSmoking);
        return new Explosion(this, entity, x, y, z, strength);
    }

    /**
 * func_72885_a - newExplosion.
 * PRESENTATION upgrade FROM the stub list: the host now detonates a REAL native 26.2 explosion via {@code HostWorld.explode} - block damage gated on isSmoking (1.7.10's "actually destroys terrain" flag), fire on isFlaming, sound...
 */
    @Override
    public Explosion func_72885_a(net.minecraft.entity.Entity entity, double x, double y, double z,
                                   float strength, boolean isFlaming, boolean isSmoking) {
        host.explode(x, y, z, strength, isFlaming, isSmoking);
        return new Explosion(this, entity, x, y, z, strength);
    }

    /** func_73040_p - getPlayerManager. The manager delegates watcher state to HostWorld. */
    @Override
    public PlayerManager func_73040_p() {
        return playerManager;
    }

    /** func_72972_b - getSavedLightValue. No light data in HostWorld - counted stub, safe default
     *  "fully lit" (15) so light-dependent machine logic (e.g. solar panels, light sensors) does
     *  not spuriously behave as if in total darkness. Proposed contract addition:
     *  {@code HostWorld.getLight(x,y,z)}. */
    @Override
    public int func_72972_b(EnumSkyBlock type, int x, int y, int z) {
        UmbStub.hit(FACADE, "func_72972_b(getSavedLightValue)");
        return 15;
    }

    /** func_147447_a - rayTraceBlocks (clip). SRG-verified block traversal using the existing host
     *  block/meta reads and the legacy Block collision hooks func_149668_a/func_149731_a. */
    @Override
    public MovingObjectPosition func_147447_a(Vec3 from, Vec3 to, boolean stopOnLiquid,
                                               boolean ignoreBoundingBlock, boolean returnLastUncollidableBlock) {
        if (from == null || to == null || !finite(from) || !finite(to)) {
            entityDiag("ENTITY-DIAG func_147447_a from=" + vecText(from) + " to=" + vecText(to)
                    + " result=null invalid");
            return null;
        }
        int x = MathHelper.func_76128_c(from.field_72450_a);
        int y = MathHelper.func_76128_c(from.field_72448_b);
        int z = MathHelper.func_76128_c(from.field_72449_c);
        int endX = MathHelper.func_76128_c(to.field_72450_a);
        int endY = MathHelper.func_76128_c(to.field_72448_b);
        int endZ = MathHelper.func_76128_c(to.field_72449_c);

        for (int steps = 0; steps <= 200; steps++) {
            MovingObjectPosition hit = rayTraceBlock(x, y, z, from, to, stopOnLiquid,
                    ignoreBoundingBlock);
            if (hit != null) {
                entityDiag("ENTITY-DIAG func_147447_a from=" + vecText(from) + " to=" + vecText(to)
                        + " result=" + hit.field_72313_a + " xyz=" + hit.field_72311_b + ","
                        + hit.field_72312_c + "," + hit.field_72309_d + " hit=" + vecText(hit.field_72307_f));
                return hit;
            }
            if (x == endX && y == endY && z == endZ) {
                entityDiag("ENTITY-DIAG func_147447_a from=" + vecText(from) + " to=" + vecText(to)
                        + " result=null");
                return null;
            }

            double tx = Double.POSITIVE_INFINITY;
            double ty = Double.POSITIVE_INFINITY;
            double tz = Double.POSITIVE_INFINITY;
            double dx = to.field_72450_a - from.field_72450_a;
            double dy = to.field_72448_b - from.field_72448_b;
            double dz = to.field_72449_c - from.field_72449_c;
            if (dx > 0.0D) {
                tx = (x + 1.0D - from.field_72450_a) / dx;
            } else if (dx < 0.0D) {
                tx = (x - from.field_72450_a) / dx;
            }
            if (dy > 0.0D) {
                ty = (y + 1.0D - from.field_72448_b) / dy;
            } else if (dy < 0.0D) {
                ty = (y - from.field_72448_b) / dy;
            }
            if (dz > 0.0D) {
                tz = (z + 1.0D - from.field_72449_c) / dz;
            } else if (dz < 0.0D) {
                tz = (z - from.field_72449_c) / dz;
            }
            if (tx < ty && tx < tz) {
                x += dx > 0.0D ? 1 : -1;
            } else if (ty < tz) {
                y += dy > 0.0D ? 1 : -1;
            } else {
                z += dz > 0.0D ? 1 : -1;
            }
        }
        entityDiag("ENTITY-DIAG func_147447_a from=" + vecText(from) + " to=" + vecText(to)
                + " result=null step-limit");
        return null;
    }

    /** func_72933_a - rayTraceBlocks excluding liquids; methods.csv names this exact overload. */
    @Override
    public MovingObjectPosition func_72933_a(Vec3 from, Vec3 to) {
        return func_147447_a(from, to, false, false, false);
    }

    private MovingObjectPosition rayTraceBlock(int x, int y, int z, Vec3 from, Vec3 to,
                                                boolean stopOnLiquid, boolean ignoreBoundingBlock) {
        Block block = func_147439_a(x, y, z);
        if (block == null || block == net.minecraft.init.Blocks.field_150350_a) {
            return null;
        }
        if (!stopOnLiquid && block.func_149688_o().func_76224_d()) {
            return null;
        }
        AxisAlignedBB bounds = block.func_149668_a(this, x, y, z);
        if (!ignoreBoundingBlock && bounds == null) {
            return null;
        }
        return block.func_149731_a(this, x, y, z, from, to);
    }

    private static boolean finite(Vec3 v) {
        return !Double.isNaN(v.field_72450_a) && !Double.isNaN(v.field_72448_b)
                && !Double.isNaN(v.field_72449_c) && !Double.isInfinite(v.field_72450_a)
                && !Double.isInfinite(v.field_72448_b) && !Double.isInfinite(v.field_72449_c);
    }

    private void entityDiag(String message) {
        if (entityDiagLogs++ < ENTITY_DIAG_LOG_LIMIT) {
            host.log(message);
        }
    }

    private static String vecText(Vec3 v) {
        return v == null ? "null" : v.field_72450_a + "," + v.field_72448_b + "," + v.field_72449_c;
    }

    private static String boxText(AxisAlignedBB box) {
        return box == null ? "null" : box.field_72340_a + "," + box.field_72338_b + "," + box.field_72339_c
                + "->" + box.field_72336_d + "," + box.field_72337_e + "," + box.field_72334_f;
    }

    /** func_72976_f - getHeightValue. No terrain-height concept in HostWorld - counted stub, safe
     *  default (ground level, 64) rather than 0 (which would make every machine think it is deep
     *  underground). Proposed contract addition: {@code HostWorld.getHeight(x,z)}. */
    @Override
    public int func_72976_f(int x, int z) {
        UmbStub.hit(FACADE, "func_72976_f(getHeightValue)");
        return 64;
    }

    // ---- counted stubs: curated from facade-fidelity.md's ranked legacy World surface ----

    @Override
    public boolean func_72962_a(net.minecraft.entity.player.EntityPlayer player, int x, int y, int z) {
        UmbStub.hit(FACADE, "func_72962_a(canMineBlock)");
        return true;
    }

    @Override
    public void func_147464_a(int x, int y, int z, Block block, int delay) {
        UmbStub.hit(FACADE, "func_147464_a(scheduleBlockUpdate)");
        host.scheduleTick(x, y, z, delay);
    }

    /** func_72956_a - playSoundAtEntity. PRESENTATION upgrade FROM the stub list: real, forwarded
     *  to the host at the entity's own position (field_70165_t/u/v = posX/Y/Z, fields.csv-verified).
     *  The host maps the legacy name and skips-and-counts what it cannot translate. */
    @Override
    public void func_72956_a(net.minecraft.entity.Entity entity, String name, float volume, float pitch) {
        if (entity == null || name == null) {
            return;
        }
        host.playSound(entity.field_70165_t, entity.field_70163_u, entity.field_70161_v, name, volume, pitch);
    }

    /** func_72908_a - playSoundEffect. PRESENTATION upgrade FROM the stub list: real. */
    @Override
    public void func_72908_a(double x, double y, double z, String name, float volume, float pitch) {
        if (name == null) {
            return;
        }
        host.playSound(x, y, z, name, volume, pitch);
    }

    /** func_72926_e - vanilla auxiliary sound/effect event; keep the protocol ids generic. */
    @Override
    public void func_72926_e(int eventId, int x, int y, int z, int data) {
        LegacyAuxSfx.dispatch(host, eventId, x, y, z, data);
    }

    /**
 * func_72869_a - spawnParticle.
 * PRESENTATION upgrade FROM the stub list: real; (vx,vy,vz) is the single particle's MOTION in 1.7.10 semantics, which the host reproduces exactly with 26.2's sendParticles count=0 velocity mode
 */
    @Override
    public void func_72869_a(String name, double x, double y, double z, double vx, double vy, double vz) {
        if (name == null) {
            return;
        }
        host.spawnParticle(name, x, y, z, vx, vy, vz);
    }

    // ---- ENTITY-BRIDGE: func_72838_d upgraded from a counted stub to real (was: "always returns
    // false", per ENTITY-BRIDGE.md's §0 - "every legacy projectile, missile, and mob-spawn call today
    // silently no-ops"; this is the entire point of the entity bridge). ----

    /**
 * func_72838_d - spawnEntityInWorld.
 * The legacy Entity object ALREADY EXISTS here (legacy code constructed it: "new EntityBullet(world,...)" then "world.spawnEntityInWorld(entity)") - this method does not construct anything legacy-side, it only wraps the...
 */
    @Override
    public boolean func_72838_d(net.minecraft.entity.Entity entity) {
        if (entity == null) {
            return false;
        }
        try {
            host.log("ENTITY-DIAG spawnEntityInWorld enter class=" + entity.getClass().getName()
                    + " id=" + EntityList.func_75621_b(entity)
                    + " pos=" + entity.field_70165_t + "," + entity.field_70163_u + "," + entity.field_70161_v);
            // Vanilla posts the join event BEFORE the list add (see the method javadoc for the
            // bytecode evidence); a cancelled join never reaches the list or a twin.
            if (joinCancelledByPost(postEntityJoinWorldEvent(entity), entity.field_98038_p)) {
                host.log("ENTITY-DIAG spawnEntityInWorld cancelled class=" + entity.getClass().getName()
                        + " id=" + EntityList.func_75621_b(entity));
                return false;
            }
            if (entity instanceof net.minecraft.entity.item.EntityItem) {
                // UNIVERSAL dropped-item shortcut: vanilla Block.dropBlockAsItem
                // and every mod drop path spawn a legacy
                // EntityItem through this exact method, and the host has no EntityItem
                // twin (it would tick locally forever, invisible and unpickupable). Convert
                // to a host item entity instead; the host applies its own drop rules.
                // Never constructs anything else, never touches the entity lists.
                return spawnDropAsHostItem((net.minecraft.entity.item.EntityItem) entity);
            }
            // World.spawnEntityInWorld's body also touches a real Chunk. This
            // facade has no native chunks, so reproduce its loadedEntityList/onEntityAdded part
            // directly and keep the chunk-dependent body out of the isolated universe.
            if (!field_72996_f.contains(entity)) {
                field_72996_f.add(entity);
                func_72923_a(entity);
            }
            if (!field_72996_f.contains(entity)) return false;
            boolean twinned = ensureHostTwin(entity);
            host.log("ENTITY-DIAG spawnEntityInWorld twin-result class=" + entity.getClass().getName()
                    + " twinned=" + twinned + " hostLevel=" + (hostLevel != null)
                    + " loaded=" + field_72996_f.size());
            if (!twinned && hostLevel != null) {
                UmbStub.hit(FACADE, "func_72838_d(spawnEntityInWorld) - host declined to twin");
            }
            return true;
        } catch (Throwable t) {
            host.log("func_72838_d: HostLevel.spawnEntity threw for " + EntityList.func_75621_b(entity) + ": " + t);
            return true;
        }
    }

    /**
     * Converts a legacy EntityItem spawn into {@link HostWorld#dropItem}. The stack's
     * count/NBT cross via the existing conversion; scatter motion, pickup delay and age
     * stay native-side defaults (the host owns item physics). Returns true: vanilla
     * treats the drop as spawned either way. A null/empty stack converts to nothing but
     * still reports success, matching vanilla's spawn-then-tick semantics.
     */
    private boolean spawnDropAsHostItem(net.minecraft.entity.item.EntityItem dropped) {
        try {
            net.minecraft.item.ItemStack stack = dropped.func_92059_d();
            if (stack == null || stack.field_77994_a <= 0) {
                return true;
            }
            host.log("ENTITY-DIAG spawnEntityInWorld drop class="
                    + stack.func_77973_b().getClass().getName()
                    + " count=" + stack.field_77994_a
                    + " pos=" + dropped.field_70165_t + "," + dropped.field_70163_u + ","
                    + dropped.field_70161_v);
            host.dropItem(dropped.field_70165_t, dropped.field_70163_u, dropped.field_70161_v,
                    UmbItemConv.toStackData(stack));
        } catch (Throwable t) {
            host.log("spawnEntityInWorld: drop conversion threw for "
                    + dropped.getClass().getName() + ": " + t);
        }
        return true;
    }

    /** func_72868_a - addLoadedEntities/joinEntityInSurroundings add path. */    @Override
    public void func_72868_a(java.util.List list) {
        if (list == null) return;
        for (Object value : list) {
            if (value instanceof Entity) {
                Entity entity = (Entity) value;
                // Vanilla posts the join event first here too (: post; only the
                // non-cancelled join reaches the list and onEntityAdded) - see func_72838_d.
                if (joinCancelledByPost(postEntityJoinWorldEvent(entity), entity.field_98038_p)) {
                    host.log("ENTITY-DIAG addLoadedEntities cancelled class="
                            + entity.getClass().getName());
                    continue;
                }
                if (!field_72996_f.contains(entity)) {
                    field_72996_f.add(entity);
                    func_72923_a(entity);
                }
                ensureHostTwin(entity);
            }
        }
    }

    /**
     * Posts {@code EntityJoinWorldEvent(entity, this)} through {@link LegacyEventPoster} - the
     * single event touchpoint in this package (see its javadoc: naming a Forge event type in
     * ANY method body here would make the verifier define it before Forge's transformer chain
     * runs, silently dropping every later registration on it). Returns the raw post result
     * (true when a listener cancelled the join); the force-spawn rule lives in
     * {@link #joinCancelledByPost}. A listener that throws is logged and treated as
     * non-cancelled, per this universe's crash-isolation discipline (one bad subscriber must
     * never break every spawn). With no subscribers posted - the headless-test case - the
     * bus post returns false without touching the loader, exactly like every other bus use
     * in this universe.
     */
    private boolean postEntityJoinWorldEvent(Entity entity) {
        try {
            return LegacyEventPoster.postEntityJoinWorld(entity, this);
        } catch (Throwable t) {
            host.log("UmbWorld EntityJoinWorldEvent failed (non-fatal) for "
                    + entity.getClass().getName() + ": " + t);
            return false;
        }
    }

    /**
 * Vanilla's own cancellation rule for both entity add-paths , extracted pure so it is directly unit-testable without a booted Forge loader (subscribing a listener headless needs a LaunchClassLoader, which the headless suite does not have - verified...
 */
    public static boolean joinCancelledByPost(boolean postCancelled, boolean forceSpawn) {
        return postCancelled && !forceSpawn;
    }

    /** func_72942_c - addWeatherEffect; weather entities also need a visible host twin. */
    @Override
    public boolean func_72942_c(Entity entity) {
        boolean added = super.func_72942_c(entity);
        if (added) ensureHostTwin(entity);
        return added;
    }

    /** func_72900_e - removeEntity; remove both halves immediately when legacy schedules a kill. */
    @Override
    public void func_72900_e(Entity entity) {
        super.func_72900_e(entity);
        if (entity != null && entity.field_70128_L) {
            removeTrackedEntity(entity);
        }
    }
}
