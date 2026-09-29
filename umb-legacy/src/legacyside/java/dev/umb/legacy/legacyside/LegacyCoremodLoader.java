package dev.umb.legacy.legacyside;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.minecraft.launchwrapper.LaunchClassLoader;

/**
 * Discovers third-party FMLCorePlugin coremods (CodeChickenCore, NotEnoughItems' own plugin,
 * OpenComputers' {@code TransformerLoader}, SerializationIsBad, Mekanism's {@code asm.LoadingHook},
 * ...) among the jars already on the isolated {@link LaunchClassLoader}'s classpath, and registers
 * every ASM transformer class each plugin declares BEFORE any of those jars' own classes are
 * touched.
 *
 * <p>{@link LegacyDriver#boot}'s own javadoc table used to say this step is skipped outright:
 * "{@code CoreModManager.handleLaunch} SKIPPED. It registers PatchingTransformer (our runtime jar
 * is already binpatched), discovers coremods (HBM has none) ...". That was true for HBM, the one mod
 * this driver was built against, and false for every coremod-bearing mod the surprise-test corpus
 * each plugin's transformer during its own tweaker chain, long before any target class verifies;
 * skipping it meant every such class verified against UNPATCHED bytecode and threw whatever
 * NoSuchMethodError/NoSuchFieldError/ClassNotFoundException the coremod's own transformer exists to
 * avoid - exactly why run-headless.ps1's Skip-Reason gave up on those jars before ever attempting a
 * boot. This class is the fix.</p>
 *
 * <p>Universal by construction: it scans every URL the loader already has (mods AND the
 * forge/vanilla/library jars that never declare the attribute), the same way real FML's
 * {@code CoreModManager} scans everything in the mods + coremods folders without caring what it
 * finds - no mod id, class, or jar name is referenced anywhere in this file. The plugin object is
 * duck-typed (a public no-arg constructor plus a public no-arg
 * {@code String[] getASMTransformerClass()} method, the two members every real
 * {@code IFMLLoadingPlugin} has) so this module does not need to compile against that interface,
 * matching the reflection style {@code LegacyBridgeImpl}/{@code LegacyDriver} already use for
 * anything not on their direct compile classpath.</p>
 */
final class LegacyCoremodLoader {

    private LegacyCoremodLoader() {
    }

    /**
     * @return the plugin class names that were found and asked to register transformers, for
     *         logging/tests. A coremod that fails to load or register is logged and skipped, never
     *         thrown - one broken coremod must not abort the whole boot, matching real FML's own
     *         tolerance for a bad coremod.
     */
    static List<String> discoverAndRegister(LaunchClassLoader lcl, Consumer<String> log) {
        List<String> discovered = new ArrayList<String>();
        // Real FML's CoreModManager discovers coremods by scanning the mods/coremods directories,
        // whose listing order is filesystem/alphabetical - not the order addURL happened to be
        // called in (this project's own boot sequence, unrelated to filename). Modpacks rely on
        // this: a coremod that must bootstrap before another (a shared Mixin/ASM environment,
        // e.g.) gets its jar prefixed "!" specifically so it sorts first (mods.json's own
        // "!mixinbooter-11.17.jar" pool entry exists for exactly that reason). Proven live on the
        // with jars scanned in caller order instead of filename order, one coremod's own bundled
        // library initialized before the coremod it depended on had a chance to bootstrap the
        // shared copy, corrupting a static/singleton the second coremod's own code then failed
        // against. Sorting by filename before scanning - the same ordering a real mods folder
        // gives real FML - closes that class of bug here too, before this era has its own
        // Mixin-bootstrapping coremod pair to trip over it. Universal by construction: sorts by
        // filename alone, reads no mod id or class name.
        List<File> sorted = new ArrayList<File>();
        for (URL url : lcl.getURLs()) {
            File jar = toFile(url);
            if (jar != null && jar.isFile()) {
                sorted.add(jar);
            }
        }
        Collections.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File jar : sorted) {
            String pluginClassName = readCoreModPluginAttribute(jar);
            if (pluginClassName == null || pluginClassName.trim().isEmpty()) {
                continue;
            }
            String name = pluginClassName.trim();
            try {
                Class<?> pluginClass = Class.forName(name, true, lcl);
                Object plugin = pluginClass.getDeclaredConstructor().newInstance();
                for (String transformer : asmTransformerClasses(pluginClass, plugin)) {
                    if (transformer == null || transformer.trim().isEmpty()) {
                        continue;
                    }
                    int before = lcl.getTransformers().size();
                    lcl.registerTransformer(transformer.trim());
                    if (lcl.getTransformers().size() > before) {
                        log.accept("[umb-legacy] coremod " + name + " (" + jar.getName()
                                + ") registered transformer " + transformer.trim());
                    } else {
                        // LaunchClassLoader.registerTransformer swallows every exception.
                        log.accept("[umb-legacy] coremod " + name + " (" + jar.getName()
                                + ") transformer " + transformer.trim() + " failed to register");
                    }
                }
                discovered.add(name);
            } catch (Throwable failure) {
                Throwable cause = failure;
                while (cause.getCause() != null && cause.getCause() != cause) {
                    cause = cause.getCause();
                }
                log.accept("[umb-legacy] coremod " + name + " (" + jar.getName()
                        + ") failed to load: " + cause);
            }
        }
        return discovered;
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

    private static File toFile(URL url) {
        try {
            return new File(url.toURI());
        } catch (URISyntaxException | IllegalArgumentException badUri) {
            String path = url.getPath();
            return path == null ? null : new File(path);
        }
    }
}
