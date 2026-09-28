package dev.umb.objbridge.entity;

import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import java.lang.reflect.Field;
import java.util.Map;

/** Patches the 26.2 provider table before the dispatcher materializes its renderer map. */
public final class LegacyBlockEntityRendererRegistration {
    private LegacyBlockEntityRendererRegistration(){}
    public static void install(){try{Field f=BlockEntityRenderers.class.getDeclaredField("PROVIDERS");f.setAccessible(true);@SuppressWarnings("unchecked") Map<Object,Object> m=(Map<Object,Object>)f.get(null);int n=0;for(Identifier id:BuiltInRegistries.BLOCK_ENTITY_TYPE.keySet())if("legacy_tile".equals(id.getPath())){Object t=BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(id);m.put(t,(BlockEntityRendererProvider)LegacyBlockEntityRenderer::new);n++;}System.out.println("[UMB-OBJBRIDGE] BLOCK-ENTITY-RENDERER providers="+n);}catch(Throwable t){System.out.println("[UMB-OBJBRIDGE] BLOCK-ENTITY-RENDERER install failed: "+t);}}
}
