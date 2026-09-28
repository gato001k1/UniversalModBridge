package dev.umb.cli;

import dev.umb.core.ModAnalysis;
import dev.umb.core.ModAnalyzer;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.Callable;

/**
 * `umb` headless tool (spec §16, M0 vertical): analyze a mod jar, check its static linkage
 * against a host Minecraft jar, list known hosts. Exit codes are script-friendly:
 * 0 = success/linked, 1 = usage or IO failure, 2 = analysis completed but mod does NOT link.
 */
@Command(name = "umb",
        mixinStandardHelpOptions = true,
        version = "umb 0.1 (M0)",
        description = "UniversalModBridge: analyze and translate legacy mods for modern hosts.",
        exitCodeOnInvalidInput = 1,
        subcommands = {UmbCli.AnalyzeCommand.class, UmbCli.CheckLinkageCommand.class,
                GraphAuditCommand.class, RemapCommand.class, MatchCommand.class,
                BridgeCommand.class, TranslateCommand.class, SmokeCommand.class,
                LaunchCommand.class, HostCommand.class})
public final class UmbCli implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    // ------------------------------------------------------------------ analyze

    @Command(name = "analyze",
            mixinStandardHelpOptions = true,
            version = "umb 0.1 (M0)",
            description = "Analyze a mod jar: loader, version, namespace, risks.",
            exitCodeOnInvalidInput = 1)
    static final class AnalyzeCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the mod jar.")
        Path jar;

        @Option(names = {"--json"}, description = "Emit machine-readable JSON.")
        boolean json;

        @Spec
        CommandSpec cmdSpec;

        @Override
        public Integer call() throws IOException {
            if (!Files.isRegularFile(jar)) {
                cmdSpec.commandLine().getErr()
                        .println("error: no such file: " + jar);
                return 1;
            }
            ModAnalysis a = new dev.umb.core.BasicModAnalyzer().analyze(jar);
            if (json) {
                emitJson(a);
            } else {
                emitText(a);
            }
            return 0;
        }

        private void emitText(ModAnalysis a) {
            System.out.println("modId            : " + a.modId());
            System.out.println("version          : " + a.modVersion());
            System.out.println("source MC version: " + a.sourceMcVersion().orElse("(unknown)"));
            System.out.println("loader           : " + a.loader());
            System.out.println("namespace        : " + a.namespace());
            System.out.println("max class ver    : " + a.classFileVersionMax()
                    + " (Java " + Math.max(0, a.classFileVersionMax() - 44) + ")");
            System.out.println("entrypoints      : " + a.entrypoints().size());
            System.out.println("mixin configs    : " + a.mixinConfigs().size()
                    + (a.mixinConfigs().isEmpty() ? "" : a.mixinConfigs().stream()
                            .map(m -> "\n                   " + m.path()
                                    + " (" + m.mixins().size() + " mixins"
                                    + (m.refmapPresent() ? ", refmap" : ", NO refmap") + ")")
                            .collect(java.util.stream.Collectors.joining())));
            System.out.println("access mods      : " + (a.accessWideners().size()
                    + a.accessTransformers().size()));
            System.out.println("coremod          : " + (a.hasCoremod()
                    ? "YES (" + String.join(", ", a.coremodClasses()) + ")" : "no"));
            System.out.println("coremod shapes   : " + (a.coremodShapes().isEmpty()
                    ? "none" : a.coremodShapes().stream().map(Enum::name)
                            .collect(java.util.stream.Collectors.joining(", "))));
            ModAnalysis.MixinInventory inv = a.mixinInventory();
            System.out.println("mixin inventory  : " + inv.configs().size() + " config(s), "
                    + inv.declaredMixinCount() + " declared, " + inv.presentInJarCount()
                    + " present, " + inv.missingFromJarCount() + " missing"
                    + (inv.undeclaredMixinClasses().isEmpty() ? ""
                            : " + " + inv.undeclaredMixinClasses().size() + " undeclared"));
            System.out.println("embedded libs    : " + a.embeddedLibraries().size());
            if (!a.knownIncompatibilities().isEmpty()) {
                System.out.println("risks:");
                for (String r : a.knownIncompatibilities()) {
                    System.out.println("  - " + r);
                }
            }
            System.out.println("evidence (" + a.evidence().size() + "):");
            for (ModAnalysis.Evidence e : a.evidence()) {
                System.out.printf(Locale.ROOT, "  [%s] %.2f %s%n", e.kind(), e.confidence(), e.detail());
            }
        }

        private void emitJson(ModAnalysis a) {
            var sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"modId\": ").append(jsonStr(a.modId())).append(",\n");
            sb.append("  \"modVersion\": ").append(jsonStr(a.modVersion())).append(",\n");
            sb.append("  \"sourceMcVersion\": ")
                    .append(jsonStr(a.sourceMcVersion().orElse(null))).append(",\n");
            sb.append("  \"loader\": \"").append(a.loader()).append("\",\n");
            sb.append("  \"namespace\": \"").append(a.namespace()).append("\",\n");
            sb.append("  \"classFileVersionMax\": ").append(a.classFileVersionMax()).append(",\n");
            sb.append("  \"entrypoints\": ").append(jsonList(a.entrypoints())).append(",\n");
            sb.append("  \"hasCoremod\": ").append(a.hasCoremod()).append(",\n");
            sb.append("  \"coremodShapes\": ")
                    .append(jsonList(a.coremodShapes().stream().map(Enum::name).toList()))
                    .append(",\n");
            ModAnalysis.MixinInventory inv = a.mixinInventory();
            sb.append("  \"mixinInventory\": {\n");
            sb.append("    \"configs\": ").append(inv.configs().size()).append(",\n");
            sb.append("    \"declaredMixinCount\": ").append(inv.declaredMixinCount()).append(",\n");
            sb.append("    \"presentInJarCount\": ").append(inv.presentInJarCount()).append(",\n");
            sb.append("    \"missingFromJarCount\": ").append(inv.missingFromJarCount()).append(",\n");
            sb.append("    \"undeclaredMixinClasses\": ")
                    .append(jsonList(inv.undeclaredMixinClasses())).append("\n");
            sb.append("  },\n");
            sb.append("  \"embeddedLibraries\": ");
            sb.append(jsonList(a.embeddedLibraries().stream()
                    .map(l -> l.name()).toList())).append(",\n");
            sb.append("  \"knownIncompatibilities\": ")
                    .append(jsonList(a.knownIncompatibilities())).append(",\n");
            sb.append("  \"evidence\": [").append(a.evidence().stream()
                    .map(e -> "{\"kind\":\"" + e.kind() + "\",\"confidence\":"
                            + e.confidence() + ",\"detail\":" + jsonStr(e.detail()) + "}")
                    .collect(java.util.stream.Collectors.joining(",")))
                    .append("]\n");
            sb.append("}");
            System.out.println(sb);
        }

        private static String jsonStr(String s) {
            return s == null ? "null" : "\"" + s.replace("\\", "\\\\")
                    .replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        }

        private static String jsonList(java.util.List<String> items) {
            return items.stream().map(UmbCli.AnalyzeCommand::jsonStr)
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
        }
    }

    // ------------------------------------------------------------------ check-linkage

    @Command(name = "check-linkage",
            mixinStandardHelpOptions = true,
            version = "umb 0.1 (M0)",
            description = "Statically resolve every method/field ref of MOD against HOST-JAR.",
            exitCodeOnInvalidInput = 1)
    static final class CheckLinkageCommand implements Callable<Integer> {

        @Parameters(index = "0", description = "Path to the candidate mod jar.")
        Path mod;

        @Parameters(index = "1", description = "Path to the host Minecraft jar (deobfuscated).")
        Path hostJar;

        @Option(names = {"--max-report"}, defaultValue = "25",
                description = "Cap listed missing refs (default ${DEFAULT-VALUE}).")
        int maxReport;

        @Spec
        CommandSpec cmdSpec;

        @Override
        public Integer call() throws IOException {
            if (!Files.isRegularFile(mod)) {
                cmdSpec.commandLine().getErr().println("error: no such file: " + mod);
                return 1;
            }
            if (!Files.isRegularFile(hostJar)) {
                cmdSpec.commandLine().getErr().println("error: no such file: " + hostJar);
                return 1;
            }
            long t0 = System.nanoTime();
            try (HostIndex host = HostIndex.of(hostJar)) {
                long t1 = System.nanoTime();
                LinkageChecker.LinkageResult result = new LinkageChecker(host).check(mod);
                long t2 = System.nanoTime();

                System.out.printf(Locale.ROOT,
                        "host index   : %d classes (%.1fs)%n",
                        host.classCount(), (t1 - t0) / 1e9);
                System.out.printf(Locale.ROOT,
                        "scanned      : %d classes, %d refs (%.1fs)%n",
                        result.classesScanned(), result.refsChecked(), (t2 - t1) / 1e9);

                if (result.links()) {
                    System.out.println("linkage      : OK — every host reference resolves");
                    return 0;
                }
                System.out.printf(Locale.ROOT,
                        "linkage      : BROKEN — %d unresolved refs%n", result.missing().size());
                result.missing().stream().limit(maxReport)
                        .forEach(m -> System.out.println("  - " + m));
                if (result.missing().size() > maxReport) {
                    System.out.printf(Locale.ROOT,
                            "  ... and %d more%n", result.missing().size() - maxReport);
                }
                return 2;
            }
        }
    }

    public static void main(String[] args) {
        System.exit(commandLine().execute(args));
    }

    /**
     * Single wiring point for {@link #main} and the tests. The exitCodeOnInvalidInput
     * attributes carry D5's usage-errors-are-1 rule (picocli's default 2 collides
     * with check-linkage's reserved verdict code); this factory adds the last piece:
     * unexpected exceptions report one line on stderr instead of a stack trace,
     * still exiting 1 so a crashed run never reads as linkage success either.
     */
    static CommandLine commandLine() {
        CommandLine cmd = new CommandLine(new UmbCli());
        cmd.setExecutionExceptionHandler((ex, c, parse) -> {
            c.getErr().println("error: " + ex);
            return 1;
        });
        return cmd;
    }
}
