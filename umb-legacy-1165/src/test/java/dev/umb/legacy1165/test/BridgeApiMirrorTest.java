package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

/**
 * Enforces the constraint from the swarm brief: "the bridge API is shared across eras and is
 * mirrored between modules with a byte-identical build gate." This module's copy of
 * {@code dev.umb.bridge.api} MUST stay byte-for-byte identical to {@code umb-legacy}'s canonical
 * copy - if this test ever fails, someone edited one side without the other, which would silently
 * break the "host never learns there are three eras" property the whole design depends on.
 */
class BridgeApiMirrorTest {

    @Test
    void bridgeApiIsByteIdenticalToTheCanonicalUmbLegacyCopy() throws Exception {
        File repo = TestRepo.find();
        File canonical = new File(repo, "umb-legacy/src/bridge-api/java/dev/umb/bridge/api");
        File mirror = new File(repo, "umb-legacy-1165/src/bridge-api/java/dev/umb/bridge/api");
        assertTrue(canonical.isDirectory(), "canonical bridge-api dir missing: " + canonical);
        assertTrue(mirror.isDirectory(), "mirror bridge-api dir missing: " + mirror);

        TreeSet<String> canonicalNames = new TreeSet<>(Arrays.asList(canonical.list()));
        TreeSet<String> mirrorNames = new TreeSet<>(Arrays.asList(mirror.list()));
        assertEquals(canonicalNames, mirrorNames, "file list diverged between the two copies");
        assertFalse(canonicalNames.isEmpty());

        for (String name : canonicalNames) {
            byte[] a = Files.readAllBytes(new File(canonical, name).toPath());
            byte[] b = Files.readAllBytes(new File(mirror, name).toPath());
            assertTrue(Arrays.equals(a, b), name + " differs between umb-legacy and umb-legacy-1165");
        }
    }
}
