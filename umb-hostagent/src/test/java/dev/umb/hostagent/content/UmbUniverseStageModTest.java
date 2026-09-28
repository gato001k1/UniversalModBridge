package dev.umb.hostagent.content;

import static org.junit.jupiter.api.Assertions.*;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.Test;

class UmbUniverseStageModTest {
    @Test
    void plainModsExplodeAndRestageWhenSizeOrMtimeChanges() throws Exception {
        Path root = Files.createTempDirectory("umb-stage-");
        Path source = root.resolve("plain.jar");
        Path mods = root.resolve("mods");
        Files.createDirectories(mods);
        writeJar(source, null, null, "assets/example/a.txt", "one");

        java.io.File staged = UmbUniverse.stageMod(source.toFile(), mods.toFile());
        assertTrue(staged.isDirectory());
        assertEquals("one", Files.readString(staged.toPath().resolve("assets/example/a.txt")));
        FileTime old = Files.getLastModifiedTime(source);
        writeJar(source, null, null, "assets/example/a.txt", "two-and-more");
        Files.setLastModifiedTime(source, FileTime.fromMillis(old.toMillis() + 2000));
        java.io.File restaged = UmbUniverse.stageMod(source.toFile(), mods.toFile());
        assertEquals(staged, restaged);
        assertEquals("two-and-more", Files.readString(restaged.toPath().resolve("assets/example/a.txt")));
        assertFalse(mods.resolve("plain.jar").toFile().exists());
    }

    @Test
    void coremodAndTweakerJarsStayJars() throws Exception {
        Path root = Files.createTempDirectory("umb-stage-core-");
        Path mods = root.resolve("mods");
        Files.createDirectories(mods);
        Path core = root.resolve("core.jar");
        writeJar(core, "FMLCorePlugin", "x.Core", "x.txt", "x");
        java.io.File coreOut = UmbUniverse.stageMod(core.toFile(), mods.toFile());
        assertTrue(coreOut.isFile());
        assertTrue(coreOut.getName().equals("core.jar"));
        assertFalse(mods.resolve("core").toFile().exists());

        Path tweak = root.resolve("tweak.jar");
        writeJar(tweak, "TweakClass", "x.Tweak", "x.txt", "x");
        java.io.File tweakOut = UmbUniverse.stageMod(tweak.toFile(), mods.toFile());
        assertTrue(tweakOut.isFile());
        assertFalse(mods.resolve("tweak").toFile().exists());
    }

    private static void writeJar(Path path, String manifestKey, String manifestValue,
                                 String entry, String value) throws Exception {
        Manifest mf = new Manifest();
        mf.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (manifestKey != null) mf.getMainAttributes().putValue(manifestKey, manifestValue);
        try (OutputStream out = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(out, mf)) {
            jar.putNextEntry(new ZipEntry(entry));
            jar.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
