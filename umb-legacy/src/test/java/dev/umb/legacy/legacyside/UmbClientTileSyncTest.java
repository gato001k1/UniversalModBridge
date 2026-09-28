package dev.umb.legacy.legacyside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;

/**
 * Bug #26 (Tsar Bomba overlays never draw) gate: the persistent client tile twin must be built
 * with its real constructor, not bare allocation.
 *
 * <p>Root cause it pins: {@code UmbClientWorld.copyTile} used {@code UmbUnsafe.allocate}, so
 * constructor-created helpers (an inventory {@code slots} array, exactly like HBM's bomb tiles)
 * stayed null, and every later NBT re-read sized state from those very helpers
 * ({@code slots = new ItemStack[getSizeInventory()]} with {@code getSizeInventory} reading
 * {@code slots.length}) threw before assigning anything - swallowed forever, leaving
 * inventory-reading GUIs frozen on empty overlays while host-native slots stayed live.</p>
 */
public final class UmbClientTileSyncTest {

    /**
     * The HBM inventory-tile shape, without the mod: a slot array created by the constructor,
     * sized reads, and an NBT round trip. No HBM classes are referenced (the mod jar is never
     * on the unit-test classpath).
     */
    public static final class SizedSlotTile extends TileEntity {
        ItemStack[] slots;

        public SizedSlotTile() {
            slots = new ItemStack[6];
        }

        public int func_70302_i_() {
            return slots.length;
        }

        public ItemStack func_70301_a(int index) {
            return slots[index];
        }

        public void func_145841_b(NBTTagCompound tag) {
            super.func_145841_b(tag);
            NBTTagList list = new NBTTagList();
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] == null) continue;
                NBTTagCompound item = new NBTTagCompound();
                item.func_74774_a("Slot", (byte) i);
                slots[i].func_77955_b(item);
                list.func_74742_a(item);
            }
            tag.func_74782_a("items", list);
        }

        public void func_145839_a(NBTTagCompound tag) {
            super.func_145839_a(tag);
            NBTTagList list = tag.func_150295_c("items", 10);
            slots = new ItemStack[func_70302_i_()];
            for (int i = 0; i < list.func_74745_c(); i++) {
                NBTTagCompound item = list.func_150305_b(i);
                byte slot = item.func_74771_c("Slot");
                if (slot >= 0 && slot < slots.length) {
                    slots[slot] = ItemStack.func_77949_a(item);
                }
            }
        }
    }

    /** A tile with no no-arg constructor: the allocation fallback must still not throw. */
    public static final class NoDefaultCtorTile extends TileEntity {
        public NoDefaultCtorTile(String ignored) {
        }
    }

    @Test
    public void constructPrefersTheRealConstructor() throws Exception {
        SizedSlotTile source = new SizedSlotTile();
        TileEntity twin = LegacyClientTilePresenter.construct(source);
        assertNotNull(twin);
        assertEquals(6, ((SizedSlotTile) twin).func_70302_i_());
    }

    @Test
    public void constructFallsBackWithoutANoArgConstructor() throws Exception {
        TileEntity twin = LegacyClientTilePresenter.construct(
                new NoDefaultCtorTile("x"));
        assertNotNull(twin);
    }

    @Test
    public void syncSizesAndFillsTheTwin() {
        SizedSlotTile server = new SizedSlotTile();
        SizedSlotTile twin;
        try {
            twin = (SizedSlotTile) LegacyClientTilePresenter.construct(server);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        LegacyClientTilePresenter.syncPersistentState(server, twin);
        assertEquals(6, twin.func_70302_i_());
        assertNull(twin.func_70301_a(0));
    }

    @Test
    public void syncNeverThrowsEvenWhenTheTwinLacksInvariants() {
        SizedSlotTile server = new SizedSlotTile();
        TileEntity bare = UmbUnsafe.allocate(SizedSlotTile.class);
        LegacyClientTilePresenter.syncPersistentState(server, bare);
    }
}
