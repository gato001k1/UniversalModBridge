package dev.umb.hostagent.content;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * The middle tier of the G2 embedding's three-classloader stack (see {@link UmbUniverse}'s
 * javadoc for the full account of why there are three, not one or two):
 *
 * <pre>
 *   tier 1: the running 26.2 client's own system/app classloader
 *           (client.jar, umb-hostagent.jar -- which bundles dev.umb.bridge.api, jopt-simple,
 *            umb-legacy-{boot,api} alongside its own classes; HostAgent adds the official
 *            LaunchWrapper jar to this loader at premain)
 *                              |  parent
 *                              v
 *   tier 2: THIS loader -- owns ONLY the legacy universe's log4j 2.0-beta9 (log4j-api + log4j-core)
 *                              |  parent
 *                              v
 *   tier 3: dev.umb.legacy.boot.LegacyLoader -- the legacy 1.7.10/FML universe
 * </pre>
 *
 * <p>Why log4j and nothing else needs isolating here: the running 26.2 client almost certainly
 * ships its own (much newer) log4j on tier 1's classpath under the same {@code org.apache.logging}
 * package. {@code LegacyLoader} parent-delegates that package (it has to -- FML's own
 * {@code LogWrapper} plumbing needs exactly one log4j, see {@code LegacyLoader}'s javadoc), and a
 * plain {@link URLClassLoader} is parent-FIRST by default, so without this tier the legacy
 * universe would silently get tier 1's modern log4j instead of the 2.0-beta9 it was built against
 * -- a version FML's log4j usage is not guaranteed to be compatible with. This loader flips that:
 * it checks its OWN two jars first, and falls through to tier 1 for everything else (including
 * {@code net.minecraft.launchwrapper.*} and {@code dev.umb.bridge.api.*}, which are safe to share
 * with tier 1 -- see {@link UmbUniverse} for why sharing {@code dev.umb.bridge.api} specifically is
 * not just safe but REQUIRED).</p>
 */
final class UniverseRootLoader extends URLClassLoader {

    UniverseRootLoader(URL[] log4jJars, ClassLoader tier1Parent) {
        super("umb-universe-root", log4jJars, tier1Parent);
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> c = findLoadedClass(name);
            if (c == null) {
                if (owns(name)) {
                    c = findClass(name);
                } else {
                    c = getParent().loadClass(name);
                }
            }
            if (resolve) {
                resolveClass(c);
            }
            return c;
        }
    }

    /** true when this loader's own two log4j jars contain the class file for {@code name}. */
    private boolean owns(String name) {
        return findResource(name.replace('.', '/').concat(".class")) != null;
    }
}
