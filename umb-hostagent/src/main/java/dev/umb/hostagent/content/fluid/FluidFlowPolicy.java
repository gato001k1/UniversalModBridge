package dev.umb.hostagent.content.fluid;

import net.minecraft.core.Direction;

/** Small, testable policy seam for the only non-standard fluid behavior. */
final class FluidFlowPolicy {
    private FluidFlowPolicy() {}

    static boolean isGaseous(FluidEntry entry) {
        return entry.gaseous || entry.density < 0;
    }

    static Direction verticalSpreadDirection(FluidEntry entry) {
        return isGaseous(entry) ? Direction.UP : Direction.DOWN;
    }
}
