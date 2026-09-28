package dev.umb.hostagent.content;

import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;
import dev.umb.hostagent.AgentLog;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * A native 26.2 {@link Container} whose contents live in a legacy {@link ContainerHandle}. This
 * is what backs the machine {@link net.minecraft.world.inventory.Slot}s in {@link UmbLegacyMenu}
 * on the server: every read/write is proxied straight through to the bridge, so the legacy
 * container stays the single source of truth and the 26.2 menu is a live view over it.
 *
 * <p><b>Per-pass read cache:</b> {@code AbstractContainerMenu.broadcastChanges()} (javap-verified,
 * called once per server tick per player with this menu open) calls {@code Slot.getItem()} for
 * EVERY slot in the menu to detect changes to sync to the client -- for an N-slot machine that is
 * N calls to {@link #getItem} per tick. Without caching, each of those calls independently invokes
 * {@link ContainerHandle#slots()}, which crosses the host/legacy classloader boundary and rebuilds
 * a whole {@code SlotData[]} (allocating + converting every slot) -- an O(N) operation, so one
 * {@code broadcastChanges()} pass would cost O(N^2). {@link UmbLegacyMenu#broadcastChanges()}
 * overrides the vanilla method to call {@link #invalidateCache()} once at the START of each pass
 * (before delegating to {@code super.broadcastChanges()}), so the FIRST {@link #getItem} call of
 * that pass does the one real {@code handle.slots()} fetch and every subsequent call in the same
 * pass reuses it -- exactly one cross-loader round trip per tick regardless of slot count. Any
 * WRITE ({@link #setItem}, {@link #removeItem}, {@link #clearContent}) invalidates the cache
 * immediately so a read that happens to land between two {@code broadcastChanges()} passes (e.g. a
 * player's own {@code clicked()} click) never serves stale data -- only same-pass reads are ever
 * cached, and every cached array is thrown away the moment the underlying handle might have
 * changed.
 *
 * All calls are expected on the server thread only (the bridge's own contract).
 */
final class LegacyContainerAdapter implements Container {

    private final ContainerHandle handle;
    private final int size;

    /** Per-pass cache; null means "not fetched yet this pass", see class javadoc. */
    private SlotData[] cachedSlots;

    LegacyContainerAdapter(ContainerHandle handle, int size) {
        this.handle = handle;
        this.size = size;
    }

    /** Called once at the start of each {@code broadcastChanges()} pass -- see class javadoc. */
    void invalidateCache() {
        cachedSlots = null;
    }

    private SlotData[] cachedOrFetchSlots() {
        SlotData[] s = cachedSlots;
        if (s == null) {
            s = handle.slots();
            cachedSlots = s;
        }
        return s;
    }

    @Override
    public int getContainerSize() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < size; i++) {
            if (!getItem(i).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        try {
            for (var s : cachedOrFetchSlots()) {
                if (s.index == slot) return LegacyStackConv.toNative(s.stack);
            }
            return ItemStack.EMPTY;
        } catch (Throwable t) {
            AgentLog.error("LegacyContainerAdapter.getItem", t, 2);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        try {
            StackData taken = handle.takeSlot(slot, amount);
            return LegacyStackConv.toNative(taken);
        } catch (Throwable t) {
            AgentLog.error("LegacyContainerAdapter.removeItem", t, 2);
            return ItemStack.EMPTY;
        } finally {
            // the handle's real contents may have just changed -- never serve a stale read
            cachedSlots = null;
        }
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack whole = getItem(slot);
        if (whole.isEmpty()) return ItemStack.EMPTY;
        return removeItem(slot, whole.getCount());
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        try {
            handle.setSlot(slot, LegacyStackConv.toLegacy(stack));
        } catch (Throwable t) {
            AgentLog.error("LegacyContainerAdapter.setItem", t, 2);
        } finally {
            // the handle's real contents may have just changed -- never serve a stale read
            cachedSlots = null;
        }
    }

    @Override
    public void setChanged() {
        // the bridge is authoritative and pushes its own state; nothing to flush here
    }

    @Override
    public boolean stillValid(Player player) {
        return true; // UmbLegacyBlockEntity disables the whole menu path if the bridge dies
    }

    @Override
    public void clearContent() {
        for (int i = 0; i < size; i++) {
            try {
                handle.setSlot(i, StackData.EMPTY);
            } catch (Throwable t) {
                AgentLog.error("LegacyContainerAdapter.clearContent", t, 1);
            }
        }
        cachedSlots = null;
    }
}
