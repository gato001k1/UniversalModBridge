package dev.umb.objbridge.entity;

import dev.umb.objbridge.ObjLog;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import java.lang.reflect.Field;
import java.util.Map;

/** Installs one provider for every generic legacy entity/part type before the dispatcher is built. */
public final class LegacyEntityRendererRegistration {
    private static volatile boolean installed;
    private LegacyEntityRendererRegistration() { }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void install() {
        try {
            Field f = EntityRenderers.class.getDeclaredField("PROVIDERS");
            f.setAccessible(true);
            Map providers = (Map) f.get(null);
            int n = 0;
            for (Identifier id : BuiltInRegistries.ENTITY_TYPE.keySet()) {
                // legacy_part twins carry no legacy class identity, so the shared renderer
                // resolves them to an empty capture and draws nothing - but the dispatcher
                // still needs a provider bound, otherwise the type is unrenderable.
                if (!"legacy_entity".equals(id.getPath()) && !"legacy_part".equals(id.getPath())) {
                    continue;
                }
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(id);
                providers.put(type, (EntityRendererProvider<Entity>) LegacyEntityRenderer::new);
                n++;
            }
            boolean first = !installed;
            installed = true;
            ObjLog.loud("ENTITY-RENDERER registered generic provider(s)=" + n
                    + " visualRows=" + LegacyEntityVisual.loadedCount()
                    + " drawable=" + LegacyEntityVisual.drawableCount()
                    + " skipped=" + LegacyEntityVisual.skippedCount()
                    + (first ? "" : " timing=dispatcher-entry"));
        } catch (Throwable t) {
            ObjLog.loud("ENTITY-RENDERER registration failed: " + t);
            ObjLog.error("LegacyEntityRendererRegistration.install", t, 8);
        }
    }
}
