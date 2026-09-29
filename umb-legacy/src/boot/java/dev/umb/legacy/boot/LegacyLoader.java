package dev.umb.legacy.boot;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * The legacy universe classloader.
 *
 * <p>It MUST be a {@link LaunchClassLoader}: {@code cpw.mods.fml.common.Loader}'s constructor does
 * {@code new ModClassLoader(getClass().getClassLoader())} and {@code ModClassLoader} casts that
 * parent to {@code LaunchClassLoader} before calling {@code addURL/getSources/registerTransformer}.
 * Subclassing keeps every one of FML's expectations (transformer registry, {@code getClassBytes},
 * {@code addURL}, {@code clearNegativeEntries}) while letting us fix the two things about
 * LaunchWrapper that do not survive the move off Java 8:</p>
 *
 * <ol>
 *   <li>LaunchWrapper calls {@code super(sources, null)}, i.e. its URLClassLoader parent is the
 *       BOOTSTRAP loader. On Java 9+ that hides every platform-loader module (java.sql,
 *       javax.script, jdk.charsets ...), so we override {@link #loadClass(String, boolean)} and
 *       fall back to a real parent loader for anything we do not own.</li>
 *   <li>LaunchWrapper's built-in classLoaderExceptions ("sun.", "org.lwjgl.",
 *       "org.apache.logging.", "com.mojang." on the server tweaker) assume those jars sit on the
 *       application classpath. In an embedded legacy universe they do not - they are OURS - so the
 *       exception set is reset to the minimum that must stay shared with the host.</li>
 * </ol>
 *
 * 26.2 client rather than a bare JVM):</p>
 * <pre>
 *   java. / jdk. / sun. / com.sun.        -> parent, always (never define JDK classes ourselves)
 *   net.minecraft.launchwrapper.          -> parent, always (FML casts to the HOST's LaunchClassLoader)
 *   dev.umb.legacy.api. / .boot.          -> parent, always (the shared plain-data handshake)
 *   anything whose .class is in OUR jars  -> CHILD FIRST, through the transformer pipeline
 *   everything else                       -> parent fallback
 * </pre>
 *
 * <p>The "is the resource in our jars" test is what makes this safe for both {@code javax.vecmath}
 * (shipped by the 1.7.10 libraries, must be ours) and {@code javax.script} (JDK only, must be the
 * parent's) without hand-maintaining a package list.</p>
 */
public final class LegacyLoader extends LaunchClassLoader {

    /**
     * Prefixes that must resolve in the parent even if we happen to ship a copy.
     *
     * <p>{@code dev.umb.bridge.api.} is the G2 boundary contract (see
     * {@code java.*}-only interfaces/classes loaded by the universe-root loader and shared with the
     * 26.2 host through this same parent-delegation mechanism, exactly like {@code dev.umb.legacy.api}
     * already is - so both sides resolve it to the SAME {@code Class} objects.</p>
     *
     * <p>{@code org.apache.logging.} is here for the same reason LaunchWrapper itself excludes it:
     * {@code net.minecraft.launchwrapper.LogWrapper} is parent-delegated (the whole
     * {@code net.minecraft.launchwrapper} package has to be one copy, because FML casts
     * {@code Launch.classLoader} to {@code LaunchClassLoader}) and LogWrapper's signatures mention
     * {@code org.apache.logging.log4j.Level}. {@code LaunchClassLoader.findClass} calls it on a
     * perfectly ordinary path - the "jar has a security seal for path X" warning, which
     * lzma-0.0.1.jar triggers - so a duplicated log4j turns that warning into a fatal
     * NoClassDefFoundError, and a log4j-api/log4j-core split across the two loaders makes
     * log4j's own provider lookup fail ("Log4jContextFactory does not implement
     * LoggerContextFactory"). One shared log4j 2.0-beta9 it is.</p>
     */
    private static final List<String> ALWAYS_PARENT = Collections.unmodifiableList(Arrays.asList(
            "java.",
            "jdk.",
            "sun.",
            "com.sun.",
            "net.minecraft.launchwrapper.",
            "org.apache.logging.",
            "dev.umb.legacy.api.",
            "dev.umb.legacy.boot.",
            "dev.umb.bridge.api."));

    private final ClassLoader delegateParent;
    private final List<String> alwaysParent;
    private final ConcurrentHashMap<String, Boolean> ownsCache = new ConcurrentHashMap<String, Boolean>();

    public LegacyLoader(URL[] sources, ClassLoader delegateParent) {
        this(sources, delegateParent, Collections.<String>emptyList());
    }

    public LegacyLoader(URL[] sources, ClassLoader delegateParent, List<String> extraParentPrefixes) {
        super(sources);
        if (delegateParent == null) {
            throw new IllegalArgumentException("the legacy loader needs a real parent loader");
        }
        this.delegateParent = delegateParent;
        List<String> p = new ArrayList<String>(ALWAYS_PARENT);
        p.addAll(extraParentPrefixes);
        this.alwaysParent = Collections.unmodifiableList(p);

        resetLaunchWrapperExclusions();
        // our own classes are release-8 but never need transforming; ASM 5.0.3 must not see them
        addTransformerExclusion("dev.umb.legacy.");
    }

    /**
     * Replace LaunchWrapper's Java-8-era classLoaderExceptions with the set that matches the
     * delegation policy above. Reflection is required because the field is private, but it is a
     * plain application class so no --add-opens is involved.
     */
    @SuppressWarnings("unchecked")
    private void resetLaunchWrapperExclusions() {
        try {
            Field f = LaunchClassLoader.class.getDeclaredField("classLoaderExceptions");
            f.setAccessible(true);
            Set<String> ex = (Set<String>) f.get(this);
            ex.clear();
            ex.addAll(alwaysParent);
        } catch (Exception e) {
            throw new IllegalStateException("cannot reset LaunchClassLoader.classLoaderExceptions", e);
        }
    }

    /**
     * Overridden to redirect into {@link #addTransformerExclusion}: this loader is the sole
     * authority over which names are parent-delegated ({@link #ALWAYS_PARENT}, fixed at
     * construction) - a caller-supplied prefix never ADDS to that set - but the caller's underlying
     * INTENT ("this package must be its own island, exempt from the mod-instrumentation pipeline")
     * is honored anyway, through the one mechanism that is actually safe in this era's
     * single-isolated-loader design.
     *
     * <p>Real {@code LaunchClassLoader} normally lets any code holding a reference to it call this
     * to add MORE parent-delegated prefixes, and real coremods use exactly that (Sponge Mixin's own
     * bootstrap calls it for its own packages). That is the right call in a REAL Forge launch, where
     * the coremod's jar sits on the actual JVM launch classpath and the running game's own
     * {@code LaunchClassLoader} never defines those classes either - so parent-delegating them loses
     * nothing. Neither premise holds here: this era's isolated universe has no javaagent and no
     * launch-classpath overlap, so a coremod's jar is ONLY ever reachable through this loader's own
     * addURL'd sources - honoring a real exclusion call would route those names to a parent that has
     * no such classes at all (proven live on the 1.12.2 module in this exact shape, see {@code
     * a plain no-op left the loader as sole definer but then let that coremod's own newly-registered
     * transformer re-enter this same loader's {@code findClass()} for one of its own
     * still-being-defined classes, a {@code ClassCircularityError}).
     *
     * <p>Redirecting to {@link #addTransformerExclusion} closes both windows at once: the excluded
     * prefix stays off {@code classLoaderExceptions} (so this loader remains the sole definer,
     * preserving identity) but is fed into the transformer-skip list instead (so this loader defines
     * the class WITHOUT running any registered transformer - including the coremod's own - against
     * it, which is what a real launch's classpath split actually achieves). Universal by
     * construction: this override does not name any coremod or mod id - whatever prefix a caller
     * asks to keep off the transform pipeline gets kept off it, regardless of who asked.
     */
    @Override
    public void addClassLoaderExclusion(String toExclude) {
        addTransformerExclusion(toExclude);
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
                } else if (owns(name)) {
                    // LaunchClassLoader.findClass: getClassBytes + runTransformers + defineClass
                    c = findClass(name);
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
}
