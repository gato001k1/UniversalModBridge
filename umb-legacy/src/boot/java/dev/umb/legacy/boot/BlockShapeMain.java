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

import dev.umb.legacy.api.BlockMetaShape;
import dev.umb.legacy.api.BlockShapeEntry;

/** Legacy compatibility behavior. */
public final class BlockShapeMain {

    private static final String PROBE = "dev.umb.legacy.legacyside.BlockShapeProbe";

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
        File jsonOut = file("umb.legacy.blockShapesJson",
                new File(repo, "research/out/legacy/block-shapes.json"));

        File modsDir = new File(gameDir, "mods");
        Files.createDirectories(modsDir.toPath());
        Files.createDirectories(new File(gameDir, "config").toPath());

        boolean deobfuscatedEnvironment = Boolean.parseBoolean(System.getProperty(
                "umb.legacy.deobfuscatedEnvironment", "true"));
        List<File> cp = deobfuscatedEnvironment
                ? LegacyClasspath.forBoot(classpathFile, runtimeJar, forgeJar,
                        Collections.<File>emptyList(), Arrays.asList(legacysideJar, apiJar(repo), bridgeApiJar(repo)))
                : LegacyClasspath.forInstallerBoot(classpathFile, runtimeJar, forgeJar,
                        Collections.<File>emptyList(), Arrays.asList(legacysideJar, apiJar(repo), bridgeApiJar(repo)));
        URL[] urls = LegacyClasspath.toUrls(cp);

        out.println("[block-shapes] repo    = " + repo);
        out.println("[block-shapes] gameDir = " + gameDir);
        out.println("[block-shapes] sources = " + cp.size() + " jars");

        LegacyLoader loader = new LegacyLoader(urls, BlockShapeMain.class.getClassLoader());

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
        blackboard.put("fml.deobfuscatedEnvironment", Boolean.valueOf(deobfuscatedEnvironment));
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
            out.println("[block-shapes] TIMEOUT after " + timeoutSeconds + "s");
            write(new File(outDir, "block-shapes.txt"), "BLOCK-SHAPES-FAIL: timeout after " + timeoutSeconds + "s\n");
            out.flush();
            Runtime.getRuntime().halt(3);
        }

        if (run.entries == null) {
            String failMsg = "BLOCK-SHAPES-FAIL: " + run.failure;
            write(new File(outDir, "block-shapes.txt"), failMsg);
            out.println(failMsg);
            out.flush();
            Runtime.getRuntime().halt(1);
            return;
        }

        Map<String, Integer> counts = summarize(run.entries);
        String json = Json.blockShapes(run.entries, counts);
        Files.createDirectories(jsonOut.getParentFile().toPath());
        write(jsonOut, json);

        String summaryLine = "BLOCK-SHAPES ok=" + counts.get("ok") + "/" + counts.get("total")
                + " nonCube=" + counts.get("nonCube") + " multiBox=" + counts.get("multiBox")
                + " errors=" + counts.get("errors") + " metasDeduped=" + counts.get("metasDeduped");
        write(new File(outDir, "block-shapes.txt"), summaryLine + "\n");
        out.println("[block-shapes] wrote " + jsonOut + " (" + json.length() + " bytes)");
        out.println(summaryLine);
        out.flush();
        Runtime.getRuntime().halt(0);
    }

    private static Map<String, Integer> summarize(List<BlockShapeEntry> entries) {
        int total = entries.size();
        int errors = 0, nonCube = 0, multiBox = 0, metasDeduped = 0;
        for (int i = 0; i < entries.size(); i++) {
            BlockShapeEntry e = entries.get(i);
            if (e.error() != null) {
                errors++;
                continue;
            }
            if (e.isNonCube()) {
                nonCube++;
            }
            if (e.isMultiBox()) {
                multiBox++;
            }
            metasDeduped += Math.max(0, e.metasProbed() - e.metaShapes().size());
        }
        Map<String, Integer> m = new LinkedHashMap<String, Integer>();
        m.put("total", Integer.valueOf(total));
        m.put("ok", Integer.valueOf(total - errors));
        m.put("errors", Integer.valueOf(errors));
        m.put("nonCube", Integer.valueOf(nonCube));
        m.put("multiBox", Integer.valueOf(multiBox));
        m.put("metasDeduped", Integer.valueOf(metasDeduped));
        return m;
    }

    private static final class Run implements Runnable {
        private final LegacyLoader loader;
        List<BlockShapeEntry> entries;
        String failure;

        Run(LegacyLoader loader) {
            this.loader = loader;
        }

        @Override
        @SuppressWarnings("unchecked")
        public void run() {
            try {
                Class<?> c = Class.forName(PROBE, true, loader);
                if (c.getClassLoader() != loader) {
                    throw new IllegalStateException("BlockShapeProbe leaked to " + c.getClassLoader());
                }
                Object result = c.getMethod("run").invoke(null);
                entries = (List<BlockShapeEntry>) result;
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
            out.println("[block-shapes] cannot write " + f + ": " + e);
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

    private BlockShapeMain() {
    }
}
