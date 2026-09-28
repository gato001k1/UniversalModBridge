package dev.umb.rendermap;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code Snapshot.hbmItems()}/{@code hbmBlocks()} used to hardcode {@code id.startsWith("hbm:")}
 * as the definition of "this mod's own content" — on any other mod that matches nothing.
 * {@code modItems()}/{@code modBlocks()} redefine it as "not vanilla," and
 * {@code resolveLocalName} is the new namespace-agnostic id lookup {@link ModRegistryResolver}
 * relies on.
 */
class SnapshotGenericTest {

    @Test
    void modItemsAndBlocksAreEverythingThatIsNotVanilla_regardlessOfNamespaceString() {
        Snapshot snap = new Snapshot();
        Snapshot.Itm vanilla = new Snapshot.Itm(); vanilla.id = "minecraft:stick";
        Snapshot.Itm modA = new Snapshot.Itm(); modA.id = "examplemod:widget";
        Snapshot.Itm modB = new Snapshot.Itm(); modB.id = "othermod:thing";
        snap.items.add(vanilla); snap.items.add(modA); snap.items.add(modB);

        Snapshot.Blk vanillaBlk = new Snapshot.Blk(); vanillaBlk.id = "minecraft:dirt";
        Snapshot.Blk modBlk = new Snapshot.Blk(); modBlk.id = "examplemod:special_block";
        snap.blocks.add(vanillaBlk); snap.blocks.add(modBlk);

        assertEquals(2, snap.modItems().size());
        assertTrue(snap.modItems().stream().noneMatch(i -> i.id.startsWith("minecraft:")));
        assertEquals(1, snap.modBlocks().size());
        assertEquals("examplemod:special_block", snap.modBlocks().get(0).id);
    }

    @Test
    void resolveLocalName_neverGuessesANamespace_prefersNonVanillaOnCollision() {
        Snapshot snap = new Snapshot();
        snap.localNameToIds.put("tile.foo", java.util.List.of("minecraft:tile.foo", "examplemod:tile.foo"));
        assertEquals("examplemod:tile.foo", snap.resolveLocalName("tile.foo"));
        assertNull(snap.resolveLocalName("no.such.name"));
    }
}
