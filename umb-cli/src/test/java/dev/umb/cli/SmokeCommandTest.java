package dev.umb.cli;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * umb smoke CLI surface (M3). Pins the exit contract: 0 iff every parseable class
 * loaded, 1 on linkage failure or usage error, --allow-failures downgrades the exit
 * but never suppresses the failed count, --json emits exactly one flat JSON object,
 * and D4 refuses a jar with zero parseable class entries (package-info only) even
 * though nothing "fails" to load. Routes through UmbCli.commandLine() so the picocli
 * err-writer and execution-exception wiring are exercised too.
 */
class SmokeCommandTest {

    @TempDir
    Path tmp;

    private static CommandLine cli(StringWriter out, StringWriter err) {
        CommandLine cmd = UmbCli.commandLine();
        cmd.setOut(new PrintWriter(out));
        cmd.setErr(new PrintWriter(err));
        return cmd;
    }

    private static Path jarWithManifest(Path dir, String name, Map<String, byte[]> entries)
            throws IOException {
        Path p = dir.resolve(name);
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            out.putNextEntry(new JarEntry("META-INF/MANIFEST.MF"));
            manifest.write(out);
            out.closeEntry();
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    /** A quietly valid class with no methods. */
    private static byte[] clean(String internalName) {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class whose go()V invokes owner.go()V on a null receiver (constant-pool ref). */
    private static byte[] calling(String internalName, String owner) {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC, internalName, null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(org.objectweb.asm.Opcodes.ACC_PUBLIC, "go", "()V", null, null);
        mv.visitInsn(org.objectweb.asm.Opcodes.ACONST_NULL);
        mv.visitMethodInsn(org.objectweb.asm.Opcodes.INVOKEVIRTUAL, owner, "go", "()V", false);
        mv.visitInsn(org.objectweb.asm.Opcodes.RETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A package-info shim: an interface, as javac emits for package annotations. */
    private static byte[] packageInfo(String internalName) {
        var cw = new org.objectweb.asm.ClassWriter(0);
        cw.visit(52, org.objectweb.asm.Opcodes.ACC_PUBLIC | org.objectweb.asm.Opcodes.ACC_INTERFACE
                | org.objectweb.asm.Opcodes.ACC_ABSTRACT | org.objectweb.asm.Opcodes.ACC_SYNTHETIC,
                internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void happyPathExitsZero() throws IOException {
        Path host = jarWithManifest(tmp, "host.jar", Map.of(
                "net/minecraft/A.class", clean("net/minecraft/A")));
        Path mod = jarWithManifest(tmp, "mod.jar", Map.of(
                "q/Clean.class", clean("q/Clean")));
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute("smoke", mod.toString(), "--host", host.toString());
        assertEquals(0, code);
        assertTrue(out.toString().contains("smoked " + mod), out.toString());
        assertTrue(out.toString().contains("loaded"), out.toString());
        assertTrue(out.toString().matches("(?s).*failed\\s*:\\s*0.*"), out.toString());
        assertEquals("", err.toString());
    }

    @Test
    void linkageFailureExitsOneAndNamesMissingSymbol() throws IOException {
        Path host = jarWithManifest(tmp, "host.jar", Map.of(
                "net/minecraft/A.class", clean("net/minecraft/A")));
        Path mod = jarWithManifest(tmp, "mod.jar", Map.of(
                "q/GhostRef.class", calling("q/GhostRef", "net/absent/Ghost")));
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute("smoke", mod.toString(), "--host", host.toString());
        assertEquals(1, code);
        assertTrue(out.toString().contains("net.absent.Ghost"), out.toString());
        assertTrue(out.toString().contains("! failed: q.GhostRef"), out.toString());
        assertEquals("", err.toString(), "a linkage result is a report, not a usage error");
    }

    @Test
    void allowFailuresExitsZeroButPrintsFailedCount() throws IOException {
        Path host = jarWithManifest(tmp, "host.jar", Map.of());
        Path mod = jarWithManifest(tmp, "mod.jar", Map.of(
                "q/GhostRef.class", calling("q/GhostRef", "net/absent/Ghost")));
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "smoke", mod.toString(), "--host", host.toString(), "--allow-failures");
        assertEquals(0, code);
        assertTrue(out.toString().matches("(?s).*failed\\s*:\\s*1.*"), out.toString());
        assertTrue(out.toString().contains("net.absent.Ghost"), out.toString());
    }

    @Test
    void jsonEmitsExactlyOneFlatObject() throws IOException {
        Path host = jarWithManifest(tmp, "host.jar", Map.of(
                "net/minecraft/A.class", clean("net/minecraft/A")));
        Path mod = jarWithManifest(tmp, "mod.jar", Map.of(
                "q/Clean.class", clean("q/Clean")));
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute(
                "smoke", mod.toString(), "--host", host.toString(), "--json");
        assertEquals(0, code);
        // Exactly one line, and it is one JSON object -- never a batch of lines.
        String[] lines = out.toString().strip().split("\n");
        assertEquals(1, lines.length, out.toString());
        String json = lines[0];
        assertTrue(json.startsWith("{") && json.endsWith("}"), json);
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(mod.toAbsolutePath().toString(), o.get("modJar").getAsString());
        assertEquals(host.toAbsolutePath().toString(), o.get("hostJar").getAsString());
        assertEquals(1, o.get("totalClasses").getAsInt());
        assertEquals(1, o.get("parseableEntries").getAsInt());
        assertEquals(1, o.get("loaded").getAsInt());
        assertEquals(0, o.get("failed").getAsInt());
        assertEquals(0, o.get("missingSymbols").getAsInt());
        assertEquals("", err.toString());
    }

    @Test
    void missingInputOrHostExitsOne() throws IOException {
        Path host = jarWithManifest(tmp, "host.jar", Map.of());
        Path mod = jarWithManifest(tmp, "mod.jar", Map.of(
                "q/Clean.class", clean("q/Clean")));
        Path ghost = tmp.resolve("no-such-mod.jar");

        var out = new StringWriter();
        var err = new StringWriter();
        int missingIn = cli(out, err).execute("smoke", ghost.toString(), "--host", host.toString());
        assertEquals(1, missingIn);
        assertTrue(err.toString().contains("error: no such file: " + ghost), err.toString());

        out = new StringWriter();
        err = new StringWriter();
        int missingHost = cli(out, err).execute("smoke", mod.toString(), "--host",
                tmp.resolve("no-such-host.jar").toString());
        assertEquals(1, missingHost);
        assertTrue(err.toString().contains("error: no such file:"), err.toString());
    }

    @Test
    void emptyJarRefuses() throws IOException {
        Path host = jarWithManifest(tmp, "host.jar", Map.of());
        // A jar of ONLY package-info: nothing to smoke, and it must not read as a pass.
        Path mod = jarWithManifest(tmp, "pkgonly.jar", Map.of(
                "q/package-info.class", packageInfo("q/package-info")));
        var out = new StringWriter();
        var err = new StringWriter();
        int code = cli(out, err).execute("smoke", mod.toString(), "--host", host.toString());
        assertEquals(1, code);
        assertTrue(err.toString().contains("0 class entries; nothing to smoke"), err.toString());
        assertFalse(out.toString().contains("smoked"), out.toString());
    }
}
