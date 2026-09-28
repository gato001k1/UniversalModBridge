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
 * <p>It MUST be a {@link LaunchClassLoader}: {@code cpw.mods.fml.common.Loader}'s constructor does {@code new ModClassLoader(getClass().getClassLoader())} and {@code ModClassLoader} casts that parent to {@code...
 */
public final class LegacyLoader extends LaunchClassLoader {

    /**
 * Prefixes that must resolve in the parent even if we happen to ship a copy.
 * <p>{@code dev.umb.bridge.api.} is the G2 boundary contract : a jar of plain {@code java.*}-only interfaces/classes loaded by the universe-root loader and shared with the 26.2 host...
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
