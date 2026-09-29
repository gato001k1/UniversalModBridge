package dev.umb.legacy.legacyside;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import dev.umb.legacy.api.LegacyUniverse;
import dev.umb.legacy.api.RegistrySnapshot;
import dev.umb.legacy.api.StageResult;
import dev.umb.legacy.api.UniverseConfig;
import dev.umb.legacy.legacyside.render.LegacyRenderCapture;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.asm.FMLSanityChecker;
import cpw.mods.fml.relauncher.FMLInjectionData;
import cpw.mods.fml.relauncher.FMLLaunchHandler;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;

import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import net.minecraftforge.classloading.FMLForgePlugin;

/**
 * Drives the REAL FML Loader without LaunchWrapper's main/tweaker/coremod machinery.
 *
 * <p>What LaunchWrapper + FMLServerTweaker + FMLInjectionAndSortingTweaker + CoreModManager +
 * FMLDeobfTweaker would normally do, and what we do instead:</p>
 * <table>
 *   <tr><td>FMLTweaker.acceptOptions</td><td>installs FMLSecurityManager - SKIPPED, and that is the
 *       single reason this boots at all: {@code System.setSecurityManager} throws UOE on Java 25.</td></tr>
 *   <tr><td>FMLLaunchHandler.setupServer</td><td>we set {@code FMLLaunchHandler.side} and
 *       {@code FMLRelaunchLog.side}/{@code minecraftHome} directly and call the package-private
 *       {@code FMLInjectionData.build}.</td></tr>
 *   <tr><td>CoreModManager.handleLaunch</td><td>PARTIALLY reproduced. Its PatchingTransformer
 *       registration is skipped (our runtime jar is already binpatched) and its
 *       {@code FMLInjectionData.containers} seeding is done by hand (two root plugin names, two
 *       source-jar statics) - but its OTHER job, discovering every jar's own
 *       {@code FMLCorePlugin} manifest attribute and registering the ASM transformers it declares,
 *       is real: see {@link LegacyCoremodLoader}. HBM ships no coremod of its own, which is why this
 *       table used to say the whole step was skipped; the surprise-test corpus
 *       Mekanism, ...), so leaving it skipped meant their classes verified against unpatched
 *       bytecode and threw whatever the coremod's own transformer exists to prevent.</td></tr>
 *   <tr><td>FMLDeobfTweaker.injectIntoClassLoader</td><td>we register the same transformer list
 *       minus DeobfuscationTransformer (SRG environment) and minus ModAccessTransformer (see
 *       README), then call {@code Loader.injectData} + {@code Loader.instance()}.</td></tr>
 *   <tr><td>Minecraft.startGame / DedicatedServer.startServer</td><td>we call
 *       {@code net.minecraft.init.Bootstrap.func_151354_b()} (which is what populates
 *       {@code net.minecraft.init.Blocks}/{@code Items} from the registries) and then
 *       {@code FMLCommonHandler.beginLoading}, in that order - the order the real client uses.</td></tr>
 * </table>
 */
public final class LegacyDriver implements LegacyUniverse {

    private final List<StageResult> stages = new ArrayList<StageResult>();
    private UniverseConfig config;
    private boolean booted;

    @Override
    public void boot(UniverseConfig cfg) throws Exception {
        this.config = cfg;
        File gameDir = new File(cfg.gameDir());
        File forgeJar = new File(cfg.forgeJar());
        LaunchClassLoader lcl = (LaunchClassLoader) Launch.classLoader;

        // ---- 1. LaunchWrapper exclusions that FMLLaunchHandler/FMLServerTweaker would add.
        // Only the TRANSFORMER exclusions are reproduced: the classloader exclusions in real FML
        // exist to share classes with the application classpath, which is exactly what an isolated
        // legacy universe must not do.
        lcl.addTransformerExclusion("cpw.mods.fml.repackage.");
        lcl.addTransformerExclusion("cpw.mods.fml.relauncher.");
        lcl.addTransformerExclusion("cpw.mods.fml.common.asm.transformers.");
        lcl.addTransformerExclusion("cpw.mods.fml.common.asm.transformers.deobf.");
        lcl.addTransformerExclusion("cpw.mods.fml.common.patcher.");
        lcl.addTransformerExclusion("net.minecraftforge.classloading.");
        lcl.addTransformerExclusion("net.minecraftforge.transformers.");

        // ---- 2. side, before anything can read it (@SidedProxy, coremods, MinecraftForge.initialize)
        // The embedded universe used by the 26.2 client is an integrated CLIENT even though
        // bridge callbacks are dispatched from the host's server tick. Standalone probes keep
        // the historical SERVER default. Client-only coremods otherwise abort during discovery.
        Side launchSide = "CLIENT".equalsIgnoreCase(System.getProperty("umb.legacy.side", "SERVER"))
                ? Side.CLIENT : Side.SERVER;
        Statics.set(FMLLaunchHandler.class, "side", launchSide);
        Statics.set(FMLRelaunchLog.class, "side", launchSide);
        Statics.set(FMLRelaunchLog.class, "minecraftHome", gameDir);

        // ---- 3. FMLInjectionData.build(mcHome, loader): reads fmlversion.properties off the
        // classloader. Loader's constructor refuses to run if mccversion != "1.7.10".
        Statics.call(FMLInjectionData.class, "build",
                new Class<?>[]{File.class, LaunchClassLoader.class},
                new Object[]{gameDir, lcl});
        if (!"1.7.10".equals(Statics.get(FMLInjectionData.class, "mccversion"))) {
            throw new IllegalStateException("fmlversion.properties says mcversion="
                    + Statics.get(FMLInjectionData.class, "mccversion"));
        }

        // ---- 4. the two root core plugins' mod containers + the File their getSource() returns.
        // InjectedModContainer captures getSource() eagerly and Loader.loadMods() logs
        // mod.getSource().getName() for EVERY container, so a null here is an NPE at CONSTRUCTING.
        FMLInjectionData.containers.add("cpw.mods.fml.common.FMLContainer");
        FMLInjectionData.containers.add("net.minecraftforge.common.ForgeModContainer");
        FMLSanityChecker.fmlLocation = forgeJar;
        FMLForgePlugin.forgeLocation = forgeJar;
        FMLForgePlugin.RUNTIME_DEOBF = Boolean.parseBoolean(
                System.getProperty("umb.legacy.runtimeDeobf", "false"));

        // ---- 4b. third-party FMLCorePlugin coremods, discovered from whatever is already on the
        // isolated loader's own classpath (see LegacyCoremodLoader's javadoc for why this step used
        // to be skipped and what real FML's CoreModManager does here). Must run before step 5's
        // transformer list AND before any mod class below has a chance to verify, so its own
        // transformer sees the class first - the same order real FML uses (coremods register during
        // the tweaker chain, before FMLDeobfTweaker adds the standard FML list).
        List<String> coremods = LegacyCoremodLoader.discoverAndRegister(lcl,
                msg -> FMLRelaunchLog.info("%s", msg));
        if (!coremods.isEmpty()) {
            FMLRelaunchLog.info("[umb-legacy] coremods discovered: %s", coremods);
        }

        // ---- 5. transformers
        List<String> registered = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();
        for (String name : config.transformers()) {
            int before = lcl.getTransformers().size();
            lcl.registerTransformer(name);
            List<IClassTransformer> now = lcl.getTransformers();
            if (now.size() > before) {
                registered.add(name + " -> " + now.get(now.size() - 1).getClass().getName());
            } else {
                // LaunchClassLoader.registerTransformer swallows every exception
                failed.add(name);
            }
        }
        FMLRelaunchLog.info("[umb-legacy] transformers registered: %s", registered);
        if (!failed.isEmpty()) {
            throw new IllegalStateException("transformers failed to register: " + failed);
        }

        // ---- 6. what FMLDeobfTweaker does last
        Loader.injectData(FMLInjectionData.data());
        Loader.instance();

        // ---- 7. what Minecraft.startGame does BEFORE beginMinecraftLoading (bytecode offset 310 vs
        // 632): populate the vanilla registries so net.minecraft.init.Blocks/Items resolve.
        net.minecraft.init.Bootstrap.func_151354_b();
        assertVanillaRegistered();

        // ---- 7b. widen the fixed-size vanilla arrays whose own mod-side workarounds are the thing
        // that breaks on a modern JDK (see LegacyCompat).
        LegacyCompat.widenPotionRegistry(Integer.getInteger("umb.legacy.potionArraySize", 256).intValue());

        // ---- 8. hand FML its sided handler. beginLoading also reflectively runs
        // MinecraftForge.initialize() (OreDictionary, UsernameCache, FluidRegistry defaults).
        FMLCommonHandler.instance().beginLoading(new UmbSidedHandler(gameDir));

        booted = true;
    }

    /** Cheap proof that the runtime jar really is Forge-patched and AT-applied. */
    private void assertVanillaRegistered() throws Exception {
        if (net.minecraft.init.Blocks.field_150348_b == null) {
            throw new IllegalStateException("net.minecraft.init.Blocks.stone is null after Bootstrap.register()");
        }
        if (net.minecraft.init.Items.field_151042_j == null) {
            throw new IllegalStateException("net.minecraft.init.Items.iron_ingot is null after Bootstrap.register()");
        }
        assertAccessTransformerApplied();
    }

    /**
     * GENERALITY fix (hostagent-purge #13): this used to be an unconditional check, hardcoded to
     * exactly one member HBM's OWN bundled {@code HBM_at.cfg} (an {@code FMLAT:} manifest entry
     * inside HBM's own jar) targets - {@code net.minecraft.block.Block.func_149642_a} - with an
     * error message calling it out by name. That AT is not something THIS DRIVER applies:
     * {@code ModAccessTransformer} is deliberately never registered here at all (see this class's
     * own javadoc table, "minus ModAccessTransformer") - whatever mod-specific AT a mod's jar
     * declares must already be pre-baked into the checked-in runtime jar
     * preparation step that is not part of any build script in this repo (confirmed: no
     * {@code build-legacy.ps1}/harness step regenerates that jar). A genuinely different mod's own
     * AT config would need that same offline preparation redone for ITS OWN target member -
     * file) and is documented as such in {@code HOSTAGENT-PURGE.md}, not silently glossed over.
     * <p>What this method fixes: the check itself no longer hardcodes HBM's target member. Three
     * system properties name the class/method/parameter-types to probe instead, defaulting to
     * exactly today's HBM check - so default behaviour for the test mod (and its error message) is
     * unchanged unless a caller actually overrides them for a different mod's own AT target.
     */
    private void assertAccessTransformerApplied() throws Exception {
        String className = System.getProperty("umb.legacy.at.class", "net.minecraft.block.Block");
        String methodName = System.getProperty("umb.legacy.at.method", "func_149642_a");
        String paramSpec = System.getProperty("umb.legacy.at.paramTypes",
                "net.minecraft.world.World,int,int,int,net.minecraft.item.ItemStack");
        Class<?> owner = Class.forName(className);
        Class<?>[] paramTypes = resolveParamTypes(paramSpec);
        int mods;
        try {
            mods = owner.getDeclaredMethod(methodName, paramTypes).getModifiers();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("access-transformer check target " + className + "#" + methodName
                    + "(" + paramSpec + ") does not exist on the runtime jar"
                    + " (see umb.legacy.at.* system properties)", e);
        }
        if (!java.lang.reflect.Modifier.isPublic(mods)) {
            throw new IllegalStateException("expected access-transformer target " + className + "#" + methodName
                    + " is not public - the runtime jar was not pre-baked with the expected AT"
                    + " (see umb.legacy.at.* system properties to point this check at a different mod's own target)");
        }
    }

    private static Class<?>[] resolveParamTypes(String spec) throws ClassNotFoundException {
        if (spec == null || spec.trim().isEmpty()) {
            return new Class<?>[0];
        }
        String[] parts = spec.split(",");
        Class<?>[] out = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = primitiveOrClass(parts[i].trim());
        }
        return out;
    }

    private static Class<?> primitiveOrClass(String name) throws ClassNotFoundException {
        if ("int".equals(name)) return int.class;
        if ("boolean".equals(name)) return boolean.class;
        if ("long".equals(name)) return long.class;
        if ("float".equals(name)) return float.class;
        if ("double".equals(name)) return double.class;
        if ("short".equals(name)) return short.class;
        if ("byte".equals(name)) return byte.class;
        if ("char".equals(name)) return char.class;
        return Class.forName(name);
    }

    @Override
    public List<StageResult> lifecycle() {
        if (!booted) {
            stages.add(new StageResult("CONSTRUCTING", false, 0L, "IllegalStateException",
                    "boot() was never completed", null));
            return stages;
        }
        // Loader.loadMods()          : LOADING -> CONSTRUCTING -> PREINITIALIZATION
        // Loader.preinitializeMods() : PREINITIALIZATION events -> INITIALIZATION
        // Loader.initializeMods()    : INITIALIZATION + POSTINITIALIZATION + AVAILABLE + freezeData
        if (!run("CONSTRUCTING", 0)) {
            return stages;
        }
        if (!run("PREINIT", 1)) {
            return stages;
        }
        run("INIT+POSTINIT", 2);
        return stages;
    }

    private boolean run(String stage, int which) {
        long t0 = System.nanoTime();
        try {
            switch (which) {
                case 0:
                    Loader.instance().loadMods();
                    // Mod containers and their source packs now exist. Install the native-free
                    // client singleton and mount every discovered mod resource before PREINIT;
                    // ordinary client proxies may read Minecraft.func_71410_x() and client-only
                    // static model initializers may resolve their own mod resources safely.
                    // Renderer/keybinding discovery remains in the later capture path.
                    if (FMLCommonHandler.instance().getSide() == Side.CLIENT) {
                        LegacyRenderCapture.installPreInitClientFacade();
                    }
                    break;
                case 1:
                    Loader.instance().preinitializeMods();
                    break;
                default:
                    Loader.instance().initializeMods();
                    break;
            }
            stages.add(StageResult.ok(stage, ms(t0)));
            return true;
        } catch (Throwable t) {
            StringWriter w = new StringWriter();
            t.printStackTrace(new PrintWriter(w));
            stages.add(new StageResult(stage, false, ms(t0), t.getClass().getName(),
                    String.valueOf(t.getMessage()), w.toString()));
            return false;
        }
    }

    private static long ms(long t0) {
        return (System.nanoTime() - t0) / 1000000L;
    }

    @Override
    public RegistrySnapshot snapshot() throws Exception {
        return RegistrySnapshotter.take(FMLCommonHandler.instance().getSide().name());
    }

    @Override
    public void close() {
        // Nothing is safely reclaimable: FML installed a TracingPrintStream over System.out,
        // registered shutdown hooks and started log4j threads. The universe is single-shot.
    }
}
