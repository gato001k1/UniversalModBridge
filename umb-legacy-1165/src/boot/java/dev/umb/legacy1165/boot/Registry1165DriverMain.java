package dev.umb.legacy1165.boot;

import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Headless registry-lifecycle gate: builds the isolated universe, then delegates EVERYTHING to
 * the in-universe {@code dev.umb.legacy1165.legacyside.Legacy1165Lifecycle} (direct calls, no
 * reflection soup) and writes its report to {@code registry-probe.txt} + {@code registrations.json}.
 * Thin by design: this class compiles against the api jar only; all Forge/vanilla contact lives
 * legacyside. See Legacy1165Lifecycle's javadoc for the production-order mapping and every
 * labeled substitution.
 *
 * <p>Mod jars come from {@code -Dumb.1165.modjars} (semicolon-separated, fail loudly when
 * absent); the game dir is {@code research/out/legacy-1165/gamedir}.</p>
 */
public final class Registry1165DriverMain {

    private Registry1165DriverMain() {
    }

    public static void main(String[] args) throws Exception {
        final PrintStream out = System.out;
        File repo = new File(System.getProperty("umb.repo", args.length > 0 ? args[0] : "."))
                .getAbsoluteFile();
        String manifestProp = System.getProperty("umb.1165.classpath");
        File manifest = manifestProp == null || manifestProp.trim().isEmpty()
                ? new File(repo, "umb-legacy-1165/resources/classpath-1165.txt")
                : new File(manifestProp).getAbsoluteFile();
        String outDirProp = System.getProperty("umb.1165.outDir");
        File outDir = outDirProp == null || outDirProp.trim().isEmpty()
                ? new File(repo, "research/out/legacy-1165")
                : new File(outDirProp).getAbsoluteFile();
        Files.createDirectories(outDir.toPath());
        File gameDir = new File(outDir, "gamedir");

        String modJarsProp = System.getProperty("umb.1165.modjars");
        if (modJarsProp == null || modJarsProp.trim().isEmpty()) {
            throw new IllegalStateException(
                    "missing -Dumb.1165.modjars (semicolon-separated mod jar paths)");
        }
        List<File> modJars = new ArrayList<File>();
        for (String part : modJarsProp.split(";")) {
            String t = part.trim();
            if (t.isEmpty()) {
                continue;
            }
            File f = new File(t);
            if (!f.isAbsolute()) {
                f = new File(repo, t);
            }
            if (!f.isFile()) {
                throw new IllegalStateException("mod jar not found: " + f);
            }
            modJars.add(f.getAbsoluteFile());
        }

        List<File> files = Legacy1165Classpath.readManifest(repo, manifest);
        List<URL> urlList = new ArrayList<URL>();
        for (File f : files) urlList.add(f.toURI().toURL());
        for (File modJar : modJars) {
            boolean alreadyPresent = false;
            for (File f : files) {
                if (f.getAbsoluteFile().equals(modJar.getAbsoluteFile())) {
                    alreadyPresent = true;
                    break;
                }
            }
            if (!alreadyPresent) urlList.add(modJar.getAbsoluteFile().toURI().toURL());
        }
        URL[] urls = urlList.toArray(new URL[0]);
        out.println("[registry1165-driver] jars = " + files.size());
        ClassLoader app = Registry1165DriverMain.class.getClassLoader();
        Legacy1165Loader loader = new Legacy1165Loader(urls, app);

        Consumer<String> log = new Consumer<String>() {
            @Override
            public void accept(String msg) {
                out.println(msg);
            }
        };

        Object result;
        try {
            Class<?> lifecycle =
                    Class.forName("dev.umb.legacy1165.legacyside.Legacy1165Lifecycle", true, loader);
            Method run = lifecycle.getMethod("run", ClassLoader.class, List.class, File.class,
                    Consumer.class);
            result = run.invoke(null, loader, modJars, gameDir, log);
        } catch (Exception e) {
            writePartial(outDir, files.size(), e);
            out.println("[registry1165-driver] UNCAUGHT: " + e);
            printCauses(out, e);
            Runtime.getRuntime().halt(1);
            return;
        }

        boolean ok = writeReport(outDir, files.size(), result);
        out.println("[registry1165-driver] " + (ok ? "REGISTRY-OK" : "REGISTRY-PARTIAL"));
        Runtime.getRuntime().halt(ok ? 0 : 1);
    }

    @SuppressWarnings("unchecked")
    private static boolean writeReport(File outDir, int jars, Object result) throws Exception {
        Class<?> resultClass = result.getClass();
        List<Object> stages = (List<Object>) resultClass.getField("stages").get(result);
        Map<String, Map<String, String>> captured =
                (Map<String, Map<String, String>>) resultClass.getField("captured").get(result);
        boolean ok = ((Boolean) resultClass.getMethod("allOk").invoke(result)).booleanValue();

        StringBuilder report = new StringBuilder();
        report.append("jars=").append(jars).append('\n');
        for (Object stage : stages) {
            Class<?> stageClass = stage.getClass();
            String name = (String) stageClass.getField("name").get(stage);
            boolean stageOk = ((Boolean) stageClass.getField("ok").get(stage)).booleanValue();
            long millis = ((Long) stageClass.getField("millis").get(stage)).longValue();
            Object error = stageClass.getField("error").get(stage);
            report.append("stage=").append(name).append(" ok=").append(stageOk)
                    .append(" millis=").append(millis).append('\n');
            if (!stageOk) {
                report.append("  error=").append(error).append('\n');
            }
        }
        report.append(ok ? "REGISTRY-OK\n" : "REGISTRY-PARTIAL\n");
        Files.write(new File(outDir, "registry-probe.txt").toPath(),
                report.toString().getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        Files.write(new File(outDir, "registrations.json").toPath(),
                toJson(captured).getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        System.out.println(report);
        return ok;
    }

    private static void writePartial(File outDir, int jars, Throwable t) {
        try {
            String report = "jars=" + jars + "\ndelegate-failed: " + t + "\nREGISTRY-PARTIAL\n";
            Files.write(new File(outDir, "registry-probe.txt").toPath(),
                    report.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception ignored) {
            // best effort on top of a failure
        }
    }

    private static void printCauses(PrintStream out, Throwable t) {
        Throwable c = t;
        while (c != null) {
            out.println("[registry1165-driver] caused: " + c.getClass().getName() + ": "
                    + c.getMessage());
            StackTraceElement[] st = c.getStackTrace();
            for (int i = 0; i < Math.min(st.length, 40); i++) {
                out.println("[registry1165-driver]   at " + st[i]);
            }
            for (Throwable s : c.getSuppressed()) {
                out.println("[registry1165-driver]   suppressed: " + s.getClass().getName()
                        + ": " + s.getMessage());
                StackTraceElement[] sst = s.getStackTrace();
                for (int i = 0; i < Math.min(sst.length, 15); i++) {
                    out.println("[registry1165-driver]     at " + sst[i]);
                }
            }
            c = c.getCause();
        }
    }

    private static String toJson(Map<String, Map<String, String>> captured) {
        StringBuilder sb = new StringBuilder("{\n");
        boolean firstReg = true;
        for (Map.Entry<String, Map<String, String>> reg : captured.entrySet()) {
            if (!firstReg) {
                sb.append(",\n");
            }
            firstReg = false;
            sb.append("  \"").append(reg.getKey()).append("\": {\n");
            boolean first = true;
            for (Map.Entry<String, String> e : reg.getValue().entrySet()) {
                if (!first) {
                    sb.append(",\n");
                }
                first = false;
                sb.append("    \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"");
            }
            sb.append("\n  }");
        }
        sb.append("\n}\n");
        return sb.toString();
    }
}
