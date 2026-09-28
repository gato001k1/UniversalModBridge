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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;

/**
 * Legacy compatibility behavior.
 */
public final class TickCoverageMain {

    private static final String PROBE = "dev.umb.legacy.legacyside.TickCoverageProbe";

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
        File assetsDir = new File(System.getProperty("umb.legacy.assetsDir",
                new File(repo, "research/visual/mc1710-native/assets").getAbsolutePath()));
        long timeoutSeconds = Long.parseLong(System.getProperty("umb.legacy.timeoutSeconds", "180"));
        File jsonOut = file("umb.legacy.tickCoverageJson",
                new File(repo, "research/out/legacy/g2-tick-coverage.json"));

        File modsDir = new File(gameDir, "mods");
        Files.createDirectories(modsDir.toPath());
        Files.createDirectories(new File(gameDir, "config").toPath());

        List<File> cp = LegacyClasspath.forBoot(classpathFile, runtimeJar, forgeJar,
                Collections.<File>emptyList(), Arrays.asList(legacysideJar, apiJar(repo), bridgeApiJar(repo)));
        URL[] urls = LegacyClasspath.toUrls(cp);

        out.println("[tick-coverage] repo    = " + repo);
        out.println("[tick-coverage] gameDir = " + gameDir);
        out.println("[tick-coverage] sources = " + cp.size() + " jars");

        LegacyLoader loader = new LegacyLoader(urls, TickCoverageMain.class.getClassLoader());

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

        Run run = new Run(loader);
        Thread t = new Thread(run, "Server thread");
        t.setContextClassLoader(loader);
        t.setDaemon(false);
        t.start();
        t.join(timeoutSeconds * 1000L);

        if (t.isAlive()) {
            out.println("[tick-coverage] TIMEOUT after " + timeoutSeconds + "s");
            write(new File(outDir, "tick-coverage.txt"), "TICK-COVERAGE-FAIL: timeout after " + timeoutSeconds + "s\n");
            out.flush();
            Runtime.getRuntime().halt(3);
        }

        if (run.report == null) {
            String failMsg = "TICK-COVERAGE-FAIL: " + run.failure;
            write(new File(outDir, "tick-coverage.txt"), failMsg);
            out.println(failMsg);
            out.flush();
            Runtime.getRuntime().halt(1);
            return;
        }

        Summary summary = parseAndWriteJson(run.report, jsonOut);
        String summaryLine = "TICK-COVERAGE ok=" + summary.ok + "/" + summary.total
                + " threw=" + summary.threw + " stubDependent=" + summary.stubDependent
                + " stubHits=" + summary.distinctStubMembers + " distinct members";
        write(new File(outDir, "tick-coverage.txt"), summaryLine + "\n");
        out.println("[tick-coverage] wrote " + jsonOut);
        out.println(summaryLine);
        out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static final class Summary {
        int total, ok, threw, stubDependent;
        int distinctStubMembers;
    }

    private static Summary parseAndWriteJson(String report, File jsonOut) throws Exception {
        String[] lines = report.split("\n", -1);
        Summary summary = new Summary();
        Set<String> allStubMembers = new LinkedHashSet<String>();

        StringBuilder json = new StringBuilder(1 << 16);
        json.append("{\n  \"classes\": [\n");
        boolean first = true;
        for (String line : lines) {
            if (line.length() == 0 || line.startsWith("CLASS-COUNT")) {
                continue;
            }
            String[] parts = line.split("\t", -1);
            if (parts.length < 4) {
                continue;
            }
            String className = parts[0];
            String rawOutcome = parts[1];
            String detail = parts[2];
            String stubHitsCsv = parts[3];
            List<String> stubHits = new ArrayList<String>();
            if (stubHitsCsv.length() > 0) {
                for (String s : stubHitsCsv.split(",")) {
                    if (s.length() > 0) {
                        stubHits.add(s);
                        allStubMembers.add(s);
                    }
                }
            }
            String outcome;
            if ("THREW".equals(rawOutcome)) {
                outcome = "THREW";
                summary.threw++;
            } else if (!stubHits.isEmpty()) {
                outcome = "STUB-DEPENDENT";
                summary.stubDependent++;
                summary.ok++;
            } else {
                outcome = "OK";
                summary.ok++;
            }
            summary.total++;

            if (!first) {
                json.append(",\n");
            }
            first = false;
            json.append("    {\"class\": ").append(Json.escape(className))
                    .append(", \"outcome\": ").append(Json.escape(outcome))
                    .append(", \"detail\": ").append(Json.escape(detail))
                    .append(", \"stubHits\": [");
            for (int i = 0; i < stubHits.size(); i++) {
                if (i > 0) {
                    json.append(", ");
                }
                json.append(Json.escape(stubHits.get(i)));
            }
            json.append("]}");
        }
        summary.distinctStubMembers = allStubMembers.size();
        json.append("\n  ],\n");
        json.append("  \"summary\": {\n")
                .append("    \"total\": ").append(summary.total).append(",\n")
                .append("    \"ok\": ").append(summary.ok).append(",\n")
                .append("    \"threw\": ").append(summary.threw).append(",\n")
                .append("    \"stubDependent\": ").append(summary.stubDependent).append(",\n")
                .append("    \"distinctStubMembers\": ").append(summary.distinctStubMembers).append("\n")
                .append("  },\n");
        json.append("  \"stubMembersHit\": [");
        boolean firstM = true;
        for (String m : allStubMembers) {
            if (!firstM) {
                json.append(", ");
            }
            firstM = false;
            json.append(Json.escape(m));
        }
        json.append("]\n}\n");

        Files.createDirectories(jsonOut.getParentFile().toPath());
        write(jsonOut, json.toString());
        return summary;
    }

    private static final class Run implements Runnable {
        private final LegacyLoader loader;
        String report;
        String failure;

        Run(LegacyLoader loader) {
            this.loader = loader;
        }

        @Override
        public void run() {
            try {
                Class<?> c = Class.forName(PROBE, true, loader);
                if (c.getClassLoader() != loader) {
                    throw new IllegalStateException("TickCoverageProbe leaked to " + c.getClassLoader());
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
            out.println("[tick-coverage] cannot write " + f + ": " + e);
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

    private TickCoverageMain() {
    }
}
