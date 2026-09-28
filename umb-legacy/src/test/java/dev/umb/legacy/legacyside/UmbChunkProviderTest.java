package dev.umb.legacy.legacyside;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.world.gen.ChunkProviderServer;

/**
 * Bug #25-follow-up gate: the facade chunk provider must BE a ChunkProviderServer, not merely
 * an IChunkProvider. 1.7.10 mods cast {@code world.getChunkProvider()} to it (HBM
 * {@code PollutionHandler.handleWorldDestruction} does, then calls only {@code func_73149_a});
 * the old shape died with ClassCastException on every such call, every server tick.
 */
public final class UmbChunkProviderTest {
    @Test
    public void providerPassesTheModCast() {
        UmbChunkProvider provider = new UmbChunkProvider();
        ChunkProviderServer asServer = (ChunkProviderServer) provider;
        assertTrue(asServer.func_73149_a(0, 0));
        assertTrue(asServer.func_73149_a(-30000000, 30000000));
    }

    @Test
    public void singletonCarriesNoChunksAndLoadsNothing() {
        UmbChunkProvider provider = UmbChunkProvider.INSTANCE;
        assertTrue(provider instanceof ChunkProviderServer);
        assertEquals(0, provider.func_73152_e());
        assertTrue(provider.func_152380_a().isEmpty());
        assertNull(provider.func_73154_d(3, -7));
        assertNull(provider.func_73158_c(3, -7));
    }

    @Test
    public void unloadPathsAreNoOps() {
        UmbChunkProvider provider = new UmbChunkProvider();
        provider.func_73241_b(3, -7);
        provider.func_73240_a();
        assertFalse(provider.func_73156_b());
        assertFalse(provider.func_73157_c());
    }
}
