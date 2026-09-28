package dev.umb.console;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /files/...} is the only endpoint that reads arbitrary paths, so every rejection it
 * must make is pinned here. A miss would expose the whole disk to anything that can reach
 * 127.0.0.1.
 */
class SafePathTest {

    @TempDir Path root;
    Path outside;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(root.resolve("win-hbm3/shots"));
        Files.write(root.resolve("win-hbm3/shots/01-menu.png"), new byte[]{(byte) 0x89, 'P', 'N', 'G'});
        Files.write(root.resolve("hostagent-probe.log"), "PROBE-OK".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("secret.key"), "nope".getBytes(StandardCharsets.UTF_8));
        outside = root.getParent().resolve("outside-" + System.nanoTime() + ".txt");
        Files.write(outside, "should never be served".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void servesAFileInsideTheRoot() {
        Path p = SafeFiles.resolve(root, "win-hbm3/shots/01-menu.png");
        assertNotNull(p);
        assertTrue(p.endsWith("01-menu.png"));
    }

    @Test
    void servesADeepUrlEncodedPath() {
        assertNotNull(SafeFiles.resolve(root, "win-hbm3%2Fshots%2F01-menu.png"));
        assertNotNull(SafeFiles.resolve(root, "hostagent-probe.log"));
    }

    @Test
    void rejectsDotDotTraversalInEveryEncoding() {
        assertNull(SafeFiles.resolve(root, "../" + outside.getFileName()));
        assertNull(SafeFiles.resolve(root, "..%2F" + outside.getFileName()));
        assertNull(SafeFiles.resolve(root, "%2e%2e%2f" + outside.getFileName()));
        assertNull(SafeFiles.resolve(root, "win-hbm3/../../" + outside.getFileName()));
        assertNull(SafeFiles.resolve(root, "win-hbm3/shots/../../../" + outside.getFileName()));
        assertNull(SafeFiles.resolve(root, "..\\" + outside.getFileName()));
    }

    @Test
    void rejectsAbsoluteAndDriveAndUncPaths() {
        assertNull(SafeFiles.resolve(root, "C:/Windows/win.ini"));
        assertNull(SafeFiles.resolve(root, "C:\\Windows\\win.ini"));
        assertNull(SafeFiles.resolve(root, "/etc/passwd"));
        assertNull(SafeFiles.resolve(root, "//server/share/x.txt"));
        assertNull(SafeFiles.resolve(root, "\\\\server\\share\\x.txt"));
        assertNull(SafeFiles.resolve(root, outside.toAbsolutePath().toString()));
    }

    @Test
    void rejectsDisallowedExtensions() {
        assertNull(SafeFiles.resolve(root, "secret.key"));
        assertTrue(SafeFiles.hasAllowedExtension("a.png"));
        assertTrue(SafeFiles.hasAllowedExtension("REPORT.md"));
        assertTrue(SafeFiles.hasAllowedExtension("pack.mcmeta"));
        assertTrue(SafeFiles.hasAllowedExtension("x.LOG"));
        assertTrue(!SafeFiles.hasAllowedExtension("run.exe"));
        assertTrue(!SafeFiles.hasAllowedExtension("noextension"));
        assertTrue(!SafeFiles.hasAllowedExtension("trailing."));
        assertTrue(!SafeFiles.hasAllowedExtension(null));
    }

    @Test
    void rejectsDirectoriesEmptyAndNulByte() {
        assertNull(SafeFiles.resolve(root, "win-hbm3"));
        assertNull(SafeFiles.resolve(root, "win-hbm3/shots"));
        assertNull(SafeFiles.resolve(root, ""));
        assertNull(SafeFiles.resolve(root, "   "));
        assertNull(SafeFiles.resolve(root, null));
        assertNull(SafeFiles.resolve(root, "a\u0000.png"));
        assertNull(SafeFiles.resolve(null, "a.png"));
    }

    @Test
    void rejectsAMissingFileAndEmptySegments() {
        assertNull(SafeFiles.resolve(root, "win-hbm3/shots/nope.png"));
        assertNull(SafeFiles.resolve(root, "win-hbm3//shots/01-menu.png"));
        assertNull(SafeFiles.resolve(root, "./hostagent-probe.log"));
    }

    @Test
    void contentTypesAreSane() {
        assertEquals("image/png", SafeFiles.contentType("a.png"));
        assertEquals("image/jpeg", SafeFiles.contentType("a.JPG"));
        assertTrue(SafeFiles.contentType("a.json").startsWith("application/json"));
        assertTrue(SafeFiles.contentType("a.log").startsWith("text/plain"));
        assertTrue(SafeFiles.contentType(null).startsWith("text/plain"));
    }

    @Test
    void relativeUrlIsForwardSlashedAndRootRelative() {
        Path p = root.resolve("win-hbm3/shots/01-menu.png");
        assertEquals("win-hbm3/shots/01-menu.png", SafeFiles.relativeUrl(root, p));
        assertNull(SafeFiles.relativeUrl(root, outside));
    }

    @Test
    void decodeRejectsMalformedPercentEscapes() {
        assertNull(SafeFiles.decode("%zz"));
        assertEquals("a b", SafeFiles.decode("a%20b"));
    }
}
