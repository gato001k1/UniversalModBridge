package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.registry.GameData;

import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.inventory.Container;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.EffectData;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.InputData;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileHandle;
import dev.umb.legacy.legacyside.input.LegacyInputDispatcher;
import dev.umb.legacy.legacyside.input.LegacyInputRecord;
import dev.umb.legacy.legacyside.network.LegacyEffectPacketDecoder;
import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import dev.umb.legacy.api.StageResult;
import dev.umb.legacy.api.UniverseConfig;
import dev.umb.legacy.legacyside.gui.GuiButtonPacketDispatcher;

/**
 * {@code dev.umb.bridge.api.LegacyBridge} - the single entry point THE BOUNDARY CONTRACT names
 * (DESIGN.md LANE A step 6). Runs INSIDE the {@code LegacyLoader}, so it is the only class in this
 * package that touches {@code dev.umb.bridge.api} AND owns the full FML lifecycle.
 *
 * <p>{@link #boot} reuses {@link LegacyDriver} exactly as {@code Bootstrap.java}'s test harness
 * does (same {@code UniverseConfig}, same transformer list) - single-shot, per DESIGN.md R4 and
 * risk #8: a second {@code boot()} throws rather than half-working. The config's file paths are
 * read from the SAME {@code -D} system properties {@code Bootstrap.java} uses, since both run in
 * the same JVM and a real 26.2 embedding is expected to set them the same way before creating the
 * universe-root loader.</p>
 */
public final class LegacyBridgeImpl implements LegacyBridge {

    private volatile boolean booted;
    private UmbWorld umbWorld;
    /** Lifecycle bookkeeping uses the same persistent key as UmbWorld, never a display name. */
    private final Set<String> seenPlayers = new HashSet<String>();
    private final Map<String, HostPlayer> livePlayers = new HashMap<String, HostPlayer>();
    /** Bounded state-change diagnostics for the generic live collision seam. */
    private final Map<String, String> collisionDiag = new HashMap<String, String>();
    private static final int COLLISION_DIAG_LIMIT = 512;
    /** Auditable packet-effect coverage; emitted only when a client packet is drained. */
    private long decodedEffectCount;
    private long unknownEffectCount;
    /** Per-position live answers; metadata is part of the key because variant twins share an id. */
    private final Map<ShapeKey, List<double[]>> liveShapeCache = new HashMap<ShapeKey, List<double[]>>();
    private static final int SHAPE_INVALIDATION_RADIUS = 8;
    private final Object shapeLock = new Object();
    private volatile UmbPlayer activeGuiPlayer;

    @Override
    public void boot(HostWorld world) throws Exception {
        if (booted) {
            throw new IllegalStateException("the legacy universe is single-shot; boot() was already called");
        }
        UniverseConfig cfg = buildConfig();
        LegacyDriver driver = new LegacyDriver();
        driver.boot(cfg);
        List<StageResult> stages = driver.lifecycle();
        for (StageResult s : stages) {
            if (!s.ok()) {
                throw new IllegalStateException("legacy universe boot failed at stage " + s.stage()
                        + ": " + s.throwableClass() + ": " + s.throwableMessage());
            }
        }
        this.umbWorld = UmbWorld.create(world, 0);
        // rely on for world-scoped static setup - see UmbMinecraftServer's javadoc for the exact
        // HBM call trace (RBMKDials.createDials) the step-3 mass-tick harness found missing this.
        // Never allowed to fail boot(): a genuine 26.2 embedding still gets M1's furnace scenario
        // even if some OTHER mod's serverStarting handler misbehaves in this synthetic universe.
        net.minecraft.server.MinecraftServer server = UmbMinecraftServer.create(umbWorld);
        cpw.mods.fml.common.IFMLSidedHandler sided =
                cpw.mods.fml.common.FMLCommonHandler.instance().getSidedDelegate();
        if (!(sided instanceof UmbSidedHandler)) {
            throw new IllegalStateException("FML sidedDelegate is not UmbSidedHandler: " + sided);
        }
        ((UmbSidedHandler) sided).bindServer(server);

        // Forge's WorldEvent.Load is normally posted by the real server's world loader. The
        // synthetic UmbWorld has no vanilla load path, so post the same event exactly once after
        // the facade is fully seeded and before serverStarting. This is generic for every mod.
        // LegacyEventPoster (not this class) names the event types: naming them in ANY method
        // body here would make class verification load them before the transformers are
        // registered (see that class's javadoc), silently dropping every later registration.
        try {
            LegacyEventPoster.postWorldLoad(umbWorld);
        } catch (Throwable t) {
            world.log("LegacyBridgeImpl.boot: WorldEvent.Load failed (non-fatal): " + t);
        }

        // FML's common handler is the same path used by the real dedicated server. Keep the
        // events independent: a bad serverStarting subscriber must not prevent serverStarted from
        // reaching the remaining mods or hide which event failed.
        int activeMods = Loader.instance().getActiveModList().size();
        boolean aboutToStart = false;
        try {
            aboutToStart = cpw.mods.fml.common.FMLCommonHandler.instance().handleServerAboutToStart(server);
            world.log("LegacyBridgeImpl.boot: serverAboutToStart delivered to " + activeMods
                    + " active mods (ok=" + aboutToStart + ")");
        } catch (Throwable t) {
            world.log("LegacyBridgeImpl.boot: serverAboutToStart event failed (non-fatal): " + t);
        }
        boolean starting = false;
        try {
            starting = aboutToStart && cpw.mods.fml.common.FMLCommonHandler.instance().handleServerStarting(server);
            world.log("LegacyBridgeImpl.boot: serverStarting delivered to " + activeMods
                    + " active mods (ok=" + starting + ")");
        } catch (Throwable t) {
            world.log("LegacyBridgeImpl.boot: serverStarting event failed (non-fatal): " + t);
        }
        if (starting) {
            try {
                cpw.mods.fml.common.FMLCommonHandler.instance().handleServerStarted();
                world.log("LegacyBridgeImpl.boot: serverStarted delivered to " + activeMods + " active mods");
            } catch (Throwable t) {
                world.log("LegacyBridgeImpl.boot: serverStarted event failed (non-fatal): " + t);
            }
        } else {
            world.log("LegacyBridgeImpl.boot: serverStarted skipped because serverStarting did not complete");
        }
        this.booted = true;
    }

    /**
     * Host server-tick entry point. FMLCommonHandler already owns the canonical event constructors
     * and buses, so this deliberately delegates to its verified onPre/onPost methods instead of
     * reconstructing TickEvent objects. The host calls START before tickServer's body and END
     * after it returns. Player facades are registered before their first event, which also makes
     * World.func_72872_a and the legacy player list observe the same objects.
     */
    @Override
    public void tickEvents(HostWorld world, HostPlayer[] players, boolean endPhase) {
        ensureBooted();
        HostPlayer[] current = players == null ? new HostPlayer[0] : players;
        dev.umb.legacy.legacyside.input.LegacyInputDiag.setSink(umbWorld.host()::log);
        try {
            FMLCommonHandler fml = FMLCommonHandler.instance();
            if (!endPhase) {
                syncPlayers(java.util.Arrays.asList(current));
                fml.onPreServerTick();
                fml.onPreWorldTick(umbWorld);
                for (HostPlayer hostPlayer : current) {
                    UmbPlayer player = preparePlayer(hostPlayer);
                    if (player != null) {
                        fml.onPlayerPreTick(player);
                        player.tickInventoryItems(umbWorld);
                        try {
                            // Additive client view: the authoritative input/mirror path has
                            // already returned before this client-only dispatch begins.
                            LegacyNetworkLoopback.bindClientPlayer(player);
                            LegacyClientTickDispatcher.tick(player, umbWorld);
                            LegacyGuiMouseDispatcher.tick(player);
                            handoffOpenedGui(hostPlayer, player);
                        } catch (Throwable t) {
                            if (dev.umb.legacy.legacyside.input.LegacyInputDiag.oncePer(
                                    "client-tick-unavailable", 60_000_000_000L)) {
                                umbWorld.host().log("[UMB-INPUT] client tick unavailable: "
                                        + t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
                            }
                        } finally {
                            LegacyNetworkLoopback.clearClientPlayer();
                            // Client-originated packets are delivered only after the client facade
                            // is restored to the server world.  A server handler may therefore
                            // publish openGui during clearClientPlayer, after the dispatch-side
                            // consume above has already run.
                            handoffOpenedGui(hostPlayer, player);
                        }
                    }
                }
            } else {
                for (HostPlayer hostPlayer : current) {
                    UmbPlayer player = preparePlayer(hostPlayer);
                    if (player != null) {
                        fml.onPlayerPostTick(player);
                    }
                }
                // Each post-tick event runs on its own: a failing world-tick listener must not
                // skip the server-tick listeners (and vice versa).
                try {
                    fml.onPostWorldTick(umbWorld);
                } catch (Throwable t) {
                    logTickFailureOnce(world, "END world", t);
                }
                try {
                    fml.onPostServerTick();
                } catch (Throwable t) {
                    logTickFailureOnce(world, "END server", t);
                }
            }
        } catch (Throwable t) {
            logTickFailureOnce(world, endPhase ? "END" : "START", t);
        }
    }

    private static final java.util.Set<String> TICK_FAILURES_LOGGED =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<String, Boolean>());

    /** Logs a tick-event failure with its stack the first time each cause appears, then stays quiet. */
    private static void logTickFailureOnce(HostWorld world, String phase, Throwable t) {
        String key = phase + "|" + t.getClass().getName() + "|" + String.valueOf(t.getMessage());
        if (!TICK_FAILURES_LOGGED.add(key)) return;
        StringBuilder sb = new StringBuilder("LegacyBridgeImpl.tickEvents(" + phase + ") failed (non-fatal, logged once): " + t);
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(st.length, 14); i++) sb.append("\n    at ").append(st[i]);
        world.log(sb.toString());
    }

    private void handoffOpenedGui(HostPlayer hostPlayer, UmbPlayer player) {
        UmbGui.OpenedGui opened = UmbGui.consumeOpenedGui(player);
        if (opened != null) {
            activeGuiPlayer = player;
            hostPlayer.openLegacyContainer(opened.handle, opened.title,
                    opened.x, opened.y, opened.z);
            LegacyGuiMouseDispatcher.open(UmbGui.lastContext());
        }
    }

    @Override
    public dev.umb.bridge.api.GlEmulationSession.Mesh renderHud(String playerName,
                                                                 float partialTicks,
                                                                 int width, int height) {
        ensureBooted();
        try {
            HostPlayer host = hostPlayerForName(playerName);
            UmbPlayer player = preparePlayer(host);
            if (player == null) return null;
            // draw code can send a client->server packet as a side effect (MCHeli's own
            // MCH_PacketIndRotation/MCH_PacketIndNotifyAmmoNum, live-observed dropped
            // "unresolved=sender" while riding). tickEvents() binds exactly this same
            // player around LegacyClientTickDispatcher.tick() so captureClientToServer's sender
            // resolves via the tested CURRENT_CLIENT_PLAYER path and any packet is QUEUED for
            // delivery only after the authoritative server world/state is restored (see
            // loopback-sender.md and LegacyNetworkLoopback's own javadoc on
            // PENDING_CLIENT_TO_SERVER: a server handler sees the client facade's world -
            // isRemote=true - for as long as this scope is open, and most legacy server handlers
            // early-return on that). renderOverlay() never had this binding, so any packet a HUD
            // handler sent from inside it fell back to the FACADE_CLIENT_PLAYER-only path and
            // was delivered immediately, mid-capture, with the client facade still installed.
            // Binding here mirrors tickEvents()'s proven pattern exactly.
            LegacyNetworkLoopback.bindClientPlayer(player);
            try {
                return LegacyClientTickDispatcher.renderOverlay(
                        player, umbWorld, partialTicks, width, height);
            } finally {
                LegacyNetworkLoopback.clearClientPlayer();
            }
        } catch (Throwable t) {
            if (umbWorld != null) umbWorld.host().log("renderHud failed: " + t);
            return null;
        }
    }

    @Override
    public dev.umb.bridge.api.GlEmulationSession.Mesh renderGui(String playerName,
                                                                  float partialTicks,
                                                                  int width, int height) {
        ensureBooted();
        try {
            return LegacyGuiMouseDispatcher.render(partialTicks, width, height, 0, 0);
        } catch (Throwable t) {
            if (umbWorld != null) umbWorld.host().log("renderGui failed: " + t);
            return null;
        }
    }

    @Override
    public dev.umb.bridge.api.LegacyBridge.CameraState cameraState(String playerName) {
        return LegacyClientTickDispatcher.cameraState(playerName);
    }

    @Override
    public float thirdPersonDistance(String playerName) {
        return LegacyClientTickDispatcher.thirdPersonDistance(playerName);
    }

    private HostPlayer hostPlayerForName(String playerName) {
        if (playerName != null) {
            for (HostPlayer host : livePlayers.values()) {
                if (host != null && playerName.equals(host.getName())) return host;
            }
        }
        return livePlayers.isEmpty() ? null : livePlayers.values().iterator().next();
    }

    @Override
    public void tickEntities() {
        ensureBooted();
        try {
            umbWorld.tickEntities();
        } catch (Throwable t) {
            umbWorld.host().log("LegacyBridgeImpl.tickEntities failed (non-fatal): " + t);
        }
    }

    /**
     * Reconciles the complete host snapshot every server tick. UmbWorld owns the actual cache and
     * playerEntities list; this method only calls its package-private register/unregister hooks,
     */
    @Override
    public void syncPlayers(List<HostPlayer> current) {
        ensureBooted();
        Set<String> present = new HashSet<String>();
        if (current != null) {
            for (HostPlayer hostPlayer : current) {
                if (hostPlayer == null || hostPlayer.getName() == null) {
                    continue;
                }
                String identity = playerIdentity(hostPlayer);
                present.add(identity);
                livePlayers.put(identity, hostPlayer);
                preparePlayer(hostPlayer);
            }
        }
        for (String identity : new HashSet<String>(livePlayers.keySet())) {
            if (!present.contains(identity)) {
                HostPlayer departed = livePlayers.remove(identity);
                umbWorld.unregisterPlayer(departed);
                seenPlayers.remove(identity);
            }
        }
    }

    private static String playerIdentity(HostPlayer hostPlayer) {
        String identity = hostPlayer == null ? null : hostPlayer.getIdentityKey();
        if (identity == null || identity.length() == 0) {
            identity = hostPlayer == null ? null : hostPlayer.getName();
        }
        return identity == null ? "<unnamed>" : identity;
    }

    /** Host calls this when a player object is replaced by the vanilla respawn path. */
    @Override
    public void playerRespawn(HostPlayer hostPlayer) {
        ensureBooted();
        try {
            UmbPlayer player = preparePlayer(hostPlayer);
            if (player != null) {
                FMLCommonHandler.instance().firePlayerRespawnEvent(player);
                LegacyEventPoster.postEntityJoin(player, umbWorld);
            }
        } catch (Throwable t) {
            umbWorld.host().log("LegacyBridgeImpl.playerRespawn failed (non-fatal): " + t);
        }
    }

    @Override
    public boolean acceptInput(HostPlayer hostPlayer, InputData input) {
        if (hostPlayer == null || input == null) return false;
        try {
            ensureBooted();
            UmbPlayer player = preparePlayer(hostPlayer);
            if (player == null) return false;
            // INPUT-BRIDGE: the inventory pull above syncs content; selection follows
            // the CLIENT frame (server selection goes stale under automation gives and
            // client scrolls never reach the server — the two diverge freely). The tick
            // path falls back to host matching only before any frame ever arrives.
            player.pullInventory();
            if (input.heldSlot >= 0 && input.heldSlot <= 8
                    && hostPlayer.getName() != null && player.field_71071_by != null) {
                dev.umb.legacy.legacyside.input.LegacyClientSelection.note(
                        hostPlayer.getName(), input.heldSlot);
                player.field_71071_by.field_70461_c = input.heldSlot;
            }
            player.field_70177_z = input.yaw;
            player.field_70125_A = input.pitch;
            ItemStack held = UmbItemConv.toLegacy(new StackData(input.heldItemId, input.heldCount,
                    input.heldDamage, input.heldNbt));
            java.util.Set<String> pressed = new java.util.LinkedHashSet<String>();
            for (Map.Entry<String, Boolean> e : input.legacyKeys.entrySet())
                if (Boolean.TRUE.equals(e.getValue())) pressed.add(e.getKey());
            LegacyInputRecord record = new LegacyInputRecord(input.tick, player, input.useHeld,
                    input.attackHeld, input.sneakHeld, input.usePressed, input.attackPressed,
                    input.heldSlot, input.yaw, input.pitch, input.lookX, input.lookY, input.lookZ,
                    held, pressed);
            // never been observed in any game log; route them through the proven host().log.
            dev.umb.legacy.legacyside.input.LegacyInputDiag.setSink(umbWorld.host()::log);
            return LegacyInputDispatcher.accept(record);
        } catch (Throwable t) {
            if (umbWorld != null) umbWorld.host().log("LegacyBridgeImpl.acceptInput failed: " + t);
            return false;
        }
    }

    @Override
    public List<EffectData> drainClientEffects() {
        List<EffectData> out = new ArrayList<EffectData>();
        if (umbWorld != null && umbWorld.host() != null) {
            out.addAll(umbWorld.host().drainClientEffects());
        }
        for (LegacyNetworkLoopback.ClientMessage message : LegacyNetworkLoopback.drainClientMessages()) {
            Object payload = message.payload();
            byte[] bytes = new byte[0];
            try {
                if (payload instanceof ByteBuf) {
                    ByteBuf b = ((ByteBuf) payload).duplicate();
                    bytes = new byte[b.readableBytes()]; b.getBytes(b.readerIndex(), bytes);
                } else if (payload instanceof FMLProxyPacket) {
                    ByteBuf b = ((FMLProxyPacket) payload).payload().duplicate();
                    bytes = new byte[b.readableBytes()]; b.getBytes(b.readerIndex(), bytes);
                } else if (payload instanceof IMessage) {
                    ByteBuf b = Unpooled.buffer(); ((IMessage) payload).toBytes(b);
                    bytes = new byte[b.readableBytes()]; b.getBytes(b.readerIndex(), bytes);
                }
            } catch (Throwable t) {
                if (umbWorld != null) umbWorld.host().log("effect payload capture failed: " + t);
            }
            // A captured server-to-client packet has no single scoreboard target; the host
            // broadcast filter treats an empty player id as the correct universal target.
            List<EffectData> decoded = LegacyEffectPacketDecoder.decode(payload, "",
                    umbWorld == null || umbWorld.host() == null ? 0L : umbWorld.host().getTotalTime());
            boolean emitted = false;
            for (EffectData effect : decoded) {
                if ("explosion".equals(effect.kind)) {
                    // Custom ordnance packets can carry a strength-like value and affected-block
                    // shape without calling World.newExplosion. Re-enter the already guarded
                    // host explosion path on the server thread; its max-radius and mobGriefing
                    // policy remains authoritative. The native explosion supplies sound/particles
                    // to the client, so this effect is not sent back as a duplicate packet.
                    if (umbWorld != null && umbWorld.host() != null) {
                        umbWorld.host().explode(effect.x, effect.y, effect.z, effect.a,
                                effect.timer != 0, effect.b > 0.0F);
                    }
                    emitted = true;
                } else {
                    out.add(effect);
                    emitted = true;
                }
            }
            if (!emitted) {
                unknownEffectCount++;
                String type = payload == null ? "null" : payload.getClass().getName();
                // Direction/meaning is never guessed from a mod class. An undecodable packet is
                // retained as UNKNOWN and counted by the client seam.
                out.add(new EffectData("unknown", message.route(), type, 0L, 0,0,0,0,0,0,0,0,0,bytes));
            } else {
                decodedEffectCount += decoded.size();
            }
            if (umbWorld != null && umbWorld.host() != null) {
                umbWorld.host().log("UMB-FX packet decoded=" + decodedEffectCount
                        + " unknown=" + unknownEffectCount + " last="
                        + (emitted ? decoded.size() : 0));
            }
        }
        return out;
    }

    private UmbPlayer preparePlayer(HostPlayer hostPlayer) {
        if (hostPlayer == null) {
            return null;
        }
        UmbPlayer player = umbWorld.registerPlayer(hostPlayer);
        mirrorGameMode(player, hostPlayer);
        String name = hostPlayer.getName();
        String identity = playerIdentity(hostPlayer);
        if (name != null) {
            livePlayers.put(identity, hostPlayer);
        }
        if (name != null && seenPlayers.add(identity)) {
            FMLCommonHandler.instance().firePlayerLoggedIn(player);
            LegacyEventPoster.postEntityJoin(player, umbWorld);
        }
        return player;
    }

    /**
     * Mirrors the host game mode into the legacy PlayerCapabilities (SRG: field_75098_d
     * isCreativeMode, field_75101_c allowFlying, field_75102_a disableDamage). UmbPlayer starts
     * with fresh survival capabilities, so creative-gated mod logic (MCHeli infinite fuel) failed
     * for a creative host player.
     */
    private static void mirrorGameMode(UmbPlayer player, HostPlayer hostPlayer) {
        if (player == null || player.field_71075_bZ == null) return;
        boolean creative = hostPlayer.isCreative();
        net.minecraft.entity.player.PlayerCapabilities caps = player.field_71075_bZ;
        if (caps.field_75098_d != creative) {
            caps.field_75098_d = creative;
            caps.field_75101_c = creative;
            caps.field_75102_a = creative;
            if (!creative) caps.field_75100_b = false;
        }
    }

    @Override
    public boolean isBooted() {
        return booted;
    }

    /** Package-private: lets {@link TickCoverageProbe} (same package, G2 lane E) reach the one
     *  {@code UmbWorld} this bridge built, without widening the public {@code LegacyBridge}
     *  contract. Not part of {@code dev.umb.bridge.api}. */
    UmbWorld debugWorld() {
        ensureBooted();
        return umbWorld;
    }

    /**
     * Persistence probe seam: a world reload keeps the booted legacy universe but replaces its
     * facade world. Production reloads reach the same state through host twin callbacks; the
     * headless probe uses this package-private seam to exercise restore against a fresh facade
     * instance without attempting a second FML boot in one JVM.
     */
    void rebindWorldForPersistenceProbe(HostWorld world) {
        if (!booted) throw new IllegalStateException("legacy universe is not booted");
        umbWorld = UmbWorld.create(world, 0);
    }

    /**
     * Live-game bug fix: {@code Block.createTileEntity(World,int)} is FORGE's OWN hook (default
     * body returns null) - most vanilla-shaped 1.7.10 machine blocks never override it at all.
     * The vanilla factory almost every {@code BlockContainer} subclass actually overrides is
     * {@code ITileEntityProvider.func_149915_a} (createNewTileEntity, "Returns a new instance of a
     * block's tile entity class. Called on placing the block." - MCP methods.csv). Calling ONLY the
     * Forge hook silently returned null for any such block (observed live:
     * hbm:tile.machine_blast_furnace poisoned with "createTile returned null" even though the
     * universe booted cleanly) - both factories are generic vanilla/Forge base-class APIs, not a
     * mod-specific special case, so trying both here fixes every mod built on BlockContainer.
     */
    @Override
    public TileHandle createTile(String legacyBlockId, int x, int y, int z) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) {
                umbWorld.host().log("createTile: unknown legacy block id " + legacyBlockId);
                return null;
            }
            int meta = umbWorld.func_72805_g(x, y, z);
            TileEntity te = resolveTileEntity(block, umbWorld, meta);
            if (te == null) {
                // NEVER get a tile, and the host retries every one of them; logging every miss
                // spammed ~9x/sec in the live game. Cache the negative per (block, pos, meta)
                // and log only the first miss - the null return (honest absence) is unchanged.
                boolean isProvider = block instanceof ITileEntityProvider;
                logNoTileOnce(legacyBlockId, x, y, z, meta, isProvider);
                return null;
            }
            te.field_145851_c = x;
            te.field_145848_d = y;
            te.field_145849_e = z;
            te.func_145834_a(umbWorld);
            umbWorld.putTile(x, y, z, te);
            te.func_145829_t();
            return new TileHandleImpl(te);
        } catch (Throwable t) {
            umbWorld.host().log("createTile failed for " + legacyBlockId + " at (" + x + "," + y + "," + z
                    + "): " + t);
            return null;
        }
    }

    /**
     * Bound on distinct (block, pos, meta) negative tile results remembered (see
     * {@link #logNoTileOnce}): multiblock filler lattices are small, and an evicted entry
     * only costs one repeat log line, never correctness - the null return is unchanged.
     */
    private static final int MAX_NO_TILE_CACHE = 2048;

    /** Negative createTile results already logged once, eldest-evicted past the bound. */
    private final java.util.Map<String, Boolean> noTileLogged =
            new java.util.LinkedHashMap<String, Boolean>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Boolean> e) {
                    return size() > MAX_NO_TILE_CACHE;
                }
            };

    private void logNoTileOnce(String legacyBlockId, int x, int y, int z, int meta,
                               boolean isProvider) {
        String key = legacyBlockId + "@" + x + "," + y + "," + z + "@" + meta;
        synchronized (noTileLogged) {
            if (noTileLogged.containsKey(key)) return;
            noTileLogged.put(key, Boolean.TRUE);
        }
        umbWorld.host().log("createTile: no tile entity for " + legacyBlockId
                + " - Block.createTileEntity(Forge) returned null"
                + (isProvider ? " and ITileEntityProvider.func_149915_a(vanilla) also returned null"
                        : " and the block does not implement ITileEntityProvider either"));
    }

    /**
     * Tries Forge's {@code Block.createTileEntity(World,int)} first, then falls back to the vanilla
     * {@code ITileEntityProvider.func_149915_a} (createNewTileEntity) factory - see
     * {@link #createTile}'s javadoc for why both are needed. Public (not private/package) so it can
     * be unit-tested directly, from {@code dev.umb.legacy.test}, against a plain
     * {@code Block}/{@code ITileEntityProvider} fixture with no FML boot and no {@code GameData}
     * registry lookup involved - not part of {@code dev.umb.bridge.api}, so this does not widen the
     * boundary contract.
     */
    public static TileEntity resolveTileEntity(Block block, net.minecraft.world.World world, int meta) {
        TileEntity te = block.createTileEntity(world, meta);
        if (te == null && block instanceof ITileEntityProvider) {
            te = ((ITileEntityProvider) block).func_149915_a(world, meta);
        }
        return te;
    }

    /**
     * {@code getCollisionBoundingBoxFromPool} (vanilla {@code Block} API every 1.7.10 block
     * inherits - a null there is the honest "no live bounds", exactly like vanilla's own
     * null-box handling), and converts the corners to block-local doubles. The host only
     * calls this for sidecar-flagged state-following blocks on the server thread, but this
     * side stays total anyway: unknown id, missing box, non-finite corners (a mod returning
     * garbage) all come back null instead of throwing or poisoning anything.
     */
    @Override
    public double[] collisionBounds(String legacyBlockId, int x, int y, int z) {
        List<double[]> boxes = collisionBoxes(legacyBlockId, x, y, z);
        if (boxes == null || boxes.isEmpty()) return null;
        double[] union = null;
        for (double[] b : boxes) {
            if (b == null || b.length != 6) continue;
            if (union == null) union = b.clone();
            else {
                union[0] = Math.min(union[0], b[0]);
                union[1] = Math.min(union[1], b[1]);
                union[2] = Math.min(union[2], b[2]);
                union[3] = Math.max(union[3], b[3]);
                union[4] = Math.max(union[4], b[4]);
                union[5] = Math.max(union[5], b[5]);
            }
        }
        return union;
    }

    @Override
    public List<double[]> collisionBoxes(String legacyBlockId, int x, int y, int z) {
        return liveShape(legacyBlockId, x, y, z, false);
    }

    @Override
    public List<double[]> selectionBoxes(String legacyBlockId, int x, int y, int z) {
        return liveShape(legacyBlockId, x, y, z, true);
    }

    /**
     * Calls the legacy block's own bounds callbacks for one position.  The order is grounded in
     * the 1.7.10 SRG runtime: func_149719_a (setBlockBoundsBasedOnState) first, then either
     * func_149743_a (addCollisionBoxesToList) or func_149633_g (getSelectedBoundingBoxFromPool).
     * The multi-box callback is authoritative when it successfully returns an empty list; the
     * single-box collision callback is used only when the multi-box method itself could not run.
     */
    private List<double[]> liveShape(String legacyBlockId, int x, int y, int z, boolean selection) {
        ensureBooted();
        if (legacyBlockId == null) return null;
        int meta = umbWorld.func_72805_g(x, y, z);
        ShapeKey key = new ShapeKey(legacyBlockId, x, y, z, meta, selection);
        synchronized (shapeLock) {
            List<double[]> cached = liveShapeCache.get(key);
            if (cached != null) return copyBoxes(cached);
        }
        TileEntity previousTile = null;
        boolean aliasedCoreTile = false;
        List<double[]> result = null;
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) return null;
            // Multiblock collision callbacks commonly resolve a shared core, but then read the
            // open/closed state from getTileEntity(x,y,z).  A filler cell has no tile of its own,
            // so that callback sees the closed default even though the core is open.  Temporarily
            // expose the resolved core tile at the queried cell while the block computes its own
            // bounds.  Resolution is by the public findCore shape only; no mod identity is used.
            int[] core = resolveCollisionCore(block, x, y, z);
            if (core != null && (core[0] != x || core[1] != y || core[2] != z)) {
                TileEntity coreTile = umbWorld.getTileAt(core[0], core[1], core[2]);
                previousTile = umbWorld.getTileAt(x, y, z);
                if (coreTile != null && previousTile != coreTile) {
                    umbWorld.putTile(x, y, z, coreTile);
                    aliasedCoreTile = true;
                }
            }
            // SRG-grounded 1.7.10 order: setBlockBoundsBasedOnState first, then the
            // multi-box collision callback, with the single AABB as a compatibility fallback.
            try {
                block.func_149719_a(umbWorld, x, y, z);
            } catch (Throwable ignored) {
                // The block's own collision method remains authoritative if its state setter
                // cannot run without a partially constructed tile.
            }
            List<net.minecraft.util.AxisAlignedBB> raw = new ArrayList<net.minecraft.util.AxisAlignedBB>();
            boolean callbackRan = false;
            if (selection) {
                try {
                    net.minecraft.util.AxisAlignedBB one = block.func_149633_g(umbWorld, x, y, z);
                    if (one != null) raw.add(one);
                    callbackRan = true;
                } catch (Throwable ignored) {
                    // null means unavailable; the host will retain its safe fallback.
                }
            } else {
                try {
                    net.minecraft.util.AxisAlignedBB mask = net.minecraft.util.AxisAlignedBB.func_72330_a(
                            x - 4, y - 4, z - 4, x + 5, y + 5, z + 5);
                    block.func_149743_a(umbWorld, x, y, z, mask, raw, null);
                    callbackRan = true;
                } catch (Throwable ignored) {
                    // Only a failed multi-box invocation permits the single-box fallback.
                }
                if (!callbackRan) {
                    try {
                        net.minecraft.util.AxisAlignedBB one = block.func_149668_a(umbWorld, x, y, z);
                        if (one != null) raw.add(one);
                        callbackRan = true;
                    } catch (Throwable ignored) {
                        // no live answer
                    }
                }
            }
            if (!callbackRan) return null;
            List<double[]> out = new ArrayList<double[]>(raw.size());
            for (net.minecraft.util.AxisAlignedBB box : raw) {
                if (box == null) continue;
                double[] local = new double[]{
                        box.field_72340_a - x, box.field_72338_b - y, box.field_72339_c - z,
                        box.field_72336_d - x, box.field_72337_e - y, box.field_72334_f - z};
                boolean finite = true;
                for (int i = 0; i < 6; i++) finite &= Double.isFinite(local[i]);
                if (finite && local[3] >= local[0] && local[4] >= local[1] && local[5] >= local[2]) {
                    out.add(local);
                }
            }
            logCollisionShape(legacyBlockId, x, y, z, core, aliasedCoreTile, out);
            result = immutableBoxes(out);
            synchronized (shapeLock) {
                liveShapeCache.put(key, result);
            }
            return copyBoxes(result);
        } catch (Throwable t) {
            try {
                umbWorld.host().log("[UMB-COLLISION] legacy id=" + legacyBlockId + " cell="
                        + x + "," + y + "," + z + " boxes=null error="
                        + t.getClass().getName());
            } catch (Throwable ignored) {
                // Diagnostics must never change collision behavior.
            }
            return null;
        } finally {
            if (aliasedCoreTile) {
                if (previousTile == null) umbWorld.removeTileAt(x, y, z);
                else umbWorld.putTile(x, y, z, previousTile);
            }
        }
    }

    @Override
    public boolean hasItemRenderer(String legacyItemId, int damage, String renderType) {
        return dev.umb.legacy.legacyside.render.LegacyRenderCapture.hasItemRenderer(
                legacyItemId, damage, renderType);
    }

    @Override
    public dev.umb.bridge.api.EntityRenderCapture captureItem(String legacyItemId, int count,
            int damage, byte[] nbt, String renderType, float partialTick, boolean transformOnly) {
        return dev.umb.legacy.legacyside.render.LegacyRenderCapture.captureItem(
                legacyItemId, count, damage, nbt, renderType, partialTick, transformOnly);
    }

    @Override
    public List<double[]> cachedShape(String legacyBlockId, int x, int y, int z, boolean selection) {
        if (legacyBlockId == null || umbWorld == null) return null;
        ShapeKey key = new ShapeKey(legacyBlockId, x, y, z, umbWorld.func_72805_g(x, y, z), selection);
        synchronized (shapeLock) {
            List<double[]> cached = liveShapeCache.get(key);
            return cached == null ? null : copyBoxes(cached);
        }
    }

    @Override
    public void invalidateShape(String legacyBlockId, int x, int y, int z) {
        synchronized (shapeLock) {
            java.util.Iterator<ShapeKey> it = liveShapeCache.keySet().iterator();
            while (it.hasNext()) {
                ShapeKey key = it.next();
                if ((legacyBlockId == null || legacyBlockId.equals(key.id))
                        && Math.abs(key.x - x) <= SHAPE_INVALIDATION_RADIUS
                        && Math.abs(key.y - y) <= SHAPE_INVALIDATION_RADIUS
                        && Math.abs(key.z - z) <= SHAPE_INVALIDATION_RADIUS) {
                    it.remove();
                }
            }
        }
    }

    private static List<double[]> immutableBoxes(List<double[]> boxes) {
        List<double[]> copy = new ArrayList<double[]>(boxes.size());
        for (double[] box : boxes) copy.add(box == null ? null : box.clone());
        return java.util.Collections.unmodifiableList(copy);
    }

    private static List<double[]> copyBoxes(List<double[]> boxes) {
        List<double[]> copy = new ArrayList<double[]>(boxes.size());
        for (double[] box : boxes) copy.add(box == null ? null : box.clone());
        return copy;
    }

    private static final class ShapeKey {
        final String id;
        final int x, y, z, meta;
        final boolean selection;

        ShapeKey(String id, int x, int y, int z, int meta, boolean selection) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.meta = meta;
            this.selection = selection;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof ShapeKey)) return false;
            ShapeKey k = (ShapeKey) other;
            return x == k.x && y == k.y && z == k.z && meta == k.meta
                    && selection == k.selection && id.equals(k.id);
        }

        @Override
        public int hashCode() {
            int h = id.hashCode();
            h = 31 * h + x; h = 31 * h + y; h = 31 * h + z; h = 31 * h + meta;
            return 31 * h + (selection ? 1 : 0);
        }
    }

    private void logCollisionShape(String id, int x, int y, int z, int[] core,
                                    boolean aliased, List<double[]> boxes) {
        if (collisionDiag.size() >= COLLISION_DIAG_LIMIT) return;
        StringBuilder text = new StringBuilder();
        text.append("[UMB-COLLISION] legacy id=").append(id).append(" cell=")
                .append(x).append(',').append(y).append(',').append(z)
                .append(" core=").append(core == null ? "null"
                        : core[0] + "," + core[1] + "," + core[2])
                .append(" aliased=").append(aliased).append(" boxes=");
        if (boxes == null) {
            text.append("null");
        } else {
            text.append('[');
            for (int i = 0; i < boxes.size(); i++) {
                if (i != 0) text.append(';');
                double[] b = boxes.get(i);
                text.append(b == null ? "null" : java.util.Arrays.toString(b));
            }
            text.append(']');
        }
        String line = text.toString();
        String key = id + '@' + x + ',' + y + ',' + z;
        if (line.equals(collisionDiag.get(key))) return;
        collisionDiag.put(key, line);
        umbWorld.host().log(line);
    }

    /**
     * Resolve a multiblock's shared controller without linking the runtime to any mod class.
     * The legacy Block API has no common core-resolution method, so only a public method with
     * the exact IBlockAccess/int/int/int shape is considered.  Unknown blocks simply keep their
     * ordinary per-cell collision behavior.
     */
    private int[] resolveCollisionCore(Block block, int x, int y, int z) {
        try {
            java.lang.reflect.Method method = block.getClass().getMethod("findCore",
                    net.minecraft.world.IBlockAccess.class, int.class, int.class, int.class);
            Object value = method.invoke(block, umbWorld, Integer.valueOf(x), Integer.valueOf(y), Integer.valueOf(z));
            if (value instanceof int[]) {
                int[] core = (int[]) value;
                if (core.length >= 3) return new int[] {core[0], core[1], core[2]};
            }
        } catch (Throwable ignored) {
            // A block without this generic multiblock shape uses its normal local tile path.
        }
        return null;
    }

    @Override
    public ActivationResult activate(String legacyBlockId, int x, int y, int z, HostPlayer hostPlayer, int side,
                                      float hitX, float hitY, float hitZ) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) {
                return ActivationResult.DECLINED;
            }
            UmbPlayer player = UmbPlayer.create(umbWorld, hostPlayer);
            activeGuiPlayer = player;
            player.pullInventory();
            player.field_71070_bA = null;
            boolean handled;
            try {
                handled = block.func_149727_a(umbWorld, x, y, z, player, side, hitX, hitY, hitZ);
            } catch (Throwable t) {
                umbWorld.host().log("activate: onBlockActivated threw for " + legacyBlockId + ": " + t);
                return ActivationResult.DECLINED;
            }
            player.pushInventory();
            Container c = player.field_71070_bA;
            // The block-activation caller below opens the host menu itself.  Consume the generic
            // packet/key handoff here if a mod happened to call openGui from inside activation so
            // that the same container is never opened twice.
            UmbGui.consumeOpenedGui(player);
            if (c != null) LegacyGuiMouseDispatcher.open(UmbGui.lastContext());
            ContainerHandle handle = c != null ? new ContainerHandleImpl(c, player, legacyBlockId) : null;
            return new ActivationResult(handled, handle);
        } catch (Throwable t) {
            umbWorld.host().log("activate failed for " + legacyBlockId + " at (" + x + "," + y + "," + z
                    + "): " + t);
            return ActivationResult.DECLINED;
        }
    }

    @Override
    public void clicked(String legacyBlockId, int x, int y, int z, HostPlayer hostPlayer) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) {
                return;
            }
            UmbPlayer player = UmbPlayer.create(umbWorld, hostPlayer);
            player.pullInventory();
            try {
                block.func_149699_a(umbWorld, x, y, z, player);
            } catch (Throwable t) {
                umbWorld.host().log("clicked: onBlockClicked threw for " + legacyBlockId + ": " + t);
                return;
            }
            player.pushInventory();
        } catch (Throwable t) {
            umbWorld.host().log("clicked failed for " + legacyBlockId + " at (" + x + "," + y + "," + z
                    + "): " + t);
        }
    }

    // ---- PART 1: placement / removal / neighbor-change ----

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer hostPlayer) {
        placedBy(legacyBlockId, x, y, z, hostPlayer,
                hostPlayer == null ? null : hostPlayer.getHeldItem());
    }

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer hostPlayer,
                         StackData placedStack) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) {
                return;
            }
            UmbPlayer player = UmbPlayer.create(umbWorld, hostPlayer);
            player.pullInventory();
            ItemStack heldStack = UmbItemConv.toLegacy(placedStack);
            if (heldStack == null) {
                // Compatibility for older callers and non-item placements.  A real BlockItem
                // caller uses the exact stack overload above; this fallback preserves the old
                // contract for test fakes and older eras without allowing a null into vanilla
                // Block.onBlockPlacedBy implementations.
                heldStack = player.func_70694_bm();
            }
            // A native placement can reach this callback even when the held item has no
            // legacy twin (the host still placed the registered legacy block).  Vanilla's
            // 1.7.10 placement path never supplies null, and several generic Block subclasses
            // inspect stack metadata before filling their structure.  Preserve that contract
            // without inventing a different item: the placed block itself is the only honest
            // fallback available at this boundary.
            if (heldStack == null) {
                heldStack = new ItemStack(block);
                umbWorld.host().log("placedBy: no legacy placement stack for " + legacyBlockId
                        + "; using the placed block as the vanilla-compatible fallback");
            }
            try {
                block.func_149689_a(umbWorld, x, y, z, player, heldStack);
            } catch (Throwable t) {
                umbWorld.host().log("placedBy: onBlockPlacedBy threw for " + legacyBlockId + ": " + t);
            } finally {
                player.pushInventory();
            }
        } catch (Throwable t) {
            umbWorld.host().log("placedBy failed for " + legacyBlockId + " at (" + x + "," + y + "," + z
                    + "): " + t);
        }
    }

    @Override
    public void added(String legacyBlockId, int x, int y, int z) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) {
                return;
            }
            try {
                block.func_149726_b(umbWorld, x, y, z);
            } catch (Throwable t) {
                umbWorld.host().log("added: onBlockAdded threw for " + legacyBlockId + ": " + t);
            }
        } catch (Throwable t) {
            umbWorld.host().log("added failed for " + legacyBlockId + " at (" + x + "," + y + "," + z
                    + "): " + t);
        }
    }

    @Override
    public void neighborChanged(String legacyBlockId, int x, int y, int z, String neighborLegacyBlockId) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) {
                return;
            }
            Block neighbor = neighborLegacyBlockId == null ? null
                    : GameData.getBlockRegistry().get(neighborLegacyBlockId);
            if (neighbor == null) {
                neighbor = net.minecraft.init.Blocks.field_150350_a; // air - never null, matches
                                                                      // 1.7.10's own convention
            }
            try {
                block.func_149695_a(umbWorld, x, y, z, neighbor);
            } catch (Throwable t) {
                umbWorld.host().log("neighborChanged: onNeighborBlockChange threw for " + legacyBlockId
                        + ": " + t);
            }
        } catch (Throwable t) {
            umbWorld.host().log("neighborChanged failed for " + legacyBlockId + " at (" + x + "," + y + ","
                    + z + "): " + t);
        }
    }

    @Override
    public void broken(String legacyBlockId, int x, int y, int z, int meta, HostPlayer hostPlayer) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) {
                return;
            }
            UmbPlayer player = UmbPlayer.create(umbWorld, hostPlayer);
            player.pullInventory();
            // onBlockDestroyedByPlayer's own doc: "called right before the block is destroyed by a
            // player" - runs first, matching that ordering; breakBlock (the generic
            // cleanup/inventory-eject hook, called by World.setBlock in real 1.7.10 whenever the
            // block identity changes) runs second, immediately before the host actually removes the
            // 26.2 block (see UmbLegacyBlock.playerWillDestroy - the ONLY call site of this method -
            // for why both still see valid world state here).
            try {
                block.func_149664_b(umbWorld, x, y, z, meta);
            } catch (Throwable t) {
                umbWorld.host().log("broken: onBlockDestroyedByPlayer threw for " + legacyBlockId + ": " + t);
            }
            try {
                block.func_149749_a(umbWorld, x, y, z, block, meta);
            } catch (Throwable t) {
                umbWorld.host().log("broken: breakBlock threw for " + legacyBlockId + ": " + t);
            }
            player.pushInventory();
        } catch (Throwable t) {
            umbWorld.host().log("broken failed for " + legacyBlockId + " at (" + x + "," + y + "," + z
                    + "): " + t);
        }
    }

    @Override
    public boolean canPlaceAt(String legacyBlockId, int x, int y, int z) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) {
                return true; // unknown twin -> never the reason placement is blocked
            }
            return block.func_149742_c(umbWorld, x, y, z);
        } catch (Throwable t) {
            umbWorld.host().log("canPlaceAt failed for " + legacyBlockId + " at (" + x + "," + y + "," + z
                    + "): " + t);
            return true;
        }
    }

    // ---- BLOCK SURFACE: placement / drops / contact / display / comparator -----------------

    @Override
    public int placementMetadata(String legacyBlockId, int x, int y, int z, int side,
                                 float hitX, float hitY, float hitZ, int meta) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            return block == null ? meta : block.func_149660_a(umbWorld, x, y, z, side,
                    hitX, hitY, hitZ, meta);
        } catch (Throwable t) {
            umbWorld.host().log("placementMetadata failed for " + legacyBlockId + ": " + t);
            return meta;
        }
    }

    @Override
    public List<StackData> blockDrops(String legacyBlockId, int x, int y, int z, int meta,
                                      int fortune) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) return null;
            ArrayList<ItemStack> drops = block.getDrops(umbWorld, x, y, z, meta, fortune);
            List<StackData> out = new ArrayList<StackData>(drops.size());
            for (ItemStack drop : drops) out.add(UmbItemConv.toStackData(drop));
            return out;
        } catch (Throwable t) {
            umbWorld.host().log("blockDrops failed for " + legacyBlockId + ": " + t);
            return null;
        }
    }

    @Override
    public void stepOn(String legacyBlockId, int x, int y, int z, HostPlayer hostPlayer) {
        dispatchEntitySurface(legacyBlockId, x, y, z, hostPlayer, false, 0.0F);
    }

    @Override
    public void fallOn(String legacyBlockId, int x, int y, int z, HostPlayer hostPlayer,
                       float distance) {
        dispatchEntitySurface(legacyBlockId, x, y, z, hostPlayer, true, distance);
    }

    private void dispatchEntitySurface(String legacyBlockId, int x, int y, int z,
                                       HostPlayer hostPlayer, boolean falling, float distance) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null || hostPlayer == null) return;
            UmbPlayer player = UmbPlayer.create(umbWorld, hostPlayer);
            if (falling) block.func_149746_a(umbWorld, x, y, z, player, distance);
            else block.func_149724_b(umbWorld, x, y, z, player);
        } catch (Throwable t) {
            umbWorld.host().log((falling ? "fallOn" : "stepOn") + " failed for "
                    + legacyBlockId + ": " + t);
        }
    }

    @Override
    public void animateBlock(String legacyBlockId, int x, int y, int z) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block != null) block.func_149734_b(umbWorld, x, y, z, umbWorld.field_73012_v);
        } catch (Throwable t) {
            umbWorld.host().log("animateBlock failed for " + legacyBlockId + ": " + t);
        }
    }

    @Override
    public boolean hasComparatorInputOverride(String legacyBlockId) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            return block != null && block.func_149740_M();
        } catch (Throwable t) {
            umbWorld.host().log("hasComparatorInputOverride failed for " + legacyBlockId + ": " + t);
            return false;
        }
    }

    @Override
    public int comparatorInputOverride(String legacyBlockId, int x, int y, int z) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            // 26.2 exposes no comparator-facing direction here. Use legacy side 0 (down), the
            // documented neutral convention for this one-way boundary rather than guessing from
            // confirms func_149736_g(World,int,int,int,int).
            return block == null ? 0 : block.func_149736_g(umbWorld, x, y, z, 0);
        } catch (Throwable t) {
            umbWorld.host().log("comparatorInputOverride failed for " + legacyBlockId + ": " + t);
            return 0;
        }
    }


    /**
     * updateTick (func_149674_a, methods.csv: "Ticks the block if it's been scheduled") - the ONE
     * legacy method behind both fluid flow/fire spread (random ticks) and every self-scheduling
     * machine (scheduled ticks, the return leg of {@code UmbWorld.func_147464_a} -&gt;
     * {@code HostWorld.scheduleTick}). The world's own seeded Random (field_73012_v, seeded in
     * {@code UmbWorld.create}) is passed exactly as 1.7.10's World.updateEntities would.
     */
    @Override
    public void tickBlock(String legacyBlockId, int x, int y, int z, boolean isRandom) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) {
                return;
            }
            try {
                block.func_149674_a(umbWorld, x, y, z, umbWorld.field_73012_v);
            } catch (Throwable t) {
                umbWorld.host().log("tickBlock: updateTick threw for " + legacyBlockId
                        + (isRandom ? " (random)" : " (scheduled)") + ": " + t);
            }
        } catch (Throwable t) {
            umbWorld.host().log("tickBlock failed for " + legacyBlockId + " at (" + x + "," + y + ","
                    + z + "): " + t);
        }
    }

    /**
     * onEntityCollidedWithBlock (func_149670_a, methods.csv: "Triggered whenever an entity
     * collides with this block (enters into the block). Args: world, x, y, z, entity") -
     * PLAYER-FIRST scope per the boundary contract's javadoc. Deliberately NO
     * pullInventory/pushInventory here: this dispatch runs every tick the player stands inside the
     * block (20/s), and contact code acts on motion and damage, not the inventory - the 36-slot
     * round-trip would be pure per-tick overhead (a contact handler that reads the held item still
     * can: func_70694_bm converts on demand). Motion is seeded before and written back after,
     * change-detected, so a conveyor's push crosses the boundary but an untouched motion never
     * stomps the host's own physics.
     */
    @Override
    public void entityInside(String legacyBlockId, int x, int y, int z, HostPlayer hostPlayer) {
        ensureBooted();
        try {
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) {
                return;
            }
            UmbPlayer player = UmbPlayer.create(umbWorld, hostPlayer);
            player.pullMotion();
            try {
                block.func_149670_a(umbWorld, x, y, z, player);
            } catch (Throwable t) {
                umbWorld.host().log("entityInside: onEntityCollidedWithBlock threw for "
                        + legacyBlockId + ": " + t);
                return;
            }
            player.pushMotion();
        } catch (Throwable t) {
            umbWorld.host().log("entityInside failed for " + legacyBlockId + " at (" + x + "," + y + ","
                    + z + "): " + t);
        }
    }

    // ---- PART 2: item-side interaction ----

    @Override
    public StackData useItemRightClick(String legacyItemId, HostPlayer hostPlayer) {
        ensureBooted();
        try {
            Item item = GameData.getItemRegistry().get(legacyItemId);
            if (item == null) {
                umbWorld.host().log("ENTITY-DIAG useItemRightClick lookup-miss id=" + legacyItemId);
                return null;
            }
            umbWorld.host().log("ENTITY-DIAG useItemRightClick enter id=" + legacyItemId
                    + " itemClass=" + item.getClass().getName());
            UmbPlayer player = UmbPlayer.create(umbWorld, hostPlayer);
            player.pullInventory();
            ItemStack stack = UmbItemConv.toLegacy(hostPlayer.getHeldItem());
            if (stack == null) {
                umbWorld.host().log("ENTITY-DIAG useItemRightClick stack-conversion-null id=" + legacyItemId);
                return null; // FM-6: no legacy twin for whatever is actually held right now
            }
            umbWorld.host().log("ENTITY-DIAG useItemRightClick before-func id=" + legacyItemId
                    + " stackClass=" + stack.func_77973_b().getClass().getName()
                    + " pos=" + player.field_70165_t + "," + player.field_70163_u + "," + player.field_70161_v
                    + " rot=" + player.field_70177_z + "," + player.field_70125_A);
            ItemStack result;
            try {
                result = item.func_77659_a(stack, umbWorld, player);
            } catch (Throwable t) {
                umbWorld.host().log("useItemRightClick: onItemRightClick threw for " + legacyItemId + ": " + t);
                return null;
            }
            umbWorld.host().log("ENTITY-DIAG useItemRightClick after-func id=" + legacyItemId
                    + " result=" + (result == null ? "null" : result.func_77973_b().getClass().getName())
                    + " legacyLoadedEntities=" + umbWorld.field_72996_f.size());
            player.pushInventory();
            // HBM and other legacy items call EntityPlayer.func_71008_a (setItemInUse) from
            // onItemRightClick. That mutates only the legacy facade; 26.2's server loop drives
            // onUseTick/releaseUsing only from ServerPlayer's native using-state. Propagate the
            // state after the returned stack is written back, so the host starts the same main-
            // hand use lifecycle for every legacy item. The HostPlayer default keeps headless
            // adapters source-compatible.
            startHostUseIfLegacyRequested(hostPlayer, player);
            return UmbItemConv.toStackData(result);
        } catch (Throwable t) {
            umbWorld.host().log("useItemRightClick failed for " + legacyItemId + ": " + t);
            return null;
        }
    }

    /**
     * Maps the legacy EntityPlayer.setItemInUse flag to the host's native using-state. Keeping
     * this decision in one small helper makes the boundary contract testable without booting FML,
     * and ensures every item that starts use from onItemRightClick gets the same 26.2 lifecycle.
     */
    public static boolean startHostUseIfLegacyRequested(HostPlayer hostPlayer, UmbPlayer player) {
        if (hostPlayer == null || player == null || !player.func_71039_bw()) {
            return false;
        }
        hostPlayer.startUsingItem();
        return true;
    }

    @Override
    public ItemUseResult useItemOnBlock(String legacyItemId, HostPlayer hostPlayer, int x, int y, int z,
                                         int side, float hitX, float hitY, float hitZ) {
        ensureBooted();
        try {
            Item item = GameData.getItemRegistry().get(legacyItemId);
            if (item == null) {
                umbWorld.host().log("ENTITY-DIAG useItemOnBlock lookup-miss id=" + legacyItemId);
                return ItemUseResult.DECLINED;
            }
            UmbPlayer player = UmbPlayer.create(umbWorld, hostPlayer);
            player.pullInventory();
            ItemStack stack = UmbItemConv.toLegacy(hostPlayer.getHeldItem());
            if (stack == null) {
                umbWorld.host().log("ENTITY-DIAG useItemOnBlock stack-conversion-null id=" + legacyItemId);
                return ItemUseResult.DECLINED; // FM-6
            }
            umbWorld.host().log("ENTITY-DIAG useItemOnBlock before-func id=" + legacyItemId
                    + " stackClass=" + stack.func_77973_b().getClass().getName()
                    + " pos=" + player.field_70165_t + "," + player.field_70163_u + "," + player.field_70161_v
                    + " rot=" + player.field_70177_z + "," + player.field_70125_A
                    + " block=" + x + "," + y + "," + z + " side=" + side);
            boolean handled;
            try {
                handled = item.func_77648_a(stack, player, umbWorld, x, y, z, side, hitX, hitY, hitZ);
            } catch (Throwable t) {
                umbWorld.host().log("useItemOnBlock: onItemUse threw for " + legacyItemId + ": " + t);
                return ItemUseResult.DECLINED;
            }
            if (!handled) {
                // UNIVERSAL 1.7.10 rightClickMouse order (Minecraft.func_147121_ag,
                // the SAME click still runs onItemRightClick (client PlayerControllerMP
                // func_78769_a, server C08/-1/-1/-1/255 via func_73085_a). 26.2 sends only
                // USE_ITEM_ON for a ground click and never synthesises the air half, so a
                // right-click-only item (vehicles, crates, stations, bows, food, buckets,
                // throwables) silently does nothing when aimed at a block. Run it here on
                // the SAME stack, inside the SAME pull/push scope.
                ItemUseResult air = runDeclinedAirFallback(umbWorld, item, stack, player,
                        hostPlayer, legacyItemId);
                if (air == null) {
                    return ItemUseResult.DECLINED; // fallback threw (already logged)
                }
                player.pushInventory();
                // Fire-log (same shape as useItemRightClick's after-func): a declined
                // right-click falls through several silent vanilla refusals (ray miss,
                // spawn-box overlap incl. the player, sponge gate), each returning the
                // stack unchanged. Without this line a refused ground placement is
                // indistinguishable from a dispatch that never ran.
                umbWorld.host().log("ENTITY-DIAG useItemOnBlock after-fallback id=" + legacyItemId
                        + " airHandled=" + air.handled
                        + " count=" + (air.stack == null ? "null" : air.stack.count)
                        + " legacyLoadedEntities=" + umbWorld.field_72996_f.size());
                return air;
            }
            player.pushInventory();
            // FM-4: func_77648_a mutates `stack` in place (damage/count) - convert the SAME
            // (possibly mutated) reference, not the pre-call snapshot.
            ItemUseResult out = new ItemUseResult(handled, UmbItemConv.toStackData(stack));
            umbWorld.host().log("ENTITY-DIAG useItemOnBlock after-func id=" + legacyItemId
                    + " handled=true count=" + out.stack.count
                    + " legacyLoadedEntities=" + umbWorld.field_72996_f.size());
            return out;
        } catch (Throwable t) {
            umbWorld.host().log("useItemOnBlock failed for " + legacyItemId + ": " + t);
            return ItemUseResult.DECLINED;
        }
    }

    /**
     * The declined-use air fallback (see {@link #useItemOnBlock}): runs legacy
     * {@code Item.func_77659_a} (onItemRightClick) on the SAME stack that
     * {@code func_77648_a} (onItemUse) just declined, in the caller's pull/push scope.
     * Public and static so headless tests can drive it with a synthetic Item (no GameData
     * registry needed) - the same precedent as {@link #startHostUseIfLegacyRequested}.
     * Returns null only when the fallback itself threw (already logged); the caller must
     * then treat the whole dispatch as declined, exactly like an onItemUse throw.
     */
    public static ItemUseResult runDeclinedAirFallback(UmbWorld world, Item item, ItemStack stack,
            UmbPlayer player, HostPlayer hostPlayer, String legacyItemId) {
        int countBefore = stack.field_77994_a;
        ItemStack airResult;
        try {
            airResult = item.func_77659_a(stack, world, player);
        } catch (Throwable t) {
            world.host().log("useItemOnBlock: onItemRightClick fallback threw for "
                    + legacyItemId + ": " + t);
            return null;
        }
        boolean airHandled = airUseConsumed(stack, countBefore, airResult);
        startHostUseIfLegacyRequested(hostPlayer, player);
        return new ItemUseResult(airHandled,
                UmbItemConv.toStackData(airResult == null ? stack : airResult));
    }

    /**
     * 1.7.10 {@code PlayerControllerMP.func_78769_a} (sendUseItem) return criterion,
     * stack with a changed count. Damage is NOT consulted. A null return counts as consumed
     * (it differs from the non-null stack passed in).
     */
    public static boolean airUseConsumed(ItemStack before, int countBefore, ItemStack after) {
        return after != before || (after != null && after.field_77994_a != countBefore);
    }

    /** Items whose tooltip code needs client-only state: logged once, then skipped. */
    private static final Set<String> tooltipFailed =
            ConcurrentHashMap.newKeySet();

    @Override
    public List<String> itemTooltip(String legacyItemId, StackData stackData, boolean advanced) {
        ensureBooted();
        List<String> lines = new ArrayList<String>();
        // Some tooltip bodies reach client-only state (Minecraft.getMinecraft()); they NPE
        // on every hover. Remember failures per item: log once, then return the host
        // fallback (whatever accumulated, usually empty) without retrying.
        if (legacyItemId != null && tooltipFailed.contains(legacyItemId)) {
            return lines;
        }
        try {
            Item item = GameData.getItemRegistry().get(legacyItemId);
            ItemStack stack = UmbItemConv.toLegacy(stackData);
            if (item == null || stack == null) {
                return lines;
            }
            // func_77624_a (methods.csv: addInformation) accepts a nullable player; tooltip
            // generation must also work on the client where no ServerPlayer crosses this boundary.
            item.func_77624_a(stack, null, lines, advanced);
        } catch (Throwable t) {
            if (legacyItemId == null || tooltipFailed.add(legacyItemId)) {
                umbWorld.host().log("itemTooltip failed for " + legacyItemId
                        + " (cached, further failures silent): " + t);
            }
        }
        return lines;
    }

    @Override
    public StackData itemInventoryTick(String legacyItemId, StackData stackData, HostPlayer hostPlayer,
                                       int slot, boolean current) {
        // INPUT-BRIDGE exactly-once rule: the per-slot tick lives in tickEvents
        // (UmbPlayer.tickInventoryItems, facade objects, every slot, input-independent).
        // Running Item.onUpdate here too — on a throwaway copy whose NBT is written back
        // to the host — double-ticks state machines AND corrupts them: the gun's
        // unselected-slot branch resets DRAWING + full timer, which is exactly the
        // frozen-draw the live game showed. So this is deliberately a pass-through now;
        // the host twin keeps calling (harmless), tooltips/syncs keep working.
        return stackData;
    }

    @Override
    public int itemUseDuration(String legacyItemId, StackData stackData) {
        ensureBooted();
        try {
            Item item = GameData.getItemRegistry().get(legacyItemId);
            ItemStack stack = UmbItemConv.toLegacy(stackData);
            return item == null || stack == null ? 0 : item.func_77626_a(stack);
        } catch (Throwable t) {
            umbWorld.host().log("itemUseDuration failed for " + legacyItemId + ": " + t);
            return 0;
        }
    }

    @Override
    public String itemUseAction(String legacyItemId, StackData stackData) {
        ensureBooted();
        try {
            Item item = GameData.getItemRegistry().get(legacyItemId);
            ItemStack stack = UmbItemConv.toLegacy(stackData);
            return item == null || stack == null ? "none" : item.func_77661_b(stack).name();
        } catch (Throwable t) {
            umbWorld.host().log("itemUseAction failed for " + legacyItemId + ": " + t);
            return "none";
        }
    }

    @Override
    public float itemDestroySpeed(String legacyItemId, StackData stackData, String legacyBlockId) {
        ensureBooted();
        try {
            ItemStack stack = UmbItemConv.toLegacy(stackData);
            // Registry lookup is the normal path.  The static name lookup is the same 1.7.10
            // contract used by vanilla Item, and covers snapshots whose GameData reverse view is
            // not populated yet during the first client/server mirror tick.
            Item item = GameData.getItemRegistry().get(legacyItemId);
            if (item == null && stack != null) item = stack.func_77973_b();
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) block = Block.func_149684_b(legacyBlockId);
            if (item == null || stack == null || block == null) return Float.NaN;
            return item.func_150893_a(stack, block);
        } catch (Throwable t) {
            umbWorld.host().log("itemDestroySpeed failed for " + legacyItemId + ": " + t);
            return Float.NaN;
        }
    }

    @Override
    public boolean itemCanHarvestBlock(String legacyItemId, StackData stackData, String legacyBlockId) {
        ensureBooted();
        try {
            Item item = GameData.getItemRegistry().get(legacyItemId);
            Block block = GameData.getBlockRegistry().get(legacyBlockId);
            if (block == null) block = Block.func_149684_b(legacyBlockId);
            if (item == null || block == null) return false;
            return item.func_150897_b(block);
        } catch (Throwable t) {
            umbWorld.host().log("itemCanHarvestBlock failed for " + legacyItemId + ": " + t);
            return false;
        }
    }

    @Override
    public void itemUsingTick(String legacyItemId, StackData stackData, HostPlayer hostPlayer,
                              int remaining) {
        dispatchItemPlayer(legacyItemId, stackData, hostPlayer, remaining, true);
    }

    @Override
    public void itemStoppedUsing(String legacyItemId, StackData stackData, HostPlayer hostPlayer,
                                 int remaining) {
        ensureBooted();
        try {
            Item item = GameData.getItemRegistry().get(legacyItemId);
            ItemStack stack = UmbItemConv.toLegacy(stackData);
            if (item != null && stack != null) {
                item.func_77615_a(stack, umbWorld, hostPlayer == null ? null : UmbPlayer.create(umbWorld, hostPlayer), remaining);
            }
        } catch (Throwable t) {
            umbWorld.host().log("itemStoppedUsing failed for " + legacyItemId + ": " + t);
        }
    }

    @Override
    public StackData itemEaten(String legacyItemId, StackData stackData, HostPlayer hostPlayer) {
        ensureBooted();
        try {
            Item item = GameData.getItemRegistry().get(legacyItemId);
            ItemStack stack = UmbItemConv.toLegacy(stackData);
            if (item == null || stack == null) {
                return stackData;
            }
            ItemStack result = item.func_77654_b(stack, umbWorld,
                    hostPlayer == null ? null : UmbPlayer.create(umbWorld, hostPlayer));
            return UmbItemConv.toStackData(result);
        } catch (Throwable t) {
            umbWorld.host().log("itemEaten failed for " + legacyItemId + ": " + t);
            return stackData;
        }
    }

    private void dispatchItemPlayer(String legacyItemId, StackData stackData, HostPlayer hostPlayer,
                                    int remaining, boolean using) {
        ensureBooted();
        try {
            Item item = GameData.getItemRegistry().get(legacyItemId);
            ItemStack stack = UmbItemConv.toLegacy(stackData);
            if (item == null || stack == null) {
                return;
            }
            net.minecraft.entity.player.EntityPlayer player = hostPlayer == null
                    ? null : UmbPlayer.create(umbWorld, hostPlayer);
            if (using) {
                item.onUsingTick(stack, player, remaining);
            }
        } catch (Throwable t) {
            umbWorld.host().log("itemUsingTick failed for " + legacyItemId + ": " + t);
        }
    }

    // ---- ENTITY-BRIDGE: persistence reconstruction ----

    /**
     * Host calls this when a saved native twin is loaded from disk and needs a live legacy
     * {@code Entity} reconstructed. Uses the generic vanilla/Forge factory
     * {@code EntityList.createEntityFromNBT} (func_75615_a) - the SAME mechanism vanilla's own
     * chunk loader uses for every entity in every chunk, mod or vanilla, with zero mod-specific
     * code here: it reads the blob's own "id" tag (written by {@link EntityHandleImpl#saveNbt()}
     * via {@code writeToNBTOptional}), resolves the registered class generically off
     * {@code EntityList.stringToClassMapping}, constructs it, and calls its {@code readFromNBT}
     * (func_70020_e) - all inside this one call.
     */
    @Override
    public EntityHandle restoreEntity(byte[] nbt) {
        ensureBooted();
        if (nbt == null) {
            return null;
        }
        try {
            NBTTagCompound tag = CompressedStreamTools.func_152457_a(nbt, NBTSizeTracker.field_152451_a);
            Entity restored = EntityList.func_75615_a(tag, umbWorld);
            if (restored == null) {
                umbWorld.host().log("restoreEntity: EntityList.createEntityFromNBT returned null (id=\""
                        + tag.func_74779_i("id") + "\") - unknown/unregistered class or corrupt data");
                return null;
            }
            // A native chunk reload may restore a second UmbLegacyEntity while its original
            // legacy object survived the host unload. Reuse the UUID-matched live handle so the
            // universe has one legacy object and one twin, never a duplicate pair.
            return umbWorld.trackEntityOrExisting(restored);
        } catch (Throwable t) {
            umbWorld.host().log("restoreEntity failed: " + t);
            return null;
        }
    }

    @Override
    public void tickTile(TileHandle t) {
        if (t == null) {
            return;
        }
        // TileHandleImpl.tick() already poisons-and-stops internally on the first throw
        // (DESIGN.md risk #4); this call must still never propagate to the caller.
        try {
            t.tick();
        } catch (Throwable ignored) {
            // belt and suspenders - see TileHandleImpl
        }
    }

    @Override
    public void shutdown() {
        // Matches LegacyDriver.close(): nothing is safely reclaimable - FML wrapped System.out,
        // registered shutdown hooks and started log4j threads. The universe is single-shot.
    }

    @Override
    public void guiButtonPacket(String guiClass, int buttonId, int x, int y, int z, String[] args) {
        ensureBooted();
        GuiButtonPacketDispatcher.dispatch(guiClass, buttonId, x, y, z, args, activeGuiPlayer);
    }

    @Override
    public boolean guiMouseClick(String guiClass, int x, int y, int z,
                                 int guiX, int guiY, int button, int screenX, int screenY) {
        ensureBooted();
        return LegacyGuiMouseDispatcher.dispatch(guiClass, x, y, z, guiX, guiY, button,
                screenX, screenY);
    }

    @Override
    public boolean guiKeyTyped(String guiClass, char typedChar, int keyCode) {
        ensureBooted();
        return LegacyGuiMouseDispatcher.dispatchKey(guiClass, typedChar, keyCode);
    }

    @Override
    public boolean guiTextFocused() {
        return booted && LegacyGuiMouseDispatcher.textFocused();
    }

    private void ensureBooted() {
        if (!booted) {
            throw new IllegalStateException("the legacy universe has not been booted yet");
        }
    }

    /** Same defaults {@code dev.umb.legacy.boot.Bootstrap} uses, read from the same system properties. */
    private static UniverseConfig buildConfig() {
        String repo = require("umb.repo");
        String forgeJar = System.getProperty("umb.legacy.forgeJar",
                repo + "/build/legacy/forge-1.7.10-10.13.4.1614-srg.jar");
        String outDir = System.getProperty("umb.legacy.out", repo + "/research/out/legacy/legacy-boot");
        String gameDir = System.getProperty("umb.legacy.gameDir", outDir);
        String modsDir = System.getProperty("umb.legacy.modsDir", gameDir + "/mods");
        String assetsDir = System.getProperty("umb.legacy.assetsDir",
                repo + "/research/visual/mc1710-native/assets");
        boolean sideTransformer = Boolean.parseBoolean(System.getProperty("umb.legacy.sideTransformer", "false"));
        return new UniverseConfig(gameDir, modsDir, assetsDir, forgeJar, sideTransformer,
                transformerList(sideTransformer));
    }

    /** Identical to {@code Bootstrap.transformerList} (private there, application-loader side). */
    private static List<String> transformerList(boolean sideTransformer) {
        List<String> t = new ArrayList<String>();
        t.add("dev.umb.legacy.legacyside.UmbShimTransformer");
        t.add("dev.umb.legacy.legacyside.UmbForgeWorldProviderTransformer");
        // No event transformer of our own: Forge's EventSubscriptionTransformer instruments the
        // whole event tree (public no-arg ctor + ListenerList per Event subclass) PROVIDED no
        // event class is defined before it is registered - see LegacyEventPoster, which keeps
        // every event reference out of pre-registration-linked classes. The hand-picked
        // UmbEventTransformer used to paper over that ordering bug instead, and its PlayerEvent
        // patch called a PRIVATE super constructor (IllegalAccessError on every login event).
        t.add("cpw.mods.fml.common.asm.transformers.MarkerTransformer");
        if (sideTransformer) {
            t.add("cpw.mods.fml.common.asm.transformers.SideTransformer");
        }
        t.add("cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer");
        t.add("cpw.mods.fml.common.asm.transformers.AccessTransformer");
        t.add("net.minecraftforge.classloading.FluidIdTransformer");
        t.add("net.minecraftforge.transformers.ForgeAccessTransformer");
        t.add("cpw.mods.fml.common.asm.transformers.ItemStackTransformer");
        return t;
    }

    private static String require(String prop) {
        String v = System.getProperty(prop);
        if (v == null) {
            throw new IllegalStateException("-D" + prop + " is required to boot the legacy bridge");
        }
        return v;
    }
}
