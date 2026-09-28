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

import dev.umb.legacy.api.LegacyUniverse;
import dev.umb.legacy.api.RegistrySnapshot;
import dev.umb.legacy.api.StageResult;
import dev.umb.legacy.api.UniverseConfig;

import net.minecraft.launchwrapper.Launch;

/**
 * Stands the legacy universe up from a bare JVM and drives it headless.
 *
 * <p>Everything here runs on the APPLICATION classloader. It touches exactly three legacy types:
 * {@code net.minecraft.launchwrapper.Launch} (the statics FML reads), {@code LaunchClassLoader}
 * (via {@link LegacyLoader}) and {@link LegacyUniverse} - the plain-data handshake. It never
 * mentions {@code cpw.mods.fml.*} or {@code net.minecraft.block.*}: all of that lives inside the
 * child loader, which is what makes the same sequence reusable from inside the 26.2 client.</p>
 */
public final class Bootstrap {

    /** Loaded inside the legacy universe; must implement {@link LegacyUniverse}. */
    private static final String DRIVER = "dev.umb.legacy.legacyside.LegacyDriver";

    private static PrintStream out;

    public static void main(String[] args) throws Exception {
        // FML replaces System.out with its log4j TracingPrintStream, so keep a handle on the real one
        out = System.out;

        File repo = dir("umb.repo", null);
        File outDir = dir("umb.legacy.out", new File(repo, "research/out/legacy/legacy-boot"));
        Files.createDirectories(outDir.toPath());

        File runtimeJar = file("umb.legacy.runtimeJar", new File(repo, "research/out/legacy/1.7.10-forge-srg-runtime.jar"));
        File forgeJar = file("umb.legacy.forgeJar", new File(repo,
                "research/visual/mc1710-native/libraries/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar"));
        File classpathFile = file("umb.legacy.classpathFile",
                new File(repo, "research/visual/mc1710-native/classpath.txt"));
        File legacysideJar = file("umb.legacy.legacysideJar", new File(repo, "build/legacy/umb-legacy-legacyside.jar"));
        File gameDir = dir("umb.legacy.gameDir", outDir);
        File assetsDir = new File(System.getProperty("umb.legacy.assetsDir",
                new File(repo, "research/visual/mc1710-native/assets").getAbsolutePath()));
        boolean sideTransformer = Boolean.parseBoolean(System.getProperty("umb.legacy.sideTransformer", "false"));
        long timeoutSeconds = Long.parseLong(System.getProperty("umb.legacy.timeoutSeconds", "1200"));

        File modsDir = new File(gameDir, "mods");
        Files.createDirectories(modsDir.toPath());
        Files.createDirectories(new File(gameDir, "config").toPath());

        boolean deobfuscatedEnvironment = Boolean.parseBoolean(System.getProperty(
                "umb.legacy.deobfuscatedEnvironment", "true"));
        List<File> cp = deobfuscatedEnvironment
                ? LegacyClasspath.forBoot(classpathFile, runtimeJar, forgeJar,
                        Collections.<File>emptyList(), Arrays.asList(legacysideJar, apiJar(repo)))
                : LegacyClasspath.forInstallerBoot(classpathFile, runtimeJar, forgeJar,
                        Collections.<File>emptyList(), Arrays.asList(legacysideJar, apiJar(repo)));
        URL[] urls = LegacyClasspath.toUrls(cp);

        out.println("[umb-legacy] repo        = " + repo);
        out.println("[umb-legacy] gameDir     = " + gameDir);
        out.println("[umb-legacy] modsDir     = " + modsDir + " -> " + Arrays.toString(modsDir.list()));
        out.println("[umb-legacy] sources     = " + cp.size() + " jars");
        out.println("[umb-legacy] runtimeJar  = " + runtimeJar.getName());
        out.println("[umb-legacy] forgeJar    = " + forgeJar.getName());
        out.println("[umb-legacy] java        = " + System.getProperty("java.version") + " (" + System.getProperty("java.vm.name") + ")");
        out.println("[umb-legacy] headless    = " + System.getProperty("java.awt.headless"));
        out.println("[umb-legacy] maxHeap     = " + (Runtime.getRuntime().maxMemory() >> 20) + "M");

        LegacyLoader loader = new LegacyLoader(urls, Bootstrap.class.getClassLoader());

        // The statics FML reads straight out of LaunchWrapper. They must be set on the HOST copy of
        // Launch (parent-delegated), which is exactly the copy the child loader resolves.
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
        // deobf env: our vanilla is SRG-named, so FML must NOT install DeobfuscationTransformer
        blackboard.put("fml.deobfuscatedEnvironment", Boolean.valueOf(deobfuscatedEnvironment));
        blackboard.put("Tweaks", new ArrayList<Object>());
        blackboard.put("TweakClasses", new ArrayList<String>());
        blackboard.put("modList", new HashMap<String, Map<String, String>>());
        blackboard.put("coremodList", new ArrayList<Object>());

        UniverseConfig cfg = new UniverseConfig(gameDir.getAbsolutePath(), modsDir.getAbsolutePath(),
                assetsDir.getAbsolutePath(), forgeJar.getAbsolutePath(), sideTransformer,
                transformerList(sideTransformer));

        Run run = new Run(loader, cfg);
        // FMLCommonHandler.getEffectiveSide() keys off the thread NAME being "Server thread"
        Thread t = new Thread(run, "Server thread");
        t.setContextClassLoader(loader);
        t.setDaemon(false);
        long t0 = System.nanoTime();
        t.start();
        t.join(timeoutSeconds * 1000L);
        long wall = (System.nanoTime() - t0) / 1_000_000L;

        if (t.isAlive()) {
            String dump = threadDump();
            write(new File(outDir, "hang-threaddump.txt"), dump);
            out.println("[umb-legacy] TIMEOUT after " + timeoutSeconds + "s - thread dump written");
            out.flush();
            Runtime.getRuntime().halt(3);
        }

        StringBuilder report = new StringBuilder();
        report.append("wallMillis=").append(wall).append('\n');
        report.append("peakHeapMB=").append((Runtime.getRuntime().totalMemory() >> 20)).append('\n');
        report.append("usedHeapMB=").append(((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) >> 20)).append('\n');
        for (StageResult r : run.stages) {
            report.append("stage=").append(r.stage())
                    .append(" ok=").append(r.ok())
                    .append(" millis=").append(r.millis()).append('\n');
            if (!r.ok()) {
                report.append("  throwable=").append(r.throwableClass()).append(": ").append(r.throwableMessage()).append('\n');
                report.append(indent(r.stackTrace())).append('\n');
            }
        }
        if (run.bootFailure != null) {
            report.append("bootFailure=\n").append(indent(run.bootFailure)).append('\n');
        }
        if (run.snapshotFailure != null) {
            report.append("snapshotFailure=\n").append(indent(run.snapshotFailure)).append('\n');
        }
        write(new File(outDir, "stages.txt"), report.toString());
        out.println("[umb-legacy] ---- stages ----");
        out.println(report.toString());

        if (run.snapshot != null) {
            Map<String, String> meta = new LinkedHashMap<String, String>();
            meta.put("javaVersion", System.getProperty("java.version"));
            meta.put("wallMillis", Long.toString(wall));
            meta.put("sideTransformer", Boolean.toString(sideTransformer));
            String json = Json.snapshot(run.snapshot, run.stages, meta);
            write(new File(outDir, "live-registries.json"), json);
            out.println("[umb-legacy] live-registries.json = " + json.length() + " bytes");
            out.println("[umb-legacy] counts = " + run.snapshot.counts());
        }
        out.flush();

        boolean allOk = run.bootFailure == null && run.snapshotFailure == null;
        for (StageResult r : run.stages) {
            allOk &= r.ok();
        }
        // the legacy universe leaves non-daemon FML/netty threads behind; nothing to salvage
        Runtime.getRuntime().halt(allOk ? 0 : 1);
    }

    private static List<String> transformerList(boolean sideTransformer) {
        String override = System.getProperty("umb.legacy.transformers");
        if (override != null && !override.trim().isEmpty()) {
            return Arrays.asList(override.split(","));
        }
        List<String> t = new ArrayList<String>();
        // umb shim first: rewrites EnumHelper's dead sun.reflect plumbing (see UmbShimTransformer)
        t.add("dev.umb.legacy.legacyside.UmbShimTransformer");
        // PatchingTransformer is deliberately absent: the runtime jar is already binpatched.
        // DeobfuscationTransformer is deliberately absent: SRG-named vanilla == deobf environment.
        if (!Boolean.parseBoolean(System.getProperty("umb.legacy.deobfuscatedEnvironment", "true"))) {
            t.add("cpw.mods.fml.common.asm.transformers.DeobfuscationTransformer");
        }
        t.add("cpw.mods.fml.common.asm.transformers.MarkerTransformer");
        if (sideTransformer) {
            t.add("cpw.mods.fml.common.asm.transformers.SideTransformer");
        }
        t.add("cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer");
        t.add("cpw.mods.fml.common.asm.transformers.AccessTransformer");
        t.add("net.minecraftforge.classloading.FluidIdTransformer");
        t.add("net.minecraftforge.transformers.ForgeAccessTransformer");
        t.add("cpw.mods.fml.common.asm.transformers.ItemStackTransformer");
        return t;
    }

    private static final class Run implements Runnable {
        private final LegacyLoader loader;
        private final UniverseConfig cfg;
        List<StageResult> stages = new ArrayList<StageResult>();
        RegistrySnapshot snapshot;
        String bootFailure;
        String snapshotFailure;

        Run(LegacyLoader loader, UniverseConfig cfg) {
            this.loader = loader;
            this.cfg = cfg;
        }

        @Override
        public void run() {
            LegacyUniverse universe = null;
            try {
                Class<?> c = Class.forName(DRIVER, true, loader);
                if (c.getClassLoader() != loader) {
                    throw new IllegalStateException("driver leaked to " + c.getClassLoader());
                }
                universe = (LegacyUniverse) c.getDeclaredConstructor().newInstance();
                universe.boot(cfg);
            } catch (Throwable e) {
                bootFailure = stack(e);
                return;
            }
            stages = universe.lifecycle();
            try {
                snapshot = universe.snapshot();
            } catch (Throwable e) {
                snapshotFailure = stack(e);
            }
            try {
                universe.close();
            } catch (Throwable ignored) {
                // nothing useful to do
            }
        }
    }

    private static String stack(Throwable t) {
        StringWriter w = new StringWriter();
        t.printStackTrace(new PrintWriter(w));
        return w.toString();
    }

    private static String indent(String s) {
        if (s == null) {
            return "";
        }
        return "    " + s.replace("\n", "\n    ");
    }

    private static String threadDump() {
        StringBuilder b = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            b.append("\"").append(e.getKey().getName()).append("\" state=").append(e.getKey().getState()).append('\n');
            for (StackTraceElement s : e.getValue()) {
                b.append("    at ").append(s).append('\n');
            }
            b.append('\n');
        }
        return b.toString();
    }

    private static void write(File f, String content) {
        try {
            Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (Exception e) {
            out.println("[umb-legacy] cannot write " + f + ": " + e);
        }
    }

    private static File apiJar(File repo) {
        return new File(repo, "build/legacy/umb-legacy-api.jar");
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

    private Bootstrap() {
    }
}
