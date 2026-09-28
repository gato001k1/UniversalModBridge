package dev.umb.console;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Discovery of candidate mod jars, plus the cheap part of {@code analyze}: the {@code modid} and
 * display name out of {@code mcmod.info}. Reading only that one zip entry keeps the Mods panel
 * instant even for a 54 MB jar, and it is exactly the same field {@code harness\legacy.ps1} uses
 * to pick the run directory, so the console and the pipeline can never disagree about a modid.
 */
public final class Mods {

    /** One discovered jar. */
    public static final class ModInfo {
        public String path;          // absolute
        public String repoPath;      // repo-relative, the form legacy.ps1 is called with
        public String file;
        public long sizeBytes;
        public String modid;
        public String name;
        public String version;
        public String mcversion;
        public String dir;           // which search dir it came from
        public String error;         // why modid is missing, when it is
    }

    private Mods() { }

    /** Lists {@code *.jar} in each existing directory, newest-name-first inside each dir. */
    public static List<ModInfo> list(Path repo, List<Path> dirs) {
        List<ModInfo> out = new ArrayList<>();
        for (Path dir : dirs) {
            if (dir == null || !Files.isDirectory(dir)) continue;
            List<Path> jars = new ArrayList<>();
            try (var s = Files.list(dir)) {
                s.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                        .forEach(jars::add);
            } catch (IOException e) {
                continue;
            }
            jars.sort(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)));
            for (Path jar : jars) out.add(describe(repo, dir, jar));
        }
        return out;
    }

    static ModInfo describe(Path repo, Path dir, Path jar) {
        ModInfo m = new ModInfo();
        m.path = jar.toAbsolutePath().normalize().toString();
        m.file = jar.getFileName().toString();
        m.dir = dir.toAbsolutePath().normalize().toString();
        try {
            m.sizeBytes = Files.size(jar);
        } catch (IOException e) {
            m.sizeBytes = -1L;
        }
        if (repo != null) {
            Path base = repo.toAbsolutePath().normalize();
            Path abs = jar.toAbsolutePath().normalize();
            m.repoPath = abs.startsWith(base) ? base.relativize(abs).toString() : m.path;
        } else {
            m.repoPath = m.path;
        }
        readMcmodInfo(jar, m);
        if (m.modid == null || m.modid.isBlank()) {
            String base = m.file;
            int dot = base.lastIndexOf('.');
            if (dot > 0) base = base.substring(0, dot);
            m.modid = base.replaceAll("[^A-Za-z0-9_.-]", "_").toLowerCase(Locale.ROOT);
            if (m.error == null) m.error = "no modid in mcmod.info; falling back to the file name";
        }
        return m;
    }

    /** Fills modid/name/version/mcversion from {@code mcmod.info}. Never throws. */
    static void readMcmodInfo(Path jar, ModInfo m) {
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            ZipEntry e = zf.getEntry("mcmod.info");
            if (e == null) {
                m.error = "no mcmod.info in the jar (not a legacy Forge mod?)";
                return;
            }
            String text;
            try (InputStream in = zf.getInputStream(e)) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject first = firstModEntry(text);
            if (first == null) {
                m.error = "mcmod.info is not a recognised shape";
                return;
            }
            m.modid = str(first, "modid");
            m.name = str(first, "name");
            m.version = str(first, "version");
            m.mcversion = str(first, "mcversion");
        } catch (IOException | RuntimeException ex) {
            m.error = "cannot read mcmod.info: " + ex.getClass().getSimpleName();
        }
    }

    /** {@code [ {...} ]} and {@code { "modList":[ {...} ] }} are both legal 1.7.10 shapes. */
    static JsonObject firstModEntry(String text) {
        if (text == null || text.isBlank()) return null;
        String t = text;
        if (t.charAt(0) == '﻿') t = t.substring(1);
        JsonElement el;
        try {
            el = JsonParser.parseString(t);
        } catch (RuntimeException e) {
            return null;
        }
        if (el.isJsonArray()) {
            JsonArray a = el.getAsJsonArray();
            if (a.isEmpty() || !a.get(0).isJsonObject()) return null;
            return a.get(0).getAsJsonObject();
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("modList") && o.get("modList").isJsonArray()) {
                JsonArray a = o.getAsJsonArray("modList");
                if (a.isEmpty() || !a.get(0).isJsonObject()) return null;
                return a.get(0).getAsJsonObject();
            }
            if (o.has("modid")) return o;
        }
        return null;
    }

    private static String str(JsonObject o, String k) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull() || !o.get(k).isJsonPrimitive()) return null;
        String v = o.get(k).getAsString().trim();
        return v.isEmpty() ? null : v;
    }
}
