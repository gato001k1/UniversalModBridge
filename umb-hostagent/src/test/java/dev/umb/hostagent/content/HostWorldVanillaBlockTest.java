package dev.umb.hostagent.content;

import dev.umb.hostagent.HostAgent;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Vanilla terrain must be visible to legacy code. Before {@link HostWorldImpl#vanillaLegacyKey}
 * every vanilla 26.2 block read as "minecraft:air", so a mod raytrace (e.g. a vehicle item that
 * clips the look vector against the ground before spawning) passed straight through stone and
 * silently did nothing.
 */
class HostWorldVanillaBlockTest {

    @BeforeAll
    static void boot() {
        TestSupport.ensureBootstrapped();
        Path snapshot = Paths.get("research/out/legacy/hbm-snapshot.json");
        Assumptions.assumeTrue(Files.isRegularFile(snapshot),
                "hbm-snapshot.json not present in this checkout: " + snapshot);
        HostAgent.configure(snapshot, null, "hbm");
        VanillaItemBridge.resetForTest();
        VanillaItemBridge.ensureBuilt();
    }

    @Test
    void stoneIsStoneNotAir() {
        assertArrayEquals(new String[] {"minecraft:stone", "0"}, HostWorldImpl.vanillaLegacyKey(Blocks.STONE));
    }

    @Test
    void itemlessFluidsAreNamedDirectly() {
        assertArrayEquals(new String[] {"minecraft:water", "0"}, HostWorldImpl.vanillaLegacyKey(Blocks.WATER));
        assertArrayEquals(new String[] {"minecraft:lava", "0"}, HostWorldImpl.vanillaLegacyKey(Blocks.LAVA));
    }

    @Test
    void airStaysNull() {
        assertNull(HostWorldImpl.vanillaLegacyKey(Blocks.AIR));
        assertNull(HostWorldImpl.vanillaLegacyKey(null));
    }

    @Test
    void commonTerrainHasALegacyId() {
        for (var b : new net.minecraft.world.level.block.Block[] {Blocks.DIRT, Blocks.GRASS_BLOCK,
                Blocks.SAND, Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.OAK_PLANKS}) {
            assertNotNull(HostWorldImpl.vanillaLegacyKey(b), "no legacy id for " + b);
        }
    }
}
