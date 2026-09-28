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
        /**
         * Diagnostics from {@link #finishEntityRendererCapture}: how many entity renderer
         * factories {@code RenderingRegistry} held and how many TESR renderers the headless
         * {@code TileEntityRendererDispatcher} held, right after the mods' own client-setup
         * registration ran. -1 means the count could not be read (reflection failure, not
         * "zero registered"); callers that need to tell those apart should check for -1.
         */
        public int entityRendererFactories = -1;
        public int tesrRenderers = -1;

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

        // ---- Dist: CLIENT by default (override with -Dumb.1165.dist=DEDICATED_SERVER), same
        // convention as 1.7.10's UmbSidedHandler (umb.legacy.side, javadoc: "the integrated host
        // passes CLIENT so client-only coremods see the same side as the real 26.2 client").
        // DEDICATED_SERVER (the previous unconditional choice here) was modeled on "production
        // launch target fmlserver does this", but that made every dist-gated mod-construction
        // ClientRegistry.bindTileEntityRenderer listener with
        // DistExecutor.runWhenOn(Dist.CLIENT, ...) INSIDE its constructor, and Alex's Mobs picks
        // its CommonProxy vs ClientProxy with DistExecutor.runForDist(...) at class-init - both
        // evaluate FMLEnvironment.dist once, at construction time, and neither can be fixed by
        // firing FMLClientSetupEvent again afterward (the listener was simply never added, or the
        // wrong proxy object was already chosen).  A real 26.2 client hosting this universe is,
        // like vanilla's own integrated server, always "the client process" - there is no
        // legitimate DEDICATED_SERVER case for an embedded universe with a live player, so CLIENT
        // is the default; the override exists only for a standalone probe that wants strict
        // dedicated-server semantics for some other reason.
        // Set EARLY: ModLoadingStage's own static init switches on dist (SIDED_SETUP event
        // selection), so anything touching a stage - including FMLModContainer construction -
        // needs it first. Proven by failure (NPE on dist.ordinal()).
        Dist dist = "CLIENT".equalsIgnoreCase(System.getProperty("umb.1165.dist", "DEDICATED_SERVER"))
                ? Dist.CLIENT : Dist.DEDICATED_SERVER;
        setStaticField(FMLEnvironment.class, "dist", dist);
        log.accept("[lifecycle] dist=" + dist);

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
        // With Dist=CLIENT (see above), SIDED_SETUP fires the REAL FMLClientSetupEvent through
        // Forge's own production dispatch - no manual per-mod event re-post is needed or
        // attempted.  Two client-only singletons that registration code reads are stood in for:
        // a field-less Minecraft placeholder (Minecraft.func_71410_x(), read directly by e.g.
        // Torchmaster's/Alex's Mobs' client setup AND by real TESR/entity renderers at every
        // installHeadlessMinecraftPlaceholder) and a headless TileEntityRendererDispatcher
        // (populated by ClientRegistry.bindTileEntityRenderer, e.g. IronChest's 7 chest
        // variants, swapped back out once loadMods/finishMods below complete).
        Object[] tesrSwap = null;
        if (dist == Dist.CLIENT) {
            installHeadlessMinecraftPlaceholder(log);
            tesrSwap = swapInHeadlessTileDispatcher(log);
        }
        t0 = now();
        Function<Executor, CompletableFuture<Void>> noOp =
                new Function<Executor, CompletableFuture<Void>>() {
                    @Override
                    public CompletableFuture<Void> apply(Executor executor) {
                        return CompletableFuture.<Void>completedFuture(null);
                    }
                };
        try {
            ModLoader.get().loadMods(sync, parallel, noOp, noOp, ticker);
        } catch (net.minecraftforge.fml.LoadingFailedException e) {
            for (net.minecraftforge.fml.ModLoadingException error : e.getErrors()) {
                log.accept("[lifecycle] loadMods error: " + error.formatToString() + " <- "
                        + firstCauseLines(error.getCause()));
            }
            throw e;
        }
        result.add("loadMods(setup+sided)", true, ms(t0), null);

        // ---- finishMods: ENQUEUE_IMC + PROCESS_IMC + COMPLETE + freeze + lock.
        t0 = now();
        ModLoader.get().finishMods(sync, parallel, ticker);
        result.add("finishMods(imc+complete+freeze)", true, ms(t0), null);

        // ---- entity/TESR renderer capture: read what the mods' OWN client-setup registration
        // just populated (real, run above - not re-fired), restore the two placeholders, then
        // build the headless EntityRendererManager the capture layer needs.  This is the only
        // path to a live EntityRendererManager without a real Minecraft client singleton.
        t0 = now();
        finishEntityRendererCapture(result, tesrSwap, log);
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
     * Restores the two placeholders installed around {@code loadMods}/{@code finishMods} (see
     * {@code run()}), reads what the mods' OWN client-setup registration just populated for
     * real, then constructs a minimal headless {@code EntityRendererManager} and installs it
     * into {@code LegacyEntityRenderCapture1165Client}.
     *
     * <p>This is the universal mechanism for entity/TESR rendering in this universe: Dist=CLIENT
     * (set once, at the top of {@code run()}) makes every dist-gated mod-construction pattern -
     * {@code DistExecutor.runWhenOn}/{@code runForDist}, {@code @Mod.EventBusSubscriber} - behave
     * like a real client, so {@code loadMods}'s own SIDED_SETUP dispatch fires
     * {@code FMLClientSetupEvent} for real and every mod's own registration lambda runs
     * (renderer factories, TESR bindings). This method only reads the result and builds the
     * headless consumer object; it posts no events itself. No mod id or class name branching
     * anywhere in this path.</p>
     *
     * <p>The Minecraft placeholder (see {@code installHeadlessMinecraftPlaceholder}) is
     * deliberately NOT cleared here, unlike the TESR dispatcher swap: {@code
     * LegacyTileRenderCapture1165Client}/{@code LegacyEntityRenderCapture1165Client} capture
     * calls happen on an ONGOING basis for as long as this universe is alive (every time the
     * host wants a frame from a modded tile/entity), not only during this boot window, and the
     * real renderer code calls {@code Minecraft.func_71410_x()} directly at RENDER time too -
     * proven live: IronChestTileEntityRenderer NPEs on it mid-render once the placeholder is
     * cleared. The TESR dispatcher does not have this problem because capture goes through the
     * STORED headless instance ({@code LegacyTileRenderCapture1165Client.install}), never through
     * the restored singleton field.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void finishEntityRendererCapture(Result result, Object[] tesrSwap,
            Consumer<String> log) {
        // TESR: restore the dispatcher singleton and hand the populated headless one to the
        // capture layer. Guarded on its own: a TESR failure must never cost the entity
        // renderers below.
        result.tesrRenderers = restoreTileDispatcher(tesrSwap, log);
        try {
            // Step 1: count registered entity renderer factories (diagnostic).
            int factories = countRenderingRegistryFactories();
            result.entityRendererFactories = factories;
            log.accept("[entity-render] RenderingRegistry factories=" + factories);
            if (factories == 0) {
                log.accept("[entity-render] no entity renderer factories registered; entity capture unavailable");
                return;
            }

            // Step 2: allocate a minimal EntityRendererManager via Unsafe (bypasses the
            // constructor which requires TextureManager/ItemRenderer/FontRenderer/GameSettings
            // client objects).  Only the field_78729_o map is needed: func_78713_a reads it,
            // and loadEntityRenderers populates it.
            net.minecraft.client.renderer.entity.EntityRendererManager mgr =
                    allocateHeadlessEntityRendererManager();
            if (mgr == null) {
                log.accept("[entity-render] headless EntityRendererManager allocation failed");
                return;
            }

            // Step 3: populate the manager from the registered factories.
            net.minecraftforge.fml.client.registry.RenderingRegistry.loadEntityRenderers(mgr);
            int loaded = mgr.field_78729_o == null ? 0 : mgr.field_78729_o.size();
            log.accept("[entity-render] EntityRendererManager loaded renderers=" + loaded);

            // Step 4: install into the capture layer.
            LegacyEntityRenderCapture1165Client.install(mgr);
            log.accept("[entity-render] LegacyEntityRenderCapture1165Client installed manager="
                    + (mgr != null ? "ok" : "null") + " renderers=" + loaded);
        } catch (Throwable t) {
            log.accept("[entity-render] finishEntityRendererCapture failed: " + t);
        }
    }

    /**
     * Installs a field-less {@code Minecraft} placeholder so that {@code Minecraft.func_71410_x()}
     * (getInstance) returns a non-null object instead of null, for the LIFE of this universe -
     * never cleared (see {@link #finishEntityRendererCapture}'s javadoc for why).
     *
     * {@code event.getMinecraftSupplier().get()} (which calls this same accessor) then
     * {@code Minecraft.field_71474_y} (GameSettings) directly into a log call; Alex's Mobs'
     * {@code ClientProxy.clientInit()} calls {@code Minecraft.func_71410_x()} then
     * {@code func_175599_af()} (getItemRenderer, a plain field getter) and only STORES the
     * result for a later, deferred renderer construction; and - proven live - the real
     * {@code IronChestTileEntityRenderer.render()} calls {@code Minecraft.func_71410_x()}
     * again at every render, not only during client setup. None of the three need a real
     * client: all three only need a non-null receiver whose null fields are tolerated, exactly
     * the same one-field placeholder this lifecycle already used for
     * {@code TileEntityRendererDispatcher}'s class-init (see {@code initTileDispatcherClass}),
     * generalized to cover the universe's whole lifetime instead of one class-init. A "proper"
     * facade (real GameSettings/ItemRenderer/etc.) is deliberately not built: nothing observed
     * in the real corpus needs one, and a future mod that does should extend this placeholder
     * with the SPECIFIC field its own stack trace names, not a speculative one built ahead of
     * evidence.</p>
     */
    private static void installHeadlessMinecraftPlaceholder(Consumer<String> log) {
        try {
            Field instance = net.minecraft.client.Minecraft.class.getDeclaredField("field_71432_P");
            instance.setAccessible(true);
            if (instance.get(null) != null) {
                return; // a real Minecraft is already installed; never overwrite it
            }
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object placeholder = unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(unsafeField.get(null), net.minecraft.client.Minecraft.class);
            // Client setup registers reload listeners on Minecraft.getResourceManager(); a real,
            // empty manager accepts them (they run with no client packs loaded).
            setInstanceField(placeholder, "field_110451_am",
                    new net.minecraft.resources.SimpleReloadableResourceManager(
                            net.minecraft.resources.ResourcePackType.CLIENT_RESOURCES));
            // ClientRegistry.registerKeyBinding appends to gameSettings.keyBindings.
            Object settings = unsafeClass.getMethod("allocateInstance", Class.class)
                    .invoke(unsafeField.get(null), net.minecraft.client.GameSettings.class);
            setInstanceField(settings, "field_74324_K", new net.minecraft.client.settings.KeyBinding[0]);
            setInstanceField(placeholder, "field_71474_y", settings);
            instance.set(null, placeholder);
            log.accept("[entity-render] headless Minecraft placeholder installed");
        } catch (Throwable t) {
            log.accept("[entity-render] Minecraft placeholder install failed (client registration/rendering may NPE): " + t);
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

    /**
     * Swaps a headless dispatcher into {@code TileEntityRendererDispatcher.field_147556_a}.
     * Returns {@code {headless, original}}, or null when the swap is unavailable.
     */
    private static Object[] swapInHeadlessTileDispatcher(Consumer<String> log) {
        try {
            initTileDispatcherClass(log);
            net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher headless =
                    allocateHeadlessTileEntityRendererDispatcher(log);
            if (headless == null) return null;
            Object original =
                    net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.field_147556_a;
            setStaticField(net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.class,
                    "field_147556_a", headless);
            return new Object[] {headless, original};
        } catch (Throwable t) {
            log.accept("[entity-render] TESR dispatcher swap unavailable: " + t);
            return null;
        }
    }

    /**
     * Initializes the dispatcher class. Its static singleton builds the vanilla renderers, and
     * PistonTileEntityRenderer's constructor reads {@code Minecraft.func_71410_x()}, which is
     * null in the headless universe. A field-less placeholder fills the singleton slot for
     * that one class init only and is cleared again right after.
     */
    private static void initTileDispatcherClass(Consumer<String> log) throws Exception {
        Field instance = net.minecraft.client.Minecraft.class.getDeclaredField("field_71432_P");
        instance.setAccessible(true);
        if (instance.get(null) != null) {
            Class.forName(net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.class.getName());
            return;
        }
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object placeholder = unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(unsafeField.get(null), net.minecraft.client.Minecraft.class);
        instance.set(null, placeholder);
        try {
            Class.forName(net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.class.getName(),
                    true, Legacy1165Lifecycle.class.getClassLoader());
        } finally {
            instance.set(null, null);
        }
        log.accept("[entity-render] TESR dispatcher class initialized");
    }

    /**
     * Restores the original singleton and hands the populated headless dispatcher to capture.
     *
     * @return the number of TESR renderers the headless dispatcher held, or -1 if there was no
     *         dispatcher to restore (the swap-in itself failed) or the count could not be read
     */
    private static int restoreTileDispatcher(Object[] swap, Consumer<String> log) {
        if (swap == null) return -1;
        net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher headless =
                (net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher) swap[0];
        try {
            setStaticField(net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.class,
                    "field_147556_a", swap[1]);
        } catch (Throwable t) {
            log.accept("[entity-render] TESR dispatcher restore failed: " + t);
        }
        LegacyTileRenderCapture1165Client.install(headless);
        int count = countTileEntityRenderers(headless);
        log.accept("[entity-render] TESR renderers=" + count);
        return count;
    }

    /**
     * Allocates a headless {@code TileEntityRendererDispatcher} without calling its private
     * constructor, then sets {@code field_147559_m} (the {@code Map<TileEntityType<?>,
     * TileEntityRenderer<?>>}) via {@code sun.misc.Unsafe} so that the Forge-added
     * {@code setSpecialRendererInternal} method can populate it when
     * {@code ClientRegistry.bindTileEntityRenderer} is called, and so that
     * {@code func_147547_b} can look up renderers later.
     *
     * <p>The dispatcher is swapped into the static singleton {@code field_147556_a} by the
     * caller before the event fires, and restored afterwards.</p>
     */
    private static net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher
            allocateHeadlessTileEntityRendererDispatcher(Consumer<String> log) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);

            Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
            net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher dispatcher =
                    (net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher)
                    allocate.invoke(unsafe,
                            net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.class);

            // field_147559_m: private final Map<TileEntityType<?>, TileEntityRenderer<?>>.
            // Must be set via Unsafe because it's declared final.
            Field mapField = net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.class
                    .getDeclaredField("field_147559_m");
            mapField.setAccessible(true);
            Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
            Method putObject = unsafeClass.getMethod("putObject",
                    Object.class, long.class, Object.class);
            long offset = ((Long) objectFieldOffset.invoke(unsafe, mapField)).longValue();
            putObject.invoke(unsafe, dispatcher, offset, new java.util.HashMap<>());
            return dispatcher;
        } catch (Throwable t) {
            log.accept("[entity-render] allocateHeadlessTileEntityRendererDispatcher failed: " + firstCauseLines(t));
            return null;
        }
    }

    /** Reads the number of registered renderers in a TileEntityRendererDispatcher via field_147559_m. */
    @SuppressWarnings("rawtypes")
    private static int countTileEntityRenderers(
            net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher dispatcher) {
        try {
            Field mapField = net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher.class
                    .getDeclaredField("field_147559_m");
            mapField.setAccessible(true);
            Object map = mapField.get(dispatcher);
            if (map instanceof java.util.Map) return ((java.util.Map) map).size();
        } catch (Throwable ignored) {
        }
        return -1;
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
