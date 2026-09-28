package dev.umb.objbridge.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import net.minecraft.resources.Identifier;

/** Regression test for legacy ResourceLocation paths that already contain .png. */
class LegacyCaptureTextureResolverTest {
    @Test
    void mcheliTextureBindingResolvesExistingAssetWithoutDoubleSuffix() {
        Identifier bound = Identifier.parse("mcheli:textures/helicopters/ah-1z.png");
        assertEquals("mcheli:textures/helicopters/ah-1z.png",
                LegacyCaptureTextureResolver.assetPath(bound));
        Identifier normalized = Identifier.parse("mcheli:textures/helicopters/ah-1z");
        assertEquals("mcheli:textures/helicopters/ah-1z.png",
                LegacyCaptureTextureResolver.assetPath(normalized));
        Path root = Path.of("research/out/legacy/mcheli-assets");
        Assumptions.assumeTrue(Files.isDirectory(root), "extracted MCHeli assets are unavailable");
        Path resolved = dev.umb.objbridge.obj.ObjAssets.resolve(root,
                LegacyCaptureTextureResolver.assetPath(bound));
        assertTrue(Files.isRegularFile(resolved), "MCHeli AH-1Z texture was not found: " + resolved);
    }
}
