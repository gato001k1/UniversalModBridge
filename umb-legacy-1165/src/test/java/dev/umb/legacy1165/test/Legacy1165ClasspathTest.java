package dev.umb.legacy1165.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.umb.legacy1165.boot.Legacy1165Classpath;

class Legacy1165ClasspathTest {

    @Test
    void blankLinesAndCommentsAreSkipped() throws Exception {
        Path tmp = Files.createTempDirectory("umb-legacy1165-cp-test");
        File repo = tmp.toFile();
        File a = new File(repo, "a.jar");
        Files.write(a.toPath(), new byte[]{1, 2, 3});
        File manifest = new File(repo, "cp.txt");
        Files.write(manifest.toPath(),
                ("# a comment\n\n  \na.jar\n").getBytes(StandardCharsets.UTF_8));

        List<File> files = Legacy1165Classpath.readManifest(repo, manifest);
        assertEquals(1, files.size());
        assertEquals(a.getCanonicalFile(), files.get(0).getCanonicalFile());
    }

    @Test
    void missingReferencedFileThrowsWithAHelpfulMessage() throws Exception {
        Path tmp = Files.createTempDirectory("umb-legacy1165-cp-test2");
        File repo = tmp.toFile();
        File manifest = new File(repo, "cp.txt");
        Files.write(manifest.toPath(), "does-not-exist.jar\n".getBytes(StandardCharsets.UTF_8));

        IOException e = assertThrows(IOException.class, () -> Legacy1165Classpath.readManifest(repo, manifest));
        assertTrue(e.getMessage().contains("does-not-exist.jar"));
    }

    @Test
    void emptyManifestThrows() throws Exception {
        Path tmp = Files.createTempDirectory("umb-legacy1165-cp-test3");
        File repo = tmp.toFile();
        File manifest = new File(repo, "cp.txt");
        Files.write(manifest.toPath(), "# only comments\n".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> Legacy1165Classpath.readManifest(repo, manifest));
    }

    @Test
    void missingManifestFileThrows() {
        File repo = new File(".");
        File manifest = new File(repo, "definitely-not-a-real-file-xyz.txt");
        assertThrows(IOException.class, () -> Legacy1165Classpath.readManifest(repo, manifest));
    }

    @Test
    void toUrlsPreservesOrderAndCount() throws Exception {
        Path tmp = Files.createTempDirectory("umb-legacy1165-cp-test4");
        File a = new File(tmp.toFile(), "a.jar");
        File b = new File(tmp.toFile(), "b.jar");
        Files.write(a.toPath(), new byte[]{1});
        Files.write(b.toPath(), new byte[]{2});
        List<File> files = List.of(a, b);
        URL[] urls = Legacy1165Classpath.toUrls(files);
        assertEquals(2, urls.length);
        assertTrue(urls[0].toString().endsWith("a.jar"));
        assertTrue(urls[1].toString().endsWith("b.jar"));
    }

    @Test
    void hasJarNamedIsCaseInsensitiveSubstring() throws Exception {
        Path tmp = Files.createTempDirectory("umb-legacy1165-cp-test5");
        File f = new File(tmp.toFile(), "Forge-Universal.JAR");
        Files.write(f.toPath(), new byte[]{1});
        assertTrue(Legacy1165Classpath.hasJarNamed(List.of(f), "universal"));
        assertTrue(Legacy1165Classpath.hasJarNamed(List.of(f), "forge"));
        assertEquals(false, Legacy1165Classpath.hasJarNamed(List.of(f), "launchwrapper"));
    }

    @Test
    void clientDistPutsClientOverlayAndClientJarAheadOfServerJars() throws IOException {
        Path dir = Files.createTempDirectory("umb-1165-dist");
        File serverPatched = Files.createFile(dir.resolve("mc-server-srg-patched-at.jar")).toFile();
        File server = Files.createFile(dir.resolve("mc-server-srg-at.jar")).toFile();
        File client = Files.createFile(dir.resolve("mc-client-srg-at.jar")).toFile();
        File gson = Files.createFile(dir.resolve("gson-2.8.0.jar")).toFile();
        List<File> manifest = List.of(serverPatched, server, client, gson);

        // Without the client overlay the unpatched client jar must not move (Forge patches).
        assertEquals(manifest, Legacy1165Classpath.forDist(manifest, "CLIENT"));

        File clientPatched = Files.createFile(dir.resolve("mc-client-srg-patched-at.jar")).toFile();
        List<File> ordered = Legacy1165Classpath.forDist(manifest, "CLIENT");
        assertEquals(List.of(clientPatched.getAbsoluteFile(), client, serverPatched, server, gson), ordered);
        // Any other dist keeps the dedicated-server composition untouched.
        assertEquals(manifest, Legacy1165Classpath.forDist(manifest, "DEDICATED_SERVER"));
        assertEquals(manifest, Legacy1165Classpath.forDist(manifest, null));
    }
}
