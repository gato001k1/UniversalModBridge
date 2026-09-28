package dev.umb.hostagent.content;

import dev.umb.bridge.api.StackData;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 6/7 gate: UmbLegacyMenu mirrors a ContainerHandle's slots (machine slots at their legacy
 * x/y) plus the standard 36 player slots, and its ContainerData reads the live handle.
 *
 * The MenuType and Inventory arguments are allocated via {@link TestSupport#allocate} rather than
 * constructed normally: AbstractContainerMenu's ctor only ever STORES the MenuType reference
 * (verified via javap -c -- `putfield menuType`, never dereferenced), and Slot's ctor only stores
 * its Container reference, so a real MenuType (needs the private-ctor+widened-interface dance,
 * see UmbMenuRegistration) or a real player Inventory (needs a live Player/Level) is not required
 * to exercise this class's own slot-mirroring logic headlessly.
 */
class UmbLegacyMenuTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
    }

    @SuppressWarnings("unchecked")
    private static MenuType<UmbLegacyMenu> rawMenuType() {
        return TestSupport.allocate(MenuType.class);
    }

    private static Inventory rawInventory() {
        return TestSupport.allocate(Inventory.class);
    }

    @Test
    void mirrorsMachineSlotCountAndCoordinatesFromTheHandle() {
        FakeLegacyBridge.FakeContainerHandle handle = new FakeLegacyBridge.FakeContainerHandle("Brick Furnace");
        handle.setSlot(0, new StackData("hbm:test_item", 3, 0, null));

        UmbLegacyMenu menu = UmbLegacyMenu.forServer(rawMenuType(), 1, rawInventory(), handle, Component.literal("Brick Furnace"));

        assertEquals(2, menu.machineSlotCount);
        assertEquals(2 + 36, menu.slots.size(), "machine slots + the standard 36 player slots");

        Slot slot0 = menu.slots.get(0);
        Slot slot1 = menu.slots.get(1);
        assertEquals(56, slot0.x);
        assertEquals(17, slot0.y);
        assertEquals(116, slot1.x);
        assertEquals(35, slot1.y);

        // slot0.getItem() materialises a real 26.2 ItemStack via LegacyStackConv -- NOT exercised
        // here: 26.2's item default-component binding (Holder$Reference.bindComponents) is a
        // separate pass this headless suite's plain Bootstrap.bootStrap() call does not reach for
        // vanilla items outside gameplay, and reproducing that pass is out of scope for this
        // lane (see g2-laneB-progress.md). The mapping itself is covered directly below.
        Registrar.LEGACY_ITEMS.put("hbm:test_item", Items.STICK);
        Registrar.LEGACY_VARIANT_ITEMS.put("hbm:test_item@0", Items.STICK);
        assertEquals(Items.STICK, Registrar.LEGACY_VARIANT_ITEMS.get("hbm:test_item@0"),
                "the legacy id+damage -> native item mapping LegacyStackConv relies on");
    }

    @Test
    void mirrorsTheRealHbmBrickFurnaceSlotLayout() {
        // The exact legacy machine-slot coordinates lead-verified in a live 26.2 windowed run of
        // HBM's real ContainerFurnaceBrick (research/out/legacy/win-m2/shots/09-GUI.png), a
        // standard 176x166 1.7.10 GUI. Order matters: this is the order UmbMenuProvider reads
        // ContainerHandle.slots() in and addSlot()s them, so it is also the order client-side
        // clicks/quickMoveStack index into.
        int[] xs = {62, 35, 116, 35};
        int[] ys = {35, 17, 35, 53};
        FakeLegacyBridge.FakeContainerHandle handle =
                new FakeLegacyBridge.FakeContainerHandle("Bricked Furnace", xs, ys);

        UmbLegacyMenu menu = UmbLegacyMenu.forServer(rawMenuType(), 9, rawInventory(), handle,
                Component.literal("Bricked Furnace"));

        assertEquals(4, menu.machineSlotCount, "the 4 machine slots HBM's ContainerFurnaceBrick has");
        assertEquals(4 + 36, menu.slots.size(), "4 machine slots + the standard 36 player slots");
        for (int i = 0; i < xs.length; i++) {
            Slot s = menu.slots.get(i);
            assertEquals(xs[i], s.x, "slot " + i + " x");
            assertEquals(ys[i], s.y, "slot " + i + " y");
        }

        // The standard vanilla anchor for a chest-style 176x166 panel: main inventory at (8,84)
        // (3 rows of 9, 18px apart -- rows at y=84,102,120) then the hotbar at (8,142)
        // (addStandardInventorySlots's own +58 offset, javap-verified), matching every vanilla
        // container screen's own layout for a 166-tall panel.
        Slot firstMain = menu.slots.get(4);
        Slot firstHotbar = menu.slots.get(4 + 27);
        assertEquals(8, firstMain.x);
        assertEquals(84, firstMain.y);
        assertEquals(8, firstHotbar.x);
        assertEquals(142, firstHotbar.y);
    }

    @Test
    void dataSlotCountMatchesTheHandlesSyncData() {
        FakeLegacyBridge.FakeContainerHandle handle = new FakeLegacyBridge.FakeContainerHandle("Brick Furnace");
        handle.progress = 5;
        UmbLegacyMenu menu = UmbLegacyMenu.forServer(rawMenuType(), 2, rawInventory(), handle, Component.literal("t"));
        assertEquals(handle.syncData().length, menu.dataCount());
    }

    @Test
    void quickMoveStackDelegatesToTheLegacyHandleBeforeNativeFallback() {
        FakeLegacyBridge.FakeContainerHandle handle = new FakeLegacyBridge.FakeContainerHandle("Brick Furnace");
        Registrar.LEGACY_ITEMS.put("hbm:test_item", Items.STICK);
        Registrar.LEGACY_VARIANT_ITEMS.put("hbm:test_item@0", Items.STICK);
        Registrar.resetLegacyVariantKeyCacheForTest();
        handle.setSlot(0, new StackData("hbm:test_item", 1, 0, null));
        handle.quickMoveResult = new StackData("hbm:test_item", 1, 0, null);

        UmbLegacyMenu menu = UmbLegacyMenu.forServer(rawMenuType(), 12, rawInventory(), handle,
                Component.literal("Brick Furnace"));
        ItemStack moved = menu.quickMoveStack(null, 0);

        assertEquals(1, handle.quickMoveCallCount);
        assertEquals(0, handle.lastQuickMoveIndex);
        assertEquals(handle.slotCount(), handle.lastQuickMoveMachineSlotCount);
        assertEquals(Items.STICK, moved.getItem());
        assertEquals(1, moved.getCount());
    }

    /** CONTAINER-POLICY lane: machine slots enforce the legacy mod's own Slot.isItemValid via
     *  ContainerHandle.canPlace (func_75214_a on the legacy side); player slots stay vanilla. */
    @Test
    void machineSlotEnforcesTheHandlesCanPlacePolicy() {
        FakeLegacyBridge.FakeContainerHandle handle = new FakeLegacyBridge.FakeContainerHandle("Turret");
        UmbLegacyMenu menu = UmbLegacyMenu.forServer(rawMenuType(), 4, rawInventory(), handle, Component.literal("t"));

        // A mappable native stack: registered exactly like mirrorsMachineSlotCount... does.
        Registrar.LEGACY_ITEMS.put("hbm:test_item", Items.STICK);
        Registrar.LEGACY_VARIANT_ITEMS.put("hbm:test_item@0", Items.STICK);
        Registrar.resetLegacyVariantKeyCacheForTest();
        ItemStack stick = new ItemStack(Items.STICK, 1);

        handle.canPlaceResult = false;
        assertFalse(menu.slots.get(0).mayPlace(stick), "the legacy slot policy said no");
        assertEquals(1, handle.canPlaceCallCount);
        assertEquals(0, handle.lastCanPlaceIndex, "machine-only 0-based index, per THE BOUNDARY CONTRACT");
        assertEquals("hbm:test_item", handle.lastCanPlaceStack.legacyId);

        handle.canPlaceResult = true;
        assertTrue(menu.slots.get(1).mayPlace(stick), "the legacy slot policy said yes");
        assertEquals(1, handle.lastCanPlaceIndex);

        // Player slots (index >= machineSlotCount) are plain vanilla Slots -- the legacy policy
        // must never be consulted for the player's own inventory.
        int callsBefore = handle.canPlaceCallCount;
        assertTrue(menu.slots.get(menu.machineSlotCount + 3).mayPlace(stick));
        assertEquals(callsBefore, handle.canPlaceCallCount);
    }

    /** A native stack LegacyStackConv cannot map (no Registrar/VanillaItemBridge key) must be
     *  REJECTED before it reaches the handle: the legacy container cannot represent it, so the
     *  write-through would silently destroy it. */
    @Test
    void machineSlotRejectsANativeStackTheLegacySideCannotRepresent() {
        FakeLegacyBridge.FakeContainerHandle handle = new FakeLegacyBridge.FakeContainerHandle("Turret");
        UmbLegacyMenu menu = UmbLegacyMenu.forServer(rawMenuType(), 5, rawInventory(), handle, Component.literal("t"));

        // NETHERITE_INGOT does not exist in 1.7.10 and is registered by no test fixture; if this
        // assumption ever breaks, the assertion message points straight at the mapping.
        ItemStack unmappable = new ItemStack(Items.NETHERITE_INGOT, 1);
        assertFalse(LegacyStackConv.isKnownLegacyItem(Items.NETHERITE_INGOT),
                "test premise: netherite exists in neither Registrar's maps nor VanillaItemBridge");

        assertFalse(menu.slots.get(0).mayPlace(unmappable), "unmappable stacks would be destroyed by write-through");
        assertEquals(0, handle.canPlaceCallCount, "rejected before the bridge is ever asked");
    }

    /** A throwing policy check must never brick a working GUI -- permissive on failure. */
    @Test
    void machineSlotStaysPermissiveWhenTheHandleThrows() {
        FakeLegacyBridge.FakeContainerHandle handle = new FakeLegacyBridge.FakeContainerHandle("Turret");
        UmbLegacyMenu menu = UmbLegacyMenu.forServer(rawMenuType(), 6, rawInventory(), handle, Component.literal("t"));

        Registrar.LEGACY_ITEMS.put("hbm:test_item", Items.STICK);
        Registrar.LEGACY_VARIANT_ITEMS.put("hbm:test_item@0", Items.STICK);
        Registrar.resetLegacyVariantKeyCacheForTest();

        handle.canPlaceShouldThrow = true;
        assertTrue(menu.slots.get(0).mayPlace(new ItemStack(Items.STICK, 1)));
        assertEquals(1, handle.canPlaceCallCount, "the bridge WAS asked; the throw was swallowed");
    }

    /** The client-side mirror has no handle -- it stays permissive; the server menu is the
     *  authority and vanilla resyncs a rejected click. */
    @Test
    void clientMirrorMachineSlotsStayPermissive() {
        UmbLegacyMenu menu = UmbLegacyMenu.forClient(rawMenuType(), 7, rawInventory(),
                new int[]{10, 20}, new int[]{30, 40}, 1, Component.literal("t"));
        assertTrue(menu.slots.get(0).mayPlace(new ItemStack(Items.STICK, 1)));
    }

    @Test
    void clientMirrorHasNoLiveHandleButMatchesTheSuppliedGeometry() {
        UmbLegacyMenu menu = UmbLegacyMenu.forClient(rawMenuType(), 3, rawInventory(),
                new int[]{10, 20}, new int[]{30, 40}, 2, Component.literal("t"));
        assertNull(menu.handle);
        assertEquals(2, menu.machineSlotCount);
        assertEquals(2, menu.dataCount());
        assertEquals(10, menu.slots.get(0).x);
        assertEquals(40, menu.slots.get(1).y);
    }
}
