package dev.umb.hostagent.content;

import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileHandle;
import dev.umb.hostagent.AgentLog;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * 26.2 automation view over the legacy tile's IInventory/ISidedInventory.  It deliberately asks
 * the owning block entity for its current handle on every operation: handle creation is lazy and
 * a poisoned tile must become an honest empty capability, never a stale inventory snapshot.
 * Legacy side ordinals are supplied as Direction.get3DDataValue(), which is the 0..5 ordering
 * expected by ISidedInventory (grounded against the 1.7.10 methods.csv names in TileHandleImpl).
 */
final class LegacyWorldlyContainerAdapter implements WorldlyContainer {

    private final UmbLegacyBlockEntity owner;

    LegacyWorldlyContainerAdapter(UmbLegacyBlockEntity owner) {
        this.owner = owner;
    }

    private TileHandle handle() {
        try {
            TileHandle h = owner.currentHandle();
            return h != null && h.isValid() ? h : null;
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.handle", t, 2);
            return null;
        }
    }

    private static int side(Direction direction) {
        return direction == null ? -1 : direction.get3DDataValue();
    }

    @Override
    public int getContainerSize() {
        TileHandle h = handle();
        try {
            return h == null ? 0 : Math.max(0, h.inventorySize());
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.getContainerSize", t, 2);
            return 0;
        }
    }

    @Override
    public boolean isEmpty() {
        for (int i = 0; i < getContainerSize(); i++) {
            if (!getItem(i).isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getItem(int slot) {
        TileHandle h = handle();
        try {
            return h == null ? ItemStack.EMPTY : LegacyStackConv.toNative(h.inventoryItem(slot));
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.getItem", t, 2);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        TileHandle h = handle();
        try {
            return h == null ? ItemStack.EMPTY : LegacyStackConv.toNative(h.inventoryRemove(slot, amount));
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.removeItem", t, 2);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        ItemStack current = getItem(slot);
        if (current.isEmpty()) return ItemStack.EMPTY;
        TileHandle h = handle();
        try {
            return h == null ? ItemStack.EMPTY : LegacyStackConv.toNative(h.inventoryRemove(slot, current.getCount()));
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.removeItemNoUpdate", t, 2);
            return ItemStack.EMPTY;
        }
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        TileHandle h = handle();
        if (h == null) return;
        try {
            h.inventorySet(slot, LegacyStackConv.toLegacy(stack));
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.setItem", t, 2);
        }
    }

    @Override
    public int getMaxStackSize() {
        TileHandle h = handle();
        try {
            return h == null ? 64 : Math.max(1, h.inventoryMaxStackSize());
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.getMaxStackSize", t, 2);
            return 64;
        }
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        TileHandle h = handle();
        try {
            return h != null && h.inventoryCanPlace(slot, LegacyStackConv.toLegacy(stack));
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.canPlaceItem", t, 2);
            return false;
        }
    }

    @Override
    public void setChanged() {
        TileHandle h = handle();
        if (h == null) return;
        try {
            h.inventoryChanged();
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.setChanged", t, 2);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return owner.getLevel() != null && !owner.isRemoved();
    }

    @Override
    public void clearContent() {
        TileHandle h = handle();
        if (h == null) return;
        for (int i = 0; i < getContainerSize(); i++) {
            try {
                h.inventorySet(i, StackData.EMPTY);
            } catch (Throwable t) {
                AgentLog.error("LegacyWorldlyContainerAdapter.clearContent", t, 1);
            }
        }
        setChanged();
    }

    @Override
    public int[] getSlotsForFace(Direction direction) {
        TileHandle h = handle();
        try {
            return h == null ? new int[0] : h.inventorySlotsForSide(side(direction));
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.getSlotsForFace", t, 2);
            return new int[0];
        }
    }

    @Override
    public boolean canPlaceItemThroughFace(int slot, ItemStack stack, Direction direction) {
        TileHandle h = handle();
        try {
            return h != null && h.inventoryCanPlaceThroughFace(slot, LegacyStackConv.toLegacy(stack), side(direction));
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.canPlaceItemThroughFace", t, 2);
            return false;
        }
    }

    @Override
    public boolean canTakeItemThroughFace(int slot, ItemStack stack, Direction direction) {
        TileHandle h = handle();
        try {
            return h != null && h.inventoryCanTakeThroughFace(slot, LegacyStackConv.toLegacy(stack), side(direction));
        } catch (Throwable t) {
            AgentLog.error("LegacyWorldlyContainerAdapter.canTakeItemThroughFace", t, 2);
            return false;
        }
    }
}
