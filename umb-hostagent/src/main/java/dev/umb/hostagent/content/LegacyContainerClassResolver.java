package dev.umb.hostagent.content;

import dev.umb.bridge.api.ContainerHandle;
import dev.umb.hostagent.AgentLog;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Recovers the REAL legacy {@code net.minecraft.inventory.Container} class name behind a
 * {@link ContainerHandle}, without widening {@code dev.umb.bridge.api} (gated byte-identical
 * against umb-legacy's canonical source - see {@code ContainerHandle}'s own mirror header, and the
 * lead's explicit "do NOT change dev.umb.bridge.api" instruction).
 *
 * {@code ContainerHandle} itself exposes only {@code title()}, and
 * {@code LegacyBridgeImpl.activate()} sets that to the raw legacyBlockId string, NOT the
 * container's class ({@code UmbLegacyBlock.useWithoutItem}'s own javadoc documents this exact
 * confusion for the menu TITLE). So this instead reflects into the real implementation's own
 * private {@code container} field ({@code dev.umb.legacy.legacyside.ContainerHandleImpl}, umb-legacy
 * - read-only research, never edited by this lane) - the SAME "reflect into a known private field
 * by name, with a comment explaining exactly why" idiom {@link UmbMenuRegistration#registerScreen}
 * already uses for {@code MenuScreens.SCREENS}.
 *
 * Deliberately tolerant: any {@link ContainerHandle} that is NOT a real
 * {@code ContainerHandleImpl} - every test fake in this package included (see
 * {@code FakeLegacyBridge.FakeContainerHandle}) - simply has no such field, and this returns null
 * rather than throwing. Callers (see {@link GuiProfile#lookup}) already treat null as "no
 * GUI-profile pairing available", so a fake/mocked handle degrades to the pre-existing 176x166
 * dispenser panel exactly like it did before Task B, with zero behaviour change for every existing
 * test that builds a menu over a fake handle.
 */
public final class LegacyContainerClassResolver {

    /** One legacy player-inventory slot, in the order used by the real Container. */
    public static final class PlayerSlot {
        public final int inventoryIndex;
        public final int x;
        public final int y;

        PlayerSlot(int inventoryIndex, int x, int y) {
            this.inventoryIndex = inventoryIndex;
            this.x = x;
            this.y = y;
        }
    }

    private LegacyContainerClassResolver() {
    }

    public static String resolve(ContainerHandle handle) {
        if (handle == null) return null;
        try {
            for (Class<?> c = handle.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                Field f;
                try {
                    f = c.getDeclaredField("container");
                } catch (NoSuchFieldException ignored) {
                    continue;
                }
                f.setAccessible(true);
                Object container = f.get(handle);
                return container == null ? null : container.getClass().getName();
            }
        } catch (Throwable t) {
            AgentLog.error("LegacyContainerClassResolver.resolve", t, 2);
        }
        return null;
    }

    /**
     * Reads the player-owned Slot positions from the same raw legacy Container used by the
     * ContainerHandle.  This is deliberately reflection-only and bounded to the container's
     * immediate slot list: the legacy source documents field_75151_b as the Container slot list,
     * Slot.field_75224_c as its backing inventory, Slot.field_75225_a as the inventory-local
     * index, and field_75223_e/field_75221_f as xDisplayPosition/yDisplayPosition.  Those are
     * the 1.7.10 SRG names cited in fml/conf/fields.csv by ContainerHandleImpl; no guessed host
     * coordinates are involved here.
     *
     * A non-real/test handle, a missing field, or a malformed entry returns null so callers can
     * retain the standard 36-slot fallback rather than inventing geometry.
     */
    public static List<PlayerSlot> resolvePlayerSlots(ContainerHandle handle) {
        if (handle == null) return null;
        try {
            Object rawContainer = privateField(handle, "container");
            Object rawPlayer = privateField(handle, "player");
            if (rawContainer == null || rawPlayer == null) return null;
            Object playerInventory = field(rawPlayer, "field_71071_by");
            Object allSlots = field(rawContainer, "field_75151_b");
            if (!(allSlots instanceof Iterable<?> iterable) || playerInventory == null) return null;
            List<PlayerSlot> result = new ArrayList<>();
            for (Object slot : iterable) {
                if (slot == null) continue;
                Object backing = field(slot, "field_75224_c");
                if (backing != playerInventory) continue;
                Integer index = integerField(slot, "field_75225_a");
                Integer x = integerField(slot, "field_75223_e");
                Integer y = integerField(slot, "field_75221_f");
                if (index == null || x == null || y == null || index < 0 || index >= 36) return null;
                result.add(new PlayerSlot(index, x, y));
            }
            return result.size() == 36 ? result : null;
        } catch (Throwable t) {
            AgentLog.error("LegacyContainerClassResolver.resolvePlayerSlots", t, 2);
            return null;
        }
    }

    /** True only when every recovered player slot is a panel-relative 16x16 slot. */
    public static boolean slotsInsidePanel(List<PlayerSlot> slots, int xSize, int ySize) {
        if (slots == null || slots.size() != 36 || xSize <= 0 || ySize <= 0) return false;
        boolean[] seen = new boolean[36];
        for (PlayerSlot s : slots) {
            if (s == null || s.inventoryIndex < 0 || s.inventoryIndex >= 36 || seen[s.inventoryIndex]
                    || s.x < 0 || s.y < 0 || s.x + 16 > xSize || s.y + 16 > ySize) return false;
            seen[s.inventoryIndex] = true;
        }
        for (boolean present : seen) if (!present) return false;
        return true;
    }

    private static Object privateField(Object receiver, String name) throws IllegalAccessException {
        for (Class<?> c = receiver.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(receiver);
            } catch (NoSuchFieldException ignored) {
                // Continue up the implementation hierarchy.
            }
        }
        return null;
    }

    /** Dispatches the vanilla 1.7.10 Container.enchantItem/func_75140_a button route. */
    public static boolean dispatchButton(ContainerHandle handle, int buttonId) {
        if (handle == null) return false;
        try {
            Object rawContainer = privateField(handle, "container");
            Object rawPlayer = privateField(handle, "player");
            if (rawContainer == null || rawPlayer == null) return false;
            for (Class<?> c = rawContainer.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!("func_75140_a".equals(m.getName()) || "enchantItem".equals(m.getName()))) continue;
                    if (m.getParameterCount() != 2) continue;
                    m.setAccessible(true);
                    Object result = m.invoke(rawContainer, rawPlayer, buttonId);
                    return !(result instanceof Boolean) || (Boolean) result;
                }
            }
        } catch (Throwable t) {
            AgentLog.error("LegacyContainerClassResolver.dispatchButton", t, 2);
        }
        return false;
    }

    private static Object field(Object receiver, String name) throws IllegalAccessException {
        if (receiver == null) return null;
        for (Class<?> c = receiver.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(receiver);
            } catch (NoSuchFieldException ignored) {
                // Continue up the legacy class hierarchy.
            }
        }
        return null;
    }

    private static Integer integerField(Object receiver, String name) throws IllegalAccessException {
        Object value = field(receiver, name);
        return value instanceof Number n ? n.intValue() : null;
    }
}
