package dev.umb.cli;

import dev.umb.pipeline.SmokeLoader;
import dev.umb.pipeline.SmokeReport;
import dev.umb.pipeline.bridge.DriverResult;
import dev.umb.pipeline.bridge.EntrypointScan;
import dev.umb.pipeline.bridge.EntrypointScanner;
import dev.umb.pipeline.bridge.HostCall;
import dev.umb.pipeline.bridge.HostUniverse;
import dev.umb.pipeline.bridge.InteropRegistry;
import dev.umb.pipeline.bridge.LaunchPlan;
import dev.umb.pipeline.bridge.LifecycleDriver;
import dev.umb.pipeline.bridge.MaterializationException;
import dev.umb.pipeline.bridge.Materialized;
import dev.umb.pipeline.bridge.Materializer;
import dev.umb.pipeline.bridge.MethodSignature;
import dev.umb.pipeline.bridge.ModLoader;
import dev.umb.pipeline.bridge.UniversalHost;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * M12 universal host harness: {@code umb host run|smoke}.
 *
 * <p>Single 26.2 image (research/jars/26.2/client.jar + 88 libs, JDK 25, classfile 69)
 * that smokes + launches ANY one research/out/campaign/* jar each in its own ModLoader
 * child (sequential gate A) and can hold N ModLoaders alive together behind ONE shared
 * InteropRegistry (§29-30, B concurrent stretch). HostUniverse lifetime == registry
 * lifetime. Original jars never mutated (§24). D4 honest failures everywhere.
 */
@Command(name = "host",
        mixinStandardHelpOptions = true,
        version = "umb 0.1 (M12)",
        description = "Universal 26.2 host harness — one image, many mods, one interop world.",
        subcommands = {HostCommand.Run.class, HostCommand.Smoke.class})
public final class HostCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return 0;
    }

    // ------------------------------------------------------------------ host run

    @Command(name = "run",
            mixinStandardHelpOptions = true,
            version = "umb 0.1 (M12)",
            description = "Launch one or more mod jars under ONE HostUniverse + shared InteropRegistry.",
            exitCodeOnInvalidInput = 1)
    public static final class Run implements Callable<Integer> {

        @Option(names = "--host", required = true, description = "Host Minecraft jar (26.2 deobfuscated).")
        Path hostJar;

        @Option(names = "--lib", arity = "1", description = "Host library jar or directory; repeatable.")
        List<Path> libPaths;

        @Option(names = "--mod", arity = "1", required = true,
                description = "Mod jar to open under the host (repeatable for concurrent harness).")
        List<Path> modJars;

        @Option(names = "--entry", arity = "1", description = "Additional entrypoint class; repeatable.")
        List<String> cliEntrypoints;

        @Option(names = "--plan", description = "Plan JSON (ordered entries with publish/consume roles).")
        Path planPath;

        @Option(names = "--method", defaultValue = LifecycleDriver.DEFAULT_LIFECYCLE,
                description = "Lifecycle method name (default ${DEFAULT-VALUE}).")
        String method;

        @Option(names = "--allow-failures", description = "Exit 0 even when some entrypoints fail.")
        boolean allowFailures;

        @Option(names = "--json", description = "Emit exactly one flat JSON object.")
        boolean json;

        @Spec
        CommandSpec cmdSpec;

        private List<Path> resolvedLibs = List.of();

        @Override
        public Integer call() {
            PrintWriter out = cmdSpec.commandLine().getOut();
            PrintWriter err = cmdSpec.commandLine().getErr();

            if (!Files.isRegularFile(hostJar)) {
                err.println("error: no such file: " + hostJar);
                return 1;
            }
            List<Path> mods = modJars == null ? List.of() : List.copyOf(modJars);
            if (mods.isEmpty()) {
                err.println("error: no --mod given");
                return 1;
            }
            for (Path m : mods) {
                if (!Files.isRegularFile(m)) {
                    err.println("error: no such file: " + m);
                    return 1;
                }
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

            // Validate jars have class entries (D4)
            for (Path m : mods) {
                try {
                    if (countClassEntries(m) == 0) {
                        err.println("error: 0 class entries; nothing to launch in " + m);
                        return 1;
                    }
                } catch (IOException e) {
                    err.println("error: " + e.getMessage());
                    return 1;
                }
            }

            LaunchPlan plan = null;
            if (planPath != null) {
                try {
                    plan = LaunchPlan.parse(planPath);
                } catch (IOException e) {
                    err.println("error: " + e.getMessage());
                    return 1;
                } catch (MaterializationException e) {
                    err.println("error: " + e.getMessage());
                    return 1;
                }
            }

            try {
                resolvedLibs = SmokeLoader.resolveLibJars(libs);
            } catch (IOException e) {
                err.println("error: " + e.getMessage());
                return 1;
            }

            // Build drive set: plan first, then scans of each mod, then --entry — dedup by class, first wins
            Map<String, String> byClass = new LinkedHashMap<>();
            Map<String, LaunchPlan.PlanEntry> planByClass = new LinkedHashMap<>();
            if (plan != null) {
                for (LaunchPlan.PlanEntry pe : plan.entries()) {
                    if (byClass.putIfAbsent(pe.className(), "cli:--plan") == null) {
                        planByClass.put(pe.className(), pe);
                    }
                }
            }
            // Scan each mod for entrypoints
            for (Path m : mods) {
                try {
                    EntrypointScan scan = EntrypointScanner.scan(m);
                    for (EntrypointScan.Entrypoint ep : scan.entrypoints()) {
                        byClass.putIfAbsent(ep.className(), ep.source());
                    }
                } catch (IOException e) {
                    err.println("error: " + e.getMessage());
                    return 1;
                }
            }
            if (cliEntrypoints != null) {
                for (String cls : cliEntrypoints) {
                    byClass.putIfAbsent(cls, "cli:--entry");
                }
            }
            if (byClass.isEmpty()) {
                err.println("error: no entrypoints declared in " + mods + " and no --entry/--plan class");
                return 1;
            }

            // ONE host, N mod loaders, ONE registry — lifetime == host lifetime (B stretch)
            try (UniversalHost host = new UniversalHost(hostJar, resolvedLibs)) {
                List<ModLoader> loaders = new ArrayList<>(mods.size());
                for (Path m : mods) {
                    loaders.add(host.openMod(m));
                }
                Map<String, ModLoader> loaderForClass = new LinkedHashMap<>();
                // Resolve which loader owns each class (first loader that can load it)
                for (String cls : byClass.keySet()) {
                    ModLoader owner = null;
                    for (ModLoader ml : loaders) {
                        try {
                            ml.entrypointClass(cls);
                            owner = ml;
                            break;
                        } catch (ClassNotFoundException | LinkageError ignored) {
                        }
                    }
                    if (owner != null) {
                        loaderForClass.put(cls, owner);
                    } else {
                        // No loader has it — will surface as MISSING_ENTRYPOINT_CLASS per-entry
                        loaderForClass.put(cls, loaders.get(0));
                    }
                }

                InteropRegistry registry = host.registry();
                List<LaunchCommand.EntryResult> results = new ArrayList<>(byClass.size());
                for (Map.Entry<String, String> e : byClass.entrySet()) {
                    String cls = e.getKey();
                    String src = e.getValue();
                    ModLoader owner = loaderForClass.get(cls);
                    results.add(runOne(host.universe(), owner, cls, src, planByClass.get(cls), method, registry));
                }
                int failed = (int) results.stream().filter(r -> !r.completed()).count();
                if (json) {
                    out.println(jsonObject(mods, results, failed));
                } else {
                    out.printf(Locale.ROOT, "host %s (%d lib jars) — launching %d entrypoint%s from %d mod%s%n",
                            hostJar, resolvedLibs.size(), byClass.size(), byClass.size() == 1 ? "" : "s",
                            mods.size(), mods.size() == 1 ? "" : "s");
                    for (LaunchCommand.EntryResult r : results) out.println("  " + r.line());
                    out.printf(Locale.ROOT, "  failed: %d%n", failed);
                }
                return failed == 0 ? 0 : (allowFailures ? 0 : 1);
            } catch (IOException e) {
                err.println("error: " + e.getMessage());
                return 1;
            }
        }

        private static LaunchCommand.EntryResult runOne(HostUniverse host, ModLoader mod, String className,
                                                        String source, LaunchPlan.PlanEntry plan,
                                                        String method, InteropRegistry registry) {
            if (plan != null && plan.hasConsume()) return runConsuming(host, mod, className, source, plan, method, registry);
            try {
                var result = LifecycleDriver.drive(mod, className, method, List.of(), List.of());
                if (result.completed()) return LaunchCommand.EntryResult.completed(className, source, List.of(),
                        publishFrom(host, registry, plan, result.returnValue()));
                return LaunchCommand.EntryResult.threw(className, source, result.cause());
            } catch (MaterializationException e) {
                return LaunchCommand.EntryResult.missing(className, source, e.kind(), List.of());
            } catch (LinkageError e) {
                return LaunchCommand.EntryResult.threw(className, source, e);
            }
        }

        private static LaunchCommand.EntryResult runConsuming(HostUniverse host, ModLoader mod, String className,
                                                              String source, LaunchPlan.PlanEntry plan,
                                                              String method, InteropRegistry registry) {
            List<LaunchPlan.ConsumeDirective> dirs = new ArrayList<>(plan.consume());
            dirs.sort(Comparator.comparingInt(LaunchPlan.ConsumeDirective::paramIndex));
            int arity = dirs.size();
            Class<?> ep;
            try {
                ep = mod.entrypointClass(className);
            } catch (ClassNotFoundException | LinkageError e) {
                return LaunchCommand.EntryResult.missing(className, source, MaterializationException.Kind.MISSING_ENTRYPOINT_CLASS, List.of());
            }
            Method lifecycle;
            try {
                lifecycle = LifecycleDriver.findLifecycleMethod(ep, method, arity);
            } catch (MaterializationException e) {
                return LaunchCommand.EntryResult.missing(className, source, e.kind(), List.of());
            }
            Class<?>[] paramTypes = lifecycle.getParameterTypes();
            List<String> consumed = new ArrayList<>(arity);
            List<Class<?>> argTypes = new ArrayList<>(arity);
            List<Object> args = new ArrayList<>(arity);
            for (int i = 0; i < arity; i++) {
                LaunchPlan.ConsumeDirective d = dirs.get(i);
                if (d.paramIndex() != i) return LaunchCommand.EntryResult.missing(className, source, MaterializationException.Kind.CONSUME_MISMATCH, List.of(), "consume directives must name parameters 0.." + (arity - 1) + " once each, got index " + d.paramIndex() + " at position " + i);
                Class<?> iface = paramTypes[i];
                if (!iface.isInterface()) return LaunchCommand.EntryResult.missing(className, source, MaterializationException.Kind.CONSUME_MISMATCH, List.of(), "consume parameter " + i + " must be an interface, but lifecycle declares " + iface.getName());
                Materialized resolved;
                try {
                    resolved = registry.resolve(d.identifier());
                    Map<MethodSignature, HostCall> bindings = Materializer.bindByName(iface, resolved.hostClass());
                    args.add(registry.view(resolved, iface, bindings));
                } catch (MaterializationException e) {
                    if (e.kind() == MaterializationException.Kind.NOT_PUBLISHED) return LaunchCommand.EntryResult.missing(className, source, e.kind(), List.of(d.identifier()));
                    return LaunchCommand.EntryResult.missing(className, source, e.kind(), List.of());
                }
                argTypes.add(iface);
                consumed.add(d.identifier());
            }
            DriverResult result;
            try {
                result = LifecycleDriver.drive(mod, className, method, argTypes, args);
            } catch (MaterializationException e) {
                return LaunchCommand.EntryResult.missing(className, source, e.kind(), List.of());
            } catch (LinkageError e) {
                return LaunchCommand.EntryResult.threw(className, source, e);
            }
            if (result.completed()) return LaunchCommand.EntryResult.completed(className, source, consumed, publishFrom(host, registry, plan, result.returnValue()));
            return LaunchCommand.EntryResult.threw(className, source, result.cause());
        }

        private static String publishFrom(HostUniverse host, InteropRegistry registry, LaunchPlan.PlanEntry plan, Object returnValue) {
            if (plan == null || !plan.hasPublish() || returnValue == null) return null;
            Materialized r = Materializer.recover(returnValue, host);
            if (r == null) return null;
            registry.publish(plan.publishId(), r);
            return plan.publishId();
        }

        private String jsonObject(List<Path> mods, List<LaunchCommand.EntryResult> results, int failed) {
            StringBuilder s = new StringBuilder();
            s.append("{\"modJars\":[");
            for (int i = 0; i < mods.size(); i++) {
                if (i > 0) s.append(",");
                s.append(str(mods.get(i).toString()));
            }
            s.append("],\"hostJar\":").append(str(hostJar.toString()))
                    .append(",\"libCount\":").append(resolvedLibs.size()).append(",\"entrypoints\":[");
            boolean first = true;
            for (LaunchCommand.EntryResult r : results) {
                if (!first) s.append(",");
                first = false;
                s.append("{\"class\":").append(str(r.className())).append(",\"source\":").append(str(r.source())).append("}");
            }
            s.append("],\"results\":[");
            first = true;
            for (LaunchCommand.EntryResult r : results) {
                if (!first) s.append(",");
                first = false;
                s.append("{\"class\":").append(str(r.className())).append(",\"status\":").append(str(r.status()));
                if (r.detail() != null) s.append(",\"detail\":").append(str(r.detail()));
                if (!r.consumed().isEmpty()) {
                    s.append(",\"consumed\":[");
                    for (int i = 0; i < r.consumed().size(); i++) {
                        if (i > 0) s.append(",");
                        s.append(str(r.consumed().get(i)));
                    }
                    s.append("]");
                }
                if (r.published() != null) s.append(",\"published\":").append(str(r.published()));
                s.append("}");
            }
            s.append("],\"completed\":").append(results.size() - failed).append(",\"failed\":").append(failed).append(",\"exit\":").append(failed == 0 || allowFailures ? 0 : 1).append("}");
            return s.toString();
        }

        private static String str(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }

        private static int countClassEntries(Path jar) throws IOException {
            int n = 0;
            try (JarFile jf = new JarFile(jar.toFile())) {
                for (JarEntry e : java.util.Collections.list(jf.entries())) if (e.getName().endsWith(".class")) n++;
            }
            return n;
        }
    }

    // ------------------------------------------------------------------ host smoke

    @Command(name = "smoke",
            mixinStandardHelpOptions = true,
            version = "umb 0.1 (M12)",
            description = "Smoke one or more mod jars under ONE HostUniverse (shared 26.2 image).",
            exitCodeOnInvalidInput = 1)
    public static final class Smoke implements Callable<Integer> {

        @Parameters(arity = "1..*", description = "Mod jars to smoke.")
        List<Path> modJars;

        @Option(names = "--host", required = true, description = "Host Minecraft jar.")
        Path hostJar;

        @Option(names = "--lib", arity = "1", description = "Host library jar or directory; repeatable.")
        List<Path> libPaths;

        @Option(names = "--sample-limit", defaultValue = "25", description = "Cap listed samples (default ${DEFAULT-VALUE}).")
        int sampleLimit;

        @Option(names = "--allow-failures", description = "Exit 0 even when some classes fail.")
        boolean allowFailures;

        @Option(names = "--json", description = "Emit exactly one flat JSON object.")
        boolean json;

        @Spec
        CommandSpec cmdSpec;

        @Override
        public Integer call() {
            PrintWriter out = cmdSpec.commandLine().getOut();
            PrintWriter err = cmdSpec.commandLine().getErr();
            if (modJars == null || modJars.isEmpty()) {
                err.println("error: no mod jar given");
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
            List<Path> jars = List.copyOf(modJars);
            for (Path m : jars) if (!Files.isRegularFile(m)) { err.println("error: no such file: " + m); return 1; }

            List<Path> resolvedLibs;
            try {
                resolvedLibs = SmokeLoader.resolveLibJars(libs);
            } catch (IOException e) {
                err.println("error: " + e.getMessage());
                return 1;
            }

            // ONE host for all smokes
            try (UniversalHost host = new UniversalHost(hostJar, resolvedLibs)) {
                List<SmokeReport> reports = new ArrayList<>(jars.size());
                for (Path m : jars) {
                    try {
                        SmokeReport r = UniversalHost.smokeUnder(m, host.openMod(m).loader());
                        if (r.parseableEntries() == 0) {
                            err.println("error: 0 class entries; nothing to smoke in " + m);
                            return 1;
                        }
                        reports.add(r);
                    } catch (IOException e) {
                        err.println("error: " + e.getMessage());
                        return 1;
                    }
                }
                boolean allClean = reports.stream().allMatch(SmokeReport::clean);
                if (json) {
                    out.println(jsonObject(jars, resolvedLibs, reports));
                } else {
                    out.printf(Locale.ROOT, "smoked %d mod%s under host %s (%d host lib jars)%n",
                            jars.size(), jars.size() == 1 ? "" : "s", hostJar, resolvedLibs.size());
                    for (int i = 0; i < jars.size(); i++) {
                        SmokeReport r = reports.get(i);
                        out.printf(Locale.ROOT, "  %s: parseable=%d loaded=%d failed=%d missing=%d%n",
                                jars.get(i).getFileName(), r.parseableEntries(), r.loaded(), r.failed(), r.missingSymbols().size());
                    }
                }
                return allClean ? 0 : (allowFailures ? 0 : 1);
            } catch (IOException e) {
                err.println("error: " + e.getMessage());
                return 1;
            }
        }

        private String jsonObject(List<Path> mods, List<Path> libs, List<SmokeReport> reports) {
            StringBuilder s = new StringBuilder();
            s.append("{\"hostJar\":").append(str(hostJar.toString())).append(",\"libCount\":").append(libs.size()).append(",\"mods\":[");
            for (int i = 0; i < mods.size(); i++) {
                if (i > 0) s.append(",");
                SmokeReport r = reports.get(i);
                s.append("{\"modJar\":").append(str(mods.get(i).toString()))
                        .append(",\"totalClasses\":").append(r.totalClasses())
                        .append(",\"parseableEntries\":").append(r.parseableEntries())
                        .append(",\"loaded\":").append(r.loaded())
                        .append(",\"failed\":").append(r.failed())
                        .append(",\"missingSymbols\":").append(r.missingSymbols().size()).append("}");
            }
            s.append("]}");
            return s.toString();
        }

        private static String str(String s) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
    }
}
