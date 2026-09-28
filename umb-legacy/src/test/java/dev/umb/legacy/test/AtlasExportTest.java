package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;

import dev.umb.legacy.legacyside.LegacyClientFacade;

/**
 * Headless verification of the GUI atlas export: hand-built sprites with known pixels go
 * through the real {@link TextureMap}/{@link TextureAtlasSprite} classes and the production
 * composite, and the exported PNG must contain each sprite's frame at its stitched origin
 * inside a power-of-two-padded image (the stitcher's exact contract). No FML boot needed.
 */
class AtlasExportTest {

    /** Test sprite with fully specified stitch geometry and level-0 pixels. */
    static final class TestSprite extends TextureAtlasSprite {
        TestSprite(String name, int ox, int oy, int w, int h, int[] pixels) {
            super(name);
            field_110975_c = ox;
            field_110974_d = oy;
            field_130223_c = w;
            field_130224_d = h;
            field_110976_a = new ArrayList<int[]>();
            ((List<int[]>) field_110976_a).add(pixels);
        }
    }

    @Test
    void atlasExportCompositesSpriteFramesAtStitchedOrigins(@TempDir Path tmp) throws Exception {
        String previous = System.getProperty("umb.legacy.gameDir");
        System.setProperty("umb.legacy.gameDir", tmp.toString());
        try {
            TextureMap map = new TextureMap(1, "textures/items", true);
            int[] red = {0xFFFF0000, 0xFFFF0000, 0xFFFF0000, 0xFFFF0000};
            int[] green = {0xFF00FF00, 0xFF00FF00};
            putSprite(map, new TestSprite("test:red", 0, 0, 2, 2, red));
            putSprite(map, new TestSprite("test:green", 2, 0, 1, 2, green));

            LegacyClientFacade.exportAtlasPixels(map, "items");

            File png = tmp.resolve("umbatlas/1710/items.png").toFile();
            assertTrue(png.isFile(), "atlas PNG exported");
            BufferedImage img = ImageIO.read(png);
            // Extents are 3x2; the stitcher power-of-two pads, so 4x2.
            assertEquals(4, img.getWidth());
            assertEquals(2, img.getHeight());
            assertEquals(0xFFFF0000, img.getRGB(0, 0));
            assertEquals(0xFFFF0000, img.getRGB(1, 1));
            assertEquals(0xFF00FF00, img.getRGB(2, 0));
            assertEquals(0xFF00FF00, img.getRGB(2, 1));
            assertEquals(0x00000000, img.getRGB(3, 1), "untouched padding stays transparent");
        } finally {
            if (previous == null) System.clearProperty("umb.legacy.gameDir");
            else System.setProperty("umb.legacy.gameDir", previous);
        }
    }

    private static void putSprite(TextureMap map, TextureAtlasSprite sprite) throws Exception {
        Field f = TextureMap.class.getDeclaredField("field_94252_e");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, TextureAtlasSprite> uploaded = (Map<String, TextureAtlasSprite>) f.get(map);
        uploaded.put(sprite.func_94215_i(), sprite);
    }
}
