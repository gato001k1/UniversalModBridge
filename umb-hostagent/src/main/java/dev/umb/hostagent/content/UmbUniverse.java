package dev.umb.hostagent.content;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileHandle;
import dev.umb.hostagent.AgentLog;
import dev.umb.hostagent.HostAgent;
import dev.umb.legacy.boot.LegacyClasspath;
import dev.umb.legacy.boot.LegacyLoader;
import net.minecraft.launchwrapper.Launch;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * G2 task 2: R4's lazy, synchronous, exactly-once universe boot, embedded inside the LIVE 26.2
 * client's own JVM -- not a separate process. This is the missing piece Lane A and Lane B could
 * not build independently: it is the ONLY class that actually constructs the legacy loader
 * hierarchy at runtime and reflectively wires up Lane A's real {@code LegacyBridgeImpl}, so
 * {@link dev.umb.hostagent.content.UmbBridgeHost} (which already had the boot-once/never-crash
 * bookkeeping from Lane B) has something real to call instead of a test-only fake.
 *
 * <h2>Three classloader tiers, and why</h2>
 * <pre>
 *   tier 1 (host)     the 26.2 client's own system/app classloader
 *                      client.jar + umb-hostagent.jar, which bundles:
 *                        dev.umb.hostagent.*        (this code)
 *                        dev.umb.bridge.api.*       (the boundary contract mirror, task 1)
 *                        joptsimple.* (LaunchWrapper is appended from the player's verified
 *                        official library by HostAgent before this class resolves)
 *                        dev.umb.legacy.{boot,api}.*
 *                        org.objectweb.asm.*        (already bundled, unrelated to this task)
 *                              |  parent
 *                              v
 *   tier 2            {@link UniverseRootLoader} -- owns ONLY the legacy universe's log4j
 *                      2.0-beta9 (see its javadoc); everything else falls through to tier 1
 *                              |  parent
 *                              v
 *   tier 3            {@link LegacyLoader} -- the 1.7.10/FML universe (same class Lane A's
 *                      Bootstrap.java and M1ProbeMain.java use for the standalone harness)
 * </pre>
 *
 * <p><b>Why {@code dev.umb.bridge.api} lives on tier 1 and not its own isolated tier (a literal
 * reading of umb-legacy/README.md's "universe root ... with launchwrapper/jopt-simple/log4j/
 * umb-legacy-{boot,api} on it, parented to the PLATFORM loader" would put ALL of those, including
 * the boundary contract, on a loader isolated from the host):</b> {@code LegacyBridgeImpl.boot}
 * takes a {@code HostWorld} parameter and this class casts the reflectively-constructed
 * {@code LegacyBridgeImpl} instance straight to {@code dev.umb.bridge.api.LegacyBridge} with a
 * plain {@code (LegacyBridge)} cast (see {@link #boot}) -- for that cast, and for
 * {@code HostWorldImpl}/{@code HostPlayerImpl} to be usable across the boundary at all, the
 * {@code Class} object for every {@code dev.umb.bridge.api.*} type must be IDENTICAL on both
 * sides. Two classloader trees rooted at the platform loader (tier 1's app classloader and an
 * isolated tier-2-equivalent) are siblings, not ancestor/descendant -- neither can hand the other
 * its classes, so identity sharing between them would need a {@code java.lang.reflect.Proxy} (or
 * hand-written reflection) forwarding EVERY method of EVERY boundary type, translating
 * {@code StackData}/{@code SlotData} objects field-by-field on every call. That is real, working,
 * but substantially more moving parts for zero benefit: {@code dev.umb.bridge.api} is
 * {@code java.*}-only, ships no ASM/Gson/etc, and cannot collide with anything on the 26.2
 * classpath by construction. So it rides on tier 1 (parent-delegated all the way down, exactly
 * like {@code dev.umb.legacy.api} and {@code net.minecraft.launchwrapper} already are inside
 * {@link LegacyLoader} -- see its javadoc), and ONLY log4j -- the one package with a real,
 * verified collision risk (26.2 ships its own, newer log4j on tier 1) -- gets its own isolated
 * tier. This is a deliberate, flagged deviation from the README's literal tier-2 jar list; see
 * {@code research/out/legacy/g2-integration-progress.md} for the full writeup.</p>
 *
 * <h2>Thread and JVM-flag requirements</h2>
 * <p>{@code boot()} always runs its actual work on a freshly spawned thread named exactly
 * {@code "Client thread"} for an integrated client, or {@code "Server thread"} for the
 * historical headless default. FMLCommonHandler.getEffectiveSide() keys off that literal
 * thread name -- see {@code dev.umb.legacy.boot.Bootstrap}. It never reuses the calling thread.</p>
 *
 * <p>The legacy facades use {@code sun.misc.Unsafe} and reflective access into JDK internals that
 * need {@code --sun-misc-unsafe-memory-access=allow} plus 8 {@code --add-opens} flags (see
 * {@link #REQUIRED_ADD_OPENS}) on the JVM that is ALREADY RUNNING -- these cannot be added after
 * the fact, so {@link #boot} checks {@link ManagementFactory#getRuntimeMXBean()}'s input arguments
 * before attempting anything else and fails loudly and specifically (not with a buried
 * {@code InaccessibleObjectException} three stack frames into FML) if any are missing.</p>
 */
public final class UmbUniverse implements LegacyBridge {

    private static final List<String> REQUIRED_ADD_OPENS = Collections.unmodifiableList(Arrays.asList(
            "java.base/java.lang=ALL-UNNAMED",
            "java.base/java.lang.reflect=ALL-UNNAMED",
            "java.base/java.util=ALL-UNNAMED",
            "java.base/java.util.concurrent=ALL-UNNAMED",
            "java.base/java.net=ALL-UNNAMED",
            "java.base/java.nio=ALL-UNNAMED",
            "java.base/java.io=ALL-UNNAMED",
            "java.base/java.text=ALL-UNNAMED"));

    private volatile LegacyBridge real;

    // ---------------------------------------------------------------- LegacyBridge (forwarding)

    @Override
    public synchronized void boot(HostWorld world) throws Exception {
        if (real != null) {
            throw new IllegalStateException("UmbUniverse is single-shot; boot() was already called");
        }
        checkJvmFlags();

        File repo = repoRoot();
        AgentLog.line("UmbUniverse: repo=" + repo);

        File libsDir = firstDirectory(new File(repo, "research/visual/mc1710-native/libraries"),
                new File(repo, "inputs/libraries/1.7.10"));
        File classpathFile = firstFile(new File(repo, "research/visual/mc1710-native/classpath.txt"),
                new File(repo, "inputs/legacy-1.7.10-classpath.txt"));
        // The production launcher leaves this unset and therefore keeps the historical
        // <repo>/build/legacy resolution.  Scratch launchers may point at a copied jar set so a
        // test JVM never opens the live production jars.  This is a path override only: the
        // classpath shape and file names remain universal across all legacy mods.
        File build = new File(System.getProperty("umb.legacy.buildDir",
                new File(repo, "build/legacy").getAbsolutePath()));
        File runtimeJar = requireFile(firstFile(new File(build, "1.7.10-forge-srg-runtime-fields.jar"),
                new File(repo, "inputs/1.7.10-forge-srg-runtime-fields.jar")));
        File forgeJar = requireFile(firstFile(new File(build, "forge-1.7.10-10.13.4.1614-srg.jar"),
                new File(repo, "inputs/forge-1.7.10-10.13.4.1614-srg.jar")));
        File legacysideJar = requireFile(firstFile(new File(build, "umb-legacy-legacyside.jar"),
                new File(repo, "umb-legacy-legacyside.jar")));
        File apiJar = requireFile(firstFile(new File(build, "umb-legacy-api.jar"),
                new File(repo, "umb-legacy-api.jar")));
        File log4jApi = requireFile(findOne(libsDir, "log4j-api-", ".jar"));
        File log4jCore = requireFile(findOne(libsDir, "log4j-core-", ".jar"));
        File assetsDir = firstDirectory(new File(repo, "research/visual/mc1710-native/assets"),
                new File(repo, "inputs/assets/1.7.10"));
        File log4jCfg = requireFile(firstFile(new File(repo, "umb-legacy/resources/log4j2-legacy.xml"),
                new File(repo, "log4j-client.xml")));

        String instance = safeInstanceName(System.getProperty("umb.legacy.instance", "default"));
        File defaultGameDir = new File(repo, "research/out/legacy/instances/" + instance);
        File gameDir = new File(System.getProperty("umb.legacy.gameDir", defaultGameDir.getAbsolutePath()));
        File gameRoot = gameDir.getCanonicalFile();
        // Benchmark instances run with umb.repo pointing at a runtime-repo copy while their
        // per-instance gameDir stays under the launching checkout, so containment in umb.repo is
        // not an invariant. Only an explicitly configured, canonical path is required here.
        if (!gameRoot.isAbsolute()) {
            throw new IllegalStateException("UmbUniverse gameDir must be absolute: " + gameRoot);
        }
        File modsDir = new File(gameDir, "mods");
        Files.createDirectories(gameDir.toPath());
        Files.createDirectories(modsDir.toPath());
        Files.createDirectories(new File(gameDir, "config").toPath());
        AgentLog.line("UmbUniverse: instance=" + instance + " gameDir=" + gameRoot);

        // GENERALITY fix (hostagent-purge headline item): this used to hardcode
        // `new File(repo, "research/mods-hbm/HBM-NTM-1.0.27_X5771.jar")` and stage exactly that
        // one jar, unconditionally, on every boot - so the production boot path could never load
        // any mod other than the test corpus's HBM jar, no matter what the caller actually asked
        // for. The mod jar list now comes from the same convention every other per-run knob
        // already uses (HostAgent's `-javaagent` kv args - see modjars() javadoc): a `modjars=`
        // argument is a comma-separated list of jar paths, threaded through exactly like
        // `snapshot=`/`ns=`/`lang=`. When the caller passes nothing at all (HostAgent.modJars()
        // is empty), the default is the ORIGINAL single HBM jar, so today's behavior for the test
        // mod is byte-identical whether or not anyone ever passes modjars= explicitly.
        //
        // Multi-mod: this already stages every jar it is given, in order, into the same mods dir
        // - FML's own mod-discovery scans the whole directory, not a single named file, so nothing
        // else in this method assumes exactly one jar. What staging alone does NOT yet solve for a
        // true multi-mod boot: (1) LegacyBridge (dev.umb.bridge.api) and every UmbLegacy* host
        // object are still keyed by a single implicit "the legacy universe", not per-mod, so two
        // legacy mods sharing one FML instance is already how 1.7.10/Forge itself works and needs
        // no new plumbing HERE; (2) HostAgent's OWN namespace() is still a single string, so
        // per-block/per-item namespace still has to come from each row's own id (already true
        // upstream in Registrar/PackGen - see VanillaItemBridge.textureRef/canResolve, which
        // already read the namespace out of each icon string per-asset) rather than from a single
        // agent-wide `ns=`; (3) the snapshot/rendermap/pack pipeline upstream of this class is
        // still one-file-per-mod-run (harness/legacy.ps1), so a real multi-mod boot today would
        // need those artifacts merged or loaded as a list before this class ever sees them - that
        // is a harness-level concern, out of this lane's ownership, not a umb-hostagent one.
        List<File> modJars = new ArrayList<>();
        List<Path> configured = HostAgent.modJars();
        if (configured == null || configured.isEmpty()) {
            // No historical-default jar lives here anymore: silently booting one specific
            // mod when the caller configured nothing is exactly the failure shape that has
            // bitten this project repeatedly. Every real flow configures mod jars - the game
            // via modjars=, the e2e probe via its scenario file, tests via HostAgent.configure.
            throw new IllegalStateException("UmbUniverse: no mod jars configured (HostAgent.modJars()"
                    + " is empty) - pass modjars=<jar>[,<jar>...] on the -javaagent argument or"
                    + " configure them explicitly; refusing to boot a guessed default jar");
        } else {
            for (Path p : configured) {
                modJars.add(requireFile(p.toFile()));
            }
        }
        List<File> stagedModJars = new ArrayList<>();
        for (File modJar : modJars) {
            stagedModJars.add(stageMod(modJar, modsDir));
        }
        // Legacy mods open files relative to the process working directory (e.g. MC Helicopters'
        // ".\config\mcheli.cfg"); the 26.2 client's cwd is its own game dir, which has no config/.
        Files.createDirectories(new File(System.getProperty("user.dir"), "config").toPath());
        AgentLog.line("UmbUniverse: staged " + stagedModJars.size() + " mod jar(s): " + stagedModJars);

        // ---- tier 2: isolate ONLY log4j from the host's own (newer) log4j ----
        UniverseRootLoader tier2 = new UniverseRootLoader(
                LegacyClasspath.toUrls(Arrays.asList(log4jApi, log4jCore)),
                UmbUniverse.class.getClassLoader());

        // ---- tier 3: the legacy universe itself, exactly Bootstrap.java's/M1ProbeMain's jar set ----
        List<File> cp = LegacyClasspath.forBoot(classpathFile, runtimeJar, forgeJar,
                Collections.<File>emptyList(), Arrays.asList(legacysideJar, apiJar));
        URL[] urls = LegacyClasspath.toUrls(cp);
        LegacyLoader tier3 = new LegacyLoader(urls, tier2);
        AgentLog.line("UmbUniverse: tier3 sources=" + cp.size() + " jars, tier2=log4j only");

        // ---- the statics FML reads straight out of LaunchWrapper (Bootstrap.java's exact dance) ----
        Launch.minecraftHome = gameDir;
        Launch.assetsDir = assetsDir;
        Launch.classLoader = tier3;
        Map<String, Object> blackboard = new HashMap<>();
        Launch.blackboard = blackboard;
        Map<String, String> launchArgs = new LinkedHashMap<>();
        launchArgs.put("--version", "1.7.10-Forge10.13.4.1614-1.7.10");
        launchArgs.put("--gameDir", gameDir.getAbsolutePath());
        launchArgs.put("--assetsDir", assetsDir.getAbsolutePath());
        blackboard.put("launchArgs", launchArgs);
        blackboard.put("fml.deobfuscatedEnvironment", Boolean.TRUE);
        blackboard.put("Tweaks", new ArrayList<>());
        blackboard.put("TweakClasses", new ArrayList<String>());
        blackboard.put("modList", new HashMap<String, Map<String, String>>());
        blackboard.put("coremodList", new ArrayList<>());

        // ---- system properties LegacyBridgeImpl.buildConfig() reads (same names Bootstrap.java sets) ----
        System.setProperty("umb.repo", repo.getAbsolutePath());
        System.setProperty("umb.legacy.forgeJar", forgeJar.getAbsolutePath());
        System.setProperty("umb.legacy.out", gameDir.getAbsolutePath());
        System.setProperty("umb.legacy.gameDir", gameDir.getAbsolutePath());
        System.setProperty("umb.legacy.modsDir", modsDir.getAbsolutePath());
        System.setProperty("umb.legacy.assetsDir", assetsDir.getAbsolutePath());
        System.setProperty("java.awt.headless", "true");
        System.setProperty("log4j.configurationFile", log4jCfg.getAbsolutePath());
        System.setProperty("log4j2.disable.jmx", "true");
        System.setProperty("fml.queryResult", "confirm");
        System.setProperty("fml.doNotBackup", "true");
        System.setProperty("fml.ignoreInvalidMinecraftCertificates", "true");
        System.setProperty("fml.ignorePatchDiscrepancies", "true");

        // FML 1.7.10 uses both FMLLaunchHandler.side and the literal thread name when deciding
        // effective side. Keep them coherent for the integrated client; standalone probes omit
        // umb.legacy.side and retain Server thread behavior.
        long t0 = System.nanoTime();
        Boot run = new Boot(tier3, world);
        String side = System.getProperty("umb.legacy.side", "SERVER");
        String threadName = "CLIENT".equalsIgnoreCase(side) ? "Client thread" : "Server thread";
        Thread t = new Thread(run, threadName);
        t.setContextClassLoader(tier3);
        t.setDaemon(false);
        t.start();
        t.join();
        long ms = (System.nanoTime() - t0) / 1_000_000L;

        if (run.failure != null) {
            throw new IllegalStateException("legacy universe boot failed after " + ms + " ms", run.failure);
        }
        this.real = run.bridge;
        AgentLog.line("UmbUniverse: real LegacyBridgeImpl booted in " + ms + " ms, class="
                + run.bridge.getClass().getName() + " loader=" + run.bridge.getClass().getClassLoader());
    }

    @Override
    public boolean isBooted() {
        LegacyBridge r = real;
        if (r == null) {
            return false;
        }
        try {
            return r.isBooted();
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.isBooted", t, 3);
            return false;
        }
    }

    @Override
    public TileHandle createTile(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = real;
        if (r == null) {
            return null;
        }
        return r.createTile(legacyBlockId, x, y, z);
    }

    @Override
    public ActivationResult activate(String legacyBlockId, int x, int y, int z, HostPlayer player, int side,
                                      float hitX, float hitY, float hitZ) {
        LegacyBridge r = real;
        if (r == null) {
            return ActivationResult.DECLINED;
        }
        return r.activate(legacyBlockId, x, y, z, player, side, hitX, hitY, hitZ);
    }

    @Override
    public void clicked(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        LegacyBridge r = real;
        if (r != null) {
            r.clicked(legacyBlockId, x, y, z, player);
        }
    }

    // ---- PART 1: placement / removal / neighbor-change (forwarding, same null-safe shape as above) ----

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        LegacyBridge r = real;
        if (r != null) {
            r.placedBy(legacyBlockId, x, y, z, player);
        }
    }

    @Override
    public void added(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = real;
        if (r != null) {
            r.added(legacyBlockId, x, y, z);
        }
    }

    @Override
    public void neighborChanged(String legacyBlockId, int x, int y, int z, String neighborLegacyBlockId) {
        LegacyBridge r = real;
        if (r != null) {
            r.neighborChanged(legacyBlockId, x, y, z, neighborLegacyBlockId);
        }
    }

    @Override
    public void broken(String legacyBlockId, int x, int y, int z, int meta, HostPlayer player) {
        LegacyBridge r = real;
        if (r != null) {
            r.broken(legacyBlockId, x, y, z, meta, player);
        }
    }

    @Override
    public boolean canPlaceAt(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = real;
        if (r == null) {
            return true;
        }
        return r.canPlaceAt(legacyBlockId, x, y, z);
    }

    // ---- PART 2: item-side interaction (forwarding) ----

    @Override
    public StackData useItemRightClick(String legacyItemId, HostPlayer player) {
        LegacyBridge r = real;
        if (r == null) {
            return null;
        }
        return r.useItemRightClick(legacyItemId, player);
    }

    @Override
    public ItemUseResult useItemOnBlock(String legacyItemId, HostPlayer player, int x, int y, int z, int side,
                                         float hitX, float hitY, float hitZ) {
        LegacyBridge r = real;
        if (r == null) {
            return ItemUseResult.DECLINED;
        }
        return r.useItemOnBlock(legacyItemId, player, x, y, z, side, hitX, hitY, hitZ);
    }

    // ---- INPUT-BRIDGE: forwarding (same null-safe shape as above) ----
    // (Without these, the interface defaults silently swallow input AND effects on
    // the final hop: acceptInput returns false, drainClientEffects returns empty.
    // Found live while debugging the input lane — every sibling forwards.)

    @Override
    public boolean acceptInput(HostPlayer player, dev.umb.bridge.api.InputData input) {
        LegacyBridge r = real;
        if (r == null) {
            return false;
        }
        return r.acceptInput(player, input);
    }

    @Override
    public java.util.List<dev.umb.bridge.api.EffectData> drainClientEffects() {
        LegacyBridge r = real;
        if (r == null) {
            return java.util.Collections.emptyList();
        }
        return r.drainClientEffects();
    }

    // ---- ENTITY-BRIDGE: forwarding ----

    @Override
    public EntityHandle restoreEntity(byte[] nbt) {
        LegacyBridge r = real;
        if (r == null) {
            return null;
        }
        return r.restoreEntity(nbt);
    }

    @Override
    public void tickTile(TileHandle t) {
        LegacyBridge r = real;
        if (r != null) {
            r.tickTile(t);
        }
    }

    @Override
    public void shutdown() {
        LegacyBridge r = real;
        if (r != null) {
            r.shutdown();
        }
    }

    // ---------------------------------------------------------------- internals

    private static final class Boot implements Runnable {
        private final LegacyLoader loader;
        private final HostWorld world;
        LegacyBridge bridge;
        Throwable failure;

        Boot(LegacyLoader loader, HostWorld world) {
            this.loader = loader;
            this.world = world;
        }

        @Override
        public void run() {
            try {
                Class<?> c = Class.forName("dev.umb.legacy.legacyside.LegacyBridgeImpl", true, loader);
                if (c.getClassLoader() != loader) {
                    throw new IllegalStateException("LegacyBridgeImpl leaked to " + c.getClassLoader());
                }
                Object instance = c.getDeclaredConstructor().newInstance();
                // Valid direct cast, not reflection: dev.umb.bridge.api.LegacyBridge is
                // parent-delegated all the way from tier3 through tier2 to tier1, so `c`'s
                // implemented LegacyBridge interface IS this class's own LegacyBridge.class.
                LegacyBridge b = (LegacyBridge) instance;
                b.boot(world);
                this.bridge = b;
            } catch (Throwable e) {
                Throwable cause = e;
                if (e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null) {
                    cause = e.getCause();
                }
                this.failure = cause;
            }
        }
    }

    private static void checkJvmFlags() {
        List<String> args;
        try {
            args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        } catch (Throwable t) {
            AgentLog.loud("UmbUniverse: cannot read JVM input arguments (" + t
                    + ") - proceeding without the flag pre-check, a missing flag will surface as its own error");
            return;
        }
        boolean unsafeAllowed = args.contains("--sun-misc-unsafe-memory-access=allow");
        List<String> missingOpens = new ArrayList<>();
        for (String needed : REQUIRED_ADD_OPENS) {
            boolean present = false;
            for (int i = 0; i < args.size(); i++) {
                if ("--add-opens".equals(args.get(i)) && i + 1 < args.size() && needed.equals(args.get(i + 1))) {
                    present = true;
                    break;
                }
                // some launchers pass it as one token "--add-opens=<module>=ALL-UNNAMED"
                if (args.get(i).equals("--add-opens=" + needed)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                missingOpens.add(needed);
            }
        }
        if (!unsafeAllowed || !missingOpens.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("UmbUniverse: the running JVM is missing legacy-universe flags it needed at STARTUP ");
            sb.append("(they cannot be added after the JVM is already running - the launcher must add them). ");
            if (!unsafeAllowed) {
                sb.append("missing --sun-misc-unsafe-memory-access=allow. ");
            }
            if (!missingOpens.isEmpty()) {
                sb.append("missing --add-opens: ").append(missingOpens).append(". ");
            }
            sb.append("See harness/launch-262-legacy-m1.ps1.");
            throw new IllegalStateException(sb.toString());
        }
    }

    private static File repoRoot() {
        String home = System.getProperty("umb.home");
        if (home != null && !home.isEmpty()) return new File(home).getAbsoluteFile();
        String prop = System.getProperty("umb.repo");
        if (prop != null && !prop.isEmpty()) {
            return new File(prop).getAbsoluteFile();
        }
        // derive from where umb-hostagent.jar itself sits: <repo>/build/hostagent/umb-hostagent.jar
        try {
            CodeSource cs = UmbUniverse.class.getProtectionDomain().getCodeSource();
            if (cs != null) {
                File jar = new File(cs.getLocation().toURI()).getAbsoluteFile();
                File installed = jar.isFile() ? jar.getParentFile() : jar;
                if (installed != null && (new File(installed, "manifest.json").isFile()
                        || new File(installed, "jvm-arguments.txt").isFile()
                        || new File(installed, "inputs").isDirectory())) return installed;
                File derived = jar.getParentFile().getParentFile().getParentFile();
                if (derived != null && new File(derived, "umb-legacy").isDirectory()) {
                    return derived;
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        throw new IllegalStateException("cannot determine repo root: pass -Dumb.repo=<repo>");
    }

    private static File firstFile(File... candidates) {
        for (File f : candidates) if (f != null && f.isFile()) return f;
        return candidates[0];
    }

    private static File firstDirectory(File... candidates) {
        for (File f : candidates) if (f != null && f.isDirectory()) return f;
        return candidates[0];
    }

    private static File findOne(File dir, String prefix, String suffix) {
        File found = search(dir, prefix, suffix);
        if (found == null) {
            throw new IllegalStateException("no " + prefix + "*" + suffix + " under " + dir);
        }
        return found;
    }

    private static File search(File dir, String prefix, String suffix) {
        File[] kids = dir.listFiles();
        if (kids == null) {
            return null;
        }
        for (File k : kids) {
            if (k.isDirectory()) {
                File hit = search(k, prefix, suffix);
                if (hit != null) {
                    return hit;
                }
            } else if (k.getName().startsWith(prefix) && k.getName().endsWith(suffix)) {
                return k;
            }
        }
        return null;
    }

    private static File requireFile(File f) {
        if (!f.isFile()) {
            throw new IllegalStateException("missing required legacy artifact: " + f
                    + " - run tools\\build-legacy.ps1 first");
        }
        return f;
    }

    /**
     * Stages one legacy mod for FML discovery. Plain mods are staged as an EXPLODED directory:
     * many 1.7.10 mods treat {@code ModContainer.getSource()} as a filesystem directory and read
     * content with {@code java.io.File} (MC Helicopters lists {@code <source>/assets/mcheli/*}
     * with {@code File.listFiles()} and threw FileNotFoundException in PreInit when staged as a
     * jar - live log win-twomods stdout.log:11328-11359). FML 1.7.10's DirectoryDiscoverer loads
     * directory mods natively. Coremod / tweaker jars (manifest FMLCorePlugin or TweakClass) are
     * kept as jars because CoreModManager only discovers those from jar manifests. Re-stages when
     * the source jar changed (size+mtime marker) and removes a stale jar/dir of the other form so
     * FML never sees the same mod twice.
     */
    static File stageMod(File modJar, File modsDir) throws IOException {
        String name = modJar.getName();
        String base = name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") || name.toLowerCase(java.util.Locale.ROOT).endsWith(".zip")
                ? name.substring(0, name.length() - 4) : name;
        File asJar = new File(modsDir, name);
        File asDir = new File(modsDir, base);
        boolean keepJar;
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(modJar)) {
            java.util.jar.Manifest mf = jf.getManifest();
            java.util.jar.Attributes a = mf == null ? null : mf.getMainAttributes();
            keepJar = a != null && (a.getValue("FMLCorePlugin") != null || a.getValue("TweakClass") != null);
        }
        String stamp = modJar.length() + ":" + modJar.lastModified();
        if (keepJar) {
            if (asDir.isDirectory()) deleteTree(asDir.toPath());
            if (!asJar.isFile() || asJar.length() != modJar.length()) {
                Files.copy(modJar.toPath(), asJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return asJar;
        }
        if (asJar.isFile()) Files.delete(asJar.toPath());
        File marker = new File(asDir, ".umb-staged");
        if (marker.isFile() && stamp.equals(new String(Files.readAllBytes(marker.toPath()), java.nio.charset.StandardCharsets.UTF_8))) {
            return asDir;
        }
        if (asDir.exists()) deleteTree(asDir.toPath());
        Files.createDirectories(asDir.toPath());
        Path root = asDir.toPath().toAbsolutePath().normalize();
        try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(new java.io.FileInputStream(modJar)))) {
            java.util.zip.ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                Path out = root.resolve(e.getName()).normalize();
                if (!out.startsWith(root)) throw new IOException("zip entry escapes staging dir: " + e.getName());
                if (e.isDirectory()) { Files.createDirectories(out); continue; }
                Files.createDirectories(out.getParent());
                Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Files.write(marker.toPath(), stamp.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return asDir;
    }

    private static String safeInstanceName(String raw) {
        String s = raw == null ? "default" : raw.trim();
        if (s.isEmpty()) s = "default";
        s = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return s.isEmpty() ? "default" : s;
    }

    private static void deleteTree(Path p) throws IOException {
        try (java.util.stream.Stream<Path> w = Files.walk(p)) {
            java.util.List<Path> all = new java.util.ArrayList<>();
            w.forEach(all::add);
            java.util.Collections.reverse(all);
            for (Path q : all) Files.deleteIfExists(q);
        }
    }

    // ---- 2026-09-24: forwarders for every LegacyBridge method this wrapper used to inherit as a
    // no-op default (BridgeDelegatesOverrideAllTest). Each call was silently dropped before: block
    // ticks, contact effects, drops, held-item ticks, item use, tick events, GUI buttons, respawn. ----

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, dev.umb.bridge.api.HostPlayer player, dev.umb.bridge.api.StackData placedStack) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.placedBy(legacyBlockId, x, y, z, player, placedStack);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.placedBy", t, 3);
        }
    }

    @Override
    public void tickBlock(String legacyBlockId, int x, int y, int z, boolean isRandom) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.tickBlock(legacyBlockId, x, y, z, isRandom);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.tickBlock", t, 3);
        }
    }

    @Override
    public void entityInside(String legacyBlockId, int x, int y, int z, dev.umb.bridge.api.HostPlayer player) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.entityInside(legacyBlockId, x, y, z, player);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.entityInside", t, 3);
        }
    }

    @Override
    public double[] collisionBounds(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = real;
        if (r == null) return null;
        try {
            return r.collisionBounds(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.collisionBounds", t, 3);
            return null;
        }
    }

    @Override
    public java.util.List<double[]> collisionBoxes(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = real;
        if (r == null) return null;
        try {
            return r.collisionBoxes(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.collisionBoxes", t, 3);
            return null;
        }
    }

    @Override
    public java.util.List<double[]> selectionBoxes(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = real;
        if (r == null) return null;
        try {
            return r.selectionBoxes(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.selectionBoxes", t, 3);
            return null;
        }
    }

    @Override
    public void invalidateShape(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.invalidateShape(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.invalidateShape", t, 3);
        }
    }

    @Override
    public int placementMetadata(String legacyBlockId, int x, int y, int z, int side, float hitX, float hitY, float hitZ, int meta) {
        LegacyBridge r = real;
        if (r == null) return meta;
        try {
            return r.placementMetadata(legacyBlockId, x, y, z, side, hitX, hitY, hitZ, meta);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.placementMetadata", t, 3);
            return meta;
        }
    }

    @Override
    public java.util.List<dev.umb.bridge.api.StackData> blockDrops(String legacyBlockId, int x, int y, int z, int meta, int fortune) {
        LegacyBridge r = real;
        if (r == null) return java.util.Collections.emptyList();
        try {
            return r.blockDrops(legacyBlockId, x, y, z, meta, fortune);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.blockDrops", t, 3);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public void stepOn(String legacyBlockId, int x, int y, int z, dev.umb.bridge.api.HostPlayer player) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.stepOn(legacyBlockId, x, y, z, player);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.stepOn", t, 3);
        }
    }

    @Override
    public void fallOn(String legacyBlockId, int x, int y, int z, dev.umb.bridge.api.HostPlayer player, float distance) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.fallOn(legacyBlockId, x, y, z, player, distance);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.fallOn", t, 3);
        }
    }

    @Override
    public void animateBlock(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.animateBlock(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.animateBlock", t, 3);
        }
    }

    @Override
    public boolean hasComparatorInputOverride(String legacyBlockId) {
        LegacyBridge r = real;
        if (r == null) return false;
        try {
            return r.hasComparatorInputOverride(legacyBlockId);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.hasComparatorInputOverride", t, 3);
            return false;
        }
    }

    @Override
    public int comparatorInputOverride(String legacyBlockId, int x, int y, int z) {
        LegacyBridge r = real;
        if (r == null) return 0;
        try {
            return r.comparatorInputOverride(legacyBlockId, x, y, z);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.comparatorInputOverride", t, 3);
            return 0;
        }
    }

    @Override
    public java.util.List<String> itemTooltip(String legacyItemId, dev.umb.bridge.api.StackData stack, boolean advanced) {
        LegacyBridge r = real;
        if (r == null) return java.util.Collections.emptyList();
        try {
            return r.itemTooltip(legacyItemId, stack, advanced);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.itemTooltip", t, 3);
            return java.util.Collections.emptyList();
        }
    }

    @Override
    public dev.umb.bridge.api.StackData itemInventoryTick(String legacyItemId, dev.umb.bridge.api.StackData stack, dev.umb.bridge.api.HostPlayer player, int slot, boolean current) {
        LegacyBridge r = real;
        if (r == null) return stack;
        try {
            return r.itemInventoryTick(legacyItemId, stack, player, slot, current);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.itemInventoryTick", t, 3);
            return stack;
        }
    }

    @Override
    public int itemUseDuration(String legacyItemId, dev.umb.bridge.api.StackData stack) {
        LegacyBridge r = real;
        if (r == null) return 0;
        try {
            return r.itemUseDuration(legacyItemId, stack);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.itemUseDuration", t, 3);
            return 0;
        }
    }

    @Override
    public String itemUseAction(String legacyItemId, dev.umb.bridge.api.StackData stack) {
        LegacyBridge r = real;
        if (r == null) return "none";
        try {
            return r.itemUseAction(legacyItemId, stack);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.itemUseAction", t, 3);
            return "none";
        }
    }

    @Override
    public float itemDestroySpeed(String legacyItemId, dev.umb.bridge.api.StackData stack, String legacyBlockId) {
        LegacyBridge r = real;
        if (r == null) return Float.NaN;
        try {
            return r.itemDestroySpeed(legacyItemId, stack, legacyBlockId);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.itemDestroySpeed", t, 3);
            return Float.NaN;
        }
    }

    @Override
    public boolean itemCanHarvestBlock(String legacyItemId, dev.umb.bridge.api.StackData stack, String legacyBlockId) {
        LegacyBridge r = real;
        if (r == null) return false;
        try {
            return r.itemCanHarvestBlock(legacyItemId, stack, legacyBlockId);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.itemCanHarvestBlock", t, 3);
            return false;
        }
    }

    @Override
    public void itemUsingTick(String legacyItemId, dev.umb.bridge.api.StackData stack, dev.umb.bridge.api.HostPlayer player, int remaining) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.itemUsingTick(legacyItemId, stack, player, remaining);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.itemUsingTick", t, 3);
        }
    }

    @Override
    public void itemStoppedUsing(String legacyItemId, dev.umb.bridge.api.StackData stack, dev.umb.bridge.api.HostPlayer player, int remaining) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.itemStoppedUsing(legacyItemId, stack, player, remaining);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.itemStoppedUsing", t, 3);
        }
    }

    @Override
    public dev.umb.bridge.api.StackData itemEaten(String legacyItemId, dev.umb.bridge.api.StackData stack, dev.umb.bridge.api.HostPlayer player) {
        LegacyBridge r = real;
        if (r == null) return stack;
        try {
            return r.itemEaten(legacyItemId, stack, player);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.itemEaten", t, 3);
            return stack;
        }
    }

    @Override
    public void tickEvents(dev.umb.bridge.api.HostWorld world, dev.umb.bridge.api.HostPlayer[] players, boolean endPhase) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.tickEvents(world, players, endPhase);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.tickEvents", t, 3);
        }
    }

    @Override
    public dev.umb.bridge.api.GlEmulationSession.Mesh renderHud(String playerName, float partialTicks,
                                                               int width, int height) {
        LegacyBridge r = real;
        if (r == null) return null;
        try {
            return r.renderHud(playerName, partialTicks, width, height);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.renderHud", t, 3);
            return null;
        }
    }

    @Override
    public dev.umb.bridge.api.GlEmulationSession.Mesh renderGui(String playerName, float partialTicks,
                                                                  int width, int height) {
        LegacyBridge r = real;
        if (r == null) return null;
        try {
            return r.renderGui(playerName, partialTicks, width, height);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.renderGui", t, 3);
            return null;
        }
    }

    @Override
    public LegacyBridge.CameraState cameraState(String playerName) {
        LegacyBridge r = real;
        if (r == null) return null;
        try {
            return r.cameraState(playerName);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.cameraState", t, 3);
            return null;
        }
    }

    @Override
    public float thirdPersonDistance(String playerName) {
        LegacyBridge r = real;
        if (r == null) return Float.NaN;
        try {
            return r.thirdPersonDistance(playerName);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.thirdPersonDistance", t, 3);
            return Float.NaN;
        }
    }

    @Override
    public void tickEntities() {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.tickEntities();
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.tickEntities", t, 3);
        }
    }

    @Override
    public void syncPlayers(java.util.List<dev.umb.bridge.api.HostPlayer> livePlayers) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.syncPlayers(livePlayers);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.syncPlayers", t, 3);
        }
    }

    @Override
    public void playerRespawn(dev.umb.bridge.api.HostPlayer player) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.playerRespawn(player);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.playerRespawn", t, 3);
        }
    }

    @Override
    public void guiButtonPacket(String guiClass, int buttonId, int x, int y, int z, String[] args) {
        LegacyBridge r = real;
        if (r == null) return;
        try {
            r.guiButtonPacket(guiClass, buttonId, x, y, z, args);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.guiButtonPacket", t, 3);
        }
    }

    @Override
    public boolean guiMouseClick(String guiClass, int x, int y, int z,
                                 int guiX, int guiY, int button, int screenX, int screenY) {
        LegacyBridge r = real;
        if (r == null) return false;
        try {
            return r.guiMouseClick(guiClass, x, y, z, guiX, guiY, button, screenX, screenY);
        } catch (Throwable t) {
            AgentLog.error("UmbUniverse.guiMouseClick", t, 3);
            return false;
        }
    }
}
