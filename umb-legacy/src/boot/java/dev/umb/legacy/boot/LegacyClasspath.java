package dev.umb.legacy.boot;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Assembles the legacy universe's own classpath from the EXACT list the native 1.7.10 client used .
 * <p>Reading that file rather than walking the libraries directory is not cosmetic.
 */
public final class LegacyClasspath {

    private LegacyClasspath() {
    }

    /** Substrings (lowercase) of file names that never belong on the legacy classpath. */
    private static final String[] DROP_ALWAYS = {"launchwrapper-"};

    /**
     * The classpath for a real boot: UMB overrides + SRG vanilla + SRG Forge + the 1.7.10 libraries.
     *
     * <p>The overrides list goes FIRST so a shim can shadow a legacy class by name - that is how
     * net.minecraftforge.common.util.EnumHelper gets replaced with a Java-25-capable version.</p>
     */
    public static List<File> forBoot(File classpathFile, File runtimeJar, File forgeSrgJar,
                                     List<File> overrides, List<File> extra) throws IOException {
        Set<File> out = new LinkedHashSet<File>();
        for (File f : overrides) {
            out.add(require(f, "umb override jar"));
        }
        out.add(require(runtimeJar, "forge-patched SRG runtime jar"));
        out.add(require(forgeSrgJar, "SRG-ified forge jar"));
        for (File f : read(classpathFile)) {
            if (drop(f) || isNotchClientJar(f) || isForgeUniversalJar(f)) {
                continue;
            }
            out.add(f);
        }
        for (File f : extra) {
            out.add(require(f, "extra classpath entry"));
        }
        return new ArrayList<File>(out);
    }

    /** Installer-only production boot: keep the official notch client and Forge universal jar
     * intact and let FML's own deobfuscation transformer translate them at class-load time. */
    public static List<File> forInstallerBoot(File classpathFile, File clientJar, File forgeJar,
                                              List<File> overrides, List<File> extra) throws IOException {
        Set<File> out = new LinkedHashSet<File>();
        for (File f : overrides) out.add(require(f, "umb override jar"));
        out.add(require(clientJar, "official client jar"));
        out.add(require(forgeJar, "Forge universal jar"));
        for (File f : read(classpathFile)) {
            if (drop(f)) continue;
            out.add(f);
        }
        for (File f : extra) out.add(require(f, "extra classpath entry"));
        return new ArrayList<File>(out);
    }

    /**
     * The classpath for the offline SRG-ification: the untouched notch client jar FIRST (it is the
     * descriptor oracle {@code FMLDeobfuscatingRemapper.setup} resolves FD lines against), then the
     * production Forge jar and the libraries.
     */
    public static List<File> forRemap(File classpathFile, File notchJar, File forgeUniversalJar,
                                      List<File> extra) throws IOException {
        Set<File> out = new LinkedHashSet<File>();
        out.add(require(notchJar, "notch client jar"));
        out.add(require(forgeUniversalJar, "forge universal jar"));
        for (File f : read(classpathFile)) {
            if (drop(f)) {
                continue;
            }
            out.add(f);
        }
        for (File f : extra) {
            out.add(require(f, "extra classpath entry"));
        }
        return new ArrayList<File>(out);
    }

    public static URL[] toUrls(List<File> files) throws MalformedURLException {
        URL[] urls = new URL[files.size()];
        for (int i = 0; i < files.size(); i++) {
            urls[i] = files.get(i).toURI().toURL();
        }
        return urls;
    }

    /** Parses the launcher's own {@code -cp} string. Missing entries are reported, not ignored. */
    public static List<File> read(File classpathFile) throws IOException {
        if (classpathFile == null || !classpathFile.isFile()) {
            throw new IOException("no classpath.txt: " + classpathFile);
        }
        String raw = new String(Files.readAllBytes(classpathFile.toPath()), StandardCharsets.UTF_8);
        List<File> out = new ArrayList<File>();
        List<String> missing = new ArrayList<String>();
        String trimmed = raw.trim();
        // The installer materializes this file with the current OS separator. Accept the
        // launcher's historical semicolon/newline form too, so a copied input can never make
        // Linux parse a Windows classpath as one giant path (or vice versa).
        String separator = trimmed.indexOf(';') >= 0 || trimmed.indexOf('\n') >= 0
                || trimmed.indexOf('\r') >= 0 ? ";|\\r?\\n"
                : java.util.regex.Pattern.quote(File.pathSeparator);
        for (String part : trimmed.split(separator)) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            File f = new File(p);
            if (f.isFile()) {
                out.add(f);
            } else {
                missing.add(p);
            }
        }
        if (!missing.isEmpty()) {
            throw new IOException("classpath.txt references " + missing.size()
                    + " missing files, first: " + missing.get(0));
        }
        return out;
    }

    private static boolean drop(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        for (String bad : DROP_ALWAYS) {
            if (n.contains(bad)) {
                return true;
            }
        }
        return false;
    }

    static boolean isNotchClientJar(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.startsWith("1.7.10-forge") && n.endsWith(".jar");
    }

    static boolean isForgeUniversalJar(File f) {
        String n = f.getName().toLowerCase(Locale.ROOT);
        return n.startsWith("forge-1.7.10-") && n.endsWith("-universal.jar");
    }

    private static File require(File f, String what) throws IOException {
        // Headless probes may use an exploded mod root because a few legacy mods resolve their
        // definition files relative to the FML source directory; ordinary production entries stay
        // jars, while this keeps the loader honest about the exact directory it was given.
        if (f == null || (!f.isFile() && !f.isDirectory())) {
            throw new IOException("missing " + what + ": " + f);
        }
        return f;
    }
}
