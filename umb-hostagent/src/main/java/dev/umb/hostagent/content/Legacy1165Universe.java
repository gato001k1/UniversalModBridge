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
 * The 1.16.5 legacy universe, embedded in the live 26.2 client next to the 1.7.10 one.
 * Lazy boot-once forwarder to the REAL in-universe
 * {@code dev.umb.legacy1165.legacyside.Legacy1165BridgeImpl} - same null-safe shape as
 * {@link UmbUniverse}, but none of its machinery is shared: different loader
 * (a plain child-first URLClassLoader over the 1.16.5 manifest, no LaunchWrapper anywhere),
 * different boot (ModLoader phases, no Launch statics, no game staging), different flags.
 *
 * <p>How the in-universe bridge is reached with zero new build dependencies: the manifest
 * lists every jar (including our own boot jar); a plain URLClassLoader over the boot jar
 * loads the loader class; the loader builds the universe; the bridge class loads through
 * it and casts to the parent-delegated {@code dev.umb.bridge.api.LegacyBridge} - the same
 * class-identity argument {@link UmbUniverse} documents, which is why the contract mirror
 * must stay byte-identical everywhere.</p>
 */
public final class Legacy1165Universe extends LegacyEraUniverse {

    private final List<File> modJars;
    private final List<File> dataPacks;

    public Legacy1165Universe(List<File> modJars) {
        this(modJars, Collections.emptyList());
    }

    public Legacy1165Universe(List<File> modJars, List<File> dataPacks) {
        super("Legacy1165Universe");
        if (modJars == null || modJars.isEmpty())
            throw new IllegalArgumentException("Legacy1165Universe needs at least one mod jar");
        this.modJars = Collections.unmodifiableList(new ArrayList<>(modJars));
        this.dataPacks = Collections.unmodifiableList(new ArrayList<>(
                dataPacks == null ? Collections.emptyList() : dataPacks));
    }

    @Override
    public synchronized void boot(HostWorld world) throws Exception {
        if (real != null) return;
        checkEraJvmFlags();
        File repo = repoRoot();
        File manifest = firstFile(new File(repo, "umb-legacy-1165/resources/classpath-1165.txt"),
                new File(repo, "inputs/classpath-1165.txt"));
        List<File> files = readManifest(repo, manifest);
        List<URL> urls = new ArrayList<>();
        for (File f : files) urls.add(f.toURI().toURL());

        // A manifest record is both a Forge mod input and a classloader input, so
        // record jars must be visible to the isolated loader as well. Avoid a
        // duplicate URL when a legacy record is still carried by the static manifest.
        for (File modJar : modJars) {
            requireFile(modJar, "manifest record jar missing");
            boolean alreadyPresent = false;
            for (File f : files) {
                if (f.getAbsoluteFile().equals(modJar.getAbsoluteFile())) {
                    alreadyPresent = true;
                    break;
                }
            }
            if (!alreadyPresent) urls.add(modJar.getAbsoluteFile().toURI().toURL());
        }

        File bootJar = firstFile(new File(repo, "umb-legacy-1165/build/umb-legacy1165-boot.jar"),
                new File(repo, "umb-legacy1165-boot.jar"));
        requireFile(bootJar, "run umb-legacy-1165/build.ps1 first");
        URL[] bootUrls = new URL[]{bootJar.toURI().toURL()};
        ClassLoader hostLoader = Legacy1165Universe.class.getClassLoader();
        ClassLoader bootLoader = new java.net.URLClassLoader(bootUrls, hostLoader);

        // The bridge reads its mod jars from the same property the scripts use; the value
        // is forge-first + manifest record jars (see below), not globals.
        String previousModJars = System.getProperty("umb.1165.modjars");
        // Forge itself is a mod: it boots first so its registry entries and
        // capability registrations exist when content mods build. The universe
        // manifest always carries the universal jar, so it is prepended here
        // rather than required in every manifest record.
        // umb.1165.modjars carries forge-first + record jars for the in-universe bridge.
        StringBuilder jars = new StringBuilder();
        for (File f : files) {
            String n = f.getName();
            if (n.startsWith("forge-") && n.endsWith("-universal.jar")) {
                if (jars.length() > 0) jars.append(';');
                jars.append(f.getAbsolutePath());
            }
        }
        for (File modJar : modJars) {
            if (jars.length() > 0) jars.append(';');
            jars.append(modJar.getAbsolutePath());
        }
        System.setProperty("umb.1165.modjars", jars.toString());
        try {
            Class<?> loaderClass = Class.forName(
                    "dev.umb.legacy1165.boot.Legacy1165Loader", true, bootLoader);
            Object loader = loaderClass
                    .getConstructor(URL[].class, ClassLoader.class)
                    .newInstance(urls.toArray(new URL[0]), hostLoader);
            Class<?> bridgeClass = Class.forName(
                    "dev.umb.legacy1165.legacyside.Legacy1165BridgeImpl", true,
                    (ClassLoader) loader);
            Object instance = bridgeClass.getDeclaredConstructor().newInstance();
            if (instance.getClass().getClassLoader() != loader) {
                throw new IllegalStateException("1165 bridge leaked to "
                        + instance.getClass().getClassLoader());
            }
            LegacyBridge b = (LegacyBridge) instance;
            try {
                instance.getClass().getMethod("boot", HostWorld.class, List.class)
                        .invoke(instance, world, dataPacks);
            } catch (Throwable bootError) {
                // Log the full chain: a lazy boot failure is only diagnosable from
                // the deepest cause.
                AgentLog.loud("UMB-BRIDGE-1165 boot FAILED: " + fullChain(bootError));
                AgentLog.error("Legacy1165Universe.boot", bootError, 25);
                throw bootError;
            }
            this.real = b;
            AgentLog.loud("UMB-BRIDGE-1165 universe booted (" + modJars.size() + " mod jar(s))");
        } finally {
            if (previousModJars == null) System.clearProperty("umb.1165.modjars");
            else System.setProperty("umb.1165.modjars", previousModJars);
        }
    }
}
