package dev.umb.legacy.test;

import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import org.junit.jupiter.api.Test;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.entity.player.EntityPlayerMP;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import com.mojang.authlib.GameProfile;

/** Regression coverage for the client-to-server wire boundary. */
public final class LegacyWireDefensiveCopyTest {
    @Test
    public void serializesAFieldCopyAndLeavesOriginalUntouched() throws Exception {
        final AtomicInteger seen = new AtomicInteger(-1);
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<MutatingMessage, IMessage>() {
            @Override
            public IMessage onMessage(MutatingMessage message, MessageContext context) {
                seen.set(message.value);
                return null;
            }
        }, MutatingMessage.class, 901, Side.SERVER);

        MutatingMessage original = new MutatingMessage(7);
        LegacyNetworkLoopback.bindClientPlayer(testPlayer());
        LegacyNetworkLoopback.captureClientToServer(original);
        LegacyNetworkLoopback.clearClientPlayer();

        assertEquals(7, seen.get());
        assertEquals(1, MutatingMessage.toBytesCalls.get(), "toBytes ran on the defensive copy");
        assertEquals(7, original.value);
    }

    @Test
    public void failedWireCopyDisablesFutureCopiesForThatClass() throws Exception {
        final AtomicInteger delivered = new AtomicInteger();
        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<BrokenMessage, IMessage>() {
            @Override
            public IMessage onMessage(BrokenMessage message, MessageContext context) {
                delivered.incrementAndGet();
                return null;
            }
        }, BrokenMessage.class, 902, Side.SERVER);

        LegacyNetworkLoopback.bindClientPlayer(testPlayer());
        LegacyNetworkLoopback.captureClientToServer(new BrokenMessage());
        LegacyNetworkLoopback.captureClientToServer(new BrokenMessage());
        LegacyNetworkLoopback.clearClientPlayer();

        assertEquals(2, delivered.get());
        assertEquals(1, BrokenMessage.toBytesCalls.get(),
                "the failed class is placed on the no-copy set");
    }

    public static final class MutatingMessage implements IMessage {
        static final AtomicInteger toBytesCalls = new AtomicInteger();
        int value;

        public MutatingMessage() {}

        MutatingMessage(int value) { this.value = value; }

        @Override
        public void fromBytes(ByteBuf buf) { value = buf.readInt(); }

        @Override
        public void toBytes(ByteBuf buf) {
            toBytesCalls.incrementAndGet();
            buf.writeInt(value);
            value = 99;
        }
    }

    public static final class BrokenMessage implements IMessage {
        static final AtomicInteger toBytesCalls = new AtomicInteger();

        @Override
        public void fromBytes(ByteBuf buf) { throw new IllegalStateException("intentional test failure"); }

        @Override
        public void toBytes(ByteBuf buf) {
            toBytesCalls.incrementAndGet();
            buf.writeInt(1);
        }
    }

    private static EntityPlayerMP testPlayer() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        Object unsafe = singleton.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        EntityPlayerMP player = (EntityPlayerMP) allocate.invoke(unsafe, EntityPlayerMP.class);
        Field profile = net.minecraft.entity.player.EntityPlayer.class
                .getDeclaredField("field_146106_i");
        Method offset = unsafeClass.getMethod("objectFieldOffset", Field.class);
        long off = ((Long) offset.invoke(unsafe, profile)).longValue();
        Method put = unsafeClass.getMethod("putObject", Object.class, Long.TYPE, Object.class);
        put.invoke(unsafe, player, off, new GameProfile(UUID.randomUUID(), "wire-test"));
        return player;
    }
}
