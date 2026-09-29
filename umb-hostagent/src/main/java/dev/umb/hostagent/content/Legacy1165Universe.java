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
 * The 1.16.5 legacy universe, embedded in the live 26.2 client next to the 1.7.10 one
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

    /** In-universe dist switch read by Legacy1165Lifecycle (CLIENT or DEDICATED_SERVER). */
    static final String DIST_PROPERTY = "umb.1165.dist";

    private volatile String bootedDist;

    /** The dist the live universe actually booted with, or null before boot. */
    public String bootedDist() {
        return bootedDist;
    }

    /**
     * Boot order for the era's Forge dist. A 26.2 client hosting this universe is "the client
     * process" (like vanilla's integrated server), so the live default is CLIENT: mods then
     * register their client renderers (TESRs, entity renderers) at construction/client setup,
     * which is the only time dist-gated mods ever do. CLIENT runs far more mod code headlessly,
     * so a CLIENT boot failure must never take the era (or the host) down: the fallback is a
     * fresh loader booted as DEDICATED_SERVER, the pre-existing live behavior. An explicit
     * {@code -Dumb.1165.dist} pins one dist with no fallback (probes, bisection).
     */
    static String[] distAttempts(String pinned) {
        if (pinned != null && !pinned.trim().isEmpty()) {
            return new String[] {pinned.trim()};
        }
        return new String[] {"CLIENT", "DEDICATED_SERVER"};
    }

    @Override
    public synchronized void boot(HostWorld world) throws Exception {
        if (real != null) return;
        checkEraJvmFlags();
        String[] attempts = distAttempts(System.getProperty(DIST_PROPERTY));
        for (int i = 0; i < attempts.length; i++) {
            try {
                bootWithDist(world, attempts[i]);
                bootedDist = attempts[i];
                if (i > 0) {
                    AgentLog.loud("UMB-BRIDGE-1165 booted with dist=" + attempts[i]
                            + " after the " + attempts[i - 1] + " attempt failed (no 1.16.5 client"
                            + " renderers this session)");
                } else {
                    AgentLog.loud("UMB-BRIDGE-1165 booted with dist=" + attempts[i]);
                }
                return;
            } catch (Throwable failure) {
                if (i + 1 >= attempts.length) {
                    if (failure instanceof Exception) throw (Exception) failure;
                    throw new IllegalStateException(failure);
                }
                // The failed attempt's loader, statics and Forge registries are discarded with
                // it: the next attempt builds a brand-new isolated loader from the manifest.
                AgentLog.loud("UMB-BRIDGE-1165 dist=" + attempts[i] + " boot failed; retrying dist="
                        + attempts[i + 1] + " in a fresh loader: " + fullChain(failure));
            }
        }
    }

    /**
     * Same key as objbridge's {@code LegacyCaptureTextureResolver.ASSET_JARS_PROPERTY} (kept as a
     * literal: hostagent never links objbridge). Captured era draws name textures such as
     * {@code alexsmobs:textures/entity/x.png}; this era's jars are not in the ObjBridge
     * manifest, so the resolver reads them from the jars published here.
     */
    static final String CAPTURE_ASSET_JARS_PROPERTY = "umb.capture.assetJars";

    static synchronized void publishCaptureAssetJars(List<File> jars) {
        String current = System.getProperty(CAPTURE_ASSET_JARS_PROPERTY, "");
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>();
        for (String part : current.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!part.trim().isEmpty()) all.add(part.trim());
        }
        for (File jar : jars) {
            if (jar != null) all.add(jar.getAbsolutePath());
        }
        System.setProperty(CAPTURE_ASSET_JARS_PROPERTY, String.join(File.pathSeparator, all));
    }

    /**
     * The era classpath for {@code dist}, ordered by the era's own boot helper
     * ({@code Legacy1165Classpath.forDist}: for CLIENT the Forge client overlay + full client jar
     * go ahead of the server jars, otherwise shared classes lose their client-only members).
     * Reached reflectively through the boot jar, like the loader itself. A missing helper (an
     * older boot jar) keeps the manifest order.
     */
    @SuppressWarnings("unchecked")
    static List<File> orderForDist(ClassLoader bootLoader, List<File> files, String dist) {
        try {
            Class<?> classpath = Class.forName("dev.umb.legacy1165.boot.Legacy1165Classpath", true,
                    bootLoader);
            Object ordered = classpath.getMethod("forDist", List.class, String.class)
                    .invoke(null, files, dist);
            if (ordered instanceof List) {
                List<File> result = (List<File>) ordered;
                if (result != files) {
                    AgentLog.loud("UMB-BRIDGE-1165 dist=" + dist + " classpath: client overlay first ("
                            + result.get(0).getName() + ")");
                }
                return result;
            }
        } catch (NoSuchMethodException older) {
            AgentLog.loud("UMB-BRIDGE-1165 boot jar has no Legacy1165Classpath.forDist; manifest order kept");
        } catch (Throwable t) {
            AgentLog.loud("UMB-BRIDGE-1165 classpath ordering failed, manifest order kept: " + t);
        }
        return files;
    }

    private void bootWithDist(HostWorld world, String dist) throws Exception {
        File repo = repoRoot();
        File manifest = firstFile(new File(repo, "umb-legacy-1165/resources/classpath-1165.txt"),
                new File(repo, "inputs/classpath-1165.txt"));
        List<File> files = readManifest(repo, manifest);
        File bootJar = firstFile(new File(repo, "umb-legacy-1165/build/umb-legacy1165-boot.jar"),
                new File(repo, "umb-legacy1165-boot.jar"));
        requireFile(bootJar, "run umb-legacy-1165/build.ps1 first");
        URL[] bootUrls = new URL[]{bootJar.toURI().toURL()};
        ClassLoader hostLoader = Legacy1165Universe.class.getClassLoader();
        ClassLoader bootLoader = new java.net.URLClassLoader(bootUrls, hostLoader);
        files = orderForDist(bootLoader, files, dist);
        List<URL> urls = new ArrayList<>();
        for (File f : files) urls.add(f.toURI().toURL());

        // A manifest record is both a Forge mod input and a classloader input.  IronChest was
        // historically present in classpath-1165.txt, which masked this requirement; newly
        // registered 1.16.5 records must be visible to the isolated loader as well.  Avoid a
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

        // The bridge reads its mod jars from the same property the scripts use; the value
        // is forge-first + manifest record jars (see below), not globals.
        String previousModJars = System.getProperty("umb.1165.modjars");
        // Forge itself is a mod (production boots it first: its ATTRIBUTES registry entries
        // and capability registrations must exist when content mods build - proven by a live
        // swim_speed failure without it). The universe manifest always carries the universal
        // jar, so it is prepended here rather than required in every manifest record.
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
        String previousDist = System.getProperty(DIST_PROPERTY);
        System.setProperty(DIST_PROPERTY, dist);
        ClassLoader previousContextLoader = Thread.currentThread().getContextClassLoader();
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
                // Full chain, not the one-level message: a lazy in-game boot failure is only
                // diagnosable from the deepest cause plus the failing <clinit> frames.
                AgentLog.loud("UMB-BRIDGE-1165 boot FAILED: " + fullChain(bootError));
                AgentLog.error("Legacy1165Universe.boot", bootError, 25);
                throw bootError;
            }
            this.real = b;
            publishCaptureAssetJars(modJars);
            AgentLog.loud("UMB-BRIDGE-1165 universe booted (" + modJars.size() + " mod jar(s))");
        } finally {
            Thread.currentThread().setContextClassLoader(previousContextLoader);
            if (previousModJars == null) System.clearProperty("umb.1165.modjars");
            else System.setProperty("umb.1165.modjars", previousModJars);
            if (previousDist == null) System.clearProperty(DIST_PROPERTY);
            else System.setProperty(DIST_PROPERTY, previousDist);
        }
    }
}
