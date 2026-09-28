package dev.umb.legacy.legacyside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import cpw.mods.fml.common.network.IGuiHandler;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Container;
import net.minecraft.world.World;

import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;

/**
 * End-to-end headless regression for a legacy machine mode button.
 *
 * <p>The fixture has the same panel-relative hit rectangle and five-int packet shape used by
 * HBM's fluid-tank button.  It deliberately enters through the real dispatcher, so the test
 * covers the persistent GuiContainer click, the custom-wrapper c2s capture, wire replay, and the
 * player-bound SERVER MessageContext in one assertion.</p>
 */
class LegacyGuiTankButtonLoopbackTest {
    private static final int PACKET_ID = 127;

    @AfterEach
    void clear() throws Exception {
        LegacyNetworkLoopback.drainClientMessages();
        LegacyNetworkLoopback.clearClientPlayer();
        LegacyNetworkLoopback.setServerContextProvider(null);
        setStatic(LegacyGuiMouseDispatcher.class, "activeSession", null);
        setStatic(UmbGui.class, "lastContext", null);
    }

    @Test
    void tankModeGuiClickReachesBoundServerHandler() throws Exception {
        EntityPlayerMP player = allocate(EntityPlayerMP.class);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger seenX = new AtomicInteger();
        AtomicInteger seenValue = new AtomicInteger();
        AtomicInteger seenId = new AtomicInteger();

        LegacyNetworkLoopback.registerSimpleMessage(new IMessageHandler<TankModePacket, IMessage>() {
            @Override
            public IMessage onMessage(TankModePacket message, MessageContext context) {
                calls.incrementAndGet();
                assertNotNull(context);
                assertNotNull(context.getServerHandler());
                assertSame(player, context.getServerHandler().field_147369_b);
                seenX.set(message.x);
                seenValue.set(message.value);
                seenId.set(message.id);
                return null;
            }
        }, TankModePacket.class, PACKET_ID, Side.SERVER);

        TankModeGui gui = new TankModeGui();
        UmbGui.GuiContext context = new UmbGui.GuiContext(new FixtureGuiHandler(gui), 7, player,
                null, 0, 0, 0);
        setStatic(UmbGui.class, "lastContext", context);
        LegacyGuiMouseDispatcher.open(context);

        // The real tank button is x=151..168, y=35..52 in the legacy panel.  With a zero panel
        // origin, this is the exact center used by the live gui_click probe: (160,43).
        assertTrue(LegacyGuiMouseDispatcher.dispatch(TankModeGui.class.getName(), 12, 81, -3,
                160, 43, 0, 160, 43));

        assertEquals(1, calls.get(), "the click must reach the server handler after facade restore");
        assertEquals(12, seenX.get());
        assertEquals(0, seenValue.get());
        assertEquals(0, seenId.get());
    }

    private static final class FixtureGuiHandler implements IGuiHandler {
        private final GuiContainer gui;

        FixtureGuiHandler(GuiContainer gui) {
            this.gui = gui;
        }

        @Override
        public Object getServerGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
            return null;
        }

        @Override
        public Object getClientGuiElement(int id, EntityPlayer player, World world, int x, int y, int z) {
            return gui;
        }
    }

    private static final class TankModeGui extends GuiContainer {
        TankModeGui() {
            super(new Container() {
                @Override
                public boolean func_75145_c(EntityPlayer player) {
                    return true;
                }
            });
        }

        @Override
        protected void func_73864_a(int mouseX, int mouseY, int button) {
            if (mouseX >= field_147003_i + 151 && mouseX < field_147003_i + 169
                    && mouseY >= field_147009_r + 35 && mouseY <= field_147009_r + 53) {
                LegacyNetworkLoopback.captureClientToServer(new TankModePacket(12, 81, -3, 0, 0));
            }
        }

        @Override
        protected void func_146976_a(float partialTicks, int mouseX, int mouseY) {
        }
    }

    public static final class TankModePacket implements IMessage {
        int x;
        int y;
        int z;
        int value;
        int id;

        public TankModePacket() {
        }

        TankModePacket(int x, int y, int z, int value, int id) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.value = value;
            this.id = id;
        }

        @Override
        public void fromBytes(ByteBuf buffer) {
            x = buffer.readInt();
            y = buffer.readInt();
            z = buffer.readInt();
            value = buffer.readInt();
            id = buffer.readInt();
        }

        @Override
        public void toBytes(ByteBuf buffer) {
            buffer.writeInt(x);
            buffer.writeInt(y);
            buffer.writeInt(z);
            buffer.writeInt(value);
            buffer.writeInt(id);
        }
    }

    private static void setStatic(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field field = unsafeClass.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Object unsafe = field.get(null);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class).invoke(unsafe, type));
    }
}
