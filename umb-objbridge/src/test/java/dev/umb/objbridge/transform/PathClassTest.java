package dev.umb.objbridge.transform;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathClassTest {

    @Test
    void classifiesTheRealMethodNamesSeenInTheShippedData() {
        assertEquals(PathClass.WORLD, PathClass.classify("func_147500_a"));   // TileEntitySpecialRenderer.renderTileEntityAt
        assertEquals(PathClass.WORLD, PathClass.classify("doRender"));
        assertEquals(PathClass.WORLD, PathClass.classify("renderTileEntityAt"));

        assertEquals(PathClass.INVENTORY, PathClass.classify("renderInventory"));
        assertEquals(PathClass.INVENTORY, PathClass.classify("renderInventoryBlock"));
        assertEquals(PathClass.INVENTORY, PathClass.classify("setupInv"));
        assertEquals(PathClass.INVENTORY, PathClass.classify("setupModTable"));

        assertEquals(PathClass.FIRST_PERSON, PathClass.classify("setupFirstPerson"));
        assertEquals(PathClass.FIRST_PERSON, PathClass.classify("renderFirstPerson"));

        assertEquals(PathClass.THIRD_PERSON, PathClass.classify("setupThirdPerson"));

        assertEquals(PathClass.COMMON, PathClass.classify("renderCommon"));
        assertEquals(PathClass.COMMON, PathClass.classify("renderCommonWithStack"));

        assertEquals(PathClass.OTHER, PathClass.classify("renderOther"));
        assertEquals(PathClass.OTHER, PathClass.classify(null));
        assertEquals(PathClass.OTHER, PathClass.classify(""));
        assertEquals(PathClass.OTHER, PathClass.classify("someUnrelatedHelper"));
    }
}
