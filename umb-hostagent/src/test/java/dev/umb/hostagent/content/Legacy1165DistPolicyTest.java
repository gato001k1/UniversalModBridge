package dev.umb.hostagent.content;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * 1.16.5 live client mode: CLIENT first with a fresh-loader DEDICATED_SERVER fallback, an
 * explicit -Dumb.1165.dist pins one dist, and a booted era publishes its jars for captured
 * texture lookup.
 */
class Legacy1165DistPolicyTest {

    @Test
    void liveDefaultTriesClientThenFallsBackToDedicatedServer() {
        assertArrayEquals(new String[] {"CLIENT", "DEDICATED_SERVER"},
                Legacy1165Universe.distAttempts(null));
        assertArrayEquals(new String[] {"CLIENT", "DEDICATED_SERVER"},
                Legacy1165Universe.distAttempts("  "));
    }

    @Test
    void explicitDistIsPinnedWithoutFallback() {
        assertArrayEquals(new String[] {"DEDICATED_SERVER"},
                Legacy1165Universe.distAttempts("DEDICATED_SERVER"));
        assertArrayEquals(new String[] {"CLIENT"}, Legacy1165Universe.distAttempts(" CLIENT "));
    }

    @Test
    void assetJarsAreAppendedOnceInOrder() {
        String key = Legacy1165Universe.CAPTURE_ASSET_JARS_PROPERTY;
        String previous = System.getProperty(key);
        try {
            System.clearProperty(key);
            File a = new File("a-mod.jar"), b = new File("b-mod.jar");
            Legacy1165Universe.publishCaptureAssetJars(Arrays.asList(a, b));
            Legacy1165Universe.publishCaptureAssetJars(Arrays.asList(b));
            assertEquals(a.getAbsolutePath() + File.pathSeparator + b.getAbsolutePath(),
                    System.getProperty(key));
        } finally {
            if (previous == null) System.clearProperty(key);
            else System.setProperty(key, previous);
        }
    }
}
