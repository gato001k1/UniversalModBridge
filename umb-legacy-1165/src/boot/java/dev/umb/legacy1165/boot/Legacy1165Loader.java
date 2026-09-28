package dev.umb.legacy1165.boot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The 1.16.5 legacy universe classloader.
 *
 * <p>This is deliberately NOT a line-for-line port of the 1.7.10/1.12.2 {@code LegacyLoader}:
 * those eras boot through {@code net.minecraft.launchwrapper.Launch} (a {@code LaunchClassLoader}
 * subclass with its own transformer pipeline and its own two Java-9+ compatibility fixes), while
 * 1.16.5 boots through {@code cpw.mods.modlauncher.Launcher} (a {@code TransformingClassLoader}
 * built by ModLauncher's service discovery - proven by the real 36.2.34 installer's
 * {@code version.json}: {@code mainClass=cpw.mods.modlauncher.Launcher}; the universal jar
 * contains NO launchwrapper classes at all). There is no LaunchWrapper to extend, no
 * {@code classLoaderExceptions} field to narrow, and no bootstrap-parent trap to fix - a plain
 * {@code URLClassLoader} with an explicit child-first-by-ownership policy is the whole design.
 * See ERA-1165-PLAN.md "loader generation" for the evidence.</p>
 *
 * <p>Delegation policy:</p>
 * <pre>
 *   java. / jdk. / sun. / com.sun.        -&gt; parent, always (never define a JDK class ourselves)
 *   org.apache.logging.                   -&gt; parent, always (one shared log4j, same rule as 1.7.10/1.12.2)
 *   dev.umb.legacy1165.api. / .boot.      -&gt; parent, always (this era's plain-data handshake)
 *   dev.umb.bridge.api.                   -&gt; parent, always (the SHARED cross-era boundary contract)
 *   anything whose .class is in OUR jars  -&gt; CHILD FIRST (Forge/FML/ModLauncher/vanilla/mod)
 *   everything else                       -&gt; parent fallback
 * </pre>
 *
 * <p>"Is it in our jars" (a {@code findResource} probe, cached) beats a hand-maintained package
 * list, same as the older eras. {@code net.minecraft.*} is routed child-first with a parent
 * fallback: vanilla arrives SRG-named (offline rename, see ERA-1165-PLAN.md round 2), so SRG
 * names resolve here.</p>
 *
 * <p><b>Transformation hook (the ModLauncher-generation difference):</b> {@link #findClass}
 * runs every child-loaded class through Forge's OWN eventbus transformer - the same engine
 * ModLauncher's {@code "eventbus"} launch plugin drives in production (verified by javap +
 * source on the hash-verified eventbus 4.0.0 jar), which adds the public no-arg constructor +
 * listener-list plumbing to every Event subclass. That is the production mechanism that lets
 * eventbus instantiate events like {@code RegistryEvent$Register} for listener-list
 * computation. The work happens in {@code dev.umb.legacy1165.transform.EventTransform}, which
 * lives INSIDE the child universe (own jar on the manifest): the loader's own symbolic
 * references would resolve through the application loader and collide with the child's ASM /
 * engine copies (two {@code org.objectweb.asm.Type} identities - proven by failure), so the
 * loader calls the worker reflectively and never links the transformed world itself.
 * Non-event classes pass through byte-identical (the worker no-ops; untransformed classes
 * still go through {@code super.findClass}, preserving exact URLClassLoader semantics). When
 * the worker jar is absent (synthetic unit-test sources), the hook degrades to a plain loader -
 * it NEVER breaks classloading for a transform. Transformed classes are defined without a
 * CodeSource (no sealing constraints collide in practice - the full lifecycle suite proves
 * it); stack frames are recomputed with hierarchies resolved through the child universe,
 * never the app loader.</p>
 *
 * <p><b>log4j version note:</b> the real vanilla 1.16.5 library set ships log4j-api/log4j-core
 * 2.8.1, but Forge 36.2.34's own version.json pins 2.15.0. {@code classpath-1165.txt} keeps only
 * the 2.15.0 pair - the same "one shared log4j" rule as the older eras, just this era's pinned
 * version.</p>
 */
public final class Legacy1165Loader extends URLClassLoader {

    private static final List<String> ALWAYS_PARENT = Collections.unmodifiableList(Arrays.asList(
            "java.",
            "jdk.",
            "sun.",
            "com.sun.",
            "org.apache.logging.",
            "dev.umb.legacy1165.api.",
            "dev.umb.legacy1165.boot.",
            "dev.umb.bridge.api."));

    private final ClassLoader delegateParent;
    private final List<String> alwaysParent;
    private final ConcurrentHashMap<String, Boolean> ownsCache = new ConcurrentHashMap<String, Boolean>();

    // Eventbus-transformer hook state. The worker lives INSIDE the child universe
    // (dev.umb.legacy1165.transform.EventTransform, own jar on the manifest) because the
    // loader's own symbolic references would resolve through the APP loader and collide with
    // the child's ASM/engine copies (proven by failure: two org.objectweb.asm.Type identities).
    // Resolved reflectively so sources without the helper jar degrade to a plain loader.
    private volatile boolean transformResolved = false;
    private volatile boolean transformAvailable = false;
    private volatile java.lang.reflect.Method transformMethod;

    public Legacy1165Loader(URL[] sources, ClassLoader delegateParent) {
        this(sources, delegateParent, Collections.<String>emptyList());
    }

    public Legacy1165Loader(URL[] sources, ClassLoader delegateParent, List<String> extraParentPrefixes) {
        super(sources, null);
        if (delegateParent == null) {
            throw new IllegalArgumentException("the legacy loader needs a real parent loader");
        }
        this.delegateParent = delegateParent;
        List<String> p = new ArrayList<String>(ALWAYS_PARENT);
        p.addAll(extraParentPrefixes);
        this.alwaysParent = Collections.unmodifiableList(p);
    }

    public boolean isParentDelegated(String name) {
        for (int i = 0; i < alwaysParent.size(); i++) {
            if (name.startsWith(alwaysParent.get(i))) {
                return true;
            }
        }
        return false;
    }

    /** true when this loader's own sources contain the class file for {@code name}. */
    public boolean owns(String name) {
        Boolean cached = ownsCache.get(name);
        if (cached != null) {
            return cached.booleanValue();
        }
        boolean owned = findResource(name.replace('.', '/').concat(".class")) != null;
        ownsCache.put(name, Boolean.valueOf(owned));
        return owned;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (isParentDelegated(name)) {
                    c = delegateParent.loadClass(name);
                } else if (name.startsWith("net.minecraft.") || owns(name)) {
                    // Vanilla is obfuscated on disk, so owns(real SRG name) is false. A future
                    // rename/transform stage (ModLauncher transformation services, not this
                    // class) is what will make SRG names resolve; until then route child-first
                    // so that stage - once installed - sees these requests before the parent.
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException missingFromLegacyUniverse) {
                        if (name.startsWith("net.minecraft.")) {
                            c = delegateParent.loadClass(name);
                        } else {
                            throw missingFromLegacyUniverse;
                        }
                    }
                } else {
                    c = delegateParent.loadClass(name);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    @Override
    public URL getResource(String name) {
        URL own = findResource(name);
        if (own != null) {
            return own;
        }
        return delegateParent.getResource(name);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (name.contains("RegistryEvent") && "true".equals(System.getProperty("umb.debugTransform"))) {
            System.err.println("[legacy1165-transform] findClass entered for " + name);
        }
        if (isTransformExempt(name)) {
            return super.findClass(name);
        }
        String path = name.replace('.', '/').concat(".class");
        URL url = findResource(path);
        if (url == null) {
            throw new ClassNotFoundException(name);
        }
        byte[] bytes = readBytes(url, name);
        byte[] transformed = transformEventBytes(bytes);
        if (transformed != bytes) {
            // Define WITH the origin jar's CodeSource (signers included): Forge's jars are
            // SIGNED (not sealed), and the JVM rejects a signer-less class next to signed
            // siblings in the same package (proven by failure: IModBusEvent vs a transformed
            // FML lifecycle event). Production's TransformingClassLoader preserves CodeSources
            // the same way.
            return defineClass(name, transformed, 0, transformed.length,
                    codeSourceFor(url, path));
        }
        return super.findClass(name);
    }

    private final ConcurrentHashMap<String, java.util.jar.JarFile> jarCache =
            new ConcurrentHashMap<String, java.util.jar.JarFile>();

    /**
     * CodeSource for a class origin URL, carrying the origin jar entry's real code signers when
     * present (Forge universal / eventbus are signed; vanilla/SRG jars are not). Reading the
     * entry through a verifying JarFile both populates the signers AND integrity-checks the
     * fetched jar (tampering fails loudly here instead of later). Falls back to an unsigned
     * CodeSource when anything is unexpected - callers then get the JVM's own honest error.
     */
    private java.security.CodeSource codeSourceFor(URL url, String path) {
        try {
            if ("jar".equals(url.getProtocol())) {
                String spec = url.getFile();
                int bang = spec.indexOf("!/");
                if (bang >= 0) {
                    String jarKey = spec.substring(0, bang);
                    String entryName = spec.substring(bang + 2);
                    java.util.jar.JarFile jar = jarCache.get(jarKey);
                    if (jar == null) {
                        jar = new java.util.jar.JarFile(
                                new java.io.File(new java.net.URI(jarKey)), true);
                        java.util.jar.JarFile raced = jarCache.putIfAbsent(jarKey, jar);
                        if (raced != null) {
                            jar.close();
                            jar = raced;
                        }
                    }
                    java.util.jar.JarEntry entry = jar.getJarEntry(entryName);
                    if (entry != null) {
                        InputStream in = jar.getInputStream(entry);
                        try {
                            byte[] buf = new byte[65536];
                            while (in.read(buf) >= 0) {
                                // drain: verification (and signer population) happens on read
                            }
                        } finally {
                            in.close();
                        }
                        return new java.security.CodeSource(url, entry.getCodeSigners());
                    }
                }
            }
        } catch (Exception e) {
            debugTransform("codesource fallback for " + path, e);
        }
        return new java.security.CodeSource(url, (java.security.CodeSigner[]) null);
    }

    /**
     * Classes that must NEVER pass through the event transformer, or the transformer cannot
     * bootstrap itself ({@code ClassCircularityError}, proven by failure): ASM (the tool the
     * transform is written with), the in-universe worker itself, and the engine/transformer
     * machinery classes. None of them are events (the engine would no-op them anyway), so
     * exempting is behavior-identical and also saves a parse. Everything else - including
     * eventbus's own event types like GenericEvent - flows through normally. This mirrors
     * production, where ModLauncher never applies transformation services to the service
     * machinery itself.
     */
    private static boolean isTransformExempt(String name) {
        return name.startsWith("org.objectweb.asm.")
                || name.startsWith("dev.umb.legacy1165.transform.")
                || name.startsWith("net.minecraftforge.eventbus.EventBusEngine")
                || name.startsWith("net.minecraftforge.eventbus.EventSubclassTransformer")
                || name.startsWith("net.minecraftforge.eventbus.EventAccessTransformer");
    }

    private static byte[] readBytes(URL url, String name) throws ClassNotFoundException {
        try (InputStream in = url.openStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) >= 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new ClassNotFoundException(name, e);
        }
    }

    /**
     * Runs one class file through Forge's eventbus transformer when it handles it; returns the
     * IDENTICAL array reference when nothing changed (so callers can branch on identity).
     * The work happens in the child universe (see the field note); this method only ferries
     * bytes across. Never throws for transform causes: any failure degrades to the original
     * bytes, and worker-resolution failure degrades permanently for this loader.
     */
    private byte[] transformEventBytes(byte[] bytes) {
        try {
            ensureTransformWorker();
            if (!transformAvailable) {
                return bytes;
            }
            Object out = transformMethod.invoke(null, this, bytes);
            if (out == null) {
                return bytes;
            }
            return (byte[]) out;
        } catch (LinkageError | ReflectiveOperationException e) {
            // Helper absent/broken, or worker shape unexpected: plain bytes, and stop trying
            // for this loader. Never break classloading for a transform.
            // -Dumb.debugTransform=true prints the swallowed cause once per distinct message
            // (diagnostic facility for exactly this failure mode; off by default).
            transformAvailable = false;
            debugTransform("transform worker unavailable, degrading to plain loader", e);
            return bytes;
        }
    }

    private static final java.util.Set<String> DEBUG_LOGGED =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private static void debugTransform(String what, Throwable t) {
        if (!"true".equals(System.getProperty("umb.debugTransform"))) {
            return;
        }
        String key = what + "::" + t;
        if (DEBUG_LOGGED.add(key)) {
            System.err.println("[legacy1165-transform] " + what + ": " + t);
        }
    }

    private synchronized void ensureTransformWorker() {
        if (transformResolved) {
            return;
        }
        transformResolved = true;
        try {
            // Child-first, like any legacy class. Reentrancy is safe: the flag is set before
            // loading, so the nested pass (which loads this worker class itself) defines it
            // untransformed and returns; the outer pass then resolves the method.
            Class<?> worker =
                    Class.forName("dev.umb.legacy1165.transform.EventTransform", true, this);
            transformMethod =
                    worker.getMethod("transform", ClassLoader.class, byte[].class);
            transformAvailable = true;
        } catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
            transformAvailable = false;
            debugTransform("transform worker resolution failed, degrading to plain loader", e);
        }
    }
}
