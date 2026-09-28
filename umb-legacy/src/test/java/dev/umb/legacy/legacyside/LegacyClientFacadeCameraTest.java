package dev.umb.legacy.legacyside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Regression gate for copied-player facades versus genuine non-player camera dummies. */
public final class LegacyClientFacadeCameraTest {
    @Test
    public void copiedPlayerDoesNotOverrideCamera() {
        Object player = new Object();
        assertFalse(LegacyClientFacade.isOverriddenView(player, player));
        assertFalse(LegacyClientFacade.isOverriddenView(player, null));
    }

    @Test
    public void nonPlayerViewMayOverrideCamera() {
        assertTrue(LegacyClientFacade.isOverriddenView(new Object(), new Object()));
    }

    @Test
    public void thirdPersonDistanceReadsTheModWrittenRendererField() throws Exception {
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, null);
        writeThirdPersonDistance(binding, 16.0F);
        try {
            assertEquals(16.0F, LegacyClientFacade.thirdPersonDistance(binding.minecraft), 0.0001F);
        } finally {
            writeThirdPersonDistance(binding, 0.0F);
        }
    }

    @Test
    public void thirdPersonDistanceIsNaNWhenNothingWasWritten() throws Exception {
        LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, null);
        writeThirdPersonDistance(binding, 0.0F);
        assertTrue(Float.isNaN(LegacyClientFacade.thirdPersonDistance(binding.minecraft)));
    }

    @Test
    public void thirdPersonDistanceIsNaNForANullFacade() {
        assertTrue(Float.isNaN(LegacyClientFacade.thirdPersonDistance(null)));
    }

    /**
     * Bug #29: a render-thread singleton read landing between install()'s static
     * singleton publish and its service bind saw field_71446_o null and died in
     * vanilla RenderItem's missing-icon path (Minecraft.func_110434_K() null).
     * install() must seed the shared services before publishing, so the fresh
     * facade is drawable the moment it becomes observable.
     */
    @Test
    public void freshFacadeIsServiceBoundBeforeItIsPublished() throws Exception {
        java.lang.reflect.Field shared = LegacyClientFacade.class.getDeclaredField(
                "CLIENT_TEXTURE_MANAGER");
        shared.setAccessible(true);
        Object previous = shared.get(null);
        // Headless-safe: the vanilla 1.7.10 TextureManager constructor only allocates
        // its maps; the resource load happens in func_110549_a, which we never call.
        net.minecraft.client.renderer.texture.TextureManager probe =
                new net.minecraft.client.renderer.texture.TextureManager(null);
        shared.set(null, probe);
        try {
            LegacyClientFacade.Binding binding = LegacyClientFacade.install(null, null);
            assertTrue(binding.minecraft == net.minecraft.client.Minecraft.func_71410_x(),
                    "install() must publish the facade as the static singleton");
            assertTrue(binding.minecraft.func_110434_K() == probe,
                    "the published facade must already carry the shared TextureManager");
        } finally {
            shared.set(null, previous);
        }
    }

    private static void writeThirdPersonDistance(LegacyClientFacade.Binding binding, float value)
            throws Exception {
        java.lang.reflect.Field renderer = null;
        for (java.lang.reflect.Field field
                : net.minecraft.client.Minecraft.class.getDeclaredFields()) {
            if (field.getType().getName().equals("net.minecraft.client.renderer.EntityRenderer")) {
                field.setAccessible(true);
                renderer = field;
                break;
            }
        }
        assertNotNull(renderer, "Minecraft must expose the grounded EntityRenderer field");
        Object entityRenderer = renderer.get(binding.minecraft);
        assertNotNull(entityRenderer);
        // 1.7.10 SRG thirdPersonDistance (field_78490_B); the MCP alias is the lookup fallback.
        java.lang.reflect.Field distance =
                net.minecraft.client.renderer.EntityRenderer.class.getDeclaredField("field_78490_B");
        distance.setAccessible(true);
        distance.setFloat(entityRenderer, value);
    }
}
