package dev.umb.pipeline.bridge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bridge v2 entrypoint discovery. Pins the two real metadata forms against synthetic
 * jars: fabric.mod.json {@code entrypoints} (string and {@code {"value": ...}} forms)
 * and the Forge {@code @Mod} class annotation (descriptor verified on
 * fixtures/fixture-hello-forge). A jar with neither must report an honest empty scan:
 * sourcesSeen empty, sourcesLookedFor naming exactly what was probed, and no guessed
 * class name anywhere (D4).
 */
class EntrypointScannerTest {

    @TempDir
    Path tmp;

    private Path jar(Map<String, byte[]> entries) throws IOException {
        Path p = tmp.resolve("test-" + Math.abs(entries.hashCode()) + ".jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(p))) {
            for (var e : entries.entrySet()) {
                out.putNextEntry(new JarEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return p;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /** A plain class carrying no annotations. */
    private static byte[] plainClass(String internalName) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** A class carrying the given class annotation (visible, as @Mod is RUNTIME-retained). */
    private static byte[] annotatedClass(String internalName, String annotationDescriptor) {
        var cw = new ClassWriter(0);
        cw.visit(52, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        cw.visitAnnotation(annotationDescriptor, true);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<String> classes(EntrypointScan scan) {
        return scan.entrypoints().stream().map(EntrypointScan.Entrypoint::className).toList();
    }

    // ------------------------------------------------------------------ fabric

    @Test
    void fabricEntrypointsReportedInEveryCategory() throws IOException {
        Path mod = jar(Map.of(
                "fabric.mod.json", utf8("{\"entrypoints\":{"
                        + "\"main\":[\"q.Main\"],"
                        + "\"client\":[\"q.ClientA\",{\"value\":\"q.ClientB\"}],"
                        + "\"server\":[\"q.Server\"]}}"),
                "q/Main.class", plainClass("q/Main")));
        EntrypointScan scan = EntrypointScanner.scan(mod);

        assertTrue(scan.hasEntrypoints());
        // Sources across categories, sorted by (source, className): fabric:client <
        // fabric:main < fabric:server, and q.ClientA < q.ClientB inside client.
        assertEquals(List.of(
                "fabric:client", "q.ClientA",
                "fabric:client", "q.ClientB",
                "fabric:main", "q.Main",
                "fabric:server", "q.Server"),
                scan.entrypoints().stream().flatMap(e -> List.of(e.source(), e.className()).stream()).toList());
        assertEquals(List.of("fabric.mod.json"), scan.sourcesSeen());
        assertEquals(List.of("fabric.mod.json", "forge:@Mod"), scan.sourcesLookedFor());
    }

    @Test
    void fabricBareJsonFormsToleratedButUnknownShapesIgnored() throws IOException {
        // A bare-string entrypoint value (not an array) and a malformed object item are
        // tolerated without crashing; only the well-formed names are recorded.
        Path mod = jar(Map.of(
                "fabric.mod.json", utf8("{\"entrypoints\":{\"main\":\"q.BareString\","
                        + "\"client\":[{\"value\":\"q.Good\"},{\"nope\":true}]}}"),
                "q/Anything.class", plainClass("q/Anything")));
        EntrypointScan scan = EntrypointScanner.scan(mod);

        // Entries sort by (source, className): fabric:client (q.Good) before fabric:main.
        assertEquals(List.of("q.Good", "q.BareString"), classes(scan));
        assertEquals(List.of("fabric:client", "fabric:main"),
                scan.entrypoints().stream().map(EntrypointScan.Entrypoint::source).toList());
        assertEquals(List.of("fabric.mod.json"), scan.sourcesSeen());
    }

    @Test
    void fabricWithoutEntrypointsStillCountsAsSeen() throws IOException {
        Path mod = jar(Map.of(
                "fabric.mod.json", utf8("{\"schemaVersion\":1,\"name\":\"no ent\"}"),
                "q/Plain.class", plainClass("q/Plain")));
        EntrypointScan scan = EntrypointScanner.scan(mod);

        assertFalse(scan.hasEntrypoints());
        assertEquals(List.of("fabric.mod.json"), scan.sourcesSeen(), "read but declaring nothing is still seen");
        assertEquals(List.of("fabric.mod.json", "forge:@Mod"), scan.sourcesLookedFor());
    }

    // ------------------------------------------------------------------ forge @Mod

    @Test
    void forgeModAnnotationIsReported() throws IOException {
        Path mod = jar(Map.of(
                "net/umb/fixtures/hello/HelloForgeMod.class",
                annotatedClass("net/umb/fixtures/hello/HelloForgeMod",
                        "Lnet/minecraftforge/fml/common/Mod;")));
        EntrypointScan scan = EntrypointScanner.scan(mod);

        assertEquals(List.of("net.umb.fixtures.hello.HelloForgeMod"), classes(scan));
        assertEquals(List.of("forge:@Mod"), scan.entrypoints().stream()
                .map(EntrypointScan.Entrypoint::source).toList());
        assertEquals(List.of("forge:@Mod"), scan.sourcesSeen());
    }

    @Test
    void forgeCpwEraVariantIsReported() throws IOException {
        Path mod = jar(Map.of(
                "cpw/mods/example/OldMod.class",
                annotatedClass("cpw/mods/example/OldMod", "Lcpw/mods/fml/common/Mod;")));
        EntrypointScan scan = EntrypointScanner.scan(mod);

        assertEquals(List.of("cpw.mods.example.OldMod"), classes(scan));
        assertEquals(List.of("forge:@Mod"), scan.sourcesSeen());
    }

    @Test
    void unrelatedAnnotationsAreNotEntrypoints() throws IOException {
        Path mod = jar(Map.of(
                "q/Annotated.class",
                annotatedClass("q/Annotated", "Ljava/lang/Deprecated;")));
        EntrypointScan scan = EntrypointScanner.scan(mod);

        assertFalse(scan.hasEntrypoints());
        assertEquals(List.of(), scan.sourcesSeen());
    }

    // ------------------------------------------------------------------ neither present

    @Test
    void neitherFormYieldsHonestEmpty() throws IOException {
        Path mod = jar(Map.of(
                "q/Plain.class", plainClass("q/Plain")));
        EntrypointScan scan = EntrypointScanner.scan(mod);

        assertFalse(scan.hasEntrypoints());
        assertEquals(List.of(), scan.entrypoints());
        assertEquals(List.of(), scan.sourcesSeen(),
                "nothing was found AND nothing claimed");
        assertEquals(List.of("fabric.mod.json", "forge:@Mod"), scan.sourcesLookedFor(),
                "what was looked for is always named");
    }

    // ------------------------------------------------------------------ robustness

    @Test
    void entryOrderDoesNotMatterForDeterminism() throws IOException {
        // Same entries, declared in the jar in the opposite order and with a reverse JSON
        // array; the reported order must be identical.
        Path modA = jar(new LinkedHashMap<>(Map.of(
                "fabric.mod.json", utf8("{\"entrypoints\":{\"main\":[\"q.Z\",\"q.A\"]}}"),
                "q/Plain.class", plainClass("q/Plain"))));
        Path modB = jar(new LinkedHashMap<>(Map.of(
                "q/Plain.class", plainClass("q/Plain"),
                "fabric.mod.json", utf8("{\"entrypoints\":{\"main\":[\"q.A\",\"q.Z\"]}}"))));

        assertEquals(List.of("q.A", "q.Z"), classes(EntrypointScanner.scan(modA)));
        assertEquals(classes(EntrypointScanner.scan(modA)), classes(EntrypointScanner.scan(modB)));
    }

    @Test
    void malformedFabricJsonIsNotClaimedSeen() throws IOException {
        Path mod = jar(Map.of(
                "fabric.mod.json", utf8("{ not json at all "),
                "q/Plain.class", plainClass("q/Plain")));
        EntrypointScan scan = EntrypointScanner.scan(mod);

        assertFalse(scan.hasEntrypoints());
        assertEquals(List.of(), scan.sourcesSeen(), "unparseable metadata cannot be a seen source");
        assertTrue(scan.sourcesLookedFor().contains("fabric.mod.json"));
    }
}