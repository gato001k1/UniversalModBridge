package dev.umb.pipeline.bridge;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Bridge-stage handle on the SmokeLoader-shaped host classloader: a URLClassLoader over
 * the host jar + lib jars standing on the PLATFORM classloader, so the host universe sees
 * the real JDK and its own libraries but never the app classes carrying UMB. Classes load
 * lazily; nothing is defined until a name is asked for.
 *
 * <p>Honest failure: {@link #hostClass} throws {@link ClassNotFoundException} (or a
 * LinkageError for an unlinkable supertype chain) on a name this universe cannot load —
 * it never returns null and never fabricates a stand-in. {@link Materializer} converts
 * those into the named {@link MaterializationException}.
 *
 * <p>Lifecycle: a materialized object holds instances defined by this loader, so the
 * universe must stay open as long as any {@link Materialized} result is reachable. Close
 * it only when all materialized proxies and host instances have been discarded.
 */
public final class HostUniverse implements AutoCloseable {

    private final URLClassLoader loader;

    public HostUniverse(Path hostJar, List<Path> libJars) throws IOException {
        List<URL> urls = new ArrayList<>(libJars.size() + 1);
        urls.add(hostJar.toUri().toURL());
        for (Path lib : libJars) {
            urls.add(lib.toUri().toURL());
        }
        this.loader = new URLClassLoader(urls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
    }

    /**
     * @param binaryName dotted binary name, e.g. {@code net.minecraft.core.BlockPos}
     * @return the loaded class
     * @throws ClassNotFoundException the class does not exist below this loader
     */
    public Class<?> hostClass(String binaryName) throws ClassNotFoundException {
        // initialize=false: structural load (define + superclass chain), no static
        // initializers — the same discipline SmokeLoader applies to the smoke stage.
        return Class.forName(binaryName, false, loader);
    }

    public ClassLoader loader() {
        return loader;
    }

    @Override
    public void close() throws IOException {
        loader.close();
    }
}