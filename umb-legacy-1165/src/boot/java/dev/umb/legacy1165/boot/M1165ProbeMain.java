package dev.umb.legacy1165.boot;

import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Headless M1-style vertical gate: builds the isolated universe and runs
 * {@code dev.umb.legacy1165.legacyside.M1165Probe} inside it (boot -> place iron chest ->
 * activate -> 54 slots -> put/take diamonds -> tick -> NBT round-trip). Writes
 * {@code research/out/legacy-1165/m1165-probe.txt} ({@code M1165-OK} / {@code M1165-FAIL}).
 * Mod jars from {@code -Dumb.1165.modjars} (forge universal first, then the content mod).
 *   powershell -NoProfile -ExecutionPolicy Bypass -File umb-legacy-1165\run-m1165.ps1
 */
public final class M1165ProbeMain {

    private M1165ProbeMain() {
    }

    public static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        File repo = new File(System.getProperty("umb.repo", args.length > 0 ? args[0] : "."))
                .getAbsoluteFile();
        File manifest = new File(repo, "umb-legacy-1165/resources/classpath-1165.txt");
        File outDir = new File(repo, "research/out/legacy-1165");
        Files.createDirectories(outDir.toPath());

        String modJarsProp = System.getProperty("umb.1165.modjars");
        if (modJarsProp == null || modJarsProp.trim().isEmpty()) {
            String forge = new File(repo,
                    "research/out/legacy-1165/forge-1.16.5-36.2.34-universal.jar")
                    .getAbsolutePath();
            String mod = new File(repo,
                    "research/out/legacy-1165/ironchest-1.16.5-11.2.21.jar").getAbsolutePath();
            modJarsProp = forge + ";" + mod;
            out.println("[m1165-probe] umb.1165.modjars unset, defaulting to forge+ironchest");
        }
        System.setProperty("umb.1165.modjars", modJarsProp);

        List<File> files = Legacy1165Classpath.readManifest(repo, manifest);
        URL[] urls = Legacy1165Classpath.toUrls(files);
        out.println("[m1165-probe] jars = " + files.size());
        Legacy1165Loader loader =
                new Legacy1165Loader(urls, M1165ProbeMain.class.getClassLoader());

        String result;
        boolean ok;
        try {
            Class<?> probe =
                    Class.forName("dev.umb.legacy1165.legacyside.M1165Probe", true, loader);
            Method run = probe.getMethod("run");
            result = String.valueOf(run.invoke(null));
            ok = result.startsWith("M1165-OK");
        } catch (Exception e) {
            StringBuilder sb = new StringBuilder();
            sb.append("M1165-FAIL: ").append(e).append('\n');
            Throwable c = e.getCause() == null ? e : e.getCause();
            while (c != null) {
                sb.append("caused: ").append(c.getClass().getName()).append(": ")
                        .append(c.getMessage()).append('\n');
                StackTraceElement[] st = c.getStackTrace();
                for (int i = 0; i < Math.min(st.length, 15); i++) {
                    String line = st[i].toString();
                    if (line.contains("dev.umb") || line.contains("com.progwml6")
                            || line.contains("net.minecraftforge") || line.contains("net.minecraft")) {
                        sb.append("  at ").append(line).append('\n');
                    }
                }
                c = c.getCause();
            }
            result = sb.toString();
            ok = false;
        }
        Files.write(new File(outDir, "m1165-probe.txt").toPath(),
                result.getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        out.println(result);
        Runtime.getRuntime().halt(ok ? 0 : 1);
    }
}
