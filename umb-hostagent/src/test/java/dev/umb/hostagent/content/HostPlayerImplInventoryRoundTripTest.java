package dev.umb.hostagent.content;

import dev.umb.bridge.api.StackData;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the REAL M1 bug (lead-verified live, see win-m3/shots/11-crop.png ->
 * 12-crop.png, and the exact conversion-failure lines this test pins down at
 * research/out/legacy/win-m3/logs/hostagent.log:42-44): right-clicking any legacy container
 * ERASED the player's real 26.2 inventory items that had no legacy-side representation.
 *
 * Root cause: {@code LegacyBridgeImpl.activate()} (umb-legacy, not touched by this fix)
 * unconditionally round-trips ALL 36 player-inventory slots through
 * {@code UmbPlayer.pullInventory()}/{@code pushInventory()} on EVERY legacy-container open, even
 * though HBM's furnace (like most legacy containers) never reads or writes the player's own
 * inventory. {@link HostPlayerImpl#getInventorySlot} already correctly reports
 * {@code StackData.EMPTY} for a native item with no legacy id at all ({@link LegacyStackConv#toLegacy}
 * has no way to name e.g. {@code minecraft:netherite_ingot} in 1.7.10 terms -- it did not exist
 * yet -- this is expected and unchanged); the bug was that the pull/push round trip then fed that
 * same EMPTY back into {@link HostPlayerImpl#setInventorySlot}, which blindly wrote it into the
 * REAL inventory, erasing an item legacy code never even touched.
 *
 * Fix: {@code setInventorySlot} now only writes an incoming EMPTY over a slot whose CURRENT
 * native item also has no legacy representation when that item is untouched by anything other
 * than this round trip -- i.e. it treats "came back empty AND the real slot holds an
 * unmappable item" as a round-trip artifact, not a genuine legacy-side clear, and leaves it
 * alone. A slot holding a legacy-KNOWN item is still written through normally in both
 * directions, so real legacy-driven changes (including genuine clears) are unaffected.
 *
 * NOTE (G2-vanilla-bridge): this test used to use {@code minecraft:coal} as its "unmappable"
 * example. {@link VanillaItemBridge} now gives {@code minecraft:coal} a real legacy id (it is a
 * straight identity match against the 1.7.10 snapshot's own {@code minecraft:coal} entry -- see
 * {@code VanillaItemBridgeTest}), so it would no longer exercise this guard at all. Swapped to
 * {@code minecraft:netherite_ingot}, an item that plainly cannot appear in a 1.7.10 snapshot
 * (netherite postdates 1.7.10 by years) and so is guaranteed to stay unmapped by both
 * {@link Registrar} and {@link VanillaItemBridge} forever, regardless of snapshot content.
 */
class HostPlayerImplInventoryRoundTripTest {

    private static final String LEGACY_ID = "hbm:test_round_trip_marker";

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
        Items.NETHERITE_INGOT.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        Items.DIAMOND.builtInRegistryHolder().bindComponents(DataComponentMap.EMPTY);
        // A legacy-KNOWN item, exactly like Registrar.run() would populate from a real snapshot --
        // Items.DIAMOND stands in for a legacy-registered item so the "still writes through
        // normally when the item IS legacy-known" half of this test is exercised for real.
        Registrar.LEGACY_ITEMS.put(LEGACY_ID, Items.DIAMOND);
        Registrar.LEGACY_VARIANT_ITEMS.put(LEGACY_ID + "@0", Items.DIAMOND);
    }

    private static HostPlayerImpl newHostPlayerWithInventory(int index, ItemStack stack) throws ReflectiveOperationException {
        ServerPlayer player = TestSupport.allocate(ServerPlayer.class);
        Inventory inv = TestSupport.allocate(Inventory.class);
        Field itemsField = Inventory.class.getDeclaredField("items");
        itemsField.setAccessible(true);
        NonNullList<ItemStack> items = NonNullList.withSize(36, ItemStack.EMPTY);
        itemsField.set(inv, items);
        inv.setItem(index, stack);

        Field invField = Player.class.getDeclaredField("inventory");
        invField.setAccessible(true);
        invField.set(player, inv);

        return new HostPlayerImpl(player);
    }

    @Test
    void pushingBackAnUnconvertibleEmptyDoesNotEraseARealUnmappableItem() throws Exception {
        // minecraft:netherite_ingot has no legacy id (Registrar.LEGACY_ITEMS/LEGACY_VARIANT_ITEMS
        // never map it, and VanillaItemBridge cannot either -- netherite did not exist in 1.7.10,
        // so it can never appear in the snapshot), so getInventorySlot must (correctly, unchanged)
        // report EMPTY for it.
        HostPlayerImpl hp = newHostPlayerWithInventory(5, new ItemStack(Items.NETHERITE_INGOT, 7));

        StackData pulled = hp.getInventorySlot(5);
        assertTrue(pulled.isEmpty(), "an unmappable native item round-trips to StackData.EMPTY on pull -- unchanged");

        // Simulate LegacyBridgeImpl.activate()'s pushInventory(): the legacy side hands back
        // exactly what pull() gave it (nothing touched the slot), i.e. StackData.EMPTY.
        hp.setInventorySlot(5, StackData.EMPTY);

        Item afterPush = readBackItem(hp, 5);
        assertSame(Items.NETHERITE_INGOT, afterPush, "the real netherite ingot must survive a round trip the legacy side never actually modified");
    }

    @Test
    void aGenuineLegacySideClearOfAKnownItemStillWritesThrough() throws Exception {
        // Items.DIAMOND IS legacy-registered (see boot()), so this slot's content is something
        // legacy code could genuinely see and clear -- an incoming EMPTY here must still apply.
        HostPlayerImpl hp = newHostPlayerWithInventory(6, new ItemStack(Items.DIAMOND, 1));

        StackData pulled = hp.getInventorySlot(6);
        assertFalse(pulled.isEmpty(), "a legacy-registered item must round-trip to a real StackData, not EMPTY");

        hp.setInventorySlot(6, StackData.EMPTY);

        Item afterPush = readBackItem(hp, 6);
        assertSame(Items.AIR, afterPush, "a real legacy-side clear of a legacy-known item must still propagate");
    }

    @Test
    void aGenuineLegacySideChangeOfAKnownItemStillWritesThrough() throws Exception {
        HostPlayerImpl hp = newHostPlayerWithInventory(7, new ItemStack(Items.DIAMOND, 1));

        // Legacy code changed the count (e.g. the container consumed part of the stack).
        hp.setInventorySlot(7, new StackData(LEGACY_ID, 3, 0, null));

        Item afterPush = readBackItem(hp, 7);
        assertSame(Items.DIAMOND, afterPush, "a real legacy-side change to a legacy-known item must still propagate");
    }

    private static Item readBackItem(HostPlayerImpl hp, int index) throws ReflectiveOperationException {
        Field playerField = HostPlayerImpl.class.getDeclaredField("player");
        playerField.setAccessible(true);
        ServerPlayer player = (ServerPlayer) playerField.get(hp);
        return player.getInventory().getItem(index).getItem();
    }

    /**
     * javap-verified: {@code Player.getMainHandItem()}/{@code setItemInHand} do NOT read/write
     * {@code Inventory.items} at all - they go through {@code LivingEntity.getItemBySlot}/
     * {@code setItemSlot}, which delegate to a separate {@code LivingEntity.equipment} field
     * ({@link EntityEquipment}, an {@code EnumMap<EquipmentSlot, ItemStack>}). {@link HostPlayerImpl}
     * itself never sets this field up (a real, constructed {@code ServerPlayer} always has one); an
     * {@code Unsafe}-allocated test double needs it seeded directly, unlike {@link #newHostPlayerWithInventory}.
     */
    private static HostPlayerImpl newHostPlayerWithMainHandItem(ItemStack stack) throws ReflectiveOperationException {
        ServerPlayer player = TestSupport.allocate(ServerPlayer.class);
        net.minecraft.world.entity.EntityEquipment equipment = new net.minecraft.world.entity.EntityEquipment();
        equipment.set(net.minecraft.world.entity.EquipmentSlot.MAINHAND, stack);

        Field equipmentField = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("equipment");
        equipmentField.setAccessible(true);
        equipmentField.set(player, equipment);

        return new HostPlayerImpl(player);
    }

    private static Item readBackMainHandItem(HostPlayerImpl hp) throws ReflectiveOperationException {
        Field playerField = HostPlayerImpl.class.getDeclaredField("player");
        playerField.setAccessible(true);
        ServerPlayer player = (ServerPlayer) playerField.get(hp);
        return player.getMainHandItem().getItem();
    }

    /**
     * FM-6 (INTERACTION-BRIDGE.md): the same erasure class fixed above for
     * {@link HostPlayerImpl#setInventorySlot} was still live, unguarded, on
     * {@link HostPlayerImpl#setHeldItem} - a native 26.2 item held in the main hand has no legacy
     * twin, so {@code getHeldItem()} correctly reports it as absent (StackData.EMPTY), but nothing
     * stopped a caller from writing that EMPTY straight back over the real item. This is the exact
     * "held item disappears" shape of the fix already required for E3 (bridging useItemOn: "do NOT
     * convert to EMPTY and write back").
     */
    @Test
    void settingTheHeldItemToEmptyDoesNotEraseARealUnmappableNativeItem() throws Exception {
        HostPlayerImpl hp = newHostPlayerWithMainHandItem(new ItemStack(Items.NETHERITE_INGOT, 1));

        StackData pulled = hp.getHeldItem();
        assertTrue(pulled.isEmpty(), "an unmappable native item round-trips to StackData.EMPTY via getHeldItem -- unchanged");

        hp.setHeldItem(StackData.EMPTY);

        Item afterPush = readBackMainHandItem(hp);
        assertSame(Items.NETHERITE_INGOT, afterPush, "a real, unmappable held item must survive setHeldItem(EMPTY)");
    }

    @Test
    void aGenuineLegacySideClearOfTheHeldItemStillWritesThroughWhenItWasLegacyKnown() throws Exception {
        HostPlayerImpl hp = newHostPlayerWithMainHandItem(new ItemStack(Items.DIAMOND, 1));

        StackData pulled = hp.getHeldItem();
        assertFalse(pulled.isEmpty(), "a legacy-registered held item must round-trip to a real StackData");

        hp.setHeldItem(StackData.EMPTY);

        Item afterPush = readBackMainHandItem(hp);
        assertSame(Items.AIR, afterPush, "a real legacy-side clear of a legacy-known held item must still propagate");
    }

    /**
     * Automation lane (input-lane round 13 follow-up): the selected hotbar slot reads
     * through, so legacy code can stop matching it by fallback.
     */
    @Test
    void selectedHotbarSlotReadsThrough() throws Exception {
        HostPlayerImpl hp = newHostPlayerWithInventory(0, new ItemStack(Items.DIAMOND, 1));
        Field playerField = HostPlayerImpl.class.getDeclaredField("player");
        playerField.setAccessible(true);
        ServerPlayer player = (ServerPlayer) playerField.get(hp);
        player.getInventory().setSelectedSlot(4);
        assertEquals(4, hp.getSelectedSlot(), "the selected slot must read through, not match by fallback");
        player.getInventory().setSelectedSlot(0);
        assertEquals(0, hp.getSelectedSlot(), "slot 0 reads as 0 (not confused with the -1 unknown sentinel)");
    }
}
