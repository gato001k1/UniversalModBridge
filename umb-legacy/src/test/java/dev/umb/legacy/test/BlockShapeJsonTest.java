package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import dev.umb.legacy.api.BlockMetaShape;
import dev.umb.legacy.api.BlockShapeEntry;
import dev.umb.legacy.boot.Json;

/** Legacy compatibility behavior. */
class BlockShapeJsonTest {

    @Test
    void fullCubeBlockSerialisesOneDedupedGroupCoveringAllMetas() {
        // minecraft:stone-shaped control: one shape, all 16 metas collapse into it.
        int[] allMetas = new int[16];
        for (int i = 0; i < 16; i++) {
            allMetas[i] = i;
        }
        BlockMetaShape cube = new BlockMetaShape(allMetas,
                new double[]{0, 0, 0, 1, 1, 1}, new double[]{0, 0, 0, 1, 1, 1}, new double[]{0, 0, 0, 1, 1, 1},
                new double[][]{{0, 0, 0, 1, 1, 1}}, true, true, true, null);
        BlockShapeEntry stone = new BlockShapeEntry("minecraft:stone", 1, "net.minecraft.block.Block", null,
                Arrays.asList(cube));

        Map<String, Integer> counts = counts(1, 1, 0, 0, 0, 15);
        String json = Json.blockShapes(Arrays.asList(stone), counts);

        assertTrue(json.startsWith("{\n"));
        assertTrue(json.contains("\"id\": \"minecraft:stone\""));
        assertTrue(json.contains("\"isFullCube\": true"));
        assertTrue(json.contains("\"metas\": [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15]"));
        assertTrue(json.contains("\"metasDeduped\": 15"));
        assertBalanced(json);
    }

    @Test
    void nonCubeMultiBoxBlockSerialisesEveryBoxAndANullSlot() {
        // a torch-shaped control: no collision box (null), one selection box, not opaque, not
        // full-cube - and a machine-shaped control with TWO collision boxes (the multi-hitbox case).
        BlockMetaShape torchShape = new BlockMetaShape(new int[]{0, 1, 2, 3, 4},
                new double[]{0.4, 0.0, 0.4, 0.6, 0.6, 0.6}, null, new double[]{0.4, 0.0, 0.4, 0.6, 0.6, 0.6},
                new double[0][], false, false, false, null);
        BlockShapeEntry torch = new BlockShapeEntry("minecraft:torch", 50, "net.minecraft.block.BlockTorch", null,
                Arrays.asList(torchShape));

        BlockMetaShape machineShape = new BlockMetaShape(new int[]{0},
                new double[]{0, 0, 0, 1, 2, 1}, new double[]{0, 0, 0, 1, 2, 1}, new double[]{0, 0, 0, 1, 2, 1},
                new double[][]{{0, 0, 0, 1, 1, 1}, {0.25, 1, 0.25, 0.75, 2, 0.75}},
                true, true, false, null);
        BlockMetaShape brokenMeta = new BlockMetaShape(new int[]{1},
                null, null, null, new double[0][], false, false, false,
                "java.lang.NullPointerException: boom at some.Class.method(Class.java:1)");
        BlockShapeEntry machine = new BlockShapeEntry("hbm:tile.machine_radar_large", 3050,
                "com.hbm.blocks.machine.BlockRadarLarge", null, Arrays.asList(machineShape, brokenMeta));

        Map<String, Integer> counts = counts(3, 2, 0, 2, 1, 26);
        String json = Json.blockShapes(Arrays.asList(torch, machine), counts);

        assertTrue(json.contains("\"collisionAabb\": null"));
        assertTrue(json.contains("\"id\": \"hbm:tile.machine_radar_large\""));
        assertTrue(json.contains("[0.25, 1.0, 0.25, 0.75, 2.0, 0.75]"));
        assertTrue(json.contains("\"error\": \"java.lang.NullPointerException: boom at some.Class.method(Class.java:1)\""));
        assertTrue(json.contains("\"nonCube\": 2"));
        assertTrue(json.contains("\"multiBox\": 1"));
        assertBalanced(json);
    }

    @Test
    void wholeBlockErrorSerialisesWithEmptyMetaGroups() {
        BlockShapeEntry broken = new BlockShapeEntry("hbm:tile.something_weird", 4000,
                "com.hbm.blocks.Weird", "java.lang.IllegalStateException: registry ghost", Collections.<BlockMetaShape>emptyList());

        Map<String, Integer> counts = counts(1, 0, 1, 0, 0, 0);
        String json = Json.blockShapes(Arrays.asList(broken), counts);

        assertTrue(json.contains("\"error\": \"java.lang.IllegalStateException: registry ghost\""));
        assertTrue(json.contains("\"metaGroups\": []"));
        assertTrue(json.contains("\"errors\": 1"));
        assertBalanced(json);
    }

    private static Map<String, Integer> counts(int total, int ok, int errors, int nonCube, int multiBox, int metasDeduped) {
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        m.put("total", total);
        m.put("ok", ok);
        m.put("errors", errors);
        m.put("nonCube", nonCube);
        m.put("multiBox", multiBox);
        m.put("metasDeduped", metasDeduped);
        return m;
    }

    /** Cheap structural check: braces/brackets balance outside of strings (same helper as JsonSnapshotTest). */
    private static void assertBalanced(String json) {
        int curly = 0;
        int square = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"': inString = true; break;
                case '{': curly++; break;
                case '}': curly--; break;
                case '[': square++; break;
                case ']': square--; break;
                default: break;
            }
            assertTrue(curly >= 0 && square >= 0, "unbalanced at " + i);
        }
        assertEquals(0, curly, "unbalanced braces");
        assertEquals(0, square, "unbalanced brackets");
        assertFalse(inString, "unterminated string");
    }
}
