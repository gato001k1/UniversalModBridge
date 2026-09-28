package dev.umb.hostagent.content;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reader for the 1.7.10 "key=value" .lang format (used for en_US.lang). */
public final class LangTable {

    private final Map<String, String> map = new HashMap<>();

    public static LangTable empty() {
        return new LangTable();
    }

    public static LangTable load(Path file) {
        LangTable t = new LangTable();
        if (file == null || !Files.isRegularFile(file)) return t;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String raw : lines) {
                String line = raw;
                if (line.isEmpty()) continue;
                if (line.charAt(0) == '﻿') line = line.substring(1);
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                String v = line.substring(eq + 1);
                if (!k.isEmpty()) t.map.put(k, v);
            }
        } catch (IOException | RuntimeException ignored) {
            // a missing or broken lang file must never abort a run
        }
        return t;
    }

    public String get(String key) {
        return key == null ? null : map.get(key);
    }

    public String getOr(String key, String fallback) {
        String v = get(key);
        return v != null ? v : fallback;
    }

    public int size() {
        return map.size();
    }

    /** true when a snapshot displayName is still a raw translation key rather than English text. */
    public static boolean looksLikeRawKey(String s) {
        if (s == null || s.isEmpty()) return true;
        if (s.endsWith(".name")) return true;
        return (s.startsWith("tile.") || s.startsWith("item.")) && !s.contains(" ");
    }

    /**
     * Last-resort readable name for content HBM itself never translated.
     *
     * 103 of the 5041 flattened entries have no {@code <unlocalizedName>.name} row in
     * HBM's own en_US.lang (dev/dummy/invisible blocks such as tile.dummy_port_launch_table).
     * The alternative is shipping the raw key as the display name, which is exactly the bug this
     * milestone exists to remove, so the key is turned into title case instead:
     * {@code tile.dummy_port_launch_table -> "Dummy Port Launch Table"}.
     */
    public static String humanize(String rawKeyOrId) {
        if (rawKeyOrId == null || rawKeyOrId.isEmpty()) return "?";
        String s = rawKeyOrId;
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        if (s.endsWith(".name")) s = s.substring(0, s.length() - 5);
        if (s.startsWith("tile.")) s = s.substring(5);
        else if (s.startsWith("item.")) s = s.substring(5);
        StringBuilder sb = new StringBuilder(s.length());
        boolean boundary = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '.' || c == '-' || c == '/' || c == ' ' || c == '#') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') sb.append(' ');
                boundary = true;
                continue;
            }
            // camelCase is a word boundary too: "concrete_colored.lightBlue" -> "... Light Blue"
            if (!boundary && Character.isUpperCase(c) && i > 0 && Character.isLowerCase(s.charAt(i - 1))) {
                sb.append(' ');
                sb.append(c);
                continue;
            }
            if (boundary) {
                sb.append(Character.toUpperCase(c));
                boundary = false;
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "?" : out;
    }
}
