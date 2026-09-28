package dev.umb.legacy1165.legacyside;

import java.util.ArrayList;
import java.util.List;

import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;

import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.container.Container;
import net.minecraft.inventory.container.Slot;
import net.minecraft.item.ItemStack;

/**
 * {@code dev.umb.bridge.api.ContainerHandle} over a live 1.16.5 {@code Container} - a port of
 * 1.7.10's {@code ContainerHandleImpl} with every SRG member re-grounded in-lane (several
 * numbers are stable across the eras, each verified, none assumed):
 *
 * <p>Machine slots are every {@code Slot} in {@code field_75151_b} whose backing
 * {@code IInventory} ({@code field_75224_c}) is NOT the probe player's own
 * {@code field_71071_by} - "player slots are host-side" (THE BOUNDARY CONTRACT).
 * {@code slots()}/{@code setSlot}/{@code takeSlot} index the machine-only subset in list
 * order. Coordinates are constructor-grounded ({@code field_75223_e}=x arg 3,
 * {@code field_75221_f}=y arg 4); content ops are {@code func_75211_c} (get),
 * {@code func_75215_d} + {@code func_75218_e} (set + changed), {@code func_75209_a} (take),
 * {@code func_75214_a} (place policy).</p>
 */
public final class ContainerHandle1165 implements ContainerHandle {

    private final Container container;
    /**
     * ironchest-visuals lane: the player entity behind this container (may be null for
     * pre-existing callers). Host-side {@code LegacyContainerClassResolver} reads this
     * field by NAME ("player") plus the container above ("container") to recover the 36
     * player-slot positions reflectively with era-stable SRG names
     * ({@code field_71071_by} inventory, {@code field_75151_b} slots,
     * {@code field_75224_c}/{@code field_75225_a}/{@code field_75223_e}/
     * {@code field_75221_f} slot data - all verified in this module's javadoc above).
     * No bridge-api change: the names are the contract, same idiom as the 1.7.10
     * {@code ContainerHandleImpl}'s own "container"/"player" fields.
     */
    private final net.minecraft.entity.player.PlayerEntity player;
    private final List<Slot> machineSlots;
    private final String title;

    public ContainerHandle1165(Container container, PlayerInventory playerInventory, String title) {
        this(container, playerInventory == null ? null : playerInventory.field_70458_d, title);
    }

    public ContainerHandle1165(Container container,
            net.minecraft.entity.player.PlayerEntity player, String title) {
        this.container = container;
        this.player = player;
        this.title = title;
        this.machineSlots = new ArrayList<Slot>();
        PlayerInventory inv = player == null ? null : player.field_71071_by;
        List<Slot> all = container.field_75151_b;
        for (Slot slot : all) {
            if (slot.field_75224_c != inv) {
                machineSlots.add(slot);
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
            Slot slot = machineSlots.get(i);
            out[i] = new SlotData(i, slot.field_75223_e, slot.field_75221_f,
                    UmbItemConv1165.toStackData(slot.func_75211_c()));
        }
        return out;
    }

    @Override
    public void setSlot(int index, StackData stack) {
        if (index < 0 || index >= machineSlots.size()) {
            return;
        }
        Slot slot = machineSlots.get(index);
        slot.func_75215_d(UmbItemConv1165.toLegacy(stack));
        slot.func_75218_e();
    }

    @Override
    public StackData takeSlot(int index, int amount) {
        if (index < 0 || index >= machineSlots.size()) {
            return null;
        }
        Slot slot = machineSlots.get(index);
        ItemStack taken = slot.func_75209_a(amount);
        slot.func_75218_e();
        return UmbItemConv1165.toStackData(taken);
    }

    @Override
    public boolean canPlace(int index, StackData stack) {
        if (index < 0 || index >= machineSlots.size()) {
            return false;
        }
        ItemStack legacy = UmbItemConv1165.toLegacy(stack);
        if (legacy.func_190926_b()) {
            return false;
        }
        return machineSlots.get(index).func_75214_a(legacy);
    }

    @Override
    public int[] syncData() {
        // Progress ints (ContainerData) via the holder list; plain chests register none, so
        // this is honestly empty for them and real where mods add holders. Private list read
        // reflectively (unnamed modules allow it; documented) - there is no public accessor.
        try {
            java.lang.reflect.Field holdersField =
                    Container.class.getDeclaredField("field_216964_d");
            holdersField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.List<net.minecraft.util.IntReferenceHolder> holders =
                    (java.util.List<net.minecraft.util.IntReferenceHolder>) holdersField
                            .get(container);
            int[] out = new int[holders.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = holders.get(i).func_221495_b();
            }
            return out;
        } catch (Throwable t) {
            return new int[0];
        }
    }

    @Override
    public void close() {
        // Headless probe: nothing to sync or send. Production close flows live host-side.
    }
}
