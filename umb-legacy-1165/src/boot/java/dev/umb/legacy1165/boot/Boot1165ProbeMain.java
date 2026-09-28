package dev.umb.legacy1165.boot;

import java.io.File;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import dev.umb.legacy1165.api.StageResult;

/**
 * Headless "how far can the isolated loader get against the REAL fetched 1.16.5 jars" probe.
 *
 * <p>This is deliberately NOT a full ModLauncher/FML boot (see ERA-1165-PLAN.md "what a full boot
 * still needs" for exactly why a full boot is out of scope for this lane: production 1.16.5 needs
 * ModLauncher transformation services to rename the obfuscated vanilla jar to SRG at classload
 * time, driven by MCPConfig mapping data the installer applies - no transformation service is
 * implemented or driven here). What it DOES prove, against real artifacts (Forge 36.2.34 universal
 * + installer-profile libraries from maven.minecraftforge.net, the real obfuscated 1.16.5
 * client.jar cached under research/jars/1.16.5 by an earlier mapping lane, the Iron Chests 1.16.5
 * test-subject mod from Modrinth - not fixtures):</p>
 *
 * <ol>
 *   <li>The isolated {@link Legacy1165Loader}, with the ModLauncher-era delegation policy (plain
 *       {@code URLClassLoader}, no LaunchWrapper anywhere), can load genuine ModLauncher classes
 *       ({@code cpw.mods.modlauncher.Launcher}, {@code TransformingClassLoader}) and genuine
 *       Forge 1.16.5/FML 36.x runtime classes (never obfuscated - Forge's own code is plain
 *       {@code net.minecraftforge.*} / {@code cpw.mods.*}) out of the real jars.</li>
 *   <li>The SRG-renamed + binpatched + AT-widened SERVER jars (produced in-lane by Forge's
 *       own documented processors, stage SHAs verified) make genuine vanilla SERVER classes
 *       loadable by their real SRG names ({@code net.minecraft.block.Block},
 *       {@code AbstractBlock}, {@code TileEntityType}) - the pre-rename wall is CROSSED.</li>
 *   <li>The universe is client-capable: the client SRG jar rides along because Forge's own
 *       server binpatch links the client-only {@code IChestLid} into server classes, so
 *       {@code net.minecraft.client.Minecraft} also resolves BY NAME. Instantiating client
 *       classes (window, game boot) is explicitly out of scope.</li>
 *   <li>The resources a real boot's mod-discovery stage would need ({@code META-INF/mods.toml},
 *       {@code META-INF/coremods.json}, {@code META-INF/accesstransformer.cfg} inside the Forge
 *       jar) are physically present and reachable through the loader today.</li>
 * </ol>
 */
public final class Boot1165ProbeMain {

    private Boot1165ProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        File repo = new File(System.getProperty("umb.repo", args.length > 0 ? args[0] : "."))
                .getAbsoluteFile();
        File manifest = new File(repo, "umb-legacy-1165/resources/classpath-1165.txt");
        File outDir = new File(repo, "research/out/legacy-1165");
        Files.createDirectories(outDir.toPath());

        List<StageResult> stages = new ArrayList<StageResult>();

        List<File> files = Legacy1165Classpath.readManifest(repo, manifest);
        URL[] urls = Legacy1165Classpath.toUrls(files);
        out.println("[boot1165-probe] repo      = " + repo);
        out.println("[boot1165-probe] manifest  = " + manifest + " (" + files.size() + " jars)");

        Legacy1165Loader loader = new Legacy1165Loader(urls, Boot1165ProbeMain.class.getClassLoader());

        // ModLauncher itself (separate library jar, NOT inside the Forge universal jar).
        stages.add(loadable(loader, "cpw.mods.modlauncher.Launcher"));
        stages.add(loadable(loader, "cpw.mods.modlauncher.TransformingClassLoader"));
        stages.add(loadable(loader, "cpw.mods.modlauncher.api.ITransformationService"));
        // FML 36.x / Forge 1.16.5 runtime (plain names, never obfuscated).
        stages.add(loadable(loader, "net.minecraftforge.fml.ModLoader"));
        stages.add(loadable(loader, "net.minecraftforge.event.RegistryEvent"));
        stages.add(loadable(loader, "net.minecraftforge.registries.IForgeRegistry"));
        stages.add(loadable(loader, "net.minecraftforge.registries.DeferredRegister"));
        stages.add(loadable(loader, "net.minecraftforge.fml.RegistryObject"));
        stages.add(loadable(loader, "net.minecraftforge.common.capabilities.Capability"));
        // The rename spike (run-rename.ps1) crossed the pre-rename wall for SERVER classes:
        // net.minecraft.block.Block now resolves from mc-server-srg-patched.jar.
        stages.add(loadable(loader, "net.minecraft.block.Block"));
        stages.add(loadable(loader, "net.minecraft.block.AbstractBlock"));
        stages.add(loadable(loader, "net.minecraft.tileentity.TileEntityType"));
        // The transforming-loader proof: production's eventbus transformer adds a public
        // no-arg constructor to every Event subclass at class-load (see Legacy1165Loader).
        // RegistryEvent$Register ships exactly one public ctor (ResourceLocation,
        // IForgeRegistry) - if the hook ran, the no-arg ctor exists now.
        stages.add(eventTransformed(loader, "net.minecraftforge.event.RegistryEvent$Register"));
        // The universe is client-capable (integrated-style): the client SRG jar is on the
        // manifest because Forge's own server binpatch links a client-only interface
        // (IChestLid) into server classes. Client classes therefore RESOLVE by name - what
        // remains unproven (and unattempted: no window, no game boot) is instantiating any
        // of them (Minecraft.<init> needs a display, game dir, and a full client boot).
        stages.add(loadable(loader, "net.minecraft.client.Minecraft"));

        stages.add(resourcePresent(loader, "META-INF/mods.toml"));
        stages.add(resourcePresent(loader, "META-INF/coremods.json"));
        stages.add(resourcePresent(loader, "META-INF/accesstransformer.cfg"));

        long t0 = System.nanoTime();
        boolean obfShapeOk = false;
        try {
            // do NOT classload it (unresolved obf superclass/interface refs would throw
            // NoClassDefFoundError deep inside verification) - just prove the short-name shape
            // exists, which is the concrete evidence for "obfuscated, not SRG named".
            obfShapeOk = loader.getResource("a.class") != null || loader.getResource("aa.class") != null;
        } catch (Throwable ignored) {
            // swallow - either result still answers the question below
        }
        stages.add(obfShapeOk
                ? StageResult.ok("obf-shape-present(a.class|aa.class)", (System.nanoTime() - t0) / 1_000_000L)
                : new StageResult("obf-shape-present(a.class|aa.class)", false, (System.nanoTime() - t0) / 1_000_000L,
                        "AssertionError", "neither short-name class file found", null));

        StringBuilder report = new StringBuilder();
        report.append("jars=").append(files.size()).append('\n');
        boolean allOk = true;
        for (StageResult r : stages) {
            report.append("stage=").append(r.stage()).append(" ok=").append(r.ok())
                    .append(" millis=").append(r.millis()).append('\n');
            if (!r.ok()) {
                report.append("  throwable=").append(r.throwableClass()).append(": ")
                        .append(r.throwableMessage()).append('\n');
            }
        }
        // Every stage reports ok=true on success; any failure gates PROBE-PARTIAL.
        for (int i = 0; i < stages.size(); i++) {
            if (!stages.get(i).ok()) {
                allOk = false;
            }
        }
        report.append(allOk ? "PROBE-OK\n" : "PROBE-PARTIAL\n");
        Files.write(new File(outDir, "boot-probe.txt").toPath(),
                report.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        out.println(report);
        Runtime.getRuntime().halt(allOk ? 0 : 1);
    }

    private static StageResult loadable(Legacy1165Loader loader, String name) {
        long t0 = System.nanoTime();
        try {
            Class<?> c = Class.forName(name, false, loader);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            return StageResult.ok("load(" + name + ")", ms);
        } catch (Throwable t) {
            return StageResult.failed("load(" + name + ")", (System.nanoTime() - t0) / 1_000_000L, t);
        }
    }

    private static StageResult eventTransformed(Legacy1165Loader loader, String name) {
        long t0 = System.nanoTime();
        try {
            Class<?> c = Class.forName(name, false, loader);
            c.getConstructor();
            c.getDeclaredField("LISTENER_LIST");
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            return StageResult.ok("transformed(" + name + "+noarg+LISTENER_LIST)", ms);
        } catch (Throwable t) {
            return StageResult.failed("transformed(" + name + ")", (System.nanoTime() - t0) / 1_000_000L, t);
        }
    }

    private static StageResult resourcePresent(Legacy1165Loader loader, String resourceName) {
        long t0 = System.nanoTime();
        URL u = loader.getResource(resourceName);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        if (u != null) {
            return StageResult.ok("resource(" + resourceName + ")", ms);
        }
        return new StageResult("resource(" + resourceName + ")", false, ms,
                "NotFound", resourceName + " not reachable through the loader", null);
    }
}
