package dev.umb.hostagent.automation;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the live bug: after the FIRST key/packet-triggered legacy GUI (e.g.
 * MCHeli's R -> EntityPlayer.openGui) opened and was closed via the {@code close_menu}
 * automation command, EVERY LATER open request silently produced no host menu -
 * {@code [UMB-GUI] gui openGui entered/prepared} and {@code gui legacy screen opened} all still
 * logged (the legacy side never knew the host declined), but no {@code [UMB-GUI] opened legacy
 * container} line and no client screen ever appeared again.
 *
 * {@code close_menu} used to call {@code Minecraft.getInstance().gui.setScreen(null)} directly.
 * That is exactly the body of the BASE {@code Screen.onClose()} - but
 * {@code AbstractContainerScreen.onClose()} OVERRIDES {@code onClose()} to first call
 * {@code minecraft.player.closeContainer()}, which sends the
 * {@code ServerboundContainerClosePacket} the server needs to run
 * {@code Player.doCloseContainer()} (the real container's {@code removed()} plus resetting
 * {@code containerMenu} back to the inventory menu). Skipping {@code onClose()} left the
 * server's {@code containerMenu} pinned on the old {@code UmbLegacyMenu} forever, so every
 * later {@code HostPlayerImpl.openLegacyContainer} call hit its "a legacy menu is already open"
 * reopen guard and silently no-opped - forever, since nothing else ever reset that field.
 * Block-activated GUIs (HBM's reactor, #99) never hit this because {@code UmbLegacyBlock} calls
 * {@code player.openMenu(...)} directly, and {@code ServerPlayer.openMenu()} (also
 * the new one - {@code close_menu} is the one path that bypassed that mechanism entirely.
 *
 * The fix ({@link AutomationControl#closeScreen}) now calls the screen's own {@code onClose()} -
 * the exact same call {@code Screen.keyPressed}'s escape handling makes on a real ESC press
 * whether a human pressed escape or this automation command did it.
 *
 * {@code Screen} needs a live {@code Minecraft.getInstance()} to construct for real (see
 * allocates a bare instance via {@code Unsafe.allocateInstance} (same idiom as
 * {@code TestSupport.allocate}, duplicated locally: {@code TestSupport} lives in the
 * {@code dev.umb.hostagent.content} package and is package-private there) and overrides only
 * {@code onClose()} to record whether it ran - proving {@link AutomationControl#closeScreen}
 * delegates to the screen's real close hook instead of bypassing it, without needing a live
 * client or a real container menu.
 */
final class AutomationControlCloseMenuTest {

    @Test
    void closeScreenDelegatesToTheScreensOwnOnCloseInsteadOfBypassingIt() throws Exception {
        RecordingScreen screen = allocate(RecordingScreen.class);

        AutomationControl.closeScreen(screen);

        assertTrue(screen.closed, "close_menu must call the screen's own onClose() (the same "
                + "hook a real ESC press uses) so an AbstractContainerScreen's override can send "
                + "the close-container packet - bypassing it (the previous direct "
                + "Gui.setScreen(null) body) left the server thinking the container was still "
                + "open and permanently blocked every later key/packet-triggered legacy GUI open");
    }

    /** Never actually invoked: {@link #allocate} skips every constructor, matching how
     *  {@code UmbLegacyScreenTest} allocates a real {@code Screen} subclass headlessly. It exists
     *  only so this class compiles with a valid super-constructor reference. */
    static final class RecordingScreen extends Screen {
        boolean closed;

        RecordingScreen() {
            super((Component) null);
        }

        @Override
        public void onClose() {
            closed = true;
        }
    }

    private static <T> T allocate(Class<T> type) throws ReflectiveOperationException {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
        @SuppressWarnings("unchecked")
        T instance = (T) unsafe.allocateInstance(type);
        return instance;
    }
}
