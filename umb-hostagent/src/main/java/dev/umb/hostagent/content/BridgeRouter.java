package dev.umb.hostagent.content;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.EffectData;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.InputData;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileHandle;
import dev.umb.hostagent.AgentLog;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Era routing for legacy universes (added for the 1.16.5 lane): ONE {@code LegacyBridge} for
 * every call site (all of them already go through {@code UmbBridgeHost.get()}, so none change)
 * that forwards each call to the universe owning the id's namespace.
 *
 * <p>Rules, all era-agnostic (no mod-specific logic anywhere here - namespaces and eras come
 * from the manifest):</p>
 * <ul>
 *   <li>The default bridge (the 1.7.10 universe) serves everything with no known era, and
 *       every method that carries no id at all. Behavior without any era registration is
 *       identical to before this class existed.</li>
 *   <li>Era bridges are admitted at registration but boot only when a routed call for one of
 *       their installed namespaces arrives. A routed call never waits for an era boot: while
 *       the readiness gate is closed it falls back to the default bridge, which honestly ignores
 *       unknown ids. This keeps unused Forge eras completely out of the launch path.</li>
 *   <li>A failed era boot disables that era loudly (never crashes the game, never retries).</li>
 *   <li>{@code tickTile} has no id, so tile ownership is remembered at {@code createTile} time
 *       (identity map - handles carry no namespace).</li>
 *   <li>World-level broadcasts ({@code tickEvents}, {@code syncPlayers}, {@code playerRespawn})
 *       go to the default plus every booted era bridge; one bridge's throw never breaks the
 *       others. {@code shutdown} stops all; {@code isBooted} reports the default (existing
 *       semantics for the peek paths).</li>
 * </ul>
 */
public final class BridgeRouter implements LegacyBridge {

    /**
     * Host-only callback for a block entity whose era was still booting when its tile was
     * created.  It deliberately lives outside bridge-api: the retry queue is a host scheduling
     * concern, not a cross-era bridge contract.
     */
    interface EraRetryTarget {
        void retryAfterEraBoot();
        boolean isEraRetryTargetAlive();
    }

    private final LegacyBridge defaultBridge;
    private final Map<String, String> namespaceToEra = new LinkedHashMap<>();
    private final Map<String, EraHolder> eras = new LinkedHashMap<>();
    private final Map<TileHandle, LegacyBridge> tileOwners =
            Collections.synchronizedMap(new IdentityHashMap<TileHandle, LegacyBridge>());
    private volatile HostWorld lastWorld;
    private volatile long defaultBootMillis = -1L;
    private volatile long defaultMemoryDelta;
    private volatile String defaultStatus = "lazy";

    private static final class EraHolder {
        final Set<String> namespaces;
        final Supplier<LegacyBridge> factory;
        volatile LegacyBridge bridge;
        final AtomicBoolean bootAttempted = new AtomicBoolean(false);
        volatile boolean broken = false;
        volatile long bootMillis = -1L;
        volatile long memoryDelta;
        volatile String status = "lazy";
        final ConcurrentLinkedQueue<WeakReference<EraRetryTarget>> pendingTileRetries =
                new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<WeakReference<EraRetryTarget>> readyTileRetries =
                new ConcurrentLinkedQueue<>();

        EraHolder(Set<String> namespaces, Supplier<LegacyBridge> factory) {
            this.namespaces = namespaces;
            this.factory = factory;
        }
    }

    public BridgeRouter(LegacyBridge defaultBridge) {
        if (defaultBridge == null) throw new IllegalArgumentException("defaultBridge is null");
        this.defaultBridge = defaultBridge;
    }

    /**
     * Registers one era: every listed namespace routes to a lazily-built bridge. Namespace
     * ownership must be exclusive (loud on overlap); the default era needs no registration.
     */
    public synchronized void registerEra(String era, Set<String> namespaces,
            Supplier<LegacyBridge> factory) {
        if (era == null || era.isBlank()) throw new IllegalArgumentException("era is blank");
        if (eras.containsKey(era)) throw new IllegalStateException("duplicate era: " + era);
        if (namespaces == null || namespaces.isEmpty())
            throw new IllegalArgumentException("era " + era + " has no namespaces");
        if (factory == null) throw new IllegalArgumentException("era " + era + " has no factory");
        for (String ns : namespaces) {
            String key = ns.toLowerCase(Locale.ROOT);
            String prev = namespaceToEra.putIfAbsent(key, era);
            if (prev != null) {
                namespaceToEra.remove(key);
                throw new IllegalStateException("namespace " + key + " owned by both " + prev
                        + " and " + era);
            }
        }
        eras.put(era, new EraHolder(namespaces, factory));
        AgentLog.line("BridgeRouter: era " + era + " owns " + namespaces);
    }

    // ---------------------------------------------------------------- routing

    static String namespaceOf(String id) {
        if (id == null) return null;
        int colon = id.indexOf(':');
        if (colon <= 0) return null;
        return id.substring(0, colon).toLowerCase(Locale.ROOT);
    }

    /** True when the id is owned by a registered non-default era, without booting it. */
    public synchronized boolean isEraOwned(String id) {
        String namespace = namespaceOf(id);
        return namespace != null && namespaceToEra.containsKey(namespace);
    }

    /** Returns the owning era without booting it; unowned ids belong to the default 1.7.10 side. */
    public synchronized String eraFor(String id) {
        String namespace = namespaceOf(id);
        String era = namespace == null ? null : namespaceToEra.get(namespace);
        return era == null ? "1.7.10" : era;
    }

    private LegacyBridge route(String id, boolean mayBoot) {
        String ns = namespaceOf(id);
        if (ns == null) return defaultBridge;
        String era;
        synchronized (this) {
            era = namespaceToEra.get(ns);
        }
        if (era == null) return defaultBridge;
        EraHolder holder;
        synchronized (this) {
            holder = eras.get(era);
        }
        if (holder == null || holder.broken) return defaultBridge;
        if (!UmbSettings.eraEnabled(era)) return defaultBridge;
        LegacyBridge b = holder.bridge;
        if (b != null) {
            try {
                if (b.isBooted()) return b;
            } catch (Throwable t) {
                AgentLog.error("BridgeRouter.isBooted(" + era + ")", t, 2);
                return defaultBridge;
            }
        }
        if (!mayBoot) return defaultBridge;
        startEraBoot(era, holder);
        return defaultBridge;
    }

    /** Starts one daemon boot and returns immediately; callers must honor the readiness gate. */
    private void startEraBoot(String era, EraHolder holder) {
        HostWorld world = lastWorld;
        if (world == null || holder.broken || holder.bridge != null
                || !holder.bootAttempted.compareAndSet(false, true)) {
            return;
        }
        // Publish the retryable state before starting the daemon.  The first createTile call can
        // otherwise race the new thread and incorrectly fall through to the default universe.
        holder.status = "booting";
        Thread boot = new Thread(() -> {
            long started = System.nanoTime();
            long before = usedMemory();
            try {
                LegacyBridge b = holder.factory.get();
                if (b == null) throw new IllegalStateException("era factory returned null: " + era);
                b.boot(world);
                holder.bridge = b;
                holder.bootMillis = (System.nanoTime() - started) / 1_000_000L;
                holder.memoryDelta = usedMemory() - before;
                holder.status = "ready";
                movePendingTileRetries(holder);
                AgentLog.loud("BridgeRouter: era " + era + " booted asynchronously");
            } catch (Throwable t) {
                holder.broken = true;
                holder.bootMillis = (System.nanoTime() - started) / 1_000_000L;
                holder.memoryDelta = usedMemory() - before;
                holder.status = "failed";
                // Wake queued tiles so they can observe the failed era and enter the normal
                // retryable-failure path; never leave them marked "booting" forever.
                movePendingTileRetries(holder);
                // Full chain (same formatter shape as Legacy1165Universe): one-level messages
                // hid a live ExceptionInInitializerError's real cause once already.
                AgentLog.loud("BridgeRouter: era " + era + " FAILED to boot, disabled: "
                        + Legacy1165Universe.fullChain(t));
                AgentLog.error("BridgeRouter.startEraBoot(" + era + ")", t, 25);
            }
        }, "UMB-era-boot-" + era);
        boot.setDaemon(true);
        boot.start();
    }

    // ---------------------------------------------------------------- LegacyBridge

    @Override
    public synchronized void boot(HostWorld world) throws Exception {
        lastWorld = world;
        defaultStatus = "booting";
        long started = System.nanoTime();
        long before = usedMemory();
        try {
            defaultBridge.boot(world);
            defaultBootMillis = (System.nanoTime() - started) / 1_000_000L;
            defaultMemoryDelta = usedMemory() - before;
            defaultStatus = defaultBridge.toString().startsWith("DisabledLegacyBridge")
                    ? "not installed" : "ready";
        } catch (Exception | Error e) {
            defaultBootMillis = (System.nanoTime() - started) / 1_000_000L;
            defaultMemoryDelta = usedMemory() - before;
            defaultStatus = "failed";
            throw e;
        }
        // Do not prewarm registered eras here. Registration is only namespace admission; the
        // isolated Forge loader must remain untouched until a real block/item/tile id owned by
        // that era is routed. This is the P1 lazy-era boundary.
    }

    private static long usedMemory() {
        Runtime r = Runtime.getRuntime();
        return r.totalMemory() - r.freeMemory();
    }

    /** Compact status for the in-game universal settings screen. */
    public synchronized String statusLine(String era) {
        if ("1.7.10".equals(era)) {
            String text = defaultStatus;
            if (defaultBootMillis >= 0) text += " " + defaultBootMillis + "ms "
                    + Math.max(0L, defaultMemoryDelta / (1024L * 1024L)) + "MB";
            return text;
        }
        EraHolder holder = eras.get(era);
        if (holder == null) return "not installed";
        String text = holder.status;
        if (holder.bootMillis >= 0) text += " " + holder.bootMillis + "ms "
                + Math.max(0L, holder.memoryDelta / (1024L * 1024L)) + "MB";
        else text += " 0ms 0MB";
        return text;
    }

    @Override
    public boolean isBooted() {
        try {
            return defaultBridge.isBooted();
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.isBooted", t, 2);
            return false;
        }
    }

    @Override
    public TileHandle createTile(String legacyBlockId, int x, int y, int z) {
        LegacyBridge b = route(legacyBlockId, true);
        if (b == defaultBridge) {
            EraHolder holder = holderFor(legacyBlockId);
            if (holder != null && eraStillBooting(holder)) {
                return new EraBootingTileHandle(eraFor(legacyBlockId));
            }
        }
        try {
            TileHandle t = b.createTile(legacyBlockId, x, y, z);
            if (t != null && b != defaultBridge) tileOwners.put(t, b);
            return t;
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.createTile(" + legacyBlockId + ")", t, 3);
            return null;
        }
    }

    private synchronized EraHolder holderFor(String id) {
        String ns = namespaceOf(id);
        if (ns == null) return null;
        String era = namespaceToEra.get(ns);
        return era == null ? null : eras.get(era);
    }

    private static boolean eraStillBooting(EraHolder holder) {
        if (holder == null || holder.broken || holder.bridge != null) return false;
        return holder.bootAttempted.get() && "booting".equals(holder.status);
    }

    /** Returns true only while the namespace's asynchronous era boot is in progress. */
    boolean isEraBooting(String id) {
        return eraStillBooting(holderFor(id));
    }

    /** Returns true when the namespace's asynchronous era failed and is disabled. */
    boolean isEraBootFailed(String id) {
        EraHolder holder = holderFor(id);
        return holder != null && holder.broken && "failed".equals(holder.status);
    }

    /**
     * Registers a loaded block entity for retry after its namespace's era becomes ready.  Weak
     * references make this queue unable to keep an unloaded chunk or block entity alive.
     */
    boolean deferTile(String id, EraRetryTarget target) {
        if (target == null) return false;
        EraHolder holder = holderFor(id);
        if (!eraStillBooting(holder)) return false;
        holder.pendingTileRetries.add(new WeakReference<>(target));
        return true;
    }

    private static void movePendingTileRetries(EraHolder holder) {
        WeakReference<EraRetryTarget> ref;
        int moved = 0;
        while ((ref = holder.pendingTileRetries.poll()) != null) {
            if (ref.get() != null) {
                holder.readyTileRetries.add(ref);
                moved++;
            }
        }
        if (moved > 0) {
            AgentLog.line("BridgeRouter: queued tile retries=" + moved);
        }
    }

    /**
     * Runs queued retries on the caller's thread. Production calls this from the server tick
     * hook, so an era boot thread never touches a Minecraft block entity or forces a chunk load.
     */
    int drainReadyTileRetries() {
        int retried = 0;
        synchronized (this) {
            for (EraHolder holder : eras.values()) {
                WeakReference<EraRetryTarget> ref;
                while ((ref = holder.readyTileRetries.poll()) != null) {
                    EraRetryTarget target = ref.get();
                    if (target == null || !target.isEraRetryTargetAlive()) continue;
                    try {
                        target.retryAfterEraBoot();
                        retried++;
                    } catch (Throwable t) {
                        AgentLog.error("BridgeRouter.tileRetry", t, 3);
                    }
                }
            }
        }
        return retried;
    }

    @Override
    public ActivationResult activate(String legacyBlockId, int x, int y, int z, HostPlayer player,
            int side, float hitX, float hitY, float hitZ) {
        LegacyBridge b = route(legacyBlockId, true);
        try {
            return b.activate(legacyBlockId, x, y, z, player, side, hitX, hitY, hitZ);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.activate(" + legacyBlockId + ")", t, 3);
            return ActivationResult.DECLINED;
        }
    }

    @Override
    public void clicked(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        try {
            route(legacyBlockId, true).clicked(legacyBlockId, x, y, z, player);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.clicked(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public void tickTile(TileHandle t) {
        LegacyBridge b = t == null ? defaultBridge : tileOwners.getOrDefault(t, defaultBridge);
        try {
            b.tickTile(t);
        } catch (Throwable e) {
            AgentLog.error("BridgeRouter.tickTile", e, 3);
        }
    }

    @Override
    public void shutdown() {
        try {
            defaultBridge.shutdown();
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.shutdown(default)", t, 2);
        }
        synchronized (this) {
            for (Map.Entry<String, EraHolder> e : eras.entrySet()) {
                LegacyBridge b = e.getValue().bridge;
                if (b == null) continue;
                try {
                    b.shutdown();
                } catch (Throwable t) {
                    AgentLog.error("BridgeRouter.shutdown(" + e.getKey() + ")", t, 2);
                }
            }
        }
        synchronized (this) {
            tileOwners.clear();
        }
    }

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        try {
            route(legacyBlockId, true).placedBy(legacyBlockId, x, y, z, player);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.placedBy(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer player,
            StackData placedStack) {
        try {
            route(legacyBlockId, true).placedBy(legacyBlockId, x, y, z, player, placedStack);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.placedBy(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public void added(String legacyBlockId, int x, int y, int z) {
        try {
            route(legacyBlockId, true).added(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.added(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public void neighborChanged(String legacyBlockId, int x, int y, int z,
            String neighborLegacyBlockId) {
        try {
            route(legacyBlockId, true).neighborChanged(legacyBlockId, x, y, z, neighborLegacyBlockId);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.neighborChanged(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public void broken(String legacyBlockId, int x, int y, int z, int meta, HostPlayer player) {
        try {
            route(legacyBlockId, true).broken(legacyBlockId, x, y, z, meta, player);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.broken(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public boolean canPlaceAt(String legacyBlockId, int x, int y, int z) {
        try {
            return route(legacyBlockId, true).canPlaceAt(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.canPlaceAt(" + legacyBlockId + ")", t, 3);
            return true;
        }
    }

    @Override
    public StackData useItemRightClick(String legacyItemId, HostPlayer player) {
        try {
            return route(legacyItemId, true).useItemRightClick(legacyItemId, player);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.useItemRightClick(" + legacyItemId + ")", t, 3);
            return null;
        }
    }

    @Override
    public ItemUseResult useItemOnBlock(String legacyItemId, HostPlayer player, int x, int y, int z,
            int side, float hitX, float hitY, float hitZ) {
        try {
            return route(legacyItemId, true).useItemOnBlock(legacyItemId, player, x, y, z, side,
                    hitX, hitY, hitZ);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.useItemOnBlock(" + legacyItemId + ")", t, 3);
            return ItemUseResult.DECLINED;
        }
    }

    @Override
    public EntityHandle restoreEntity(byte[] nbt) {
        try {
            return defaultBridge.restoreEntity(nbt);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.restoreEntity", t, 3);
            return null;
        }
    }

    // ---- tick/contact peeks: never boot an era (server-thread stall); default on miss ----

    @Override
    public void tickBlock(String legacyBlockId, int x, int y, int z, boolean isRandom) {
        try {
            route(legacyBlockId, false).tickBlock(legacyBlockId, x, y, z, isRandom);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.tickBlock(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public void entityInside(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        try {
            route(legacyBlockId, false).entityInside(legacyBlockId, x, y, z, player);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.entityInside(" + legacyBlockId + ")", t, 3);
        }
    }

    // ---- interaction surfaces: route by id (era bridges inherit honest defaults) ----

    @Override
    public double[] collisionBounds(String legacyBlockId, int x, int y, int z) {
        // Was silently inherited from the LegacyBridge default (null = "static shape"), so no
        // state-following collision ever reached a live legacy block (BridgeDelegatesOverrideAllTest).
        try {
            return route(legacyBlockId, true).collisionBounds(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.collisionBounds(" + legacyBlockId + ")", t, 3);
            return null;
        }
    }

    @Override
    public java.util.List<double[]> collisionBoxes(String legacyBlockId, int x, int y, int z) {
        try {
            return route(legacyBlockId, true).collisionBoxes(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.collisionBoxes(" + legacyBlockId + ")", t, 3);
            return null;
        }
    }

    @Override
    public java.util.List<double[]> selectionBoxes(String legacyBlockId, int x, int y, int z) {
        try {
            return route(legacyBlockId, true).selectionBoxes(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.selectionBoxes(" + legacyBlockId + ")", t, 3);
            return null;
        }
    }

    @Override
    public void invalidateShape(String legacyBlockId, int x, int y, int z) {
        try {
            route(legacyBlockId, true).invalidateShape(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.invalidateShape(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public void guiButtonPacket(String guiClass, int buttonId, int x, int y, int z, String[] args) {
        // Was silently inherited from the no-op default: every legacy GUI button press was
        // dropped here. GUI classes carry no namespace, so they go to the default universe,
        // the same id-less rule as acceptInput.
        try {
            defaultBridge.guiButtonPacket(guiClass, buttonId, x, y, z, args);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.guiButtonPacket(" + guiClass + "#" + buttonId + ")", t, 3);
        }
    }

    @Override
    public boolean guiMouseClick(String guiClass, int x, int y, int z,
                                 int guiX, int guiY, int button, int screenX, int screenY) {
        try {
            return defaultBridge.guiMouseClick(guiClass, x, y, z, guiX, guiY, button, screenX, screenY);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.guiMouseClick(" + guiClass + ")", t, 3);
            return false;
        }
    }

    @Override
    public int placementMetadata(String legacyBlockId, int x, int y, int z, int side,
            float hitX, float hitY, float hitZ, int meta) {
        try {
            return route(legacyBlockId, true).placementMetadata(legacyBlockId, x, y, z, side,
                    hitX, hitY, hitZ, meta);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.placementMetadata(" + legacyBlockId + ")", t, 3);
            return meta;
        }
    }

    @Override
    public java.util.List<StackData> blockDrops(String legacyBlockId, int x, int y, int z, int meta,
            int fortune) {
        try {
            return route(legacyBlockId, true).blockDrops(legacyBlockId, x, y, z, meta, fortune);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.blockDrops(" + legacyBlockId + ")", t, 3);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public void stepOn(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        try {
            route(legacyBlockId, true).stepOn(legacyBlockId, x, y, z, player);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.stepOn(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public void fallOn(String legacyBlockId, int x, int y, int z, HostPlayer player, float distance) {
        try {
            route(legacyBlockId, true).fallOn(legacyBlockId, x, y, z, player, distance);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.fallOn(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public void animateBlock(String legacyBlockId, int x, int y, int z) {
        try {
            route(legacyBlockId, true).animateBlock(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.animateBlock(" + legacyBlockId + ")", t, 3);
        }
    }

    @Override
    public boolean hasComparatorInputOverride(String legacyBlockId) {
        try {
            return route(legacyBlockId, true).hasComparatorInputOverride(legacyBlockId);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.hasComparatorInputOverride(" + legacyBlockId + ")", t, 3);
            return false;
        }
    }

    @Override
    public int comparatorInputOverride(String legacyBlockId, int x, int y, int z) {
        try {
            return route(legacyBlockId, true).comparatorInputOverride(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.comparatorInputOverride(" + legacyBlockId + ")", t, 3);
            return 0;
        }
    }

    @Override
    public java.util.List<String> itemTooltip(String legacyItemId, StackData stack, boolean advanced) {
        try {
            // Tooltip generation runs from the client search tree/render path. It is explicitly
            // a peek: never let a hover or creative refresh start an isolated Forge boot.
            return route(legacyItemId, false).itemTooltip(legacyItemId, stack, advanced);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.itemTooltip(" + legacyItemId + ")", t, 3);
            return java.util.Collections.emptyList();
        }
    }

    /** Client-side tooltip gate: an unbooted owned era must not be cached as a real empty result. */
    boolean tooltipReady(String legacyItemId) {
        String ns = namespaceOf(legacyItemId);
        if (ns == null) return true;
        String era;
        synchronized (this) {
            era = namespaceToEra.get(ns);
            if (era == null) return true;
            EraHolder holder = eras.get(era);
            if (holder == null || holder.broken) return true;
            LegacyBridge b = holder.bridge;
            if (b == null) return false;
            try {
                return b.isBooted();
            } catch (Throwable t) {
                return false;
            }
        }
    }

    @Override
    public StackData itemInventoryTick(String legacyItemId, StackData stack, HostPlayer player,
            int slot, boolean current) {
        try {
            return route(legacyItemId, true).itemInventoryTick(legacyItemId, stack, player, slot,
                    current);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.itemInventoryTick(" + legacyItemId + ")", t, 3);
            return stack;
        }
    }

    @Override
    public int itemUseDuration(String legacyItemId, StackData stack) {
        try {
            return route(legacyItemId, true).itemUseDuration(legacyItemId, stack);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.itemUseDuration(" + legacyItemId + ")", t, 3);
            return 0;
        }
    }

    @Override
    public String itemUseAction(String legacyItemId, StackData stack) {
        try {
            return route(legacyItemId, true).itemUseAction(legacyItemId, stack);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.itemUseAction(" + legacyItemId + ")", t, 3);
            return "none";
        }
    }

    @Override
    public float itemDestroySpeed(String legacyItemId, StackData stack, String legacyBlockId) {
        try {
            return route(legacyItemId, true).itemDestroySpeed(legacyItemId, stack, legacyBlockId);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.itemDestroySpeed(" + legacyItemId + ")", t, 3);
            return Float.NaN;
        }
    }

    @Override
    public boolean itemCanHarvestBlock(String legacyItemId, StackData stack, String legacyBlockId) {
        try {
            return route(legacyItemId, true).itemCanHarvestBlock(legacyItemId, stack, legacyBlockId);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.itemCanHarvestBlock(" + legacyItemId + ")", t, 3);
            return false;
        }
    }

    @Override
    public void itemUsingTick(String legacyItemId, StackData stack, HostPlayer player, int remaining) {
        try {
            route(legacyItemId, true).itemUsingTick(legacyItemId, stack, player, remaining);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.itemUsingTick(" + legacyItemId + ")", t, 3);
        }
    }

    @Override
    public void itemStoppedUsing(String legacyItemId, StackData stack, HostPlayer player,
            int remaining) {
        try {
            route(legacyItemId, true).itemStoppedUsing(legacyItemId, stack, player, remaining);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.itemStoppedUsing(" + legacyItemId + ")", t, 3);
        }
    }

    @Override
    public StackData itemEaten(String legacyItemId, StackData stack, HostPlayer player) {
        try {
            return route(legacyItemId, true).itemEaten(legacyItemId, stack, player);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.itemEaten(" + legacyItemId + ")", t, 3);
            return stack;
        }
    }

    // ---- input lane: id-less player calls go to the default universe ----
    // (Without these overrides the interface defaults silently swallow input AND
    // effects: acceptInput returns false, drainClientEffects returns empty. The input
    // lane discovered this live — every other call here forwards, these two did not.)

    @Override
    public boolean acceptInput(HostPlayer player, InputData input) {
        try {
            return defaultBridge.acceptInput(player, input);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.acceptInput", t, 3);
            return false;
        }
    }

    @Override
    public java.util.List<EffectData> drainClientEffects() {
        java.util.List<EffectData> out = new java.util.ArrayList<>();
        try {
            java.util.List<EffectData> mine = defaultBridge.drainClientEffects();
            if (mine != null) out.addAll(mine);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.drainClientEffects(default)", t, 2);
        }
        synchronized (this) {
            for (Map.Entry<String, EraHolder> e : eras.entrySet()) {
                LegacyBridge b = e.getValue().bridge;
                if (b == null) continue;
                try {
                    java.util.List<EffectData> mine = b.drainClientEffects();
                    if (mine != null) out.addAll(mine);
                } catch (Throwable t) {
                    AgentLog.error("BridgeRouter.drainClientEffects(" + e.getKey() + ")", t, 2);
                }
            }
        }
        return out;
    }

    // ---- world-level broadcasts: default plus every booted era bridge ----

    @Override
    public void tickEvents(HostWorld world, HostPlayer[] players, boolean endPhase) {        lastWorld = world;
        broadcast("tickEvents", b -> b.tickEvents(world, players, endPhase));
    }

    @Override
    public dev.umb.bridge.api.GlEmulationSession.Mesh renderHud(String playerName,
                                                                  float partialTicks,
                                                                  int width, int height) {
        try {
            return defaultBridge.renderHud(playerName, partialTicks, width, height);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.renderHud", t, 2);
            return null;
        }
    }

    @Override
    public dev.umb.bridge.api.GlEmulationSession.Mesh renderGui(String playerName,
                                                                  float partialTicks,
                                                                  int width, int height) {
        try {
            return defaultBridge.renderGui(playerName, partialTicks, width, height);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.renderGui", t, 2);
            return null;
        }
    }

    @Override
    public CameraState cameraState(String playerName) {
        try {
            return defaultBridge.cameraState(playerName);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.cameraState", t, 2);
            return null;
        }
    }

    @Override
    public float thirdPersonDistance(String playerName) {
        try {
            return defaultBridge.thirdPersonDistance(playerName);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter.thirdPersonDistance", t, 2);
            return Float.NaN;
        }
    }

    @Override
    public void tickEntities() {
        broadcast("tickEntities", LegacyBridge::tickEntities);
    }

    @Override
    public void syncPlayers(java.util.List<HostPlayer> livePlayers) {
        broadcast("syncPlayers", b -> b.syncPlayers(livePlayers));
    }

    @Override
    public void playerRespawn(HostPlayer player) {
        broadcast("playerRespawn", b -> b.playerRespawn(player));
    }

    private void broadcast(String what, java.util.function.Consumer<LegacyBridge> call) {
        try {
            call.accept(defaultBridge);
        } catch (Throwable t) {
            AgentLog.error("BridgeRouter." + what + "(default)", t, 2);
        }
        synchronized (this) {
            for (Map.Entry<String, EraHolder> e : eras.entrySet()) {
                LegacyBridge b = e.getValue().bridge;
                if (b == null) continue;
                try {
                    call.accept(b);
                } catch (Throwable t) {
                    AgentLog.error("BridgeRouter." + what + "(" + e.getKey() + ")", t, 2);
                }
            }
        }
    }

    // ---------------------------------------------------------------- test hooks

    /** For tests: which bridge would serve this id right now (no booting). */
    synchronized String routeForTest(String id) {
        String ns = namespaceOf(id);
        if (ns == null) return "default";
        String era = namespaceToEra.get(ns);
        if (era == null) return "default";
        EraHolder holder = eras.get(era);
        if (holder == null || holder.broken) return "default";
        if (holder.bridge != null) return era;
        return "default-unbooted-" + era;
    }

    synchronized java.util.Map<String, String> namespacesForTest() {
        return new LinkedHashMap<>(namespaceToEra);
    }
}
