package dev.umb.legacy1122.legacyside;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Discovers third-party FMLCorePlugin coremods (CodeChickenCore/NEI-style plugins, MixinBooter's
 * own bootstrap, EnderCore, Forgelin, FoamFix, Mekanism's {@code asm.LoadingHook}, ...) among the
 * mod jars a boot was given, and registers each plugin's declared ASM transformer classes on the
 * isolated {@code Legacy1122Loader} BEFORE any of those jars' own classes are touched.
 *
 * <p>Real FML's {@code CoreModManager.handleLaunch} does this during the tweaker chain, long before
 * {@code Loader.loadMods()} runs. {@link Legacy1122Lifecycle} never drove that machinery (it hand-
 * seeds only the two Forge/Minecraft root containers and the FML-internal transformer list - see
 * its {@code installForgeTransformers}), so every coremod-bearing jar used to verify against
 * UNPATCHED bytecode and throw whatever NoSuchMethodError/NoSuchFieldError/ClassNotFoundException
 * its own transformer exists to avoid - the surprise-test corpus's run-headless.ps1 skipped those
 * jars outright for exactly that reason (Skip-Reason: "FMLCorePlugin coremod ... unsupported").
 * This class is the fix: the same mechanism the 1.7.10 module's {@code LegacyCoremodLoader} adds,
 * ported to this era's reflection style (this module does not compile against the real Forge
 * classes - see {@link Legacy1122Lifecycle}'s own javadoc).</p>
 *
 * <p>Universal by construction: it reads the {@code FMLCorePlugin} manifest attribute out of
 * whichever jars it is given (no mod id, class name, or jar name is referenced anywhere in this
 * file) and duck-types the plugin object - it only needs a public no-arg constructor and a public
 * no-arg {@code String[] getASMTransformerClass()} method, the same two members every real
 * {@code IFMLLoadingPlugin} implementation has, without requiring this module to compile against
 * that interface.</p>
 */
final class Legacy1122CoremodLoader {

    private Legacy1122CoremodLoader() {
    }

    /**
     * Scans {@code modJars} for an {@code FMLCorePlugin} manifest attribute, adds any hit to the
     * loader's own sources (so the plugin class - and later the mod's own classes - resolve through
     * the SAME loader whose transformer chain the plugin is about to extend), instantiates it, and
     * registers every class name {@code getASMTransformerClass()} returns. A coremod that fails to
     * load or register is logged and skipped, never thrown - one broken coremod must not abort the
     * whole boot, matching real FML's own tolerance.
     *
     * @return the plugin class names that were found, for logging/tests.
     */
    static List<String> discoverAndRegister(ClassLoader loader, List<File> modJars, Consumer<String> log)
            throws Exception {
        Class<?> launchLoaderType = Class.forName("net.minecraft.launchwrapper.LaunchClassLoader", true, loader);
        Method addUrl = launchLoaderType.getMethod("addURL", URL.class);
        Method registerTransformer = launchLoaderType.getMethod("registerTransformer", String.class);
        List<String> plugins = new ArrayList<String>();
        for (File jar : modJars) {
            String pluginClassName = readCoreModPluginAttribute(jar);
            if (pluginClassName == null || pluginClassName.trim().isEmpty()) {
                continue;
            }
            String name = pluginClassName.trim();
            try {
                addUrl.invoke(loader, jar.toURI().toURL());
                Class<?> pluginClass = Class.forName(name, true, loader);
                Object plugin = pluginClass.getDeclaredConstructor().newInstance();
                for (String transformer : asmTransformerClasses(pluginClass, plugin)) {
                    if (transformer == null || transformer.trim().isEmpty()) {
                        continue;
                    }
                    registerTransformer.invoke(loader, transformer.trim());
                    log.accept("UMB-BRIDGE-1122 coremod " + name + " (" + jar.getName()
                            + ") registered transformer " + transformer.trim());
                }
                plugins.add(name);
            } catch (Throwable failure) {
                Throwable cause = failure;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                // A full trace, not just the exception's toString(): this one line has already
                // paid for itself twice (surrogate-vs-null "tweaks" list, a Gson/ASM classloader
                // split - see Legacy1122Lifecycle's own javadoc for both) - a bare toString() would
                // have named the exception but not WHERE, leaving the actual fix to guesswork.
                StackTraceElement[] frames = cause.getStackTrace();
                StringBuilder where = new StringBuilder();
                for (int i = 0; i < Math.min(6, frames.length); i++) {
                    where.append(" | at ").append(frames[i]);
                }
                log.accept("UMB-BRIDGE-1122 coremod " + name + " (" + jar.getName()
                        + ") failed to load: " + cause + where);
            }
        }
        return plugins;
    }

    private static String[] asmTransformerClasses(Class<?> pluginClass, Object plugin) throws Exception {
        for (Method m : pluginClass.getMethods()) {
            if (m.getName().equals("getASMTransformerClass") && m.getParameterTypes().length == 0) {
                Object result = m.invoke(plugin);
                return result instanceof String[] ? (String[]) result : new String[0];
            }
        }
        return new String[0];
    }

    /** Static manifest read only - no class of the jar is loaded by this method. */
    static String readCoreModPluginAttribute(File jar) {
        try (JarFile jf = new JarFile(jar)) {
            Manifest mf = jf.getManifest();
            if (mf == null) {
                return null;
            }
            return mf.getMainAttributes().getValue("FMLCorePlugin");
        } catch (IOException notAJar) {
            return null;
        }
    }
}
