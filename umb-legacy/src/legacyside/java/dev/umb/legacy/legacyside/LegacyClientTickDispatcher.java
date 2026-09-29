package dev.umb.legacy.legacyside;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.net.URL;
import java.security.CodeSource;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.eventhandler.Event;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.IEventListener;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import dev.umb.legacy.legacyside.input.LegacyInputDiag;
import dev.umb.legacy.legacyside.input.LegacyLwjglState;
import dev.umb.legacy.legacyside.input.LegacyModClasses;
import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;
import dev.umb.legacy.legacyside.render.LegacyRenderCapture;
import dev.umb.bridge.api.GlEmulationSession;
import dev.umb.bridge.api.LegacyBridge;

/**
 * Generic option-(b) client-event bridge. It discovers client tick subscribers from the
 * loaded mod sources, constructs them with the current minimal facade where possible, and
 * posts the canonical FML client and render tick events in the existing server universe.
 * No mod identity is part of this mechanism.
 */
public final class LegacyClientTickDispatcher {
    private static final int TRACE_FIELD_LIMIT = 48;
    /** Last time the host drove renderOverlay; the tick path posts RenderTickEvent only when stale. */
    private static volatile long lastOverlayFrameNanos;
    private static final long OVERLAY_FRAME_STALE_NANOS = 250_000_000L;
    private static final String SUBSCRIBE_DESC =
            "Lcpw/mods/fml/common/eventhandler/SubscribeEvent;";
    private static final String CLIENT_TICK_DESC =
            "Lcpw/mods/fml/common/gameevent/TickEvent$ClientTickEvent;";
    private static final String RENDER_TICK_DESC =
            "Lcpw/mods/fml/common/gameevent/TickEvent$RenderTickEvent;";
    private static final String OVERLAY_DESC =
            "Lnet/minecraftforge/client/event/RenderGameOverlayEvent;";
    private static final boolean DEBUG_CLIENT_DISPATCH = Boolean.parseBoolean(
            System.getProperty("umb.debug.clientDispatch", "false"));
    private static final long DISPATCH_SUMMARY_NANOS = 5_000_000_000L;
    /**
     * Client tile presentation is a visual twin pass, not the authoritative tile tick or
     * network capture.  It performs bounded NBT plus reflective primitive copying for every
     * nearby legacy tile; running it at 10 Hz keeps GUI/effect latency below a frame budget while
     * avoiding a full duplicate-machine walk on every 20 Hz server tick.  This is deliberately
     * universal: the cadence is independent of mod, tile class, and namespace.
     */
    private static final long CLIENT_TILE_PRESENTATION_INTERVAL_TICKS = 2L;

    private static final Set<String> REGISTERED =
            Collections.synchronizedSet(new HashSet<String>());
    /** Actual subscriber instances, retained so lazily discovered handlers can be rebound. */
    private static final List<Object> CLIENT_SUBSCRIBERS =
            Collections.synchronizedList(new ArrayList<Object>());
    private static volatile long dispatchCount;
    private static volatile int registeredCount;
    private static volatile int lastTickInvocations;
    private static volatile int lastTickFailures;
    private static volatile long lastDiscoveryNanos;
    /** Mod sources are fixed after FML startup; rescanning their class trees every few seconds
     * made the server thread walk the extracted game directory during unrelated client ticks. */
    private static volatile boolean discoveryComplete;
    private static volatile long discoveryPasses;
    private static volatile int lastDiscoverySources;
    private static volatile int lastDiscoveryCandidates;
    private static volatile int lastDiscoveryRegistered;
    private static final Object DISPATCH_DIAG_LOCK = new Object();
    private static final Map<String, ListenerDispatchStats> DISPATCH_LISTENER_STATS =
            new LinkedHashMap<String, ListenerDispatchStats>();
    private static final Set<String> FIRST_FAILURE_DETAILS = new HashSet<String>();
    private static final Map<String, LegacyBridge.CameraState> CAMERA_STATES =
            new java.util.concurrent.ConcurrentHashMap<String, LegacyBridge.CameraState>();
    /** Last facade EntityRenderer third-person distance per player, captured with CAMERA_STATES. */
    private static final Map<String, Float> THIRD_PERSON_DISTANCES =
            new java.util.concurrent.ConcurrentHashMap<String, Float>();
    private static long dispatchDiagWindowStart = System.nanoTime();
    private static long dispatchDiagTicks;
    private static long dispatchDiagWalkedNodes;
    /** Reused by tick and overlay dispatch in one client frame; replaced at the next tick. */
    private static List<Object> tickRebindRoots = Collections.emptyList();

    private LegacyClientTickDispatcher() {
    }

    /** Dispatches one client/render tick for the supplied authoritative legacy player. */
    public static void tick(EntityPlayer serverPlayer, World serverWorld) {
        synchronized (LegacyClientTickDispatcher.class) {
            tickLocked(serverPlayer, serverWorld);
        }
    }

    private static void tickLocked(EntityPlayer serverPlayer, World serverWorld) {
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(serverPlayer, serverWorld);
        ensureRegistered(binding.minecraft);
        tickRebindRoots = rebindRoots();
        LegacyClientFacade.MinecraftReferenceScope minecraftScope =
                LegacyClientFacade.rebindMinecraftReferences(tickRebindRoots, binding.minecraft);
        LegacyClientFacade.ensureDisplayDimensions(binding.minecraft);
        // FML started this native-free universe on SERVER, so @SidedProxy selected common
        // proxies during boot. Install the generic client visual hooks before event dispatch and
        // before the first entity renderer lookup; otherwise model registration is circularly
        // dependent on a capture that cannot start yet.
        LegacyRenderCapture.prepareClientUniverse(binding);
        String playerName = playerName(serverPlayer);
        if (playerName == null) {
            playerName = "__legacy_client__";
        }
        LegacyLwjglState.begin(playerName);
        // Vanilla client state that mouse-driven mod code gates on: in-world focus and a
        // MouseHelper whose deltas come from the host's real view rotation.
        binding.minecraft.field_71415_G = LegacyLwjglState.focused(playerName);
        if (!(binding.minecraft.field_71417_B instanceof UmbMouseHelper)) {
            binding.minecraft.field_71417_B = new UmbMouseHelper();
        }
        ++dispatchCount;
        lastTickInvocations = 0;
        lastTickFailures = 0;
        recordDispatchTick();
        LegacyClientFacade.EntityWorldScope entityWorldScope = null;
        try {
            entityWorldScope = LegacyClientFacade.rebindEntityWorlds(binding.player, binding.world, serverWorld,
                        serverPlayer, binding.player);
            LegacyNetworkLoopback.captureVanillaTileUpdates(
                    serverWorld instanceof UmbWorld ? (UmbWorld) serverWorld : null);
            LegacyNetworkLoopback.deliverClientMessages(binding);
            if (serverWorld instanceof UmbWorld
                    && (dispatchCount % CLIENT_TILE_PRESENTATION_INTERVAL_TICKS) == 1L) {
                LegacyClientTilePresenter.tick((UmbWorld) serverWorld, binding.world, binding.player);
            }
            EventBus bus = cpw.mods.fml.common.FMLCommonHandler.instance().bus();
            postIsolated(bus, new TickEvent.ClientTickEvent(TickEvent.Phase.START), binding,
                    playerName, serverPlayer);
            if (LegacyInputDiag.oncePer("render-start-state", 3_000_000_000L)) {
                Minecraft mcNow = binding.minecraft;
                LegacyInputDiag.log("render START state player=" + playerName
                        + " focusField=" + mcNow.field_71415_G
                        + " focusedState=" + LegacyLwjglState.focused(playerName)
                        + " paused=" + mcNow.func_147113_T()
                        + " singletonSame=" + (Minecraft.func_71410_x() == mcNow)
                        + " mouseHelper=" + (mcNow.field_71417_B == null ? "null" : mcNow.field_71417_B.getClass().getSimpleName())
                        + " screen=" + mcNow.field_71462_r
                        + " riding=" + (mcNow.field_71439_g == null ? "noplayer"
                                : String.valueOf(mcNow.field_71439_g.field_70154_o))
                        + " roots=" + describeRootMinecraft(mcNow));
            }
            // Real Minecraft posts RenderTickEvent once per frame. While the host drives
            // renderOverlay every frame it posts it there (inside the capture session); when the
            // HUD seam is idle (off, or not riding) this tick keeps it flowing.
            if (System.nanoTime() - lastOverlayFrameNanos > OVERLAY_FRAME_STALE_NANOS) {
                postIsolated(bus, new TickEvent.RenderTickEvent(TickEvent.Phase.START, 0.0F), binding,
                        playerName, serverPlayer);
                postIsolated(bus, new TickEvent.RenderTickEvent(TickEvent.Phase.END, 0.0F), binding,
                        playerName, serverPlayer);
            }
            postIsolated(bus, new TickEvent.ClientTickEvent(TickEvent.Phase.END), binding,
                    playerName, serverPlayer);
        } catch (Throwable t) {
            String failureKey = "client-tick-dispatch:" + causeKey(t);
            if (firstFailureDetail(failureKey)) {
                LegacyInputDiag.log("client tick dispatch failed player=" + playerName
                        + " cause=" + t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
            }
        } finally {
            String cameraPlayer = playerName(serverPlayer);
            if (cameraPlayer != null) {
                CAMERA_STATES.put(cameraPlayer, LegacyClientFacade.cameraState(binding.minecraft));
                float distance = LegacyClientFacade.thirdPersonDistance(binding.minecraft);
                if (Float.isFinite(distance)) {
                    THIRD_PERSON_DISTANCES.put(cameraPlayer, Float.valueOf(distance));
                }
            }
            try {
                // This tick's level polls have seen every press latched since the last tick.
                LegacyLwjglState.clientTickDone(playerName);
                LegacyLwjglState.end();
            } finally {
                try {
                    if (entityWorldScope != null) entityWorldScope.restore();
                } finally {
                    try {
                        minecraftScope.restore();
                    } finally {
                        binding.restoreProxies();
                    }
                }
            }
            emitDispatchSummaryIfDue(playerName);
        }
    }

    /**
     * Renders the generic Forge overlay event stream inside the bounded legacy client facade.
     * The callback is deliberately separate from the host GUI type: only GL-EMU data crosses
     * the class-loader boundary.
     *
     * <p>Also posts {@code TickEvent.RenderTickEvent} START/END here (in addition to the
     * existing {@code RenderGameOverlayEvent} stream): a HUD is not always attached to a Forge
     * overlay element. A mod's own client tick handler commonly draws its HUD directly from a
     * MCHeli jar: {@code mcheli.wrapper.W_TickHandler.onRenderTickEvent} dispatches
     * {@code phase==END} to {@code onRenderTickPost(float)}, which draws the vehicle HUD). {@link
     * #tick} already posts this event every host server tick, but that call runs outside any
     * {@link LegacyRenderCapture} session, so every GL-EMU call the handler makes there is
     * silently discarded ({@code LegacyRenderCapture.ACTIVE} is null): the mesh returned to the
     * host was always missing that HUD's geometry, independent of any painter fix. Posting it a
     * second time here, inside the capture scope, with the real partial tick, is what actually
     * captures it; {@link #tick} posts it only while this path is idle, so the event fires
     * once per frame either way. Ordering matches real Minecraft: RenderTickEvent(START) precedes every overlay
     * element, RenderTickEvent(END) follows all of them, so a HUD drawn from END still paints on
     * top - exactly like today's live game.</p>
     *
     * {@code MCH_HudItemTexture.drawTexture} nor {@code MCH_HudItem}'s own body ever calls
     * {@code GL11.glEnable(GL_TEXTURE_2D)} - it only binds the texture (build's
     * {@code getfield}/{@code bindTexture} evidence) and draws Tessellator quads, exactly the
     * class of mod code {@link LegacyRenderCapture#captureOverlay(LegacyClientFacade.Binding,
     * SRG {@code FontRenderer} (the facade's real font, not a stub) shows the same thing: its
     * glyph-quad loop (SRG {@code func_78255_a}) only ever {@code glDisable}/{@code glEnable}s
     * {@code GL_TEXTURE_2D} around the underline/strikethrough decoration box, never at the top
     * of the method - glyph quads also assume ambient texturing. {@link LegacyGuiCapture} (the
     * GuiContainer/R-menu path, already proven correct live) already passes {@code true} here;
     * this is the same real vanilla "the 2D overlay phase begins with texturing (and usually
     * blending) already enabled" fact applying equally to the HUD render phase, not a
     * HUD-specific guess.</p>
     */
    public static GlEmulationSession.Mesh renderOverlay(EntityPlayer serverPlayer, World serverWorld,
                                                         final float partialTicks, final int width,
                                                         final int height) {
        lastOverlayFrameNanos = System.nanoTime();
        synchronized (LegacyClientTickDispatcher.class) {
            LegacyClientFacade.Binding binding = LegacyClientFacade.install(serverPlayer, serverWorld);
            ensureRegistered(binding.minecraft);
            LegacyClientFacade.MinecraftReferenceScope minecraftScope =
                    LegacyClientFacade.rebindMinecraftReferences(
                            tickRebindRoots.isEmpty() ? rebindRoots() : tickRebindRoots, binding.minecraft);
            LegacyClientFacade.ensureDisplayDimensions(binding.minecraft);
            LegacyRenderCapture.prepareClientUniverse(binding);
            String playerName = playerName(serverPlayer);
            if (playerName == null) playerName = "__legacy_client__";
            LegacyLwjglState.begin(playerName);
            LegacyClientFacade.EntityWorldScope entityWorldScope = null;
            try {
                entityWorldScope = LegacyClientFacade.rebindEntityWorlds(binding.player, binding.world, serverWorld,
                            serverPlayer, binding.player);
                final String name = playerName;
                final ScaledResolution resolution = new ScaledResolution(binding.minecraft, width, height);
                final EventBus bus = MinecraftForge.EVENT_BUS;
                final EventBus fmlBus = FMLCommonHandler.instance().bus();
                // The host already renders every vanilla HUD element. Still capture each legacy
                // event type separately: mods commonly attach vehicle HUDs to CROSSHAIRS,
                // HOTBAR, or another element rather than ALL. LegacyHudPainter filters the
                // host-owned full-screen fills after the GL state has been recorded.
                GlEmulationSession.Mesh result = LegacyRenderCapture.captureOverlay(binding,
                        new Runnable() {
                            @Override public void run() {
                                // Real Minecraft fires RenderTickEvent(START) before any overlay
                                // element; a vehicle HUD attached to RenderTickEvent(END) (see
                                // the method javadoc) must see the same ordering so it still
                                // paints on top of everything captured below.
                                postIsolated(fmlBus, new TickEvent.RenderTickEvent(
                                        TickEvent.Phase.START, partialTicks), binding, name,
                                        serverPlayer);
                                RenderGameOverlayEvent all = new RenderGameOverlayEvent(
                                        partialTicks, resolution, 0, 0);
                                postIsolated(bus, new RenderGameOverlayEvent.Pre(all,
                                        RenderGameOverlayEvent.ElementType.ALL), binding, name,
                                        serverPlayer);
                            }
                        }, true);
                for (final RenderGameOverlayEvent.ElementType type
                        : RenderGameOverlayEvent.ElementType.values()) {
                    if (type == RenderGameOverlayEvent.ElementType.ALL) continue;
                    final RenderGameOverlayEvent base = new RenderGameOverlayEvent(
                            partialTicks, resolution, 0, 0);
                    result = GlEmulationSession.concat(result,
                            LegacyRenderCapture.captureOverlay(binding, new Runnable() {
                                @Override public void run() {
                                    postIsolated(bus, new RenderGameOverlayEvent.Pre(base, type),
                                            binding, name, serverPlayer);
                                }
                            }, true));
                    result = GlEmulationSession.concat(result,
                            LegacyRenderCapture.captureOverlay(binding, new Runnable() {
                                @Override public void run() {
                                    postIsolated(bus, new RenderGameOverlayEvent.Post(base, type),
                                            binding, name, serverPlayer);
                                }
                            }, true));
                }
                GlEmulationSession.Mesh postAll = LegacyRenderCapture.captureOverlay(binding,
                        new Runnable() {
                            @Override public void run() {
                                RenderGameOverlayEvent all = new RenderGameOverlayEvent(
                                        partialTicks, resolution, 0, 0);
                                postIsolated(bus, new RenderGameOverlayEvent.Post(all,
                                        RenderGameOverlayEvent.ElementType.ALL), binding, name,
                                        serverPlayer);
                                // RenderTickEvent(END) is where MCHeli (and any similarly-shaped
                                // mod) actually draws its vehicle HUD; fire it last so its
                                // geometry paints over every overlay element captured above,
                                // matching real Minecraft's frame order.
                                postIsolated(fmlBus, new TickEvent.RenderTickEvent(
                                        TickEvent.Phase.END, partialTicks), binding, name,
                                        serverPlayer);
                            }
                        }, true);
                return GlEmulationSession.concat(result, postAll);
            } catch (Throwable t) {
                LegacyInputDiag.log("overlay dispatch failed player=" + playerName + " cause="
                        + t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
                return new GlEmulationSession(false).seal();
            } finally {
                try {
                    if (entityWorldScope != null) entityWorldScope.restore();
                } finally {
                    try {
                        minecraftScope.restore();
                    } finally {
                        try { LegacyLwjglState.end(); }
                        finally { binding.restoreProxies(); }
                    }
                }
            }
        }
    }

    public static LegacyBridge.CameraState cameraState(String playerName) {
        return playerName == null ? null : CAMERA_STATES.get(playerName);
    }

    public static float thirdPersonDistance(String playerName) {
        if (playerName == null) {
            return Float.NaN;
        }
        Float distance = THIRD_PERSON_DISTANCES.get(playerName);
        return distance == null ? Float.NaN : distance.floatValue();
    }

    /**
     * EventBus.post stops at its exception handler after reporting a bad listener. Client-only
     * code is allowed to be incomplete in a headless facade, so isolate listeners while retaining
     * the bus's already-sorted listener order and the canonical event objects.
     */
    private static void postIsolated(EventBus bus, Event event, LegacyClientFacade.Binding binding,
            String playerName, EntityPlayer serverPlayer) {
        try {
            Field field = EventBus.class.getDeclaredField("busID");
            field.setAccessible(true);
            int busId = field.getInt(bus);
            IEventListener[] listeners = event.getListenerList().getListeners(busId);
            for (IEventListener listener : listeners) {
                if (!(listener instanceof EventPriority)) {
                    lastTickInvocations++;
                }
                logSubscriberDecision(event, listener, binding, playerName, serverPlayer);
                try {
                    LegacyClientFacade.ensureDisplayDimensions(binding.minecraft);
                    listener.invoke(event);
                    logSubscriberOutcome(event, listener, binding, playerName, serverPlayer);
                } catch (Throwable t) {
                    if (!(listener instanceof EventPriority)) {
                        lastTickFailures++;
                    }
                    Throwable cause = t;
                    if (cause instanceof java.lang.reflect.InvocationTargetException
                            && ((java.lang.reflect.InvocationTargetException) cause).getCause() != null) {
                        cause = ((java.lang.reflect.InvocationTargetException) cause).getCause();
                    }
                    String listenerDescription = listenerDescription(listener);
                    recordFailure(listenerDescription, cause);
                    String failureKey = causeKey(cause);
                    if (firstFailureDetail(failureKey)) {
                        LegacyInputDiag.log("client subscriber failed event="
                                + event.getClass().getName() + " listener=" + listenerDescription + " cause="
                                + cause.getClass().getName() + ":" + String.valueOf(cause.getMessage())
                                + " " + LegacyClientFacade.displayDiagnostic(
                                        binding == null ? null : binding.minecraft)
                                + " stack=" + stackSummary(cause));
                    }
                }
            }
        } catch (Throwable t) {
            if (firstFailureDetail("fallback:" + event.getClass().getName() + ":" + causeKey(t))) {
                LegacyInputDiag.log("client event dispatch fallback event=" + event.getClass().getName()
                        + " cause=" + t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
            }
            try {
                bus.post(event);
            } catch (Throwable ignored) {
                // The per-tick caller remains alive even when the fallback bus is incomplete.
            }
        }
    }

    /** Generic state snapshot around each client listener; rate-limited to keep live logs usable. */
    private static void logSubscriberDecision(Event event, IEventListener listener,
            LegacyClientFacade.Binding binding, String playerName, EntityPlayer serverPlayer) {
        String listenerName = listenerDescription(listener);
        recordDecision(listenerName);
        if (!DEBUG_CLIENT_DISPATCH) return;
        String key = "client-subscriber-decision:" + event.getClass().getName() + ":"
                + listenerName + ":" + System.identityHashCode(listener);
        if (!LegacyInputDiag.oncePer(key, DISPATCH_SUMMARY_NANOS)) return;
        logSubscriberState("decision", event, listener, binding, playerName, serverPlayer);
    }

    private static void logSubscriberOutcome(Event event, IEventListener listener,
            LegacyClientFacade.Binding binding, String playerName, EntityPlayer serverPlayer) {
        String listenerName = listenerDescription(listener);
        recordOutcome(listenerName);
        logListenerSignals(listener, playerName);
        if (!DEBUG_CLIENT_DISPATCH) return;
        String key = "client-subscriber-outcome:" + event.getClass().getName() + ":"
                + listenerName + ":" + System.identityHashCode(listener);
        if (!LegacyInputDiag.oncePer(key, DISPATCH_SUMMARY_NANOS)) return;
        logSubscriberState("returned", event, listener, binding, playerName, serverPlayer);
    }

    private static String listenerDescription(IEventListener listener) {
        return listener == null ? "null" : String.valueOf(listener);
    }

    /**
     * One bounded, generic view of handler-owned input state. Some legacy handlers keep their
     * key objects below a tick-handler array rather than on the event subscriber itself. This
     * follows only fields whose names describe input or handler collections; it never names a
     * mod class and never runs on every tick after the short rate-limit expires.
     */
    private static void logListenerSignals(IEventListener listener, String playerName) {
        String listenerName = listenerDescription(listener);
        String key = "client-input-signals:" + listenerName;
        if (!LegacyInputDiag.oncePer(key, DISPATCH_SUMMARY_NANOS)) {
            return;
        }
        try {
            String signals = traceSignalGraph(listenerTarget(listener));
            if (!"none".equals(signals)) {
                LegacyInputDiag.log("client input signals player=" + playerName
                        + " listener=" + listenerName + " " + signals);
            }
        } catch (Throwable ignored) {
            // Optional diagnostics must never affect client dispatch.
        }
    }

    private static String traceSignalGraph(Object root) {
        if (root == null) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        Set<Object> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<Object, Boolean>());
        int[] budget = {TRACE_FIELD_LIMIT};
        traceSignalObject(root, "root", 0, seen, budget, out);
        return out.length() == 0 ? "none" : out.toString();
    }

    private static void traceSignalObject(Object value, String path, int depth,
            Set<Object> seen, int[] budget, StringBuilder out) {
        if (value == null || depth > 3 || budget[0] <= 0 || !seen.add(value)) {
            return;
        }
        Class<?> cursor = value.getClass();
        if (cursor.isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < length && budget[0] > 0; i++) {
                traceSignalObject(java.lang.reflect.Array.get(value, i), path + "[" + i + "]",
                        depth + 1, seen, budget, out);
            }
            return;
        }
        for (; cursor != null && cursor != Object.class && budget[0] > 0;
                cursor = cursor.getSuperclass()) {
            Field[] fields;
            try {
                fields = cursor.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }
            for (Field field : fields) {
                if (budget[0] <= 0 || Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                boolean signal = name.indexOf("key") >= 0 || name.indexOf("press") >= 0
                        || name.indexOf("weapon") >= 0 || name.indexOf("mouse") >= 0
                        || name.indexOf("button") >= 0 || name.indexOf("use") >= 0;
                boolean branch = signal || name.indexOf("tick") >= 0 || name.indexOf("handler") >= 0
                        || name.indexOf("list") >= 0 || name.indexOf("array") >= 0;
                if (!branch) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object nested = field.get(value);
                    if (signal) {
                        if (out.length() > 0) {
                            out.append(' ');
                        }
                        out.append(path).append('.').append(field.getName()).append('=').append(traceValue(nested));
                    }
                    budget[0]--;
                    if (nested != null && !isScalar(nested)) {
                        traceSignalObject(nested, path + '.' + field.getName(), depth + 1,
                                seen, budget, out);
                    }
                } catch (Throwable ignored) {
                    // Optional diagnostics must never affect listener dispatch.
                }
            }
        }
    }

    private static boolean isScalar(Object value) {
        return value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof String || value.getClass().isEnum();
    }

    private static String causeKey(Throwable cause) {
        String message = String.valueOf(cause == null ? null : cause.getMessage());
        if (message.length() > 160) {
            message = message.substring(0, 160);
        }
        return (cause == null ? "null" : cause.getClass().getName()) + ":" + message;
    }

    private static String stackSummary(Throwable cause) {
        if (cause == null || cause.getStackTrace() == null) {
            return "(no stack)";
        }
        StringBuilder out = new StringBuilder();
        for (StackTraceElement frame : cause.getStackTrace()) {
            if (out.length() > 0) {
                out.append(" <- ");
            }
            out.append(frame.toString());
        }
        return out.length() == 0 ? "(empty stack)" : out.toString();
    }

    private static void recordDispatchTick() {
        synchronized (DISPATCH_DIAG_LOCK) {
            dispatchDiagTicks++;
        }
    }

    private static void recordDecision(String listener) {
        synchronized (DISPATCH_DIAG_LOCK) {
            stats(listener).decisions++;
        }
    }

    private static void recordOutcome(String listener) {
        synchronized (DISPATCH_DIAG_LOCK) {
            stats(listener).returned++;
        }
    }

    private static void recordFailure(String listener, Throwable cause) {
        synchronized (DISPATCH_DIAG_LOCK) {
            ListenerDispatchStats stats = stats(listener);
            stats.failed++;
            String key = causeKey(cause);
            Integer previous = stats.failures.get(key);
            stats.failures.put(key, Integer.valueOf(previous == null ? 1 : previous.intValue() + 1));
        }
    }

    private static ListenerDispatchStats stats(String listener) {
        ListenerDispatchStats stats = DISPATCH_LISTENER_STATS.get(listener);
        if (stats == null) {
            stats = new ListenerDispatchStats();
            DISPATCH_LISTENER_STATS.put(listener, stats);
        }
        return stats;
    }

    private static boolean firstFailureDetail(String key) {
        synchronized (DISPATCH_DIAG_LOCK) {
            return FIRST_FAILURE_DETAILS.add(key);
        }
    }

    private static void emitDispatchSummaryIfDue(String playerName) {
        String summary = null;
        synchronized (DISPATCH_DIAG_LOCK) {
            long now = System.nanoTime();
            if (now - dispatchDiagWindowStart < DISPATCH_SUMMARY_NANOS) {
                return;
            }
            StringBuilder out = new StringBuilder();
            out.append("client dispatch summary player=").append(playerName)
                    .append(" ticks=").append(dispatchDiagTicks)
                    .append(" walked=").append(dispatchDiagWalkedNodes)
                    .append(" subscribers=").append(registeredCount).append(" listeners={");
            boolean first = true;
            for (Map.Entry<String, ListenerDispatchStats> entry : DISPATCH_LISTENER_STATS.entrySet()) {
                if (!first) out.append("; ");
                first = false;
                ListenerDispatchStats stats = entry.getValue();
                out.append(entry.getKey()).append(" d=").append(stats.decisions)
                        .append(" r=").append(stats.returned).append(" f=").append(stats.failed);
                if (!stats.failures.isEmpty()) {
                    out.append(" causes=").append(stats.failures);
                }
            }
            out.append('}');
            summary = out.toString();
            DISPATCH_LISTENER_STATS.clear();
            dispatchDiagTicks = 0L;
            dispatchDiagWalkedNodes = 0L;
            dispatchDiagWindowStart = now;
        }
        LegacyInputDiag.log(summary);
    }

    private static final class ListenerDispatchStats {
        long decisions;
        long returned;
        long failed;
        final Map<String, Integer> failures = new LinkedHashMap<String, Integer>();
    }

    private static void logSubscriberState(String phase, Event event, IEventListener listener,
            LegacyClientFacade.Binding binding, String playerName, EntityPlayer serverPlayer) {
        String listenerDescription = String.valueOf(listener);
        Object clientRiding = binding == null || binding.player == null
                ? null : binding.player.field_70154_o;
        Object serverRiding = serverPlayer == null ? null : serverPlayer.field_70154_o;
        String riding = clientRiding == null ? "null"
                : clientRiding.getClass().getName() + "@" + System.identityHashCode(clientRiding);
        String serverRidingText = serverRiding == null ? "null"
                : serverRiding.getClass().getName() + "@" + System.identityHashCode(serverRiding);
        String keys = LegacyLwjglState.downCodes(playerName).toString();
        LegacyInputDiag.log("client subscriber " + phase + " event=" + event.getClass().getName()
                + " listener=" + listenerDescription
                + " player=" + (binding != null && binding.minecraft.field_71439_g != null)
                + " world=" + (binding != null && binding.minecraft.field_71441_e != null)
                + " riding=" + riding
                + " serverRiding=" + serverRidingText
                + " sameRiding=" + (clientRiding == serverRiding)
                + " focus=" + (binding != null && binding.minecraft.field_71415_G)
                + " gui=" + (binding == null || binding.minecraft.field_71462_r == null ? "null"
                        : binding.minecraft.field_71462_r.getClass().getName())
                + " keys=" + keys
                + " ridingState=" + traceRidingState(clientRiding));
    }

    /**
     * Bounded, generic diagnostics for state gates on a mounted object. The dispatcher must not
     * know any mod's field names; matching only common state-name fragments keeps this useful for
     * different vehicle/entity implementations while avoiding a deep object graph walk.
     */
    private static String traceRidingState(Object riding) {
        if (riding == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder();
        int seen = 0;
        int inspected = 0;
        int errors = 0;
        for (Class<?> cursor = riding.getClass(); cursor != null && cursor != Object.class
                && seen < TRACE_FIELD_LIMIT; cursor = cursor.getSuperclass()) {
            Field[] fields;
            try {
                fields = cursor.getDeclaredFields();
            } catch (Throwable ignored) {
                errors++;
                continue;
            }
            for (Field field : fields) {
                if (seen >= TRACE_FIELD_LIMIT) {
                    break;
                }
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                inspected++;
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                String typeName = field.getType().getName().toLowerCase(java.util.Locale.ROOT);
                boolean stateName = name.indexOf("info") >= 0 || name.indexOf("engine") >= 0
                        || name.indexOf("fuel") >= 0 || name.indexOf("destroy") >= 0
                        || name.indexOf("throttle") >= 0;
                boolean stateType = typeName.indexOf("info") >= 0;
                boolean primitiveSignal = field.getType().isPrimitive()
                        && (field.getType() == Boolean.TYPE || field.getType() == Integer.TYPE
                            || field.getType() == Long.TYPE || field.getType() == Float.TYPE
                            || field.getType() == Double.TYPE)
                        && (name.indexOf("engine") >= 0 || name.indexOf("fuel") >= 0
                            || name.indexOf("destroy") >= 0 || name.indexOf("throttle") >= 0);
                if (!stateName && !stateType && !primitiveSignal) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object value = field.get(riding);
                    if (out.length() > 0) {
                        out.append(',');
                    }
                    out.append(field.getName()).append('=').append(traceValue(value));
                    seen++;
                } catch (Throwable ignored) {
                    errors++;
                    // Optional diagnostics must never affect listener dispatch.
                }
            }
        }
        return out.length() == 0 ? "none inspected=" + inspected + " errors=" + errors : out.toString();
    }

    private static String traceValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof Character
                || value instanceof String) {
            return String.valueOf(value);
        }
        return value.getClass().getName() + "@" + System.identityHashCode(value);
    }

    /** Read-only diagnostics used by headless probes and tests. */
    public static long dispatchCount() {
        return dispatchCount;
    }

    public static int registeredCount() {
        return registeredCount;
    }

    public static int lastTickInvocations() {
        return lastTickInvocations;
    }

    public static int lastTickFailures() {
        return lastTickFailures;
    }

    private static void ensureRegistered(Minecraft minecraft) {
        if (discoveryComplete) {
            return;
        }
        long now = System.nanoTime();
        long interval = 250_000_000L;
        if (now - lastDiscoveryNanos < interval) {
            return;
        }
        synchronized (LegacyClientTickDispatcher.class) {
            now = System.nanoTime();
            if (now - lastDiscoveryNanos < interval) {
                return;
            }
            lastDiscoveryNanos = now;
            try {
                DiscoveryResult result = discoverAndRegister(minecraft);
                discoveryPasses++;
                boolean changed = result.sources != lastDiscoverySources
                        || result.candidates != lastDiscoveryCandidates
                        || registeredCount != lastDiscoveryRegistered;
                if (changed || registeredCount == 0 || discoveryPasses % 20L == 0L) {
                    LegacyInputDiag.log("client subscriber discovery complete pass="
                            + discoveryPasses + " sources=" + result.sources
                            + " candidates=" + result.candidates
                            + " subscribers=" + registeredCount);
                }
                lastDiscoverySources = result.sources;
                lastDiscoveryCandidates = result.candidates;
                lastDiscoveryRegistered = registeredCount;
                // FML's mod list and each ModContainer source are immutable after lifecycle
                // discovery. A successful source walk is therefore the only pass we need.
                discoveryComplete = result.sources > 0;
            } catch (Throwable t) {
                LegacyInputDiag.log("client subscriber discovery failed cause="
                        + t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
            }
        }
    }

    private static DiscoveryResult discoverAndRegister(Minecraft minecraft) {
        int sources = 0;
        int candidates = 0;
        List<ModContainer> containers = Loader.instance().getModList();
        for (ModContainer container : containers) {
            if (container == null) {
                continue;
            }
            File source = sourceFor(container);
            if (source == null) {
                continue;
            }
            sources++;
            ClassLoader preferredLoader = modClassLoader(container);
            for (String className : tickSubscriberClasses(source)) {
                candidates++;
                registerOne(className, minecraft, preferredLoader);
            }
        }
        return new DiscoveryResult(sources, candidates);
    }

    private static final class DiscoveryResult {
        final int sources;
        final int candidates;

        DiscoveryResult(int sources, int candidates) {
            this.sources = sources;
            this.candidates = candidates;
        }
    }

    /**
     * FML can expose a source before its mod class has a usable protection domain, and some
     * launchers expose the inverse. Try both locations so discovery is independent of launch
     * order and of which classloader supplied the ModContainer.
     */
    private static File sourceFor(ModContainer container) {
        try {
            File source = container.getSource();
            if (source != null && (source.isFile() || source.isDirectory())) {
                return source;
            }
        } catch (Throwable ignored) {
            // Fall through to the loaded mod's code source.
        }
        try {
            Object mod = container.getMod();
            if (mod == null) {
                return null;
            }
            CodeSource codeSource = mod.getClass().getProtectionDomain().getCodeSource();
            URL location = codeSource == null ? null : codeSource.getLocation();
            if (location == null || !"file".equalsIgnoreCase(location.getProtocol())) {
                return null;
            }
            File source = new File(location.toURI());
            return source.isFile() || source.isDirectory() ? source : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static ClassLoader modClassLoader(ModContainer container) {
        try {
            Object mod = container.getMod();
            if (mod != null && mod.getClass().getClassLoader() != null) {
                return mod.getClass().getClassLoader();
            }
        } catch (Throwable ignored) {
            // Use the shared FML resolution chain below.
        }
        return null;
    }

    private static List<String> tickSubscriberClasses(File source) {
        Map<String, String> superNames = new HashMap<String, String>();
        Set<String> annotatedOwners = new HashSet<String>();
        List<String> classNames = new ArrayList<String>();
        if (source.isDirectory()) {
            collectDirectoryClasses(source, source, superNames, annotatedOwners, classNames);
        } else try (JarFile jar = new JarFile(source)) {
            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.endsWith(".class") || name.indexOf('$') >= 0) {
                    continue;
                }
                String className = name.substring(0, name.length() - 6).replace('/', '.');
                try {
                    ClassReader reader = new ClassReader(jar.getInputStream(entry));
                    superNames.put(className, dotted(reader.getSuperName()));
                    if (hasClientAnnotation(reader)) {
                        annotatedOwners.add(className);
                    }
                    classNames.add(className);
                } catch (Throwable ignored) {
                    // One malformed/unsupported class must not prevent other client handlers.
                }
            }
        } catch (IOException ignored) {
            return Collections.emptyList();
        }

        List<String> result = new ArrayList<String>();
        for (String className : classNames) {
            String cursor = className;
            Set<String> seen = new HashSet<String>();
            while (cursor != null && seen.add(cursor)) {
                if (annotatedOwners.contains(cursor)) {
                    result.add(className);
                    break;
                }
                cursor = superNames.get(cursor);
            }
        }
        return result;
    }

    private static void collectDirectoryClasses(File root, File current,
            Map<String, String> superNames, Set<String> annotatedOwners,
            List<String> classNames) {
        File[] children = current.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectDirectoryClasses(root, child, superNames, annotatedOwners, classNames);
                continue;
            }
            String path = root.toURI().relativize(child.toURI()).getPath();
            if (!path.endsWith(".class") || path.indexOf('$') >= 0) {
                continue;
            }
            String className = path.substring(0, path.length() - 6).replace('/', '.');
            try (java.io.InputStream in = new java.io.FileInputStream(child)) {
                ClassReader reader = new ClassReader(in);
                superNames.put(className, dotted(reader.getSuperName()));
                if (hasClientAnnotation(reader)) {
                    annotatedOwners.add(className);
                }
                classNames.add(className);
            } catch (Throwable ignored) {
                // One malformed/unsupported class must not prevent other client handlers.
            }
        }
    }

    /**
     * Rebind roots = our discovered subscribers PLUS every object actually registered for client
     * tick/render events on the FML bus. With side=CLIENT, a mod's own client proxy registers its
     * handlers too; those instances captured an earlier facade Minecraft in their fields and
     * would otherwise read stale state (e.g. inGameHasFocus=false) forever.
     */
    private static List<Object> rebindRoots() {
        List<Object> roots = new ArrayList<Object>(CLIENT_SUBSCRIBERS);
        try {
            EventBus bus = cpw.mods.fml.common.FMLCommonHandler.instance().bus();
            int busId = getBusId(bus);
            Event base = new net.minecraftforge.client.event.RenderGameOverlayEvent(0.0F, null, 0, 0);
            Event[] probes = {new TickEvent.ClientTickEvent(TickEvent.Phase.START),
                    new TickEvent.ClientTickEvent(TickEvent.Phase.END),
                    new TickEvent.RenderTickEvent(TickEvent.Phase.START, 0.0F),
                    new net.minecraftforge.client.event.RenderGameOverlayEvent.Pre(
                            (net.minecraftforge.client.event.RenderGameOverlayEvent) base,
                            net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL),
                    new net.minecraftforge.client.event.RenderGameOverlayEvent.Post(
                            (net.minecraftforge.client.event.RenderGameOverlayEvent) base,
                            net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL)};
            java.util.Set<Object> seen = java.util.Collections.newSetFromMap(
                    new java.util.IdentityHashMap<Object, Boolean>());
            seen.addAll(CLIENT_SUBSCRIBERS);
            for (Event probe : probes) {
                IEventListener[] array = probe.getListenerList().getListeners(busId);
                for (IEventListener listener : array) {
                    Object target = cachedListenerTarget(listener, array);
                    if (target != null && seen.add(target)) {
                        roots.add(target);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Discovered subscribers alone remain a valid (older) root set.
        }
        return roots;
    }

    private static String describeRootMinecraft(Minecraft binding) {
        StringBuilder out = new StringBuilder("[");
        for (Object root : rebindRoots()) {
            for (Class<?> c = root.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())
                            || !Minecraft.class.isAssignableFrom(f.getType())) continue;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(root);
                        out.append(root.getClass().getSimpleName()).append('@')
                                .append(Integer.toHexString(System.identityHashCode(root)))
                                .append('.').append(f.getName()).append(v == binding ? "=binding" : v == null ? "=null" : "=STALE")
                                .append(' ');
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return out.append(']').toString();
    }

    /** A client tick/render listener already registered on the FML bus whose target is exactly type. */
    private static Object registeredTargetOfType(Class<?> type) {
        Object bestMatch = null;
        try {
            EventBus bus = cpw.mods.fml.common.FMLCommonHandler.instance().bus();
            int busId = getBusId(bus);
            Event base = new net.minecraftforge.client.event.RenderGameOverlayEvent(0.0F, null, 0, 0);
            Event[] probes = {new TickEvent.ClientTickEvent(TickEvent.Phase.START),
                    new TickEvent.ClientTickEvent(TickEvent.Phase.END),
                    new TickEvent.RenderTickEvent(TickEvent.Phase.START, 0.0F),
                    new net.minecraftforge.client.event.RenderGameOverlayEvent.Pre(
                            (net.minecraftforge.client.event.RenderGameOverlayEvent) base,
                            net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL),
                    new net.minecraftforge.client.event.RenderGameOverlayEvent.Post(
                            (net.minecraftforge.client.event.RenderGameOverlayEvent) base,
                            net.minecraftforge.client.event.RenderGameOverlayEvent.ElementType.ALL)};
            for (Event probe : probes) {
                IEventListener[] array = probe.getListenerList().getListeners(busId);
                for (IEventListener listener : array) {
                    Object target = cachedListenerTarget(listener, array);
                    if (target != null) {
                        if (target.getClass() == type) {
                            return target;
                        } else if (type.isInstance(target)) {
                            bestMatch = target;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall back to constructing our own subscriber.
        }
        return bestMatch;
    }

    private static volatile Field EVENT_BUS_ID_FIELD;
    private static int getBusId(EventBus bus) throws Exception {
        Field f = EVENT_BUS_ID_FIELD;
        if (f == null) {
            f = EventBus.class.getDeclaredField("busID");
            f.setAccessible(true);
            EVENT_BUS_ID_FIELD = f;
        }
        return f.getInt(bus);
    }

    private static final java.util.IdentityHashMap<IEventListener, Object> LISTENER_CACHE =
            new java.util.IdentityHashMap<IEventListener, Object>();

    private static Object cachedListenerTarget(IEventListener listener, IEventListener[] array) {
        synchronized (LISTENER_CACHE) {
            if (LISTENER_CACHE.containsKey(listener)) return LISTENER_CACHE.get(listener);
            if (LISTENER_CACHE.size() > 512) LISTENER_CACHE.clear(); // bounded loaded-listener cache
            Object target = computeListenerTarget(listener);
            LISTENER_CACHE.put(listener, target);
            return target;
        }
    }

    /** The subscriber instance behind an ASMEventHandler (its generated handler's instance field). */
    private static Object computeListenerTarget(IEventListener listener) {
        if (listener == null || listener instanceof EventPriority) {
            return null;
        }
        try {
            Field handlerField = listener.getClass().getDeclaredField("handler");
            handlerField.setAccessible(true);
            Object generated = handlerField.get(listener);
            if (generated == null) {
                return null;
            }
            Class<?> declaringClass = null;
            for (Field f : listener.getClass().getDeclaredFields()) {
                if (f.getType() == java.lang.reflect.Method.class) {
                    f.setAccessible(true);
                    java.lang.reflect.Method method = (java.lang.reflect.Method) f.get(listener);
                    if (method != null) declaringClass = method.getDeclaringClass();
                    break;
                }
            }
            Object bestCandidate = null;
            int candidates = 0;
            for (Field f : generated.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                f.setAccessible(true);
                Object value = f.get(generated);
                if (value != null) {
                    if (declaringClass != null && declaringClass.isAssignableFrom(f.getType())) {
                        return value;
                    }
                    bestCandidate = value;
                    candidates++;
                }
            }
            if (candidates == 1) return bestCandidate;
        } catch (Throwable ignored) {
            // Non-ASM listeners are covered by discovery.
        }
        return null;
    }

    /** Compatibility alias for the diagnostics path; discovery uses the cached implementation. */
    private static Object listenerTarget(IEventListener listener) {
        return computeListenerTarget(listener);
    }

    private static boolean hasClientAnnotation(ClassReader reader) {
        final boolean[] found = new boolean[1];
        reader.accept(new ClassVisitor(Opcodes.ASM4) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                if (descriptor.indexOf(CLIENT_TICK_DESC) < 0
                        && descriptor.indexOf(RENDER_TICK_DESC) < 0
                        && descriptor.indexOf(OVERLAY_DESC) < 0) {
                    return null;
                }
                return new MethodVisitor(Opcodes.ASM4) {
                    @Override
                    public org.objectweb.asm.AnnotationVisitor visitAnnotation(String desc,
                            boolean visible) {
                        if (SUBSCRIBE_DESC.equals(desc)) {
                            found[0] = true;
                        }
                        return null;
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static String dotted(String internal) {
        return internal == null ? null : internal.replace('/', '.');
    }

    private static void registerOne(String className, Minecraft minecraft, ClassLoader preferredLoader) {
        try {
            Class<?> type = forName(className, preferredLoader);
            int modifiers = type.getModifiers();
            if (Modifier.isAbstract(modifiers) || type.isInterface()
                    || !hasRuntimeClientSubscriber(type)) {
                return;
            }
            // With side=CLIENT a mod's own client proxy registers its handlers itself. Adopt that
            // instance instead of constructing a second one: two live handlers run every client
            // tick twice (live: MCHeli fired 2x because two MCH_ClientCommonTickHandlers ticked).
            Object existing = registeredTargetOfType(type);
            if (existing != null) {
                synchronized (CLIENT_SUBSCRIBERS) {
                    if (!CLIENT_SUBSCRIBERS.contains(existing)) {
                        CLIENT_SUBSCRIBERS.add(existing);
                    }
                }
                seedStaticSingleton(type, existing);
                LegacyInputDiag.log("client subscriber adopted (mod-registered) " + className);
                return;
            }
            Object instance = instantiate(type, minecraft);
            if (instance == null) {
                return;
            }
            seedStaticSingleton(type, instance);
            synchronized (CLIENT_SUBSCRIBERS) {
                CLIENT_SUBSCRIBERS.add(instance);
            }
            EventBus[] buses = new EventBus[] { FMLCommonHandler.instance().bus(),
                    MinecraftForge.EVENT_BUS };
            for (EventBus bus : buses) {
                String registrationKey = className + "@" + System.identityHashCode(type.getClassLoader())
                        + ":" + System.identityHashCode(bus);
                if (REGISTERED.add(registrationKey)) bus.register(instance);
            }
            registeredCount++;
            LegacyInputDiag.log("client subscriber registered " + className);
        } catch (Throwable t) {
            Throwable cause = t;
            if (cause instanceof java.lang.reflect.InvocationTargetException
                    && ((java.lang.reflect.InvocationTargetException) cause).getCause() != null) {
                cause = ((java.lang.reflect.InvocationTargetException) cause).getCause();
            }
            LegacyInputDiag.log("client subscriber skipped " + className + " cause="
                    + cause.getClass().getName() + ":" + String.valueOf(cause.getMessage()));
        }
    }

    /**
     * Client proxies commonly publish the handler they just constructed through a static
     * singleton.  The server-side boot selected the common proxy, so discovery constructs the
     * client handler directly; seed only an empty same-type singleton slot to preserve that
     * ordinary client registration contract without naming a mod or proxy.
     */
    private static void seedStaticSingleton(Class<?> type, Object instance) {
        for (Class<?> cursor = type; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Field field : cursor.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (!Modifier.isStatic(modifiers) || !field.getType().isInstance(instance)) {
                    continue;
                }
                String name = field.getName();
                if (!"instance".equals(name) && !"INSTANCE".equals(name)) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    if (field.get(null) == null) {
                        field.set(null, instance);
                    }
                } catch (Throwable ignored) {
                    // A client singleton is optional; discovery must remain additive.
                }
            }
        }
    }

    private static Class<?> forName(String className, ClassLoader preferredLoader)
            throws ClassNotFoundException {
        if (preferredLoader != null) {
            try {
                return Class.forName(className, true, preferredLoader);
            } catch (ClassNotFoundException ignored) {
                // Fall through to the established FML/context-loader chain.
            }
        }
        return LegacyModClasses.forName(className);
    }

    private static boolean hasRuntimeClientSubscriber(Class<?> type) {
        for (Class<?> cursor = type; cursor != null; cursor = cursor.getSuperclass()) {
            for (java.lang.reflect.Method method : cursor.getDeclaredMethods()) {
                if (!method.isAnnotationPresent(SubscribeEvent.class)) {
                    continue;
                }
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1
                        && (parameters[0].getName().equals(
                                "cpw.mods.fml.common.gameevent.TickEvent$ClientTickEvent")
                            || parameters[0].getName().equals(
                                "cpw.mods.fml.common.gameevent.TickEvent$RenderTickEvent")
                            || parameters[0].getName().equals(
                                "net.minecraftforge.client.event.RenderGameOverlayEvent$Pre")
                            || parameters[0].getName().equals(
                                "net.minecraftforge.client.event.RenderGameOverlayEvent$Post"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object instantiate(Class<?> type, Minecraft minecraft) throws Exception {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            Object[] arguments = new Object[parameterTypes.length];
            boolean usable = true;
            for (int i = 0; i < parameterTypes.length; i++) {
                arguments[i] = constructorArgument(parameterTypes[i], minecraft);
                if (arguments[i] == null && parameterTypes[i].isPrimitive()) {
                    usable = false;
                    break;
                }
                if (arguments[i] == null && parameterTypes[i] != Object.class) {
                    usable = false;
                    break;
                }
            }
            if (!usable) {
                continue;
            }
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        }
        return null;
    }

    private static Object constructorArgument(Class<?> type, Minecraft minecraft) {
        if (type.isAssignableFrom(Minecraft.class)) {
            return minecraft;
        }
        return staticValue(type);
    }

    private static Object staticValue(Class<?> type) {
        try {
            for (ModContainer container : Loader.instance().getModList()) {
                Object mod = container.getMod();
                if (mod == null) {
                    continue;
                }
                for (Class<?> cursor = mod.getClass(); cursor != null; cursor = cursor.getSuperclass()) {
                    for (Field field : cursor.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers())
                                || !type.isAssignableFrom(field.getType())) {
                            continue;
                        }
                        field.setAccessible(true);
                        Object value = field.get(null);
                        if (value != null) {
                            return value;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Constructor discovery is best effort per handler.
        }
        return null;
    }

    private static String playerName(EntityPlayer player) {
        if (player == null) {
            return null;
        }
        try {
            return player.func_70005_c_();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
