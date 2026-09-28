package dev.umb.hostagent.content;

import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.UmbAccessWidener;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * R5/R6 glue: registers the ONE generic {@code umb:legacy_menu} MenuType pre-freeze, and (later,
 * step 6) wires the client screen into {@code MenuScreens.SCREENS} reflectively.
 *
 * MenuType's constructor is private and its {@code MenuSupplier} constructor-argument type is a
 * package-private interface our precompiled code cannot {@code implements} at javac time. See
 * {@link UmbDynamicAdapters} for why a runtime-generated adapter (not
 * {@link java.lang.reflect.Proxy}) is the correct fix and why {@link UmbAccessWidener} must have
 * already widened the interface's class-file access_flags before this runs.
 */
public final class UmbMenuRegistration {

    /** containerId -> client-side slot geometry, written by the server just before openMenu (R5 singleplayer shortcut). */
    static final Map<Integer, Layout> LAYOUTS = new ConcurrentHashMap<>();

    private static volatile MenuType<UmbLegacyMenu> MENU_TYPE;
    private static volatile boolean screenRegistered = false;

    private UmbMenuRegistration() {
    }

    public static boolean isScreenRegistered() {
        return screenRegistered;
    }

    public static final class Layout {
        final int[] xs;
        final int[] ys;
        final int dataCount;
        final int xSize;
        final int ySize;
        final Identifier texture;
        final int sheetWidth;
        final int sheetHeight;
        /** SCREEN-RENDER lane: see {@link UmbLegacyMenu#rects}/{@link UmbLegacyMenu#labels}/
         *  {@link UmbLegacyMenu#rectTextures} - this in-process side channel (a plain
         *  {@code Map}, not a real network packet: the embedded server and its client share one
         *  JVM here) is what actually gets these from {@link UmbMenuProvider}'s server-side
         *  GuiProfile lookup to the client-side menu {@link UmbLegacyScreen} renders. */
        final java.util.List<GuiProfile.Rect> rects;
        final java.util.List<GuiProfile.Label> labels;
        final Identifier[] rectTextures;
        final int[] rectTextureSheetWidths;
        final int[] rectTextureSheetHeights;
        final int[] playerIndices;
        final int[] playerXs;
        final int[] playerYs;
        final java.util.List<GuiProfile.Button> buttons;
        /** Packet GUI dispatch context reconstructed on the client for legacy button clicks. */
        final String packetGuiClass;
        final int packetX;
        final int packetY;
        final int packetZ;

        /** Back-compat: the pre-Task-B geometry-only layout, still used by
         *  UmbMenuAdapterCrossLoaderTest - defaults to the plain 176x166 dispenser panel. */
        Layout(int[] xs, int[] ys, int dataCount) {
            this(xs, ys, dataCount, GuiProfile.FALLBACK_X, GuiProfile.FALLBACK_Y, null, 256, 256);
        }

        /** Task B: the real GUI size + background texture travel alongside the slot geometry
         *  through this same server-to-client side channel (see UmbMenuProvider/clientCreateMenuHelper). */
        Layout(int[] xs, int[] ys, int dataCount, int xSize, int ySize, Identifier texture,
               int sheetWidth, int sheetHeight) {
            this(xs, ys, dataCount, xSize, ySize, texture, sheetWidth, sheetHeight,
                    java.util.List.of(), java.util.List.of(), null, null, null, null, null, null, java.util.List.of());
        }

        /** SCREEN-RENDER lane: also carries the statically-drawable extra rects/labels and every
         *  texture they can reference (see {@link UmbMenuProvider#createMenu}). */
        Layout(int[] xs, int[] ys, int dataCount, int xSize, int ySize, Identifier texture,
               int sheetWidth, int sheetHeight,
               java.util.List<GuiProfile.Rect> rects, java.util.List<GuiProfile.Label> labels,
               Identifier[] rectTextures, int[] rectTextureSheetWidths, int[] rectTextureSheetHeights,
               int[] playerIndices, int[] playerXs, int[] playerYs,
               java.util.List<GuiProfile.Button> buttons) {
            this(xs, ys, dataCount, xSize, ySize, texture, sheetWidth, sheetHeight,
                    rects, labels, rectTextures, rectTextureSheetWidths, rectTextureSheetHeights,
                    playerIndices, playerXs, playerYs, buttons, null, 0, 0, 0);
        }

        /** Carries the legacy GUI class and block position needed by client-originated button
         *  clicks. The server-side UmbLegacyMenu already has these values; they must travel with
         *  the same singleplayer layout handoff or the client fallback silently cannot queue. */
        Layout(int[] xs, int[] ys, int dataCount, int xSize, int ySize, Identifier texture,
               int sheetWidth, int sheetHeight,
               java.util.List<GuiProfile.Rect> rects, java.util.List<GuiProfile.Label> labels,
               Identifier[] rectTextures, int[] rectTextureSheetWidths, int[] rectTextureSheetHeights,
               int[] playerIndices, int[] playerXs, int[] playerYs,
               java.util.List<GuiProfile.Button> buttons,
               String packetGuiClass, int packetX, int packetY, int packetZ) {
            this.xs = xs;
            this.ys = ys;
            this.dataCount = dataCount;
            this.xSize = xSize;
            this.ySize = ySize;
            this.texture = texture;
            this.sheetWidth = sheetWidth;
            this.sheetHeight = sheetHeight;
            this.rects = rects;
            this.labels = labels;
            this.rectTextures = rectTextures;
            this.rectTextureSheetWidths = rectTextureSheetWidths;
            this.rectTextureSheetHeights = rectTextureSheetHeights;
            this.playerIndices = playerIndices;
            this.playerXs = playerXs;
            this.playerYs = playerYs;
            this.buttons = buttons;
            this.packetGuiClass = packetGuiClass;
            this.packetX = packetX;
            this.packetY = packetY;
            this.packetZ = packetZ;
        }
    }

    public static MenuType<UmbLegacyMenu> menuType() {
        return MENU_TYPE;
    }

    /** Step 2: pre-freeze registration of the one generic MenuType (R5). Idempotent. */
    public static synchronized MenuType<UmbLegacyMenu> registerMenuType(String namespace) throws Exception {
        if (MENU_TYPE != null) return MENU_TYPE;

        Object supplierAdapter = UmbDynamicAdapters.generateSingleMethodAdapter(
                "dev/umb/hostagent/content/UmbMenuSupplierGen",
                UmbAccessWidener.MENU_SUPPLIER,
                "create",
                "(ILnet/minecraft/world/entity/player/Inventory;)Lnet/minecraft/world/inventory/AbstractContainerMenu;",
                "dev/umb/hostagent/content/UmbMenuRegistration",
                "clientCreateMenuHelper");

        Class<?> supplierIface = Class.forName(UmbAccessWidener.MENU_SUPPLIER.replace('/', '.'),
                false, MenuType.class.getClassLoader());
        Constructor<?> ctor = MenuType.class.getDeclaredConstructor(supplierIface, FeatureFlagSet.class);
        ctor.setAccessible(true);
        @SuppressWarnings("unchecked")
        MenuType<UmbLegacyMenu> type = (MenuType<UmbLegacyMenu>) ctor.newInstance(supplierAdapter, FeatureFlags.VANILLA_SET);

        Identifier rl = Identifier.fromNamespaceAndPath(namespace, "legacy_menu");
        ResourceKey<MenuType<?>> key = ResourceKey.create(Registries.MENU, rl);
        Registry.register(BuiltInRegistries.MENU, key, type);
        MENU_TYPE = type;
        AgentLog.loud("PATCHED MenuType registered: " + rl);
        return type;
    }

    /**
     * Forwarded to by the generated MenuSupplier adapter -- MUST keep exactly the erased
     * descriptor {@code (ILnet/minecraft/world/entity/player/Inventory;)Lnet/minecraft/world/inventory/AbstractContainerMenu;}.
     * This is the CLIENT-side reconstruction path: containerId + Inventory only, no player, no
     * live ContainerHandle -- geometry comes from the singleplayer layout side-channel.
     */
    static AbstractContainerMenu clientCreateMenuHelper(int containerId, Inventory inv) {
        Layout l = LAYOUTS.remove(containerId);
        if (l == null) {
            AgentLog.error("UmbMenuRegistration.clientCreateMenuHelper",
                    new IllegalStateException("no layout for containerId " + containerId), 1);
            l = new Layout(new int[0], new int[0], 0);
        }
        UmbLegacyMenu menu = UmbLegacyMenu.forClient(MENU_TYPE, containerId, inv, l.xs, l.ys, l.dataCount,
                Component.translatable("umb.legacy_menu.title"),
                l.xSize, l.ySize, l.texture, l.sheetWidth, l.sheetHeight,
                l.rects, l.labels, l.rectTextures, l.rectTextureSheetWidths, l.rectTextureSheetHeights,
                l.playerIndices, l.playerXs, l.playerYs);
        menu.buttons = l.buttons;
        menu.packetGuiClass = l.packetGuiClass;
        menu.packetX = l.packetX;
        menu.packetY = l.packetY;
        menu.packetZ = l.packetZ;
        return menu;
    }

    private static Object screenAdapterCache;

    /** Step 6: MenuScreens.SCREENS reflective registration; do NOT widen MenuScreens.register(). */
    public static synchronized void registerScreen() throws Exception {
        if (MENU_TYPE == null) {
            throw new IllegalStateException("registerMenuType must run before registerScreen");
        }
        // Multi-mod: registerScreen() runs once per mod namespace, but the generated adapter class
        // is identical for all of them - defining it twice throws LinkageError (duplicate class
        // definition, live twomods boot). Generate once, reuse for every MenuType.
        if (screenAdapterCache == null) screenAdapterCache = UmbDynamicAdapters.generateSingleMethodAdapter(
                "dev/umb/hostagent/content/UmbScreenConstructorGen",
                UmbAccessWidener.SCREEN_CONSTRUCTOR,
                "create",
                "(Lnet/minecraft/world/inventory/AbstractContainerMenu;Lnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/client/gui/screens/Screen;",
                "dev/umb/hostagent/content/UmbMenuRegistration",
                "screenCreateHelper");
        Object screenAdapter = screenAdapterCache;

        Field f = MenuScreens.class.getDeclaredField("SCREENS");
        f.setAccessible(true);
        Object mapObj = f.get(null);
        @SuppressWarnings("unchecked")
        Map<Object, Object> screens = (Map<Object, Object>) mapObj;
        screens.put(MENU_TYPE, screenAdapter);
        screenRegistered = true;
        AgentLog.loud("PATCHED MenuScreens.SCREENS += umb:legacy_menu");
    }

    /** Forwarded to by the generated ScreenConstructor adapter -- keep the erased descriptor exact. */
    static Screen screenCreateHelper(AbstractContainerMenu menu, Inventory inv, Component title) {
        return new UmbLegacyScreen((UmbLegacyMenu) menu, inv, title);
    }

    /** For tests: forget the registered type so a fresh headless bootstrap can re-register it. */
    public static void resetForTests() {
        MENU_TYPE = null;
        LAYOUTS.clear();
        screenRegistered = false;
    }
}
