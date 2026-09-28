package dev.umb.hostagent.content;

import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.TileHandle;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;

/**
 * MenuProvider and MenuConstructor are both PUBLIC interfaces (javap-verified) -- unlike
 * MenuType$MenuSupplier / MenuScreens$ScreenConstructor, this needs no widener, no bytecode
 * generation, just an ordinary implementing class.
 *
 * {@link net.minecraft.server.level.ServerPlayer#openMenu(MenuProvider)} calls
 * {@code createMenu(id, inv, player)} then sends
 * {@code ClientboundOpenScreenPacket(id, menu.getType(), getDisplayName())} -- so before returning
 * the menu we stash this container's slot geometry in the singleplayer layout side-channel
 * ({@link UmbMenuRegistration#LAYOUTS}) so the client's MenuSupplier adapter (which receives only
 * containerId + Inventory from the packet) can rebuild the same slot positions.
 */
final class UmbMenuProvider implements MenuProvider {

    private final ContainerHandle handle;
    private final Component title;
    /** TILE-FIELD-SNAPSHOT lane: null for every pre-existing caller (no tile entity behind this
     *  GUI's block, or the bridge could not create one) — see the 3-arg constructor. */
    private final TileHandle tileHandle;
    private final int blockX, blockY, blockZ;

    UmbMenuProvider(ContainerHandle handle, Component title) {
        this(handle, title, null);
    }

    /** TILE-FIELD-SNAPSHOT lane: also carries the live tile handle behind this position (see
     *  {@link UmbLegacyBlockEntity#currentHandle()}), so the created menu can snapshot the fields
     *  its own GUI's gauges/guards need, once per server tick. */
    UmbMenuProvider(ContainerHandle handle, Component title, TileHandle tileHandle) {
        this(handle, title, tileHandle, 0, 0, 0);
    }

    UmbMenuProvider(ContainerHandle handle, Component title, TileHandle tileHandle,
                    int blockX, int blockY, int blockZ) {
        this.handle = handle;
        this.title = title;
        this.tileHandle = tileHandle;
        this.blockX = blockX; this.blockY = blockY; this.blockZ = blockZ;
    }

    @Override
    public Component getDisplayName() {
        return title;
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        // Resolve the real legacy Container class before validating player-slot geometry. The
        // panel dimensions are GUI-profile data, not the historical 176x166 fallback.
        String containerClass = LegacyContainerClassResolver.resolve(handle);
        // ironchest-visuals lane: the handle's title carries the container REGISTRY id on
        // the 1.16.5 path (ContainerHandle1165 gets it from ForgeRegistries.CONTAINERS -
        // several ContainerTypes share one Container class there), while 1.7.10 titles
        // are block ids that match no containerId row and safely fall back to the class
        // key. Only a well-formed ns:path is ever used as a key, never invented text.
        GuiProfile.GuiEntry gui = Registrar.GUI_PROFILE.lookup(containerClass, containerIdOf(handle));
        int xSize = gui != null ? gui.xSize : GuiProfile.FALLBACK_X;
        int ySize = gui != null ? gui.ySize : GuiProfile.FALLBACK_Y;

        SlotData[] slots = handle.slots();
        int[] xs = new int[slots.length];
        int[] ys = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            xs[i] = slots[i].x;
            ys[i] = slots[i].y;
        }
        java.util.List<LegacyContainerClassResolver.PlayerSlot> playerSlots =
                LegacyContainerClassResolver.resolvePlayerSlots(handle);
        int[] playerIndices = null, playerXs = null, playerYs = null;
        if (LegacyContainerClassResolver.slotsInsidePanel(playerSlots, xSize, ySize)) {
            playerIndices = new int[36];
            playerXs = new int[36];
            playerYs = new int[36];
            for (LegacyContainerClassResolver.PlayerSlot s : playerSlots) {
                // The legacy Container commonly stores main-inventory slots before hotbar
                // slots, while AbstractContainerMenu's player inventory is addressed by the
                // inventory-local index. Preserve the real index rather than list order.
                playerIndices[s.inventoryIndex] = s.inventoryIndex;
                playerXs[s.inventoryIndex] = s.x;
                playerYs[s.inventoryIndex] = s.y;
            }
        }

        // Task B: recover the real legacy Container class (reflection, no bridge-api change - see
        // LegacyContainerClassResolver's javadoc) and look up its real GUI size + background
        // texture. A fake/test handle, or a container gui-profile.json never paired, resolves to
        // null and falls all the way back to the pre-existing 176x166 dispenser panel.
        Identifier texture = null;
        int sheetWidth = 256, sheetHeight = 256;
        java.util.List<GuiProfile.Rect> rects = java.util.List.of();
        java.util.List<GuiProfile.Label> labels = java.util.List.of();
        Identifier[] rectTextures = null;
        int[] rectSheetWidths = null, rectSheetHeights = null;
        if (gui != null && gui.texture != null) {
            // GENERALITY fix (laneCasing, Bug 1): gui.texture.namespace/path come straight from
            // whatever raw ResourceLocation-ish string umb-guimap extracted from the mod's own
            // bindTexture(...) call - sanitize before building a live Identifier the same way
            // PackGen.copyGuiTextures now sanitizes the file it actually writes into the pack, so
            // the two agree on where this texture lives.
            texture = Identifier.fromNamespaceAndPath(
                    LegacyIds.sanitizeNamespace(gui.texture.namespace),
                    LegacyIds.sanitizePath(gui.texture.path));
            sheetWidth = gui.texture.sheetWidth;
            sheetHeight = gui.texture.sheetHeight;
        }
        if (gui != null) {
            // SCREEN-RENDER lane: the statically-drawable extra rects/labels (progress-bar
            // frames, gauge borders, icons, custom text) GuiProfile already filtered down to a
            // safe-to-blit subset (see GuiProfile.buildRects/buildLabels), plus every texture a
            // rect might reference (a GUI can bindTexture() more than once per draw method - see
            // GuiProfile.Rect#textureIndex).
            rects = gui.rects;
            labels = gui.labels;
            rectTextures = new Identifier[gui.textures.length];
            rectSheetWidths = new int[gui.textures.length];
            rectSheetHeights = new int[gui.textures.length];
            for (int i = 0; i < gui.textures.length; i++) {
                GuiProfile.TextureRef t = gui.textures[i];
                if (t == null) continue;
                rectTextures[i] = Identifier.fromNamespaceAndPath(
                        LegacyIds.sanitizeNamespace(t.namespace), LegacyIds.sanitizePath(t.path));
                rectSheetWidths[i] = t.sheetWidth;
                rectSheetHeights[i] = t.sheetHeight;
            }
        }

        UmbMenuRegistration.LAYOUTS.put(containerId,
                new UmbMenuRegistration.Layout(xs, ys, safeSyncLength(), xSize, ySize, texture, sheetWidth, sheetHeight,
                        rects, labels, rectTextures, rectSheetWidths, rectSheetHeights,
                        playerIndices, playerXs, playerYs, gui != null ? gui.buttons : java.util.List.of(),
                        gui != null ? gui.guiClassName : null, blockX, blockY, blockZ));
        // TILE-FIELD-SNAPSHOT lane: the FieldPath[] request is built ONCE here (menu-open time),
        // from this GUI's own deduplicated field list - see GuiProfile.GuiEntry#tileFieldRefs and
        // TileSnapshotChannel's own javadoc on why this never runs per tick.
        FieldPath[] tileFieldRequest = gui != null
                ? TileSnapshotChannel.buildRequest(gui.tileFieldRefs) : new FieldPath[0];
        UmbLegacyMenu menu = UmbLegacyMenu.forServer(UmbMenuRegistration.menuType(), containerId, playerInventory, handle, title,
                xSize, ySize, texture, sheetWidth, sheetHeight,
                rects, labels, rectTextures, rectSheetWidths, rectSheetHeights,
                tileHandle, tileFieldRequest, playerIndices, playerXs, playerYs);
        menu.buttons = gui != null ? gui.buttons : java.util.List.of();
        menu.packetGuiClass = gui != null ? gui.guiClassName : null;
        menu.packetX = blockX; menu.packetY = blockY; menu.packetZ = blockZ;
        return menu;
    }

    private int safeSyncLength() {
        try {
            return handle.syncData().length;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** The handle title when it is already a well-formed {@code ns:path} registry id. */
    static String containerIdOf(ContainerHandle handle) {
        if (handle == null) return null;
        String title;
        try {
            title = handle.title();
        } catch (Throwable t) {
            return null;
        }
        if (title == null) return null;
        int colon = title.indexOf(':');
        if (colon <= 0 || colon == title.length() - 1 || title.indexOf(':', colon + 1) >= 0) {
            return null;
        }
        return title;
    }
}
