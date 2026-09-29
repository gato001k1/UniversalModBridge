package dev.umb.hostagent.content;

import dev.umb.bridge.api.StackData;
import dev.umb.hostagent.HostAgent;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G2 live-smelt-test regression gate: proves {@link UmbLegacyMenu#forServer}'s machine slots are a
 * LIVE VIEW of the real {@link dev.umb.bridge.api.ContainerHandle} through the actual
 * {@link net.minecraft.world.inventory.Slot} objects the menu hands out -- not a one-shot
 * snapshot copy -- in both directions:
 *
 * <ol>
 *   <li>a write made THROUGH the menu's own {@code Slot} (exactly what a player's click ends up
 *       {@code Container.setItem(int, ItemStack)}) reaches the {@code ContainerHandle} via
 *       {@code setSlot(index, StackData)} with the right legacy id and count;</li>
 *   <li>a change made directly to the handle (standing in for the legacy machine consuming fuel or
 *       producing an output on its own tick) is visible through the menu's {@code Slot} after a
 *       {@code broadcastChanges()} pass, with no menu reconstruction; and</li>
 *   <li>a real vanilla item ({@code minecraft:coal}) round-trips end to end through the menu in
 *       both directions, proving the conversion path (not just the plumbing) works.</li>
 * </ol>
 *
 * Furnace's GUI slots, then 20+ seconds with no progress, no output, no lit-variant switch, and no
 * error in hostagent.log. If {@code UmbLegacyMenu}'s machine slots were ever backed by a throwaway
 * {@code SimpleContainer} (filled once at menu-construction time, never written back), every
 * assertion below would fail: the handle would never see the player's placed items, and a change
 * to the handle would never appear back in the menu.
 */
class UmbLegacyMenuLiveWriteThroughTest {

    private static final Path SNAPSHOT = Paths.get("research/out/legacy/hbm-snapshot.json");

    @BeforeAll
    static void boot() throws Exception {
        TestSupport.ensureBootstrapped();
        Assumptions.assumeTrue(Files.isRegularFile(SNAPSHOT),
                "hbm-snapshot.json not present in this checkout: " + SNAPSHOT);
        // Force a REAL VanillaItemBridge build from the actual snapshot regardless of whatever
        // state an earlier test class in this same JVM (JUnit --scan-class-path runs every test
        // class in one JVM) already left it in -- see VanillaItemBridge.resetForTest()'s javadoc
        // (needed for the minecraft:coal round trip below to see a real mapping rather than a
        // permanently-latched empty one from an earlier class that ran before HostAgent.configure).
        HostAgent.configure(SNAPSHOT, null, "hbm");
        VanillaItemBridge.resetForTest();
        VanillaItemBridge.ensureBuilt();

        // 26.2's item default-component binding (Holder$Reference.bindComponents) is a separate
        // pass this headless suite's plain Bootstrap.bootStrap() call does not reach for vanilla
        // items outside gameplay -- same idiom UmbMenuContentParityTest/VanillaItemBridgeTest use,
        // required before constructing a real ItemStack from any of these items.
        Items.IRON_INGOT.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        Items.GOLD_INGOT.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        Items.COAL.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
    }

    @SuppressWarnings("unchecked")
    private static MenuType<UmbLegacyMenu> rawMenuType() {
        return TestSupport.allocate(MenuType.class);
    }

    /** For tests that never call {@code broadcastChanges()}: Slot's ctor only stores the
     * Container reference, so an uninitialised {@code Inventory} is fine as long as nothing ever
     * reads one of ITS slots. */
    private static Inventory rawInventory() {
        return TestSupport.allocate(Inventory.class);
    }

    /** For tests that DO call {@code broadcastChanges()}: vanilla's own loop (javap-verified)
     * calls {@code Slot.getItem()} on EVERY slot, including the 36 player-inventory ones added by
     * {@code addStandardInventorySlots}, which need a real, non-null backing item list -- same
     * idiom as {@code UmbMenuContentParityTest.realInventoryWith}. */
    private static Inventory realInventory() throws ReflectiveOperationException {
        Inventory inv = TestSupport.allocate(Inventory.class);
        Field itemsField = Inventory.class.getDeclaredField("items");
        itemsField.setAccessible(true);
        itemsField.set(inv, NonNullList.withSize(36, ItemStack.EMPTY));
        return inv;
    }

    /** Registers a legacy id/item mapping and resets Registrar's reverse-lookup cache -- an
     * earlier test class in this same JVM may already have built that cache without this
     * fixture's entry (see Registrar.resetLegacyVariantKeyCacheForTest()'s javadoc). */
    private static void registerLegacyItem(String legacyId, net.minecraft.world.item.Item item) {
        Registrar.LEGACY_ITEMS.put(legacyId, item);
        Registrar.LEGACY_VARIANT_ITEMS.put(legacyId + "@0", item);
        Registrar.resetLegacyVariantKeyCacheForTest();
    }

    @Test
    void writingThroughTheMenusSlotReachesTheContainerHandle() {
        FakeLegacyBridge.FakeContainerHandle handle = new FakeLegacyBridge.FakeContainerHandle(
                "Bricked Furnace", new int[]{62, 35, 116, 35}, new int[]{35, 17, 35, 53});
        UmbLegacyMenu menu = UmbLegacyMenu.forServer(rawMenuType(), 1, rawInventory(), handle,
                Component.literal("Bricked Furnace"));

        // Register a fake legacy item so LegacyStackConv can round-trip it (mirrors
        // UmbLegacyMenuTest's own idiom for exercising the conversion path headlessly).
        registerLegacyItem("hbm:test_ore", Items.IRON_INGOT);

        assertTrue(handle.slots()[0].stack.isEmpty(), "handle's slot 0 starts empty");

        // Container.setItem(int, ItemStack) -> Slot.setChanged() -> Container.setChanged()) -- NOT
        // handle.setSlot(...) directly.
        Slot slot0 = menu.slots.get(0);
        slot0.set(new ItemStack(Items.IRON_INGOT, 3));

        StackData afterWrite = handle.slots()[0].stack;
        assertFalse(afterWrite.isEmpty(), "the ContainerHandle must have received the write-through");
        assertEquals("hbm:test_ore", afterWrite.legacyId);
        assertEquals(3, afterWrite.count);
    }

    @Test
    void aChangeMadeDirectlyOnTheHandleAppearsInTheMenuAfterBroadcastChanges() throws Exception {
        FakeLegacyBridge.FakeContainerHandle handle = new FakeLegacyBridge.FakeContainerHandle(
                "Bricked Furnace", new int[]{62, 35, 116, 35}, new int[]{35, 17, 35, 53});
        UmbLegacyMenu menu = UmbLegacyMenu.forServer(rawMenuType(), 2, realInventory(), handle,
                Component.literal("Bricked Furnace"));

        registerLegacyItem("hbm:test_ingot", Items.GOLD_INGOT);

        Slot outputSlot = menu.slots.get(2);
        assertTrue(outputSlot.getItem().isEmpty(), "output slot starts empty, mirroring the handle");

        // Simulate the legacy TileEntity producing an output ingot on its own tick -- written
        // straight to the ContainerHandle, exactly like the real legacy machine code would (never
        // through the menu).
        handle.setSlot(2, new StackData("hbm:test_ingot", 1, 0, null));

        // Exactly what vanilla calls once per server tick per player with this menu open.
        menu.broadcastChanges();

        ItemStack seen = outputSlot.getItem();
        assertFalse(seen.isEmpty(), "the menu's slot must reflect the handle's new contents after broadcastChanges");
        assertEquals(Items.GOLD_INGOT, seen.getItem());
        assertEquals(1, seen.getCount());
    }

    @Test
    void vanillaCoalRoundTripsThroughTheMenuInBothDirections() throws Exception {
        FakeLegacyBridge.FakeContainerHandle handle = new FakeLegacyBridge.FakeContainerHandle(
                "Bricked Furnace", new int[]{62, 35, 116, 35}, new int[]{35, 17, 35, 53});
        UmbLegacyMenu menu = UmbLegacyMenu.forServer(rawMenuType(), 3, realInventory(), handle,
                Component.literal("Bricked Furnace"));

        // fuel slot, matching the real HBM Brick Furnace layout (index 1)
        Slot fuelSlot = menu.slots.get(1);

        // native -> legacy, through the menu's Slot (the "player puts coal in the fuel slot" step)
        fuelSlot.set(new ItemStack(Items.COAL, 8));
        StackData legacy = handle.slots()[1].stack;
        assertEquals("minecraft:coal", legacy.legacyId, "minecraft:coal is a straight identity match, no RENAME_TABLE row needed");
        assertEquals(8, legacy.count);
        assertEquals(0, legacy.damage);

        // legacy -> native, back through the menu's Slot after the handle changes again (the
        // "player takes the leftover coal back out" / "machine consumes some fuel" step)
        handle.setSlot(1, new StackData("minecraft:coal", 5, 0, null));
        menu.broadcastChanges();
        ItemStack seen = fuelSlot.getItem();
        assertEquals(Items.COAL, seen.getItem());
        assertEquals(5, seen.getCount());
    }
}
