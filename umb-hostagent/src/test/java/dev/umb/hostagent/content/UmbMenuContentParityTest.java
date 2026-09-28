package dev.umb.hostagent.content;

import dev.umb.hostagent.Hooks;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Invariant regression test requested for the "player-inventory slots render empty" M1 bug:
 * builds the SERVER-side {@link UmbLegacyMenu} via the real {@link UmbMenuProvider} path and the
 * CLIENT-side one via the REAL production dispatch the live client uses --
 * {@code UmbMenuRegistration.menuType().create(int, Inventory)} -&gt;
 * {@code MenuType$MenuSupplier.create(...)} -&gt; the ASM-generated {@code UmbMenuSupplierGen} -&gt;
 * {@code UmbMenuRegistration.clientCreateMenuHelper} (not a direct call to the helper) -- with a
 * known {@link ItemStack} placed in a real, content-backed {@link Inventory} up front, then
 * asserts both menus have the same slot count/order and that the corresponding player-inventory
 * slot reports that exact stack on both sides.
 *
 * <p>Investigation note: this invariant already held before and after the actual fix (see
 * {@link HostPlayerImplInventoryRoundTripTest} for the real regression this session found and
 * fixed) -- {@link UmbLegacyMenu}'s single private constructor is used, unmodified, by both
 * {@link UmbLegacyMenu#forServer} and {@link UmbLegacyMenu#forClient}, so server and client always
 * added the same slots in the same order over the same real {@code Inventory} reference. The
 * actual defect was upstream of menu construction entirely: {@code HostPlayerImpl.setInventorySlot}
 * was erasing real, unmappable native items (plain vanilla items with no legacy id) during
 * {@code LegacyBridgeImpl.activate()}'s unconditional inventory pull/push round trip, so by the
 * time ANY menu -- server or client -- was built, the player's own real {@code Inventory} object
 * was already missing the items. This test is kept as a standing invariant: if a future change
 * ever DOES cause the two menus to diverge in slot count/order/content, it will catch that
 * class of regression even though it was not the cause of this particular bug.</p>
 */
class UmbMenuContentParityTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
        Items.COAL.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
    }

    private static Inventory realInventoryWith(int index, ItemStack stack) throws ReflectiveOperationException {
        Inventory inv = TestSupport.allocate(Inventory.class);
        Field itemsField = Inventory.class.getDeclaredField("items");
        itemsField.setAccessible(true);
        NonNullList<ItemStack> items = NonNullList.withSize(36, ItemStack.EMPTY);
        itemsField.set(inv, items);
        inv.setItem(index, stack);
        return inv;
    }

    @Test
    void serverAndClientMenusAgreeOnSlotCountOrderAndPlayerInventoryContent() throws Exception {
        TestSupport.ensureBootstrapped();
        assertTrue(Hooks.hasRun(), "Hooks.beforeFreeze must have run (requires the real -javaagent attach)");
        assertNotNull(UmbMenuRegistration.menuType());

        FakeLegacyBridge.FakeContainerHandle handle = new FakeLegacyBridge.FakeContainerHandle(
                "Bricked Furnace", new int[]{62, 35, 116, 35}, new int[]{35, 17, 35, 53});
        UmbMenuProvider provider = new UmbMenuProvider(handle, Component.literal("Bricked Furnace"));

        int containerId = 55055;
        Inventory serverInv = realInventoryWith(2, new ItemStack(Items.COAL, 5));
        AbstractContainerMenu serverMenu = provider.createMenu(containerId, serverInv, null);

        // THE real production client dispatch: MenuType.create(int, Inventory) ->
        // MenuType$MenuSupplier.create(...) -> the ASM-generated UmbMenuSupplierGen ->
        // clientCreateMenuHelper -- not a direct call to the helper.
        Inventory clientInv = realInventoryWith(2, new ItemStack(Items.COAL, 5));
        AbstractContainerMenu clientMenu = UmbMenuRegistration.menuType().create(containerId, clientInv);

        assertEquals(serverMenu.slots.size(), clientMenu.slots.size(),
                "server and client menus must add the same number of slots");
        int serverMachineSlotCount = ((UmbLegacyMenu) serverMenu).machineSlotCount;
        assertEquals(serverMachineSlotCount, ((UmbLegacyMenu) clientMenu).machineSlotCount,
                "server and client menus must agree on where player-inventory slots start");

        // Inventory container index 2 is a HOTBAR slot (indices 0-8 are the hotbar,
        // addInventoryHotbarSlots; 9-35 are the main inventory, addInventoryExtendedSlots) --
        // addStandardInventorySlots javap-verified adds the 27 main-inventory slots FIRST, then
        // the 9 hotbar slots, so hotbar index i lands at menu slot machineSlotCount + 27 + i.
        int expectedSlotIndex = serverMachineSlotCount + 27 + 2;

        assertSame(Items.COAL, serverMenu.slots.get(expectedSlotIndex).getItem().getItem(),
                "server-side player-inventory slot must reflect the real Inventory contents");
        assertSame(Items.COAL, clientMenu.slots.get(expectedSlotIndex).getItem().getItem(),
                "client-side player-inventory slot must reflect the real Inventory contents");
    }

    @Test
    void clientLayoutRestoresLegacyGuiPacketContextForFallbackClicks() throws Exception {
        TestSupport.ensureBootstrapped();
        int containerId = 55056;
        UmbMenuRegistration.LAYOUTS.put(containerId,
                new UmbMenuRegistration.Layout(new int[0], new int[0], 0,
                        176, 166, null, 256, 256,
                        java.util.List.of(), java.util.List.of(), null, null, null,
                        null, null, null, java.util.List.of(),
                        "com.hbm.inventory.gui.GUIMachineBattery", 74, 68, -41));

        UmbLegacyMenu clientMenu = (UmbLegacyMenu) UmbMenuRegistration.menuType().create(
                containerId, realInventoryWith(0, ItemStack.EMPTY));

        assertEquals("com.hbm.inventory.gui.GUIMachineBattery", clientMenu.packetGuiClass,
                "client fallback click must retain the legacy GUI class");
        assertEquals(74, clientMenu.packetX);
        assertEquals(68, clientMenu.packetY);
        assertEquals(-41, clientMenu.packetZ);
    }
}
