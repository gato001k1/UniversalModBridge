package dev.umb.legacy.boot;

import java.io.File;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.launchwrapper.Launch;

/**
 * DESIGN.md LANE A step 6: the host-side harness for {@code M1Probe} - same LegacyLoader/Launch/
 * "Server thread" setup {@code Bootstrap.java} uses for the G1 snapshot harness, but reflectively
 * drives {@code dev.umb.legacy.legacyside.M1Probe.run()} (which itself boots
 * {@code LegacyBridgeImpl} and exercises the Brick Furnace scenario) instead of
 * {@code LegacyDriver} + a registry snapshot.
 *
 * <p>Kept deliberately separate from {@code Bootstrap.java} rather than adding a mode flag to it:
 * the two harnesses drive completely different entry points ({@code LegacyUniverse} vs
 * {@code dev.umb.bridge.api.LegacyBridge}) and boot the FML {@code Loader} singleton exactly once
 * each - they must never run in the same JVM.</p>
 */
public final class M1ProbeMain {

    private static final String PROBE = "dev.umb.legacy.legacyside.M1Probe";

    private static PrintStream out;

    public static void main(String[] args) throws Exception {
        out = System.out;

        File repo = dir("umb.repo", null);
        File outDir = dir("umb.legacy.out", new File(repo, "research/out/legacy/legacy-boot"));
        Files.createDirectories(outDir.toPath());

        File runtimeJar = file("umb.legacy.runtimeJar", new File(repo, "build/legacy/1.7.10-forge-srg-runtime-fields.jar"));
        File forgeJar = file("umb.legacy.forgeJar", new File(repo, "build/legacy/forge-1.7.10-10.13.4.1614-srg.jar"));
        File classpathFile = file("umb.legacy.classpathFile",
                new File(repo, "research/visual/mc1710-native/classpath.txt"));
        File legacysideJar = file("umb.legacy.legacysideJar", new File(repo, "build/legacy/umb-legacy-legacyside.jar"));
        File gameDir = dir("umb.legacy.gameDir", outDir);
        String probeName = System.getProperty("umb.legacy.probe", PROBE);
        File assetsDir = new File(System.getProperty("umb.legacy.assetsDir",
                new File(repo, "research/visual/mc1710-native/assets").getAbsolutePath()));
        long timeoutSeconds = Long.parseLong(System.getProperty("umb.legacy.timeoutSeconds", "120"));

        File modsDir = new File(gameDir, "mods");
        Files.createDirectories(modsDir.toPath());
        Files.createDirectories(new File(gameDir, "config").toPath());

        List<File> probeMods = new ArrayList<File>();
        String probeModPaths = System.getProperty("umb.legacy.modJars", "");
        for (String path : probeModPaths.split(File.pathSeparator)) {
            if (!path.isEmpty()) probeMods.add(new File(path));
        }
        List<File> cp = LegacyClasspath.forBoot(classpathFile, runtimeJar, forgeJar,
                probeMods, Arrays.asList(legacysideJar, apiJar(repo), bridgeApiJar(repo)));
        URL[] urls = LegacyClasspath.toUrls(cp);

        out.println("[m1-probe] repo    = " + repo);
        out.println("[m1-probe] gameDir = " + gameDir);
        out.println("[m1-probe] sources = " + cp.size() + " jars");

        LegacyLoader loader = new LegacyLoader(urls, M1ProbeMain.class.getClassLoader());

        Launch.minecraftHome = gameDir;
        Launch.assetsDir = assetsDir;
        Launch.classLoader = loader;
        Map<String, Object> blackboard = new HashMap<String, Object>();
        Launch.blackboard = blackboard;
        Map<String, String> launchArgs = new LinkedHashMap<String, String>();
        launchArgs.put("--version", "1.7.10-Forge10.13.4.1614-1.7.10");
        launchArgs.put("--gameDir", gameDir.getAbsolutePath());
        launchArgs.put("--assetsDir", assetsDir.getAbsolutePath());
        blackboard.put("launchArgs", launchArgs);
        blackboard.put("fml.deobfuscatedEnvironment", Boolean.TRUE);
        blackboard.put("Tweaks", new ArrayList<Object>());
        blackboard.put("TweakClasses", new ArrayList<String>());
        blackboard.put("modList", new HashMap<String, Map<String, String>>());
        blackboard.put("coremodList", new ArrayList<Object>());

        Run run = new Run(loader, probeName);
        Thread t = new Thread(run, "Server thread");
        t.setContextClassLoader(loader);
        t.setDaemon(false);
        t.start();
        t.join(timeoutSeconds * 1000L);

        if (t.isAlive()) {
            out.println("[m1-probe] TIMEOUT after " + timeoutSeconds + "s");
            write(new File(outDir, "m1-probe.txt"), "M1-FAIL: timeout after " + timeoutSeconds + "s\n");
            out.flush();
            Runtime.getRuntime().halt(3);
        }

        String report = run.report != null ? run.report : "M1-FAIL: " + run.failure;
        write(new File(outDir, "m1-probe.txt"), report);
        out.println("[m1-probe] ---- report ----");
        out.println(report);
        out.flush();

        Runtime.getRuntime().halt(report.startsWith("M1-OK") || report.startsWith("CLIENT-OK")
                || report.startsWith("ENTITY-OK") || report.startsWith("PERSISTENCE-OK")
                || report.startsWith("HUD-OK") || report.startsWith("SEATINFO-OK") ? 0 : 1);
    }

    private static final class Run implements Runnable {
        private final LegacyLoader loader;
        private final String probeName;
        String report;
        String failure;

        Run(LegacyLoader loader, String probeName) {
            this.loader = loader;
            this.probeName = probeName;
        }

        @Override
        public void run() {
            try {
                Class<?> c = Class.forName(probeName, true, loader);
                if (c.getClassLoader() != loader) {
                    throw new IllegalStateException("M1Probe leaked to " + c.getClassLoader());
                }
                report = (String) c.getMethod("run").invoke(null);
            } catch (Throwable e) {
                Throwable cause = e;
                if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
                    cause = e.getCause();
                }
                StringWriter w = new StringWriter();
                cause.printStackTrace(new PrintWriter(w));
                failure = cause.getClass().getName() + ": " + cause.getMessage() + "\n" + w;
            }
        }
    }

    private static void write(File f, String content) {
        try {
            Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception e) {
            out.println("[m1-probe] cannot write " + f + ": " + e);
        }
    }

    private static File apiJar(File repo) {
        return new File(repo, "build/legacy/umb-legacy-api.jar");
    }

    private static File bridgeApiJar(File repo) {
        return new File(repo, "build/legacy/umb-bridge-api.jar");
    }

    private static File dir(String prop, File dflt) {
        String v = System.getProperty(prop);
        if (v != null) {
            return new File(v).getAbsoluteFile();
        }
        if (dflt == null) {
            throw new IllegalStateException("-D" + prop + " is required");
        }
        return dflt.getAbsoluteFile();
    }

    private static File file(String prop, File dflt) {
        return dir(prop, dflt);
    }

    private M1ProbeMain() {
    }
}
