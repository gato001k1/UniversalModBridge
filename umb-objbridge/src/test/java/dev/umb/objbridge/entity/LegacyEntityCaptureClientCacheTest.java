package dev.umb.objbridge.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyEntityCaptureClientCacheTest {
    private static final class ProbeEntity {
        @SuppressWarnings("unused")
        private Object handle;

        @SuppressWarnings("unused")
        private java.util.UUID getUUID() {
            return null;
        }
    }

    @Test
    void repeatedReflectionProbesDiscoverEachMemberAtMostOnce() {
        long[] before = LegacyEntityCaptureClient.reflectionDiscoveryCountsForTests();
        for (int i = 0; i < 10_000; i++) {
            LegacyEntityCaptureClient.probeReflectionCachesForTests(ProbeEntity.class);
        }
        long[] after = LegacyEntityCaptureClient.reflectionDiscoveryCountsForTests();
        assertTrue(after[0] - before[0] <= 1, "handle field rediscovered");
        assertTrue(after[1] - before[1] <= 1, "UUID method rediscovered");
    }
}
