package dev.umb.hostagent.content;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 6/7 gate: "the screen class constructs".
 *
 * javap -c on {@code Screen(Component)} shows it calls {@code Minecraft.getInstance().font} in
 * its own body -- with no live client bootstrapped (R8: no windowed client in this lane),
 * {@code Minecraft.getInstance()} returns null and that NPEs immediately, and
 * AbstractContainerScreen's own ctor funnels into the very same super(Component) call. There is
 * no constructor path here that does not require a live Minecraft instance.
 *
 * So this test proves the class is well-formed and correctly wired -- the right constructor shape,
 * the right supertype chain, satisfies MenuAccess&lt;UmbLegacyMenu&gt; (required by
 * MenuScreens$ScreenConstructor's {@code U extends Screen & MenuAccess<T>} bound) -- via
 * {@code Unsafe.allocateInstance}, which allocates the object WITHOUT running any constructor, the
 * same idiom the legacy facades already use for a class whose real construction path needs
 * infrastructure this headless lane does not have. The actual windowed construction is exercised
 * by the later cheap windowed-verification lane (DESIGN.md "DEFINITION OF DONE").
 */
class UmbLegacyScreenTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
    }

    @Test
    void hasThePublicThreeArgConstructorMenuScreensNeeds() throws Exception {
        Constructor<UmbLegacyScreen> ctor = UmbLegacyScreen.class.getConstructor(
                UmbLegacyMenu.class, net.minecraft.world.entity.player.Inventory.class,
                net.minecraft.network.chat.Component.class);
        assertTrue(java.lang.reflect.Modifier.isPublic(ctor.getModifiers()));
    }

    @Test
    void extendsAbstractContainerScreenOfUmbLegacyMenu() {
        assertTrue(AbstractContainerScreen.class.isAssignableFrom(UmbLegacyScreen.class));
        assertTrue(Screen.class.isAssignableFrom(UmbLegacyScreen.class));
    }

    @Test
    void satisfiesTheMenuAccessBoundScreenConstructorRequires() {
        // MenuScreens$ScreenConstructor<T,U> requires U extends Screen & MenuAccess<T> --
        // AbstractContainerScreen<T> already implements MenuAccess<T>, so UmbLegacyScreen
        // inherits that for free; this is exactly why AbstractContainerScreen was chosen.
        assertTrue(MenuAccess.class.isAssignableFrom(UmbLegacyScreen.class));
    }

    @Test
    void classAllocatesAndTheGenericSuperclassIsParameterisedWithUmbLegacyMenu() {
        // Unsafe.allocateInstance skips every constructor (Screen's included), so `menu` stays
        // whatever the JVM zero-initialises a reference field to (null) -- this only proves the
        // class shape is correct, not runtime behaviour that depends on a live Minecraft
        // instance. getMenu()'s reflected Method erases to AbstractContainerMenu (a generic
        // bridge method), so the real check for "which T" is the generic superclass signature,
        // i.e. `extends AbstractContainerScreen<UmbLegacyMenu>`.
        UmbLegacyScreen screen = TestSupport.allocate(UmbLegacyScreen.class);
        assertNotNull(screen);

        java.lang.reflect.Type generic = UmbLegacyScreen.class.getGenericSuperclass();
        assertTrue(generic instanceof java.lang.reflect.ParameterizedType);
        java.lang.reflect.Type[] args = ((java.lang.reflect.ParameterizedType) generic).getActualTypeArguments();
        assertEquals(1, args.length);
        assertEquals(UmbLegacyMenu.class, args[0]);
    }
}
