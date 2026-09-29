package dev.umb.legacy1165.boot;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the 1.16.5 legacy classpath from an explicit manifest file, the same discipline the
 * 1.7.10 and 1.12.2 modules use for their own manifests (a sorted recursive scan silently picked
 * analogous trap: the real vanilla library set ships log4j-api/log4j-core 2.8.1, but the real
 * Forge 36.2.34 version.json pins 2.15.0 - see
 * 2.8.1 pair is deliberately absent from {@code classpath-1165.txt}).
 *
 * <p>Every path in the manifest is relative to the repo root and is verified to exist before this
 * class returns anything - a silently-missing jar is exactly the kind of bug that only shows up as
 * a much later, confusing {@code ClassNotFoundException} deep inside FML.</p>
 */
public final class Legacy1165Classpath {

    private Legacy1165Classpath() {
    }

    /** Reads a newline-separated, repo-root-relative jar list; blank lines and #-comments skipped. */
    public static List<File> readManifest(File repo, File manifest) throws IOException {
        if (!manifest.isFile()) {
            throw new IOException("classpath manifest not found: " + manifest);
        }
        List<File> out = new ArrayList<File>();
        for (String line : Files.readAllLines(manifest.toPath(), StandardCharsets.UTF_8)) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#")) {
                continue;
            }
            // Staged mod/resource inputs may intentionally live on a separate volume (the
            // 1.16.5 wave uses F: for scratch and large assets).  File(parent, absoluteChild)
            // is not reliable across the supported Windows/JDK combinations, so resolve the
            // absolute form explicitly before falling back to the repo-root-relative contract.
            File f = new File(t);
            if (!f.isAbsolute()) {
                f = new File(repo, t);
            }
            f = f.getAbsoluteFile();
            if (!f.isFile()) {
                throw new IOException("classpath-1165.txt references a missing file: " + f
                        + " (run the fetch step in umb-legacy-1165/README.md first)");
            }
            out.add(f);
        }
        if (out.isEmpty()) {
            throw new IOException("classpath manifest is empty: " + manifest);
        }
        return out;
    }

    public static URL[] toUrls(List<File> files) {
        URL[] urls = new URL[files.size()];
        for (int i = 0; i < files.size(); i++) {
            try {
                urls[i] = files.get(i).toURI().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException(e);
            }
        }
        return urls;
    }

    /** The Forge-binpatched CLIENT overlay, access-transformed (run-rename-client + run-apply-at). */
    public static final String CLIENT_PATCHED_JAR = "mc-client-srg-patched-at.jar";
    static final String CLIENT_FULL_JAR = "mc-client-srg-at.jar";
    static final String SERVER_JAR_PREFIX = "mc-server-srg";

    /**
     * Orders the era classpath for a Forge dist. The manifest composes the game like a DEDICATED
     * SERVER install: the server binpatch overlay over the full server jar, with the client jar
     * behind them only for client-only classes. For CLIENT that is wrong: every class present in
     * both (Entity, MathHelper, ...) loads from the SERVER jars, where Mojang stripped the
     * {@code @OnlyIn(Dist.CLIENT)} members, so client renderers die with NoSuchMethodError
     * (live: MathHelper.func_219805_h from a mob renderer). A real client install is the CLIENT
     * binpatch overlay over the full client jar, so for CLIENT those two go first, ahead of the
     * server jars (which then only serve server-only classes). Without the client overlay next to
     * the client jar the order is left as is: the unpatched client jar alone would drop Forge's
     * patches. Any other dist returns {@code files} unchanged.
     */
    public static List<File> forDist(List<File> files, String dist) {
        if (!"CLIENT".equalsIgnoreCase(dist == null ? "" : dist.trim())) return files;
        File clientFull = null;
        int firstServer = -1;
        for (int i = 0; i < files.size(); i++) {
            String name = files.get(i).getName();
            if (name.equals(CLIENT_FULL_JAR)) clientFull = files.get(i);
            if (firstServer < 0 && name.startsWith(SERVER_JAR_PREFIX)) firstServer = i;
        }
        if (clientFull == null || firstServer < 0) return files;
        File clientPatched = new File(clientFull.getParentFile(), CLIENT_PATCHED_JAR);
        if (!clientPatched.isFile()) return files;
        List<File> out = new ArrayList<File>(files.size() + 1);
        for (int i = 0; i < files.size(); i++) {
            File f = files.get(i);
            if (i == firstServer) {
                out.add(clientPatched.getAbsoluteFile());
                out.add(clientFull);
            }
            if (f.equals(clientFull) || f.getName().equals(CLIENT_PATCHED_JAR)) continue;
            out.add(f);
        }
        return out;
    }

    /** True if any manifest entry's file name matches (case-insensitive substring), for smoke tests. */
    public static boolean hasJarNamed(List<File> files, String substringLowercase) {
        for (File f : files) {
            if (f.getName().toLowerCase(java.util.Locale.ROOT).contains(substringLowercase)) {
                return true;
            }
        }
        return false;
    }
}
