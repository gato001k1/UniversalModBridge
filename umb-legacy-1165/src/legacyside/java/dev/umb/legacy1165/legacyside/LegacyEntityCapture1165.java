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
            String reason = describe(failure);
            if (!reason.equals(lastFailure)) {
                lastFailure = reason;
                // Once per distinct cause, with the frames that locate it: a bare class name
                // ("NoSuchMethodError") does not say which member is missing or who called it.
                System.err.println("[UMB-ENTITY-1165] capture failed class=" + className
                        + " reason=" + reason + " at " + frames(failure, 8));
            }
            return EntityRenderCapture.empty(className,
                    "1165-capture-provider-threw:" + reason);
        }
    }

    /** Deepest cause as {@code Class: message} (the message names e.g. the missing method). */
    static String describe(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getName() + (message == null || message.isEmpty() ? "" : ": " + message);
    }

    static String frames(Throwable failure, int max) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        StackTraceElement[] stack = root.getStackTrace();
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < stack.length && i < max; i++) {
            if (i > 0) out.append(" <- ");
            out.append(stack[i]);
        }
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
