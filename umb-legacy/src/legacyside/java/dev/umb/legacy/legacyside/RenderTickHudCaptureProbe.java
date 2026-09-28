package dev.umb.legacy.legacyside;

import java.util.Collections;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.EventBus;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;

import dev.umb.bridge.api.GlEmulationSession;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;
import dev.umb.legacy.legacyside.render.LegacyRenderCapture;

/**
 * Proves the overlay capture posts {@code TickEvent.RenderTickEvent}: some mods draw
 * their vehicle HUD from a {@code RenderTickEvent} END-phase handler instead of the
 * Forge overlay events, and that event must fire inside an active capture session or
 * the handler's GL calls are silently discarded and the mesh misses the HUD geometry.
 *
 * <p>Mod-agnostic: it registers a plain {@code @SubscribeEvent} listener for
 * {@code RenderTickEvent} on both buses production uses, calls render and asserts
 * the resulting mesh contains the geometry the listener emitted from the END phase.
 * The listener also sends a client-to-server packet, like real HUD handlers do, and
 * the probe asserts it was delivered, not dropped.</p>
 *
 * <p>Driven like {@link M1Probe} - a full real FML/Forge boot inside the isolated
 * {@code LegacyLoader} universe, run as a separate JVM subprocess by
 * {@code dev.umb.legacy.boot.M1ProbeMain}. No third-party mod jar is needed: the
 * event-bus mechanism does not depend on any specific mod being present.</p>
 */
public final class RenderTickHudCaptureProbe {

    private RenderTickHudCaptureProbe() {
    }

    public static String run() throws Exception {
        LegacyBridge bridge = new LegacyBridgeImpl();
        M1Probe.FakeHostWorld world = new M1Probe.FakeHostWorld();
        bridge.boot(world);
        if (!bridge.isBooted()) {
            throw new IllegalStateException("isBooted() false after boot() returned");
        }

        M1Probe.FakeHostPlayer hostPlayer = new M1Probe.FakeHostPlayer();
        bridge.syncPlayers(Collections.singletonList((HostPlayer) hostPlayer));

        MarkerHudListener listener = new MarkerHudListener();
        EventBus fmlBus = FMLCommonHandler.instance().bus();
        fmlBus.register(listener);
        MinecraftForge.EVENT_BUS.register(listener);
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<MarkerPacket, IMessage>() {
            @Override
            public IMessage onMessage(MarkerPacket message, MessageContext context) {
                listener.packetDelivered = true;
                return null;
            }
        }, MarkerPacket.class, 9001, Side.SERVER);

        // bridge.renderHud (not LegacyClientTickDispatcher.renderOverlay directly) so this
        // exercises LegacyBridgeImpl.renderHud's own bindClientPlayer/clearClientPlayer wrap -
        // the actual fix for the live "dropped unresolved=sender" report - not just the
        // dispatcher's event posting.
        GlEmulationSession.Mesh mesh;
        try {
            mesh = bridge.renderHud(hostPlayer.getName(), 0.5F, 320, 240);
        } finally {
            fmlBus.unregister(listener);
            MinecraftForge.EVENT_BUS.unregister(listener);
        }

        if (!listener.sawStart) {
            throw new IllegalStateException("RenderTickEvent(START) never reached a listener "
                    + "registered on the FML bus during renderOverlay");
        }
        if (!listener.sawEnd) {
            throw new IllegalStateException("RenderTickEvent(END) never reached a listener "
                    + "registered on the FML bus during renderOverlay");
        }
        if (mesh == null) {
            throw new IllegalStateException("bridge.renderHud returned null");
        }
        boolean found = false;
        for (GlEmulationSession.Draw draw : mesh.draws) {
            if (MarkerHudListener.MARKER_TEXTURE.equals(draw.texture)) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalStateException("a quad emitted from a RenderTickEvent(END) handler "
                    + "is missing from the mesh renderOverlay returned - this is the exact gap "
                    + "that left MCHeli's vehicle HUD undrawn (see class javadoc)");
        }
        if (!listener.packetDelivered) {
            throw new IllegalStateException("a client->server packet sent from a "
                    + "RenderTickEvent(END) handler during bridge.renderHud(...) was not "
                    + "delivered - the exact \"dropped unresolved=sender\" symptom reported live "
                    + "while riding (see class javadoc)");
        }

        bridge.shutdown();
        return "HUD-OK\nsawStart=" + listener.sawStart + " sawEnd=" + listener.sawEnd
                + " draws=" + mesh.draws.size() + " vertices=" + mesh.vertexCount()
                + " markerFound=" + found + " packetDelivered=" + listener.packetDelivered + "\n";
    }

    /** A minimal, real (non-mock) Forge client subscriber shaped exactly like MCHeli's. */
    public static final class MarkerHudListener {
        static final String MARKER_TEXTURE = "umb-test:render-tick-marker";

        boolean sawStart;
        boolean sawEnd;
        volatile boolean packetDelivered;

        @SubscribeEvent
        public void onRenderTick(TickEvent.RenderTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                sawStart = true;
                return;
            }
            sawEnd = true;
            // Exactly the entry points the legacy GL11/Tessellator bytecode transform redirects
            // real (transformed) mod immediate-mode draw calls to.
            LegacyRenderCapture.bindTexture(new ResourceLocation("umb-test", "render-tick-marker"));
            LegacyRenderCapture.begin(GlEmulationSession.GL_QUADS);
            LegacyRenderCapture.vertex(0, 0, 0, 0, 0);
            LegacyRenderCapture.vertex(10, 0, 0, 1, 0);
            LegacyRenderCapture.vertex(10, 10, 0, 1, 1);
            LegacyRenderCapture.vertex(0, 10, 0, 0, 1);
            LegacyRenderCapture.draw();
            // Mirrors MCHeli's own MCH_PacketIndRotation/MCH_PacketIndNotifyAmmoNum: a real HUD
            // draw handler sending a client->server packet as a side effect of drawing.
            LegacyNetworkLoopback.captureClientToServer(new MarkerPacket());
        }
    }

    /** Trivial real IMessage: only the delivery path is under test, not payload content. */
    public static final class MarkerPacket implements IMessage {
        public MarkerPacket() {
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
        }

        @Override
        public void toBytes(ByteBuf buffer) {
        }
    }
}
