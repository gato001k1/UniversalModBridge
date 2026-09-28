package dev.umb.bridge.api;

/** One slot's position and contents, for building the 26.2 menu. */
public final class SlotData {
    public final int index, x, y; public final StackData stack;
    public SlotData(int index, int x, int y, StackData stack) {
        this.index = index; this.x = x; this.y = y; this.stack = stack; }
}
