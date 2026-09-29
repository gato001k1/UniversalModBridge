package dev.umb.legacy.legacyside;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiPlayerInfo;
import net.minecraft.client.gui.MapItemRenderer;
import net.minecraft.client.gui.MapItemRenderer;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.profiler.Profiler;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.network.Packet;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.IChatComponent;
import net.minecraft.util.Session;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.SidedProxy;

import dev.umb.legacy.legacyside.input.LegacyInputDiag;
import dev.umb.legacy.legacyside.input.LegacyModClasses;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.EffectData;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.LegacyClientTileBridge;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;

/**
 * Minimal client view for client-only legacy code executed in the existing SERVER universe.
 *
 * <p>This is option (b) from the client-universe design: one synthetic client facade is rebound
 * around the already-authoritative legacy player/world for the duration of a client handler tick.
 * The objects are allocated without vanilla constructors because those constructors require a
 * display, Netty connection, chunk source, and real client thread. Only the fields that client
 * handlers use as identity/context are seeded. No renderer or GL path is entered.</p>
 *
 * fields field_71432_P, field_71439_g, field_71441_e, field_71474_y, field_71412_D;
 * Entity.field_70154_o and field_70170_p; World.field_72995_K. This class intentionally has no
 * mod identity or packet knowledge.</p>
 */
public final class LegacyClientFacade {
    // Unsafe-allocated legacy Minecraft has no native window. Keep old client mods'
    // integer display divisions non-zero during headless client-tick dispatch.
    private static final int DEFAULT_DISPLAY_WIDTH = 854;
    private static final int DEFAULT_DISPLAY_HEIGHT = 480;

    /**
     * Last client-world facade installed by the bounded dispatcher.  The 26.2 render thread can
     * arrive after the authoritative server copy has been detached from its ServerLevel lookup,
     * but this cache is already populated from the same server snapshot and is safe to query by
     * coordinates.  It is deliberately typed only to the generic legacy client world; no mod
     * renderer or block class is named here.
     */
    private static volatile UmbClientWorld LAST_CLIENT_WORLD;
    /** Captures legacy ISound calls and hands them to the native client effect seam. This is
     * deliberately keyed by ISound identity: a moving legacy sound mutates one object in place
     * while its updater changes position, pitch and volume. */
    private static final class ForwardingSoundHandler extends SoundHandler {
        private final HostWorld host;
        private final String playerId;
        private final java.util.Set<ISound> loops = java.util.Collections.newSetFromMap(
                new IdentityHashMap<ISound, Boolean>());

        ForwardingSoundHandler(HostWorld host, String playerId) {
            super(null, null);
            this.host = host;
            this.playerId = playerId == null ? "" : playerId;
        }

        @Override
        public void func_147682_a(ISound sound) {
            if (sound == null || host == null) return;
            try {
                ResourceLocation id = sound.func_147650_b();
                if (id == null) return;
                String name = id.toString();
                double x = sound.func_147649_g();
                double y = sound.func_147654_h();
                double z = sound.func_147651_i();
                float volume = finite(sound.func_147653_e(), 1.0F);
                float pitch = finite(sound.func_147655_f(), 1.0F);
                long tick = host.getTotalTime();
                if (sound.func_147657_c()) {
                    EffectData effect = new EffectData(loops.add(sound) ? "loop_start" : "loop_update",
                            playerId, name, tick, x, y, z, 0, 0, 0, volume, pitch, 0, new byte[0]);
                    host.enqueueClientEffect(effect);
                } else {
                    host.enqueueClientEffect(new EffectData("sound", playerId, name, tick,
                            x, y, z, 0, 0, 0, volume, pitch, 0, new byte[0]));
                }
            } catch (Throwable t) {
                host.log("UMB-FX legacy ISound dropped: " + t.getClass().getName());
            }
        }

        @Override
        public void func_147683_b(ISound sound) {
            if (sound == null || host == null || !loops.remove(sound)) return;
            try {
                ResourceLocation id = sound.func_147650_b();
                if (id != null) host.enqueueClientEffect(new EffectData("loop_stop", playerId,
                        id.toString(), host.getTotalTime(), sound.func_147649_g(), sound.func_147654_h(),
                        sound.func_147651_i(), 0, 0, 0, 0, 0, 0, new byte[0]));
            } catch (Throwable t) {
                host.log("UMB-FX legacy ISound stop dropped: " + t.getClass().getName());
            }
        }

        @Override
        public boolean func_147692_c(ISound sound) {
            return sound != null && loops.contains(sound);
        }

        @Override
        public void func_147690_c() {
            for (ISound sound : new ArrayList<ISound>(loops)) func_147683_b(sound);
        }

        private static float finite(float value, float fallback) {
            return Float.isNaN(value) || Float.isInfinite(value) ? fallback : value;
        }
    }

    /** Native-free 1.7.10 client network facade; all client-originated packets use loopback. */
    private static final class FacadeNetHandler extends NetHandlerPlayClient {
        private FacadeNetHandler() {
            super(null, null, null);
        }

        static FacadeNetHandler create(Minecraft minecraft, WorldClient world,
                HostWorld host, Object serverPlayer) {
            FacadeNetHandler handler = UmbUnsafe.allocate(FacadeNetHandler.class);
            setField(handler, NetHandlerPlayClient.class, "field_147299_f", minecraft);
            setField(handler, NetHandlerPlayClient.class, "field_147300_g", world);
            setField(handler, NetHandlerPlayClient.class, "field_147303_b",
                    playerInfoList(host, serverPlayer));
            setField(handler, NetHandlerPlayClient.class, "field_147304_c", Integer.valueOf(20));
            setField(handler, NetHandlerPlayClient.class, "field_147310_i",
                    new java.util.HashMap<Object, Object>());
            setField(handler, NetHandlerPlayClient.class, "field_147306_l",
                    new java.util.Random());
            return handler;
        }

        @Override
        public void func_147297_a(Packet packet) {
            LegacyNetworkLoopback.captureClientPacket(packet);
        }
    }

    /** Keeps client chat calls useful without constructing a native client HUD. */
    private static final class FacadeChat extends GuiNewChat {
        private final HostWorld host;

        FacadeChat(Minecraft minecraft, HostWorld host) {
            super(minecraft);
            this.host = host;
        }

        @Override
        public void func_146227_a(IChatComponent component) {
            if (host != null && component != null) {
                host.log("[UMB-CHAT] " + component.func_150260_c());
            }
        }

        @Override
        public void func_146234_a(IChatComponent component, int updateCounter) {
            func_146227_a(component);
        }
    }

    private static final class FacadeGuiIngame extends GuiIngame {
        private final GuiNewChat chat;
        private final HostWorld host;

        FacadeGuiIngame(Minecraft minecraft, HostWorld host) {
            super(minecraft);
            this.host = host;
            this.chat = new FacadeChat(minecraft, host);
        }

        @Override
        public GuiNewChat func_146158_b() {
            return chat;
        }

        @Override
        public void func_110326_a(String message, boolean animate) {
            if (host != null && message != null) {
                host.log("[UMB-CHAT] " + message);
            }
        }
    }
    private static final Map<String, KeyBinding> FACADE_KEY_BINDINGS =
            new java.util.HashMap<String, KeyBinding>();
    /** Process-local caches; the dispatcher serializes facade installation and proxy swaps. */
    private static final Map<String, Field> REFLECTED_FIELDS =
            new java.util.HashMap<String, Field>();
    private static final Map<String, Field> FIRST_FIELDS_BY_TYPE =
            new java.util.HashMap<String, Field>();
    private static final Map<Field, Object> CLIENT_PROXY_CACHE =
            new java.util.HashMap<Field, Object>();
    private static final Map<Field, Object> ENTITY_RENDERER_CACHE =
            new java.util.HashMap<Field, Object>();
    /** Native-free client services shared by the short-lived per-tick Minecraft facades. */
    private static volatile TextureManager CLIENT_TEXTURE_MANAGER;
    private static volatile FontRenderer CLIENT_FONT_RENDERER;
    private static IResourceManager CLIENT_RESOURCE_MANAGER;
    private static IResourceManager CLIENT_FONT_RESOURCE_MANAGER;
    private static volatile long ATLAS_NEXT_ATTEMPT_NANOS;
    private static final ResourceLocation VANILLA_FONT =
            new ResourceLocation("textures/font/ascii.png");

    public static final class Binding {
        public final Minecraft minecraft;
        public final EntityClientPlayerMP player;
        public final WorldClient world;
        public final GameSettings gameSettings;
        private final ProxyScope proxyScope;
        private final EntityPlayerMP previousFacadePlayer;
        private final boolean facadePlayerBound;

        private Binding(Minecraft minecraft, EntityClientPlayerMP player, WorldClient world,
                        GameSettings gameSettings, ProxyScope proxyScope,
                        EntityPlayerMP previousFacadePlayer, boolean facadePlayerBound) {
            this.minecraft = minecraft;
            this.player = player;
            this.world = world;
            this.gameSettings = gameSettings;
            this.proxyScope = proxyScope;
            this.previousFacadePlayer = previousFacadePlayer;
            this.facadePlayerBound = facadePlayerBound;
        }

        void restoreProxies() {
            try {
                proxyScope.restore();
            } finally {
                if (facadePlayerBound) {
                    LegacyNetworkLoopback.restoreFacadePlayer(previousFacadePlayer);
                }
            }
        }
    }

    /**
     * Temporary client-world view for the authoritative mounted entity graph.  Client handlers
     * and client-side entity update code must observe the same WorldClient as Minecraft, while
     * the server universe must retain ownership of those entities immediately after dispatch.
     */
    public static final class EntityWorldScope {
        private final List<EntityWorld> changed;
        private final Object root;
        private final Object authoritativeWorld;
        private final WorldClient clientWorld;
        private final List<PassengerLink> passengerLinks;
        private final List<Object[]> worldFieldSwaps = new ArrayList<Object[]>();
        private final List<Object[]> primitiveSnapshots = new ArrayList<Object[]>();
        private boolean restored;

        private EntityWorldScope(List<EntityWorld> changed, Object root, Object authoritativeWorld,
                WorldClient clientWorld, List<PassengerLink> passengerLinks) {
            this.changed = changed;
            this.root = root;
            this.authoritativeWorld = authoritativeWorld;
            this.clientWorld = clientWorld;
            this.passengerLinks = passengerLinks;
        }

        public void restore() {
            if (restored) {
                return;
            }
            restored = true;
            for (int i = primitiveSnapshots.size() - 1; i >= 0; i--) {
                Object[] snapshot = primitiveSnapshots.get(i);
                try {
                    UmbUnsafe.setField(snapshot[0], (Field) snapshot[1], snapshot[2]);
                } catch (Throwable t) {
                    if (LegacyInputDiag.oncePer("client-primitive-restore", 60_000_000_000L)) {
                        LegacyInputDiag.log("client primitive restore failed cause="
                                + t.getClass().getName());
                    }
                }
            }
            for (int i = worldFieldSwaps.size() - 1; i >= 0; i--) {
                Object[] swap = worldFieldSwaps.get(i);
                try {
                    UmbUnsafe.setField(swap[0], (Field) swap[1], swap[2]);
                } catch (Throwable t) {
                    if (LegacyInputDiag.oncePer("client-worldfield-restore", 60_000_000_000L)) {
                        LegacyInputDiag.log("client world-field restore failed " + t);
                    }
                }
            }
            for (int i = passengerLinks.size() - 1; i >= 0; i--) {
                PassengerLink link = passengerLinks.get(i);
                try {
                    setField(link.owner, net.minecraft.entity.Entity.class,
                            "field_70153_n", link.previousPassenger);
                } catch (Throwable t) {
                    if (LegacyInputDiag.oncePer("client-passenger-restore:" +
                            link.owner.getClass().getName(), 0)) {
                        LegacyInputDiag.log("client passenger restore failed entity="
                                + link.owner.getClass().getName() + " cause="
                                + t.getClass().getName());
                    }
                }
            }
            for (int i = changed.size() - 1; i >= 0; i--) {
                EntityWorld entry = changed.get(i);
                Object world = authoritativeWorld == null ? entry.world : authoritativeWorld;
                // A listener may have left a previously rebound entity in the client view, or
                // may have changed the mounted graph while it ran. The server world is the
                // authoritative owner for every legacy entity in this option-(b) universe.
                if (world == null || world == clientWorld) {
                    world = authoritativeWorld;
                }
                if (entry.entity != root && world != null) {
                    try {
                        setWorld(entry.entity, world);
                    } catch (Throwable t) {
                        if (LegacyInputDiag.oncePer("client-world-restore:" +
                                entry.entity.getClass().getName(), 0)) {
                            LegacyInputDiag.log("client world restore failed entity="
                                    + entry.entity.getClass().getName() + " cause="
                                    + t.getClass().getName());
                        }
                    }
                }
            }
            if (authoritativeWorld != null) {
                try {
                    restoreMountedGraph(root, authoritativeWorld,
                            new IdentityHashMap<Object, Boolean>(), 0);
                } catch (Throwable t) {
                    if (LegacyInputDiag.oncePer("client-world-restore-graph", 0)) {
                        LegacyInputDiag.log("client mounted graph restore failed cause="
                                + t.getClass().getName());
                    }
                }
            }
        }

        private void restoreMountedGraph(Object entity, Object world,
                Map<Object, Boolean> seen, int depth) {
            if (!(entity instanceof net.minecraft.entity.Entity) || depth > 16
                    || seen.put(entity, Boolean.TRUE) != null) {
                return;
            }
            if (entity != root) {
                setWorld(entity, world);
            }
            restoreMountedGraph(readField(entity, net.minecraft.entity.Entity.class, "field_70154_o"),
                    world, seen, depth + 1);
            restoreMountedGraph(readField(entity, net.minecraft.entity.Entity.class, "field_70153_n"),
                    world, seen, depth + 1);
        }
    }

    private static final class EntityWorld {
        final Object entity;
        final Object world;

        EntityWorld(Object entity, Object world) {
            this.entity = entity;
            this.world = world;
        }
    }

    private static final class PassengerLink {
        final Object owner;
        final Object previousPassenger;

        PassengerLink(Object owner, Object previousPassenger) {
            this.owner = owner;
            this.previousPassenger = previousPassenger;
        }
    }

    /** Restores server-side SidedProxy fields after the bounded client dispatch. */
    private static final class ProxyScope {
        private final List<ProxyValue> changed;
        private boolean restored;

        ProxyScope(List<ProxyValue> changed) {
            this.changed = changed;
        }

        synchronized void restore() {
            if (restored) {
                return;
            }
            restored = true;
            for (int i = changed.size() - 1; i >= 0; i--) {
                ProxyValue value = changed.get(i);
                try {
                    if (value.target == null) {
                        value.field.set(null, value.previous);
                    } else {
                        UmbUnsafe.setField(value.target, value.field, value.previous);
                    }
                } catch (Throwable t) {
                    if (LegacyInputDiag.oncePer("client-proxy-restore:" + value.field, 60_000_000_000L)) {
                        LegacyInputDiag.log("client proxy restore failed field=" + value.field
                                + " cause=" + t.getClass().getName());
                    }
                }
            }
        }
    }

    private static final class ProxyValue {
        final Object target;
        final Field field;
        final Object previous;

        ProxyValue(Field field, Object previous) {
            this(null, field, previous);
        }

        ProxyValue(Object target, Field field, Object previous) {
            this.target = target;
            this.field = field;
            this.previous = previous;
        }
    }

    private static final class ClientProxyRegistration {
        final Field field;
        final Object clientProxy;

        ClientProxyRegistration(Field field, Object clientProxy) {
            this.field = field;
            this.clientProxy = clientProxy;
        }
    }

    private static volatile List<ClientProxyRegistration> CLIENT_PROXY_REGISTRATIONS;
    private static volatile int CLIENT_PROXY_MOD_COUNT = -1;
    private static volatile long CLIENT_PROXY_REDISCOVER_AFTER;
    private static final Object CLIENT_PROXY_DISCOVERY_LOCK = new Object();

    /** Restores handler-owned Minecraft references after one bounded client dispatch. */
    public static final class MinecraftReferenceScope {
        private final List<ProxyValue> changed;
        private boolean restored;

        private MinecraftReferenceScope(List<ProxyValue> changed) {
            this.changed = changed;
        }

        public synchronized void restore() {
            if (restored) {
                return;
            }
            restored = true;
            for (int i = changed.size() - 1; i >= 0; i--) {
                ProxyValue value = changed.get(i);
                try {
                    UmbUnsafe.setField(value.target, value.field, value.previous);
                } catch (Throwable ignored) {
                    // A handler may have been unloaded between dispatch and restoration.
                }
            }
        }
    }

    private LegacyClientFacade() {
    }

    /** Builds and installs a singleton facade; {@code serverPlayer/serverWorld} may be null in a probe. */
    public static Binding install(Object serverPlayer, Object serverWorld) {
        Minecraft minecraft = UmbUnsafe.allocate(Minecraft.class);
        EntityClientPlayerMP player = UmbUnsafe.allocate(EntityClientPlayerMP.class);
        UmbClientWorld world = UmbUnsafe.allocate(UmbClientWorld.class);
        UmbEffectRenderer effectRenderer = UmbUnsafe.allocate(UmbEffectRenderer.class);
        GameSettings settings = UmbUnsafe.allocate(GameSettings.class);
        seedGameSettings(settings);
        // captureTile enters with the tile temporarily bound to the previous UmbClientWorld.
        // Reinstalling a facade from that view must retain its authoritative UmbWorld; treating
        // the client view as a fresh/null server world replaces the shared tile map with an empty
        // one and leaves the host renderer's provider installed but unable to resolve any tile.
        UmbWorld authoritativeWorld = serverWorld instanceof UmbWorld ? (UmbWorld) serverWorld
                : serverWorld instanceof UmbClientWorld
                        ? ((UmbClientWorld) serverWorld).authoritativeWorld() : null;
        HostWorld host = authoritativeWorld == null ? null : authoritativeWorld.host();
        world.bindHost(host);
        world.bindAuthoritative(authoritativeWorld);
        if (authoritativeWorld != null && world.clientTileCount() == 0
                && LegacyInputDiag.oncePer("client-tile-bind-empty:" + System.identityHashCode(authoritativeWorld),
                        60_000_000_000L)) {
            LegacyInputDiag.log("client tile facade bind empty authoritative="
                    + authoritativeWorld.getClass().getName() + " source="
                    + (serverWorld == null ? "null" : serverWorld.getClass().getName()));
        }
        LAST_CLIENT_WORLD = world;
        LegacyClientTileBridge.install("1.7.10", new LegacyClientTileBridge.Provider() {
            @Override public Object tileAt(int x, int y, int z) {
                return clientLegacyTileAt(x, y, z);
            }
            @Override public String diagnose(String requestedDimension, int x, int y, int z) {
                UmbClientWorld current = LAST_CLIENT_WORLD;
                return current == null
                        ? "provider=present requestedDim=" + String.valueOf(requestedDimension)
                                + " requested=" + x + "," + y + "," + z + " world=null"
                        : current.clientTileDiagnostic(requestedDimension, x, y, z);
            }
        });
        effectRenderer.bindHost(host);
        // AbstractClientPlayer's constructor path asks Minecraft for SkinManager while a
        // client-only view entity is created by a tick handler. The real client owns this
        // singleton; option (b) must provide the same non-null service boundary.
        setField(minecraft, Minecraft.class, "field_152350_aA",
                UmbUnsafe.allocate(net.minecraft.client.resources.SkinManager.class));
        // field_110451_am is Minecraft's resource manager, not its sound service.  The
        // client visual boundary installs the real native-free resource manager before
        // reload listeners run; seed the sound service by type so the two contracts cannot
        // be crossed by an obfuscated field-name guess.
        setFirstFieldOfType(minecraft, SoundHandler.class,
                new ForwardingSoundHandler(host, legacyPlayerName(serverPlayer)));

        // install() publishes the facade where concurrent render-thread singleton
        // reads can observe it - vanilla RenderItem's missing-icon path calls
        // Minecraft.func_71410_x().func_110434_K() on every draw - and a read landing
        // between publish and bind sees field_71446_o null. bindSharedClientServices
        // touches only the fresh object, so seeding first changes nothing else.
        bindSharedClientServices(minecraft);
        set(Minecraft.class, "field_71432_P", null, minecraft);
        // The synthetic client has no constructor-created EntityRenderer.  Legacy client
        // listeners are still allowed to write camera state (for example, roll) through the
        // vanilla Minecraft facade, so leaving this field null turns an otherwise isolated
        // listener failure into a repeated ReflectionHelper NPE on the server tick.  Find the
        // field by its type rather than an obfuscated name and allocate it without invoking a
        // display/GL constructor; the emulation boundary owns all render calls.
        seedClientEntityRenderer(minecraft);
        seedClientRenderGlobal(minecraft);
        bindFmlClientHandler(minecraft);
        // These are logical capture dimensions, not a claim about a native window. HBM and
        // other 1.7.10 clients divide mouse coordinates by them during client ticks.
        setField(minecraft, Minecraft.class, "field_71443_c", DEFAULT_DISPLAY_WIDTH);
        setField(minecraft, Minecraft.class, "field_71440_d", DEFAULT_DISPLAY_HEIGHT);
        setField(minecraft, Minecraft.class, "field_71439_g", player);
        setField(minecraft, Minecraft.class, "field_71451_h", player);
        setField(minecraft, Minecraft.class, "field_71441_e", world);
        setField(minecraft, Minecraft.class, "field_71474_y", settings);
        setField(minecraft, Minecraft.class, "field_71452_i", effectRenderer);
        setField(minecraft, Minecraft.class, "field_135017_as",
                UmbUnsafe.allocate(net.minecraft.client.resources.LanguageManager.class));
        seedClientI18n(null);
        UmbUnsafe.setField(minecraft, field(Minecraft.class, "field_71412_D"), new File("."));

        // WorldClient is the client twin; its SRG isRemote flag must be true so generic client
        // helpers (including view-entity facades) take their client branch.
        setField(world, net.minecraft.world.World.class, "field_72995_K", Boolean.TRUE);
        net.minecraft.world.WorldProvider provider =
                UmbUnsafe.allocate(net.minecraft.world.WorldProviderSurface.class);
        setField(world, net.minecraft.world.World.class, "field_73011_w", provider);
        // WorldProvider.getSeed() is a Forge-added API. Link the synthetic provider back to this
        // client world so the generic Forge method can read WorldInfo without a real constructor.
        setField(provider, net.minecraft.world.WorldProvider.class, "field_76579_a", world);
        Object worldInfo = readField(serverWorld, net.minecraft.world.World.class, "field_72986_A");
        setField(world, net.minecraft.world.World.class, "field_72986_A",
                worldInfo == null ? new UmbWorldInfo() : worldInfo);
        Object scoreboard = readField(authoritativeWorld == null ? serverWorld : authoritativeWorld,
                net.minecraft.world.World.class, "field_96442_D");
        setField(world, net.minecraft.world.World.class, "field_96442_D",
                scoreboard == null ? new net.minecraft.scoreboard.Scoreboard() : scoreboard);
        setField(world, net.minecraft.world.World.class, "field_73012_v",
                valueOr(serverWorld, net.minecraft.world.World.class, "field_73012_v", new java.util.Random()));
        setField(world, net.minecraft.world.World.class, "field_72984_F",
                valueOr(serverWorld, net.minecraft.world.World.class, "field_72984_F", new Profiler()));
        setField(world, net.minecraft.world.World.class, "field_73013_u",
                valueOr(serverWorld, net.minecraft.world.World.class, "field_73013_u", EnumDifficulty.NORMAL));
        // The authoritative facade provider is deliberately chunkless; the client twin needs
        // real empty Chunk shells because World.addEntityToWorld indexes client-only view entities.
        setField(world, net.minecraft.world.World.class, "field_73020_y",
                new UmbClientChunkProvider(world));
        setField(world, net.minecraft.world.World.class, "field_72996_f",
                copyList(readField(serverWorld, net.minecraft.world.World.class, "field_72996_f")));
        setField(world, net.minecraft.world.World.class, "field_73010_i",
                copyList(readField(serverWorld, net.minecraft.world.World.class, "field_73010_i")));
        setField(world, net.minecraft.world.World.class, "field_147482_g",
                world.clientTileSnapshot());
        setField(world, net.minecraft.world.World.class, "field_73021_x", new ArrayList<Object>());
        setField(world, net.minecraft.world.World.class, "field_72998_d", new ArrayList<Object>());
        // WorldClient keeps its own entity/chunk bookkeeping outside World's lists; Unsafe
        // allocation skips the constructor that normally creates these collections.
        // The 1.7.10 SRG field field_73037_M is WorldClient.mc. MuzzleFlashPacket and other
        // generic client packets read it through WorldClient methods; seed it because Unsafe
        // allocation bypasses the constructor assignment from Minecraft.func_71410_x().
        setField(world, WorldClient.class, "field_73037_M", minecraft);
        setField(world, WorldClient.class, "field_73034_c", new net.minecraft.util.IntHashMap());
        setField(world, WorldClient.class, "field_73032_d", new java.util.HashSet<Object>());
        setField(world, WorldClient.class, "field_73036_L", new java.util.HashSet<Object>());
        setField(world, WorldClient.class, "field_73038_N", new java.util.HashSet<Object>());
        mirrorEntity(serverPlayer, player);
        mirrorEntity(serverWorld, world);
        setField(player, net.minecraft.entity.Entity.class, "field_70170_p", world);
        // Unsafe allocation skips EntityPlayer's constructor, so the client-side player
        // facade has no PlayerCapabilities object unless we seed it explicitly.  Client
        // handlers are allowed to read this field directly (for example, creative/infinite
        // ammo checks); keep the facade contract identical to the server-side UmbPlayer.
        net.minecraft.entity.player.PlayerCapabilities capabilities =
                new net.minecraft.entity.player.PlayerCapabilities();
        Object serverCapabilities = readField(serverPlayer,
                net.minecraft.entity.player.EntityPlayer.class, "field_71075_bZ");
        if (serverCapabilities != null) {
            copy(serverCapabilities, capabilities,
                    net.minecraft.entity.player.PlayerCapabilities.class, "field_75098_d");
            copy(serverCapabilities, capabilities,
                    net.minecraft.entity.player.PlayerCapabilities.class, "field_75101_c");
            copy(serverCapabilities, capabilities,
                    net.minecraft.entity.player.PlayerCapabilities.class, "field_75102_a");
            copy(serverCapabilities, capabilities,
                    net.minecraft.entity.player.PlayerCapabilities.class, "field_75100_b");
        }
        setField(player, net.minecraft.entity.player.EntityPlayer.class,
                "field_71075_bZ", capabilities);
        Object inventory = readField(serverPlayer, net.minecraft.entity.player.EntityPlayer.class,
                "field_71071_by");
        if (inventory == null) {
            inventory = new net.minecraft.entity.player.InventoryPlayer(player);
        }
        setField(player, net.minecraft.entity.player.EntityPlayer.class,
                "field_71071_by", inventory);
        Object properties = readField(serverPlayer, net.minecraft.entity.Entity.class,
                "extendedProperties");
        if (properties instanceof Map<?, ?>) {
            setField(player, net.minecraft.entity.Entity.class, "extendedProperties",
                    new java.util.HashMap<Object, Object>((Map<?, ?>) properties));
        } else {
            setField(player, net.minecraft.entity.Entity.class, "extendedProperties",
                    new java.util.HashMap<Object, Object>());
        }
        Object capturedDrops = readField(serverPlayer, net.minecraft.entity.Entity.class,
                "capturedDrops");
        setField(player, net.minecraft.entity.Entity.class, "capturedDrops",
                capturedDrops instanceof List<?> ? new ArrayList<Object>((List<?>) capturedDrops)
                        : new ArrayList<Object>());
        Object riding = readField(serverPlayer, net.minecraft.entity.Entity.class, "field_70154_o");
        setField(player, net.minecraft.entity.Entity.class, "field_70154_o", riding);
        copy(serverPlayer, player, net.minecraft.entity.player.EntityPlayer.class, "field_146106_i");
        // Share the authoritative DataWatcher: client GUIs and HUDs read health/air/absorption
        // through it (GuiContainer.updateScreen -> isEntityAlive -> getHealth NPE'd without it).
        copy(serverPlayer, player, net.minecraft.entity.Entity.class, "field_70180_af");
        // Renderers may ask the facade player for a profile while drawing rider/seat overlays.
        // A synthetic server player can lack one; use a stable capture-only identity so that
        // this optional overlay cannot abort an otherwise valid model capture.
        if (readField(player, net.minecraft.entity.player.EntityPlayer.class, "field_146106_i") == null) {
            setField(player, net.minecraft.entity.player.EntityPlayer.class, "field_146106_i",
                    new com.mojang.authlib.GameProfile(new java.util.UUID(0L, 1L), "UMB-Capture"));
        }
        FacadeNetHandler netHandler = FacadeNetHandler.create(minecraft, world, host,
                serverPlayer);
        setField(player, EntityClientPlayerMP.class, "field_71174_a", netHandler);
        setField(minecraft, Minecraft.class, "field_71449_j", facadeSession(player));
        setField(minecraft, Minecraft.class, "field_71456_v",
                new FacadeGuiIngame(minecraft, host));
        // Born-bound: concurrent legacy installs on other threads can publish this
        // facade as the static singleton before its first prepare() runs; vanilla
        // singleton reads (RenderItem, wrapper bind helpers) must never observe null
        // services. The shared services are immutable with respect to the facade,
        // so assigning them here is always safe.
        bindSharedClientServices(minecraft);
        EntityPlayerMP facadePlayer = serverPlayer instanceof EntityPlayerMP
                ? (EntityPlayerMP) serverPlayer : null;
        EntityPlayerMP previousFacadePlayer = LegacyNetworkLoopback.bindFacadePlayer(facadePlayer);
        return new Binding(minecraft, player, world, settings, installClientProxies(),
                previousFacadePlayer, facadePlayer != null);
    }

    /**
     * Render-thread fallback for a legacy tile whose 26.2 BlockEntity twin has no handle.  The
     * client facade refresh copies the authoritative legacy tile before this method can be used,
     * so this returns a client-side tile instance with the current synced state and never loads a
     * host chunk.  Null is an honest miss when no facade tick has populated that position yet.
     */
    public static TileEntity clientLegacyTileAt(int x, int y, int z) {
        UmbClientWorld world = LAST_CLIENT_WORLD;
        return world == null ? null : world.func_147438_o(x, y, z);
    }

    /**
     * Unsafe allocation skips GameSettings' constructor, but client handlers legitimately read
     * its vanilla KeyBinding fields. Seed the whole declared surface once, with the canonical
     * 1.7.10 defaults where known, so a missing client constructor cannot become a mod-specific
     * null dereference. The objects are reused because a facade is rebuilt for each tick.
     */
    private static void seedGameSettings(GameSettings settings) {
        List<KeyBinding> bindings = new ArrayList<KeyBinding>();
        try {
            for (Field field : GameSettings.class.getDeclaredFields()) {
                if (field.getType() != KeyBinding.class) {
                    continue;
                }
                field.setAccessible(true);
                KeyBinding binding = (KeyBinding) field.get(settings);
                if (binding == null) {
                    synchronized (FACADE_KEY_BINDINGS) {
                        binding = FACADE_KEY_BINDINGS.get(field.getName());
                        if (binding == null) {
                            binding = new KeyBinding("key." + field.getName(),
                                    vanillaKeyCode(field.getName()),
                                    "key.categories.gameplay");
                            FACADE_KEY_BINDINGS.put(field.getName(), binding);
                        }
                    }
                    field.set(settings, binding);
                }
                bindings.add(binding);
            }
            KeyBinding[] all = bindings.toArray(new KeyBinding[bindings.size()]);
            setField(settings, GameSettings.class, "field_151456_ac", all);
            setField(settings, GameSettings.class, "field_74324_K", all);
            // GameSettings is allocated without its constructor.  The vanilla resource-pack
            // repository iterates its selected-pack list during construction; leave every
            // list-valued settings field as a real empty list so client registration cannot
            // fail before the emulated resource manager is mounted.
            for (Field field : GameSettings.class.getDeclaredFields()) {
                if (!List.class.isAssignableFrom(field.getType())) continue;
                field.setAccessible(true);
                if (field.get(settings) == null) {
                    UmbUnsafe.setField(settings, field, new ArrayList<Object>());
                }
            }
        } catch (Throwable t) {
            if (LegacyInputDiag.oncePer("client-game-settings-seed", 60_000_000_000L)) {
                LegacyInputDiag.log("client game settings seed failed cause="
                        + t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
            }
        }
    }

    private static String legacyPlayerName(Object value) {
        if (!(value instanceof net.minecraft.entity.player.EntityPlayer)) return "";
        try {
            String name = ((net.minecraft.entity.player.EntityPlayer) value).func_70005_c_();
            return name == null ? "" : name;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int vanillaKeyCode(String fieldName) {
        if ("field_74351_w".equals(fieldName)) return 17;
        if ("field_74370_x".equals(fieldName)) return 30;
        if ("field_74368_y".equals(fieldName)) return 31;
        if ("field_74366_z".equals(fieldName)) return 32;
        if ("field_74314_A".equals(fieldName)) return 57;
        if ("field_74311_E".equals(fieldName)) return 42;
        if ("field_151445_Q".equals(fieldName)) return 18;
        if ("field_74313_G".equals(fieldName)) return -99;
        if ("field_74316_C".equals(fieldName)) return 16;
        if ("field_74312_F".equals(fieldName)) return -100;
        if ("field_74322_I".equals(fieldName)) return -98;
        if ("field_151444_V".equals(fieldName)) return 29;
        if ("field_74310_D".equals(fieldName)) return 20;
        if ("field_74321_H".equals(fieldName)) return 15;
        if ("field_74323_J".equals(fieldName)) return 33;
        return -1;
    }

    /** Mirrors a mounted legacy vehicle into the client-player facade. */
    public static void setRiding(EntityClientPlayerMP player, Object vehicle) {
        setField(player, net.minecraft.entity.Entity.class, "field_70154_o", vehicle);
    }

    /**
     * Rebinds client-handler object graphs to the facade for the current bounded tick.
     *
     * <p>Client subscribers are discovered lazily and therefore may have been constructed with
     * an earlier facade.  Their nested tick handlers commonly retain a protected Minecraft
     * reference, so updating only Minecraft.thePlayer is insufficient after a mount or player
     * change.  This generic graph walk changes only non-static Minecraft-typed fields and keeps
     * a reversible scope; it never names a mod or a handler class.</p>
     */
    public static MinecraftReferenceScope rebindMinecraftReferences(Iterable<?> roots,
            Minecraft minecraft) {
        List<ProxyValue> changed = new ArrayList<ProxyValue>();
        IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        if (roots != null && minecraft != null) {
            for (Object root : roots) {
                rebindMinecraftGraph(root, minecraft, seen, changed, 0);
            }
        }
        return new MinecraftReferenceScope(changed);
    }

    /**
     * Reasserts the headless display contract immediately before a listener runs. Some legacy
     * client code obtains Minecraft through its static singleton rather than through the handler
     * object graph, and a fresh unsafe facade is created for every bounded tick.
     */
    public static void ensureDisplayDimensions(Minecraft minecraft) {
        if (minecraft == null) {
            return;
        }
        setField(minecraft, Minecraft.class, "field_71443_c", DEFAULT_DISPLAY_WIDTH);
        setField(minecraft, Minecraft.class, "field_71440_d", DEFAULT_DISPLAY_HEIGHT);
        // Keep the singleton and the binding identical even if a previous listener replaced it.
        set(Minecraft.class, "field_71432_P", null, minecraft);
    }

    /**
     * Synthetic-display contract, same family as the 854x480 dimensions: with no native GL
     * the maximum-texture-size probe ({@code Minecraft.func_71369_N}) cannot measure anything
     * (its GL probe calls are emulated away, so it answers -1), and the item/terrain atlas
     * stitch that GUI item rendering triggers needs a positive budget. 8192 is what the probe
     * returns on virtually every real display of the last fifteen years; write once, only
     * when non-positive. Safe to call from any capture path.
     */
    public static void seedMaxTextureSize() {
        try {
            Field sizeField = field(Minecraft.class, "max_texture_size");
            if (sizeField.getInt(null) <= 0) {
                sizeField.setInt(null, 8192);
                if (LegacyInputDiag.oncePer("gui-max-texture-size", 0)) {
                    LegacyInputDiag.log("gui max texture size seeded=8192");
                }
            }
        } catch (Throwable seedFailed) {
            if (LegacyInputDiag.oncePer("gui-max-texture-size-failed", 60_000_000_000L)) {
                LegacyInputDiag.log("gui max texture size seed failed cause="
                        + seedFailed.getClass().getName());
            }
        }
    }

    /**
     * A ReportedException's own stack ends at the wrapping catch site; the throwing
     * frames live in the CrashReport text. Log the head of it (bounded, single line) so
     * the next live stitch failure names the phase that actually threw.
     */
    private static void logAtlasCrashReport(String kind, Throwable failure) {
        try {
            if (!(failure instanceof net.minecraft.util.ReportedException)) return;
            net.minecraft.crash.CrashReport report =
                    ((net.minecraft.util.ReportedException) failure).func_71575_a();
            if (report == null) return;
            String text = report.func_71502_e();
            if (text == null) return;
            text = text.replace('\r', ' ').replace('\n', '|');
            if (text.length() > 2000) text = text.substring(0, 2000) + "...[truncated]";
            LegacyInputDiag.log("client atlas crash kind=" + kind + " report=" + text);
        } catch (Throwable ignored) {
            // Diagnostics must never break the stitch path.
        }
    }

    /**
     * Registers the real 1.7.10 item + block atlases in the shared facade TextureManager,
     * stitched native-free exactly the way the vanilla client bootstraps them
     * ({@code new TextureMap(1, "textures/items", true)} and
     * {@code new TextureMap(0, "textures/blocks", true)} loaded through
     * and {@code Minecraft.func_71357_a}). Vanilla's own stitch driver
     * ({@code TextureMap.loadTextureAtlas} -> private {@code func_110573_f}) walks the
     * block/item registries (items self-select their atlas by sprite number) and fires the
     * Forge stitch events, so mod icons register through the exact generic mechanism as
     * vanilla's; nothing here names a mod, block, or item.
     *
     * <p>Success is detected by state ({@code getTexture} returning the TextureMap), never
     * by a flag, so a poisoned entry (vanilla puts its missing texture on load failure) is
     * overwritten by the next attempt. Attempts back off (30 s) because a full stitch
     * iterates every registered block/item and reads its PNG: a persistently failing
     * stitch must not run every tick.</p>
     */
    private static void ensureItemBlockAtlases(TextureManager textures,
            IResourceManager resources) {
        if (textures == null || resources == null) return;
        try {
            if (textures.func_110581_b(TextureMap.field_110576_c) instanceof TextureMap
                    && textures.func_110581_b(TextureMap.field_110575_b)
                            instanceof TextureMap) {
                return;
            }
        } catch (Throwable ignored) {
            // Fall through to the stitch attempt below.
        }
        long now = System.nanoTime();
        if (ATLAS_NEXT_ATTEMPT_NANOS != 0
                && now - ATLAS_NEXT_ATTEMPT_NANOS < 30_000_000_000L) {
            return;
        }
        ATLAS_NEXT_ATTEMPT_NANOS = now;
        try {
            seedMaxTextureSize();
            boolean items = false;
            boolean blocks = false;
            try {
                items = textures.func_130088_a(TextureMap.field_110576_c,
                        new TextureMap(1, "textures/items", true));
            } catch (Throwable atlasFailed) {
                if (LegacyInputDiag.oncePer("client-atlas-items", 60_000_000_000L)) {
                    LegacyInputDiag.log("client items atlas stitch failed cause="
                            + atlasFailed.getClass().getName() + ":"
                            + String.valueOf(atlasFailed.getMessage())
                            + " stack=" + shortStack(atlasFailed));
                            logAtlasCrashReport("items", atlasFailed);
                }
            }
            try {
                blocks = textures.func_130088_a(TextureMap.field_110575_b,
                        new TextureMap(0, "textures/blocks", true));
            } catch (Throwable atlasFailed) {
                if (LegacyInputDiag.oncePer("client-atlas-blocks", 60_000_000_000L)) {
                    LegacyInputDiag.log("client blocks atlas stitch failed cause="
                            + atlasFailed.getClass().getName() + ":"
                            + String.valueOf(atlasFailed.getMessage())
                            + " stack=" + shortStack(atlasFailed));
                            logAtlasCrashReport("blocks", atlasFailed);
                }
            }
            if (items) {
                Object itemsMap = null;
                try { itemsMap = textures.func_110581_b(TextureMap.field_110576_c); }
                catch (Throwable ignored) { }
                if (itemsMap instanceof net.minecraft.client.renderer.texture.TextureMap) {
                    exportAtlasPixels(
                            (net.minecraft.client.renderer.texture.TextureMap) itemsMap, "items");
                }
            }
            if (blocks) {
                Object blocksMap = null;
                try { blocksMap = textures.func_110581_b(TextureMap.field_110575_b); }
                catch (Throwable ignored) { }
                if (blocksMap instanceof net.minecraft.client.renderer.texture.TextureMap) {
                    exportAtlasPixels(
                            (net.minecraft.client.renderer.texture.TextureMap) blocksMap, "blocks");
                }
            }
            if ((items || blocks) && LegacyInputDiag.oncePer("client-atlas-stitched", 0)) {
                LegacyInputDiag.log("client item/block atlases stitched items=" + items
                        + " blocks=" + blocks);
            }
        } catch (Throwable failure) {
            if (LegacyInputDiag.oncePer("client-atlas-ensure", 60_000_000_000L)) {
                LegacyInputDiag.log("client atlas ensure failed cause="
                        + failure.getClass().getName() + ":"
                        + String.valueOf(failure.getMessage())
                        + " stack=" + shortStack(failure));
            }
        }
    }

    /**
     * The complete 1.7.10 vanilla GUI art set the mesh replays reference. Static pixels,
     * enumerated once from the vanilla client jar; each era module carries its own list.
     */
    private static final String[] VANILLA_GUI_ART = new String[] {
            "achievement/achievement_background.png",
            "achievement/achievement_icons.png",
            "book.png",
            "container/anvil.png",
            "container/beacon.png",
            "container/brewing_stand.png",
            "container/crafting_table.png",
            "container/creative_inventory/tab_inventory.png",
            "container/creative_inventory/tab_item_search.png",
            "container/creative_inventory/tab_items.png",
            "container/creative_inventory/tabs.png",
            "container/dispenser.png",
            "container/enchanting_table.png",
            "container/furnace.png",
            "container/generic_54.png",
            "container/hopper.png",
            "container/horse.png",
            "container/inventory.png",
            "container/stats_icons.png",
            "container/villager.png",
            "demo_background.png",
            "icons.png",
            "options_background.png",
            "resource_packs.png",
            "stream_indicator.png",
            "title/background/panorama_0.png",
            "title/background/panorama_1.png",
            "title/background/panorama_2.png",
            "title/background/panorama_3.png",
            "title/background/panorama_4.png",
            "title/background/panorama_5.png",
            "title/minecraft.png",
            "title/mojang.png",
            "widgets.png",
    };

    private static volatile boolean VANILLA_GUI_EXPORTED;

    /**
     * Copies the vanilla GUI art above into the shared game dir, where the host serves
     * it under a private era namespace (26.2 no longer ships these files). Runs once per
     * session; files that already exist are trusted (static pixels). Best effort throughout.
     */
    private static void exportVanillaGuiArt() {
        if (VANILLA_GUI_EXPORTED) return;
        String gameDir = null;
        try { gameDir = System.getProperty("umb.legacy.gameDir"); }
        catch (Throwable ignored) { }
        ClassLoader loader = null;
        try { loader = Minecraft.class.getClassLoader(); }
        catch (Throwable ignored) { }
        if (gameDir == null || gameDir.isEmpty() || loader == null) {
            if (LegacyInputDiag.oncePer("client-vanilla-export-nogamedir", 0)) {
                LegacyInputDiag.log("client vanilla gui export disabled: no gameDir/loader");
            }
            return;
        }
        VANILLA_GUI_EXPORTED = true;
        if (LegacyInputDiag.oncePer("client-vanilla-export-start", 0)) {
            LegacyInputDiag.log("client vanilla 1710 gui art export starting");
        }
        java.util.zip.ZipFile vanillaJar = openVanillaClientJar();
        int copied = 0;
        int copiedJar = 0;
        try {
            for (String name : VANILLA_GUI_ART) {
                try {
                    java.io.File dst =
                            new java.io.File(gameDir, "umbvanilla1710/textures/gui/" + name);
                    if (dst.isFile() && dst.length() > 0) continue;
                    java.io.InputStream in = null;
                    boolean fromJar = false;
                    if (vanillaJar != null) {
                        try {
                            java.util.zip.ZipEntry entry = vanillaJar.getEntry(
                                    "assets/minecraft/textures/gui/" + name);
                            if (entry != null) in = vanillaJar.getInputStream(entry);
                            fromJar = in != null;
                        } catch (Throwable ignored) { }
                    }
                    if (in == null) {
                        try {
                            in = loader.getResourceAsStream(
                                    "assets/minecraft/textures/gui/" + name);
                        } catch (Throwable ignored) { }
                    }
                    if (in == null) {
                        if (LegacyInputDiag.oncePer("client-vanilla-missing:" + name, 0)) {
                            LegacyInputDiag.log("client vanilla gui missing file=" + name);
                        }
                        continue;
                    }
                    try {
                        java.io.File parent = dst.getParentFile();
                        if (parent != null) parent.mkdirs();
                        java.io.OutputStream out = new java.io.FileOutputStream(dst);
                        try {
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
                        } finally { out.close(); }
                        copied++;
                        if (fromJar) copiedJar++;
                    } finally { in.close(); }
                } catch (Throwable oneFailed) {
                    if (LegacyInputDiag.oncePer("client-vanilla-export:" + name,
                            60_000_000_000L)) {
                        LegacyInputDiag.log("client vanilla gui export failed file=" + name
                                + " cause=" + oneFailed.getClass().getName());
                    }
                }
            }
        } finally {
            if (vanillaJar != null) {
                try { vanillaJar.close(); } catch (Throwable ignored) { }
            }
        }
        if (LegacyInputDiag.oncePer("client-vanilla-exported", 0)) {
            LegacyInputDiag.log("client vanilla 1710 gui art exported files=" + copied
                    + " fromJar=" + copiedJar);
        }
    }

    /**
     * Locates the vanilla 1.7.10 client jar (same candidate layout the resource pack
     * uses): its GUI art is authoritative, while the classloader may serve a newer
     * vanilla's same-named files (or nothing at all). Null when absent.
     */
    private static java.util.zip.ZipFile openVanillaClientJar() {
        try {
            String repo = "";
            try { repo = System.getProperty("umb.repo", ""); } catch (Throwable ignored) { }
            String userDir = "";
            try { userDir = System.getProperty("user.dir", ""); } catch (Throwable ignored) { }
            java.util.ArrayList<java.io.File> candidates =
                    new java.util.ArrayList<java.io.File>();
            if (!repo.isEmpty()) {
                candidates.add(new java.io.File(new java.io.File(repo),
                        "research/jars/1.7.10/client.jar"));
            }
            if (!userDir.isEmpty()) {
                candidates.add(new java.io.File(new java.io.File(userDir),
                        "research/jars/1.7.10/client.jar"));
            }
            candidates.add(new java.io.File("research/jars/1.7.10/client.jar"));
            for (java.io.File candidate : candidates) {
                try {
                    if (candidate.isFile()) return new java.util.zip.ZipFile(candidate);
                } catch (Throwable ignored) { }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /**
     * Exports the just-stitched atlas pixels beside the game so the host can serve them
     * as a dynamic texture (the stitched layout exists only in legacy memory). Rebuilds
     * the image from each uploaded sprite's level-0 frame at its stitched origin;
     * rotated sprites and sprites without frame data are honestly skipped and counted.
     * Best effort: failures only skip the export (oncePer-logged), never the stitch.
     * Each era module exports under its own tag.
     * Public as a test hook (headless composite verification); production callers
     * go through the atlas stitch in ensureItemBlockAtlases.
     */
    public static void exportAtlasPixels(
            net.minecraft.client.renderer.texture.TextureMap map, String kind) {
        int placed = 0;
        int skipped = 0;
        try {
            String gameDir = null;
            try { gameDir = System.getProperty("umb.legacy.gameDir"); }
            catch (Throwable ignored) { }
            if (gameDir == null || gameDir.isEmpty() || map == null) return;
            Object raw = field(net.minecraft.client.renderer.texture.TextureMap.class,
                    "field_94252_e").get(map);
            if (!(raw instanceof java.util.Map)) return;
            java.util.Map<?, ?> sprites = (java.util.Map<?, ?>) raw;
            // Atlas dimensions live on the Stitcher, not the map - and the stitcher
            // the exact padded size from sprite extents so fractional UVs keep sampling
            // the right cells; abort honestly when any sprite hides its geometry.
            int w = 0;
            int h = 0;
            for (Object o : sprites.values()) {
                if (!(o instanceof net.minecraft.client.renderer.texture.TextureAtlasSprite)) {
                    return;
                }
                net.minecraft.client.renderer.texture.TextureAtlasSprite sprite =
                        (net.minecraft.client.renderer.texture.TextureAtlasSprite) o;
                int ox;
                int oy;
                int sw;
                int sh;
                try {
                    ox = sprite.func_130010_a();
                    oy = sprite.func_110967_i();
                    sw = sprite.func_94211_a();
                    sh = sprite.func_94216_b();
                } catch (Throwable ignored) { return; }
                if (sw <= 0 || sh <= 0) return;
                if (ox < 0 || oy < 0 || ox + sw > 8192 || oy + sh > 8192) return;
                if (ox + sw > w) w = ox + sw;
                if (oy + sh > h) h = oy + sh;
            }
            if (w <= 0 || h <= 0) return;
            w = net.minecraft.util.MathHelper.func_151236_b(w);
            h = net.minecraft.util.MathHelper.func_151236_b(h);
            java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h,
                    java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (Object o : sprites.values()) {
                if (!(o instanceof net.minecraft.client.renderer.texture.TextureAtlasSprite)) {
                    skipped++;
                    continue;
                }
                net.minecraft.client.renderer.texture.TextureAtlasSprite sprite =
                        (net.minecraft.client.renderer.texture.TextureAtlasSprite) o;
                int ox;
                int oy;
                int sw;
                int sh;
                try {
                    ox = sprite.func_130010_a();
                    oy = sprite.func_110967_i();
                    sw = sprite.func_94211_a();
                    sh = sprite.func_94216_b();
                } catch (Throwable ignored) { skipped++; continue; }
                Object framesRaw;
                try {
                    framesRaw = field(sprite.getClass(), "field_110976_a").get(sprite);
                } catch (Throwable ignored) { skipped++; continue; }
                if (!(framesRaw instanceof java.util.List)) { skipped++; continue; }
                java.util.List<?> frames = (java.util.List<?>) framesRaw;
                if (frames.isEmpty() || !(frames.get(0) instanceof int[])) {
                    skipped++;
                    continue;
                }
                int[] px = (int[]) frames.get(0);
                if (sw <= 0 || sh <= 0 || px.length != sw * sh) { skipped++; continue; }
                boolean rotated = false;
                try {
                    rotated = field(sprite.getClass(), "field_130222_e")
                            .getBoolean(sprite);
                } catch (Throwable ignored) { }
                if (rotated) { skipped++; continue; }
                if (ox < 0 || oy < 0 || ox + sw > w || oy + sh > h) {
                    skipped++;
                    continue;
                }
                img.setRGB(ox, oy, sw, sh, px, 0, sw);
                placed++;
            }
            java.io.File dst =
                    new java.io.File(gameDir, "umbatlas/1710/" + kind + ".png");
            java.io.File parent = dst.getParentFile();
            if (parent != null) parent.mkdirs();
            javax.imageio.ImageIO.write(img, "png", dst);
            if (LegacyInputDiag.oncePer("client-atlas-exported:" + kind, 0)) {
                LegacyInputDiag.log("client atlas exported kind=" + kind + " placed="
                        + placed + " skipped=" + skipped + " size=" + w + "x" + h);
            }
        } catch (Throwable failure) {
            if (LegacyInputDiag.oncePer("client-atlas-export:" + kind, 60_000_000_000L)) {
                LegacyInputDiag.log("client atlas export failed kind=" + kind + " cause="
                        + failure.getClass().getName() + ":"
                        + String.valueOf(failure.getMessage()));
            }
        }

    }
    /**
     * The atlas stitch driver ({@code TextureMap.func_110573_f}, at the head of every atlas
     * load) reads {@code field_71438_f} (renderGlobal) off the singleton to register the
     * destroy-stage sprites, so every facade carries a blank one. A blank instance is
     * sufficient: registration only writes its own icon array. Never null after this.
     */
    private static void seedClientRenderGlobal(Minecraft minecraft) {
        if (minecraft == null) return;
        try {
            if (readField(minecraft, Minecraft.class, "field_71438_f") == null) {
                UmbUnsafe.setField(minecraft, field(Minecraft.class, "field_71438_f"),
                        UmbUnsafe.allocate(RenderGlobal.class));
                if (LegacyInputDiag.oncePer("client-render-global-seed", 0)) {
                    LegacyInputDiag.log("client facade RenderGlobal seeded (blank destroy-stage host)");
                }
            }
        } catch (Throwable failure) {
            if (LegacyInputDiag.oncePer("client-render-global-seed-failed",
                    60_000_000_000L)) {
                LegacyInputDiag.log("client render global seed failed cause="
                        + failure.getClass().getName());
            }
        }
    }

    /**
     * Seeds the real 1.7.10 text/texture services into a synthetic Minecraft facade.
     *
     * <p>Legacy GUI instances retain the Minecraft object from their construction tick, so this
     * is called after every facade install (not only during client registration).  The service
     * objects themselves are shared: FontRenderer is immutable with respect to the facade and
     * TextureManager is backed by the native-free GL-EMU path when it binds or uploads a texture.
     * No client window or native GL context is created here.</p>
     */
    public static synchronized void bindClientRenderServices(Minecraft minecraft,
            GameSettings settings, IResourceManager resources) {
        if (minecraft == null || settings == null) return;
        try {
            if (resources != null && resources != CLIENT_RESOURCE_MANAGER) {
                CLIENT_RESOURCE_MANAGER = resources;
                CLIENT_TEXTURE_MANAGER = new TextureManager(resources);
                CLIENT_FONT_RENDERER = null;
                CLIENT_FONT_RESOURCE_MANAGER = null;
            } else if (CLIENT_TEXTURE_MANAGER == null) {
                CLIENT_TEXTURE_MANAGER = new TextureManager(resources);
            }
            if (CLIENT_FONT_RENDERER == null) {
                CLIENT_FONT_RENDERER = new FontRenderer(settings, VANILLA_FONT,
                        CLIENT_TEXTURE_MANAGER, false);
            }
            if (resources != null && resources != CLIENT_FONT_RESOURCE_MANAGER) {
                // This reload reads ascii.png and glyph_sizes.bin through the mounted vanilla
                // pack. It does not call a native uploader; texture binds are GL-EMU calls.
                CLIENT_FONT_RENDERER.func_110549_a(resources);
                CLIENT_FONT_RESOURCE_MANAGER = resources;
            }
            setField(minecraft, Minecraft.class, "field_71446_o", CLIENT_TEXTURE_MANAGER);
            setField(minecraft, Minecraft.class, "field_71466_p", CLIENT_FONT_RENDERER);
            setField(minecraft, Minecraft.class, "field_71464_q", CLIENT_FONT_RENDERER);
            ensureItemBlockAtlases(CLIENT_TEXTURE_MANAGER, resources);
            exportVanillaGuiArt();
            seedClientI18n(resources);
            if (LegacyInputDiag.oncePer("client-render-services-ready", 0)) {
                LegacyInputDiag.log("client render services ready font="
                        + CLIENT_FONT_RENDERER.getClass().getName() + " texture="
                        + CLIENT_TEXTURE_MANAGER.getClass().getName() + " resource="
                        + (CLIENT_RESOURCE_MANAGER == null ? "null"
                                : CLIENT_RESOURCE_MANAGER.getClass().getName()));
            }
        } catch (Throwable failure) {
            if (LegacyInputDiag.oncePer("client-render-services", 60_000_000_000L)) {
                LegacyInputDiag.log("client render services seed failed cause="
                        + failure.getClass().getName() + ":" + String.valueOf(failure.getMessage())
                        + " root=" + rootCause(failure) + " stack=" + shortStack(failure));
            }
        }
    }

    private static String rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getClass().getName() + ":" + String.valueOf(current.getMessage());
    }

    private static String shortStack(Throwable failure) {
        StringBuilder out = new StringBuilder();
        StackTraceElement[] frames = failure.getStackTrace();
        int limit = Math.min(frames.length, 8);
        for (int i = 0; i < limit; i++) {
            if (i != 0) out.append(" <- ");
            out.append(frames[i].toString());
        }
        return out.toString();
    }

    /** Small diagnostic snapshot; it never calls mod code. */
    public static String displayDiagnostic(Minecraft binding) {
        try {
            Minecraft singleton = Minecraft.func_71410_x();
            return "binding=" + displayDiagnosticOne(binding)
                    + " singleton=" + displayDiagnosticOne(singleton)
                    + " same=" + (binding == singleton);
        } catch (Throwable t) {
            return "binding=" + displayDiagnosticOne(binding) + " singleton=unreadable:" + t.getClass().getName();
        }
    }

    /** Captures the generic legacy renderViewEntity contract before this short-lived facade dies. */
    public static LegacyBridge.CameraState cameraState(Minecraft minecraft) {
        if (minecraft == null) return null;
        try {
            Object player = readField(minecraft, Minecraft.class, "field_71439_g");
            Object view = readField(minecraft, Minecraft.class, "field_71451_h");
            if (!(view instanceof net.minecraft.entity.Entity)) view = player;
            if (!(view instanceof net.minecraft.entity.Entity)) return null;
            net.minecraft.entity.Entity entity = (net.minecraft.entity.Entity) view;
            boolean overridden = isOverriddenView(player, view)
                    && Double.isFinite(entity.field_70165_t)
                    && Double.isFinite(entity.field_70163_u)
                    && Double.isFinite(entity.field_70161_v)
                    && Float.isFinite(entity.field_70125_A)
                    && Float.isFinite(entity.field_70177_z);
            return new LegacyBridge.CameraState(overridden, entity.field_70165_t,
                    entity.field_70163_u, entity.field_70161_v, entity.field_70125_A,
                    entity.field_70177_z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** A copied player facade is still the player camera; only a non-player dummy may override it. */
    static boolean isOverriddenView(Object player, Object view) {
        return view != null && view != player
                && !(view instanceof net.minecraft.entity.player.EntityPlayer);
    }

    private static volatile Field THIRD_PERSON_DISTANCE_FIELD;
    private static volatile boolean THIRD_PERSON_DISTANCE_LOOKUP_DONE;

    /**
     * Reads the third-person camera distance legacy client code wrote into the facade's own
     * against the 1.7.10 SRG runtime and against the mod wrapper that writes exactly that field
     * through the Minecraft facade).  The desired value (B) is read rather than the smoothed one
     * ({@code field_78491_C}): the headless facade never runs the renderer, so nothing copies B
     * into C here, while B always holds the mod's latest write.  NaN on any failure - an honest
     * absence the host answers by keeping its own distance.  Never names a mod or handler.
     */
    public static float thirdPersonDistance(Minecraft minecraft) {
        if (minecraft == null) {
            return Float.NaN;
        }
        try {
            Field rendererField = firstFieldOfType(Minecraft.class,
                    net.minecraft.client.renderer.EntityRenderer.class);
            if (rendererField == null) {
                return Float.NaN;
            }
            Object renderer = rendererField.get(minecraft);
            if (renderer == null) {
                return Float.NaN;
            }
            Field distance = thirdPersonDistanceField(renderer.getClass());
            if (distance == null) {
                return Float.NaN;
            }
            Object value = distance.get(renderer);
            if (value instanceof Number) {
                float f = ((Number) value).floatValue();
                return Float.isFinite(f) && f > 0.0F ? f : Float.NaN;
            }
        } catch (Throwable ignored) {
            // Optional client-owned state must never abort the listener dispatch.
        }
        return Float.NaN;
    }

    private static Field thirdPersonDistanceField(Class<?> rendererType) {
        Field cached = THIRD_PERSON_DISTANCE_FIELD;
        if (cached != null && cached.getDeclaringClass().isAssignableFrom(rendererType)) {
            return cached;
        }
        if (THIRD_PERSON_DISTANCE_LOOKUP_DONE && cached != null) {
            return cached;
        }
        for (String name : new String[] {"field_78490_B", "thirdPersonDistance"}) {
            for (Class<?> cursor = rendererType; cursor != null; cursor = cursor.getSuperclass()) {
                try {
                    Field candidate = cursor.getDeclaredField(name);
                    if (candidate.getType() != float.class
                            || Modifier.isStatic(candidate.getModifiers())) {
                        continue;
                    }
                    candidate.setAccessible(true);
                    THIRD_PERSON_DISTANCE_FIELD = candidate;
                    THIRD_PERSON_DISTANCE_LOOKUP_DONE = true;
                    return candidate;
                } catch (NoSuchFieldException ignored) {
                    // Try the superclass, then the MCP fallback name.
                }
            }
        }
        THIRD_PERSON_DISTANCE_LOOKUP_DONE = true;
        return THIRD_PERSON_DISTANCE_FIELD;
    }

    /**
     * In 1.7.10 single-player the integrated server shares the JVM with the client, and mods call
     * MCH_EntityHeli.onUpdate spawns rotor particles through getClient().effectRenderer and was
     * poisoned with an NPE the moment the throttle rose). Our Forge boots as a server, so
     * FMLClientHandler.client stays null. Bind it once to the facade Minecraft, whose
     * effectRenderer and other client services are already seeded; never overwrite a real one.
     */
    private static void bindFmlClientHandler(Minecraft minecraft) {
        try {
            cpw.mods.fml.client.FMLClientHandler handler = cpw.mods.fml.client.FMLClientHandler.instance();
            Field client = field(cpw.mods.fml.client.FMLClientHandler.class, "client");
            if (client.get(handler) == null) {
                client.set(handler, minecraft);
                LegacyInputDiag.log("FMLClientHandler.client bound to the client facade");
            }
        } catch (Throwable t) {
            if (LegacyInputDiag.oncePer("fml-client-bind", 60_000_000_000L)) {
                LegacyInputDiag.log("FMLClientHandler.client bind failed: " + t);
            }
        }
    }

    /**
     * Best-effort early publication of the shared font/texture services onto a fresh
     * facade. Every install() publishes the facade as the static singleton first and binds
     * later, so without this any concurrent install on another thread can observe (or leave
     * behind) an unbound facade, which vanilla singleton reads turn into an NPE pages later.
     * No lock: the statics are volatile and the values immutable; the full loud bind runs
     * right after in every path (prepareClientUniverse or the direct bind calls).
     */
    public static void bindSharedClientServices(Minecraft minecraft) {
        if (minecraft == null) return;
        try {
            TextureManager textures = CLIENT_TEXTURE_MANAGER;
            if (textures != null) setField(minecraft, Minecraft.class, "field_71446_o",
                    textures);
            FontRenderer font = CLIENT_FONT_RENDERER;
            if (font != null) {
                setField(minecraft, Minecraft.class, "field_71466_p", font);
                setField(minecraft, Minecraft.class, "field_71464_q", font);
            }
        } catch (Throwable sharedBindFailed) {
            // Loud on purpose: a facade published with null services is the bug-#29
            // NPE one draw later. The full bind still covers it, but the cause must
            // be named here while it is adjacent.
            if (LegacyInputDiag.oncePer("client-shared-services", 60_000_000_000L)) {
                LegacyInputDiag.log("client shared services seed failed cause="
                        + sharedBindFailed.getClass().getName() + ":"
                        + String.valueOf(sharedBindFailed.getMessage()));
            }
        }
    }

    private static void seedClientEntityRenderer(Minecraft minecraft) {
        if (minecraft == null) return;
        try {
            Field candidate = firstFieldOfType(Minecraft.class,
                    net.minecraft.client.renderer.EntityRenderer.class);
            if (candidate != null) {
                Object renderer = candidate.get(minecraft);
                if (renderer == null) {
                    synchronized (ENTITY_RENDERER_CACHE) {
                        renderer = ENTITY_RENDERER_CACHE.get(candidate);
                        if (renderer == null) {
                            renderer = UmbUnsafe.allocate(candidate.getType());
                            ENTITY_RENDERER_CACHE.put(candidate, renderer);
                            if (LegacyInputDiag.oncePer("client-entity-renderer-seed:" +
                                    candidate.getName(), 0)) {
                                LegacyInputDiag.log("client facade EntityRenderer cached field="
                                        + candidate.getName());
                            }
                        }
                    }
                    UmbUnsafe.setField(minecraft, candidate, renderer);
                }
                // EntityRenderer is cached across the short-lived Minecraft facades, but its
                // constructor normally writes field_78531_r (the owning Minecraft).  Unsafe
                // allocation skipped that constructor, and reusing the renderer without this
                // rebinding leaves HBM's overlay path in EntityRenderer.func_78478_c with a
                // null owner even though Minecraft.field_71417_i is non-null.
                setField(renderer, net.minecraft.client.renderer.EntityRenderer.class,
                        "field_78531_r", minecraft);
                seedRendererServices(renderer, minecraft);
                return;
            }
            if (LegacyInputDiag.oncePer("client-entity-renderer-field-missing",
                    60_000_000_000L)) {
                LegacyInputDiag.log("client facade EntityRenderer field missing");
            }
        } catch (Throwable failure) {
            if (LegacyInputDiag.oncePer("client-entity-renderer-seed-failed",
                    60_000_000_000L)) {
                LegacyInputDiag.log("client facade EntityRenderer seed failed cause="
                        + failure.getClass().getName() + ":" + String.valueOf(failure.getMessage())
                        + " root=" + rootCause(failure) + " stack=" + shortStack(failure));
            }
        }
    }

    /**
     * Seeds the renderer-owned services the vanilla EntityRenderer constructor creates,
     * using the same native-free building blocks as the facade's font/texture services.
     *
     * <p>Client tick handlers touch these every tick through the static singleton
     * {@code field_78516_c} (itemRenderer) is read by the wrappers that null
     * {@code field_78453_b} (itemToRender) and write {@code field_78454_c}
     * (equippedProgress) while riding, and {@code field_147709_v} (theMapItemRenderer)
     * backs the public {@code func_147701_i} accessor. A null instance makes Forge's
     * reflection helper throw NPE, which can abort the rest of the mod's tick logic.
     * The remaining roller/fov/distance members ({@code field_78490_B},
     * {@code field_78495_O}, {@code field_78505_P}, {@code field_78503_V}) are primitives
     * and need no seeding. ItemRenderer's real constructor is native-free (a RenderBlocks
     * plus the mc reference); MapItemRenderer's only stores its TextureManager.</p>
     */
    private static void seedRendererServices(Object renderer, Minecraft minecraft) {
        if (renderer == null || minecraft == null) return;
        try {
            Object itemRenderer = readField(renderer,
                    net.minecraft.client.renderer.EntityRenderer.class, "field_78516_c");
            if (itemRenderer == null) {
                itemRenderer = new ItemRenderer(minecraft);
                UmbUnsafe.setField(renderer,
                        field(net.minecraft.client.renderer.EntityRenderer.class,
                                "field_78516_c"),
                        itemRenderer);
                if (LegacyInputDiag.oncePer("client-item-renderer-seed", 0)) {
                    LegacyInputDiag.log("client facade ItemRenderer seeded");
                }
            } else {
                // The renderer (and its ItemRenderer) is shared across short-lived
                // facades; keep the mc back-reference on the current one.
                setField(itemRenderer, ItemRenderer.class, "field_78455_a", minecraft);
            }
            Object mapRenderer = readField(renderer,
                    net.minecraft.client.renderer.EntityRenderer.class, "field_147709_v");
            if (mapRenderer == null) {
                TextureManager textures = null;
                try { textures = minecraft.func_110434_K(); } catch (Throwable ignored) { }
                UmbUnsafe.setField(renderer,
                        field(net.minecraft.client.renderer.EntityRenderer.class,
                                "field_147709_v"),
                        new MapItemRenderer(textures));
            }
        } catch (Throwable failure) {
            if (LegacyInputDiag.oncePer("client-renderer-services-seed-failed",
                    60_000_000_000L)) {
                LegacyInputDiag.log("client renderer services seed failed cause="
                        + failure.getClass().getName() + ":"
                        + String.valueOf(failure.getMessage()));
            }
        }
    }

    private static String displayDiagnosticOne(Minecraft minecraft) {
        if (minecraft == null) {
            return "null";
        }
        try {
            return System.identityHashCode(minecraft) + ":"
                    + field(Minecraft.class, "field_71443_c").getInt(minecraft) + "x"
                    + field(Minecraft.class, "field_71440_d").getInt(minecraft)
                    + " gui=" + (field(Minecraft.class, "field_71462_r").get(minecraft) == null
                            ? "null" : field(Minecraft.class, "field_71462_r").get(minecraft).getClass().getName());
        } catch (Throwable t) {
            return System.identityHashCode(minecraft) + ":unreadable:" + t.getClass().getName();
        }
    }

    private static void rebindMinecraftGraph(Object value, Minecraft minecraft,
            IdentityHashMap<Object, Boolean> seen, List<ProxyValue> changed, int depth) {
        if (value == null || minecraft == null || depth > 6
                || value instanceof Minecraft || seen.put(value, Boolean.TRUE) != null) {
            return;
        }
        Class<?> type = value.getClass();
        if (type.isPrimitive() || type.isEnum() || type.getName().startsWith("java.")
                || type.getName().startsWith("javax.") || type.getName().startsWith("sun.")
                || type.getName().startsWith("org.lwjgl.")
                || type.getName().startsWith("net.minecraft.")) {
            return;
        }
        if (type.isArray()) {
            if (!type.getComponentType().isPrimitive()) {
                int length = java.lang.reflect.Array.getLength(value);
                int limit = Math.min(length, 256);
                for (int i = 0; i < limit; i++) {
                    rebindMinecraftGraph(java.lang.reflect.Array.get(value, i), minecraft,
                            seen, changed, depth + 1);
                }
            }
            return;
        }
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            Field[] fields;
            try {
                fields = cursor.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (Minecraft.class.isAssignableFrom(field.getType())) {
                        Object previous = field.get(value);
                        if (previous != minecraft) {
                            changed.add(new ProxyValue(value, field, previous));
                            UmbUnsafe.setField(value, field, minecraft);
                        }
                    } else if (!field.getType().isPrimitive()
                            && field.getType() != String.class) {
                        rebindMinecraftGraph(field.get(value), minecraft, seen, changed, depth + 1);
                    }
                } catch (Throwable ignored) {
                    // Optional client-owned state must never abort the listener dispatch.
                }
            }
        }
    }

    /** Seeds the client entity's authoritative world reference without invoking a client constructor. */
    public static void setWorld(Object entity, Object world) {
        setField(entity, net.minecraft.entity.Entity.class, "field_70170_p", world);
    }

    /**
     * Rebinds the bounded riding/passenger graph to the synthetic client world.  The graph is
     * followed through vanilla's SRG riding fields rather than any mod class or field, so seats,
     * vehicles, and other mounted entities receive the same generic treatment.
     */
    public static EntityWorldScope rebindEntityWorlds(Object root, WorldClient clientWorld) {
        return rebindEntityWorlds(root, clientWorld, null);
    }

    /**
     * Rebinds a mounted graph for client listeners while retaining the server-side owner for
     * restoration. The explicit owner matters when an earlier listener leaked the client world:
     * the captured value is then no longer trustworthy, but the caller still knows the universe
     * that owns the entity.
     */
    public static EntityWorldScope rebindEntityWorlds(Object root, WorldClient clientWorld,
            Object authoritativeWorld) {
        return rebindEntityWorlds(root, clientWorld, authoritativeWorld, null, null);
    }

    /**
     * Rebinds both world ownership and the bounded passenger identity used by legacy entity
     * APIs. The client facade must be the passenger object while client handlers run: many
     * legacy entities resolve seat, weapon, and pilot state by object identity rather than by
     * UUID. The authoritative link is restored before the server tick resumes.
     */
    public static EntityWorldScope rebindEntityWorlds(Object root, WorldClient clientWorld,
            Object authoritativeWorld, Object authoritativePlayer, Object clientPlayer) {
        List<EntityWorld> changed = new ArrayList<EntityWorld>();
        List<PassengerLink> passengerLinks = new ArrayList<PassengerLink>();
        Map<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();
        collectEntityWorlds(root, clientWorld, seen, changed, passengerLinks, 0,
                authoritativePlayer, clientPlayer);
        EntityWorldScope scope =
                new EntityWorldScope(changed, root, authoritativeWorld, clientWorld, passengerLinks);
        // Mod helper objects owned by a rebound entity (weapon sets, turrets, sub-parts) often
        // cache the World at construction. Swap World fields that still point at the entity's
        // server world, so client-side code takes its native isRemote branch instead of acting
        // on the server (live: MCHeli weapons spawned real bullets from the client handler).
        Map<Object, Boolean> swept = new IdentityHashMap<Object, Boolean>();
        for (EntityWorld entry : changed) {
            if (entry.world != null && entry.world != clientWorld) {
                sweepWorldFields(entry.entity, entry.world, clientWorld, swept, scope.worldFieldSwaps,
                        scope.primitiveSnapshots, 0);
            }
        }
        rehydrateClientServices(changed);
        return scope;
    }

    /**
     * Forge normally constructs client-only entity helpers while the client proxy is active.
     * This universe constructs authoritative entities under the server proxy first, so a
     * client-side facade must fill only still-null final helper fields after the entity has been
     * rebound to a remote world. Matching is structural: a one-argument client-proxy factory
     * whose return type matches the field. No mod class or field name is part of the contract.
     */
    private static void rehydrateClientServices(List<EntityWorld> entities) {
        if (entities == null || entities.isEmpty() || CLIENT_PROXY_REGISTRATIONS == null) return;
        int scanned = 0;
        int filled = 0;
        for (EntityWorld entry : entities) {
            Object entity = entry.entity;
            if (entity == null) continue;
            for (Class<?> cursor = entity.getClass(); cursor != null && cursor != Object.class;
                    cursor = cursor.getSuperclass()) {
                String className = cursor.getName();
                if (className.startsWith("net.minecraft.") || className.startsWith("java.")
                        || className.startsWith("dev.umb.")) continue;
                for (Field field : cursor.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || !Modifier.isFinal(field.getModifiers())
                            || field.getType().isPrimitive() || field.getType().isArray()) continue;
                    try {
                        field.setAccessible(true);
                        if (field.get(entity) != null) continue;
                    } catch (Throwable ignored) {
                        continue;
                    }
                    scanned++;
                    if (fillClientService(entity, field)) filled++;
                }
            }
        }
        if (filled != 0 && LegacyInputDiag.oncePer("client-service-rehydrate", 5_000_000_000L)) {
            LegacyInputDiag.log("client service rehydrate scanned=" + scanned + " filled=" + filled);
        }
    }

    private static boolean fillClientService(Object entity, Field field) {
        for (ClientProxyRegistration registration : CLIENT_PROXY_REGISTRATIONS) {
            Object proxy = registration.clientProxy;
            if (proxy == null) continue;
            for (Class<?> cursor = proxy.getClass(); cursor != null && cursor != Object.class;
                    cursor = cursor.getSuperclass()) {
                for (java.lang.reflect.Method method : cursor.getDeclaredMethods()) {
                    if (Modifier.isStatic(method.getModifiers()) || method.isBridge()
                            || method.isSynthetic() || method.getReturnType() == Void.TYPE) continue;
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length != 1 || !parameters[0].isAssignableFrom(entity.getClass())
                            || !field.getType().isAssignableFrom(method.getReturnType())) continue;
                    try {
                        method.setAccessible(true);
                        Object value = method.invoke(proxy, entity);
                        if (value == null) continue;
                        UmbUnsafe.setField(entity, field, value);
                        return true;
                    } catch (Throwable ignored) {
                        // An optional client helper must never abort the generic dispatch.
                    }
                }
            }
        }
        return false;
    }

    private static final java.util.concurrent.ConcurrentMap<Class<?>, Field[]> SWEEP_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Field[]>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, Field[]> PRIMITIVE_SWEEP_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Field[]>();

    private static Field[] sweepFields(Class<?> type) {
        Field[] cached = SWEEP_FIELDS.get(type);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<Field>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            String n = c.getName();
            // Vanilla classes are covered by field_70170_p; only mod-owned state is swept.
            if (n.startsWith("net.minecraft.") || n.startsWith("java.") || n.startsWith("dev.umb.")) continue;
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t.isPrimitive() || t == String.class) continue;
                try { f.setAccessible(true); } catch (Throwable ignored) { continue; }
                out.add(f);
            }
        }
        Field[] arr = out.toArray(new Field[0]);
        Field[] prev = SWEEP_FIELDS.putIfAbsent(type, arr);
        return prev == null ? arr : prev;
    }

    private static Field[] primitiveSweepFields(Class<?> type) {
        Field[] cached = PRIMITIVE_SWEEP_FIELDS.get(type);
        if (cached != null) return cached;
        List<Field> out = new ArrayList<Field>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            String className = c.getName();
            if (className.startsWith("net.minecraft.") || className.startsWith("java.")
                    || className.startsWith("dev.umb.")) {
                continue;
            }
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.getType().isPrimitive()) {
                    continue;
                }
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                // Client presentation/input state is allowed to persist. Gameplay counters in
                // helper objects (ammo, heat, cooldowns, etc.) are restored before the deferred
                // server packet applies the action authoritatively.
                if (name.indexOf("rot") >= 0 || name.indexOf("yaw") >= 0
                        || name.indexOf("pitch") >= 0 || name.indexOf("roll") >= 0
                        || name.indexOf("render") >= 0 || name.indexOf("input") >= 0
                        || name.indexOf("key") >= 0 || name.indexOf("mouse") >= 0
                        || name.indexOf("camera") >= 0 || name.indexOf("sound") >= 0
                        || name.indexOf("effect") >= 0 || name.indexOf("particle") >= 0) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    out.add(field);
                } catch (Throwable ignored) {
                    // An inaccessible optional field is not part of the journal.
                }
            }
        }
        Field[] array = out.toArray(new Field[out.size()]);
        Field[] previous = PRIMITIVE_SWEEP_FIELDS.putIfAbsent(type, array);
        return previous == null ? array : previous;
    }
    // The object walk journals nested mod-owned gameplay helpers for deferred server delivery.
    private static void sweepWorldFields(Object owner, Object serverWorld, Object clientWorld,
            Map<Object, Boolean> seen, List<Object[]> swaps, List<Object[]> primitiveSnapshots,
            int depth) {
        if (owner == null || depth > 4 || seen.put(owner, Boolean.TRUE) != null) return;
        Class<?> type = owner.getClass();
        if (type.isArray()) {
            if (type.getComponentType().isPrimitive()) return;
            int len = Math.min(java.lang.reflect.Array.getLength(owner), 64);
            for (int i = 0; i < len; i++) {
                sweepWorldFields(java.lang.reflect.Array.get(owner, i), serverWorld, clientWorld,
                        seen, swaps, primitiveSnapshots, depth + 1);
            }
            return;
        }
        if (depth > 0 && owner instanceof net.minecraft.entity.Entity) return;
        if (depth > 0) {
            for (Field field : primitiveSweepFields(owner.getClass())) {
                try {
                    primitiveSnapshots.add(new Object[] {owner, field, field.get(owner)});
                } catch (Throwable ignored) {
                    // Optional client-state journaling must never abort dispatch.
                }
            }
        }
        for (Field f : sweepFields(type)) {
            try {
                Object v = f.get(owner);
                if (v == null) continue;
                if (v == serverWorld && f.getType().isAssignableFrom(clientWorld.getClass())) {
                    swaps.add(new Object[] {owner, f, v});
                    UmbUnsafe.setField(owner, f, clientWorld);
                } else if (!(v instanceof net.minecraft.world.World)
                        && !(v instanceof java.util.Map) && !(v instanceof Iterable)) {
                    sweepWorldFields(v, serverWorld, clientWorld, seen, swaps, primitiveSnapshots,
                            depth + 1);
                }
            } catch (Throwable ignored) {
                // Optional mod state; never abort the client dispatch.
            }
        }
    }

    private static void collectEntityWorlds(Object entity, WorldClient clientWorld,
            Map<Object, Boolean> seen, List<EntityWorld> changed, List<PassengerLink> passengerLinks,
            int depth, Object authoritativePlayer, Object clientPlayer) {
        if (!(entity instanceof net.minecraft.entity.Entity) || depth > 16
                || seen.put(entity, Boolean.TRUE) != null) {
            return;
        }
        Object originalWorld = readField(entity, net.minecraft.entity.Entity.class, "field_70170_p");
        if (originalWorld != clientWorld) {
            changed.add(new EntityWorld(entity, originalWorld));
            setWorld(entity, clientWorld);
        }
        Object passenger = readField(entity, net.minecraft.entity.Entity.class, "field_70153_n");
        if (passenger != null && passenger == authoritativePlayer && clientPlayer != null
                && clientPlayer != authoritativePlayer) {
            passengerLinks.add(new PassengerLink(entity, passenger));
            setField(entity, net.minecraft.entity.Entity.class, "field_70153_n", clientPlayer);
            if (LegacyInputDiag.oncePer("client-passenger-rebind:" +
                    entity.getClass().getName(), 5_000_000_000L)) {
                LegacyInputDiag.log("client passenger rebind entity=" +
                        entity.getClass().getName() + " authoritative=" +
                        authoritativePlayer.getClass().getName() + " facade=" +
                        clientPlayer.getClass().getName());
            }
            passenger = clientPlayer;
        }
        collectEntityWorlds(readField(entity, net.minecraft.entity.Entity.class, "field_70154_o"),
                clientWorld, seen, changed, passengerLinks, depth + 1,
                authoritativePlayer, clientPlayer);
        collectEntityWorlds(passenger, clientWorld, seen, changed, passengerLinks, depth + 1,
                authoritativePlayer, clientPlayer);
    }

    private static void mirrorEntity(Object source, Object target) {
        if (source == null) {
            return;
        }
        // A fresh facade is rebuilt on every bounded client tick. Mirror both vanilla position
        // histories, not only the current transform, so legacy interpolation never falls back
        // to the Unsafe allocation defaults while a rider/camera helper is active.
        copy(source, target, net.minecraft.entity.Entity.class, "field_70169_q");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70167_r");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70166_s");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70142_S");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70137_T");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70136_U");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70165_t");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70163_u");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70161_v");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70126_B");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70127_C");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70177_z");
        copy(source, target, net.minecraft.entity.Entity.class, "field_70125_A");
    }

    private static void copy(Object source, Object target, Class<?> owner, String name) {
        try {
            Field f = field(owner, name);
            Class<?> type = f.getType();
            Object value = f.get(source);
            if (type == double.class) {
                UmbUnsafe.setDouble(target, f, ((Double) value).doubleValue());
            } else if (type == float.class) {
                UmbUnsafe.setFloat(target, f, ((Float) value).floatValue());
            } else {
                UmbUnsafe.setField(target, f, value);
            }
        } catch (Throwable ignored) {
            // A host-side fake need not expose every vanilla field; absent mirror data is honest.
        }
    }

    private static volatile Object I18N_RESOURCES;

    /**
     * Client I18n: a real client's LanguageManager installs I18n's static Locale on resource
     * reload; the synthetic client never reloads, so every GUI that calls I18n.format (titles,
     * labels, tooltips) threw an NPE and its whole capture was lost. The Locale is loaded the
     * vanilla way - lang/en_US.lang from every domain of the client resource manager (vanilla
     * client strings plus each mod's lang file) - then topped up with FML's runtime-registered
     * names and the server StringTranslate table for keys the files do not define. Reloaded when
     * the resource manager changes; a failure is logged once and never blocks the facade.
     */
    private static void seedClientI18n(IResourceManager resources) {
        try {
            Field localeField = field(net.minecraft.client.resources.I18n.class, "field_135054_a");
            if (localeField.get(null) != null && (resources == null || resources == I18N_RESOURCES)) {
                return;
            }
            net.minecraft.client.resources.Locale locale = new net.minecraft.client.resources.Locale();
            int fromFiles = 0;
            if (resources != null) {
                try {
                    List<String> langs = new ArrayList<String>();
                    langs.add("en_US");
                    locale.func_135022_a(resources, langs);
                } catch (Throwable t) {
                    System.out.println("[UMB-LEGACY] client I18n lang files failed (non-fatal): " + t);
                }
            }
            Object props = readField(locale, net.minecraft.client.resources.Locale.class, "field_135032_a");
            if (props instanceof Map) {
                @SuppressWarnings("unchecked") Map<String, String> map = (Map<String, String>) props;
                fromFiles = map.size();
                try {
                    Class<?> registry = Class.forName("cpw.mods.fml.common.registry.LanguageRegistry",
                            false, Minecraft.class.getClassLoader());
                    Object instance = registry.getMethod("instance").invoke(null);
                    registry.getMethod("loadLanguageTable", Map.class, String.class)
                            .invoke(instance, map, "en_US");
                } catch (Throwable ignored) {
                    // Older/other FML without the hook: lang files and StringTranslate still apply.
                }
                Field instance = field(net.minecraft.util.StringTranslate.class, "field_74817_a");
                Object table = readField(instance.get(null), net.minecraft.util.StringTranslate.class,
                        "field_74816_c");
                if (table instanceof Map) {
                    for (Map.Entry<?, ?> e : ((Map<?, ?>) table).entrySet()) {
                        if (e.getKey() instanceof String && e.getValue() instanceof String
                                && !map.containsKey(e.getKey())) {
                            map.put((String) e.getKey(), (String) e.getValue());
                        }
                    }
                    // The other direction too: StatCollector reads StringTranslate, which a real
                    // client fills from the client jar's full en_US.lang. Ours came from the
                    // server's reduced table, so client keys (container.inventory, ...) printed
                    // raw in any GUI translating through StatCollector. Add only missing keys.
                    if (fromFiles > 0) {
                        @SuppressWarnings("unchecked") Map<String, String> server = (Map<String, String>) table;
                        synchronized (instance.get(null)) {
                            for (Map.Entry<String, String> e : map.entrySet()) {
                                if (!server.containsKey(e.getKey())) server.put(e.getKey(), e.getValue());
                            }
                        }
                    }
                }
                System.out.println("[UMB-LEGACY] client I18n locale keys=" + map.size()
                        + " fromLangFiles=" + fromFiles);
            }
            java.lang.reflect.Method set = net.minecraft.client.resources.I18n.class
                    .getDeclaredMethod("func_135051_a", net.minecraft.client.resources.Locale.class);
            set.setAccessible(true);
            set.invoke(null, locale);
            if (resources != null) I18N_RESOURCES = resources;
        } catch (Throwable t) {
            System.out.println("[UMB-LEGACY] client I18n seed failed (non-fatal): " + t);
        }
    }

    private static Object readField(Object source, Class<?> owner, String name) {
        if (source == null) {
            return null;
        }
        try {
            return field(owner, name).get(source);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object valueOr(Object source, Class<?> owner, String name, Object fallback) {
        Object value = readField(source, owner, name);
        return value == null ? fallback : value;
    }

    private static List<Object> copyList(Object value) {
        if (!(value instanceof List<?>)) {
            return new ArrayList<Object>();
        }
        return new ArrayList<Object>((List<?>) value);
    }

    private static List<GuiPlayerInfo> playerInfoList(HostWorld host, Object serverPlayer) {
        List<GuiPlayerInfo> result = new ArrayList<GuiPlayerInfo>();
        java.util.HashSet<String> names = new java.util.HashSet<String>();
        if (host != null) {
            try {
                List<HostPlayer> players = host.getPlayers();
                if (players != null) {
                    for (HostPlayer player : players) {
                        if (player == null) continue;
                        try {
                            String name = player.getName();
                            if (name == null || name.length() == 0 || !names.add(name)) continue;
                            GuiPlayerInfo info = new GuiPlayerInfo(name);
                            setFirstFieldOfType(info, Integer.TYPE,
                                    Integer.valueOf(Math.max(0, player.getPing())));
                            result.add(info);
                        } catch (Throwable ignored) {
                            // One malformed host player must not hide the remaining player list.
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Headless/test worlds may intentionally expose no host player list.
            }
        }
        if (result.isEmpty() && serverPlayer instanceof net.minecraft.entity.player.EntityPlayer) {
            try {
                String name = ((net.minecraft.entity.player.EntityPlayer) serverPlayer)
                        .func_70005_c_();
                if (name != null && name.length() != 0) {
                    result.add(new GuiPlayerInfo(name));
                }
            } catch (Throwable ignored) {
                // The synthetic probe player may have no profile yet.
            }
        }
        return result;
    }

    private static Session facadeSession(EntityClientPlayerMP player) {
        String name = "UMB-Capture";
        String uuid = "00000000-0000-0000-0000-000000000001";
        try {
            if (player != null) {
                String candidate = player.func_70005_c_();
                if (candidate != null && candidate.length() != 0) name = candidate;
                java.util.UUID id = player.func_110124_au();
                if (id != null) uuid = id.toString();
            }
        } catch (Throwable ignored) {
            // The stable capture identity is sufficient for client session consumers.
        }
        return new Session(name, uuid, "", "legacy");
    }

    /**
     * Forge normally resolves @SidedProxy to the client class during client boot. The legacy
     * universe deliberately boots as SERVER, so client handlers otherwise call a ServerProxy
     * whose me() returns null. Discovering the annotation keeps this universal and avoids any
     * mod-specific runtime name; only a bounded field replacement is active during dispatch.
     */
    private static ProxyScope installClientProxies() {
        List<ProxyValue> changed = new ArrayList<ProxyValue>();
        try {
            List<ModContainer> mods = Loader.instance().getModList();
            List<ClientProxyRegistration> registrations = clientProxyRegistrations(mods);
            for (ClientProxyRegistration registration : registrations) {
                Object previous = registration.field.get(null);
                if (previous != registration.clientProxy) {
                    registration.field.set(null, registration.clientProxy);
                    changed.add(new ProxyValue(registration.field, previous));
                }
            }
        } catch (Throwable t) {
            if (LegacyInputDiag.oncePer("client-proxy-install", 60_000_000_000L)) {
                LegacyInputDiag.log("client proxy facade discovery failed cause="
                        + t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
            }
        }
        return new ProxyScope(changed);
    }

    private static List<ClientProxyRegistration> clientProxyRegistrations(
            List<ModContainer> mods) throws Exception {
        int modCount = mods == null ? 0 : mods.size();
        List<ClientProxyRegistration> cached = CLIENT_PROXY_REGISTRATIONS;
        long now = System.nanoTime();
        if (cached != null && CLIENT_PROXY_MOD_COUNT == modCount
                && (!cached.isEmpty() || now < CLIENT_PROXY_REDISCOVER_AFTER)) {
            return cached;
        }
        synchronized (CLIENT_PROXY_DISCOVERY_LOCK) {
            cached = CLIENT_PROXY_REGISTRATIONS;
            now = System.nanoTime();
            if (cached != null && CLIENT_PROXY_MOD_COUNT == modCount
                    && (!cached.isEmpty() || now < CLIENT_PROXY_REDISCOVER_AFTER)) {
                return cached;
            }
            List<ClientProxyRegistration> discovered = new ArrayList<ClientProxyRegistration>();
            if (mods != null) {
                for (ModContainer container : mods) {
                    if (container == null || container.getMod() == null) continue;
                    Class<?> modClass = container.getMod().getClass();
                    ClassLoader loader = modClass.getClassLoader();
                    for (Class<?> cursor = modClass; cursor != null; cursor = cursor.getSuperclass()) {
                        for (Field field : cursor.getDeclaredFields()) {
                            if (!Modifier.isStatic(field.getModifiers())) continue;
                            SidedProxy annotation = field.getAnnotation(SidedProxy.class);
                            if (annotation == null || annotation.clientSide() == null
                                    || annotation.clientSide().length() == 0) continue;
                            field.setAccessible(true);
                            Class<?> clientClass = clientClass(annotation.clientSide(), loader);
                            if (clientClass == null || !field.getType().isAssignableFrom(clientClass)) {
                                continue;
                            }
                            Object previous = field.get(null);
                            Object clientProxy;
                            synchronized (CLIENT_PROXY_CACHE) {
                                clientProxy = CLIENT_PROXY_CACHE.get(field);
                                if (clientProxy == null && previous != null
                                        && clientClass.isInstance(previous)) {
                                    clientProxy = previous;
                                    CLIENT_PROXY_CACHE.put(field, clientProxy);
                                }
                                if (clientProxy == null) {
                                    Constructor<?> ctor = clientClass.getDeclaredConstructor();
                                    ctor.setAccessible(true);
                                    clientProxy = ctor.newInstance();
                                    CLIENT_PROXY_CACHE.put(field, clientProxy);
                                    if (LegacyInputDiag.oncePer("client-proxy-cache:"
                                            + field.getDeclaringClass().getName() + "." + field.getName(), 0)) {
                                        LegacyInputDiag.log("client proxy facade cached field="
                                                + field.getDeclaringClass().getName() + "." + field.getName());
                                    }
                                }
                            }
                            discovered.add(new ClientProxyRegistration(field, clientProxy));
                        }
                    }
                }
            }
            CLIENT_PROXY_MOD_COUNT = modCount;
            CLIENT_PROXY_REDISCOVER_AFTER = now + 5_000_000_000L;
            CLIENT_PROXY_REGISTRATIONS = java.util.Collections.unmodifiableList(discovered);
            return CLIENT_PROXY_REGISTRATIONS;
        }
    }

    private static Class<?> clientClass(String name, ClassLoader preferred) {
        try {
            if (preferred != null) {
                return Class.forName(name, true, preferred);
            }
        } catch (Throwable ignored) {
        }
        try {
            return LegacyModClasses.forName(name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void setField(Object target, Class<?> owner, String name, Object value) {
        UmbUnsafe.setField(target, field(owner, name), value);
    }

    private static void setFirstFieldOfType(Object target, Class<?> type, Object value) {
        Field f = firstFieldOfType(target.getClass(), type);
        if (f != null) {
            UmbUnsafe.setField(target, f, value);
        }
    }

    private static Field firstFieldOfType(Class<?> owner, Class<?> type) {
        String key = owner.getName() + "#" + type.getName();
        synchronized (FIRST_FIELDS_BY_TYPE) {
            Field cached = FIRST_FIELDS_BY_TYPE.get(key);
            if (cached != null) return cached;
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || !type.isAssignableFrom(f.getType())) continue;
                    f.setAccessible(true);
                    FIRST_FIELDS_BY_TYPE.put(key, f);
                    return f;
                }
            }
            return null;
        }
    }

    private static void set(Class<?> owner, String name, Object target, Object value) {
        try {
            Field f = field(owner, name);
            f.set(target, value);
        } catch (Exception e) {
            throw new IllegalStateException("cannot seed " + owner.getName() + "." + name, e);
        }
    }

    private static Field field(Class<?> owner, String name) {
        String key = owner.getName() + "#" + name;
        synchronized (REFLECTED_FIELDS) {
            Field cached = REFLECTED_FIELDS.get(key);
            if (cached != null) return cached;
        }
        Class<?> c = owner;
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                synchronized (REFLECTED_FIELDS) {
                    REFLECTED_FIELDS.put(key, f);
                }
                return f;
            } catch (NoSuchFieldException ignored) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("missing grounded field " + owner.getName() + "." + name);
    }
}
