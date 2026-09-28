package dev.umb.legacy.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Runs {@code dev.umb.legacy.legacyside.RenderTickHudCaptureProbe} through the SAME
 * {@code dev.umb.legacy.boot.M1ProbeMain} subprocess harness {@link M1ProbeTest} uses (see that
 * class's javadoc for why this must be a separate JVM: FML's {@code Loader}/{@code GameData}
 * singletons are global and single-shot). No third-party mod jar is staged - the probe proves a
 * generic Forge event-bus mechanism, not anything mod-specific.
 */
class RenderTickHudCaptureProbeTest {

    @Test
    void renderOverlayCapturesGeometryDrawnFromRenderTickEventEnd() throws Exception {
        // Test-only override: lets a build point this ONE test at freshly rebuilt jars
        // without touching the shared -Dumb.repo that M1ProbeTest - sharing this JVM
        // in a full package run - resolves its own, unrelated jar/mod paths from.
        String repoOverride = System.getProperty("umb.legacy.testProbeRepo");
        Path repo = repoOverride != null ? Path.of(repoOverride) : repoRoot();
        Path build = repo.resolve("build/legacy");
        Path libsDir = repo.resolve("research/visual/mc1710-native/libraries");
        Path outDir = repo.resolve("research/out/legacy/legacy-boot");
        Path mod = repo.resolve("umb-legacy");
        Files.createDirectories(outDir.resolve("mods"));
        Files.createDirectories(outDir.resolve("config"));

        String java25 = javaExecutable().toString();
        String bootJar = build.resolve("umb-legacy-boot.jar").toString();
        String apiJar = build.resolve("umb-legacy-api.jar").toString();
        String bridgeApiJar = build.resolve("umb-bridge-api.jar").toString();
        String lsJar = build.resolve("umb-legacy-legacyside.jar").toString();
        String forgeSrg = build.resolve("forge-1.7.10-10.13.4.1614-srg.jar").toString();
        String runtimeJar = build.resolve("1.7.10-forge-srg-runtime-fields.jar").toString();
        String lwJar = libsDir.resolve("net/minecraft/launchwrapper/1.12/launchwrapper-1.12.jar").toString();
        String joptJar = findOne(libsDir, "jopt-simple-");
        String log4jApi = findOne(libsDir, "log4j-api-");
        String log4jCore = findOne(libsDir, "log4j-core-");
        String log4jCfg = mod.resolve("resources/log4j2-legacy.xml").toString();

        for (String p : new String[] {java25, bootJar, apiJar, bridgeApiJar, lsJar, forgeSrg, runtimeJar,
                lwJar, joptJar, log4jApi, log4jCore, log4jCfg}) {
            assertTrue(new File(p).exists(), "missing: " + p + " - run tools/build-legacy.ps1 first");
        }

        String hostCp = String.join(File.pathSeparator, bootJar, apiJar, bridgeApiJar, lwJar, joptJar,
                log4jApi, log4jCore);

        List<String> cmd = new ArrayList<String>();
        cmd.add(java25);
        cmd.add("-Xmx1G");
        cmd.add("-Djava.awt.headless=true");
        cmd.add("--sun-misc-unsafe-memory-access=allow");
        for (String pkg : new String[] {"java.lang", "java.lang.reflect", "java.util",
                "java.util.concurrent", "java.net", "java.nio", "java.io", "java.text"}) {
            cmd.add("--add-opens");
            cmd.add("java.base/" + pkg + "=ALL-UNNAMED");
        }
        cmd.add("-XX:-OmitStackTraceInFastThrow");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Duser.language=en");
        cmd.add("-Duser.country=US");
        cmd.add("-Dlog4j.configurationFile=" + log4jCfg);
        cmd.add("-Dlog4j2.disable.jmx=true");
        cmd.add("-Dfml.queryResult=confirm");
        cmd.add("-Dfml.doNotBackup=true");
        cmd.add("-Dfml.ignoreInvalidMinecraftCertificates=true");
        cmd.add("-Dfml.ignorePatchDiscrepancies=true");
        cmd.add("-Dumb.repo=" + repo);
        cmd.add("-Dumb.legacy.out=" + outDir);
        cmd.add("-Dumb.legacy.forgeJar=" + forgeSrg);
        cmd.add("-Dumb.legacy.runtimeJar=" + runtimeJar);
        cmd.add("-Dumb.legacy.legacysideJar=" + lsJar);
        cmd.add("-Dumb.legacy.timeoutSeconds=90");
        cmd.add("-Dumb.legacy.probe=dev.umb.legacy.legacyside.RenderTickHudCaptureProbe");
        cmd.add("-cp");
        cmd.add(hostCp);
        cmd.add("dev.umb.legacy.boot.M1ProbeMain");

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectOutput(outDir.resolve("hud-probe.log").toFile());
        pb.redirectError(outDir.resolve("hud-probe.err.log").toFile());
        Process proc = pb.start();
        boolean finished = proc.waitFor(120, TimeUnit.SECONDS);
        if (!finished) {
            proc.destroyForcibly();
        }
        assertTrue(finished, "RenderTickHudCaptureProbe did not exit within 120s");

        String report = Files.readString(outDir.resolve("m1-probe.txt"));
        System.out.println("[RenderTickHudCaptureProbeTest] m1-probe.txt:\n" + report);

        assertEquals(0, proc.exitValue(), "RenderTickHudCaptureProbe exited non-zero:\n" + report);
        assertTrue(report.startsWith("HUD-OK"), "expected HUD-OK, got:\n" + report);
        assertTrue(report.contains("sawStart=true"), report);
        assertTrue(report.contains("sawEnd=true"), report);
        assertTrue(report.contains("markerFound=true"), report);
    }

    private static String findOne(Path dir, String prefix) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix)
                            && p.getFileName().toString().endsWith(".jar"))
                    .findFirst()
                    .map(Path::toString)
                    .orElseThrow(() -> new IllegalStateException("no " + prefix + "*.jar under " + dir));
        }
    }

    private static Path javaExecutable() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        Path javaHome = Path.of(System.getProperty("java.home"));
        Path direct = javaHome.resolve("bin").resolve(executable);
        if (Files.isRegularFile(direct) && Files.isExecutable(direct)) {
            return direct;
        }
        Path parentJdk = javaHome.getParent();
        if (parentJdk != null) {
            Path parent = parentJdk.resolve("bin").resolve(executable);
            if (Files.isRegularFile(parent) && Files.isExecutable(parent)) {
                return parent;
            }
        }
        throw new IllegalStateException("cannot locate Java executable under java.home=" + javaHome);
    }

    private static Path repoRoot() {
        String p = System.getProperty("umb.repo");
        if (p != null) {
            return Path.of(p);
        }
        Path cur = Path.of("").toAbsolutePath();
        while (cur != null) {
            if (Files.isDirectory(cur.resolve("umb-legacy")) && Files.isDirectory(cur.resolve("research"))) {
                return cur;
            }
            cur = cur.getParent();
        }
        throw new IllegalStateException("cannot locate repo root; pass -Dumb.repo=<path>");
    }
}
