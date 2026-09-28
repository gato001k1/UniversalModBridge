package dev.umb.legacy.legacyside;

import dev.umb.legacy.legacyside.input.LegacyLwjglState;
import net.minecraft.client.Minecraft;
import net.minecraft.util.MouseHelper;

/**
 * Legacy {@code MouseHelper} for the native-free client facade. Its deltas (SRG field_74377_a
 * deltaX, field_74375_b deltaY) are derived from the host player's real view rotation since the
 * last poll, inverted through the vanilla 1.7.10 turn formula (EntityRenderer: k = (sens*0.6+0.2)^3*8,
 * Entity.setAngles applies *0.15, pitch is subtracted) so code that reads raw mouse motion
 * (vehicle sticks, turrets, camera tools) sees the same motion the player made. Cursor grab calls
 * are no-ops: the host owns the window.
 */
public final class UmbMouseHelper extends MouseHelper {

    @Override
    public void func_74374_c() {
        float[] look = LegacyLwjglState.consumeLookDelta();
        if (dev.umb.legacy.legacyside.input.LegacyInputDiag.oncePer("umb-mouse-poll", 3_000_000_000L)) {
            dev.umb.legacy.legacyside.input.LegacyInputDiag.log("legacy MouseHelper polled yaw=" + look[0]);
        }
        float sens = 0.5f;
        try {
            Minecraft mc = Minecraft.func_71410_x();
            if (mc != null && mc.field_71474_y != null) sens = mc.field_71474_y.field_74341_c;
        } catch (Throwable ignored) {
            // Default sensitivity when the facade has no GameSettings yet.
        }
        float f = sens * 0.6f + 0.2f;
        float k = f * f * f * 8.0f * 0.15f;
        this.field_74377_a = Math.round(look[0] / k);
        this.field_74375_b = Math.round(-look[1] / k);
        if ((field_74377_a != 0 || field_74375_b != 0)
                && dev.umb.legacy.legacyside.input.LegacyInputDiag.oncePer("umb-mouse-delta", 2_000_000_000L)) {
            dev.umb.legacy.legacyside.input.LegacyInputDiag.log("legacy mouse delta dx=" + field_74377_a
                    + " dy=" + field_74375_b + " fromYaw=" + look[0] + " fromPitch=" + look[1]);
        }
    }

    @Override
    public void func_74372_a() {
    }

    @Override
    public void func_74373_b() {
    }
}
