package dev.umb.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mods panel and {@code legacy.ps1} must agree about the modid, because the modid names the
 * run directory every status JSON is read from. These build real jars, so the zip reading is
 * exercised rather than mocked.
 */
class ModsListingTest {

    /** HBM's own mcmod.info shape: a top-level array. */
    private static final String HBM_STYLE = """
            [
            {
              "modid": "hbm",
              "name": "Hbm's Nuclear Tech",
              "version":"1.0.27_X5771",
              "mcversion": "1.7.10"
            }
            ]
            """;

    /** The other legal 1.7.10 shape: {"modListVersion":2,"modList":[...]}. */
    private static final String MODLIST_STYLE = """
            {"modListVersion":2,"modList":[{"modid":"thermal","name":"Thermal","version":"1.2",
             "mcversion":"1.7.10"}]}
            """;

    private static Path jar(Path dir, String name, String mcmodInfo) throws IOException {
        Path p = dir.resolve(name);
        try (OutputStream os = Files.newOutputStream(p); ZipOutputStream zos = new ZipOutputStream(os)) {
            if (mcmodInfo != null) {
                zos.putNextEntry(new ZipEntry("mcmod.info"));
                zos.write(mcmodInfo.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.putNextEntry(new ZipEntry("com/example/Mod.class"));
            zos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            zos.closeEntry();
        }
        return p;
    }

    @Test
    void listsOnlyJarsSortedByName(@TempDir Path repo) throws IOException {
        Path mods = repo.resolve("research/mods-hbm");
        Files.createDirectories(mods);
        jar(mods, "zeta.jar", HBM_STYLE);
        jar(mods, "alpha.jar", MODLIST_STYLE);
        Files.write(mods.resolve("notes.txt"), "not a jar".getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(mods.resolve("subdir.jar"));   // a DIRECTORY named *.jar

        List<Mods.ModInfo> got = Mods.list(repo, List.of(mods));
        assertEquals(2, got.size());
        assertEquals("alpha.jar", got.get(0).file);
        assertEquals("zeta.jar", got.get(1).file);
        assertEquals("thermal", got.get(0).modid);
        assertEquals("hbm", got.get(1).modid);
    }

    @Test
    void readsModidNameVersionMcversionFromBothShapes(@TempDir Path repo) throws IOException {
        Path dir = repo.resolve("legacy-mods");
        Files.createDirectories(dir);
        Path a = jar(dir, "a.jar", HBM_STYLE);
        Mods.ModInfo m = Mods.describe(repo, dir, a);
        assertEquals("hbm", m.modid);
        assertEquals("Hbm's Nuclear Tech", m.name);
        assertEquals("1.0.27_X5771", m.version);
        assertEquals("1.7.10", m.mcversion);
        assertNull(m.error);
        assertEquals("legacy-mods\\a.jar".replace('\\', java.io.File.separatorChar), m.repoPath);
        assertTrue(m.sizeBytes > 0);

        Path b = jar(dir, "b.jar", MODLIST_STYLE);
        assertEquals("thermal", Mods.describe(repo, dir, b).modid);
    }

    @Test
    void aJarWithNoMcmodInfoFallsBackToItsFileNameAndSaysWhy(@TempDir Path repo) throws IOException {
        Path dir = repo.resolve("legacy-mods");
        Files.createDirectories(dir);
        Path p = jar(dir, "Some Weird MOD v2.jar", null);
        Mods.ModInfo m = Mods.describe(repo, dir, p);
        assertEquals("some_weird_mod_v2", m.modid);
        assertNotNull(m.error);
        assertTrue(m.error.contains("mcmod.info"));
    }

    @Test
    void brokenMcmodInfoNeverThrows(@TempDir Path repo) throws IOException {
        Path dir = repo.resolve("legacy-mods");
        Files.createDirectories(dir);
        Mods.ModInfo m = Mods.describe(repo, dir, jar(dir, "x.jar", "{ this is not json"));
        assertEquals("x", m.modid);
        assertNotNull(m.error);

        Mods.ModInfo m2 = Mods.describe(repo, dir, jar(dir, "y.jar", "[]"));
        assertEquals("y", m2.modid);
        assertNotNull(m2.error);
    }

    @Test
    void aBomInMcmodInfoIsTolerated(@TempDir Path repo) throws IOException {
        Path dir = repo.resolve("legacy-mods");
        Files.createDirectories(dir);
        Mods.ModInfo m = Mods.describe(repo, dir, jar(dir, "z.jar", "﻿" + HBM_STYLE));
        assertEquals("hbm", m.modid);
    }

    @Test
    void missingDirectoriesAreSkippedNotFatal(@TempDir Path repo) {
        List<Mods.ModInfo> got = Mods.list(repo, List.of(
                repo.resolve("does-not-exist"), repo.resolve("also-missing")));
        assertTrue(got.isEmpty());
    }

    @Test
    void junitCountsAreParsedOutOfGateOutput() {
        List<String> lines = List.of(
                "Test run finished after 1234 ms",
                "[        45 tests successful      ]",
                "[         0 tests failed          ]",
                "[         9 containers found      ]",
                "PROBE-OK blocks=1244/1244 items=3829/3829");
        var m = ConsoleServer.parseJunit(lines);
        assertEquals(45, m.get("tests-successful"));
        assertEquals(0, m.get("tests-failed"));
        assertEquals(9, m.get("containers-found"));
        assertTrue(String.valueOf(m.get("probe")).startsWith("PROBE-OK"));
    }
}
