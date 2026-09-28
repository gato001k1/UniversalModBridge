package dev.umb.hostagent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/**
 * Private-namespace routing for legacy GUI textures: mod ids pass through, vanilla GUI
 * sheets and stitched atlases map per era, everything else keeps the pack convention.
 */
class LegacyGuiTextureResolverTest {

    @Test
    void modTexturePassesThroughUnchanged() {
        assertEquals(Identifier.fromNamespaceAndPath("mcheli", "textures/gui/gui.png"),
                LegacyGuiTextureResolver.resolve("mcheli:textures/gui/gui.png"));
    }

    @Test
    void vanillaGuiSheetUsesPrivate1710NamespaceByDefault() {
        assertEquals(Identifier.fromNamespaceAndPath("umbvanilla1710", "textures/gui/widgets.png"),
                LegacyGuiTextureResolver.resolve("minecraft:textures/gui/widgets.png"));
    }

    @Test
    void vanillaGuiSheetUsesEraNamespace() {
        assertEquals(Identifier.fromNamespaceAndPath("umbvanilla1122", "textures/gui/widgets.png"),
                LegacyGuiTextureResolver.resolve("minecraft:textures/gui/widgets.png", "1.12.2"));
        assertEquals(Identifier.fromNamespaceAndPath("umbvanilla1165", "textures/gui/widgets.png"),
                LegacyGuiTextureResolver.resolve("minecraft:textures/gui/widgets.png", "1.16.5"));
    }

    @Test
    void vanillaFontStaysOnMinecraftNamespace() {
        assertEquals(Identifier.fromNamespaceAndPath("minecraft", "textures/font/ascii.png"),
                LegacyGuiTextureResolver.resolve("minecraft:textures/font/ascii.png"));
    }

    @Test
    void atlasesMapToDynamicIdsPerEra() {
        assertEquals(Identifier.fromNamespaceAndPath("umbatlas", "1710/items.png"),
                LegacyGuiTextureResolver.resolve("minecraft:textures/atlas/items.png"));
        assertEquals(Identifier.fromNamespaceAndPath("umbatlas", "1710/blocks.png"),
                LegacyGuiTextureResolver.resolve("minecraft:textures/atlas/blocks.png"));
        assertEquals(Identifier.fromNamespaceAndPath("umbatlas", "1122/items.png"),
                LegacyGuiTextureResolver.resolve("minecraft:textures/atlas/items.png", "1.12.2"));
    }

    @Test
    void unknownAtlasPathFallsBackToFill() {
        assertNull(LegacyGuiTextureResolver.resolve("minecraft:textures/atlas/unknown.png"));
    }

    @Test
    void blankInputsResolveNull() {
        assertNull(LegacyGuiTextureResolver.resolve(null));
        assertNull(LegacyGuiTextureResolver.resolve("   "));
    }
}
