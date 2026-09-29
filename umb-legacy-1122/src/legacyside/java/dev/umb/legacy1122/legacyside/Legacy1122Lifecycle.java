package dev.umb.legacy1122.legacyside;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;

/**
 * The real 1.12.2 FML lifecycle, driven from inside the isolated LaunchClassLoader.
 * Every Forge/Minecraft type is resolved reflectively so the legacyside compile remains
 * independent of a second copy of the 1.12.2 universe classes.  The sequence is the one
 * proven by Boot1122TransformProbeMain: FML injection, beginLoading, loadMods, preinit,
 * GameData registry events, and initializeMods (which owns POSTINITIALIZATION in Forge 14).
 */
final class Legacy1122Lifecycle {
    static final class Result {
        final int modCount;
        final List<String> stages = new ArrayList<String>();
        Result(int modCount) { this.modCount = modCount; }
        boolean ok() { return !stages.isEmpty() && stages.get(stages.size() - 1).equals("initializeMods"); }
    }

    private Legacy1122Lifecycle() { }

    static Result run(ClassLoader loader, List<File> modJars, File gameDir,
                      File forgeJar, Consumer<String> log) throws Exception {
        if (!gameDir.isDirectory() && !gameDir.mkdirs()) {
            throw new IOException("cannot create 1.12.2 game directory " + gameDir);
        }
        File mods = new File(gameDir, "mods");
        if (!mods.isDirectory() && !mods.mkdirs()) throw new IOException("cannot create " + mods);
        for (File jar : modJars) {
            // A coremod-bearing jar is instead addURL'd straight onto the loader by
            // Legacy1122CoremodLoader below, so FML's own ModDiscoverer.findClasspathMods sees it
            // there. ALSO copying it into gameDir/mods would let findModDirMods claim the same mod
            // id a second time - reproduced live: Loader.identifyDuplicates threw
            // DuplicateModsFoundException for OpenComputers the first time this ran a real coremod
            // through the isolated lifecycle. Real FML never double-discovers a coremod jar either
            // (CoreModManager tracks what it already claimed); this is the same rule, just decided
            // up front from the manifest instead of a shared "already claimed" set.
            if (Legacy1122CoremodLoader.readCoreModPluginAttribute(jar) != null) {
                continue;
            }
            Files.copy(jar.toPath(), new File(mods, jar.getName()).toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Thread thread = Thread.currentThread();
        ClassLoader oldTccl = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            installForgeTransformers(loader, forgeJar, log);
            // Third-party FMLCorePlugin coremods (CodeChickenCore/NEI-style plugins, EnderCore,
            // Forgelin, FoamFix, Mekanism's asm.LoadingHook, ...) must register their ASM
            // transformers before any of their own jar's classes verify - see
            // Legacy1122CoremodLoader's own javadoc for why this was missing and what real FML does
            // here. Must run before FMLInjectionData/loadMods touch any mod class below.
            // A coremod's own constructor can call LaunchClassLoader.addClassLoaderExclusion on
            // itself while discoverAndRegister below runs it (proven live: MixinBooter's
            // MixinBooterPlugin.initialize() -> installClassLoaderExclusionsAndTransformers adds
            // org.spongepowered.asm.* / org.objectweb.asm. / zone.rong.mixinbooter.service. to
            // exactly that set, assuming - as it would in a real Forge launch with a javaagent or
            // launch-classpath overlap - that "parent" also has those classes; here it does not, so
            // every one of those loads a coremod like this needs turns into a ClassNotFoundException
            // and its own bootstrap, MixinBootstrap.init() in MixinBooter's case, never runs; see
            // Legacy1122Loader.addClassLoaderExclusion's own javadoc for why that override - not a
            // reset called from here after the fact - is what actually closes the window: the
            // exclusion call and the broken class load it causes both happen inside the SAME
            // coremod constructor invocation below, before this method ever gets control back).
            Legacy1122CoremodLoader.Discovery coremods = Legacy1122CoremodLoader.discoverAndRegister(loader, modJars, log);
            if (!coremods.plugins.isEmpty()) {
                log.accept("UMB-BRIDGE-1122 coremods discovered: " + coremods.plugins);
            }
            Class<?> injection = Class.forName("net.minecraftforge.fml.relauncher.FMLInjectionData", true, loader);
            Field containers = injection.getField("containers");
            Object raw = containers.get(null);
            if (raw instanceof List) {
                List list = (List) raw;
                list.clear();
                list.add("net.minecraftforge.fml.common.FMLContainer");
                list.add("net.minecraftforge.common.ForgeModContainer");
                // Real CoreModManager wraps a coremod-declared getModContainerClass() into an
                // Loader.identifyMods(): it does `injectedContainers.addAll(...)`, then
                // `Class.forName(name, true, modClassLoader).newInstance()` on every entry, wraps
                // each result in `new InjectedModContainer(container, container.getSource())`, and
                // adds it to Loader.mods - the exact same list FMLContainer/ForgeModContainer just
                // went into above). Without this, a coremod-only mod (no separate @Mod class of its
                // own - MixinBooter's manifest has no FMLCorePluginContainsFMLMod, confirmed) never
                // becomes a real, dependency-checkable ModContainer, so any mod declaring
                // @Mod(dependencies="required-after:mixinbooter...") fails Loader.sortModList()
                // with MissingModsException even though MixinBooter's own coremod loaded and ran
                // fine (proven live: universal-tweaks, packet-fixer). Universal by construction:
                // whatever getModContainerClass() a coremod declares gets added here, not a
                // hardcoded mixinbooter special case.
                list.addAll(coremods.modContainerClasses);
            }
            Method build = findMethod(injection, "build", 2);
            build.setAccessible(true);
            build.invoke(null, gameDir, loader);

            Class<?> sanity = Class.forName("net.minecraftforge.fml.common.asm.FMLSanityChecker", true, loader);
            sanity.getField("fmlLocation").set(null, forgeJar);
            Class<?> loaderType = Class.forName("net.minecraftforge.fml.common.Loader", true, loader);
            Method inject = loaderType.getMethod("injectData", Object[].class);
            inject.invoke(null, new Object[]{injection.getMethod("data").invoke(null)});

            // Minecraft.startGame populates the vanilla registries before Forge begins
            // loading. In 1.12.2 OreDictionary.initVanillaEntries() touches Blocks
            // during FMLCommonHandler.beginLoading(), so omitting this call makes the
            // isolated lifecycle fail with "Accessed Blocks before Bootstrap!".
            invokeVanillaBootstrap(loader);

            Class<?> commonType = Class.forName("net.minecraftforge.fml.common.FMLCommonHandler", true, loader);
            Object common = commonType.getMethod("instance").invoke(null);
            Class<?> sidedType = Class.forName("net.minecraftforge.fml.common.IFMLSidedHandler", true, loader);
            // Lazy: by the first getDataFixer() call (initializeMods) the AccessTransformer is
            // registered, so Forge's CompoundDataFixer can reach the widened vanilla fields.
            Object[] dataFixer = new Object[1];
            Object sided = Proxy.newProxyInstance(loader, new Class<?>[]{sidedType}, (p, m, a) -> {
                String name = m.getName();
                if ("getSide".equals(name)) {
                    Class<?> side = Class.forName("net.minecraftforge.fml.relauncher.Side", true, loader);
                    return Enum.valueOf((Class) side, "SERVER");
                }
                if ("getAdditionalBrandingInformation".equals(name)) return Collections.emptyList();
                if ("getSavesDirectory".equals(name)) return gameDir;
                if ("getCurrentLanguage".equals(name)) return "en_us";
                if ("stripSpecialChars".equals(name)) return a == null ? "" : a[0];
                if ("getDataFixer".equals(name)) {
                    if (dataFixer[0] == null) dataFixer[0] = makeDataFixer(loader, log);
                    return dataFixer[0];
                }
                Class<?> rt = m.getReturnType();
                if (rt == boolean.class) return Boolean.FALSE;
                if (rt == int.class) return Integer.valueOf(0);
                if (rt == long.class) return Long.valueOf(0L);
                if (rt == float.class) return Float.valueOf(0.0f);
                if (rt == double.class) return Double.valueOf(0.0d);
                return null;
            });
            commonType.getMethod("beginLoading", sidedType).invoke(common, sided);

            Object fml = loaderType.getMethod("instance").invoke(null);
            loaderType.getMethod("loadMods", List.class).invoke(fml, Collections.emptyList());
            Result result = new Result(((List) loaderType.getMethod("getModList").invoke(fml)).size());
            result.stages.add("construct");
            log.accept("UMB-BRIDGE-1122 lifecycle loadMods=OK modCount=" + result.modCount);
            try {
                loaderType.getMethod("preinitializeMods").invoke(fml);
                result.stages.add("preInitializeMods");
            } catch (Throwable preFailure) {
                // Java 21 removed ReflectionFactory.newFieldAccessor, which Forge's
                // ObjectHolder pass calls.  RegistryEvent delivery itself is still real;
                // use Forge's own dispatcher and keep the failure visible in the log.
                Throwable cause = unwrap(preFailure);
                log.accept("UMB-BRIDGE-1122 preInitializeMods wall=" + cause);
                Class<?> gameData = Class.forName("net.minecraftforge.registries.GameData", true, loader);
                gameData.getMethod("fireRegistryEvents").invoke(null);
                result.stages.add("preInitializeMods:registryEvents");
            }
            loaderType.getMethod("initializeMods").invoke(fml);
            result.stages.add("initializeMods");
            log.accept("UMB-BRIDGE-1122 lifecycle initializeMods=OK (POSTINITIALIZATION included)");
            return result;
        } finally {
            thread.setContextClassLoader(oldTccl);
        }
    }

    private static Method findMethod(Class<?> type, String name, int arity) throws NoSuchMethodException {
        for (Method m : type.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == arity) return m;
        }
        throw new NoSuchMethodException(type.getName() + "." + name + "/" + arity);
    }

    private static void installForgeTransformers(ClassLoader loader, File forgeJar,
                                                 Consumer<String> log) throws Exception {
        File repo = forgeJar.getParentFile().getParentFile().getParentFile().getParentFile();
        // LaunchWrapper state must exist before loading Forge's remapper or patch manager;
        // both consult these statics while defining their dependent classes.
        Class<?> launchType = Class.forName("net.minecraft.launchwrapper.Launch", true, loader);
        java.util.Map blackboard = new java.util.HashMap();
        blackboard.put("fml.deobfuscatedEnvironment", Boolean.FALSE);
        blackboard.put("launchArgs", new java.util.HashMap());
        blackboard.put("forgeLaunchArgs", new java.util.HashMap());
        blackboard.put("TweakClasses", new java.util.ArrayList());
        // "Tweaks" (distinct key from "TweakClasses" above: that one holds tweak CLASS NAMES,
        // this one holds the already-INSTANTIATED ITweaker objects) is real LaunchWrapper's own
        // blackboard contract, populated by Launch.main()'s tweaker-loading loop - which this
        // lifecycle, by design, never runs (see this class's own javadoc: no ModLauncher/tweaker
        // boot). Left unseeded, it stays null, and any coremod that introspects "who is the
        // current side" the way real production code commonly does - index 0 of this list, class
        // name ending in FMLServerTweaker vs anything else - NPEs before its own bootstrap logic
        // ever runs. Proven live: MixinBooter's zone.rong.mixinbooter.util.Environment static
        // !mixinbooter-11.17.jar) and NPE'd on "tweaks is null" the first time this lifecycle
        // tried to load a coremod that actually reads it - HBM and every coremod tested before
        // MixinBooter happened not to. The REAL net.minecraftforge.fml.common.launcher
        // .FMLServerTweaker cannot be constructed here - its superclass FMLTweaker's constructor
        // calls System.setSecurityManager(...), which throws UnsupportedOperationException
        // unconditionally on modern JDKs (proven live: this was this fix's first attempt) -
        // dev.umb.legacy1122.legacyside.FMLServerTweaker is a harmless same-simple-name stand-in built
        // for exactly this (see its own javadoc); Class.getName().endsWith("FMLServerTweaker")
        // still matches it.
        Object serverTweaker = Class.forName(
                "dev.umb.legacy1122.legacyside.FMLServerTweaker", true, loader)
                .getDeclaredConstructor().newInstance();
        java.util.List<Object> tweaks = new java.util.ArrayList<Object>();
        tweaks.add(serverTweaker);
        blackboard.put("Tweaks", tweaks);
        launchType.getField("blackboard").set(null, blackboard);
        launchType.getField("classLoader").set(null, loader);
        Class<?> remapperType = Class.forName(
                "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper",
                true, loader);
        Object remapper = remapperType.getField("INSTANCE").get(null);
        Class<?> launchLoaderType = Class.forName("net.minecraft.launchwrapper.LaunchClassLoader",
                true, loader);
        Method setup = remapperType.getMethod("setup", File.class,
                launchLoaderType, String.class);
        setup.invoke(remapper, repo, loader, "/deobfuscation_data-1.12.2.lzma");
        Method exclude = launchLoaderType.getMethod("addTransformerExclusion", String.class);
        Method transformer = launchLoaderType.getMethod("registerTransformer", String.class);
        exclude.invoke(loader, "net.minecraftforge.fml.common.asm.transformers.");
        exclude.invoke(loader, "net.minecraftforge.fml.common.patcher.");
        // net.minecraftforge.fml.common.eventhandler.SubscribeEvent (an ANNOTATION with a default
        // value - "EventPriority priority() default NORMAL") must never pass through
        // EventSubscriptionTransformer/EventSubscriberTransformer: those two run a full ASM
        // ClassNode parse over EVERY non-"net.minecraft."-prefixed class as it loads (verified:
        // it only skips null bytes, the literal class "...eventhandler.Event", and
        // "net.minecraft." names; SubscribeEvent matches none of those). The first time that
        // parse reaches SubscribeEvent's own AnnotationDefault attribute, ASM's
        // MethodNode.visitAnnotationDefault() constructs a MethodNode$1 and throws
        // "IllegalAccessError: failed to access class org.objectweb.asm.tree.MethodNode$1 from
        // class org.objectweb.asm.tree.MethodNode" (MixinBooter wall 3, proven live) - the exact
        // same category of bug already fixed below for Gson (an annotation-default value
        // re-entrantly splitting an ASM inner class across two loaders mid-transform: this
        // lifecycle's own asm-debug-all-5.2 - pinned to match the forge universal jar's own
        // Class-Path, see build.ps1 - versus run-tests.ps1's newer asm-9.9 ahead of it on the
        // JUnit launcher's classpath for the transformer-verifier test), just tripped by Forge's
        // own annotation type instead of a mod's JSON metadata. Once ASMEventHandler (which DOES
        // still get transformed normally) reads that broken class back via reflection, its own
        // SubscribeEvent.priority() call sees a DIFFERENT loader's copy of the annotation and the
        // JVM's loader-constraint check rejects it - the LinkageError this fix resolves.
        // Deliberately narrow (the exact class, not the whole eventhandler package): the package
        // also holds GenericEvent, EventBus, and friends that DO need
        // Deobfuscation/AccessTransformer/PatchingTransformer applied normally (proven live: a
        // package-wide exclusion here made GenericEvent's constructor disappear - "NoSuchMethodError
        // net.minecraftforge.fml.common.eventhandler.GenericEvent: method '<init>()' not found" -
        // because it also skipped the transformers those classes genuinely need, not just the two
        // that crash on annotation defaults). Any other 1.12.2 mod (or Forge class) that defines
        // its OWN annotation with a default value hits this identical wall the first time it loads
        // through this pipeline; this entry only covers the one proven by MixinBooter today.
        exclude.invoke(loader, "net.minecraftforge.fml.common.eventhandler.SubscribeEvent");
        // Real Forge/LaunchWrapper excludes its own bundled third-party libraries (Gson among
        // them) from the mod transformer pipeline for exactly the reason this fix exists: Gson is
        // itself used DURING mod loading (a coremod's own JSON-based metadata scan, e.g.), so
        // transforming Gson's own classes on demand is a bootstrapping hazard, not a feature any
        // mod needs. Without this exclusion, the FIRST class loaded through this loader that
        // pulls in an ANNOTATION TYPE from Gson (any class with an @JsonAdapter-annotated field
        // somewhere in its type graph - proven live: MixinBooter's own ModDiscoverer.discover()
        // parses mod metadata JSON with a plain new GsonBuilder().create(), triggering exactly
        // this) crashes EventSubscriptionTransformer.transform() with
        // "IllegalAccessError: failed to access class org.objectweb.asm.tree.MethodNode$1 from
        // class org.objectweb.asm.tree.MethodNode" - ASM's own annotation-default-value visitor
        // splitting an inner-class reference across two different loaders while re-entrantly
        // parsing bytecode mid-transform. Not mod-specific: any code path (ours or a mod's) that
        // makes Gson touch an @JsonAdapter type for the first time through this loader hits the
        // identical wall.
        exclude.invoke(loader, "com.google.gson.");
        transformer.invoke(loader, "net.minecraftforge.fml.common.asm.transformers.PatchingTransformer");
        transformer.invoke(loader, "net.minecraftforge.fml.common.asm.transformers.DeobfuscationTransformer");
        transformer.invoke(loader, "net.minecraftforge.fml.common.asm.transformers.EventSubscriptionTransformer");
        transformer.invoke(loader, "net.minecraftforge.fml.common.asm.transformers.EventSubscriberTransformer");
        transformer.invoke(loader, "net.minecraftforge.fml.common.asm.transformers.AccessTransformer");
        transformer.invoke(loader, "dev.umb.legacy1122.legacyside.Legacy1122EnumHelperTransformer");
        transformer.invoke(loader, "dev.umb.legacy1122.legacyside.Legacy1122RenderTransformer");

        Class<?> sideType = Class.forName("net.minecraftforge.fml.relauncher.Side", true, loader);
        Object client = Enum.valueOf((Class) sideType, "CLIENT");
        Class<?> managerType = Class.forName(
                "net.minecraftforge.fml.common.patcher.ClassPatchManager", true, loader);
        try {
            Class<?> pack200 = Class.forName("java.util.jar.Pack200", false, loader);
            log.accept("UMB-BRIDGE-1122 Pack200 loader=" + pack200.getClassLoader()
                    + " codeSource=" + codeSource(pack200));
        } catch (Throwable missingPack200) {
            log.accept("UMB-BRIDGE-1122 Pack200 lookup failed=" + missingPack200);
        }
        Object manager = managerType.getField("INSTANCE").get(null);
        try {
            managerType.getMethod("setup", sideType).invoke(manager, client);
        } catch (Throwable setupFailure) {
            // Real ClassPatchManager.setup() calls java.util.jar.Pack200.newUnpacker() directly
            // jar); JEP 367 removed that JDK API in Java 21, so setup() throws NoClassDefFoundError
            // before it ever populates its own "patches" map - the diagnostic probe two lines above
            // already predicted exactly this ("Pack200 lookup failed") but the failure used to be
            // left unhandled, so EVERY 1.12.2 boot crashed right here (proven: once
            // run-headless.ps1's Boot-1122 was fixed to actually drive this lifecycle instead of a
            // flat-classpath smoke test, every picked mod - coremod or not - crashed at this exact
            // line). The fallback is not new code: it is the SAME repairPatchTable path already
            // below for "setup succeeded but left the map empty", which decodes the same
            // binpatches.pack.lzma stream through Apache Commons Compress's Harmony unpack200
            // implementation instead of the JDK's removed one. No patch data is invented here.
            Throwable cause = unwrap(setupFailure);
            log.accept("UMB-BRIDGE-1122 ClassPatchManager.setup failed, falling back to "
                    + "repairPatchTable: " + cause.getClass().getName() + ":" + cause.getMessage());
            // setup() assigns its own "patches" ListMultimap before it ever reaches the Pack200
            // ClassPatchManager declares it as a private, non-final
            // com.google.common.collect.ListMultimap, never initialised at the field declaration).
            // repairPatchTable below needs a real (empty) Multimap to put() into - the same shape
            // a successful setup() would have produced - not a null it would NPE on.
            Field patchesField0 = managerType.getDeclaredField("patches");
            patchesField0.setAccessible(true);
            if (patchesField0.get(manager) == null) {
                Class<?> multimapType = Class.forName("com.google.common.collect.ArrayListMultimap", true, loader);
                patchesField0.set(manager, multimapType.getMethod("create").invoke(null));
            }
        }
        URL pack = loader.getResource("binpatches.pack.lzma");
        log.accept("UMB-BRIDGE-1122 patch resource=" + pack + " loader=" + loader
                + " tccl=" + Thread.currentThread().getContextClassLoader());
        if (pack == null) {
            throw new IOException("binpatches.pack.lzma not visible through isolated loader " + loader);
        }
        Field patchesField = managerType.getDeclaredField("patches");
        patchesField.setAccessible(true);
        Object patches = patchesField.get(manager);
        java.util.Set<?> keys = (java.util.Set<?>) patches.getClass().getMethod("keySet").invoke(patches);
        log.accept("UMB-BRIDGE-1122 patch keys=" + keys.size());
        if (keys.isEmpty()) {
            int repaired = repairPatchTable(loader, managerType, manager, patches, log);
            log.accept("UMB-BRIDGE-1122 repaired patch keys=" + repaired);
        }
    }

    /** Fallback for Forge's setup when Launch.classLoader was initialized too late. */
    private static int repairPatchTable(ClassLoader loader, Class<?> managerType,
                                        Object manager, Object patches,
                                        Consumer<String> log) throws Exception {
        byte[] packed = unpackPack(loader, log);
        log.accept("UMB-BRIDGE-1122 decoded Pack200 bytes=" + packed.length);
        Method readPatch = managerType.getDeclaredMethod("readPatch", JarEntry.class, JarInputStream.class);
        readPatch.setAccessible(true);
        Class<?> patchType = Class.forName("net.minecraftforge.fml.common.patcher.ClassPatch", true, loader);
        Field sourceName = patchType.getDeclaredField("sourceClassName");
        sourceName.setAccessible(true);
        Method put = patches.getClass().getMethod("put", Object.class, Object.class);
        JarInputStream in = new JarInputStream(new ByteArrayInputStream(packed));
        int parsed = 0;
        try {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                if (!entry.getName().startsWith("binpatch/client/")) {
                    in.closeEntry();
                    continue;
                }
                try {
                    Object patch = readPatch.invoke(manager, entry, in);
                    if (patch != null) {
                        put.invoke(patches, sourceName.get(patch), patch);
                        parsed++;
                    }
                } catch (Throwable failure) {
                    Throwable cause = unwrap(failure);
                    log.accept("UMB-BRIDGE-1122 patch parse failure entry=" + entry.getName()
                            + " cause=" + cause.getClass().getName() + ":" + cause.getMessage());
                    throw cause instanceof Exception ? (Exception) cause : new IOException(cause);
                }
                in.closeEntry();
            }
        } finally {
            in.close();
        }
        return parsed;
    }

    private static byte[] unpackPack(ClassLoader loader, Consumer<String> log) throws Exception {
        URL resource = loader.getResource("binpatches.pack.lzma");
        if (resource == null) throw new IOException("binpatches.pack.lzma missing during repair");
        InputStream raw = resource.openStream();
        Class<?> lzma = Class.forName("LZMA.LzmaInputStream", true, loader);
        InputStream lzmaStream = (InputStream) lzma.getConstructor(InputStream.class).newInstance(raw);
        try {
            java.io.ByteArrayOutputStream lzmaBytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = lzmaStream.read(buffer)) >= 0) {
                if (read != 0) lzmaBytes.write(buffer, 0, read);
            }
            byte[] pack200 = lzmaBytes.toByteArray();
            log.accept("UMB-BRIDGE-1122 LZMA bytes=" + pack200.length + " magic=" + hexPrefix(pack200));
            // Debug aid, opt-in: -Dumb.1122.dumpBinpatches=<file> writes the decoded pack.
            String dumpPath = System.getProperty("umb.1122.dumpBinpatches");
            if (dumpPath != null && !dumpPath.isEmpty()) try {
                File liveDump = new File(dumpPath);
                File parent = liveDump.getParentFile();
                if (parent != null) parent.mkdirs();
                Files.write(liveDump.toPath(), pack200);
                log.accept("UMB-BRIDGE-1122 LZMA dump=" + liveDump.getAbsolutePath());
            } catch (Throwable dumpFailure) {
                log.accept("UMB-BRIDGE-1122 LZMA dump failed=" + dumpFailure);
            }
            java.io.ByteArrayOutputStream unpackedJar = new java.io.ByteArrayOutputStream();
            JarOutputStream jar = new JarOutputStream(unpackedJar);
            Class<?> archive = Class.forName(
                    "org.apache.commons.compress.harmony.unpack200.Archive", true, loader);
            log.accept("UMB-BRIDGE-1122 Archive loader=" + archive.getClassLoader()
                    + " codeSource=" + codeSource(archive));
            Object unpacker = archive.getConstructor(InputStream.class, JarOutputStream.class)
                    .newInstance(new ByteArrayInputStream(pack200), jar);
            try {
                archive.getMethod("unpack").invoke(unpacker);
            } catch (Throwable failure) {
                Throwable cause = unwrap(failure);
                log.accept("UMB-BRIDGE-1122 Archive failure=" + cause.getClass().getName()
                        + ":" + cause.getMessage());
                throw cause instanceof Exception ? (Exception) cause : new IOException(cause);
            }
            jar.close();
            return unpackedJar.toByteArray();
        } finally {
            lzmaStream.close();
            raw.close();
        }
    }

    private static String hexPrefix(byte[] bytes) {
        StringBuilder out = new StringBuilder();
        int count = Math.min(4, bytes.length);
        for (int i = 0; i < count; i++) out.append(String.format("%02x", bytes[i] & 255));
        return out.length() == 0 ? "empty" : out.toString();
    }

    private static Object codeSource(Class<?> type) {
        try { return type.getProtectionDomain().getCodeSource(); }
        catch (Throwable ignored) { return "<unavailable>"; }
    }

    private static void invokeVanillaBootstrap(ClassLoader loader) throws Exception {
        Class<?> bootstrap;
        Method register;
        try {
            // Live FML deobfuscates before this call. Resolve the named class/member first;
            // raw ni/c is only for the pre-transform probe jar (joined-1.12.2.srg grounds both).
            bootstrap = Class.forName("net.minecraft.init.Bootstrap", false, loader);
            register = bootstrap.getDeclaredMethod("func_151354_b");
        } catch (ClassNotFoundException missingNamedBootstrap) {
            bootstrap = Class.forName("ni", false, loader);
            register = bootstrap.getDeclaredMethod("c");
        }
        register.setAccessible(true);
        register.invoke(null);
    }

    private static Object makeDataFixer(ClassLoader loader, java.util.function.Consumer<String> log) throws Exception {
        Class<?> fixer;
        try {
            fixer = Class.forName("net.minecraft.util.datafix.DataFixer", true, loader);
        } catch (ClassNotFoundException missingDeobfuscatedName) {
            // Forge 1.12.2's universal/client jars carry the vanilla class as the notch name
            fixer = Class.forName("ry", true, loader);
        }
        Object vanilla = fixer.getConstructor(int.class).newInstance(Integer.valueOf(1343));
        // IFMLSidedHandler.getDataFixer() returns CompoundDataFixer and FMLCommonHandler returns it
        // ClassCastException DataFixer -> CompoundDataFixer) needs the real Forge wrapper.
        try {
            Class<?> compound = Class.forName("net.minecraftforge.common.util.CompoundDataFixer", true, loader);
            Object result = compound.getConstructor(fixer).newInstance(vanilla);
            log.accept("UMB-BRIDGE-1122 dataFixer=" + result.getClass().getName());
            return result;
        } catch (Throwable compoundFailure) {
            log.accept("UMB-BRIDGE-1122 CompoundDataFixer unavailable, vanilla fixer used: " + unwrap(compoundFailure));
            return vanilla;
        }
    }

    private static Throwable unwrap(Throwable t) {
        while (t.getCause() != null && (t instanceof java.lang.reflect.InvocationTargetException
                || t instanceof ExceptionInInitializerError)) t = t.getCause();
        return t;
    }
}
