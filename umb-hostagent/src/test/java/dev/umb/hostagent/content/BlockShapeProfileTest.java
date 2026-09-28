package dev.umb.hostagent.content;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-data gate for {@link BlockShapeProfile}: parses a synthetic block-shapes.json shaped
 * exactly like the real research/out/legacy/block-shapes.json (verified against real
 * hbm:tile.machine_purex / hbm:tile.launch_pad_large entries while building this - see
 * laneConsume-progress.md). No net.minecraft types here - {@link BlockShapesTest} covers the
 * VoxelShape builder that consumes this.
 */
class BlockShapeProfileTest {

    private static final String JSON = """
        {
          "producer": "umb-legacy block-shape probe (GAP 1)",
          "summary": {"total": 2, "ok": 2, "errors": 0, "nonCube": 1, "multiBox": 1, "metasDeduped": 20},
          "blocks": [
            {
              "id": "hbm:tile.machine_purex",
              "className": "com.hbm.blocks.machine.MachinePUREX",
              "error": null,
              "metaGroups": [
                {"metas": [0,1,2,3], "rawBounds": [0,0,0,1,1,1], "collisionAabb": [0,0,0,1,1,1],
                 "collisionBoxes": [[0,0,0,1,1,1]], "isFullCube": true}
              ]
            },
            {
              "id": "hbm:tile.launch_pad_large",
              "className": "com.hbm.blocks.bomb.LaunchPadLarge",
              "error": null,
              "metaGroups": [
                {"metas": [0,1], "rawBounds": [0,0,0,1,0.999,1], "collisionAabb": [0,0,0,1,0.999,1],
                 "collisionBoxes": [], "isFullCube": false},
                {"metas": [12], "rawBounds": [0,0,0,1,0.999,1], "collisionAabb": [0,0,0,1,0.999,1],
                 "collisionBoxes": [[1,0,-4,5,1,5],[-4,0,-4,0,1,5],[0,0.875,-4,1,1,5]], "isFullCube": false}
              ]
            }
          ]
        }
        """;

    @Test
    void parsesEveryBlockAndItsMetaGroups() {
        BlockShapeProfile p = BlockShapeProfile.parseString(JSON);
        assertEquals(2, p.size());
        assertEquals(0, p.errors);

        BlockShapeProfile.BlockEntry purex = p.get("hbm:tile.machine_purex");
        assertEquals("com.hbm.blocks.machine.MachinePUREX", purex.className);
        assertEquals(1, purex.metaGroups.size());
        assertTrue(purex.metaGroups.get(0).isFullCube);

        BlockShapeProfile.BlockEntry pad = p.get("hbm:tile.launch_pad_large");
        assertEquals(2, pad.metaGroups.size());
    }

    @Test
    void groupForFindsTheGroupCoveringTheExactMeta() {
        BlockShapeProfile p = BlockShapeProfile.parseString(JSON);
        BlockShapeProfile.BlockEntry pad = p.get("hbm:tile.launch_pad_large");

        BlockShapeProfile.MetaGroup meta1 = pad.groupFor(1);
        assertTrue(meta1.hasMeta(1));
        assertEquals(0, meta1.collisionBoxes.size(), "the slab-like group has no explicit boxes");

        BlockShapeProfile.MetaGroup meta12 = pad.groupFor(12);
        assertEquals(3, meta12.collisionBoxes.size(), "the out-of-cell directional group");
        assertTrue(meta12 != meta1);
    }

    @Test
    void groupForFallsBackToMetaZeroThenTheFirstGroupThenNull() {
        BlockShapeProfile p = BlockShapeProfile.parseString(JSON);
        BlockShapeProfile.BlockEntry pad = p.get("hbm:tile.launch_pad_large");

        // meta 99 exists nowhere - falls back to the group that covers meta 0
        BlockShapeProfile.MetaGroup fallback = pad.groupFor(99);
        assertTrue(fallback.hasMeta(0));

        assertNull(p.get("hbm:tile.nonexistent"));
    }

    @Test
    void unknownIdReturnsNull() {
        BlockShapeProfile p = BlockShapeProfile.parseString(JSON);
        assertNull(p.get("hbm:tile.does_not_exist"));
    }

    @Test
    void aMissingOrUnreadableFileBehavesLikeEmpty() {
        BlockShapeProfile p = BlockShapeProfile.load(java.nio.file.Paths.get("no/such/file.json"));
        assertEquals(0, p.size());
        assertNull(p.get("hbm:tile.machine_purex"));
    }

    @Test
    void malformedJsonFileBehavesLikeEmptyNotAnException(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp)
            throws Exception {
        java.nio.file.Path bad = tmp.resolve("bad.json");
        java.nio.file.Files.writeString(bad, "not json at all {{{");
        BlockShapeProfile p = BlockShapeProfile.load(bad);
        assertEquals(0, p.size());
    }
}
