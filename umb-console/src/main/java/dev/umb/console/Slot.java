package dev.umb.console;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads - never takes - the single-Minecraft-window slot, using exactly the rule
 * {@code harness\legacy.ps1} enforces: a live {@code java.exe} whose command line names
 * {@code legacy\win-}, {@code objbridge} or {@code mc1710-native}, or a
 * {@code research\out\legacy\.window-slot.lock} whose pid is still alive.
 *
 * <p>Command lines come from one WMI query per refresh, spawned through PowerShell and cached for
 * a few seconds - the panel polls, and a CIM query costs ~half a second.
 */
public final class Slot {

    private static final long CACHE_MS = 4000;
    private static final Pattern WIN_RUN = Pattern.compile("legacy\\\\win-([A-Za-z0-9_.-]+)");

    /** Build/pack/probe tools mention a keyword but never open a window. */
    private static final String[] FALSE_POSITIVES = {
            "dev.umb.packgen.PackGen", "dev.umb.objbridge.gen.ObjPackGen",
            "dev.umb.objbridge.probe.", "dev.umb.hostagent.probe.", "dev.umb.rendermap.",
            "dev.umb.console.", "junit-platform-console-standalone"
    };

    public static final class Holder {
        public long pid;
        public String run;
        public String why;
    }

    private final Path repo;
    private final Path lockFile;
    private volatile long cachedAt;
    private volatile List<Holder> cached = List.of();

    public Slot(Path repo) {
        this.repo = repo;
        this.lockFile = repo.resolve("research/out/legacy/.window-slot.lock");
    }

    public Map<String, Object> state() {
        List<Holder> holders = holders();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("busy", !holders.isEmpty());
        m.put("holders", holders);
        m.put("lockFile", lockFile.toString());
        m.put("checkedAt", java.time.Instant.now().toString());
        return m;
    }

    public synchronized List<Holder> holders() {
        long now = System.currentTimeMillis();
        if (now - cachedAt < CACHE_MS) return cached;
        List<Holder> out = new ArrayList<>();
        for (Map.Entry<Long, String> e : javaProcesses().entrySet()) {
            String cl = e.getValue();
            String low = cl.toLowerCase(Locale.ROOT);
            boolean skip = false;
            for (String fp : FALSE_POSITIVES) {
                if (low.contains(fp.toLowerCase(Locale.ROOT))) { skip = true; break; }
            }
            if (skip) continue;
            String matched = null;
            for (String pat : new String[]{"legacy\\win-", "objbridge", "mc1710-native"}) {
                if (low.contains(pat.toLowerCase(Locale.ROOT))) { matched = pat; break; }
            }
            if (matched == null) continue;
            Holder h = new Holder();
            h.pid = e.getKey();
            h.why = "java.exe cmdline matches \"" + matched + "\"";
            Matcher m = WIN_RUN.matcher(cl);
            if (m.find()) h.run = "win-" + m.group(1);
            else if (low.contains("mc1710-native")) h.run = "mc1710-native";
            else h.run = "objbridge";
            out.add(h);
        }
        Holder lock = fromLockFile(out);
        if (lock != null) out.add(lock);
        cached = out;
        cachedAt = now;
        return out;
    }

    private Holder fromLockFile(List<Holder> already) {
        if (!Files.isRegularFile(lockFile)) return null;
        try {
            String text = Files.readString(lockFile, StandardCharsets.UTF_8);
            if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) return null;
            JsonObject o = el.getAsJsonObject();
            long pid = o.has("pid") ? o.get("pid").getAsLong() : 0L;
            if (pid <= 0) return null;
            if (ProcessHandle.of(pid).filter(ProcessHandle::isAlive).isEmpty()) return null;
            for (Holder h : already) if (h.pid == pid) return null;
            Holder h = new Holder();
            h.pid = pid;
            h.run = o.has("run") && !o.get("run").isJsonNull() ? o.get("run").getAsString() : "?";
            String stage = o.has("stage") && !o.get("stage").isJsonNull() ? o.get("stage").getAsString() : "?";
            String since = o.has("startedAt") && !o.get("startedAt").isJsonNull() ? o.get("startedAt").getAsString() : "?";
            h.why = "lock file, stage=" + stage + " since " + since;
            return h;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** pid -&gt; command line, for every live java.exe. Empty map when the query fails. */
    private Map<Long, String> javaProcesses() {
        Map<Long, String> out = new LinkedHashMap<>();
        List<String> cmd = List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command",
                "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
                        + "ForEach-Object { $_.ProcessId.ToString() + '|' + ($_.CommandLine -replace \"`r|`n\", ' ') }");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(repo.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    int bar = line.indexOf('|');
                    if (bar <= 0) continue;
                    try {
                        out.put(Long.parseLong(line.substring(0, bar).trim()), line.substring(bar + 1));
                    } catch (NumberFormatException ignored) { /* not a data line */ }
                }
            }
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
        } catch (IOException e) {
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out;
    }
}
