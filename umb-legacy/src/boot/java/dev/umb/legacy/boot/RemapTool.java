package dev.umb.legacy.boot;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.launchwrapper.Launch;

/**
 * Build step: produce an SRG-named Forge jar from the production universal jar.
 *
 * <p>Runs a throwaway legacy loader over [notch client jar, forge universal jar, the 1.7.10
 * libraries, the legacy-side jar] and calls {@code ForgeSrgifier.remap} inside it. The notch jar is
 * present ONLY here - the real boot never sees it.</p>
 */
public final class RemapTool {

    public static void main(String[] args) throws Exception {
        File repo = new File(System.getProperty("umb.repo")).getAbsoluteFile();
        File notchJar = new File(System.getProperty("umb.legacy.notchJar",
                new File(repo, "research/visual/mc1710-native/versions/1.7.10-Forge10.13.4.1614-1.7.10/1.7.10-Forge10.13.4.1614-1.7.10.jar").getAbsolutePath()));
        File forgeJar = new File(System.getProperty("umb.legacy.forgeJar",
                new File(repo, "research/visual/mc1710-native/libraries/net/minecraftforge/forge/1.7.10-10.13.4.1614-1.7.10/forge-1.7.10-10.13.4.1614-1.7.10-universal.jar").getAbsolutePath()));
        File classpathFile = new File(System.getProperty("umb.legacy.classpathFile",
                new File(repo, "research/visual/mc1710-native/classpath.txt").getAbsolutePath()));
        File runtimeJar = new File(System.getProperty("umb.legacy.runtimeJar",
                new File(repo, "research/out/legacy/1.7.10-forge-srg-runtime.jar").getAbsolutePath()));
        File legacysideJar = new File(System.getProperty("umb.legacy.legacysideJar",
                new File(repo, "build/legacy/umb-legacy-legacyside.jar").getAbsolutePath()));
        File outJar = new File(System.getProperty("umb.legacy.forgeSrgJar",
                new File(repo, "build/legacy/forge-1.7.10-10.13.4.1614-srg.jar").getAbsolutePath()));
        File workDir = new File(System.getProperty("umb.legacy.out",
                new File(repo, "research/out/legacy/legacy-boot").getAbsolutePath()));
        Files.createDirectories(workDir.toPath());

        for (File f : Arrays.asList(notchJar, forgeJar, runtimeJar, legacysideJar)) {
            if (!f.isFile()) {
                throw new IllegalStateException("missing input: " + f);
            }
        }

        List<File> cp = LegacyClasspath.forRemap(classpathFile, notchJar, forgeJar,
                Arrays.asList(legacysideJar, new File(repo, "build/legacy/umb-legacy-api.jar")));
        URL[] urls = LegacyClasspath.toUrls(cp);

        LegacyLoader loader = new LegacyLoader(urls, RemapTool.class.getClassLoader());
        Launch.minecraftHome = workDir;
        Launch.assetsDir = workDir;
        Launch.classLoader = loader;
        Map<String, Object> blackboard = new HashMap<String, Object>();
        Launch.blackboard = blackboard;
        blackboard.put("fml.deobfuscatedEnvironment", Boolean.FALSE);
        blackboard.put("launchArgs", new HashMap<String, String>());

        System.out.println("[umb-legacy remap] notch  = " + notchJar.getName());
        System.out.println("[umb-legacy remap] forge  = " + forgeJar.getName());
        System.out.println("[umb-legacy remap] out    = " + outJar);

        Thread.currentThread().setContextClassLoader(loader);
        Class<?> srgifier = Class.forName("dev.umb.legacy.legacyside.ForgeSrgifier", true, loader);
        Method remap = srgifier.getMethod("remap", String.class, String.class, String.class,
                String.class, String.class);
        String summary = (String) remap.invoke(null,
                workDir.getAbsolutePath(),
                forgeJar.getAbsolutePath(),
                outJar.getAbsolutePath(),
                runtimeJar.getAbsolutePath(),
                "/deobfuscation_data-1.7.10.lzma");

        System.out.println(summary);
        Files.write(new File(workDir, "srgify.txt").toPath(), summary.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private RemapTool() {
    }
}
