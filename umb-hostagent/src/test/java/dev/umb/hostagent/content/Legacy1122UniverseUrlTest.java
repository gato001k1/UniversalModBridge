package dev.umb.hostagent.content;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.util.List;

import org.junit.jupiter.api.Test;

class Legacy1122UniverseUrlTest {
    @Test
    void isolatedUrlsCarryBootProbeAndPack200RuntimeLibraries() throws Exception {
        List<File> manifest = List.of(
                new File("commons-compress-1.28.0.jar"),
                new File("commons-io-2.20.0.jar"),
                new File("umb-legacy1122-pack200.jar"));
        List<URL> urls = Legacy1122Universe.isolatedUrls(manifest,
                new File("umb-legacy1122-boot.jar"), new File("umb-legacy1122-legacyside.jar"));
        String joined = urls.toString();
        assertTrue(joined.contains("umb-legacy1122-boot.jar"));
        assertTrue(joined.contains("umb-legacy1122-pack200.jar"));
        assertTrue(joined.contains("commons-compress-1.28.0.jar"));
        assertTrue(joined.contains("commons-io-2.20.0.jar"));
    }
}
