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
 * the WRONG duplicate guava jar once in the 1.7.10 lane; 1.16.5's own library set has its own
 * analogous trap: the real vanilla library set ships log4j-api/log4j-core 2.8.1, but the real
 * Forge 36.2.34 version.json pins 2.15.0 - see
 * research/out/legacy-1165/ERA-1165-PLAN.md "log4j version choice" for why 2.15.0 wins and the
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
