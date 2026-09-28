package dev.umb.pipeline.bridge;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

/**
 * Mod classloader — Bridge v1 entrypoint loading. A URLClassLoader over the mod jar
 * whose PARENT is the {@link HostUniverse}'s loader, so the classloader shape is:
 * {@code mod ⟵ host universe (host jar + libs) ⟵ platform ⟵ bootstrap}. The mod sees
 * the host universe and its own classes; it NEVER sees the UMB app classes, because the
 * app loader is not on the parent chain.
 *
 * <p>Honest load: {@link #entrypointClass} throws {@link ClassNotFoundException} (or a
 * LinkageError for an unloadable supertype chain) on a name this loader cannot serve —
 * never null, never a stand-in — and uses {@code initialize=false}, so no static
 * initializers run at load (the same discipline as {@link SmokeLoader} and
 * {@link HostUniverse}).
 *
 * <p>Lifecycle: {@link LifecycleDriver} consumes classes from this loader; close the
 * loader only after every loaded entrypoint is done running.
 */
public final class ModLoader implements AutoCloseable {

    private final URLClassLoader loader;

    public ModLoader(Path modJar, HostUniverse universe) throws IOException {
        this.loader = new URLClassLoader(new URL[]{modJar.toUri().toURL()}, universe.loader());
    }

    /**
     * @param binaryName dotted binary name, e.g. {@code q.Entrypoint}
     * @return the loaded class
     * @throws ClassNotFoundException the class does not exist below this loader
     */
    public Class<?> entrypointClass(String binaryName) throws ClassNotFoundException {
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