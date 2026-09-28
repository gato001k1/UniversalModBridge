package dev.umb.console;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

/**
 * The whole of {@code GET /files/...}'s safety story.
 *
 * <p>A request path is only served when, after URL-decoding and normalisation, it is a REAL file
 * that lives inside the one allowed root and carries one of the allowed extensions. Everything
 * else - {@code ..} segments, absolute paths, drive letters, UNC prefixes, NUL bytes, symlinks
 * that leave the root, directories, unknown extensions - returns {@code null}, which the server
 * turns into a 404. Nothing here ever reports WHY, so the endpoint cannot be used to probe the
 * filesystem.
 */
public final class SafeFiles {

    /** The only extensions the console will ever hand out. */
    public static final Set<String> ALLOWED = Set.of(
            "png", "jpg", "jpeg", "gif", "txt", "log", "json", "md", "info", "mcmeta", "csv");

    private SafeFiles() { }

    /** Decodes a URL path segment run; returns {@code null} on malformed input. */
    public static String decode(String raw) {
        if (raw == null) return null;
        try {
            String s = URLDecoder.decode(raw, StandardCharsets.UTF_8);
            if (s.indexOf('\0') >= 0) return null;
            return s;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolves {@code relative} under {@code root}, or {@code null} when the result would not be a
     * readable, allowed-extension regular file inside {@code root}.
     *
     * @param root      the allowed root; must exist
     * @param relative  a '/'-or-'\'-separated relative path, possibly URL-encoded
     */
    public static Path resolve(Path root, String relative) {
        if (root == null || relative == null) return null;
        String rel = decode(relative);
        if (rel == null || rel.isBlank()) return null;
        rel = rel.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);
        if (rel.isEmpty()) return null;
        // Reject anything that even LOOKS rooted before touching the filesystem.
        if (rel.startsWith("//") || rel.contains(":")) return null;
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return null;
        }
        if (!hasAllowedExtension(rel)) return null;

        Path base;
        Path candidate;
        try {
            base = root.toAbsolutePath().normalize();
            candidate = base.resolve(rel).normalize();
        } catch (InvalidPathException e) {
            return null;
        }
        if (!candidate.startsWith(base)) return null;
        if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) return null;
        // A symlink/junction inside the root must still resolve inside the root.
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(base.toRealPath())) return null;
        } catch (IOException e) {
            return null;
        }
        return candidate;
    }

    public static boolean hasAllowedExtension(String name) {
        if (name == null) return false;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return false;
        String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return ALLOWED.contains(ext);
    }

    public static String contentType(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".json") || n.endsWith(".mcmeta") || n.endsWith(".info")) return "application/json; charset=utf-8";
        if (n.endsWith(".csv")) return "text/csv; charset=utf-8";
        return "text/plain; charset=utf-8";
    }

    /** {@code root}-relative, forward-slashed form of {@code p}, for building /files URLs. */
    public static String relativeUrl(Path root, Path p) {
        Path base = root.toAbsolutePath().normalize();
        Path abs = p.toAbsolutePath().normalize();
        if (!abs.startsWith(base)) return null;
        return base.relativize(abs).toString().replace('\\', '/');
    }
}
