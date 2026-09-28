package dev.umb.hostagent.content;

import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.SlotData;
import dev.umb.bridge.api.StackData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task B gate: {@link LegacyContainerClassResolver} recovers the real legacy Container class name
 * by reflecting into a private {@code container} field - the SAME shape
 * {@code dev.umb.legacy.legacyside.ContainerHandleImpl} (umb-legacy) actually has - without ever
 * widening {@code dev.umb.bridge.api}. This stands in for that real class with a minimal
 * {@link ContainerHandle} implementation carrying the same private field name, and separately
 * proves every EXISTING test fake (no such field) degrades to null rather than throwing.
 */
class LegacyContainerClassResolverTest {

    @Test
    void nullHandleResolvesToNull() {
        assertNull(LegacyContainerClassResolver.resolve(null));
    }

    @Test
    void aHandleWithNoContainerFieldResolvesToNull() {
        // FakeLegacyBridge.FakeContainerHandle is exactly what every OTHER test in this package
        // builds menus over - it must keep resolving to null (i.e. "no gui-profile.json pairing
        // available", the pre-Task-B fallback) forever, or every existing menu/screen test's
        // 176x166 assertions would silently start depending on this class's own implementation.
        ContainerHandle fake = new FakeLegacyBridge.FakeContainerHandle("hbm:tile.test");
        assertNull(LegacyContainerClassResolver.resolve(fake));
    }

    /** Same field name/shape as the real {@code ContainerHandleImpl}, without depending on umb-legacy. */
    private static final class FakeRealShapeHandle implements ContainerHandle {
        @SuppressWarnings("unused")
        private final Object container;

        FakeRealShapeHandle(Object container) {
            this.container = container;
        }

        @Override public String title() { return "t"; }
        @Override public int slotCount() { return 0; }
        @Override public SlotData[] slots() { return new SlotData[0]; }
        @Override public void setSlot(int index, StackData s) { }
        @Override public StackData takeSlot(int index, int amount) { return StackData.EMPTY; }
        @Override public int[] syncData() { return new int[0]; }
        @Override public void close() { }
    }

    private static final class SomeLegacyContainer {
    }

    @Test
    void resolvesTheRealClassNameOfThePrivateContainerField() {
        ContainerHandle handle = new FakeRealShapeHandle(new SomeLegacyContainer());
        assertEquals(SomeLegacyContainer.class.getName(), LegacyContainerClassResolver.resolve(handle));
    }

    @Test
    void aNullContainerFieldResolvesToNull() {
        ContainerHandle handle = new FakeRealShapeHandle(null);
        assertNull(LegacyContainerClassResolver.resolve(handle));
    }

    @Test
    void playerSlotLayoutReadsLegacySlotCoordinatesAndInventoryIndices() {
        FakeInventory inventory = new FakeInventory();
        FakePlayer player = new FakePlayer(inventory);
        FakeRawContainer container = new FakeRawContainer(inventory);
        for (int i = 0; i < 36; i++) {
            container.field_75151_b.add(new FakeSlot(inventory, i, 8 + (i % 9) * 18,
                    84 + (i / 9) * 18));
        }
        ContainerHandle handle = new FakeRealHandle(container, player);
        java.util.List<LegacyContainerClassResolver.PlayerSlot> slots =
                LegacyContainerClassResolver.resolvePlayerSlots(handle);
        assertEquals(36, slots.size());
        assertEquals(8, slots.get(0).x);
        assertEquals(84, slots.get(0).y);
        assertEquals(35, slots.get(35).inventoryIndex);
        org.junit.jupiter.api.Assertions.assertTrue(
                LegacyContainerClassResolver.slotsInsidePanel(slots, 256, 233));
        org.junit.jupiter.api.Assertions.assertFalse(
                LegacyContainerClassResolver.slotsInsidePanel(slots, 176, 150));
    }

    private static final class FakeInventory { }
    private static final class FakePlayer {
        private final FakeInventory field_71071_by;
        FakePlayer(FakeInventory inventory) { this.field_71071_by = inventory; }
    }
    private static final class FakeSlot {
        private final Object field_75224_c;
        private final int field_75225_a, field_75223_e, field_75221_f;
        FakeSlot(Object inv, int index, int x, int y) {
            field_75224_c = inv; field_75225_a = index; field_75223_e = x; field_75221_f = y;
        }
    }
    private static final class FakeRawContainer {
        private final java.util.List<FakeSlot> field_75151_b = new java.util.ArrayList<>();
        FakeRawContainer(FakeInventory ignored) { }
    }
    private static final class FakeRealHandle implements ContainerHandle {
        private final Object container;
        private final Object player;
        FakeRealHandle(Object container, Object player) { this.container = container; this.player = player; }
        @Override public String title() { return "t"; }
        @Override public int slotCount() { return 0; }
        @Override public SlotData[] slots() { return new SlotData[0]; }
        @Override public void setSlot(int index, StackData s) { }
        @Override public StackData takeSlot(int index, int amount) { return StackData.EMPTY; }
        @Override public int[] syncData() { return new int[0]; }
        @Override public void close() { }
    }
}
