package dev.umb.hostagent.content;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileHandle;
import dev.umb.hostagent.AgentLog;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The 1.12.2 legacy universe, embedded in the live 26.2 client next to the 1.7.10 and 1.16.5 ones.
 * Lazy boot-once forwarder to the REAL in-universe
 * {@code dev.umb.legacy1122.legacyside.Legacy1122BridgeImpl} - same null-safe shape as
 * {@link UmbUniverse}, but none of its machinery is shared: different loader
 * (a plain child-first URLClassLoader over the 1.12.2 manifest, no LaunchWrapper anywhere),
 * different boot (ModLoader phases, no Launch statics, no game staging), different flags.
 *
 * <p>How the in-universe bridge is reached with zero new build dependencies: the manifest
 * lists every jar (including our own boot jar); a plain URLClassLoader over the boot jar
 * loads the loader class; the loader builds the universe; the bridge class loads through
 * it and casts to the parent-delegated {@code dev.umb.bridge.api.LegacyBridge} - the same
 * class-identity argument {@link UmbUniverse} documents, which is why the contract mirror
 * must stay byte-identical everywhere.</p>
 */
public final class Legacy1122Universe extends LegacyEraUniverse {

    private final List<File> modJars;

    public Legacy1122Universe(List<File> modJars) {
        super("Legacy1122Universe");
        if (modJars == null || modJars.isEmpty())
            throw new IllegalArgumentException("Legacy1122Universe needs at least one mod jar");
        this.modJars = Collections.unmodifiableList(new ArrayList<>(modJars));
    }

    @Override
    public synchronized void boot(HostWorld world) throws Exception {
        if (real != null) return;
        checkEraJvmFlags();
        File repo = repoRoot();
        File manifest = firstFile(new File(repo, "umb-legacy-1122/resources/classpath-1122.txt"),
                new File(repo, "inputs/classpath-1122.txt"));
        List<File> files = readManifest(repo, manifest);
        File bootJar = firstFile(new File(repo, "umb-legacy-1122/build/umb-legacy1122-boot.jar"),
                new File(repo, "umb-legacy1122-boot.jar"));
        requireFile(bootJar, "run umb-legacy-1122/build.ps1 first");
        File legacysideJar = firstFile(new File(repo, "umb-legacy-1122/build/umb-legacy1122-legacyside.jar"),
                new File(repo, "umb-legacy1122-legacyside.jar"));
        requireFile(legacysideJar, "run umb-legacy-1122/build.ps1 first");
        // Legacy1122Loader is child-first for the in-universe namespace. Its source
        // URLs, not merely its parent boot loader, must contain both the lifecycle
        // helper and the bridge implementation.
        List<URL> urls = isolatedUrls(files, bootJar, legacysideJar);
        URL[] bootUrls = new URL[]{bootJar.toURI().toURL(), legacysideJar.toURI().toURL()};
        ClassLoader hostLoader = Legacy1122Universe.class.getClassLoader();
        ClassLoader bootLoader = new java.net.URLClassLoader(bootUrls, hostLoader);

        // The bridge reads its mod jars from the same property the scripts use; the value
        // is forge-first + manifest record jars (see below), not globals.
        String previousModJars = System.getProperty("umb.1122.modjars");
        String previousGameDir = System.getProperty("umb.1122.gamedir");
        // Forge itself is a mod: it boots first so its registry entries and
        // capability registrations exist when content mods build. The universe
        // manifest always carries the universal jar, so it is prepended here
        // rather than required in every manifest record.
        // umb.1122.modjars carries forge-first + record jars for the in-universe bridge.
        StringBuilder jars = new StringBuilder();
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("forge-") && n.endsWith("-universal.jar")) {
                if (jars.length() > 0) jars.append(';');
                jars.append(f.getAbsolutePath());
            }
        }
        for (File modJar : modJars) {
            requireFile(modJar, "manifest record jar missing");
            if (jars.length() > 0) jars.append(';');
            jars.append(modJar.getAbsolutePath());
        }
        System.setProperty("umb.1122.modjars", jars.toString());
        // Per-instance runtime dir: two games must not share one mod dir (file lock).
        String legacyGameDir = System.getProperty("umb.legacy.gameDir");
        File runtime1122 = legacyGameDir != null && !legacyGameDir.isEmpty()
                ? new File(legacyGameDir, "legacy-1122")
                : new File(repo, "research/out/legacy-1122/runtime-live");
        System.setProperty("umb.1122.gamedir", runtime1122.getAbsolutePath());
        try {
            Class<?> loaderClass = Class.forName(
                    "dev.umb.legacy1122.boot.Legacy1122Loader", true, bootLoader);
            Object loader = loaderClass
                    .getConstructor(URL[].class, ClassLoader.class)
                    .newInstance(urls.toArray(new URL[0]), hostLoader);
            Class<?> bridgeClass = Class.forName(
                    "dev.umb.legacy1122.legacyside.Legacy1122BridgeImpl", true,
                    (ClassLoader) loader);
            Object instance = bridgeClass.getDeclaredConstructor().newInstance();
            if (instance.getClass().getClassLoader() != loader) {
                throw new IllegalStateException("1122 bridge leaked to "
                        + instance.getClass().getClassLoader());
            }
            LegacyBridge b = (LegacyBridge) instance;
            try {
                b.boot(world);
            } catch (Throwable bootError) {
                // Log the full chain: a lazy boot failure is only diagnosable from
                // the deepest cause.
                AgentLog.loud("UMB-BRIDGE-1122 boot FAILED: " + fullChain(bootError));
                AgentLog.error("Legacy1122Universe.boot", bootError, 25);
                throw bootError;
            }
            this.real = b;
            AgentLog.loud("UMB-BRIDGE-1122 universe booted (" + modJars.size() + " mod jar(s))");
        } finally {
            if (previousModJars == null) System.clearProperty("umb.1122.modjars");
            else System.setProperty("umb.1122.modjars", previousModJars);
            if (previousGameDir == null) System.clearProperty("umb.1122.gamedir");
            else System.setProperty("umb.1122.gamedir", previousGameDir);
        }
    }

    static List<URL> isolatedUrls(List<File> manifestFiles, File bootJar, File legacysideJar)
            throws java.io.IOException {
        List<URL> urls = new ArrayList<>();
        for (File f : manifestFiles) urls.add(f.toURI().toURL());
        urls.add(bootJar.toURI().toURL());
        urls.add(legacysideJar.toURI().toURL());
        return urls;
    }
}
