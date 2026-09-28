package dev.umb.hostagent.content.fluid;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FluidEntryTest {
    @Test
    void parsesForgePropertiesAndKeepsNegativeDensityHonest() {
        FluidEntry f = FluidEntry.from(new com.google.gson.JsonParser().parse("""
            {"name":"steam","density":-1000,"gaseous":false,"temperature":1000,
             "viscosity":500,"luminosity":7,"iconName":"railcraft:fluids/steam_still",
             "blockId":"Railcraft:tile.railcraft.fluid.steam"}
            """).getAsJsonObject());
        assertEquals("steam", f.name);
        assertEquals(-1000, f.density);
        assertFalse(f.gaseous);
        assertTrue(f.belongsTo("railcraft"));
        assertFalse(f.belongsTo("hbm"));
    }

    @Test
    void readerCountsOnlyNamespaceOwnedFluidBlockLinks() throws Exception {
        Path p = Files.createTempFile("umb-fluids", ".json");
        Files.writeString(p, """
            {"fluids":[
              {"name":"acid","blockId":"Example:tile.acid","density":2500},
              {"name":"water","blockId":"minecraft:water"},
              {"name":"bad","blockId":"example_no_colon"}]}
            """);
        List<FluidEntry> entries = FluidSnapshotReader.load(p, "example");
        assertEquals(1, entries.size());
        assertEquals("acid", entries.get(0).name);
        Files.deleteIfExists(p);
    }

    @Test
    void absentIconIsARealMissingTextureNotAPlaceholder() {
        FluidEntry f = FluidEntry.from(new com.google.gson.JsonParser().parse(
                "{\"name\":\"corium\",\"blockId\":\"hbm:tile.corium\"}").getAsJsonObject());
        assertNull(f.iconName);
    }

    @Test
    void gaseousPolicyReversesOnlyTheVerticalDestination() {
        FluidEntry gas = FluidEntry.from(new com.google.gson.JsonParser().parse(
                "{\"name\":\"steam\",\"density\":-1000,\"blockId\":\"railcraft:steam\"}").getAsJsonObject());
        FluidEntry liquid = FluidEntry.from(new com.google.gson.JsonParser().parse(
                "{\"name\":\"oil\",\"density\":800,\"blockId\":\"railcraft:oil\"}").getAsJsonObject());
        assertEquals(net.minecraft.core.Direction.UP, FluidFlowPolicy.verticalSpreadDirection(gas));
        assertEquals(net.minecraft.core.Direction.DOWN, FluidFlowPolicy.verticalSpreadDirection(liquid));
    }

    @Test
    void generatorStagesPackAndEmitsBucketModelLangAndFluidManifest(@org.junit.jupiter.api.io.TempDir Path tmp)
            throws Exception {
        Path source = tmp.resolve("src");
        Path blocks = source.resolve("assets/example/textures/atlas-dump/blocks");
        Path items = source.resolve("assets/example/textures/atlas-dump/items");
        Files.createDirectories(blocks);
        Files.createDirectories(items);
        Files.writeString(blocks.resolve("oil_still.png"), "still");
        Files.writeString(blocks.resolve("oil_flow.png"), "flow");
        Files.writeString(items.resolve("bucket_oil.png"), "bucket");
        Path snapshot = tmp.resolve("snapshot.json");
        Files.writeString(snapshot, "{\"fluids\":[{\"name\":\"oil\",\"blockId\":\"example:oil\",\"iconName\":\"example:oil_still\"}]}");
        Path out = tmp.resolve("example-fluids");
        FluidPackGenerator.Counts counts = FluidPackGenerator.generate(snapshot, source, out, "example");
        assertEquals(1, counts.fluids());
        assertEquals(1, counts.stillWired());
        assertEquals(1, counts.flowingWired());
        assertEquals(0, counts.bucketTextureMissing());
        assertTrue(Files.isRegularFile(out.resolve("assets/example/models/item/fluid_oil.json")));
        assertTrue(Files.readString(out.resolve("assets/example/lang/en_us.json")).contains("Oil Bucket"));
        assertTrue(Files.readString(out.resolve("data/example/umb/fluid_models.json")).contains("fluid_oil"));
    }
}
