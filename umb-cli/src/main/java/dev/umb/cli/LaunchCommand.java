package dev.umb.cli;

import dev.umb.pipeline.SmokeLoader;
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
 * umb launch IN.jar --host HOST.jar [--lib PATH]... [--entry CLASS]... [--plan PLAN.json]
 * [--method NAME] [--allow-failures] [--json]: Bridge M7 vertical - discover the mod's
 * declared entrypoints, load them in the mod classloader (child of the real host universe),
 * and drive each lifecycle method. Discovery is honest {@link EntrypointScanner} output:
 * fabric.mod.json entrypoints ({@code fabric:main}, {@code fabric:client}, ...) and Forge
 * {@code @Mod} classes ({@code forge:@Mod}). {@code --entry} classes are ADDED to the
 * found set (a class named twice still launches once); {@code --method} renames the
 * lifecycle method (default onInitialize).
 *
 * <p>{@code --plan} is the M7 interop surface: an ORDERED list of entries carrying the
 * publish/consume roles (see {@link LaunchPlan}). Plan entries drive FIRST in plan order
 * (the interop ordering the vertical needs), then scan findings, then {@code --entry}
 * additions — all deduplicated by class name, first occurrence wins. Scan-only mode (no
 * plan) is exactly the legacy launch: every entry drives with NO arguments.
 *
 * <p>Exchange mechanics, D4-honest at every step:
 * <ul>
 *   <li>PUBLISH — a provider entry with no consume drives with NO args; if its lifecycle
 *       RETURNED an object recoverable by {@link Materializer#recover} (a materialized
 *       proxy or a raw host-universe instance), the HOST INSTANCE behind it is published
 *       under the identifier ({@code published:ID} on the entry line). A void or foreign
 *       return publishes nothing — no error, nothing declared.</li>
 *   <li>CONSUME — a consuming entry drives with arguments injected by parameter index:
 *       each identifier is resolved ({@code NOT_PUBLISHED} is a per-entry missing line and
 *       the batch continues), then VIEWED behind the lifecycle method's OWN declared
 *       parameter interface (the class comes from the method signature, and the view is
 *       built by the same {@link Materializer} machinery — one host instance, every side a
 *       view, never a copy). Arity/index gaps and non-interface parameters are named
 *       {@code CONSUME_MISMATCH} D4 failures, not silent casts.</li>
 *   <li>A malformed plan is a D4 refusal (stderr, exit 1): parsed with the scanner's
 *       tolerance discipline, nothing drives, never a guess.</li>
 * </ul>
 *
 * <p>Each entry runs through {@link LifecycleDriver}; one line per entry, extended with
 * {@code consumed:} and {@code published:} outcomes when the plan declares roles:
 * {@code CLASS completed}, {@code CLASS threw:cause} (the entrypoint's own exception or a
 * drive-time {@link LinkageError} — absent supertype, throwing static initializer), or
 * {@code CLASS missing:KIND}. Exit 0 iff every entry completed, or always with
 * {@code --allow-failures} (the failed count still prints).
 *
 * <p>D4 refusals (stderr, exit 1, nothing written): IN has zero class entries; the scan
 * found zero entrypoints AND no {@code --entry} and no {@code --plan} entry was given —
 * never a guessed name. With {@code --json} stdout carries exactly one flat JSON object;
 * usage errors exit 1 via exitCodeOnInvalidInput.
 */
@Command(name = "launch",
        mixinStandardHelpOptions = true,
        version = "umb 0.1 (M7)",
        description = "Launch a mod's declared entrypoints against a real host universe (plan-orchestrated).",
        exitCodeOnInvalidInput = 1)
public final class LaunchCommand implements Callable<Integer> {

    /** Entry driven by an explicit --entry flag; not a scan claim. */
    private static final String CLI_SOURCE = "cli:--entry";
    /** Entry whose role comes from --plan; not a scan claim. */
    private static final String PLAN_SOURCE = "cli:--plan";

    @Parameters(index = "0", description = "Mod jar to launch.")
    Path inJar;

    @Option(names = "--host", required = true,
            description = "Host Minecraft jar the mod loads against.")
    Path hostJar;

    @Option(names = "--lib", arity = "1",
            description = "Host library jar or directory (any *.jar under it); repeatable.")
    List<Path> libPaths;

    @Option(names = "--entry", arity = "1",
            description = "Additional entrypoint class to launch (added to the scan findings); repeatable.")
    List<String> cliEntrypoints;

    @Option(names = "--plan",
            description = "Plan JSON (ordered entries with publish/consume roles); drives first, composes with --entry.")
    Path planPath;

    @Option(names = "--method", defaultValue = LifecycleDriver.DEFAULT_LIFECYCLE,
            description = "Lifecycle method name driven with no args (default ${DEFAULT-VALUE}).")
    String method;

    @Option(names = "--allow-failures",
            description = "Exit 0 even when some entrypoints fail; the per-entry lines still print.")
    boolean allowFailures;

    @Option(names = "--json", description = "Emit exactly one flat JSON object.")
    boolean json;

    @Spec
    CommandSpec cmdSpec;

    /** Raw --lib paths resolved to actual jars (a directory yields every *.jar under it). */
    private List<Path> resolvedLibs = List.of();

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

        // --- discovery (mod jar only; host universe is not opened on a refusal)
        EntrypointScan scan;
        int classEntries;
        try {
            // Confirm the --lib args first: a directory yields every *.jar under it.
            resolvedLibs = SmokeLoader.resolveLibJars(libs);
            classEntries = countClassEntries(inJar);
            scan = EntrypointScanner.scan(inJar);
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }

        // D4: an empty jar must never read as a pass.
        if (classEntries == 0) {
            err.println("error: 0 class entries; nothing to launch in " + inJar);
            return 1;
        }

        // --plan is parsed BEFORE anything drives; a malformed plan is a D4 refusal naming
        // exactly what was wrong, and the host universe is not opened.
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

        // Drive set, deduplicated by class name, first occurrence wins:
        //   1. plan entries in plan order (interop ordering), role attached;
        //   2. scan findings not already named, scan order (deterministic);
        //   3. --entry overrides not already named, --entry order.
        Map<String, String> byClass = new LinkedHashMap<>();
        Map<String, LaunchPlan.PlanEntry> planByClass = new LinkedHashMap<>();
        if (plan != null) {
            for (LaunchPlan.PlanEntry pe : plan.entries()) {
                if (byClass.putIfAbsent(pe.className(), PLAN_SOURCE) == null) {
                    planByClass.put(pe.className(), pe);
                }
            }
        }
        for (EntrypointScan.Entrypoint ep : scan.entrypoints()) {
            byClass.putIfAbsent(ep.className(), ep.source());
        }
        if (cliEntrypoints != null) {
            for (String cls : cliEntrypoints) {
                byClass.putIfAbsent(cls, CLI_SOURCE);
            }
        }
        if (byClass.isEmpty()) {
            // D4: no declared entrypoints and no override - report what was looked for,
            // never invent a class name.
            err.println("error: no entrypoints declared in " + inJar
                    + " (looked for " + String.join(", ", scan.sourcesLookedFor()) + ")"
                    + " and no --entry given"
                    + (plan != null ? " and --plan named no classes" : ""));
            return 1;
        }

        // --- drive each entry against the real host universe
        try (HostUniverse host = new HostUniverse(hostJar, resolvedLibs);
             ModLoader mod = new ModLoader(inJar, host)) {
            InteropRegistry registry = new InteropRegistry();
            List<EntryResult> results = new ArrayList<>(byClass.size());
            for (Map.Entry<String, String> e : byClass.entrySet()) {
                results.add(runOne(host, mod, e.getKey(), e.getValue(),
                        planByClass.get(e.getKey()), method, registry));
            }
            int failed = (int) results.stream().filter(r -> !r.completed()).count();

            if (json) {
                out.println(jsonObject(results, failed));
            } else {
                out.printf(Locale.ROOT, "launching %d entrypoint%s under host %s (%d host lib jar%s)%n",
                        byClass.size(), byClass.size() == 1 ? "" : "s",
                        hostJar, resolvedLibs.size(), resolvedLibs.size() == 1 ? "" : "s");
                for (EntryResult r : results) {
                    out.println("  " + r.line());
                }
                out.printf(Locale.ROOT, "  failed: %d%n", failed);
            }
            return failed == 0 ? 0 : (allowFailures ? 0 : 1);
        } catch (IOException e) {
            err.println("error: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Drive one entrypoint. A consuming entry (a plan role naming consume directives) goes
     * through the interop path; every other entry — role-less OR publish-only — drives
     * with NO args exactly as today, and a completed publish-role entry hands its lifecycle
     * return value to the registry. Outcomes are classified like the smoke stage: a
     * completed drive, an entrypoint that threw (its real cause or a drive-time
     * {@link LinkageError}), or a named {@link MaterializationException} kind — never a
     * silent swallow. Any other RuntimeException is a driver/CLI bug and stays loud (the
     * picocli execution-exception handler reports it, exit 1).
     */
    private static EntryResult runOne(HostUniverse host, ModLoader mod, String className, String source,
                                      LaunchPlan.PlanEntry plan, String method, InteropRegistry registry) {
        if (plan != null && plan.hasConsume()) {
            return runConsuming(host, mod, className, source, plan, method, registry);
        }
        try {
            var result = LifecycleDriver.drive(mod, className, method, List.of(), List.of());
            if (result.completed()) {
                return EntryResult.completed(className, source,
                        List.of(), publishFrom(host, registry, plan, result.returnValue(), className));
            }
            return EntryResult.threw(className, source, result.cause());
        } catch (MaterializationException e) {
            return EntryResult.missing(className, source, e.kind(), List.of());
        } catch (LinkageError e) {
            // The drive surfaced while linking/initialising (absent supertype at
            // newInstance, clinit explosion). Report it as this entry's threw.
            return EntryResult.threw(className, source, e);
        }
    }

    /**
     * The M7 consume path. Resolves each directive's identifier, views the published host
     * instance behind the lifecycle method's OWN declared parameter interface — loaded from
     * the consumer's {@link ModLoader}, derived from the method signature, never guessed —
     * and injects the views at their declared parameter indexes. The directives must name
     * exactly parameters 0..arity-1 once each; any gap, duplicate, or index past the end is
     * a named {@code CONSUME_MISMATCH}, and a parameter that is not an interface cannot be
     * proxied (same kind). A resolve that names nothing published is a per-entry
     * {@code NOT_PUBLISHED} missing line; the batch continues.
     */
    private static EntryResult runConsuming(HostUniverse host, ModLoader mod, String className, String source,
                                            LaunchPlan.PlanEntry plan, String method, InteropRegistry registry) {
        List<LaunchPlan.ConsumeDirective> directives = new ArrayList<>(plan.consume());
        directives.sort(Comparator.comparingInt(LaunchPlan.ConsumeDirective::paramIndex));
        int arity = directives.size();

        Class<?> entrypoint;
        try {
            entrypoint = mod.entrypointClass(className);
        } catch (ClassNotFoundException | LinkageError e) {
            return EntryResult.missing(className, source,
                    MaterializationException.Kind.MISSING_ENTRYPOINT_CLASS, List.of());
        }
        Method lifecycle;
        try {
            lifecycle = LifecycleDriver.findLifecycleMethod(entrypoint, method, arity);
        } catch (MaterializationException e) {
            return EntryResult.missing(className, source, e.kind(), List.of());
        }
        Class<?>[] paramTypes = lifecycle.getParameterTypes();

        List<String> consumed = new ArrayList<>(arity);
        List<Class<?>> argTypes = new ArrayList<>(arity);
        List<Object> args = new ArrayList<>(arity);
        for (int i = 0; i < arity; i++) {
            LaunchPlan.ConsumeDirective d = directives.get(i);
            if (d.paramIndex() != i) {
                return EntryResult.missing(className, source,
                        MaterializationException.Kind.CONSUME_MISMATCH, List.of(),
                        "consume directives must name parameters 0.." + (arity - 1)
                                + " once each, got index " + d.paramIndex() + " at position " + i);
            }
            Class<?> iface = paramTypes[i];
            if (!iface.isInterface()) {
                return EntryResult.missing(className, source,
                        MaterializationException.Kind.CONSUME_MISMATCH, List.of(),
                        "consume parameter " + i + " must be an interface, but lifecycle declares "
                                + iface.getName());
            }
            Materialized resolved;
            try {
                resolved = registry.resolve(d.identifier());
                Map<MethodSignature, HostCall> bindings =
                        Materializer.bindByName(iface, resolved.hostClass());
                args.add(registry.view(resolved, iface, bindings));
            } catch (MaterializationException e) {
                if (e.kind() == MaterializationException.Kind.NOT_PUBLISHED) {
                    return EntryResult.missing(className, source, e.kind(), List.of(d.identifier()));
                }
                return EntryResult.missing(className, source, e.kind(), List.of());
            }
            argTypes.add(iface);
            consumed.add(d.identifier());
        }

        DriverResult result;
        try {
            result = LifecycleDriver.drive(mod, className, method, argTypes, args);
        } catch (MaterializationException e) {
            return EntryResult.missing(className, source, e.kind(), List.of());
        } catch (LinkageError e) {
            return EntryResult.threw(className, source, e);
        }
        if (result.completed()) {
            return EntryResult.completed(className, source, consumed,
                    publishFrom(host, registry, plan, result.returnValue(), className));
        }
        return EntryResult.threw(className, source, result.cause());
    }

    /**
     * PUBLISH: recover the HOST INSTANCE behind a completed lifecycle's return value and
     * register it under the plan's identifier. Recoverability is honest — a materialized/
     * view proxy yields its backed host instance, a raw host-universe instance is its own
     * host, and a VOID or foreign return publishes NOTHING (no error: nothing declared).
     *
     * @return the identifier when something was published, else null
     */
    private static String publishFrom(HostUniverse host, InteropRegistry registry, LaunchPlan.PlanEntry plan,
                                      Object returnValue, String className) {
        if (plan == null || !plan.hasPublish() || returnValue == null) {
            return null;
        }
        Materialized recovered = Materializer.recover(returnValue, host);
        if (recovered == null) {
            return null; // void or foreign return: nothing declared, nothing published
        }
        registry.publish(plan.publishId(), recovered);
        return plan.publishId();
    }

    /** One flat JSON object: scan inputs, every attempt, and the verdict counts. */
    private String jsonObject(List<EntryResult> results, int failed) {
        StringBuilder s = new StringBuilder();
        s.append("{\"modJar\":").append(str(inJar.toString()))
                .append(",\"hostJar\":").append(str(hostJar.toString()))
                .append(",\"libCount\":").append(resolvedLibs.size())
                .append(",\"entrypoints\":[");
        boolean first = true;
        for (EntryResult r : results) {
            if (!first) {
                s.append(",");
            }
            first = false;
            s.append("{\"class\":").append(str(r.className()))
                    .append(",\"source\":").append(str(r.source())).append("}");
        }
        s.append("],\"results\":[");
        first = true;
        for (EntryResult r : results) {
            if (!first) {
                s.append(",");
            }
            first = false;
            s.append("{\"class\":").append(str(r.className()))
                    .append(",\"status\":").append(str(r.status()));
            if (r.detail() != null) {
                s.append(",\"detail\":").append(str(r.detail()));
            }
            if (!r.consumed().isEmpty()) {
                s.append(",\"consumed\":[");
                boolean firstConsumed = true;
                for (String id : r.consumed()) {
                    if (!firstConsumed) {
                        s.append(",");
                    }
                    firstConsumed = false;
                    s.append(str(id));
                }
                s.append("]");
            }
            if (r.published() != null) {
                s.append(",\"published\":").append(str(r.published()));
            }
            s.append("}");
        }
        s.append("],\"completed\":").append(results.size() - failed)
                .append(",\"failed\":").append(failed)
                .append(",\"exit\":").append(failed == 0 || allowFailures ? 0 : 1)
                .append("}");
        return s.toString();
    }

    private static String str(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Count *.class entries in the jar; the D4 zero-parseable-classes gate. */
    private static int countClassEntries(Path jar) throws IOException {
        int n = 0;
        try (JarFile jf = new JarFile(jar.toFile())) {
            for (JarEntry e : java.util.Collections.list(jf.entries())) {
                if (e.getName().endsWith(".class")) {
                    n++;
                }
            }
        }
        return n;
    }

    /** How one entrypoint drive came out: one report line, one JSON status. */
    record EntryResult(String className, String source, String status, String detail, boolean completed,
                       List<String> consumed, String published) {

        static EntryResult completed(String className, String source) {
            return new EntryResult(className, source, "completed", null, true, List.of(), null);
        }

        static EntryResult completed(String className, String source, List<String> consumed, String published) {
            return new EntryResult(className, source, "completed", null, true, consumed, published);
        }

        static EntryResult threw(String className, String source, Throwable cause) {
            String text = cause == null ? "unknown" : cause.toString();
            return new EntryResult(className, source, "threw", text, false, List.of(), null);
        }

        static EntryResult missing(String className, String source, MaterializationException.Kind kind) {
            return new EntryResult(className, source, "missing", kind.name(), false, List.of(), null);
        }

        static EntryResult missing(String className, String source, MaterializationException.Kind kind,
                                   List<String> consumed) {
            return new EntryResult(className, source, "missing", kind.name(), false, consumed, null);
        }

        /** A missing line whose detail carries a human reason after the kind, e.g.
         * {@code missing:CONSUME_MISMATCH consume parameter 0 must be an interface, ...}. */
        static EntryResult missing(String className, String source, MaterializationException.Kind kind,
                                   List<String> consumed, String reason) {
            String detail = reason == null ? kind.name() : kind.name() + " " + reason;
            return new EntryResult(className, source, "missing", detail, false, consumed, null);
        }

        /** {@code CLASS completed}, {@code CLASS threw:cause}, or {@code CLASS missing:KIND},
         * extended with {@code consumed:ID,...} and {@code published:ID} when the plan role
         * carried them. */
        String line() {
            StringBuilder sb = new StringBuilder(className).append(' ');
            if (detail == null) {
                sb.append(status);
            } else {
                sb.append(status).append(':').append(detail);
            }
            if (!consumed.isEmpty()) {
                sb.append(" consumed:").append(String.join(",", consumed));
            }
            if (published != null) {
                sb.append(" published:").append(published);
            }
            return sb.toString();
        }
    }
}