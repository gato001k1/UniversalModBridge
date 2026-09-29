package dev.umb.objbridge.entity;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/** Captured era textures resolve from published mod jars (not in the ObjBridge manifest). */
class LegacyCaptureTextureJarTest {

    @Test
    void entryNameAppendsPngOnlyWhenMissing() {
        assertEquals("assets/alexsmobs/textures/entity/grizzly_bear.png",
                LegacyCaptureTextureResolver.jarEntry(
                        Identifier.fromNamespaceAndPath("alexsmobs", "textures/entity/grizzly_bear")));
        assertEquals("assets/ironchest/textures/model/iron_chest.png",
                LegacyCaptureTextureResolver.jarEntry(
                        Identifier.fromNamespaceAndPath("ironchest", "textures/model/iron_chest.png")));
    }

    @Test
    void imageBytesComeFromThePublishedJarAndMissesAreCached() throws Exception {
        File jar = Files.createTempFile("umb-capture-assets", ".jar").toFile();
        jar.deleteOnExit();
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar.toPath()))) {
            out.putNextEntry(new ZipEntry("assets/testmod/textures/entity/thing.png"));
            out.write(png);
            out.closeEntry();
        }
        String key = LegacyCaptureTextureResolver.ASSET_JARS_PROPERTY;
        String previous = System.getProperty(key);
        try {
            System.setProperty(key, new File("does-not-exist.jar").getAbsolutePath()
                    + File.pathSeparator + jar.getAbsolutePath());
            assertArrayEquals(png, LegacyCaptureTextureResolver.readFromAssetJars(
                    Identifier.fromNamespaceAndPath("testmod", "textures/entity/thing")));
            assertNull(LegacyCaptureTextureResolver.readFromAssetJars(
                    Identifier.fromNamespaceAndPath("testmod", "textures/entity/absent")));
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }
}
