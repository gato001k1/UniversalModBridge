package dev.umb.legacy.legacyside;

import dev.umb.bridge.api.HostWorld;
import net.minecraft.client.particle.EntityFX;
import net.minecraft.client.particle.EffectRenderer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generic fallback for legacy custom EntityFX. A mod may construct an EntityFX and submit it to
 * EffectRenderer instead of calling World.spawnParticle; that path has no native 26.2 identity.
 * Retain its position, motion, RGB parameters, and a bounded native scale, then ask the host for
 * a dust particle encoded as data. No mod class or namespace is inspected and no GL/Tessellator
 * renderer is entered.
 */
public final class UmbEffectRenderer extends EffectRenderer {
    private HostWorld host;
    private static final AtomicLong ENTITY_FX_SUBMITTED = new AtomicLong();
    private static final AtomicLong ENTITY_FX_FORWARDED = new AtomicLong();
    private static final AtomicLong ENTITY_FX_DROPPED = new AtomicLong();
    private static final AtomicLong LAST_REPORT_NANOS = new AtomicLong();

    private UmbEffectRenderer() {
        super(null, null);
    }

    void bindHost(HostWorld value) {
        host = value;
    }

    @Override
    public void func_78873_a(EntityFX effect) {
        ENTITY_FX_SUBMITTED.incrementAndGet();
        if (host == null || effect == null) {
            ENTITY_FX_DROPPED.incrementAndGet();
            report("missing-host-or-effect");
            return;
        }
        float red = safeColor(effect.func_70534_d());
        float green = safeColor(effect.func_70542_f());
        float blue = safeColor(effect.func_70535_g());
        int rgb = ((int) (red * 255.0F) << 16) | ((int) (green * 255.0F) << 8)
                | (int) (blue * 255.0F);
        // EntityFX exposes colour but not one universal scale getter; use the native dust default
        // as the honest bounded fallback for renderer-specific sizing.
        String name = "umb:custom_dust:" + Integer.toHexString(rgb) + ":0.75";
        host.spawnParticle(name, effect.field_70165_t, effect.field_70163_u, effect.field_70161_v,
                effect.field_70159_w, effect.field_70181_x, effect.field_70179_y);
        ENTITY_FX_FORWARDED.incrementAndGet();
        report("forwarded-custom-dust");
    }

    private void report(String reason) {
        if (host == null) return;
        long now = System.nanoTime();
        long last = LAST_REPORT_NANOS.get();
        if (last != 0L && now - last < 1_000_000_000L && !reason.startsWith("missing")) return;
        if (!LAST_REPORT_NANOS.compareAndSet(last, now)) return;
        host.log("UMB-FX EntityFX submitted=" + ENTITY_FX_SUBMITTED.get() + " forwarded="
                + ENTITY_FX_FORWARDED.get() + " dropped=" + ENTITY_FX_DROPPED.get()
                + " reason=" + reason);
    }

    private static float safeColor(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 1.0F;
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
