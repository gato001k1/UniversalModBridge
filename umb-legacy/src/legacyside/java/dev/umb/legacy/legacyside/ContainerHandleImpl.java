package dev.umb.legacy.legacyside;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;

import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;

/**
 * {@code dev.umb.bridge.api.ContainerHandle} over a raw legacy {@code Container} (DESIGN.md LANE A
 * step 5/6, facade-fidelity.md section 7). Machine slots are every {@link Slot} in
 * {@code container.field_75151_b} whose backing {@code IInventory} (`field_75224_c`) is NOT the
 * {@link UmbPlayer}'s own {@link UmbInventoryPlayer} - "player slots are host-side" (THE BOUNDARY
 * CONTRACT). {@link #slots()}/{@link #setSlot}/{@link #takeSlot} index into that MACHINE-ONLY
 * subset, 0-based, in {@code field_75151_b} order - not the container's own (player-slots-included)
 * indices.
 *
 * {@code field_75223_e} = xDisplayPosition (arg 3), {@code field_75221_f} = yDisplayPosition
 * (arg 4) - {@code field_75225_a} (getSlotIndex()) and {@code field_75222_d} are inventory-local /
 * container-local indices, neither of which this handle exposes (the machine-only 0-based index is
 * simpler and sufficient for M1).</p>
 */
public final class ContainerHandleImpl implements ContainerHandle {

    private final Container container;
    private final UmbPlayer player;
    private final String title;
    private final List<Slot> allSlots;
    private final List<Slot> machineSlots;
    private final List<Slot> playerSlots;
    private boolean closed;

    public ContainerHandleImpl(Container container, UmbPlayer player, String title) {
        this.container = container;
        this.player = player;
        this.title = title;
        this.allSlots = new ArrayList<Slot>();
        this.machineSlots = new ArrayList<Slot>();
        this.playerSlots = new ArrayList<Slot>();
        @SuppressWarnings("unchecked")
        List<Slot> all = (List<Slot>) (List<?>) container.field_75151_b;
        for (Slot s : all) {
            allSlots.add(s);
            if (s.field_75224_c != player.field_71071_by) {
                machineSlots.add(s);
            } else {
                playerSlots.add(s);
            }
        }
    }

    @Override
    public String title() {
        return title;
    }

    @Override
    public int slotCount() {
        return machineSlots.size();
    }

    @Override
    public SlotData[] slots() {
        SlotData[] out = new SlotData[machineSlots.size()];
        for (int i = 0; i < out.length; i++) {
            Slot s = machineSlots.get(i);
            out[i] = new SlotData(i, s.field_75223_e, s.field_75221_f, UmbItemConv.toStackData(s.func_75211_c()));
        }
        return out;
    }

    @Override
    public void setSlot(int index, StackData s) {
        if (index < 0 || index >= machineSlots.size()) {
            return;
        }
        Slot slot = machineSlots.get(index);
        slot.func_75215_d(UmbItemConv.toLegacy(s));
        slot.func_75218_e();
    }

    @Override
    public StackData takeSlot(int index, int amount) {
        if (index < 0 || index >= machineSlots.size()) {
            return StackData.EMPTY;
        }
        Slot slot = machineSlots.get(index);
        return UmbItemConv.toStackData(slot.func_75209_a(amount));
    }

    @Override
    public boolean canPlace(int index, StackData s) {
        if (index < 0 || index >= machineSlots.size()) {
            return false;
        }
        try {
            // Slot.isItemValid (func_75214_a, fml/conf/methods.csv:1292) - the mod's own slot
            // policy, e.g. a turret's ammo slot rejecting anything but its munition type.
            return machineSlots.get(index).func_75214_a(UmbItemConv.toLegacy(s));
        } catch (Throwable t) {
            // Permissive on failure: a broken policy check must not brick a working GUI.
            System.err.println("[UMB-CONTAINER] isItemValid failed: " + t);
            return true;
        }
    }

    @Override
    public int[] syncData() {
        try {
            container.func_75142_b();
        } catch (Throwable t) {
            System.err.println("[UMB-CONTAINER] detectAndSendChanges failed: " + t);
        }
        return player.syncData();
    }

    /**
     * Delegates shift-click to the legacy container's own transfer policy.  The host menu presents
     * machine slots first and then the player slots in the raw legacy container's order (the same
     * order used by LegacyContainerClassResolver.resolvePlayerSlots), so the bounded translation
     * below reaches the exact raw slot index expected by func_82846_b/transferStackInSlot.
     *
     * func_82846_b is grounded by fml/conf/methods.csv:2604.  Pull/push brackets the call because
     * the legacy Container reads the UmbPlayer inventory directly, while the 26.2 Inventory is
     * authoritative between bridge calls.
     */
    @Override
    public StackData quickMove(int menuIndex, int machineSlotCount) {
        if (machineSlotCount != machineSlots.size()
                || menuIndex < 0 || menuIndex >= machineSlots.size() + playerSlots.size()) {
            return StackData.EMPTY;
        }
        try {
            player.pullInventory();
            Slot source = menuIndex < machineSlots.size()
                    ? machineSlots.get(menuIndex)
                    : playerSlotByInventoryIndex(menuIndex - machineSlots.size());
            if (source == null) return StackData.EMPTY;
            int rawIndex = allSlots.indexOf(source);
            if (rawIndex < 0) return StackData.EMPTY;
            StackData before = UmbItemConv.toStackData(source.func_75211_c());
            if (before == null || before.isEmpty()) return StackData.EMPTY;
            net.minecraft.item.ItemStack moved;
            try {
                moved = container.func_82846_b(player, rawIndex);
            } finally {
                player.pushInventory();
            }
            return moved == null ? StackData.EMPTY : UmbItemConv.toStackData(moved);
        } catch (Throwable t) {
            System.err.println("[UMB-CONTAINER] transferStackInSlot failed: " + t);
            return StackData.EMPTY;
        }
    }

    /**
     * The host menu orders player slots by the 26.2 Inventory's local index (0..35).  A legacy
     * container is free to order the same slots as main inventory first (9..35) and hotbar last
     * (0..8).  Using {@code playerSlots.get(hostIndex)} therefore targets the wrong legacy Slot
     * for the most common furnace/container layout.  Slot.func_75217_a(IInventory,int) is the
     * grounded inventory-local index predicate; use it as the stable cross-era key and retain the
     * raw list only for the final container index passed to func_82846_b.  This also avoids
     * reaching Slot.field_75225_a directly because that legacy field is private in the runtime.
     */
    private Slot playerSlotByInventoryIndex(int inventoryIndex) {
        if (inventoryIndex < 0 || inventoryIndex >= 36) return null;
        for (Slot slot : playerSlots) {
            if (slot != null && slot.func_75217_a(player.field_71071_by, inventoryIndex)) return slot;
        }
        return null;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        LegacyGuiMouseDispatcher.close(player, container);
        try {
            container.func_75134_a(player);
        } catch (Throwable t) {
            System.err.println("[UMB-CONTAINER] onContainerClosed failed: " + t);
        }
    }
}
