package dev.umb.hostagent.content;

import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileFieldSnapshot;
import dev.umb.bridge.api.TileHandle;
import dev.umb.hostagent.AgentLog;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import java.util.concurrent.ConcurrentHashMap;

/**
 * One generic 26.2 menu (R5) mirroring a {@link ContainerHandle}'s machine slots at their legacy
 * x/y, plus the standard 36 player slots. There is exactly one {@code MenuType} for every legacy
 * container shape -- the geometry (slot count, positions, progress-bar count) travels with each
 * instance instead.
 *
 * Two construction paths:
 *  - {@link #forServer} is the SERVER-authoritative menu: its machine slots are backed by a
 *    {@link LegacyContainerAdapter} that proxies straight through to the live
 *    {@link ContainerHandle}, and its {@link ContainerData} reads {@code handle.syncData()} on
 *    every {@code get} (simple and correct; M1 does not need it cached).
 *  - {@link #forClient} is the CLIENT-side visual mirror opened by
 *    {@code MenuType$MenuSupplier.create(int, Inventory)} (see {@link UmbMenuRegistration}) --
 *    it has no live handle, just slot geometry read from the singleplayer layout side-channel;
 *    vanilla's own {@code ClientboundContainerSetContent}/{@code SetSlot}/{@code SetData} packets
 *    fill its plain {@link SimpleContainer} the same way they fill any other menu's slots.
 */
public final class UmbLegacyMenu extends AbstractContainerMenu {

    /** Single-process server-tick to client-render handoff for the persistent legacy GUI mesh. */
    private static final ConcurrentHashMap<Integer, dev.umb.bridge.api.GlEmulationSession.Mesh>
            GUI_MESHES = new ConcurrentHashMap<>();
    private static final java.util.Set<Integer> GUI_MESH_LOGGED =
            ConcurrentHashMap.newKeySet();

    static dev.umb.bridge.api.GlEmulationSession.Mesh currentGuiMesh(int containerId) {
        return GUI_MESHES.get(containerId);
    }

    static void stashGuiMesh(int containerId, dev.umb.bridge.api.GlEmulationSession.Mesh mesh) {
        // facade/resource failure. Keep the existing profile texture/fallback visible in that
        // case rather than turning a transient capture problem into a blank screen.
        // A failed frame keeps the last good mesh: dropping it made the screen alternate between
        // the real legacy GUI and the fallback panel (visible flicker) whenever one frame threw.
        if (mesh == null || mesh.draws.isEmpty()) return;
        else {
            if (!GUI_MESHES.containsKey(containerId) && GUI_MESHES.size() >= 8) {
                GUI_MESHES.clear(); // container ids only grow; keep the map bounded
            }
            GUI_MESHES.put(containerId, mesh);
            if (GUI_MESH_LOGGED.add(containerId)) {
                AgentLog.loud("UMB-GUI mesh handoff container=" + containerId
                        + " draws=" + mesh.draws.size() + " vertices=" + mesh.vertexCount());
            }
        }
    }

    /** Standard vanilla anchor for a chest-style panel with the player's own inventory below it -
     *  X is universal across every observed HBM GUI (foregroundLabels' "container.inventory"
     *  label sits at a constant x=8 regardless of panel width); Y is NOT universal once the panel
     *  is taller than the vanilla default, so it is computed per-menu below (see {@code playerInvY}). */
    private static final int PLAYER_INV_X = 8;
    /** {@code AbstractContainerScreen}'s own 5-arg-ctor convention: inventoryLabelY = ySize-94,
     *  and the slot grid starts 12px below that label -&gt; ySize-82 (166-82=84, the pre-Task-B
     *  constant this replaces for the default 166-tall panel). */
    private static final int PLAYER_INV_Y_OFFSET = 82;
    static final int DEFAULT_X_SIZE = GuiProfile.FALLBACK_X;
    static final int DEFAULT_Y_SIZE = GuiProfile.FALLBACK_Y;
    private static final int DEFAULT_SHEET_SIZE = 256;

    /** Non-null only for the server-authoritative menu. */
    public final ContainerHandle handle;
    public final Container machineContainer;
    public final int machineSlotCount;
    private final Component displayTitle;
    private final ContainerData data;

    /** Task B (laneConsume-progress.md): the real legacy GUI's panel size and background texture,
     *  resolved by {@link UmbMenuProvider} via {@link Registrar#GUI_PROFILE} - defaults to the
     *  pre-existing 176x166 / no-texture (vanilla dispenser panel) fallback. */
    public final int xSize;
    public final int ySize;
    /** null means "no resolvable background texture for this GUI" - {@link UmbLegacyScreen} then
     *  keeps its own pre-Task-B borrowed-dispenser fallback rendering. */
    public final Identifier textureLocation;
    public final int sheetWidth;
    public final int sheetHeight;

    /** SCREEN-RENDER lane (GENERALIZATION-PLAN.md GAP 2): the statically-drawable extra rects
     *  (progress-bar frames, gauge borders, indicator icons, ...) and foreground labels
     *  {@link GuiProfile#buildRects}/{@link GuiProfile#buildLabels} resolved for this GUI -
     *  empty for every pre-existing caller (back-compat overloads below). {@link UmbLegacyScreen}
     *  draws these on top of {@link #textureLocation} once per frame. */
    public final java.util.List<GuiProfile.Rect> rects;
    public final java.util.List<GuiProfile.Label> labels;
    /** Schema-v7 native click regions; populated from the server layout side-channel. */
    public java.util.List<GuiProfile.Button> buttons = java.util.List.of();
    /** Packet recipe context supplied by the authoritative menu opener. */
    public String packetGuiClass;
    public int packetX, packetY, packetZ;
    /** Every texture a {@link GuiProfile.Rect#textureIndex} can point at, as a live {@link Identifier}
     *  (parallel to {@link GuiProfile.GuiEntry#textures}) - null at an unresolved index. Index 0 of
     *  this array is NOT necessarily {@link #textureLocation} if the GUI's first bind failed to
     *  resolve but a later one did (see {@link GuiProfile#buildTextures}); rects only ever reference
     *  indices that resolved, so a lookup here for a rect's textureIndex is never null. */
    public final Identifier[] rectTextures;
    public final int[] rectTextureSheetWidths;
    public final int[] rectTextureSheetHeights;

    /** TILE-FIELD-SNAPSHOT lane: non-null only for the SERVER-authoritative menu, and only when
     *  this GUI's {@link GuiProfile.GuiEntry#tileFieldRefs} is non-empty — the live source
     *  {@link #broadcastChanges} snapshots from once per server tick. Always null on the
     *  CLIENT-side mirror: it never touches a bridge type directly, it only reads
     *  {@link TileSnapshotChannel} by this menu's shared {@code containerId} (see
     *  {@link #currentTileFieldLookup}). */
    private final TileHandle tileHandle;
    /** Built ONCE at menu-open time (see {@link UmbMenuProvider}), never per tick — empty when this
     *  GUI needs no tile fields at all (the common case). */
    private final FieldPath[] tileFieldRequest;

    private UmbLegacyMenu(MenuType<UmbLegacyMenu> type, int containerId, Inventory playerInventory,
                          ContainerHandle handle, Container machineContainer,
                          int[] xs, int[] ys, ContainerData data, Component title,
                          int xSize, int ySize, Identifier textureLocation, int sheetWidth, int sheetHeight,
                          java.util.List<GuiProfile.Rect> rects, java.util.List<GuiProfile.Label> labels,
                          Identifier[] rectTextures, int[] rectTextureSheetWidths, int[] rectTextureSheetHeights,
                          TileHandle tileHandle, FieldPath[] tileFieldRequest,
                          int[] playerIndices, int[] playerXs, int[] playerYs) {
        super(type, containerId);
        this.handle = handle;
        this.machineContainer = machineContainer;
        this.machineSlotCount = xs.length;
        this.displayTitle = title;
        this.data = data;
        this.xSize = xSize > 0 ? xSize : DEFAULT_X_SIZE;
        this.ySize = ySize > 0 ? ySize : DEFAULT_Y_SIZE;
        this.textureLocation = textureLocation;
        this.sheetWidth = sheetWidth > 0 ? sheetWidth : DEFAULT_SHEET_SIZE;
        this.sheetHeight = sheetHeight > 0 ? sheetHeight : DEFAULT_SHEET_SIZE;
        this.rects = rects != null ? rects : java.util.List.of();
        this.labels = labels != null ? labels : java.util.List.of();
        this.rectTextures = rectTextures != null ? rectTextures : new Identifier[0];
        this.rectTextureSheetWidths = rectTextureSheetWidths != null ? rectTextureSheetWidths : new int[0];
        this.rectTextureSheetHeights = rectTextureSheetHeights != null ? rectTextureSheetHeights : new int[0];
        this.tileHandle = tileHandle;
        this.tileFieldRequest = tileFieldRequest != null ? tileFieldRequest : new FieldPath[0];
        for (int i = 0; i < xs.length; i++) {
            addSlot(new MachineSlot(machineContainer, i, xs[i], ys[i], handle));
        }
        // A legacy Container with ZERO machine slots is never a real inventory-backed GUI: it is
        // the standard 1.7.10 idiom for a plain client-side GuiScreen (no GuiContainer, no synced
        // slots at all - e.g. MCHeli's scoreboard/config screens, MCH_ContainerScoreboard/
        // still needs SOME Container instance purely so FML's server/client GUI-open handshake has
        // something to open. Every REAL container GUI in the corpus (HBM reactor, MCHeli's
        // aircraft GUI, its UAV station) adds at least one real machine slot. Adding the host's own
        // 36 player-inventory slots on top of a zero-slot container is therefore never correct: the
        // legacy GUI never asked for them, has no panel layout that accounts for them, and the
        // native slot grid (items, hover highlight, click) then floats over whatever the legacy
        // mesh replay draws at those same screen coordinates (bug: hotbar items overlapping
        // MCHeli's scoreboard buttons). Skipping them here is universal - it depends only on the
        // machine-slot count, never a mod/class name - and leaves every real container GUI
        // (machineSlotCount > 0) completely unaffected.
        if (xs.length > 0) {
            if (playerIndices != null && playerXs != null && playerYs != null
                    && playerIndices.length == 36 && playerXs.length == 36 && playerYs.length == 36) {
                for (int i = 0; i < 36; i++) {
                    addSlot(new Slot(playerInventory, playerIndices[i], playerXs[i], playerYs[i]));
                }
            } else {
                int playerInvY = Math.max(18, this.ySize - PLAYER_INV_Y_OFFSET);
                addStandardInventorySlots(playerInventory, PLAYER_INV_X, playerInvY);
            }
        }
        addDataSlots(data);
        // Paint the very first frame's gauges without waiting for the first broadcastChanges() tick
        // (a real, but harmless, one-tick lag - see broadcastChanges's own javadoc); a no-op when
        // this GUI needs no tile fields, or on the client-side mirror (tileHandle==null there).
        if (this.tileHandle != null) {
            TileSnapshotChannel.refresh(containerId, this.tileHandle, this.tileFieldRequest);
        }
    }

    /** Number of progress-bar values this menu carries (for the generic screen's bar drawing). */
    public int dataCount() {
        return data.getCount();
    }

    /** SYNC-BINDING lane: one live {@code ContainerHandle.syncData()} register, by index — for the
     *  SERVER-authoritative menu this reads {@code handle.syncData()} directly ({@link
     *  HandleContainerData}); for the CLIENT-side mirror {@link UmbLegacyScreen} actually renders,
     *  this reads the SAME {@link ContainerData} vanilla's own {@code addDataSlots} (called in the
     *  constructor above, for BOTH construction paths) already keeps in sync over the network — no
     *  new plumbing needed, exactly the point {@code SCREEN-RENDER.md} made about this data already
     *  flowing. Bounds-checked: an out-of-range index (e.g. a stale binding after a schema change)
     *  reads 0 rather than throwing. */
    public int getData(int index) {
        return index >= 0 && index < data.getCount() ? data.get(index) : 0;
    }

    /** Back-compat: pre-Task-B callers (and most existing tests) keep the plain 176x166,
     *  no-background-texture geometry. */
    public static UmbLegacyMenu forServer(MenuType<UmbLegacyMenu> type, int containerId,
                                          Inventory playerInventory, ContainerHandle handle, Component title) {
        return forServer(type, containerId, playerInventory, handle, title,
                DEFAULT_X_SIZE, DEFAULT_Y_SIZE, null, DEFAULT_SHEET_SIZE, DEFAULT_SHEET_SIZE);
    }

    /** Task B: the real GUI size + background texture, resolved by {@link UmbMenuProvider}. */
    public static UmbLegacyMenu forServer(MenuType<UmbLegacyMenu> type, int containerId,
                                          Inventory playerInventory, ContainerHandle handle, Component title,
                                          int xSize, int ySize, Identifier textureLocation,
                                          int sheetWidth, int sheetHeight) {
        return forServer(type, containerId, playerInventory, handle, title, xSize, ySize, textureLocation,
                sheetWidth, sheetHeight, java.util.List.of(), java.util.List.of(), null, null, null);
    }

    /** SCREEN-RENDER lane: also carries the statically-drawable extra rects/labels and every
     *  texture they can reference. Resolved by {@link UmbMenuProvider}. */
    public static UmbLegacyMenu forServer(MenuType<UmbLegacyMenu> type, int containerId,
                                          Inventory playerInventory, ContainerHandle handle, Component title,
                                          int xSize, int ySize, Identifier textureLocation,
                                          int sheetWidth, int sheetHeight,
                                          java.util.List<GuiProfile.Rect> rects, java.util.List<GuiProfile.Label> labels,
                                          Identifier[] rectTextures, int[] rectTextureSheetWidths, int[] rectTextureSheetHeights) {
        return forServer(type, containerId, playerInventory, handle, title, xSize, ySize, textureLocation,
                sheetWidth, sheetHeight, rects, labels, rectTextures, rectTextureSheetWidths, rectTextureSheetHeights,
                null, null);
    }

    static UmbLegacyMenu forServer(MenuType<UmbLegacyMenu> type, int containerId,
                                   Inventory playerInventory, ContainerHandle handle, Component title,
                                   int xSize, int ySize, Identifier textureLocation,
                                   int sheetWidth, int sheetHeight,
                                   java.util.List<GuiProfile.Rect> rects, java.util.List<GuiProfile.Label> labels,
                                   Identifier[] rectTextures, int[] rectTextureSheetWidths, int[] rectTextureSheetHeights,
                                   TileHandle tileHandle, FieldPath[] tileFieldRequest,
                                   int[] playerIndices, int[] playerXs, int[] playerYs) {
        SlotData[] slots = handle.slots();
        int[] xs = new int[slots.length];
        int[] ys = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            xs[i] = slots[i].x;
            ys[i] = slots[i].y;
        }
        LegacyContainerAdapter adapter = new LegacyContainerAdapter(handle, slots.length);
        return new UmbLegacyMenu(type, containerId, playerInventory, handle, adapter, xs, ys,
                new HandleContainerData(handle), title, xSize, ySize, textureLocation, sheetWidth, sheetHeight,
                rects, labels, rectTextures, rectTextureSheetWidths, rectTextureSheetHeights,
                tileHandle, tileFieldRequest, playerIndices, playerXs, playerYs);
    }

    /** TILE-FIELD-SNAPSHOT lane: also carries the live tile handle + its per-GUI FieldPath[]
     *  request (see {@link GuiProfile.GuiEntry#tileFieldRefs}), so {@link #broadcastChanges} can
     *  refresh {@link TileSnapshotChannel} once per server tick. {@code tileHandle} may be null
     *  (no tile entity behind this GUI, or the bridge could not create one) and
     *  {@code tileFieldRequest} may be null/empty (this GUI needs no tile fields) — both are
     *  treated as "nothing to snapshot", never an error. */
    public static UmbLegacyMenu forServer(MenuType<UmbLegacyMenu> type, int containerId,
                                          Inventory playerInventory, ContainerHandle handle, Component title,
                                          int xSize, int ySize, Identifier textureLocation,
                                          int sheetWidth, int sheetHeight,
                                          java.util.List<GuiProfile.Rect> rects, java.util.List<GuiProfile.Label> labels,
                                          Identifier[] rectTextures, int[] rectTextureSheetWidths, int[] rectTextureSheetHeights,
                                          TileHandle tileHandle, FieldPath[] tileFieldRequest) {
        SlotData[] slots = handle.slots();
        int[] xs = new int[slots.length];
        int[] ys = new int[slots.length];
        for (int i = 0; i < slots.length; i++) {
            xs[i] = slots[i].x;
            ys[i] = slots[i].y;
        }
        LegacyContainerAdapter adapter = new LegacyContainerAdapter(handle, slots.length);
        return new UmbLegacyMenu(type, containerId, playerInventory, handle, adapter, xs, ys,
                new HandleContainerData(handle), title, xSize, ySize, textureLocation, sheetWidth, sheetHeight,
                rects, labels, rectTextures, rectTextureSheetWidths, rectTextureSheetHeights,
                tileHandle, tileFieldRequest, null, null, null);
    }

    /** Back-compat: see the {@code forServer} overload above. */
    public static UmbLegacyMenu forClient(MenuType<UmbLegacyMenu> type, int containerId,
                                          Inventory playerInventory, int[] xs, int[] ys, int dataCount, Component title) {
        return forClient(type, containerId, playerInventory, xs, ys, dataCount, title,
                DEFAULT_X_SIZE, DEFAULT_Y_SIZE, null, DEFAULT_SHEET_SIZE, DEFAULT_SHEET_SIZE);
    }

    /** Task B: the real GUI size + background texture, carried from the server via
     *  {@link UmbMenuRegistration.Layout} (see {@code clientCreateMenuHelper}). */
    public static UmbLegacyMenu forClient(MenuType<UmbLegacyMenu> type, int containerId,
                                          Inventory playerInventory, int[] xs, int[] ys, int dataCount, Component title,
                                          int xSize, int ySize, Identifier textureLocation,
                                          int sheetWidth, int sheetHeight) {
        return forClient(type, containerId, playerInventory, xs, ys, dataCount, title, xSize, ySize, textureLocation,
                sheetWidth, sheetHeight, java.util.List.of(), java.util.List.of(), null, null, null);
    }

    static UmbLegacyMenu forClient(MenuType<UmbLegacyMenu> type, int containerId,
                                   Inventory playerInventory, int[] xs, int[] ys, int dataCount, Component title,
                                   int xSize, int ySize, Identifier textureLocation,
                                   int sheetWidth, int sheetHeight,
                                   java.util.List<GuiProfile.Rect> rects, java.util.List<GuiProfile.Label> labels,
                                   Identifier[] rectTextures, int[] rectTextureSheetWidths, int[] rectTextureSheetHeights,
                                   int[] playerIndices, int[] playerXs, int[] playerYs) {
        SimpleContainer sc = new SimpleContainer(xs.length);
        return new UmbLegacyMenu(type, containerId, playerInventory, null, sc, xs, ys,
                new SimpleContainerData(Math.max(0, dataCount)), title,
                xSize, ySize, textureLocation, sheetWidth, sheetHeight,
                rects, labels, rectTextures, rectTextureSheetWidths, rectTextureSheetHeights,
                null, null, playerIndices, playerXs, playerYs);
    }

    /** SCREEN-RENDER lane: also carries the statically-drawable extra rects/labels and every
     *  texture they can reference, carried from the server via {@link UmbMenuRegistration.Layout}
     *  (see {@code clientCreateMenuHelper}) - this is the menu instance {@link UmbLegacyScreen}
     *  actually renders. */
    public static UmbLegacyMenu forClient(MenuType<UmbLegacyMenu> type, int containerId,
                                          Inventory playerInventory, int[] xs, int[] ys, int dataCount, Component title,
                                          int xSize, int ySize, Identifier textureLocation,
                                          int sheetWidth, int sheetHeight,
                                          java.util.List<GuiProfile.Rect> rects, java.util.List<GuiProfile.Label> labels,
                                          Identifier[] rectTextures, int[] rectTextureSheetWidths, int[] rectTextureSheetHeights) {
        SimpleContainer sc = new SimpleContainer(xs.length);
        // The client-side mirror never touches a bridge type directly (tileHandle=null) - it reads
        // TileSnapshotChannel by containerId instead (see currentTileFieldLookup()).
        return new UmbLegacyMenu(type, containerId, playerInventory, null, sc, xs, ys,
                new SimpleContainerData(Math.max(0, dataCount)), title,
                xSize, ySize, textureLocation, sheetWidth, sheetHeight,
                rects, labels, rectTextures, rectTextureSheetWidths, rectTextureSheetHeights,
                null, null, null, null, null);
    }

    public Component displayTitle() {
        return displayTitle;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = index >= 0 && index < this.slots.size() ? this.slots.get(index) : null;
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;

        // Slot.mayPlace call), so it can put ore into a fuel slot.  Let the legacy Container own
        // transferStackInSlot/func_82846_b whenever the real handle supports it; null means an
        // older/fake handle and retains the bounded native fallback below.  An empty result is
        // only final when the legacy source actually changed: several real Containers return
        // null/empty from func_82846_b while leaving the source untouched, and treating that
        // result as handled is the live QUICK_MOVE no-op failure.
        if (handle != null) {
            try {
                StackData before = LegacyStackConv.toLegacy(slot.getItem());
                StackData moved = handle.quickMove(index, machineSlotCount);
                if (moved != null) {
                    if (machineContainer instanceof LegacyContainerAdapter adapter) {
                        adapter.invalidateCache();
                    }
                    if (!moved.isEmpty() || !sameLegacyStack(before,
                            LegacyStackConv.toLegacy(slot.getItem()))) {
                        return LegacyStackConv.toNative(moved);
                    }
                    // Empty + unchanged source means the legacy transfer rejected/no-op'd. Let
                    // the bounded native path below attempt the same menu operation rather than
                    // swallowing the user's QUICK_MOVE packet.
                }
            } catch (Throwable t) {
                AgentLog.error("UmbLegacyMenu.quickMoveStack.legacy", t, 2);
            }
        }

        ItemStack original = slot.getItem();
        ItemStack copy = original.copy();
        boolean moved;
        if (index < machineSlotCount) {
            moved = moveItemStackTo(original, machineSlotCount, this.slots.size(), true);
        } else {
            moved = moveItemStackTo(original, 0, machineSlotCount, false);
        }
        if (!moved) return ItemStack.EMPTY;
        if (original.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copy;
    }

    /** Compares the source identity relevant to deciding whether a legacy transfer did anything. */
    private static boolean sameLegacyStack(StackData a, StackData b) {
        if (a == null || b == null) return a == b;
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty();
        return a.count == b.count && a.damage == b.damage
                && java.util.Objects.equals(a.legacyId, b.legacyId);
    }

    /**
     * loops {@code Slot.getItem()} over every slot to detect+sync changes). Invalidate
     * {@link LegacyContainerAdapter}'s per-pass read cache BEFORE the vanilla loop runs so the
     * machine-slot side of that loop costs exactly one {@code ContainerHandle.slots()} call for
     * this whole pass (see {@link LegacyContainerAdapter}'s class javadoc) instead of one per
     * machine slot -- this is also what makes a legacy machine's produced output (or consumed
     * fuel) show up in the GUI on its own, without reopening it: each tick's fresh fetch picks up
     * whatever the legacy TileEntity changed since the last one.
     */
    @Override
    public void broadcastChanges() {
        if (machineContainer instanceof LegacyContainerAdapter adapter) {
            adapter.invalidateCache();
        }
        // not per rendered frame — the correctness risk the brief calls out explicitly. This is the
        // ONE cross-loader TileHandle.snapshotFields call per open GUI per tick; UmbLegacyScreen
        // only ever reads the cached result (see currentTileFieldLookup(), TileSnapshotChannel).
        // A no-op when tileHandle is null (no tile behind this GUI, or the client-side mirror) or
        // this GUI needs no tile fields at all (tileFieldRequest.length==0, the common case).
        if (tileHandle != null) {
            TileSnapshotChannel.refresh(containerId, tileHandle, tileFieldRequest);
        }
        // Capture the persistent legacy GuiContainer once per server tick. The mesh is immutable
        // after seal() and the client screen only reads this same-process handoff on the render
        // thread; no legacy object or GL state crosses into the host.
        if (handle != null) {
            try {
                dev.umb.bridge.api.LegacyBridge bridge = UmbBridgeHost.get();
                stashGuiMesh(containerId, bridge == null ? null
                        : bridge.renderGui(null, 0.0F, xSize, ySize));
            } catch (Throwable t) {
                stashGuiMesh(containerId, null);
                AgentLog.errorOnce("UmbLegacyMenu.renderGui:" + containerId, t, 2);
            }
        }
        // The cache is scoped to this vanilla broadcast pass.  Invalidate again after the loop so
        // a legacy tick (or a hopper) that mutates the container before the next menu operation
        // cannot be masked by the previous pass's SlotData snapshot.
        try {
            super.broadcastChanges();
        } finally {
            if (machineContainer instanceof LegacyContainerAdapter adapter) {
                adapter.invalidateCache();
            }
        }
    }

    @Override
    public void removed(Player player) {
        // A stale snapshot under a reused containerId must never be served to a DIFFERENT GUI that
        // happens to reuse the same id later.
        TileSnapshotChannel.clear(containerId);
        stashGuiMesh(containerId, null);
        if (handle != null) {
            try {
                handle.close();
            } catch (Throwable t) {
                AgentLog.error("UmbLegacyMenu.removed", t, 2);
            }
        }
        super.removed(player);
    }

    @Override
    public boolean stillValid(Player player) {
        // UmbLegacyBlockEntity disables (poisons) the block entity if the bridge fails; that path
        // is responsible for closing the menu. Nothing legacy-specific to check here.
        return true;
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (LegacyGuiClickChannel.isKeyRequest(id)) {
            LegacyGuiClickChannel.KeyRequest request = LegacyGuiClickChannel.takeKey(id);
            if (request == null) return false;
            dev.umb.bridge.api.LegacyBridge bridge = UmbBridgeHost.get();
            return bridge != null && bridge.guiKeyTyped(packetGuiClass, request.typedChar,
                    request.keyCode);
        }
        if (LegacyGuiClickChannel.isRequest(id)) {
            LegacyGuiClickChannel.Request request = LegacyGuiClickChannel.take(id);
            if (request == null) return false;
            dev.umb.bridge.api.LegacyBridge bridge = UmbBridgeHost.get();
            return bridge != null && bridge.guiMouseClick(packetGuiClass, packetX, packetY, packetZ,
                    request.guiX, request.guiY, request.button, request.screenX, request.screenY);
        }
        // 26.2's ServerboundContainerButtonClickPacket is dispatched by the vanilla server to
        // privately by ContainerHandleImpl; the resolver invokes only the SRG-grounded
        // func_75140_a/enchantItem(Player,int) route and returns false when absent.
        if (handle != null && LegacyContainerClassResolver.dispatchButton(handle, id)) return true;
        for (GuiProfile.Button b : buttons) {
            if (b.id == id && b.packetResolved && b.packetMessageClass != null) {
                dev.umb.bridge.api.LegacyBridge bridge = UmbBridgeHost.get();
                if (bridge != null) {
                    String[] recipe = new String[b.packetConstructorArgs.size() + 1];
                    recipe[0] = "__messageClass=" + b.packetMessageClass;
                    for (int i = 0; i < b.packetConstructorArgs.size(); i++) {
                        recipe[i + 1] = b.packetConstructorArgs.get(i);
                    }
                    bridge.guiButtonPacket(packetGuiClass, id, packetX, packetY, packetZ, recipe);
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * once per {@code extractBackground}/{@code extractLabels} call (a single
     * {@link TileSnapshotChannel#get} map read, never a cross-loader call), then re-use the returned
     * closure across every rect/label/guard this GUI draws that frame. The underlying value is only
     * ever refreshed at TICK rate by {@link #broadcastChanges} — this method never triggers a fresh
     * read itself, satisfying "snapshot on the tick, cache per frame" directly. Safe to call on
     * EITHER the server-authoritative or the client-side mirror menu: both share the same
     * {@code containerId}, which is all {@link TileSnapshotChannel} keys on.
     */
    public java.util.function.Function<String, Double> currentTileFieldLookup() {
        TileFieldSnapshot s = TileSnapshotChannel.get(containerId);
        return key -> {
            for (int i = 0; i < s.keys.length; i++) {
                if (key.equals(s.keys[i])) {
                    return s.present[i] ? Double.valueOf(s.values[i]) : null;
                }
            }
            return null;
        };
    }

    /** CONTAINER-POLICY lane: a machine slot that enforces the legacy mod's own
     *  {@code Slot.isItemValid} (func_75214_a) via {@link ContainerHandle#canPlace} -- 26.2's
     *  {@code doClick} (5 call sites) and {@code moveItemStackTo} both consult {@code mayPlace}
     *  with no further plumbing. A null handle is the CLIENT-side mirror: stays permissive, the
     *  server menu is authoritative and vanilla resyncs a rejected click. A native stack
     *  {@link LegacyStackConv} cannot map (StackData.EMPTY from a non-empty input) is REJECTED:
     *  the legacy container cannot represent it, so the write-through would silently destroy it. */
    private static final class MachineSlot extends Slot {
        private final ContainerHandle handle;
        private final int machineIndex;

        MachineSlot(Container container, int index, int x, int y, ContainerHandle handle) {
            super(container, index, x, y);
            this.handle = handle;
            this.machineIndex = index;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            if (handle == null || stack.isEmpty()) return true;
            try {
                StackData s = LegacyStackConv.toLegacy(stack);
                if (s.isEmpty()) return false; // unmappable on the legacy side - placing would destroy it
                return handle.canPlace(machineIndex, s);
            } catch (Throwable t) {
                return true; // permissive on bridge failure - a policy check must never brick a GUI
            }
        }
    }

    /** {@link ContainerData} that reads the live bridge on every access -- M1 does not need caching. */
    private static final class HandleContainerData implements ContainerData {
        private final ContainerHandle handle;

        HandleContainerData(ContainerHandle handle) {
            this.handle = handle;
        }

        @Override
        public int get(int index) {
            try {
                int[] d = handle.syncData();
                return index >= 0 && index < d.length ? d[index] : 0;
            } catch (Throwable t) {
                return 0;
            }
        }

        @Override
        public void set(int index, int value) {
            // server-authoritative: the legacy container owns these values, nothing to push back
        }

        @Override
        public int getCount() {
            try {
                return handle.syncData().length;
            } catch (Throwable t) {
                return 0;
            }
        }
    }
}
