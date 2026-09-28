package dev.umb.bridge.api;

/** One slot's position and contents, for building the 26.2 menu. */
public final class SlotData {
    public final int index, x, y; public final StackData stack;
    public SlotData(int index, int x, int y, StackData stack) {
        this.index = index; this.x = x; this.y = y; this.stack = stack; }
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
