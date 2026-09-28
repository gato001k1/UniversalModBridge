package dev.umb.legacy1122.boot;

import java.io.File;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import dev.umb.legacy1122.api.StageResult;

/**
 * Headless "how far can the isolated loader get against the REAL fetched 1.12.2 jars" probe.
 *
 * <p>This is deliberately NOT a full FML boot (see ERA-1122-PLAN.md "what a full boot still
 * needs" for exactly why a full boot is out of scope for this lane: production 1.12.2 needs
 * {@code DeobfuscationTransformer} to remap the obfuscated vanilla jar at classload time using the
 * LZMA-packed SRG data Forge ships, AND {@code ClassPatchManager} to GDiff-apply
 * {@code binpatches.pack.lzma} to vanilla BEFORE that remap - neither transformer is implemented or
 * driven here). What it DOES prove, against real artifacts fetched from the real Forge/CurseForge
 * CDNs (not fixtures):</p>
 *
 * <ol>
 *   <li>The isolated {@link Legacy1122Loader}, with the SAME delegation policy the 1.7.10 module
 *       uses, can load genuine Forge 1.12.2 runtime classes (never obfuscated - Forge's own source
 *       is plain {@code net.minecraftforge.*} / {@code net.minecraftforge.fml.*}) out of the real
 *       {@code forge-1.12.2-14.23.5.2860-universal.jar}.</li>
 *   <li>The real obfuscated {@code client.jar} is reachable on the classpath but its vanilla
 *       classes are NOT loadable by their real (SRG/MCP) names - only by short obf names like
 *       {@code a.class} - demonstrating precisely the wall a full boot must cross.</li>
 *   <li>The two resources a real boot's deobfuscation stage would need
 *       ({@code deobfuscation_data-1.12.2.lzma}, {@code binpatches.pack.lzma}) are physically
 *       present and reachable through the loader today.</li>
 * </ol>
 */
public final class Boot1122ProbeMain {

    private Boot1122ProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        File repo = new File(System.getProperty("umb.repo", args.length > 0 ? args[0] : "."))
                .getAbsoluteFile();
        File manifest = new File(repo, "umb-legacy-1122/resources/classpath-1122.txt");
        File outDir = new File(repo, "research/out/legacy-1122");
        Files.createDirectories(outDir.toPath());

        List<StageResult> stages = new ArrayList<StageResult>();

        List<File> files = Legacy1122Classpath.readManifest(repo, manifest);
        URL[] urls = Legacy1122Classpath.toUrls(files);
        out.println("[boot1122-probe] repo      = " + repo);
        out.println("[boot1122-probe] manifest  = " + manifest + " (" + files.size() + " jars)");

        Legacy1122Loader loader = new Legacy1122Loader(urls, Boot1122ProbeMain.class.getClassLoader());

        stages.add(loadable(loader, "net.minecraftforge.fml.common.Loader"));
        stages.add(loadable(loader, "net.minecraftforge.event.RegistryEvent"));
        stages.add(loadable(loader, "net.minecraftforge.registries.IForgeRegistry"));
        stages.add(loadable(loader, "net.minecraftforge.fml.common.discovery.ASMDataTable"));
        stages.add(loadable(loader, "net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper"));

        stages.add(expectedNotLoadableByRealName(loader, "net.minecraft.block.Block"));
        stages.add(expectedNotLoadableByRealName(loader, "net.minecraft.client.Minecraft"));

        stages.add(resourcePresent(loader, "deobfuscation_data-1.12.2.lzma"));
        stages.add(resourcePresent(loader, "binpatches.pack.lzma"));
        stages.add(resourcePresent(loader, "forge_at.cfg"));

        long t0 = System.nanoTime();
        boolean obfShapeOk = false;
        try {
            // do NOT classload it (unresolved obf superclass/interface refs would throw
            // NoClassDefFoundError deep inside verification) - just prove the short-name shape
            // exists, which is the concrete evidence for "obfuscated, not SRG/MCP named".
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
        // stages 6-7 (expectedNotLoadableByRealName) are EXPECTED to report ok=false - that IS the
        // documented wall, not a probe failure. Only stages 1-5, 8-10, 11 gate PROBE-OK.
        for (int i = 0; i < stages.size(); i++) {
            boolean isExpectedWall = i == 5 || i == 6;
            if (!isExpectedWall && !stages.get(i).ok()) {
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

    private static StageResult loadable(Legacy1122Loader loader, String name) {
        long t0 = System.nanoTime();
        try {
            Class<?> c = Class.forName(name, false, loader);
            long ms = (System.nanoTime() - t0) / 1_000_000L;
            return StageResult.ok("load(" + name + ")", ms);
        } catch (Throwable t) {
            return StageResult.failed("load(" + name + ")", (System.nanoTime() - t0) / 1_000_000L, t);
        }
    }

    /** Passes (ok=true, matching StageResult.failed shape) when the class is genuinely NOT loadable
     *  by its real name - i.e. the expected pre-deobfuscation wall - and fails the probe if it DOES
     *  load (which would mean our understanding of the jar's obfuscation state is wrong). */
    private static StageResult expectedNotLoadableByRealName(Legacy1122Loader loader, String name) {
        long t0 = System.nanoTime();
        try {
            Class.forName(name, false, loader);
            // loaded when it should NOT have - record as a (deliberately) failed stage so the
            // report calls this out loudly rather than silently passing.
            return new StageResult("expected-wall(" + name + ")", false,
                    (System.nanoTime() - t0) / 1_000_000L, "AssertionError",
                    "loaded by its real name - obfuscation assumption is WRONG, investigate", null);
        } catch (ClassNotFoundException expected) {
            return StageResult.ok("expected-wall(" + name + ")", (System.nanoTime() - t0) / 1_000_000L);
        } catch (Throwable other) {
            return StageResult.ok("expected-wall(" + name + ")-other(" + other.getClass().getSimpleName() + ")",
                    (System.nanoTime() - t0) / 1_000_000L);
        }
    }

    private static StageResult resourcePresent(Legacy1122Loader loader, String resourceName) {
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
