package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.tileentity.TileEntity;

import dev.umb.legacy.legacyside.input.LegacyInputDispatcher;
import dev.umb.legacy.legacyside.input.LegacyInputRecord;
import dev.umb.legacy.legacyside.LegacyClientFacade;
import dev.umb.legacy.legacyside.UmbWorld;
import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;

/** Boot-free tests for the host entry point and generic IMessage loopback. */
class LegacyInputNetworkLoopbackTest {
    @AfterEach
    void clear() {
        LegacyNetworkLoopback.drainClientMessages();
        LegacyNetworkLoopback.clearClientPlayer();
        LegacyNetworkLoopback.setServerContextProvider(null);
    }

    @Test
    void inputRecordCopiesKeysAndStackBoundary() {
        LegacyInputRecord record = new LegacyInputRecord(7L, null, true, false, true,
                true, false, 3, 12.0F, -4.0F, 0.0D, 0.0D, -1.0D, null,
                Collections.singleton("legacy:key:test:1:cat"));
        assertEquals(7L, record.tick());
        assertTrue(record.useHeld());
        assertTrue(record.pressedKeys().contains("legacy:key:test:1:cat"));
        assertFalse(record.attackHeld());
    }

    @Test
    void registeredServerHandlerReceivesSyntheticPacket() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger seen = new AtomicInteger();
        final EntityPlayerMP player = allocate(EntityPlayerMP.class);
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<TestMessage, IMessage>() {
            @Override
            public IMessage onMessage(TestMessage message, MessageContext context) {
                calls.incrementAndGet();
                seen.set(message.value);
                assertNotNull(context);
                assertEquals(Side.SERVER, context.side);
                return null;
            }
        }, TestMessage.class, 19, Side.SERVER);

        LegacyNetworkLoopback.deliverToServer(new TestMessage(42), player);
        assertEquals(1, calls.get());
        assertEquals(42, seen.get());
    }

    @Test
    void capturedClientPacketUsesTheFacadePlayerServerContext() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final EntityPlayerMP player = allocate(EntityPlayerMP.class);
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<TestMessage, IMessage>() {
            @Override
            public IMessage onMessage(TestMessage message, MessageContext context) {
                calls.incrementAndGet();
                assertNotNull(context);
                assertNotNull(context.getServerHandler(),
                        "client-originated packets must not use a null server handler");
                return null;
            }
        }, TestMessage.class, 21, Side.SERVER);

        LegacyNetworkLoopback.bindClientPlayer(player);
        LegacyNetworkLoopback.captureClientToServer(new TestMessage(43));
        assertEquals(0, calls.get(), "delivery waits until the client dispatch ends");
        LegacyNetworkLoopback.clearClientPlayer();
        assertEquals(1, calls.get());
    }

    @Test
    void outOfBandGuiPacketUsesTheFacadePlayerServerContext() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final EntityPlayerMP player = allocate(EntityPlayerMP.class);
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<TestMessage, IMessage>() {
            @Override
            public IMessage onMessage(TestMessage message, MessageContext context) {
                calls.incrementAndGet();
                assertNotNull(context.getServerHandler(),
                        "GUI-originated packets must retain the facade player context");
                return null;
            }
        }, TestMessage.class, 22, Side.SERVER);

        assertTrue(LegacyNetworkLoopback.bindClientPlayerIfAbsent(player));
        LegacyNetworkLoopback.captureClientToServer(new TestMessage(44));
        assertEquals(0, calls.get(), "GUI packets stay queued until the client facade is restored");
        LegacyNetworkLoopback.clearClientPlayer();
        assertEquals(1, calls.get());
    }

    @Test
    void facadeScopeResolvesClientPacketSenderWhenTickBindingIsAbsent() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final EntityPlayerMP player = allocate(EntityPlayerMP.class);
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<TestMessage, IMessage>() {
            @Override
            public IMessage onMessage(TestMessage message, MessageContext context) {
                calls.incrementAndGet();
                assertNotNull(context.getServerHandler());
                assertSame(player, context.getServerHandler().field_147369_b);
                return null;
            }
        }, TestMessage.class, 25, Side.SERVER);

        EntityPlayerMP previous = LegacyNetworkLoopback.bindFacadePlayer(player);
        try {
            LegacyNetworkLoopback.captureClientToServer(new TestMessage(45));
        } finally {
            LegacyNetworkLoopback.restoreFacadePlayer(previous);
        }
        assertEquals(1, calls.get());
    }

    @Test
    void serverClientMessagesAreQueuedForEffectsLane() {
        LegacyNetworkLoopback.captureServerToClient(new TestMessage(9));
        List<LegacyNetworkLoopback.ClientMessage> messages =
                LegacyNetworkLoopback.drainClientMessages();
        assertEquals(1, messages.size());
        assertEquals("server->client", messages.get(0).route());
        assertTrue(messages.get(0).payload() instanceof TestMessage);
    }

    @Test
    void serverToClientUpdatesTheClientTileTwin() {
        UmbFacadeTest.FakeHostWorld host = new UmbFacadeTest.FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        SyncTile serverTile = new SyncTile();
        serverTile.func_145834_a(serverWorld);
        serverTile.field_145851_c = 4;
        serverTile.field_145848_d = 5;
        serverTile.field_145849_e = 6;
        serverTile.value = 7;
        serverWorld.putTile(4, 5, 6, serverTile);

        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, serverWorld);
        TileEntity clientTile = binding.world.func_147438_o(4, 5, 6);
        assertNotNull(clientTile);
        assertFalse(clientTile == serverTile, "client packet delivery must target a twin");

        serverTile.value = 42;
        LegacyNetworkLoopback.captureVanillaTileUpdates(serverWorld);
        assertEquals(1, LegacyNetworkLoopback.deliverClientMessages(binding));
        assertEquals(42, ((SyncTile) clientTile).value);

        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<TestTileMessage, IMessage>() {
            @Override
            public IMessage onMessage(TestTileMessage message, MessageContext context) {
                TileEntity tile = Minecraft.func_71410_x().field_71441_e
                        .func_147438_o(message.x, message.y, message.z);
                ((SyncTile) tile).value = message.value;
                return null;
            }
        }, TestTileMessage.class, 91, Side.CLIENT);
        LegacyNetworkLoopback.captureServerToClient(new TestTileMessage(4, 5, 6, 99));
        assertEquals(1, LegacyNetworkLoopback.deliverClientMessages(binding));
        assertEquals(99, ((SyncTile) clientTile).value);

        LegacyClientFacade.Binding nextBinding = LegacyClientFacade.install(null, serverWorld);
        assertSame(clientTile, nextBinding.world.func_147438_o(4, 5, 6));
        // A subsequent client-tick rebind restores the server-authoritative value. The synthetic
        // test packet deliberately writes 99 without changing the server's 42, so it must not
        // make the persistent twin diverge forever from its authoritative source.
        assertEquals(42, ((SyncTile) nextBinding.world.func_147438_o(4, 5, 6)).value);
    }

    @Test
    void clientTwinRebindCarriesTransientPrimitiveTileStateWithoutASeparatePacket() {
        UmbFacadeTest.FakeHostWorld host = new UmbFacadeTest.FakeHostWorld();
        UmbWorld serverWorld = UmbWorld.create(host, 0);
        SyncTile serverTile = new SyncTile();
        serverTile.func_145834_a(serverWorld);
        serverTile.field_145851_c = 7;
        serverTile.field_145848_d = 8;
        serverTile.field_145849_e = 9;
        serverTile.value = 11;
        serverTile.power = 11L;
        serverWorld.putTile(7, 8, 9, serverTile);

        LegacyClientFacade.Binding first = LegacyClientFacade.install(null, serverWorld);
        SyncTile clientTile = (SyncTile) first.world.func_147438_o(7, 8, 9);
        assertNotNull(clientTile);
        serverTile.value = 100000;

        LegacyClientFacade.Binding second = LegacyClientFacade.install(null, serverWorld);
        assertSame(clientTile, second.world.func_147438_o(7, 8, 9));
        assertEquals(100000, ((SyncTile) second.world.func_147438_o(7, 8, 9)).value);
        serverTile.power = 100000L;
        LegacyClientFacade.Binding third = LegacyClientFacade.install(null, serverWorld);
        assertEquals(100000L, ((SyncTile) third.world.func_147438_o(7, 8, 9)).power);
    }

    @Test
    void derivedPacketPlanOnlyFiresForPressedStableId() throws Exception {
        final AtomicInteger calls = new AtomicInteger();
        final EntityPlayerMP player = allocate(EntityPlayerMP.class);
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<TestMessage, IMessage>() {
            @Override
            public IMessage onMessage(TestMessage message, MessageContext context) {
                calls.incrementAndGet();
                return null;
            }
        }, TestMessage.class, 20, Side.SERVER);
        LegacyInputRecord record = new LegacyInputRecord(1L, player, false, false, false,
                false, false, 0, 0, 0, 0, 0, 0, null,
                Collections.singleton("legacy:key:fire:1:cat"));
        LegacyInputDispatcher.sendDerived("legacy:key:fire:1:cat", record, true,
                (input, pressed) -> new TestMessage(1));
        assertEquals(1, calls.get());
    }

    /**
 * MCHeli registers its handler for a base packet class and sends subclasses
 */
    @Test
    void serverHandlerRegisteredForBaseClassReceivesSubclassPackets() throws Exception {
        final AtomicInteger seen = new AtomicInteger();
        final EntityPlayerMP player = allocate(EntityPlayerMP.class);
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<BasePacket, IMessage>() {
            @Override
            public IMessage onMessage(BasePacket message, MessageContext context) {
                seen.set(message.value);
                return null;
            }
        }, BasePacket.class, 23, Side.SERVER);

        EntityPlayerMP previous = LegacyNetworkLoopback.bindFacadePlayer(player);
        try {
            LegacyNetworkLoopback.captureClientToServer(new SubPacket(7));
        } finally {
            LegacyNetworkLoopback.restoreFacadePlayer(previous);
        }
        assertEquals(7, seen.get());
        LegacyNetworkLoopback.deliverToServer(new SubPacket(9), player);
        assertEquals(9, seen.get());
    }

    /** Packets sent during the client dispatch are delivered after it ends (server world restored). */
    @Test
    void clientToServerPacketsDuringClientDispatchAreDeliveredAfterIt() throws Exception {
        final AtomicInteger seen = new AtomicInteger();
        final EntityPlayerMP player = allocate(EntityPlayerMP.class);
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<BasePacket, IMessage>() {
            @Override
            public IMessage onMessage(BasePacket message, MessageContext context) {
                seen.set(message.value);
                return null;
            }
        }, BasePacket.class, 24, Side.SERVER);
        LegacyNetworkLoopback.bindClientPlayer(player);
        LegacyNetworkLoopback.captureClientToServer(new SubPacket(3));
        assertEquals(0, seen.get(), "client dispatch packets remain queued until it ends");
        LegacyNetworkLoopback.clearClientPlayer();
        assertEquals(3, seen.get(), "deferred packet delivers after the client dispatch");
    }

    public static class BasePacket implements IMessage {
        int value;
        public BasePacket() { this(0); }
        BasePacket(int value) { this.value = value; }
        // Symmetric with toBytes: c2s delivery replays the wire through a fresh instance.
        @Override public void fromBytes(ByteBuf buffer) { value = buffer.readInt(); }
        @Override public void toBytes(ByteBuf buffer) { buffer.writeInt(value); }
    }

    public static final class SubPacket extends BasePacket {
        public SubPacket() { super(); }
        SubPacket(int value) { super(value); }
    }

    public static final class TestMessage implements IMessage {
        int value;
        TestMessage() { this(0); }
        TestMessage(int value) { this.value = value; }
        @Override public void fromBytes(ByteBuf buffer) { value = buffer.readInt(); }
        @Override public void toBytes(ByteBuf buffer) { buffer.writeInt(value); }
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafe, type));
    }

    public static final class TestTileMessage implements IMessage {
        int x;
        int y;
        int z;
        int value;
        TestTileMessage() { this(0, 0, 0, 0); }
        TestTileMessage(int x, int y, int z, int value) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.value = value;
        }
        @Override public void fromBytes(ByteBuf buffer) {
            x = buffer.readInt();
            y = buffer.readInt();
            z = buffer.readInt();
            value = buffer.readInt();
        }
        @Override public void toBytes(ByteBuf buffer) {
            buffer.writeInt(x);
            buffer.writeInt(y);
            buffer.writeInt(z);
            buffer.writeInt(value);
        }
    }

    public static final class SyncTile extends TileEntity {
        int value;
        long power;

        @Override
        public void func_145839_a(NBTTagCompound tag) {
            super.func_145839_a(tag);
            value = tag.func_74762_e("value");
            power = tag.func_74763_f("power");
        }

        @Override
        public void func_145841_b(NBTTagCompound tag) {
            super.func_145841_b(tag);
            tag.func_74768_a("value", value);
            tag.func_74772_a("power", power);
        }

        @Override
        public Packet func_145844_m() {
            NBTTagCompound tag = new NBTTagCompound();
            tag.func_74768_a("value", value);
            tag.func_74772_a("power", power);
            return new S35PacketUpdateTileEntity(field_145851_c, field_145848_d,
                    field_145849_e, 1, tag);
        }

        @Override
        public void onDataPacket(NetworkManager manager, S35PacketUpdateTileEntity packet) {
            value = packet.func_148857_g().func_74762_e("value");
            power = packet.func_148857_g().func_74763_f("power");
        }
    }
}
