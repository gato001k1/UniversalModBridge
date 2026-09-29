package dev.umb.hostagent.content;

import dev.umb.hostagent.Hooks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the live crash in
 * {@code crash-2026-09-08_17.19.11-client.txt}:
 * <pre>
 * java.lang.IllegalAccessError: class dev.umb.hostagent.content.UmbMenuSupplierGen tried to access
 * method '...UmbMenuRegistration.clientCreateMenuHelper(...)' (UmbMenuSupplierGen is in unnamed
 * module of loader UmbDynamicAdapters$Loader; UmbMenuRegistration is in unnamed module of loader
 * 'app')
 * </pre>
 * {@link UmbDynamicAdapters} used to define the generated {@code MenuSupplier}/
 * {@code ScreenConstructor} adapters in its OWN child {@link ClassLoader}, which put them in a
 * DIFFERENT runtime package than {@link UmbMenuRegistration} even though the package NAME was
 * identical -- a runtime package is (loader, name), not just name. The package-private helper
 * methods the adapters forward to were therefore inaccessible, and the client only ever found out
 * the first time a menu was actually opened (the previous test suite only ever REGISTERED the
 * types -- see {@code Registrar.registerLegacyTileAndMenu}'s own probe line -- it never called
 * through the generated class).
 *
 * This test exercises the REAL production call chain, not a hand-rolled stand-in:
 * {@link TestSupport#ensureBootstrapped()} runs the real {@code Bootstrap.bootStrap()}, which
 * calls {@code BuiltInRegistries.freeze()}, which (ONLY because this test runs with the real
 * {@code dev.umb.hostagent.HostAgent} attached as a {@code -javaagent} -- see
 * {@code tools/windows/run-hostagent-tests.ps1}'s dedicated third invocation for this class) has been
 * bytecode-spliced to call {@link Hooks#beforeFreeze()} first, which calls
 * {@code Registrar.registerLegacyTileAndMenu}, which calls the real
 * {@link UmbMenuRegistration#registerMenuType} / {@link UmbMenuRegistration#registerScreen} --
 * exactly what the live game does. Only a real agent attach installs
 * {@code dev.umb.hostagent.UmbAccessWidener} as a live {@code ClassFileTransformer} BEFORE
 * {@code MenuType$MenuSupplier} / {@code MenuScreens$ScreenConstructor} are ever loaded, which is
 * required for the generated adapters to legally implement those (originally package-private)
 * interfaces at all -- a plain JUnit invocation (as the rest of this package's tests use) never
 * widens them, so this class cannot run there.
 */
class UmbMenuAdapterCrossLoaderTest {

    @Test
    void generatedMenuSupplierAdapterReachesItsHelperAcrossTheRealClassLoader() throws Exception {
        TestSupport.ensureBootstrapped();
        assertTrue(Hooks.hasRun(), "Hooks.beforeFreeze must have run (requires the real -javaagent attach)");
        assertNotNull(UmbMenuRegistration.menuType(), "registerMenuType must have run as part of the real boot");

        int containerId = 4242;
        UmbMenuRegistration.LAYOUTS.put(containerId, new UmbMenuRegistration.Layout(new int[]{1}, new int[]{2}, 0));
        Inventory inv = TestSupport.allocate(Inventory.class);

        // THE exact call the live client made when it crashed: MenuType.create(...) ->
        // MenuType$MenuSupplier.create(...) -- a real, non-reflective interface dispatch into the
        // generated adapter. Pre-fix this throws a raw (unwrapped) IllegalAccessError right here;
        // post-fix it returns a real UmbLegacyMenu.
        AbstractContainerMenu menu = UmbMenuRegistration.menuType().create(containerId, inv);
        assertInstanceOf(UmbLegacyMenu.class, menu,
                "the generated MenuSupplier adapter must produce a real UmbLegacyMenu");
    }

    @Test
    void generatedScreenConstructorAdapterReachesItsHelperAcrossTheRealClassLoader() throws Exception {
        TestSupport.ensureBootstrapped();
        assertNotNull(UmbMenuRegistration.menuType());
        assertTrue(UmbMenuRegistration.isScreenRegistered(), "registerScreen must have run as part of the real boot");

        // Screen's ctor (compiled into 26.2, not ours) reads Minecraft.getInstance().font. There is
        // no real client here, so Minecraft.getInstance() would otherwise return null and NPE
        // before we ever reach the code under test -- fake just the static singleton, same
        // "allocate an object whose fields are never read beyond identity/defaults" idiom
        // TestSupport itself already documents and uses. AbstractContainerScreen's own ctor only
        // calls Inventory.getDisplayName() (-> the static DEFAULT_NAME field), which needs no live
        // state either.
        Field instanceField = Minecraft.class.getDeclaredField("instance");
        instanceField.setAccessible(true);
        instanceField.set(null, TestSupport.allocate(Minecraft.class));

        Field screensField = MenuScreens.class.getDeclaredField("SCREENS");
        screensField.setAccessible(true);
        Map<?, ?> screens = (Map<?, ?>) screensField.get(null);
        Object screenAdapter = screens.get(UmbMenuRegistration.menuType());
        assertNotNull(screenAdapter, "the real generated ScreenConstructor adapter must be registered under our MenuType");

        Inventory inv = TestSupport.allocate(Inventory.class);
        UmbLegacyMenu menu = UmbLegacyMenu.forClient(UmbMenuRegistration.menuType(), 99, inv,
                new int[]{1}, new int[]{2}, 0, Component.literal("t"));

        // ScreenConstructor is (originally) package-private, so our test source -- compiled
        // against the ORIGINAL un-widened client.jar, exactly like production -- cannot spell its
        // type and must invoke reflectively, exactly like the JVM's own interface dispatch does
        // internally. Reflection's OWN access check (caller vs. the public generated class/method)
        // always succeeds here; what we are checking is whether the invoked method's OWN bytecode
        // body can reach UmbMenuRegistration.screenCreateHelper.
        Method create = screenAdapter.getClass().getMethod("create",
                AbstractContainerMenu.class, Inventory.class, Component.class);
        Object screen;
        try {
            screen = create.invoke(screenAdapter, menu, inv, Component.literal("t"));
        } catch (InvocationTargetException e) {
            throw new AssertionError("generated ScreenConstructor adapter could not call its own "
                    + "package-private helper (the exact G2 crash this test guards against): " + e.getCause(),
                    e.getCause());
        }
        assertInstanceOf(Screen.class, screen);
        assertEquals("UmbLegacyScreen", screen.getClass().getSimpleName());
    }
}
