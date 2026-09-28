package dev.umb.bridge.api;

/** A live legacy container the host menu mirrors. Host calls these on the SERVER thread only. */
public interface ContainerHandle {
    String     title();
    int        slotCount();      // machine slots only; player slots are host-side
    SlotData[] slots();          // current contents and layout
    void       setSlot(int index, StackData s);
    StackData  takeSlot(int index, int amount);
    int[]      syncData();       // progress ints (ContainerData), stable length
    void       close();

    /**
     * Runs the legacy container's own {@code transferStackInSlot}/func_82846_b route for a
     * 26.2 menu slot index.  Returns {@code null} when this handle predates the route, an empty
     * stack when the legacy container rejected the transfer, or the legacy method's returned
     * copy when it moved a stack.  The menu index is machine slots first, followed by the
     * player slots in the same order as {@link #slots()} and the host player inventory.
     */
    default StackData quickMove(int menuIndex, int machineSlotCount) { return null; }

    /** May {@code s} go into machine slot {@code index}? The legacy {@code Slot.isItemValid}
     *  (func_75214_a) policy - e.g. a turret's ammo slot rejects a random block. Default true so
     *  any handle predating this method keeps the old vanilla-permissive behaviour. */
    default boolean canPlace(int index, StackData s) { return true; }
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
