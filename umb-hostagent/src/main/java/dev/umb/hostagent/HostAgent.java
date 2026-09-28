package dev.umb.hostagent;

import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.jar.JarFile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.umb.hostagent.content.ModContentManifest;

/**
 * -javaagent entrypoint.
 *
 * Args: "snapshot=&lt;path&gt;;log=&lt;path&gt;[;ns=hbm][;lang=&lt;en_US.lang&gt;]
 * [;modjars=&lt;jar1&gt;,&lt;jar2&gt;,...]" - ';'-separated k=v; {@code modjars} is itself
 * comma-separated so more than one legacy mod jar can be staged into the same boot (see
 * {@link #modJars()} and {@code dev.umb.hostagent.content.UmbUniverse#boot}).
 *
 * The agent adds three transformers and then gets out of the way. All real work happens in
 * {@link Hooks#beforeFreeze()}, which the first transformer splices into
 * BuiltInRegistries.freeze() so it runs while the registries are still writable.
 */
public final class HostAgent {

    private static volatile Path snapshot;
    private static volatile Path lang;
    private static volatile Path blockShapes;
    private static volatile Path guiProfile;
    // No mod-identity default lives here: null means "not configured yet", and premain()
    // derives the namespace from explicit `ns=` or from the snapshot filename, failing fast
    // when neither exists. Every real flow (game, probes, tests) configures one of the two.
    private static volatile String namespace;
    // An empty list means the caller did not provide mod jars. The universe decides whether that
    // is valid; the agent must not assume a particular mod.
    private static volatile List<Path> modJars = Collections.emptyList();
    private static volatile ModContentManifest contentManifest;

    private HostAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        Map<String, String> kv = parse(args);
        String launchwrapper = stripQuotes(kv.get("launchwrapper"));
        if (launchwrapper != null && !launchwrapper.isEmpty()) {
            try {
                // Release jars intentionally do not redistribute Mojang's LaunchWrapper. Add the
                // player's verified official library to the application classloader before any
                // legacy bridge class resolves net.minecraft.launchwrapper.*.
                inst.appendToSystemClassLoaderSearch(new JarFile(launchwrapper));
            } catch (Exception e) {
                throw new IllegalStateException("HostAgent: cannot add LaunchWrapper to the system classpath: " + launchwrapper, e);
            }
        }
        Path logPath = Paths.get(kv.getOrDefault("log", "umb-hostagent.log"));
        AgentLog.open(logPath);
        AgentLog.line("premain args=" + args);
        // Check the shared application loader before any bridge/universe class can resolve the
        // legacy LaunchClassLoader. Release jars do not bundle Mojang's LaunchWrapper.
        requireLaunchwrapper();

        String snap = kv.get("snapshot");
        snapshot = (snap == null || snap.isEmpty()) ? null : Paths.get(snap);
        String nsArg = kv.get("ns");
        if (nsArg == null || nsArg.isEmpty()) {
            // No mod-identity default: the namespace comes from the snapshot filename
            // (<namespace>-snapshot.json), which every real launch passes alongside. A launch
            // with neither is misconfigured - booting it under a guessed namespace is exactly
            // the silent-wrong-mod failure this used to cause, so fail loudly instead.
            namespace = namespaceFromSnapshot(snapshot);
            if (namespace == null) {
                throw new IllegalStateException(
                        "HostAgent: no ns=<modid> argument and no <namespace>-snapshot.json to"
                        + " derive one from - refusing to boot under a guessed namespace");
            }
            AgentLog.loud("HostAgent: no ns= argument given; derived namespace \"" + namespace
                    + "\" from " + snapshot.getFileName() + " - pass ns=<modid> explicitly to override");
        } else {
            namespace = nsArg;
        }
        String lg = kv.get("lang");
        if (lg != null && !lg.isEmpty()) {
            lang = Paths.get(lg);
        } else if (snapshot != null && snapshot.getParent() != null) {
            // Asset directories are named from the configured namespace.
            lang = snapshot.getParent().resolve(namespace + "-assets/assets/" + namespace + "/lang/en_US.lang");
        }

        // Optional shape and GUI profiles live beside the snapshot unless explicitly configured.
        // Missing files fall back to the default cube and panel.
        String shapesArg = kv.get("blockshapes");
        if (shapesArg != null && !shapesArg.isEmpty()) {
            blockShapes = Paths.get(shapesArg);
        } else if (snapshot != null && snapshot.getParent() != null) {
            blockShapes = snapshot.getParent().resolve("block-shapes.json");
        }
        String guiArg = kv.get("guiprofile");
        if (guiArg != null && !guiArg.isEmpty()) {
            guiProfile = Paths.get(guiArg);
        } else if (snapshot != null && snapshot.getParent() != null) {
            guiProfile = snapshot.getParent().resolve("gui-profile.json");
        }
        modJars = parseModJars(kv.get("modjars"));
        String manifestArg = kv.get("manifest");
        if (manifestArg != null && !manifestArg.isEmpty()) {
            try { contentManifest = ModContentManifest.load(Paths.get(manifestArg)); }
            catch (Exception e) { AgentLog.loud("HostAgent: invalid manifest=" + manifestArg + ": " + e); }
        }
        AgentLog.line("snapshot=" + snapshot + " ns=" + namespace + " lang=" + lang
                + " blockshapes=" + blockShapes + " guiprofile=" + guiProfile
                + " modjars=" + modJars);

        try {
            inst.addTransformer(new BuiltInRegistriesPatcher());
            inst.addTransformer(new CreativeTabSpritePatcher());
            // order matters: the sprite clamp fix runs first and the paging patch receives its
            // output (Instrumentation chains transformers in registration order)
            inst.addTransformer(new CreativePagingPatcher());
            // Extend the fluid model set after vanilla installs water and lava.
            inst.addTransformer(new dev.umb.hostagent.content.fluid.FluidStateModelPatcher());
            // Widen the menu construction interfaces used by generated adapters.
            inst.addTransformer(new UmbAccessWidener());
            inst.addTransformer(new LegacyServerTickPatcher());
            // Register payload codecs and listener/tick seams.
            inst.addTransformer(new LegacyPayloadCodecPatcher());
            inst.addTransformer(new LegacyCustomPayloadPatcher());
            inst.addTransformer(new LegacyClientTickPatcher());
            inst.addTransformer(new LegacyHudPatcher());
            inst.addTransformer(new LegacyCameraPatcher());
            inst.addTransformer(new LegacyAttackPatcher());
            inst.addTransformer(new UmbMenuPatcher());
            AgentLog.line("transformers installed");
            // Start automation after transformer registration because it links game classes that
            // must still pass through those transformers.
            dev.umb.hostagent.automation.AutomationControl.startIfConfigured(kv);
        } catch (Throwable t) {
            AgentLog.loud("PATCH-FAILED addTransformer: " + t);
            AgentLog.error("premain.addTransformer", t, 5);
        }

        // Install a router over the default 1.7.10 universe. Other eras are booted lazily by
        // namespace; with no era records the router simply forwards to the default bridge.
        try {
            java.util.Map<String, Integer> installedEraMods = new java.util.LinkedHashMap<>();
            java.util.Map<String, java.util.Set<String>> eraNamespaces =
                    new java.util.LinkedHashMap<>();
            java.util.Map<String, java.util.List<java.nio.file.Path>> eraModJars =
                    new java.util.LinkedHashMap<>();
            java.util.Map<String, java.util.List<java.nio.file.Path>> eraDataPacks =
                    new java.util.LinkedHashMap<>();
            if (contentManifest != null) {
                for (dev.umb.hostagent.content.ModContentRecord r : contentManifest.records()) {
                    // A manifest row is content metadata; only a row with an actually installed
                    // mod jar admits an era loader. This keeps stale/generated pack rows from
                    // turning into a Forge boot obligation.
                    if (r.jar() == null || !java.nio.file.Files.isRegularFile(r.jar())) {
                        AgentLog.line("BridgeRouter: namespace " + r.namespace() + " era " + r.era()
                                + " has no installed jar; era admission skipped");
                        continue;
                    }
                    installedEraMods.merge(r.era(), 1, Integer::sum);
                    if ("1.7.10".equals(r.era())) continue;
                    eraNamespaces.computeIfAbsent(r.era(), k -> new java.util.HashSet<>())
                            .add(r.namespace());
                    eraModJars.computeIfAbsent(r.era(), k -> new java.util.ArrayList<>())
                            .add(r.jar());
                    if (r.basePack() != null && java.nio.file.Files.isDirectory(r.basePack())) {
                        eraDataPacks.computeIfAbsent(r.era(), k -> new java.util.ArrayList<>())
                                .add(r.basePack());
                    }
                }
            }
            if (contentManifest == null && !modJars.isEmpty())
                installedEraMods.put("1.7.10", modJars.size());
            boolean has1710 = installedEraMods.containsKey("1.7.10");
            dev.umb.bridge.api.LegacyBridge defaultBridge = has1710
                    ? new dev.umb.hostagent.content.UmbUniverse()
                    : dev.umb.hostagent.content.DisabledLegacyBridge.create("1.7.10");
            dev.umb.hostagent.content.BridgeRouter router =
                    new dev.umb.hostagent.content.BridgeRouter(defaultBridge);
            for (java.util.Map.Entry<String, java.util.Set<String>> e : eraNamespaces.entrySet()) {
                final String era = e.getKey();
                final java.util.Set<String> namespaces =
                        java.util.Collections.unmodifiableSet(e.getValue());
                final java.util.List<java.nio.file.Path> modJarPaths = eraModJars.get(era);
                if (modJarPaths == null || modJarPaths.isEmpty()) {
                    AgentLog.loud("BridgeRouter: era " + era + " has no manifest jar - skipped");
                    continue;
                }
                if (!"1.16.5".equals(era) && !"1.12.2".equals(era)) {
                    AgentLog.loud("BridgeRouter: unknown era " + era + " - skipped (no universe)");
                    continue;
                }
                final java.util.List<java.io.File> jarFiles = new java.util.ArrayList<>();
                for (java.nio.file.Path p : modJarPaths) jarFiles.add(p.toFile());
                final java.util.List<java.nio.file.Path> dataPackPaths =
                        eraDataPacks.getOrDefault(era, java.util.Collections.emptyList());
                final java.util.List<java.io.File> dataPacks = new java.util.ArrayList<>();
                for (java.nio.file.Path p : dataPackPaths) dataPacks.add(p.toFile());
                if ("1.16.5".equals(era)) {
                    router.registerEra(era, namespaces, () ->
                            new dev.umb.hostagent.content.Legacy1165Universe(jarFiles, dataPacks));
                } else {
                    router.registerEra(era, namespaces, () ->
                            new dev.umb.hostagent.content.Legacy1122Universe(jarFiles));
                }
            }
            for (String era : java.util.List.of("1.7.10", "1.12.2", "1.16.5")) {
                int count = installedEraMods.getOrDefault(era, 0);
                AgentLog.loud("BridgeRouter: era=" + era + " admission="
                        + (count > 0 ? "installed" : "omitted") + " installedMods=" + count
                        + " boot=" + (count > 0 ? "lazy" : "never")
                        + " startup=0ms memory=0MB");
            }
            dev.umb.hostagent.content.UmbBridgeHost.set(router);
            AgentLog.line("UmbBridgeHost router registered (boot is lazy - see UMB-BRIDGE log line on first use)");
        } catch (Throwable t) {
            AgentLog.loud("UMB-BRIDGE FAILED to register router: " + t);
            AgentLog.error("premain.UmbBridgeHost", t, 5);
        }
    }

    /** So the agent also works with -javaagent attach-on-start plus agentmain. */
    public static void agentmain(String args, Instrumentation inst) {
        premain(args, inst);
    }

    public static Path snapshotPath() {
        return snapshot;
    }

    public static Path langPath() {
        return lang;
    }

    public static Path blockShapesPath() {
        return blockShapes;
    }

    public static Path guiProfilePath() {
        return guiProfile;
    }

    public static String namespace() {
        return namespace;
    }

    /**
     * Derives the namespace from a snapshot path's {@code <namespace>-snapshot.json} filename
     * Package-visible for tests.
     * Returns null when there is nothing to derive from - never a guessed mod id.
     */
    static String namespaceFromSnapshot(Path snapshotFile) {
        if (snapshotFile == null || snapshotFile.getFileName() == null) return null;
        String name = snapshotFile.getFileName().toString();
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (!lower.endsWith("-snapshot.json")) return null;
        String stem = lower.substring(0, lower.length() - "-snapshot.json".length());
        return stem.isEmpty() ? null : stem;
    }

    /**
     * The legacy mod jar(s) to stage into the boot, in caller-given order. Empty when nobody
     * passed {@code modjars=} (or called {@link #configure(Path, Path, String, List)}) -- an
     * empty list is a real, meaningful "not configured" signal, not an error; callers that need a
     * default apply it themselves rather than silently selecting one mod. Multiple jars are
     * preserved in caller order.
     */
    public static List<Path> modJars() {
        return modJars;
    }

    public static ModContentManifest contentManifest() { return contentManifest; }

    /** Allows the headless probe to configure the same state without an agent attach. */
    public static void configure(Path snapshotPath, Path langPath, String ns) {
        configure(snapshotPath, langPath, ns, Collections.<Path>emptyList());
    }

    /** As {@link #configure(Path, Path, String)}, additionally setting the staged mod jar list. */
    public static void configure(Path snapshotPath, Path langPath, String ns, List<Path> jars) {
        snapshot = snapshotPath;
        lang = langPath;
        namespace = ns;
        modJars = (jars == null) ? Collections.<Path>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(jars));
        if (snapshotPath != null && snapshotPath.getParent() != null) {
            blockShapes = snapshotPath.getParent().resolve("block-shapes.json");
            guiProfile = snapshotPath.getParent().resolve("gui-profile.json");
        }
    }

    static Map<String, String> parse(String args) {
        Map<String, String> kv = new LinkedHashMap<>();
        if (args == null || args.isEmpty()) return kv;
        for (String part : args.split(";")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            int eq = p.indexOf('=');
            if (eq <= 0) {
                kv.put(p, "");
            } else {
                kv.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
            }
        }
        return kv;
    }

    private static String stripQuotes(String value) {
        if (value == null) return null;
        String v = value.trim();
        return v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")
                ? v.substring(1, v.length() - 1) : v;
    }

    private static void requireLaunchwrapper() {
        try {
            Class.forName("net.minecraft.launchwrapper.LaunchClassLoader", false,
                    HostAgent.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            String message = "LaunchWrapper self-check FAILED: LaunchClassLoader is missing; "
                    + "add launchwrapper=<path-to-launchwrapper-1.12.jar> to this -javaagent "
                    + "argument, or rerun the UMB installer.";
            AgentLog.loud(message);
            throw new IllegalStateException(message, e);
        }
    }

    /** Splits the {@code modjars=} value (comma-separated, since ';' is the kv separator). */
    static List<Path> parseModJars(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<Path> out = new ArrayList<>();
        for (String part : value.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) {
                out.add(Paths.get(p));
            }
        }
        return Collections.unmodifiableList(out);
    }
}
