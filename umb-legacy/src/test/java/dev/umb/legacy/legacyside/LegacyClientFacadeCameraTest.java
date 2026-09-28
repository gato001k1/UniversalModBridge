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
