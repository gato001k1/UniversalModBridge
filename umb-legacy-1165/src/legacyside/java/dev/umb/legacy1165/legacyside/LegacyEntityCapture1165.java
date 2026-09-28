package dev.umb.legacy1165.legacyside;

import dev.umb.bridge.api.EntityRenderCapture;
import net.minecraft.entity.Entity;

/**
 * Render-thread hand-off for the 1.16.5 entity capture layer.
 *
 * <p>The legacy entity and its renderer must stay in the isolated 1.16.5
 * classloader.  The provider is therefore installed by that universe's client
 * facade; this class deliberately contains no host renderer or mod references.
 * Until the client facade installs it, capture returns a diagnostic empty
 * result instead of silently taking the bridge API default.</p>
 */
public final class LegacyEntityCapture1165 {
    public interface Provider {
        EntityRenderCapture capture(Entity entity, float partialTick) throws Throwable;
    }

    private static volatile Provider provider;
    private static volatile String lastFailure;

    private LegacyEntityCapture1165() {
    }

    /** Installs the client-universe provider; replacement is intentional for world rebinds. */
    public static void install(Provider next) {
        provider = next;
        lastFailure = null;
    }

    public static void clear() {
        provider = null;
    }

    public static EntityRenderCapture capture(Entity entity, String entityId,
            float partialTick) {
        String className = entity == null ? "" : entity.getClass().getName();
        Provider current = provider;
        if (current == null) {
            // Client entities can reach the bridge before the facade's explicit binding hook
            // runs.  Discovering the real 1.16.5 Minecraft dispatcher here is render-thread
            // safe and keeps the server/headless universe honest: Minecraft.func_71410_x()
            // simply fails with a precise unavailable reason when no client exists.
            if (LegacyEntityRenderCapture1165Client.installFromMinecraft()) {
                current = provider;
            }
            if (current == null) {
                String detail = LegacyEntityRenderCapture1165Client.unavailableReason();
                if (detail == null || detail.isEmpty()) detail = "client-provider-not-installed";
                return EntityRenderCapture.empty(className,
                        "1165-capture-provider-unavailable:" + detail + ":" + safe(entityId));
            }
        }
        try {
            EntityRenderCapture result = current.capture(entity, partialTick);
            if (result != null) return result;
            return EntityRenderCapture.empty(className,
                    "1165-capture-provider-returned-null:" + safe(entityId));
        } catch (Throwable failure) {
            String reason = failure.getClass().getName();
            if (!reason.equals(lastFailure)) {
                lastFailure = reason;
                System.err.println("[UMB-ENTITY-1165] capture failed class=" + className
                        + " reason=" + reason);
            }
            return EntityRenderCapture.empty(className,
                    "1165-capture-provider-threw:" + reason);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
