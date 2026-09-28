package dev.umb.legacy.legacyside;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;

import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.StackData;
import dev.umb.legacy.legacyside.input.LegacyClientSelection;

/**
 * The InventoryPlayer facade (facade-fidelity.md section 3).
 * Constructed NORMALLY ({@code super(player)}) - {@code InventoryPlayer}'s own constructor is trivial and safe , unlike World/EntityPlayer, so no {@code Unsafe.allocateInstance} is needed here.
 */
public final class UmbInventoryPlayer extends InventoryPlayer {

    private final HostPlayer host;
    /** Host snapshot per slot at the last pull; slots the host has not changed are kept. */
    private StackData[] lastPulled;

    public UmbInventoryPlayer(EntityPlayer player, HostPlayer host) {
        super(player);
        this.host = host;
    }

    /**
     * Host -&gt; the inherited {@code field_70462_a} array, preserving legacy-owned NBT.
     *
     * <p>INPUT-BRIDGE invariant (gun decider): a slot the host has not changed keeps its
     * facade object, so NBT mutations legacy code makes (gun flags/timers/state) survive
     * across ticks — a blind reconvert every pull would wipe them and no state machine
     * could ever progress. Only genuinely changed slots are reconverted. The selected
     * slot follows the held stack (HBM reads {@code field_70461_c} directly); first
     * hotbar match wins, no match keeps the previous slot. Call before handing control
     * to legacy code.
     *
     * <p>Synchronized: pulls run on both the server thread (tick) and the netty thread
     * (payload receipt), and an unsynchronized lastPulled/array pair reconverts
     * spuriously under races — exactly the flakiness the live game showed around
     * input frames. push() shares the monitor.
     */
    public synchronized void pull() {
        int n = Math.min(field_70462_a.length, host.getInventorySize());
        if (lastPulled == null || lastPulled.length < field_70462_a.length) {
            lastPulled = new StackData[field_70462_a.length];
        }
        for (int i = 0; i < n; i++) {
            StackData now = host.getInventorySlot(i);
            if (same(lastPulled[i], now)) {
                continue;
            }
            field_70462_a[i] = UmbItemConv.toLegacy(now);
            lastPulled[i] = now;
        }
        syncSelected();
    }

    private void syncSelected() {
        // Client authority first: input frames track the client's selection exactly
        // (change-gated), while server selection goes stale under automation gives.
        Integer clientSlot = LegacyClientSelection.lastSlotFor(
                host == null ? null : host.getName());
        if (clientSlot != null) {
            field_70461_c = clientSlot.intValue();
            return;
        }
        StackData held = host.getHeldItem();
        if (held == null || held.isEmpty()) {
            return;
        }
        for (int i = 0; i < 9 && i < field_70462_a.length; i++) {
            if (same(host.getInventorySlot(i), held)) {
                field_70461_c = i;
                return;
            }
        }
    }

    /**
 * Logical sameness for host snapshots: item identity + count + damage.
 * NBT is deliberately EXCLUDED — host NBT churns for reasons outside legacy control , and comparing it reconverts the facade stack away from under a running state machine.
 */
    static boolean same(StackData a, StackData b) {
        boolean emptyA = a == null || a.isEmpty();
        boolean emptyB = b == null || b.isEmpty();
        if (emptyA || emptyB) {
            return emptyA && emptyB;
        }
        if (a.count != b.count || a.damage != b.damage) {
            return false;
        }
        return a.legacyId == null ? b.legacyId == null : a.legacyId.equals(b.legacyId);
    }

    /** The inherited array -&gt; host. Call after legacy code returns control. */
    public synchronized void push() {
        int n = Math.min(field_70462_a.length, host.getInventorySize());
        for (int i = 0; i < n; i++) {
            StackData pushed = UmbItemConv.toStackData(field_70462_a[i]);
            host.setInventorySlot(i, pushed);
            // The host now carries exactly what the facade holds: remember it, or the
            // next pull would see a "change" and reconvert the just-pushed state away.
            if (lastPulled != null && i < lastPulled.length) {
                lastPulled[i] = pushed;
            }
        }
    }

    @Override
    public int func_70302_i_() {
        return field_70462_a.length;
    }

    @Override
    public ItemStack func_70301_a(int slot) {
        return slot >= 0 && slot < field_70462_a.length ? field_70462_a[slot] : null;
    }

    @Override
    public ItemStack func_70298_a(int slot, int amount) {
        if (slot < 0 || slot >= field_70462_a.length || field_70462_a[slot] == null) {
            return null;
        }
        ItemStack stack = field_70462_a[slot];
        int take = Math.min(amount, stack.field_77994_a);
        ItemStack out = stack.func_77946_l();
        out.field_77994_a = take;
        stack.field_77994_a -= take;
        if (stack.field_77994_a <= 0) {
            field_70462_a[slot] = null;
        }
        return out;
    }

    @Override
    public ItemStack func_70304_b(int slot) {
        if (slot < 0 || slot >= field_70462_a.length) {
            return null;
        }
        ItemStack out = field_70462_a[slot];
        field_70462_a[slot] = null;
        return out;
    }

    @Override
    public void func_70299_a(int slot, ItemStack stack) {
        if (slot >= 0 && slot < field_70462_a.length) {
            field_70462_a[slot] = stack;
        }
    }

    @Override
    public void func_70296_d() {
        // markDirty - the real write-back happens in push() at the interaction boundary, not here
    }

    @Override
    public boolean func_70300_a(EntityPlayer player) {
        return true;
    }

    @Override
    public int func_70297_j_() {
        return 64;
    }

    @Override
    public String func_145825_b() {
        return host.getName();
    }
}
