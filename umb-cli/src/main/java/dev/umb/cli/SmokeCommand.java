package dev.umb.cli;

import dev.umb.pipeline.SmokeLoader;
import dev.umb.pipeline.SmokeReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * umb smoke IN.jar --host HOST.jar [--lib PATH]... [--sample-limit N]
 * [--allow-failures] [--json]: compatibility ladder stage 3 - every class entry of
 * IN loads under the host classloader shape (mod sees host + libs, host never sees
 * the mod). LOADED means the JVM defined the class and every type in its constant
 * pool resolved in the host universe; FAILED classes carry the root cause, and
 * CNFE/NCDFE causes feed the missing-symbol sample.
 *
 * <p>Exit contract: 0 iff failed == 0; a linkage-failure run prints the honest
 * report and exits 1 unless --allow-failures (then 0, but the failed count still
 * prints - never a bare success line). D4: zero parseable class entries is refused
 * before any report claim. With --json, stdout carries exactly one flat JSON
 * object; usage and IO errors go to stderr via the picocli err writer and exit 1.
 */
@Command(name = "smoke",
        mixinStandardHelpOptions = true,
        version = "umb 0.1 (M3)",
        description = "Load every class of IN under the host classloader shape (host jar + --libs).",
        exitCodeOnInvalidInput = 1)
public final class SmokeCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Mod jar to smoke.")
    Path inJar;

    @Option(names = "--host", required = true,
            description = "Host Minecraft jar the mod loads against.")
    Path hostJar;

    @Option(names = "--lib", arity = "1",
            description = "Host library jar or directory (any *.jar under it); repeatable.")
    List<Path> libPaths;

    @Option(names = "--sample-limit", defaultValue = "25",
            description = "Cap listed missing-symbol and failure samples (default ${DEFAULT-VALUE}).")
    int sampleLimit;

    @Option(names = "--allow-failures",
            description = "Exit 0 even when some classes fail to load; the report still prints.")
    boolean allowFailures;

    @Option(names = "--json", description = "Emit exactly one flat JSON object.")
    boolean json;

    @Spec
    CommandSpec cmdSpec;

    private List<Path> hostJars = List.of();

    @Override
    public Integer call() {
        PrintWriter out = cmdSpec.commandLine().getOut();
        PrintWriter err = cmdSpec.commandLine().getErr();

        if (!Files.isRegularFile(inJar)) {
            err.println("error: no such file: " + inJar);
            return 1;
        }
        if (!Files.isRegularFile(hostJar)) {
            err.println("error: no such file: " + hostJar);
            return 1;
        }
        if (sampleLimit < 0) {
            err.println("error: --sample-limit must be >= 0, got " + sampleLimit);
            return 1;
        }
        List<Path> libs = libPaths == null ? List.of() : List.copyOf(libPaths);
        for (Path lib : libs) {
            if (!Files.exists(lib)) {
                err.println("error: no such file: " + lib);
                return 1;
            }
            if (!Files.isDirectory(lib) && !lib.getFileName().toString().endsWith(".jar")) {
                err.println("error: --lib must be a .jar file or a directory: " + lib);
                return 1;
            }
        }

        SmokeReport report;
        try {
            hostJars = SmokeLoader.resolveLibJars(libs);
            report = SmokeLoader.smoke(inJar, hostJar, hostJars);
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }

        if (report.parseableEntries() == 0) {
            // D4: an empty-or-package-info-only jar must never read as a pass.
            err.println("error: 0 class entries; nothing to smoke in " + inJar);
            return 1;
        }

        if (json) {
            out.println(jsonObject(report));
        } else {
            out.printf(Locale.ROOT, "smoked %s under host %s (%d host lib jar%s)%n",
                    inJar, hostJar, hostJars.size(), hostJars.size() == 1 ? "" : "s");
            out.printf(Locale.ROOT, "  %-16s: %d%n", "classes", report.totalClasses());
            out.printf(Locale.ROOT, "  %-16s: %d%n", "parseable", report.parseableEntries());
            out.printf(Locale.ROOT, "  %-16s: %d%n", "loaded", report.loaded());
            out.printf(Locale.ROOT, "  %-16s: %d%n", "failed", report.failed());
            out.printf(Locale.ROOT, "  %-16s: %d%n", "missing symbols", report.missingSymbols().size());
            List<Map.Entry<String, Integer>> missing = report.missingSample(sampleLimit);
            for (Map.Entry<String, Integer> m : missing) {
                out.printf(Locale.ROOT, "    ! missing: %s (referenced by %d classes)%n",
                        m.getKey(), m.getValue());
            }
            if (missing.size() < report.missingSymbols().size()) {
                out.printf(Locale.ROOT, "    ... and %d more missing%n",
                        report.missingSymbols().size() - missing.size());
            }
            List<Map.Entry<String, String>> failures = report.failureSample(sampleLimit);
            for (Map.Entry<String, String> f : failures) {
                out.printf(Locale.ROOT, "    ! failed: %s - %s%n", f.getKey(), f.getValue());
            }
            if (failures.size() < report.failures().size()) {
                out.printf(Locale.ROOT, "    ... and %d more failed%n",
                        report.failures().size() - failures.size());
            }
        }

        if (report.clean()) {
            return 0;
        }
        return allowFailures ? 0 : 1;
    }

    /** One flat JSON object; sample lists honour the --sample-limit cap. */
    private String jsonObject(SmokeReport r) {
        StringBuilder s = new StringBuilder();
        s.append("{\"modJar\":").append(str(inJar.toString()))
                .append(",\"hostJar\":").append(str(hostJar.toString()))
                .append(",\"libCount\":").append(hostJars.size())
                .append(",\"totalClasses\":").append(r.totalClasses())
                .append(",\"skipped\":").append(r.skipped())
                .append(",\"parseableEntries\":").append(r.parseableEntries())
                .append(",\"loaded\":").append(r.loaded())
                .append(",\"failed\":").append(r.failed())
                .append(",\"missingSymbols\":").append(r.missingSymbols().size())
                .append(",\"missingSample\":[");
        boolean first = true;
        for (Map.Entry<String, Integer> m : r.missingSample(sampleLimit)) {
            if (!first) {
                s.append(",");
            }
            first = false;
            s.append("{\"class\":").append(str(m.getKey()))
                    .append(",\"classes\":").append(m.getValue()).append("}");
        }
        s.append("],\"failureSample\":[");
        first = true;
        for (Map.Entry<String, String> f : r.failureSample(sampleLimit)) {
            if (!first) {
                s.append(",");
            }
            first = false;
            s.append("{\"class\":").append(str(f.getKey()))
                    .append(",\"reason\":").append(str(f.getValue())).append("}");
        }
        s.append("]}");
        return s.toString();
    }

    private static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
