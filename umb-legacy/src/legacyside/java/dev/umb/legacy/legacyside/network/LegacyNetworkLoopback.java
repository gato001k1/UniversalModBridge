package dev.umb.legacy.legacyside.network;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.FMLNetworkEvent;
import cpw.mods.fml.common.network.FMLEmbeddedChannel;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.internal.FMLProxyPacket;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import dev.umb.legacy.legacyside.input.LegacyInputDiag;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S1CPacketEntityMetadata;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;


/** Generic server-only network loopback and host-visible client-effect queue. */
public final class LegacyNetworkLoopback {
    public interface ServerContextProvider {
        MessageContext context(EntityPlayerMP player);
    }

    public static final class ClientMessage {
        private final Object payload;
        private final String channel;
        private final String route;
        ClientMessage(Object payload, String channel, String route) {
            this.payload = payload;
            this.channel = channel;
            this.route = route;
        }
        public Object payload() { return payload; }
        public String channel() { return channel; }
        public String route() { return route; }
    }

    private static final Map<Class<?>, IMessageHandler<?, ?>> SERVER_HANDLERS =
            new ConcurrentHashMap<Class<?>, IMessageHandler<?, ?>>();
    private static final Map<Class<?>, IMessageHandler<?, ?>> CLIENT_HANDLERS =
            new ConcurrentHashMap<Class<?>, IMessageHandler<?, ?>>();
    private static final List<ClientMessage> CLIENT_QUEUE = new ArrayList<ClientMessage>();
    private static final Map<TileEntity, String> VANILLA_TILE_SIGNATURES =
            new IdentityHashMap<TileEntity, String>();
    private static final Pattern VEHICLE_FIELD_NAMES = Pattern.compile(
            "(?i).*(throttle|fuel|power|rotor|speed|motion|onground|field_70181_x|field_70159_w|field_70179_y).*");
    private static final Pattern PACKET_FLAG_NAMES = Pattern.compile(
            "(?i).*(openGui|useWeapon|isUnmount|switchSeat|switchCameraMode|switchGear).*");
    private static final Map<Class<?>, Field[]> CHANNEL_FIELDS =
            new ConcurrentHashMap<Class<?>, Field[]>();
    private static final Map<Class<?>, Method> WATCHER_APPLY_METHODS =
            new ConcurrentHashMap<Class<?>, Method>();
    /** Classes whose wire copy failed once; repeated doomed reflection is worse than original delivery. */
    private static final Set<Class<?>> NO_WIRE_COPY =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<Class<?>, Boolean>());
    private static volatile Field ENTITY_WATCHER_FIELD;
    private static volatile Field EVENT_BUS_ID_FIELD;
    private static volatile Field SERVER_PLAYER_FIELD;
    private static volatile Field PLAYER_SERVER_HANDLER_FIELD;
    private static volatile Constructor<?> MESSAGE_CONTEXT_CONSTRUCTOR;
    private static volatile Object UNSAFE_INSTANCE;
    private static volatile Method UNSAFE_ALLOCATE_INSTANCE;
    private static volatile ServerContextProvider contextProvider;
    /**
 * Client handlers run inside the server-owned legacy tick, but their original Forge API has no player argument on sendToServer().
 * Keep that tick's facade here so the generic client-to-server route can construct the same SERVER MessageContext as the .
 */
    private static final ThreadLocal<EntityPlayerMP> CURRENT_CLIENT_PLAYER =
            new ThreadLocal<EntityPlayerMP>();
    /** Scoped fallback for direct facade/render dispatches that do not bind the tick queue. */
    private static final ThreadLocal<EntityPlayerMP> FACADE_CLIENT_PLAYER =
            new ThreadLocal<EntityPlayerMP>();
    private static final Object S2C_STATS_LOCK = new Object();
    private static long s2cSent;
    private static long s2cDelivered;
    private static long s2cNoTarget;

    private LegacyNetworkLoopback() { }

    public static void setServerContextProvider(ServerContextProvider provider) {
        contextProvider = provider;
    }

    /**
     * Client-to-server packets sent during the bounded client dispatch. That dispatch swaps the
     * player's world to the client facade (isRemote=true), and server handlers such as MCHeli's
     * MCH_HeliPacketHandler.onPacket_PlayerControl start with {@code if (worldObj.isRemote)
     * return}. A real client's packets reach the server after its tick, so they are delivered in
     * clearClientPlayer, once the dispatch has restored the server world.
     */
    private static final ThreadLocal<java.util.List<IMessage>> PENDING_CLIENT_TO_SERVER =
            new ThreadLocal<java.util.List<IMessage>>();
    /** Vanilla packets emitted by the synthetic NetHandlerPlayClient during one client dispatch. */
    private static final ThreadLocal<java.util.List<Packet>> PENDING_CLIENT_PACKETS =
            new ThreadLocal<java.util.List<Packet>>();

    public static void bindClientPlayer(EntityPlayerMP player) {
        if (player == null) {
            CURRENT_CLIENT_PLAYER.remove();
            PENDING_CLIENT_TO_SERVER.remove();
            PENDING_CLIENT_PACKETS.remove();
        } else {
            CURRENT_CLIENT_PLAYER.set(player);
            PENDING_CLIENT_TO_SERVER.set(new java.util.ArrayList<IMessage>());
            PENDING_CLIENT_PACKETS.set(new java.util.ArrayList<Packet>());
        }
    }

    /**
     * Binds an out-of-band legacy GUI dispatch without disturbing an existing client tick.
     * Automation and host menu callbacks can invoke a legacy GuiContainer after the normal
     * client-tick binding has ended; packets sent by that GUI still need the same player-bound
     * SERVER context, but must remain queued until the client facade is restored.
     *
     * @return true when this call owns the binding and must later call clearClientPlayer()
     */
    public static boolean bindClientPlayerIfAbsent(EntityPlayerMP player) {
        if (player == null || CURRENT_CLIENT_PLAYER.get() != null) {
            return false;
        }
        bindClientPlayer(player);
        return true;
    }

    public static void clearClientPlayer() {
        java.util.List<IMessage> pending = PENDING_CLIENT_TO_SERVER.get();
        java.util.List<Packet> pendingPackets = PENDING_CLIENT_PACKETS.get();
        PENDING_CLIENT_TO_SERVER.remove();
        PENDING_CLIENT_PACKETS.remove();
        EntityPlayerMP player = resolvedClientPlayer();
        try {
            if (pending != null) {
                for (IMessage message : pending) {
                    try {
                        deliverToServer(message, player);
                    } catch (Throwable t) {
                        if (LegacyInputDiag.oncePer("deferred-c2s-fail:" + message.getClass().getName(), 60_000_000_000L)) {
                            LegacyInputDiag.log("deferred client->server delivery failed message="
                                    + message.getClass().getName() + " error=" + t);
                        }
                    }
                }
            }
            if (pendingPackets != null) {
                for (Packet packet : pendingPackets) {
                    try {
                        deliverVanillaToServer(packet, player);
                    } catch (Throwable t) {
                        if (LegacyInputDiag.oncePer("deferred-vanilla-c2s-fail:"
                                + packet.getClass().getName() + ":" + t.getClass().getName(),
                                60_000_000_000L)) {
                            LegacyInputDiag.log("deferred vanilla client->server delivery failed packet="
                                    + packet.getClass().getName() + " error=" + t);
                        }
                    }
                }
            }
        } finally {
            CURRENT_CLIENT_PLAYER.remove();
        }
    }

    public static <M extends IMessage> void registerSimpleMessage(IMessageHandler<?, ?> handler,
            Class<M> messageClass, int discriminator, Side side) {
        if (handler == null || messageClass == null || side == null) {
            return;
        }
        (side == Side.SERVER ? SERVER_HANDLERS : CLIENT_HANDLERS).put(messageClass, handler);
    }

    /** Fallback for custom wrappers whose registerMessage API accepts a handler class. */
    public static void registerSimpleMessageClass(Class<?> handlerClass, Class<?> messageClass,
            int discriminator, Side side) {
        if (handlerClass == null || messageClass == null || side == null) return;
        try {
            Object handler = handlerClass.newInstance();
            if (!(handler instanceof IMessageHandler)) {
                throw new IllegalArgumentException("not an IMessageHandler: " + handlerClass.getName());
            }
            registerSimpleMessage((IMessageHandler<?, ?>) handler, (Class) messageClass,
                    discriminator, side);
        } catch (Exception e) {
            throw new IllegalStateException("cannot instantiate network handler " + handlerClass.getName(), e);
        }
    }

    public static void deliverToServer(IMessage message, EntityPlayerMP player) {
        if (message == null) return;
        IMessageHandler handler = serverHandlerFor(message.getClass());
        if (handler == null) {
            throw new IllegalStateException("no SERVER IMessageHandler registered for "
                    + message.getClass().getName());
        }
        EntityPlayerMP sender = player == null ? resolvedClientPlayer() : player;
        if (sender == null) {
            dropUnresolved(message, "sender");
            return;
        }
        MessageContext context = null;
        try {
            context = contextProvider == null ? defaultContext(sender)
                    : contextProvider.context(sender);
        } catch (Throwable providerFailure) {
            if (LegacyInputDiag.oncePer("client-context-provider-fallback", 60_000_000_000L)) {
                LegacyInputDiag.log("client->server context provider fallback cause="
                        + providerFailure.getClass().getName());
            }
        }
        if (!hasServerHandler(context)) {
            context = defaultContext(sender);
        }
        if (!hasServerHandler(context)) {
            dropUnresolved(message, "server-handler");
            return;
        }
        if (LegacyInputDiag.oncePer("loopback:" + message.getClass().getName(), 0)) {
            LegacyInputDiag.log("loopback " + message.getClass().getName()
                    + " -> SERVER handler (player "
                    + (sender == null ? "null" : "bound") + ")");
        }
        if (LegacyInputDiag.oncePer("loopback-flags:" + message.getClass().getName(), 5_000_000_000L)) {
            String packetFlags = packetFlags(message);
            if (!packetFlags.isEmpty()) {
                LegacyInputDiag.log("loopback-flags " + message.getClass().getSimpleName()
                        + " " + packetFlags);
            }
            if (sender != null) {
                LegacyInputDiag.log("loopback-state " + message.getClass().getSimpleName()
                        + " " + packetFlags
                        + " worldRemote=" + (sender.field_70170_p == null ? "null" : String.valueOf(sender.field_70170_p.field_72995_K))
                        + " world=" + (sender.field_70170_p == null ? "null" : sender.field_70170_p.getClass().getSimpleName())
                        + " riding=" + (sender.field_70154_o == null ? "null" : sender.field_70154_o.getClass().getName())
                        + " creative=" + (sender.field_71075_bZ != null && sender.field_71075_bZ.field_75098_d)
                        + " vehicleRiderIsSender=" + (sender.field_70154_o != null && sender.field_70154_o.field_70153_n == sender)
                        + " vehicleWorld=" + (sender.field_70154_o == null || sender.field_70154_o.field_70170_p == null ? "null"
                                : sender.field_70154_o.field_70170_p.getClass().getSimpleName() + "/remote="
                                        + sender.field_70154_o.field_70170_p.field_72995_K));
            }
        }
        try {
            handler.onMessage(message, context);
        } catch (RuntimeException | Error failure) {
            if (LegacyInputDiag.oncePer("loopback-handler-threw:" + message.getClass().getName()
                    + ":" + failure.getClass().getName(), 10_000_000_000L)) {
                StringBuilder where = new StringBuilder();
                StackTraceElement[] st = failure.getStackTrace();
                for (int i = 0; i < Math.min(6, st.length); i++) where.append(" <- ").append(st[i]);
                LegacyInputDiag.log("loopback SERVER handler threw for "
                        + message.getClass().getSimpleName() + ": " + failure + where);
            }
            throw failure;
        }
        if (sender != null && sender.field_70154_o != null
                && LegacyInputDiag.oncePer("loopback-vehicle:" + message.getClass().getName(), 3_000_000_000L)) {
            LegacyInputDiag.log("loopback-vehicle after " + message.getClass().getSimpleName() + " "
                    + vehicleState(sender.field_70154_o));
        }
    }

    /** Bounded diagnostic: numeric/boolean vehicle fields whose names suggest control state. */
    private static String vehicleState(Object vehicle) {
        StringBuilder out = new StringBuilder(vehicle.getClass().getSimpleName());
        for (Class<?> c = vehicle.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (!(t.isPrimitive()) || !VEHICLE_FIELD_NAMES.matcher(f.getName()).matches()) continue;
                try {
                    f.setAccessible(true);
                    out.append(' ').append(f.getName()).append('=').append(f.get(vehicle));
                } catch (Throwable ignored) { }
                if (out.length() > 900) return out.toString();
            }
        }
        return out.toString();
    }

    /**
     * Bounded, generic packet decision diagnostic.  It intentionally discovers only boolean
     * fields whose names describe a transport decision; it does not depend on a mod packet or
     * class name.  The value is part of the once-key, so a false-to-true transition is visible
     * without printing every control packet.
     */
    private static final java.util.concurrent.ConcurrentMap<Class<?>, List<Field>> PACKET_FLAG_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, List<Field>>();

    private static String packetFlags(Object message) {
        Class<?> messageClass = message.getClass();
        List<Field> fields = PACKET_FLAG_FIELDS.get(messageClass);
        if (fields == null) {
            fields = new ArrayList<Field>();
            for (Class<?> c = messageClass; c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            || f.getType() != Boolean.TYPE
                            || !PACKET_FLAG_NAMES.matcher(f.getName()).matches()) {
                        continue;
                    }
                    try { f.setAccessible(true); } catch (Throwable ignored) {}
                    fields.add(f);
                }
            }
            PACKET_FLAG_FIELDS.putIfAbsent(messageClass, fields);
        }
        
        StringBuilder out = new StringBuilder();
        for (Field f : fields) {
            try {
                if (out.length() > 0) out.append(' ');
                out.append(f.getName()).append('=').append(f.getBoolean(message));
            } catch (Throwable ignored) {
                // A diagnostic must never change packet delivery.
            }
            if (out.length() > 500) return out.toString();
        }
        return out.toString();
    }

    /** Hooked by SimpleNetworkWrapper.sendToServer and custom-wrapper fallbacks. */
    public static void captureClientToServer(IMessage message) {
        // A real client packet has no player in this server-only call path. The host input path
        // uses deliverToServer(message, player), which preserves identity for handlers.
        if (message != null) {
            String key = "client-to-server-capture:" + message.getClass().getName();
            if (LegacyInputDiag.oncePer(key, 1_000_000_000L)) {
                LegacyInputDiag.log("client->server capture message=" + message.getClass().getName()
                        + " handler=" + (serverHandlerFor(message.getClass()) != null));
            }
        }
        if (message != null && serverHandlerFor(message.getClass()) != null) {
            EntityPlayerMP sender = resolvedClientPlayer();
            if (sender == null) {
                dropUnresolved(message, "sender");
                return;
            }
            // A real server only ever sees the bytes: replay toBytes -> fromBytes through a fresh
            // instance, exactly as decodeClientMessage does for s2c. Packets whose state lives
            // only in fromBytes (e.g. a wrapper holding a ByteArrayDataInput) otherwise arrive
            // blank and their server handlers silently do nothing.
            message = wireCopyClientToServer(message);
            java.util.List<IMessage> pending = PENDING_CLIENT_TO_SERVER.get();
            if (pending != null) {
                pending.add(message);
            } else {
                deliverToServer(message, sender);
            }
        }
    }

    private static EntityPlayerMP resolvedClientPlayer() {
        EntityPlayerMP current = CURRENT_CLIENT_PLAYER.get();
        return current == null ? FACADE_CLIENT_PLAYER.get() : current;
    }

    private static boolean hasServerHandler(MessageContext context) {
        try {
            return context != null && context.getServerHandler() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void dropUnresolved(IMessage message, String reason) {
        if (message != null && LegacyInputDiag.oncePer("client-c2s-drop:"
                + message.getClass().getName() + ":" + reason, 60_000_000_000L)) {
            // Thread and binding state included so a future drop from an uncovered call
            // path is diagnosable from one log line.
            LegacyInputDiag.log("client->server dropped unresolved=" + reason
                    + " message=" + message.getClass().getName()
                    + " thread=" + Thread.currentThread().getName()
                    + " currentClientPlayer=" + (CURRENT_CLIENT_PLAYER.get() != null)
                    + " facadeClientPlayer=" + (FACADE_CLIENT_PLAYER.get() != null));
        }
    }

    /** Publishes the authoritative legacy player for a short-lived client facade scope. */
    public static EntityPlayerMP bindFacadePlayer(EntityPlayerMP player) {
        if (player == null) return null;
        EntityPlayerMP previous = FACADE_CLIENT_PLAYER.get();
        FACADE_CLIENT_PLAYER.set(player);
        return previous;
    }

    /** Restores the previous facade sender after a client facade scope ends. */
    public static void restoreFacadePlayer(EntityPlayerMP previous) {
        if (previous == null) {
            FACADE_CLIENT_PLAYER.remove();
        } else {
            FACADE_CLIENT_PLAYER.set(previous);
        }
    }

    /**
     * Entry point for the synthetic NetHandlerPlayClient's addToSendQueue path. Vanilla 1.7.10
     * dispatches a Packet to NetHandlerPlayServer through Packet.func_148833_a; retaining that
     * contract keeps chat, inventory, window and other client-originated packets generic. The
     * packet is deferred until the client facade has restored the authoritative server world,
     * just like SimpleNetworkWrapper messages above.
     */
    public static void captureClientPacket(Packet packet) {
        if (packet == null) return;
        if (LegacyInputDiag.oncePer("client-vanilla-capture:" + packet.getClass().getName(),
                1_000_000_000L)) {
            LegacyInputDiag.log("client->server capture vanilla packet="
                    + packet.getClass().getName());
        }
        java.util.List<Packet> pending = PENDING_CLIENT_PACKETS.get();
        if (pending != null) {
            pending.add(packet);
        } else {
            deliverVanillaToServer(packet, resolvedClientPlayer());
        }
    }

    private static void deliverVanillaToServer(Packet packet, EntityPlayerMP player) {
        if (packet == null) return;
        if (player == null) {
            if (LegacyInputDiag.oncePer("client-vanilla-c2s-drop:" + packet.getClass().getName(),
                    60_000_000_000L)) {
                LegacyInputDiag.log("client->server dropped vanilla unresolved sender packet="
                        + packet.getClass().getName());
            }
            return;
        }
        NetHandlerPlayServer handler = null;
        try {
            Object value = playerServerHandlerField().get(player);
            if (value instanceof NetHandlerPlayServer) {
                handler = (NetHandlerPlayServer) value;
            }
        } catch (Throwable ignored) {
            // Fall through to the existing generic context allocation below.
        }
        if (handler == null) {
            handler = allocate(NetHandlerPlayServer.class);
            try {
                serverPlayerField().set(handler, player);
            } catch (Throwable t) {
                throw new IllegalStateException("cannot bind vanilla packet to legacy player", t);
            }
        }
        packet.func_148833_a(handler);
        if (LegacyInputDiag.oncePer("client-vanilla-delivered:" + packet.getClass().getName(),
                1_000_000_000L)) {
            LegacyInputDiag.log("client->server vanilla delivered packet="
                    + packet.getClass().getName());
        }
    }

    /**
     * Handler registered for this message class or its nearest registered supertype. Mods may
     * register one handler for a base packet class and send subclasses (live: MCHeli registers
     * W_PacketBase and sends MCH_HeliPacketPlayerControl), so an exact-class lookup drops them.
     */
    static IMessageHandler serverHandlerFor(Class<?> type) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            IMessageHandler handler = SERVER_HANDLERS.get(c);
            if (handler != null) return handler;
        }
        return null;
    }

    public static void captureServerToClient(IMessage message) {
        enqueue(message, null, "server->client");
    }

    public static void captureClientToServer(FMLProxyPacket packet) {
        enqueue(packet, packet == null ? null : packet.channel(), "client->server-proxy");
    }

    public static void captureServerToClient(FMLProxyPacket packet) {
        enqueue(packet, packet == null ? null : packet.channel(), "server->client-proxy");
    }

    public static void captureServerToClient(ByteBuf payload) {
        captureServerToClient(payload, null);
    }

    /** Captures a raw custom-channel payload while retaining its generic channel owner. */
    public static void captureServerToClient(ByteBuf payload, Object sender) {
        if (payload == null) return;
        enqueue(Unpooled.copiedBuffer(payload), channelName(sender), "server->client-bytes");
    }

    /** Captures vanilla server packets for delivery on the bounded legacy client tick. */
    public static void captureServerToClient(Packet packet) {
        enqueue(packet, null, "server->client-vanilla");
    }

    private static void enqueue(Object payload, String channel, String route) {
        synchronized (CLIENT_QUEUE) {
            CLIENT_QUEUE.add(new ClientMessage(payload, channel, route));
        }
        if (route != null && route.startsWith("server->client")) {
            synchronized (S2C_STATS_LOCK) {
                s2cSent++;
            }
        }
    }

    public static List<ClientMessage> drainClientMessages() {
        synchronized (CLIENT_QUEUE) {
            List<ClientMessage> out = new ArrayList<ClientMessage>(CLIENT_QUEUE);
            CLIENT_QUEUE.clear();
            return Collections.unmodifiableList(out);
        }
    }

    /**
     * Delivers queued server-to-client packets inside the option-(b) legacy client scope. The
     * caller is already on the legacy tick thread, with Minecraft/theWorld/thePlayer installed,
     * so a mod handler sees the same client facade it would see after a real network decode.
     */
    public static int deliverClientMessages(dev.umb.legacy.legacyside.LegacyClientFacade.Binding binding) {
        List<ClientMessage> messages = drainClientMessages();
        int delivered = 0;
        for (ClientMessage message : messages) {
            try {
                if (deliverOne(message, binding)) {
                    delivered++;
                    synchronized (S2C_STATS_LOCK) {
                        s2cDelivered++;
                    }
                } else {
                    synchronized (S2C_STATS_LOCK) {
                        s2cNoTarget++;
                    }
                }
            } catch (Throwable t) {
                synchronized (S2C_STATS_LOCK) {
                    s2cNoTarget++;
                }
                String key = "s2c-delivery:" + payloadType(message.payload()) + ":"
                        + t.getClass().getName();
                if (LegacyInputDiag.oncePer(key, 5_000_000_000L)) {
                    LegacyInputDiag.log("s2c delivery failed payload=" + payloadType(message.payload())
                            + " cause=" + t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
                }
            } finally {
                if (message.payload() instanceof ByteBuf) {
                    releaseIfOpen((ByteBuf) message.payload());
                }
            }
        }
        logS2cStatsIfDue();
        return delivered;
    }

    /** Polls vanilla TileEntity.getDescriptionPacket once per changed packet and queues S35. */
    public static void captureVanillaTileUpdates(dev.umb.legacy.legacyside.UmbWorld serverWorld) {
        if (serverWorld == null) return;
        Set<TileEntity> live = Collections.newSetFromMap(new IdentityHashMap<TileEntity, Boolean>());
        for (TileEntity tile : serverWorld.tileSnapshot()) {
            if (tile == null) continue;
            live.add(tile);
            try {
                Packet packet = tile.func_145844_m();
                if (!(packet instanceof S35PacketUpdateTileEntity)) continue;
                S35PacketUpdateTileEntity update = (S35PacketUpdateTileEntity) packet;
                String signature = update.func_148856_c() + ":" + update.func_148855_d() + ":"
                        + update.func_148854_e() + ":" + update.func_148853_f() + ":"
                        + String.valueOf(update.func_148857_g());
                String previous = VANILLA_TILE_SIGNATURES.get(tile);
                if (!signature.equals(previous)) {
                    VANILLA_TILE_SIGNATURES.put(tile, signature);
                    captureServerToClient(update);
                }
            } catch (Throwable t) {
                if (LegacyInputDiag.oncePer("s2c-description-packet:" + tile.getClass().getName(),
                        60_000_000_000L)) {
                    LegacyInputDiag.log("s2c description packet skipped tile="
                            + tile.getClass().getName() + " cause=" + t.getClass().getName());
                }
            }
        }
        VANILLA_TILE_SIGNATURES.keySet().retainAll(live);
    }

    private static boolean deliverOne(ClientMessage message,
            dev.umb.legacy.legacyside.LegacyClientFacade.Binding binding) {
        Object payload = message.payload();
        if (payload instanceof IMessage) {
            IMessageHandler handler = clientHandler(payload.getClass());
            if (handler == null) return false;
            ByteBuf wire = null;
            try {
                wire = Unpooled.buffer();
                IMessage decoded = decodeClientMessage((IMessage) payload, wire);
                handler.onMessage(decoded, newContext(null, Side.CLIENT));
            } finally {
                releaseIfOpen(wire);
            }
            return true;
        }
        if (payload instanceof FMLProxyPacket) {
            FMLProxyPacket packet = (FMLProxyPacket) payload;
            FMLNetworkEvent.ClientCustomPacketEvent event =
                    new FMLNetworkEvent.ClientCustomPacketEvent(null, packet);
            if (!hasListeners(event)) return false;
            FMLCommonHandler.instance().bus().post(event);
            return true;
        }
        if (payload instanceof ByteBuf) {
            ByteBuf raw = (ByteBuf) payload;
            String channel = message.channel();
            if (channel != null) {
                FMLEmbeddedChannel clientChannel = NetworkRegistry.INSTANCE.getChannel(
                        channel, Side.CLIENT);
                if (clientChannel != null) {
                    ByteBuf handoff = raw.retain().duplicate();
                    try {
                        clientChannel.write(handoff);
                        clientChannel.flush();
                        return true;
                    } finally {
                        releaseIfOpen(handoff);
                    }
                }
            }
            String eventChannel = channel == null ? "UMB" : channel;
            ByteBuf handoff = raw.retain().duplicate();
            try {
                return deliverOne(new ClientMessage(new FMLProxyPacket(
                        handoff, eventChannel), eventChannel, message.route()), binding);
            } finally {
                releaseIfOpen(handoff);
            }
        }
        if (payload instanceof S35PacketUpdateTileEntity) {
            if (binding == null || binding.world == null) return false;
            S35PacketUpdateTileEntity packet = (S35PacketUpdateTileEntity) payload;
            TileEntity tile = binding.world.func_147438_o(packet.func_148856_c(),
                    packet.func_148855_d(), packet.func_148854_e());
            if (tile == null) return false;
            tile.onDataPacket(null, packet);
            return true;
        }
        if (payload instanceof S1CPacketEntityMetadata) {
            if (binding == null || binding.world == null) return false;
            S1CPacketEntityMetadata packet = (S1CPacketEntityMetadata) payload;
            net.minecraft.entity.Entity entity = binding.world.func_73045_a(packet.func_149375_d());
            if (entity == null) return false;
            try {
                Field watcherField = entityWatcherField();
                Object watcher = watcherField.get(entity);
                if (watcher == null) return false;
                watcherApplyMethod(watcher.getClass()).invoke(watcher, packet.func_149376_c());
                return true;
            } catch (Throwable t) {
                throw new IllegalStateException("cannot apply entity metadata", t);
            }
        }
        return false;
    }

    private static Field entityWatcherField() throws NoSuchFieldException {
        Field field = ENTITY_WATCHER_FIELD;
        if (field != null) return field;
        synchronized (LegacyNetworkLoopback.class) {
            field = ENTITY_WATCHER_FIELD;
            if (field == null) {
                field = net.minecraft.entity.Entity.class.getDeclaredField("field_70180_af");
                field.setAccessible(true);
                ENTITY_WATCHER_FIELD = field;
            }
            return field;
        }
    }

    private static Method watcherApplyMethod(Class<?> watcherType) throws NoSuchMethodException {
        Method method = WATCHER_APPLY_METHODS.get(watcherType);
        if (method != null) return method;
        method = watcherType.getMethod("func_75687_a", List.class);
        method.setAccessible(true);
        Method previous = WATCHER_APPLY_METHODS.putIfAbsent(watcherType, method);
        return previous == null ? method : previous;
    }

    private static IMessage wireCopyClientToServer(IMessage message) {
        if (NO_WIRE_COPY.contains(message.getClass())) return message;
        ByteBuf wire = io.netty.buffer.Unpooled.buffer();
        try {
            // Serialize a defensive field copy. Some legacy toBytes implementations advance or
            // release mutable state; the original object must remain intact for failure fallback.
            return decodeClientMessage(copyMessage(message), wire);
        } catch (Throwable failure) {
            NO_WIRE_COPY.add(message.getClass());
            if (LegacyInputDiag.oncePer("c2s-wire-copy-failed:" + message.getClass().getName(),
                    60_000_000_000L)) {
                LegacyInputDiag.log("c2s wire copy failed for " + message.getClass().getName()
                        + " (disabling future copies; delivering the original object): " + failure);
            }
            return message;
        } finally {
            releaseIfOpen(wire);
        }
    }

    /** Shallow-copy the packet object before invoking its destructive wire serializer. */
    @SuppressWarnings("unchecked")
    private static IMessage copyMessage(IMessage original) throws IllegalAccessException {
        IMessage copy = allocate((Class<IMessage>) original.getClass());
        for (Class<?> c = original.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(original);
                if (value instanceof byte[]) value = ((byte[]) value).clone();
                else if (value instanceof ByteBuf) value = Unpooled.copiedBuffer((ByteBuf) value);
                field.set(copy, value);
            }
        }
        return copy;
    }

    /**
     * Replays the Forge wire boundary even though the server send hook receives an IMessage
     * object. Client packets that initialize state from fromBytes must not see the server object.
     */
    private static IMessage decodeClientMessage(IMessage source, ByteBuf wire) {
        try {
            source.toBytes(wire);
            Constructor<?> constructor = source.getClass().getDeclaredConstructor();
            constructor.setAccessible(true);
            IMessage decoded = (IMessage) constructor.newInstance();
            decoded.fromBytes(wire);
            return decoded;
        } catch (Exception e) {
            throw new IllegalStateException("cannot round-trip client packet "
                    + source.getClass().getName(), e);
        }
    }

    private static void releaseIfOpen(ByteBuf buffer) {
        if (buffer != null && buffer.refCnt() > 0) {
            buffer.release();
        }
    }

    /** Finds a registered FML channel without knowing the mod wrapper class. */
    private static String channelName(Object sender) {
        if (sender == null) return null;
        Class<?> type = sender instanceof Class<?> ? (Class<?>) sender : sender.getClass();
        for (Field field : channelFields(type)) {
            try {
                Object value = field.get(sender instanceof Class<?> ? null : sender);
                if (value instanceof FMLEmbeddedChannel) {
                    Object name = ((FMLEmbeddedChannel) value)
                            .attr(NetworkRegistry.FML_CHANNEL).get();
                    if (name instanceof String) return (String) name;
                }
            } catch (Throwable ignored) {
                // An inaccessible unrelated field must not stop discovery of other channels.
            }
        }
        return null;
    }

    private static Field[] channelFields(Class<?> type) {
        Field[] cached = CHANNEL_FIELDS.get(type);
        if (cached != null) return cached;
        List<Field> found = new ArrayList<Field>();
        final int maxClassDepth = 8;
        final int maxFieldsPerClass = 64;
        Class<?> cursor = type;
        for (int depth = 0; cursor != null && depth < maxClassDepth;
                depth++, cursor = cursor.getSuperclass()) {
            Field[] declared = cursor.getDeclaredFields();
            int limit = Math.min(declared.length, maxFieldsPerClass);
            for (int i = 0; i < limit; i++) {
                try {
                    declared[i].setAccessible(true);
                    found.add(declared[i]);
                } catch (Throwable ignored) {
                    // Keep the bounded discovery best-effort, as before.
                }
            }
        }
        Field[] result = found.toArray(new Field[found.size()]);
        Field[] previous = CHANNEL_FIELDS.putIfAbsent(type, result);
        return previous == null ? result : previous;
    }

    private static IMessageHandler clientHandler(Class<?> messageClass) {
        for (Class<?> c = messageClass; c != null && c != Object.class; c = c.getSuperclass()) {
            IMessageHandler handler = CLIENT_HANDLERS.get(c);
            if (handler != null) return handler;
        }
        for (Map.Entry<Class<?>, IMessageHandler<?, ?>> entry : CLIENT_HANDLERS.entrySet()) {
            if (entry.getKey().isAssignableFrom(messageClass)) return entry.getValue();
        }
        return null;
    }

    private static boolean hasListeners(cpw.mods.fml.common.eventhandler.Event event) {
        try {
            Field id = eventBusIdField();
            int busId = id.getInt(FMLCommonHandler.instance().bus());
            return event.getListenerList().getListeners(busId).length != 0;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static Field eventBusIdField() throws NoSuchFieldException {
        Field field = EVENT_BUS_ID_FIELD;
        if (field != null) return field;
        synchronized (LegacyNetworkLoopback.class) {
            field = EVENT_BUS_ID_FIELD;
            if (field == null) {
                field = cpw.mods.fml.common.eventhandler.EventBus.class
                        .getDeclaredField("busID");
                field.setAccessible(true);
                EVENT_BUS_ID_FIELD = field;
            }
            return field;
        }
    }

    private static String payloadType(Object payload) {
        return payload == null ? "null" : payload.getClass().getName();
    }

    private static void logS2cStatsIfDue() {
        if (!LegacyInputDiag.oncePer("s2c-summary", 5_000_000_000L)) return;
        long sent;
        long delivered;
        long noTarget;
        synchronized (S2C_STATS_LOCK) {
            sent = s2cSent;
            delivered = s2cDelivered;
            noTarget = s2cNoTarget;
        }
        LegacyInputDiag.log("UMB-NET s2c sent=" + sent + " delivered=" + delivered
                + " noTarget=" + noTarget);
    }

    /**
 * Default context for the already-built UmbPlayer facade.
 * may replace this with a provider that obtains the current player from its .
 */
    private static MessageContext defaultContext(EntityPlayerMP player) {
        if (player == null) {
            return null;
        }
        try {
            NetHandlerPlayServer handler = null;
            Object existing = playerServerHandlerField().get(player);
            if (existing instanceof NetHandlerPlayServer) {
                handler = (NetHandlerPlayServer) existing;
            }
            if (handler == null) {
                handler = allocate(NetHandlerPlayServer.class);
                Field f = serverPlayerField();
                f.set(handler, player);
            }
            return newContext(handler, Side.SERVER);
        } catch (Exception e) {
            throw new IllegalStateException("cannot bind legacy player to SERVER context", e);
        }
    }

    private static MessageContext newContext(Object handler, Side side) {
        try {
            Constructor<?> c = MESSAGE_CONTEXT_CONSTRUCTOR;
            if (c == null) {
                synchronized (LegacyNetworkLoopback.class) {
                    c = MESSAGE_CONTEXT_CONSTRUCTOR;
                    if (c == null) {
                        for (Constructor<?> candidate : MessageContext.class.getDeclaredConstructors()) {
                            Class<?>[] p = candidate.getParameterTypes();
                            if (p.length == 2 && p[1] == Side.class) {
                                candidate.setAccessible(true);
                                c = candidate;
                                MESSAGE_CONTEXT_CONSTRUCTOR = candidate;
                                break;
                            }
                        }
                    }
                }
            }
            if (c == null) throw new NoSuchMethodException("(handler, Side)");
            return (MessageContext) c.newInstance(handler, side);
        } catch (Exception e) {
            throw new IllegalStateException("cannot construct SERVER MessageContext", e);
        }
    }

    private static <T> T allocate(Class<T> type) {
        try {
            Object unsafe = UNSAFE_INSTANCE;
            Method allocator = UNSAFE_ALLOCATE_INSTANCE;
            if (unsafe == null || allocator == null) {
                synchronized (LegacyNetworkLoopback.class) {
                    unsafe = UNSAFE_INSTANCE;
                    allocator = UNSAFE_ALLOCATE_INSTANCE;
                    if (unsafe == null || allocator == null) {
                        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                        Field f = unsafeClass.getDeclaredField("theUnsafe");
                        f.setAccessible(true);
                        unsafe = f.get(null);
                        allocator = unsafeClass.getMethod("allocateInstance", Class.class);
                        allocator.setAccessible(true);
                        UNSAFE_INSTANCE = unsafe;
                        UNSAFE_ALLOCATE_INSTANCE = allocator;
                    }
                }
            }
            return type.cast(allocator.invoke(unsafe, type));
        } catch (Exception e) {
            throw new IllegalStateException("cannot allocate " + type.getName(), e);
        }
    }

    private static Field serverPlayerField() throws NoSuchFieldException {
        Field field = SERVER_PLAYER_FIELD;
        if (field != null) return field;
        synchronized (LegacyNetworkLoopback.class) {
            field = SERVER_PLAYER_FIELD;
            if (field == null) {
                field = NetHandlerPlayServer.class.getDeclaredField("field_147369_b");
                field.setAccessible(true);
                SERVER_PLAYER_FIELD = field;
            }
            return field;
        }
    }

    private static Field playerServerHandlerField() throws NoSuchFieldException {
        Field field = PLAYER_SERVER_HANDLER_FIELD;
        if (field != null) return field;
        synchronized (LegacyNetworkLoopback.class) {
            field = PLAYER_SERVER_HANDLER_FIELD;
            if (field == null) {
                field = net.minecraft.entity.player.EntityPlayerMP.class
                        .getDeclaredField("field_71135_a");
                field.setAccessible(true);
                PLAYER_SERVER_HANDLER_FIELD = field;
            }
            return field;
        }
    }
}
