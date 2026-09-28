package dev.umb.legacy.legacyside;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import cpw.mods.fml.common.network.IGuiHandler;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.world.World;

import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;

/**
 * The client thread (UmbLegacyScreen key handling via the bridge) must never walk
 * the live legacy GUI's text fields - the server thread mutates them. Focus state is
 * computed server-side after every tick/click/key and read cached client-side.
 */
class LegacyGuiFocusCacheTest {
    @AfterEach
    void clear() throws Exception {
        LegacyNetworkLoopback.drainClientMessages();
        LegacyNetworkLoopback.clearClientPlayer();
        LegacyNetworkLoopback.setServerContextProvider(null);
        setStatic(LegacyGuiMouseDispatcher.class, "activeSession", null);
        setStatic(UmbGui.class, "lastContext", null);
    }

    @Test
    void computeSeesDirectAndArrayFields() throws Exception {
        FocusGui gui = new FocusGui();
        assertFalse(LegacyGuiMouseDispatcher.computeTextFocused(null));
        assertFalse(LegacyGuiMouseDispatcher.computeTextFocused(new Object()));
        assertFalse(LegacyGuiMouseDispatcher.computeTextFocused(gui));
        gui.field.func_146195_b(true);
        assertTrue(LegacyGuiMouseDispatcher.computeTextFocused(gui));
        gui.field.func_146195_b(false);
        assertFalse(LegacyGuiMouseDispatcher.computeTextFocused(gui));
        gui.box[1].func_146195_b(true);
        assertTrue(LegacyGuiMouseDispatcher.computeTextFocused(gui));
    }

    @Test
    void clientReadFollowsServerSideRefresh() throws Exception {
        UmbPlayer player = allocate(UmbPlayer.class);
        FocusGui gui = new FocusGui();
        UmbGui.GuiContext context = new UmbGui.GuiContext(new FixtureHandler(gui), 3, player,
                null, 0, 0, 0);
        setStatic(UmbGui.class, "lastContext", context);
        LegacyGuiMouseDispatcher.open(context);

        // Fresh session: nothing focused yet.
        assertFalse(LegacyGuiMouseDispatcher.textFocused());

        gui.field.func_146195_b(true);
        LegacyGuiMouseDispatcher.tick(player);
        assertTrue(LegacyGuiMouseDispatcher.textFocused(),
                "tick() must publish the focused state to the client-thread cache");

        gui.field.func_146195_b(false);
        assertTrue(LegacyGuiMouseDispatcher.textFocused(),
                "the cache stays stale until the next server-side refresh");
        assertTrue(LegacyGuiMouseDispatcher.dispatch(FocusGui.class.getName(), 0, 0, 0,
                5, 5, 0, 5, 5));
        assertTrue(LegacyGuiMouseDispatcher.textFocused(),
                "dispatch() must refresh the cache after the click");

        gui.field.func_146195_b(true);
        assertTrue(LegacyGuiMouseDispatcher.dispatchKey(FocusGui.class.getName(), 'a', 30));
        assertTrue(LegacyGuiMouseDispatcher.textFocused(),
                "dispatchKey() must refresh the cache after the key");
    }

    private static final class FixtureHandler implements IGuiHandler {
        private final GuiContainer gui;

        FixtureHandler(GuiContainer gui) {
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

    private static final class FocusGui extends GuiContainer {
        final GuiTextField field;
        final GuiTextField[] box;

        FocusGui() throws Exception {
            super(new Container() {
                @Override
                public boolean func_75145_c(EntityPlayer player) {
                    return true;
                }
            });
            field = allocate(GuiTextField.class);
            box = new GuiTextField[] { allocate(GuiTextField.class), allocate(GuiTextField.class) };
        }

        @Override
        public void func_73876_c() {
            // Skip the vanilla container checks headless; focus is what this covers.
        }

        @Override
        protected void func_73864_a(int mouseX, int mouseY, int button) {
            // Mimic a text field taking focus on click. The vanilla slot machinery
            // is skipped headless: it reaches LWJGL through the player controller.
            field.func_146195_b(true);
        }

        @Override
        protected void func_146286_b(int mouseX, int mouseY, int button) {
            // Release hook is a no-op here for the same reason.
        }

        @Override
        protected void func_146976_a(float partialTicks, int mouseX, int mouseY) {
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
