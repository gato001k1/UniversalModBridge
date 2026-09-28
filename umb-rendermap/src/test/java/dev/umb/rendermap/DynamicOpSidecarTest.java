package dev.umb.rendermap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DynamicOpSidecarTest {
    @Test
    void blockTeLinkagePrefersExactMapFactoryClassOverGenericRendererParameter() {
        // Regression shape from HBM machine_press: the renderer accepts TileEntity, but the
        // block row resolves the concrete TileEntityMachinePress factory class.
        String rendererParameter = "net.minecraft.tileentity.TileEntity";
        String exactMapTe = "com.hbm.tileentity.machine.TileEntityMachinePress";
        assertEquals(exactMapTe, DynamicOpSidecar.effectiveTeClass(rendererParameter, exactMapTe));
        assertEquals(rendererParameter, DynamicOpSidecar.effectiveTeClass(rendererParameter, null));
    }
}
