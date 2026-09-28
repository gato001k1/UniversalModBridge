package dev.umb.legacy1165.legacyside;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.function.Function;

import dev.umb.bridge.api.HostWorld;

import cpw.mods.modlauncher.Launcher;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.registry.Bootstrap;
import net.minecraft.server.MinecraftServer;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.BusBuilder;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModContainer;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoader;
import net.minecraftforge.fml.ModLoadingStage;
import net.minecraftforge.fml.ModWorkManager;
import net.minecraftforge.fml.javafmlmod.FMLModContainer;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.fml.loading.moddiscovery.ModFileParser;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;
import net.minecraftforge.forgespi.language.ModFileScanData;
import net.minecraftforge.forgespi.locating.IModLocator;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.ObjectHolderRegistry;
import net.minecraftforge.registries.RegistryManager;

/**
 * The 1.16.5 era's full in-universe lifecycle: hand-replicates what ModLauncher's boot
 * environment + FML's loading flow do in production, then drives the REAL
 * {@code ModLoader} phase methods - the same role 1.7.10's {@code LegacyDriver} plays for
 * {@code Loader.loadMods()/preinitializeMods()/initializeMods()}.
 *
 * <p>Production order, and what runs here instead (every substitution labeled):</p>
 * <ol>
 *   <li>ModLauncher bootstraps services, discovers mods, builds LoadingModList - INSTEAD: the
 *       caller names mod jars explicitly (system property {@code umb.1165.modjars}, fail loudly
 *       when absent); a real Forge {@code ModsFolderLocator} (reflected package-private ctor)
 *       serves them; real {@code ModFile} + {@code modsTomlParser} + real ASM scan build the
 *       {@code ModFileInfo}/{@code ModInfo}/scan data.</li>
 *   <li>{@code LoadingModList.of(...)} is real; it is injected into
 *       {@code FMLLoader.loadingModList} reflectively (private static, set where ModLauncher's
 *       boot would set it).</li>
 *   <li>Mod containers: production builds them through language providers over a
 *       {@code TransformingClassLoader}. {@code FMLModContainer} takes a plain
 *       {@code ClassLoader}, so it is constructed DIRECTLY with the isolated loader and the
 *       {@code @Mod} class resolved from the real scan - same object production builds, no
 *       provider wrapper in between.</li>
 *   <li>{@code ModList.of(...) + setLoadedMods(...)} (the latter package-private:
 *       reflective, documented).</li>
 *   <li>Dist: production's launch target sets it; here {@code FMLEnvironment.dist} is set to
 *       {@code DEDICATED_SERVER} reflectively (static final, documented) so the SIDED_SETUP
 *       switch resolves exactly like a dedicated server.</li>
 *   <li>Phases through the REAL {@code ModLoader}: {@code CONSTRUCT}, {@code CREATE_REGISTRIES},
 *       {@code LOAD_REGISTRIES} via reflective {@code dispatchAndHandleError} (private,
 *       documented) plus {@code ObjectHolderRegistry}/{@code CapabilityManager}/
 *       {@code setCustomTagTypesFromRegistries} exactly like {@code gatherAndInitializeMods};
 *       then {@code loadMods(...)} (COMMON_SETUP + SIDED_SETUP) and {@code finishMods(...)}
 *       (ENQUEUE/PROCESS_IMC + COMPLETE + freeze + lock) called directly.</li>
 * </ol>
 *
 * <p>The result ({@link Result}) carries per-stage timings plus live-registry captures for the
 * bridge and the snapshot. No game window, no world, no ModLauncher boot.</p>
 */
public final class Legacy1165Lifecycle {

    private Legacy1165Lifecycle() {
    }

    /** Stage outcome for reports. */
    public static final class Stage {
        public final String name;
        public final boolean ok;
        public final long millis;
        public final String error;

        Stage(String name, boolean ok, long millis, String error) {
            this.name = name;
            this.ok = ok;
            this.millis = millis;
            this.error = error;
        }
    }

    /** Everything a boot produced. */
    public static final class Result {
        public final List<Stage> stages = new ArrayList<Stage>();
        public final Map<String, Map<String, String>> captured =
                new TreeMap<String, Map<String, String>>();
        public String modId;
        public String modVersion;
        /** Child-universe server identity used only for real data-pack resource lookups. */
        public MinecraftServer resourceServer;

        public boolean allOk() {
            for (Stage s : stages) {
                if (!s.ok) {
                    return false;
                }
            }
            return true;
        }

        void add(String name, boolean ok, long millis, String error) {
            stages.add(new Stage(name, ok, millis, error));
        }
    }

    /**
     * Runs the full lifecycle.
     *
     * @param loader the isolated universe loader (also the mod classloader)
     * @param modJars mod jars to load (real Forge discovery over these via ModsFolderLocator)
     * @param gameDir writable dir for FMLPaths/configs (created if missing)
     * @param log sink for progress lines (HostWorld.log in the bridge, stdout in probes)
     */
    public static Result run(ClassLoader loader, List<File> modJars, File gameDir,
            Consumer<String> log) throws Exception {
        return run(loader, modJars, java.util.Collections.<File>emptyList(), gameDir, log);
    }

    public static Result run(ClassLoader loader, List<File> modJars, List<File> dataPacks,
            File gameDir, Consumer<String> log) throws Exception {
        Result result = new Result();
        ClassLoader previousTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            runInner(loader, modJars, dataPacks, gameDir, log, result);
        } finally {
            Thread.currentThread().setContextClassLoader(previousTccl);
        }
        return result;
    }

    private static void runInner(ClassLoader loader, List<File> modJars, List<File> dataPacks,
            File gameDir, Consumer<String> log, Result result) throws Exception {
        if (!gameDir.isDirectory() && !gameDir.mkdirs()) {
            throw new IllegalStateException("cannot create gameDir: " + gameDir);
        }

        // ---- gameDir + FMLPaths (production: ModLauncher env; here: direct, documented).
        long t0 = now();
        FMLPaths.loadAbsolutePaths(gameDir.toPath());
        // FMLConfig.load() reads/writes gameDir/config/fml.toml (default resource ships in the
        // launch jar). Production loads it during early FML boot; without it every
        // FMLConfig accessor NPEs (proven: parallelExecutor fallback). GameDir is ours.
        net.minecraftforge.fml.loading.FMLConfig.load();
        result.add("gamedir+fmlconfig", true, ms(t0), null);

        // ---- ModLauncher scaffold (private no-arg Launcher ctor builds the Environment with
        // defaults and assigns INSTANCE itself - proven by javap -c in round 2).
        t0 = now();
        Constructor<?> launcherCtor = Launcher.class.getDeclaredConstructor();
        launcherCtor.setAccessible(true);
        launcherCtor.newInstance();
        if (Launcher.INSTANCE == null) {
            throw new IllegalStateException("Launcher.INSTANCE still null after ctor");
        }
        result.add("scaffold", true, ms(t0), null);

        // ---- vanilla Bootstrap FIRST (order is load-bearing: its patched Registry hook pulls
        // GameData.<clinit>/init() in mid-flight - proven by failure in round 2).
        t0 = now();
        Bootstrap.class.getMethod("func_151354_b").invoke(null);
        result.add("bootstrap", true, ms(t0), null);

        // ---- GameData verify (init ran via the hooks above).
        t0 = now();
        if (ForgeRegistries.BLOCKS == null || ForgeRegistries.ITEMS == null) {
            throw new IllegalStateException("ForgeRegistries empty after Bootstrap");
        }
        result.add("gamedata", true, ms(t0), null);

        // ---- Dist = dedicated server (production launch target fmlserver does this).
        // Set EARLY: ModLoadingStage's own static init switches on dist (SIDED_SETUP event
        // selection), so anything touching a stage - including FMLModContainer construction -
        // needs it first. Proven by failure (NPE on dist.ordinal()).
        setStaticField(FMLEnvironment.class, "dist", Dist.DEDICATED_SERVER);
        log.accept("[lifecycle] dist=DEDICATED_SERVER");

        // ---- mod discovery over the named jars (Forge's own locator + parser + scan).
        // Production fills these from the launch arguments (see the launch jar manifest's
        // ServerLaunchArgs); StringSubstitutor hard-requires mcVersion/forgeVersion at class
        // init (Guava ImmutableMap rejects the nulls), so they are set first with the exact
        // production values.
        t0 = now();
        setStaticField(
                Class.forName("net.minecraftforge.fml.loading.FMLLoader", true, loader),
                "mcVersion", "1.16.5");
        setStaticField(
                Class.forName("net.minecraftforge.fml.loading.FMLLoader", true, loader),
                "forgeVersion", "36.2.34");
        setStaticField(
                Class.forName("net.minecraftforge.fml.loading.FMLLoader", true, loader),
                "mcpVersion", "20210115.111550");
        setStaticField(
                Class.forName("net.minecraftforge.fml.loading.FMLLoader", true, loader),
                "forgeGroup", "net.minecraftforge");
        // gamePath: production reads it from the ModLauncher environment (GAMEDIR); here the
        // gameDir argument plays that role (UsernameCache.<clinit> needs it during ForgeMod
        // construction - proven by failure).
        setStaticField(
                Class.forName("net.minecraftforge.fml.loading.FMLLoader", true, loader),
                "gamePath", gameDir.toPath());
        result.add("versions+gamepath", true, ms(t0), null);
        t0 = now();
        List<ModFile> modFiles = new ArrayList<ModFile>();
        List<ModInfo> modInfos = new ArrayList<ModInfo>();
        List<ModFileScanData> scans = new ArrayList<ModFileScanData>();
        Constructor<?> locatorCtor = Class.forName(
                "net.minecraftforge.fml.loading.moddiscovery.ModsFolderLocator", true, loader)
                .getDeclaredConstructor(Path.class);
        locatorCtor.setAccessible(true);
        for (File modJar : modJars) {
            Object locator = locatorCtor.newInstance(modJar.getParentFile().toPath());
            ModFile modFile = new ModFile(modJar.toPath(),
                    (IModLocator) locator,
                    ModFileParser::modsTomlParser);
            registerJarFilesystem(locator, modFile, loader);
            // identifyMods() is what ModDiscoverer calls per file: parses mods.toml AND sets
            // it on the ModFile (plus coremod/AT discovery). Guarded by locator.isValid, which
            // is exactly the filesystem registration above.
            if (!modFile.identifyMods()) {
                throw new IllegalStateException("identifyMods() declined " + modJar);
            }
            IModFileInfo info = modFile.getModFileInfo();
            ModFileScanData scan = modFile.compileContent();
            // setScanResult(scan, null) is what BackgroundScanHandler's future completion
            // calls in production; getScanResult() returns the FIELD, not the future.
            modFile.setScanResult(scan, null);
            modFiles.add(modFile);
            for (IModInfo modInfo : info.getMods()) {
                modInfos.add((ModInfo) modInfo);
            }
            scans.add(scan);
        }
        if (modInfos.isEmpty()) {
            throw new IllegalStateException("no mods found in " + modJars);
        }
        ModInfo first = modInfos.get(0);
        result.modId = first.getModId();
        result.modVersion = String.valueOf(first.getVersion());
        result.add("discover(" + modInfos.size() + " mods)", true, ms(t0), null);
        log.accept("[lifecycle] discovered modId=" + result.modId + " version=" + result.modVersion);

        // ---- LoadingModList + inject into FMLLoader (private static, set where ModLauncher
        // boot would set it).
        t0 = now();
        LoadingModList loadingList = LoadingModList.of(modFiles, modInfos, null);
        loadingList.setBrokenFiles(new ArrayList<net.minecraftforge.fml.loading.moddiscovery.ModFile>());
        setStaticField(FMLLoader.class, "loadingModList", loadingList);
        result.add("loadingmodlist", true, ms(t0), null);

        // ---- ModContainers, constructed directly (FMLModContainer takes a plain ClassLoader;
        // the @Mod class comes from the real scan, cross-checked, never guessed).
        t0 = now();
        List<ModContainer> containers = new ArrayList<ModContainer>();
        // Forge exposes the discovered ModList before @Mod classes are initialized.  Simple
        // mods do not observe this boundary, but real machine mods such as Immersive Engineering
        // query ModList from a static API field during their container class initialization.
        ModList modList = ModList.of(modFiles, modInfos);
        for (int i = 0; i < modFiles.size(); i++) {
            String modClass = findModClass(scans.get(i), modInfos);
            ModContainer container = new FMLModContainer(modInfos.get(i), modClass, loader,
                    scans.get(i));
            containers.add(container);
            log.accept("[lifecycle] container: " + modInfos.get(i).getModId() + " -> " + modClass);
        }
        setModListLoadedMods(modList, containers);
        result.add("construct-containers(" + containers.size() + ")", true, ms(t0), null);

        // gatherAndInitializeMods sets this first; we replicate the gather tail without it,
        // so set it directly (private boolean, documented).
        setInstanceField(ModLoader.get(), "loadingStateValid", Boolean.TRUE);

        // ---- executors (production calls through ModWorkManager; parallel falls back to the
        // common pool only if FMLConfig is unavailable standalone).
        ModWorkManager.DrivenExecutor sync = ModWorkManager.syncExecutor();
        Executor parallel;
        try {
            parallel = ModWorkManager.parallelExecutor();
        } catch (Throwable t) {
            log.accept("[lifecycle] ModWorkManager.parallelExecutor unavailable (" + t
                    + "), using commonPool");
            parallel = ForkJoinPool.commonPool();
        }
        Runnable ticker = new Runnable() {
            @Override
            public void run() {
            }
        };

        // ---- gather tail: CONSTRUCT + CREATE_REGISTRIES + object holders + capabilities +
        // custom tags + LOAD_REGISTRIES (mirrors gatherAndInitializeMods minus discovery/build,
        // which ran above).
        dispatch(ModLoader.get(), ModLoadingStage.CONSTRUCT, sync, parallel, ticker, result, log);
        dispatch(ModLoader.get(), ModLoadingStage.CREATE_REGISTRIES, sync, parallel, ticker,
                result, log);
        t0 = now();
        for (ModFileScanData scan : scans) {
            int total = 0;
            int nulls = 0;
            for (Object annotation : scan.getAnnotations()) {
                total++;
                if (annotation == null) {
                    nulls++;
                }
            }
            log.accept("[lifecycle] scan annotations=" + total + " nulls=" + nulls);
        }
        // Same chain ModList.getAllScanData + findObjectHolders walk, with per-link null
        // attribution (a bare NPE inside the stream gives no location).
        for (net.minecraftforge.fml.loading.moddiscovery.ModInfo info
                : ModList.get().getMods()) {
            Object owning = info.getOwningFile();
            Object file = owning == null ? null
                    : ((ModFileInfo) owning).getFile();
            Object scanResult = null;
            if (file instanceof ModFile) {
                try {
                    scanResult = ((ModFile) file).getScanResult();
                } catch (Exception e) {
                    scanResult = "ERROR: " + e;
                }
            }
            log.accept("[lifecycle] modlist entry modId=" + info.getModId() + " owningFile="
                    + (owning == null ? "null" : owning.getClass().getName()) + " file="
                    + file + " scanResult=" + (scanResult == null ? "null"
                            : scanResult instanceof String ? scanResult : "present"));
        }
        ObjectHolderRegistry.findObjectHolders();
        net.minecraftforge.common.capabilities.CapabilityManager.INSTANCE.injectCapabilities(scans);
        GameData.setCustomTagTypesFromRegistries();
        result.add("holders+capabilities+tags", true, ms(t0), null);
        dispatch(ModLoader.get(), ModLoadingStage.LOAD_REGISTRIES, sync, parallel, ticker, result,
                log);

        // ---- loadMods: COMMON_SETUP + SIDED_SETUP (direct call, production signature).
        t0 = now();
        Function<Executor, CompletableFuture<Void>> noOp =
                new Function<Executor, CompletableFuture<Void>>() {
                    @Override
                    public CompletableFuture<Void> apply(Executor executor) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                };
        ModLoader.get().loadMods(sync, parallel, noOp, noOp, ticker);
        result.add("loadMods(setup+sided)", true, ms(t0), null);

        // ---- finishMods: ENQUEUE_IMC + PROCESS_IMC + COMPLETE + freeze + lock.
        t0 = now();
        ModLoader.get().finishMods(sync, parallel, ticker);
        result.add("finishMods(imc+complete+freeze)", true, ms(t0), null);

        // ---- entity renderer capture setup (CLIENT-side registration, headless).
        // The universe runs as DEDICATED_SERVER so FMLClientSetupEvent never fires during
        // SIDED_SETUP, which means RenderingRegistry.registerEntityRenderingHandler() is
        // never called by any mod.  Fire the client setup event explicitly for every loaded
        // mod so their renderer factories register, then construct a minimal headless
        // EntityRendererManager and populate it via loadEntityRenderers.  This is the only
        // path to a live EntityRendererManager without a real Minecraft client singleton.
        t0 = now();
        installEntityRenderers(containers, log);
        result.add("entity-renderer-capture-setup", true, ms(t0), null);

        // ---- capture live registries for the bridge + snapshot.
        t0 = now();
        capture(result, log);
        result.resourceServer = UmbServer1165.create(modFiles, dataPacks, log);
        result.add("capture", true, ms(t0), null);
    }

    /**
     * Drives one ModLoadingStage like ModLoader.dispatchAndHandleError, but keeps the FULL
     * error: production's waitForTransition only reports suppressed ModLoadingExceptions and
     * drops a directly-thrown cause (seen: empty "0 errors found" for a real failure). Here
     * the transition future is built via the stage's own public buildTransition, pumped on the
     * sync executor exactly like production, and every cause/suppressed is recorded.
     */
    private static void dispatch(ModLoader loader, ModLoadingStage stage,
            ModWorkManager.DrivenExecutor sync, Executor parallel, Runnable ticker, Result result,
            Consumer<String> log) throws Exception {
        long t0 = now();
        log.accept("[lifecycle] stage " + stage + " dispatching");
        try {
            java.util.concurrent.CompletableFuture<java.util.List<Throwable>> transition =
                    stage.buildTransition(sync, parallel);
            while (!transition.isDone()) {
                sync.drive(ticker);
            }
            List<Throwable> errors = transition.join();
            if (!errors.isEmpty()) {
                throw new IllegalStateException("stage " + stage + " errors: " + errors);
            }
            result.add("stage-" + stage, true, ms(t0), null);
            log.accept("[lifecycle] stage " + stage + " ok");
        } catch (Exception e) {
            // File report AND host log: in-game nobody reads registry-probe.txt, so the stage
            // attribution plus the first causal lines go to the log too (the full chain rides
            // the thrown message into the bridge's bootFailure).
            result.add("stage-" + stage, false, ms(t0), fullChain(e));
            setInstanceField(loader, "loadingStateValid", Boolean.FALSE);
            log.accept("[lifecycle] stage " + stage + " FAILED: " + firstCauseLines(e));
            throw e;
        }
    }

    private static String fullChain(Throwable t) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        appendChain(sb, t, seen, "");
        return sb.toString();
    }

    /** First causal lines for host-log stage attribution (the full chain rides the throw). */
    private static String firstCauseLines(Throwable t) {
        StringBuilder sb = new StringBuilder();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<Throwable, Boolean>());
        Throwable c = t;
        int depth = 0;
        while (c != null && seen.add(c) && depth < 6) {
            sb.append(c.getClass().getName()).append(": ").append(c.getMessage()).append(" <- ");
            c = c.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static void appendChain(StringBuilder sb, Throwable t,
            java.util.Set<Throwable> seen, String prefix) {
        if (t == null || !seen.add(t)) {
            return;
        }
        sb.append(prefix).append(t.getClass().getName()).append(": ").append(t.getMessage())
                .append('\n');
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < Math.min(st.length, 25); i++) {
            String line = st[i].toString();
            if (line.contains("net.minecraftforge") || line.contains("com.progwml6")
                    || line.contains("dev.umb") || line.contains("java.util.concurrent")) {
                sb.append(prefix).append("  at ").append(line).append('\n');
            }
        }
        for (Throwable s : t.getSuppressed()) {
            sb.append(prefix).append("  suppressed:\n");
            appendChain(sb, s, seen, prefix + "    ");
        }
        appendChain(sb, t.getCause(), seen, prefix);
    }

    private static void capture(Result result, Consumer<String> log) {
        captureRegistry(result, "block", ForgeRegistries.BLOCKS, log);
        captureRegistry(result, "item", ForgeRegistries.ITEMS, log);
        captureRegistry(result, "tileentitytype", ForgeRegistries.TILE_ENTITIES, log);
        captureRegistry(result, "containertype", ForgeRegistries.CONTAINERS, log);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void captureRegistry(Result result, String key, IForgeRegistry reg,
            Consumer<String> log) {
        Map<String, String> got = new LinkedHashMap<String, String>();
        for (Object id : reg.getKeys()) {
            Object value = reg.getValue((ResourceLocation) id);
            got.put(String.valueOf(id), value == null ? null : value.getClass().getName());
        }
        result.captured.put(key, got);
        log.accept("[lifecycle] Register<" + reg.getRegistryName() + "> captured=" + got.size());
    }

    /** Registers the mod jar's filesystem with the locator (what ModDiscoverer does in proc). */
    private static void registerJarFilesystem(Object locator, ModFile modFile, ClassLoader loader)
            throws Exception {
        Class<?> abstractLocator = Class.forName(
                "net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileLocator", true, loader);
        Method createFs = abstractLocator.getDeclaredMethod("createFileSystem",
                net.minecraftforge.forgespi.locating.IModFile.class);
        createFs.setAccessible(true);
        Object fs = createFs.invoke(locator, modFile);
        Field modJars = abstractLocator.getDeclaredField("modJars");
        modJars.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Object, Object> map = (Map<Object, Object>) modJars.get(locator);
        map.put(modFile, fs);
    }

    /** The @Mod class from the real ASM scan (annotation value matched to the mod id). */
    private static String findModClass(ModFileScanData scan, List<ModInfo> modInfos) {
        for (ModFileScanData.AnnotationData annotation : scan.getAnnotations()) {
            String type = String.valueOf(annotation.getAnnotationType());
            if (!type.endsWith("/Mod;") && !type.endsWith(".Mod")) {
                continue;
            }
            Map<String, Object> values = annotation.getAnnotationData();
            Object value = values.get("value");
            for (ModInfo info : modInfos) {
                if (info.getModId().equals(String.valueOf(value))) {
                    return annotation.getMemberName().replace('/', '.');
                }
            }
        }
        throw new IllegalStateException("@Mod class not found in scan for " + modInfos);
    }

    /**
     * Fires {@code FMLClientSetupEvent} for every loaded mod container so their renderer
     * factories register with {@code RenderingRegistry}, then constructs a minimal headless
     * {@code EntityRendererManager} and installs it into
     * {@code LegacyEntityRenderCapture1165Client}.
     *
     * <p>This is the universal mechanism for entity rendering in the DEDICATED_SERVER universe:
     * the client setup event is fired once, factories collected, renderer manager allocated
     * without a real Minecraft client, and all registered renderers populated.  No mod id or
     * class name branching anywhere in this path.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void installEntityRenderers(List<ModContainer> containers,
            Consumer<String> log) {
        try {
            // Step 1: fire FMLClientSetupEvent for each mod's event bus so their renderer
            // registration lambdas call RenderingRegistry.registerEntityRenderingHandler.
            int fired = 0;
            for (ModContainer container : containers) {
                try {
                    if (!(container instanceof net.minecraftforge.fml.javafmlmod.FMLModContainer)) {
                        continue;
                    }
                    net.minecraftforge.fml.javafmlmod.FMLModContainer mc =
                            (net.minecraftforge.fml.javafmlmod.FMLModContainer) container;
                    net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent evt =
                            new net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent(container);
                    mc.getEventBus().post(evt);
                    fired++;
                } catch (Throwable t) {
                    log.accept("[entity-render] FMLClientSetupEvent post failed for "
                            + container.getModId() + ": " + t);
                }
            }
            log.accept("[entity-render] FMLClientSetupEvent fired for " + fired + " containers");

            // Step 2: count registered renderer factories (diagnostic).
            int factories = countRenderingRegistryFactories();
            log.accept("[entity-render] RenderingRegistry factories=" + factories);
            if (factories == 0) {
                log.accept("[entity-render] no renderer factories registered; entity capture unavailable");
                return;
            }

            // Step 3: allocate a minimal EntityRendererManager via Unsafe (bypasses the
            // constructor which requires TextureManager/ItemRenderer/FontRenderer/GameSettings
            // client objects).  Only the field_78729_o map is needed: func_78713_a reads it,
            // and loadEntityRenderers populates it.
            net.minecraft.client.renderer.entity.EntityRendererManager mgr =
                    allocateHeadlessEntityRendererManager();
            if (mgr == null) {
                log.accept("[entity-render] headless EntityRendererManager allocation failed");
                return;
            }

            // Step 4: populate the manager from the registered factories.
            net.minecraftforge.fml.client.registry.RenderingRegistry.loadEntityRenderers(mgr);
            int loaded = mgr.field_78729_o == null ? 0 : mgr.field_78729_o.size();
            log.accept("[entity-render] EntityRendererManager loaded renderers=" + loaded);

            // Step 5: install into the capture layer.
            LegacyEntityRenderCapture1165Client.install(mgr);
            log.accept("[entity-render] LegacyEntityRenderCapture1165Client installed manager="
                    + (mgr != null ? "ok" : "null") + " renderers=" + loaded);
        } catch (Throwable t) {
            log.accept("[entity-render] installEntityRenderers failed: " + t);
        }
    }

    /** Reads the size of RenderingRegistry's factory map via reflection (diagnostic). */
    private static int countRenderingRegistryFactories() {
        try {
            Field instanceField = net.minecraftforge.fml.client.registry.RenderingRegistry.class
                    .getDeclaredField("INSTANCE");
            instanceField.setAccessible(true);
            Object instance = instanceField.get(null);
            if (instance == null) return 0;
            Field renderers = instance.getClass().getDeclaredField("entityRenderers");
            renderers.setAccessible(true);
            Object map = renderers.get(instance);
            if (map instanceof java.util.Map) return ((java.util.Map) map).size();
        } catch (Throwable ignored) {
        }
        return -1;
    }

    /**
     * Allocates an {@code EntityRendererManager} without calling its constructor, then sets
     * the {@code field_78729_o} (renderer map) field to an empty {@code HashMap} so that
     * {@code func_78713_a} and {@code loadEntityRenderers} can operate on it.
     *
     * <p>{@code field_78729_o} is declared {@code public final}; {@code Field.set} refuses it
     * on JDK 17+ without {@code --add-opens java.base/java.lang.reflect}.  Use the same
     * {@code sun.misc.Unsafe} path the lifecycle already uses for {@code FMLEnvironment.dist}
     * so no extra JVM arg is required.</p>
     */
    private static net.minecraft.client.renderer.entity.EntityRendererManager
            allocateHeadlessEntityRendererManager() {
        try {
            // Obtain sun.misc.Unsafe (already used by setStaticField above).
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);

            // Allocate the object without invoking its constructor.
            Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
            net.minecraft.client.renderer.entity.EntityRendererManager mgr =
                    (net.minecraft.client.renderer.entity.EntityRendererManager)
                    allocate.invoke(unsafe,
                            net.minecraft.client.renderer.entity.EntityRendererManager.class);

            // Set field_78729_o (the public final renderers map) via Unsafe objectFieldOffset
            // so the final modifier is bypassed without --add-opens java.lang.reflect.
            Field mapField = net.minecraft.client.renderer.entity.EntityRendererManager.class
                    .getDeclaredField("field_78729_o");
            mapField.setAccessible(true);
            Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            Method putObject = unsafeClass.getMethod("putObject",
                    Object.class, long.class, Object.class);
            long offset = ((Long) objectFieldOffset.invoke(unsafe, mapField)).longValue();
            putObject.invoke(unsafe, mgr, offset, new java.util.HashMap<>());
            return mgr;
        } catch (Throwable t) {
            return null;
        }
    }


    private static void setInstanceField(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static void setStaticField(Class<?> owner, String name, Object value) throws Exception {        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        try {
            field.set(null, value);
            return;
        } catch (IllegalAccessException e) {
            // Static FINAL fields (e.g. FMLEnvironment.dist) refuse Field.set even accessible
            // on modern JDKs. Fall back to Unsafe, which bypasses the final check - needs
            // --add-opens java.base/jdk.internal.misc (see run-lifecycle.ps1, documented there
            // alongside the other era-native opens). Same value production's launch target
            // would install; only the mechanism differs. All reflective (no compile-time
            // sun.misc dep, which --release 8 hides).
        }
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method staticFieldBase = unsafeClass.getMethod("staticFieldBase", Field.class);
        Method staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", Field.class);
        Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class,
                Object.class);
        Object base = staticFieldBase.invoke(unsafe, field);
        long offset = ((Long) staticFieldOffset.invoke(unsafe, field)).longValue();
        putObject.invoke(unsafe, base, offset, value);
    }

    private static void setModListLoadedMods(ModList modList, List<ModContainer> containers)
            throws Exception {
        Method setLoadedMods = ModList.class.getDeclaredMethod("setLoadedMods", List.class);
        setLoadedMods.setAccessible(true);
        setLoadedMods.invoke(modList, containers);
    }

    private static long now() {
        return System.nanoTime();
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1000000L;
    }
}
