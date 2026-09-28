package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.umb.legacy1165.guigeom.ScreenGeometry1165;

/**
 * ironchest-visuals lane: the 1.16.5 screen-geometry extractor must recover the exact
 * per-variant panel size + background texture the mod's own classes carry. Every number
 * below is javap-grounded against ironchest-1.16.5-11.2.21.jar independently of the
 * extractor (IronChestsTypes.&lt;clinit&gt; constants + IronChestScreen ctor + container
 * factory/DeferredRegister calls - see the lane report): the test fails if the extractor
 * drifts from the mod's own data, and self-skips without the real jar.
 */
class ScreenGeometry1165Test {

    private static final class Expect {
        final int x;
        final int y;
        final String tex;
        final int sw;
        final int sh;

        Expect(int x, int y, String tex, int sw, int sh) {
            this.x = x;
            this.y = y;
            this.tex = tex;
            this.sw = sw;
            this.sh = sh;
        }
    }

    @Test
    void extractsAllEightChestVariantsExactly() throws Exception {
        File repo = TestRepo.find();
        File jar = new File(repo, "research/out/legacy-1165/ironchest-1.16.5-11.2.21.jar");
        assumeTrue(jar.isFile(), "ironchest 1.16.5 jar missing - skipping");

        Map<String, Expect> expected = new LinkedHashMap<String, Expect>();
        expected.put("ironchest:copper_chest",
                new Expect(184, 204, "ironchest:textures/gui/copper_container.png", 256, 256));
        expected.put("ironchest:crystal_chest",
                new Expect(238, 276, "ironchest:textures/gui/diamond_container.png", 256, 276));
        expected.put("ironchest:diamond_chest",
                new Expect(238, 276, "ironchest:textures/gui/diamond_container.png", 256, 276));
        expected.put("ironchest:dirt_chest",
                new Expect(184, 184, "ironchest:textures/gui/dirt_container.png", 256, 256));
        expected.put("ironchest:gold_chest",
                new Expect(184, 276, "ironchest:textures/gui/gold_container.png", 256, 276));
        expected.put("ironchest:iron_chest",
                new Expect(184, 222, "ironchest:textures/gui/iron_container.png", 256, 256));
        expected.put("ironchest:obsidian_chest",
                new Expect(238, 276, "ironchest:textures/gui/diamond_container.png", 256, 276));
        expected.put("ironchest:silver_chest",
                new Expect(184, 258, "ironchest:textures/gui/silver_container.png", 256, 276));

        // The extractor's API is package-private (era-tool, not a public contract),
        // so this reaches it reflectively; a missing guigeom unit self-skips.
        Class<?> extractor;
        try {
            extractor = Class.forName("dev.umb.legacy1165.guigeom.ScreenGeometry1165");
        } catch (ClassNotFoundException e) {
            assumeTrue(false, "guigeom jar not on test classpath - run build.ps1 first");
            return;
        }
        java.lang.reflect.Method extract =
                extractor.getDeclaredMethod("extract", File.class);
        extract.setAccessible(true);
        Object result = extract.invoke(null, jar);
        java.lang.reflect.Field rowsField = result.getClass().getDeclaredField("rows");
        rowsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<Object> rows =
                (java.util.List<Object>) rowsField.get(result);
        assertEquals(8, rows.size(), "expected exactly the 8 registered chest variants");
        Map<String, Expect> remaining = new LinkedHashMap<String, Expect>(expected);
        for (Object row : rows) {
            Class<?> rc = row.getClass();
            String id = (String) field(rc, "containerId").get(row);
            Expect e = remaining.remove(id);
            assertTrue(e != null, "unexpected containerId row: " + id);
            assertEquals(e.x, field(rc, "xSize").get(row), id + " xSize");
            assertEquals(e.y, field(rc, "ySize").get(row), id + " ySize");
            assertEquals(e.tex, field(rc, "textureNs").get(row) + ":"
                    + withTexturesPrefix((String) field(rc, "texturePath").get(row)),
                    id + " texture");
            assertEquals(e.sw, field(rc, "sheetWidth").get(row), id + " sheetWidth");
            assertEquals(e.sh, field(rc, "sheetHeight").get(row), id + " sheetHeight");
            assertEquals("com.progwml6.ironchest.common.inventory.IronChestContainer",
                    field(rc, "containerClass").get(row), id + " containerClass");
        }
        assertTrue(remaining.isEmpty(), "missing rows for: " + remaining.keySet());
    }

    private static java.lang.reflect.Field field(Class<?> c, String name)
            throws Exception {
        java.lang.reflect.Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static String withTexturesPrefix(String p) {
        return p.startsWith("textures/") ? p : "textures/" + p;
    }
}
