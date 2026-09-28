package dev.umb.objbridge.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RenderMapLinkageTest {
    @Test
    void blockTileEntityClassRecoversMissingReverseBlockIdsEdge() {
        JsonObject root = new JsonObject();

        JsonArray blocks = new JsonArray();
        JsonObject block = new JsonObject();
        block.addProperty("id", "test:machine");
        block.addProperty("className", "test.MachineBlock");
        block.addProperty("renderType", -1);
        block.addProperty("hasTileEntity", true);
        block.addProperty("tileEntityClass", "test.MachineTile");
        block.add("models", new JsonArray());
        block.add("textures", new JsonArray());
        block.add("groups", new JsonArray());
        blocks.add(block);
        root.add("blocks", blocks);

        JsonArray tes = new JsonArray();
        JsonObject te = new JsonObject();
        te.addProperty("teClass", "test.MachineTile");
        te.addProperty("rendererClass", "test.MachineRenderer");
        te.add("models", new JsonArray());
        te.add("textures", new JsonArray());
        te.add("groups", new JsonArray());
        te.add("blockIds", new JsonArray());
        tes.add(te);
        root.add("tileEntities", tes);

        RenderMap map = RenderMap.of(root);
        assertNotNull(map.tileEntityForClass("test.MachineTile"));
        assertEquals("test.MachineRenderer",
                map.tileEntityForClass("test.MachineTile").rendererClass());
        assertEquals("test.MachineRenderer",
                map.tileEntityForClass(map.blocks().get(0).tileEntityClass()).rendererClass());
    }
}
