package dev.umb.legacy1122.legacyside;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
 * file) and duck-types the plugin object - it only needs a public no-arg constructor and public
 * no-arg {@code String[] getASMTransformerClass()}/{@code String getModContainerClass()} methods,
 * the same members every real {@code IFMLLoadingPlugin} implementation has, without requiring this
 * module to compile against that interface.</p>
 */
final class Legacy1122CoremodLoader {

    private Legacy1122CoremodLoader() {
    }

    /**
     * The two things {@link #discoverAndRegister} finds, kept separate because
     * {@link Legacy1122Lifecycle} does two different things with them: {@code plugins} is logged,
     * {@code modContainerClasses} gets appended to {@code FMLInjectionData.containers} (see
     * {@link Legacy1122Lifecycle#run}'s own seeding of {@code FMLContainer}/{@code ForgeModContainer}
     * - this is the exact same list, just with third-party entries added to the two hardcoded ones).
     */
    static final class Discovery {
        final List<String> plugins;
        final List<String> modContainerClasses;

        Discovery(List<String> plugins, List<String> modContainerClasses) {
            this.plugins = plugins;
            this.modContainerClasses = modContainerClasses;
        }
    }

    /**
     * Scans {@code modJars} for an {@code FMLCorePlugin} manifest attribute, adds any hit to the
     * loader's own sources (so the plugin class - and later the mod's own classes - resolve through
     * the SAME loader whose transformer chain the plugin is about to extend), instantiates it, and
     * registers every class name {@code getASMTransformerClass()} returns. Also collects
     * {@code getModContainerClass()}'s result, if the plugin declares one - see {@link Discovery}'s
     * own javadoc for why this is not a third, independent thing this class does, but the missing
     * half of "give a coremod-only mod (no separate {@code @Mod} class of its own) a real FML
     * identity", proven live to matter: {@code universal-tweaks}/{@code packet-fixer} both declare a
     * real {@code @Mod(dependencies="required-after:mixinbooter...")} check, which real
     * {@code Loader.sortModList()} validates against {@code Loader}'s OWN discovered
     * {@code ModContainer}s - MixinBooter's manifest has no {@code FMLCorePluginContainsFMLMod}
     * attribute (confirmed: `unzip -p` on its `META-INF/MANIFEST.MF`), so it has no separate
     * {@code @Mod} class for normal discovery to find; without wiring
     * {@code getModContainerClass()}'s result into {@code FMLInjectionData.containers} the way real
     * {@code CoreModManager} does, both crash with {@code MissingModsException: ... requires
     * [mixinbooter...]} even though MixinBooter's own coremod plugin loaded and ran successfully.
     * A coremod that fails to load or register is logged and skipped, never thrown - one broken
     * coremod must not abort the whole boot, matching real FML's own tolerance.
     *
     * @return the plugin class names and mod-container class names that were found, for
     *         logging/tests and for {@link Legacy1122Lifecycle} to inject respectively.
     */
    static Discovery discoverAndRegister(ClassLoader loader, List<File> modJars, Consumer<String> log)
            throws Exception {
        Class<?> launchLoaderType = Class.forName("net.minecraft.launchwrapper.LaunchClassLoader", true, loader);
        Method addUrl = launchLoaderType.getMethod("addURL", URL.class);
        Method registerTransformer = launchLoaderType.getMethod("registerTransformer", String.class);
        List<String> plugins = new ArrayList<String>();
        List<String> modContainerClasses = new ArrayList<String>();
        // Real FML's CoreModManager discovers coremods by scanning the mods/coremods directories,
        // whose listing order is filesystem/alphabetical - not whatever order a caller happens to
        // pass jars in. Modpacks rely on this: a coremod that must bootstrap before another (a
        // shared Mixin/ASM environment, e.g.) gets its jar prefixed "!" specifically so it sorts
        // first (this project's own pool file is literally named "!mixinbooter-11.17.jar" for
        // exactly that reason). Proven live: with modJars in "picked mod first, its deps after"
        // order (this harness's own bookkeeping order, unrelated to filename), ReplayMod's own
        // coremod ran BEFORE MixinBooter's, called org.spongepowered.asm.mixin.Mixins
        // .addConfiguration() from its OWN bundled copy of Sponge Mixin before MixinBootstrap.init()
        // had ever run ("MixinException: Environment conflict, mismatched versions or you didn't
        // call MixinBootstrap.init()"), which then left the shared org.spongepowered.asm.util.asm
        // .ASM class permanently bound to ReplayMod's bundled version - so when MixinBooter's OWN
        // coremod ran next, its own code calling a method only ITS bundled ASM utility class has
        // failed with NoSuchMethodError. Sorting jars by filename before scanning - the same
        // ordering a real mods folder gives real FML - runs MixinBooter (an inherently
        // alphabetically-early filename by the same convention every affected mod already follows)
        // first, avoiding both failures. Universal by construction: sorts by filename alone, reads
        // no mod id or class name.
        List<File> sorted = new ArrayList<File>(modJars);
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
                String containerClass = modContainerClass(pluginClass, plugin);
                if (containerClass != null && !containerClass.trim().isEmpty()) {
                    modContainerClasses.add(containerClass.trim());
                    log.accept("UMB-BRIDGE-1122 coremod " + name + " (" + jar.getName()
                            + ") declared mod container " + containerClass.trim());
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
        return new Discovery(plugins, modContainerClasses);
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

    /**
     * {@code getModContainerClass()} is optional on real {@code IFMLLoadingPlugin} - most coremods
     * return {@code null} (they either have a separate real {@code @Mod} class, or genuinely want no
     * FML identity of their own). Duck-typed the same way {@code getASMTransformerClass()} is: a
     * plugin class that has no such method (an older or minimal {@code IFMLLoadingPlugin}) is
     * treated exactly like one that returns {@code null} - no container, not an error.
     */
    private static String modContainerClass(Class<?> pluginClass, Object plugin) throws Exception {
        for (Method m : pluginClass.getMethods()) {
            if (m.getName().equals("getModContainerClass") && m.getParameterTypes().length == 0) {
                Object result = m.invoke(plugin);
                return result instanceof String ? (String) result : null;
            }
        }
        return null;
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
