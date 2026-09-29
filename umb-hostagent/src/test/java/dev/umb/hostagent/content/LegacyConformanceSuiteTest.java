package dev.umb.hostagent.content;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless cross-mod conformance suite for the NEW DIRECTION.  It is intentionally tagged and is
 * are expected to turn green.  The live shape phase boots one isolated 1.7.10 universe per jar;
 * the persistence phase loads this test's probe into that same child loader and calls the real
 * LegacyBridgeImpl against a real mod TileEntity.
 */
@Tag("conformance")
class LegacyConformanceSuiteTest {
    private static final double EPS = 1.0e-4;
    private static final List<ModCase> MODS = List.of(
            new ModCase("HBM", "hbm", "research/mods-hbm/HBM-NTM-1.0.27_X5771.jar",
                    "research/out/legacy/hbm-snapshot.json", "hbm:tile.barrel_steel", "hbm:tile.barrel_steel"),
            new ModCase("IronChest", "IronChest", "research/mods-second/ironchest-1.7.10-6.0.62.742-universal.jar",
                    "research/out/legacy/IronChest-snapshot.json", "IronChest:BlockIronChest", "IronChest:BlockIronChest"),
            new ModCase("MCHeli", "mcheli", "research/mods-third/mcheli-1.7.10-1.0.3-repackaged.jar",
                    "research/out/legacy/mcheli-snapshot.json", "mcheli:drafting_table", "mcheli:drafting_table"),
            new ModCase("Chisel", "chisel", "research/mods-third/Chisel-1.7.10-1.5.7.jar",
                    "research/out/legacy/chisel-snapshot.json", null, null),
            new ModCase("Railcraft", "Railcraft", "research/mods-third/Railcraft_1.7.10-9.2.2.0.jar",
                    "research/out/legacy/Railcraft-snapshot.json", null, null));

    private static Path repo;
    private static final Map<String, JsonObject> SHAPES = new LinkedHashMap<>();

    @BeforeAll
    static void liveCorpus() throws Exception {
        repo = repoRoot();
        for (ModCase mod : MODS) {
            Assumptions.assumeTrue(Files.isRegularFile(repo.resolve(mod.jar)),
                    "real legacy mod corpus not present in this checkout: " + mod.jar);
            Path json = runShapeProbe(mod);
            try (Reader reader = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
                SHAPES.put(mod.name, JsonParser.parseReader(reader).getAsJsonObject());
            }
        }
    }

    @Test
    void p2_liveCollisionAndSelectionMustMatchTheHostShapeForEveryRealMod() {
        List<String> failures = new ArrayList<>();
        for (ModCase mod : MODS) {
            int mismatches = 0;
            String first = null;
            for (JsonObject block : blocksForNamespace(SHAPES.get(mod.name), mod.namespace)) {
                for (JsonElement element : array(block, "metaGroups")) {
                    JsonObject group = element.getAsJsonObject();
                    double[] hostCollision = hostCollisionBounds(group);
                    double[] collision = arrayOf(group, "collisionAabb");
                    double[] selection = arrayOf(group, "selectionAabb");
                    // Collision and outline are separate legacy contracts.  Several real blocks
                    // deliberately return an outline larger than their physical collision box
                    // (grounded by the live func_149633_g callback); comparing both to the
                    // collision fallback produced the historical false 73-group failure.
                    double[] hostSelection = hostSelectionBounds(group);
                    double[] recordedCollision = collisionBoxesBounds(group, collision);
                    boolean collisionMismatch = recordedCollision != null
                            && (hostCollision == null || !same(hostCollision, recordedCollision));
                    boolean selectionMismatch = selection != null
                            && (hostSelection == null || !same(hostSelection, selection));
                    if (!collisionMismatch && !selectionMismatch) continue;
                    mismatches++;
                    if (first == null) {
                        first = block.get("id").getAsString() + " class="
                                + block.get("className").getAsString() + " meta="
                                + group.getAsJsonArray("metas").get(0).getAsInt()
                                + " hostCollision=" + Arrays.toString(hostCollision)
                                + " hostSelection=" + Arrays.toString(hostSelection)
                                + " collision=" + Arrays.toString(recordedCollision)
                                + " selection=" + Arrays.toString(selection);
                    }
                }
            }
            if (mismatches != 0) {
                failures.add(mod.name + ": " + mismatches + " mismatching meta-groups; first " + first);
            }
        }
        assertTrue(failures.isEmpty(), "P2 assertion: host collision and selection shapes must equal the "
                + "live legacy bounds per state; failures=" + failures);
    }

    @Test
    void p2_legacyOpacityAndNormalRenderMustBothDriveHostOcclusion() {
        List<String> failures = new ArrayList<>();
        for (ModCase mod : MODS) {
            int mismatches = 0;
            String first = null;
            for (JsonObject block : blocksForNamespace(SHAPES.get(mod.name), mod.namespace)) {
                for (JsonElement element : array(block, "metaGroups")) {
                    JsonObject group = element.getAsJsonObject();
                    boolean legacyOpaque = bool(group, "isOpaqueCube");
                    boolean legacyNormal = bool(group, "renderAsNormalBlock");
                    boolean hostOccludes = legacyOpaque; // Registrar's current projection.
                    boolean expected = legacyOpaque && legacyNormal;
                    if (hostOccludes == expected) continue;
                    mismatches++;
                    if (first == null) {
                        first = block.get("id").getAsString() + " class="
                                + block.get("className").getAsString() + " meta="
                                + group.getAsJsonArray("metas").get(0).getAsInt()
                                + " hostOccludes=" + hostOccludes + " expected=" + expected;
                    }
                }
            }
            if (mismatches != 0) {
                failures.add(mod.name + ": " + mismatches + " mismatching meta-groups; first " + first);
            }
        }
        assertTrue(failures.isEmpty(), "P2 assertion: isOpaqueCube && renderAsNormalBlock must equal "
                + "host occlusion; failures=" + failures);
    }

    @Test
    void p3_realTileEntitiesSurviveNeighborDispatchAndVariantSaveLoadAcrossThreeMods() throws Exception {
        List<String> failures = new ArrayList<>();
        for (ModCase mod : MODS.subList(0, 3)) {
            ProcessResult result = runPersistenceProbe(mod);
            if (result.exitCode != 0 || !result.report.contains("variantSwapNbtEqual=true")) {
                failures.add(mod.name + ": exit=" + result.exitCode + " report=" + result.report);
            }
        }
        assertTrue(failures.isEmpty(), "P3 assertion: real neighbor dispatch plus TE state must survive "
                + "save/load and a metadata variant swap; failures=" + failures);
    }

    @Test
    void p1_installedErasMustNotBootBeforeTheirRealModIsUsed() throws Exception {
        CountingBridge defaultBridge = new CountingBridge();
        CountingBridge era1122 = new CountingBridge();
        CountingBridge era1165 = new CountingBridge();
        BridgeRouter router = new BridgeRouter(defaultBridge.proxy);
        assertTrue(Files.isRegularFile(repo.resolve("research/mods-1122/ironchest-1.12.2-7.0.72.847.jar")),
                "real 1.12.2 IronChest jar is required for this lane proof");
        assertTrue(Files.isRegularFile(repo.resolve("research/out/legacy-1165/ironchest-1.16.5-11.2.21.jar")),
                "real 1.16.5 IronChest jar is required for this lane proof");
        router.registerEra("1.12.2", java.util.Set.of("ironchest1122"), () -> era1122.proxy);
        router.registerEra("1.16.5", java.util.Set.of("ironchest1165"), () -> era1165.proxy);
        router.boot((dev.umb.bridge.api.HostWorld) Proxy.newProxyInstance(
                LegacyConformanceSuiteTest.class.getClassLoader(),
                new Class<?>[]{dev.umb.bridge.api.HostWorld.class},
                (proxy, method, args) -> defaultValue(method.getReturnType())));
        assertEquals(0, era1122.boots.get(), "P1 assertion: 1.12.2 loader was touched before use");
        assertEquals(0, era1165.boots.get(), "P1 assertion: 1.16.5 loader was touched before use");
        assertEquals("lazy 0ms 0MB", router.statusLine("1.12.2"));
        assertEquals("lazy 0ms 0MB", router.statusLine("1.16.5"));
        assertEquals("default-unbooted-1.12.2", router.routeForTest("ironchest1122:iron_chest"));
        assertEquals("default-unbooted-1.16.5", router.routeForTest("ironchest1165:iron_chest"));
    }

    private static double[] hostCollisionBounds(JsonObject group) {
        JsonArray boxes = group.getAsJsonArray("collisionBoxes");
        if (boxes != null && !boxes.isEmpty()) {
            double[] out = arrayOf(boxes.get(0).getAsJsonArray());
            for (int i = 1; i < boxes.size(); i++) union(out, arrayOf(boxes.get(i).getAsJsonArray()));
            return out;
        }
        if (bool(group, "isFullCube")) return new double[]{0, 0, 0, 1, 1, 1};
        double[] collision = arrayOf(group, "collisionAabb");
        return collision != null ? collision : arrayOf(group, "rawBounds");
    }

    private static double[] collisionBoxesBounds(JsonObject group, double[] collisionAabb) {
        JsonArray boxes = group.getAsJsonArray("collisionBoxes");
        if (boxes == null || boxes.isEmpty()) return collisionAabb != null
                ? collisionAabb : arrayOf(group, "rawBounds");
        double[] out = arrayOf(boxes.get(0).getAsJsonArray());
        for (int i = 1; i < boxes.size(); i++) union(out, arrayOf(boxes.get(i).getAsJsonArray()));
        return out;
    }

    /**
     * The live outline path is func_149719_a followed by func_149633_g.  The extractor records
     * that exact answer as selectionAabb; rawBounds is only the honest fallback when the legacy
     * selection callback returned null.  It must not be compared with collision geometry.
     */
    private static double[] hostSelectionBounds(JsonObject group) {
        double[] selection = arrayOf(group, "selectionAabb");
        return selection != null ? selection : arrayOf(group, "rawBounds");
    }

    private static void union(double[] a, double[] b) {
        for (int i = 0; i < 3; i++) {
            a[i] = Math.min(a[i], b[i]);
            a[i + 3] = Math.max(a[i + 3], b[i + 3]);
        }
    }

    private static boolean same(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (Math.abs(a[i] - b[i]) > EPS) return false;
        return true;
    }

    private static List<JsonObject> blocksForNamespace(JsonObject root, String namespace) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : array(root, "blocks")) {
            JsonObject block = e.getAsJsonObject();
            String id = block.get("id").getAsString();
            if (id.startsWith(namespace + ":") || id.startsWith(namespace.toLowerCase() + ":")) out.add(block);
        }
        return out;
    }

    private static JsonArray array(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || !value.isJsonArray() ? new JsonArray() : value.getAsJsonArray();
    }

    private static double[] arrayOf(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() || !value.isJsonArray() ? null : arrayOf(value.getAsJsonArray());
    }

    private static double[] arrayOf(JsonArray value) {
        double[] out = new double[value.size()];
        for (int i = 0; i < value.size(); i++) out[i] = value.get(i).getAsDouble();
        return out;
    }

    private static boolean bool(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && !value.isJsonNull() && value.getAsBoolean();
    }

    private static Path runShapeProbe(ModCase mod) throws Exception {
        Path scratch = Path.of(System.getProperty("java.io.tmpdir"), "umb-conformance").toAbsolutePath();
        Files.createDirectories(scratch);
        Path run = scratch.resolve(mod.name.toLowerCase() + "-" + UUID.randomUUID());
        Path game = run.resolve("game");
        Path out = run.resolve("out");
        Path mods = game.resolve("mods");
        Files.createDirectories(mods);
        Files.createDirectories(game.resolve("config"));
        Files.copy(repo.resolve(mod.jar), mods.resolve(Path.of(mod.jar).getFileName()), StandardCopyOption.REPLACE_EXISTING);
        Files.createDirectories(out);
        Path json = out.resolve("block-shapes.json");
        List<String> command = new ArrayList<>();
        command.add(java25().toString());
        command.add("-Xmx1G");
        command.add("-Djava.awt.headless=true");
        command.add("--sun-misc-unsafe-memory-access=allow");
        for (String pkg : List.of("java.lang", "java.lang.reflect", "java.util", "java.util.concurrent",
                "java.net", "java.nio", "java.io", "java.text")) {
            command.add("--add-opens"); command.add("java.base/" + pkg + "=ALL-UNNAMED");
        }
        command.add("-Dumb.repo=" + repo);
        command.add("-Dumb.legacy.out=" + out);
        command.add("-Dumb.legacy.gameDir=" + game);
        command.add("-Dumb.legacy.blockShapesJson=" + json);
        command.add("-Dumb.legacy.timeoutSeconds=180");
        command.add("-cp"); command.add(legacyHostClasspath());
        command.add("dev.umb.legacy.boot.BlockShapeMain");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(run.resolve("block-shapes.log").toFile()).start();
        int exit = process.waitFor();
        assertEquals(0, exit, mod.name + " live BlockShapeMain failed; see " + run.resolve("block-shapes.log"));
        assertTrue(Files.isRegularFile(json), mod.name + " did not produce a live shape report");
        return json;
    }

    private static ProcessResult runPersistenceProbe(ModCase mod) throws Exception {
        Path scratch = Path.of(System.getProperty("java.io.tmpdir"), "umb-conformance").toAbsolutePath();
        Path run = scratch.resolve(mod.name.toLowerCase() + "-p3-" + UUID.randomUUID());
        Path game = run.resolve("game");
        Files.createDirectories(game.resolve("mods"));
        Files.createDirectories(game.resolve("config"));
        Files.copy(repo.resolve(mod.jar), game.resolve("mods").resolve(Path.of(mod.jar).getFileName()), StandardCopyOption.REPLACE_EXISTING);
        List<String> command = new ArrayList<>();
        command.add(java25().toString());
        command.add("-Djava.awt.headless=true");
        command.add("--sun-misc-unsafe-memory-access=allow");
        for (String pkg : List.of("java.lang", "java.lang.reflect", "java.util", "java.util.concurrent",
                "java.net", "java.nio", "java.io", "java.text")) {
            command.add("--add-opens"); command.add("java.base/" + pkg + "=ALL-UNNAMED");
        }
        command.add("-Dumb.repo=" + repo);
        command.add("-Dumb.legacy.out=" + run);
        command.add("-Dumb.legacy.gameDir=" + game);
        // The real mod is staged in game/mods above.  Only the test probe is an extra loader URL;
        // passing the mod twice makes Forge report DuplicateModsFoundException.
        command.add("-Dumb.legacy.modJars=" + testClasses());
        command.add("-Dumb.legacy.probe=dev.umb.hostagent.content.LegacyConformanceProbe");
        command.add("-Dumb.conformance.tileId=" + mod.tileId);
        command.add("-Dumb.conformance.blockId=" + mod.blockId);
        command.add("-Dumb.conformance.neighborId=minecraft:air");
        command.add("-cp"); command.add(legacyHostClasspath() + File.pathSeparator + testClasses());
        command.add("dev.umb.legacy.boot.M1ProbeMain");
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(run.resolve("p3.log").toFile()).start();
        int exit = process.waitFor();
        Path reportPath = run.resolve("m1-probe.txt");
        String report = Files.isRegularFile(reportPath) ? Files.readString(reportPath) : "<no report>";
        return new ProcessResult(exit, report);
    }

    private static String legacyHostClasspath() throws IOException {
        Path build = repo.resolve("build/legacy");
        Path libs = repo.resolve("research/visual/mc1710-native/libraries");
        Path jopt = Files.walk(libs).filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().startsWith("jopt-simple-") && p.toString().endsWith(".jar"))
                .findFirst().orElseThrow();
        Path log4jApi = Files.walk(libs).filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().startsWith("log4j-api-") && p.toString().endsWith(".jar"))
                .findFirst().orElseThrow();
        Path log4jCore = Files.walk(libs).filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().startsWith("log4j-core-") && p.toString().endsWith(".jar"))
                .findFirst().orElseThrow();
        Path lw = libs.resolve("net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar");
        return String.join(File.pathSeparator, build.resolve("umb-legacy-boot.jar").toString(),
                build.resolve("umb-legacy-api.jar").toString(), build.resolve("umb-bridge-api.jar").toString(),
                lw.toString(), jopt.toString(), log4jApi.toString(), log4jCore.toString());
    }

    private static Path testClasses() {
        return repo.resolve("build/hostagent/test-classes");
    }

    private static Path java25() {
        return repo.resolve("tools/jdk-25.0.4.1+1/bin/java.exe");
    }

    private static Path repoRoot() {
        String configured = System.getProperty("umb.repo");
        if (configured != null) return Path.of(configured).toAbsolutePath();
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve("umb-hostagent")) && Files.isDirectory(current.resolve("research"))) return current;
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate repository root");
    }

    private record ModCase(String name, String namespace, String jar, String snapshot,
                           String tileId, String blockId) {}
    private record ProcessResult(int exitCode, String report) {}

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0.0F;
        if (type == double.class) return 0.0D;
        return null;
    }

    /** Dynamic proxy keeps this conformance source independent of the growing fake-test surface. */
    private static final class CountingBridge {
        final AtomicInteger boots = new AtomicInteger();
        final java.util.concurrent.atomic.AtomicBoolean booted = new java.util.concurrent.atomic.AtomicBoolean();
        final dev.umb.bridge.api.LegacyBridge proxy = (dev.umb.bridge.api.LegacyBridge) Proxy.newProxyInstance(
                LegacyConformanceSuiteTest.class.getClassLoader(),
                new Class<?>[]{dev.umb.bridge.api.LegacyBridge.class},
                (self, method, args) -> {
                    if (method.getName().equals("toString")) return "CountingBridge";
                    if (method.getName().equals("hashCode")) return System.identityHashCode(self);
                    if (method.getName().equals("equals")) return self == args[0];
                    if (method.getName().equals("boot")) {
                        boots.incrementAndGet();
                        booted.set(true);
                        return null;
                    }
                    if (method.getName().equals("isBooted")) return booted.get();
                    if (method.getName().equals("shutdown")) {
                        booted.set(false);
                        return null;
                    }
                    return defaultValue(method.getReturnType());
                });
    }
}
