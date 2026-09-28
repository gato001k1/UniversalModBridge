package dev.umb.legacy1122.test;

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

import dev.umb.legacy1122.boot.Legacy1122Classpath;

class Legacy1122ClasspathTest {

    @Test
    void blankLinesAndCommentsAreSkipped() throws Exception {
        Path tmp = Files.createTempDirectory("umb-legacy1122-cp-test");
        File repo = tmp.toFile();
        File a = new File(repo, "a.jar");
        Files.write(a.toPath(), new byte[]{1, 2, 3});
        File manifest = new File(repo, "cp.txt");
        Files.write(manifest.toPath(),
                ("# a comment\n\n  \na.jar\n").getBytes(StandardCharsets.UTF_8));

        List<File> files = Legacy1122Classpath.readManifest(repo, manifest);
        assertEquals(1, files.size());
        assertEquals(a.getCanonicalFile(), files.get(0).getCanonicalFile());
    }

    @Test
    void missingReferencedFileThrowsWithAHelpfulMessage() throws Exception {
        Path tmp = Files.createTempDirectory("umb-legacy1122-cp-test2");
        File repo = tmp.toFile();
        File manifest = new File(repo, "cp.txt");
        Files.write(manifest.toPath(), "does-not-exist.jar\n".getBytes(StandardCharsets.UTF_8));

        IOException e = assertThrows(IOException.class, () -> Legacy1122Classpath.readManifest(repo, manifest));
        assertTrue(e.getMessage().contains("does-not-exist.jar"));
    }

    @Test
    void emptyManifestThrows() throws Exception {
        Path tmp = Files.createTempDirectory("umb-legacy1122-cp-test3");
        File repo = tmp.toFile();
        File manifest = new File(repo, "cp.txt");
        Files.write(manifest.toPath(), "# only comments\n".getBytes(StandardCharsets.UTF_8));

        assertThrows(IOException.class, () -> Legacy1122Classpath.readManifest(repo, manifest));
    }

    @Test
    void missingManifestFileThrows() {
        File repo = new File(".");
        File manifest = new File(repo, "definitely-not-a-real-file-xyz.txt");
        assertThrows(IOException.class, () -> Legacy1122Classpath.readManifest(repo, manifest));
    }

    @Test
    void toUrlsPreservesOrderAndCount() throws Exception {
        Path tmp = Files.createTempDirectory("umb-legacy1122-cp-test4");
        File a = new File(tmp.toFile(), "a.jar");
        File b = new File(tmp.toFile(), "b.jar");
        Files.write(a.toPath(), new byte[]{1});
        Files.write(b.toPath(), new byte[]{2});
        List<File> files = List.of(a, b);
        URL[] urls = Legacy1122Classpath.toUrls(files);
        assertEquals(2, urls.length);
        assertTrue(urls[0].toString().endsWith("a.jar"));
        assertTrue(urls[1].toString().endsWith("b.jar"));
    }

    @Test
    void hasJarNamedIsCaseInsensitiveSubstring() throws Exception {
        Path tmp = Files.createTempDirectory("umb-legacy1122-cp-test5");
        File f = new File(tmp.toFile(), "Forge-Universal.JAR");
        Files.write(f.toPath(), new byte[]{1});
        assertTrue(Legacy1122Classpath.hasJarNamed(List.of(f), "universal"));
        assertTrue(Legacy1122Classpath.hasJarNamed(List.of(f), "forge"));
        assertEquals(false, Legacy1122Classpath.hasJarNamed(List.of(f), "launchwrapper"));
    }
}
