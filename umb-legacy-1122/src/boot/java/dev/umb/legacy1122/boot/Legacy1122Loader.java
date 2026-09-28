package dev.umb.legacy1122.boot;

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
 * The 1.12.2 legacy universe classloader - the ONLY piece of {@code umb-legacy-1122/boot} that is
 * not new design: it is the 1.7.10 module's {@code LegacyLoader} with the package renamed. Both
 * eras are LaunchWrapper + FML, both need the SAME two Java-9+ compatibility fixes (LaunchWrapper
 * was written for Java 8: its {@code super(sources, null)} makes the bootstrap loader its parent,
 * hiding every platform module; its hardcoded {@code classLoaderExceptions} assume the JDK's own
 * jars sit on the application classpath, which is false once everything is embedded), and both
 * need the SAME child-first-by-ownership delegation policy so the identical class survives the
 * move from a bare JVM into the 26.2 client's own classloader graph. See ERA-1122-PLAN.md
 * "loader generation" for the evidence that both eras share this exact loader shape.
 *
 * <p>Delegation policy (identical structure to 1.7.10's, new package prefixes):</p>
 * <pre>
 *   java. / jdk. / sun. / com.sun.        -&gt; parent, always
 *   net.minecraft.launchwrapper.          -&gt; parent, always (FML casts to the HOST's LaunchClassLoader)
 *   org.apache.logging.                   -&gt; parent, always (LogWrapper mentions log4j.Level; see below)
 *   dev.umb.legacy1122.api. / .boot.      -&gt; parent, always (this era's plain-data handshake)
 *   dev.umb.bridge.api.                   -&gt; parent, always (the SHARED cross-era boundary contract)
 *   anything whose .class is in OUR jars  -&gt; CHILD FIRST, through the transformer pipeline
 *   everything else                       -&gt; parent fallback
 * </pre>
 *
 * <p><b>log4j version note (new for this era, not a copy-paste of the 1.7.10 comment):</b> 1.7.10's
 * shared log4j is 2.0-beta9. The real Forge 1.12.2 universal jar's manifest Class-Path pins
 * log4j-api/log4j-core 2.15.0 (verified: {@code META-INF/MANIFEST.MF} inside the fetched
 * {@code forge-1.12.2-14.23.5.2860-universal.jar}), newer than the vanilla library set's bundled
 * 2.8.1. {@link Legacy1122Classpath}'s manifest keeps only the 2.15.0 pair for that reason - the
 * same "one shared log4j" rule as 1.7.10, just a different pinned version.</p>
 */
public final class Legacy1122Loader extends LaunchClassLoader {

    private static final List<String> ALWAYS_PARENT = Collections.unmodifiableList(Arrays.asList(
            "java.",
            "jdk.",
            "sun.",
            "com.sun.",
            "net.minecraft.launchwrapper.",
            "org.apache.logging.",
            "dev.umb.legacy1122.api.",
            "dev.umb.legacy1122.boot.",
            "dev.umb.bridge.api."));

    private final ClassLoader delegateParent;
    private final List<String> alwaysParent;
    private final ConcurrentHashMap<String, Boolean> ownsCache = new ConcurrentHashMap<String, Boolean>();

    public Legacy1122Loader(URL[] sources, ClassLoader delegateParent) {
        this(sources, delegateParent, Collections.<String>emptyList());
    }

    public Legacy1122Loader(URL[] sources, ClassLoader delegateParent, List<String> extraParentPrefixes) {
        super(sources);
        if (delegateParent == null) {
            throw new IllegalArgumentException("the legacy loader needs a real parent loader");
        }
        this.delegateParent = delegateParent;
        List<String> p = new ArrayList<String>(ALWAYS_PARENT);
        p.addAll(extraParentPrefixes);
        this.alwaysParent = Collections.unmodifiableList(p);
        resetLaunchWrapperExclusions();
    }

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
                } else if (name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")
                        || name.startsWith("cpw.mods.") || owns(name)) {
                    // Vanilla is obfuscated on disk, so owns(real SRG name) is false. The
                    // LaunchClassLoader rename transformer must see these requests before the
                    // child/parent decision; otherwise Forge signatures fall through to the
                    // host and fail on dependencies such as net.minecraft.crash.*.
                    try {
                        c = findClass(name);
                    } catch (ClassNotFoundException missingFromLegacyUniverse) {
                        if (name.startsWith("net.minecraft.") || name.startsWith("net.minecraftforge.")
                                || name.startsWith("cpw.mods.")) {
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
}
