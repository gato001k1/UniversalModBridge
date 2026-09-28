package dev.umb.console;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code research/out/legacy/runs/&lt;modid&gt;/&lt;stage&gt;.json} as written by
 * {@code harness\legacy.ps1}, parsed tolerantly: a missing, truncated or half-written file
 * becomes a status row that says so rather than an exception. Nothing here throws for bad input,
 * because the console polls these files while legacy.ps1 is busy rewriting them.
 */
public final class StageStatus {

    /** The stage order the pipeline runs in - also the display order. */
    public static final List<String> ORDER =
            List.of("analyze", "extract", "rendermap", "pack", "probe", "launch");

    public final String stage;
    public final String status;
    public final String startedAt;
    public final String endedAt;
    public final long durationMs;
    public final String command;
    public final List<String> outputs;
    public final Map<String, String> counts;
    public final String error;

    private StageStatus(String stage, String status, String startedAt, String endedAt, long durationMs,
                        String command, List<String> outputs, Map<String, String> counts, String error) {
        this.stage = stage;
        this.status = status;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.durationMs = durationMs;
        this.command = command;
        this.outputs = outputs;
        this.counts = counts;
        this.error = error;
    }

    /** A row for a stage that has never run. */
    public static StageStatus never(String stage) {
        return new StageStatus(stage, "never", null, null, 0L, null,
                new ArrayList<>(), new LinkedHashMap<>(), null);
    }

    /** A row for a stage whose JSON exists but could not be understood. */
    public static StageStatus unreadable(String stage, String why) {
        return new StageStatus(stage, "unreadable", null, null, 0L, null,
                new ArrayList<>(), new LinkedHashMap<>(), why);
    }

    /** Parses one stage JSON document. Never throws; returns an {@code unreadable} row instead. */
    public static StageStatus parse(String stageName, String json) {
        if (json == null || json.isBlank()) return unreadable(stageName, "empty file");
        String text = json;
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);   // UTF-8 BOM
        JsonObject o;
        try {
            JsonElement el = JsonParser.parseString(text);
            if (!el.isJsonObject()) return unreadable(stageName, "not a JSON object");
            o = el.getAsJsonObject();
        } catch (RuntimeException e) {
            return unreadable(stageName, "malformed JSON: " + e.getClass().getSimpleName());
        }
        String stage = str(o, "stage", stageName);
        String status = str(o, "status", "unknown");
        long ms = 0L;
        try {
            if (o.has("durationMs") && o.get("durationMs").isJsonPrimitive()) {
                ms = o.get("durationMs").getAsLong();
            }
        } catch (RuntimeException ignored) { /* leave 0 */ }

        List<String> outs = new ArrayList<>();
        if (o.has("outputs") && o.get("outputs").isJsonArray()) {
            for (JsonElement e : o.getAsJsonArray("outputs")) {
                if (e != null && e.isJsonPrimitive()) outs.add(e.getAsString());
            }
        }
        Map<String, String> counts = new LinkedHashMap<>();
        if (o.has("counts") && o.get("counts").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : o.getAsJsonObject("counts").entrySet()) {
                JsonElement v = e.getValue();
                if (v == null || v.isJsonNull()) continue;
                if (v.isJsonPrimitive()) {
                    JsonPrimitive p = v.getAsJsonPrimitive();
                    counts.put(e.getKey(), p.isBoolean() ? String.valueOf(p.getAsBoolean()) : p.getAsString());
                } else {
                    counts.put(e.getKey(), v.toString());
                }
            }
        }
        return new StageStatus(stage, status, str(o, "startedAt", null), str(o, "endedAt", null), ms,
                str(o, "command", null), outs, counts, str(o, "error", null));
    }

    /** Reads {@code <runDir>/<stage>.json}, or a {@code never}/{@code unreadable} row. */
    public static StageStatus read(Path runDir, String stage) {
        Path f = runDir.resolve(stage + ".json");
        if (!Files.isRegularFile(f)) return never(stage);
        try {
            return parse(stage, Files.readString(f, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return unreadable(stage, "unreadable: " + e.getClass().getSimpleName());
        } catch (RuntimeException e) {
            return unreadable(stage, "bad encoding: " + e.getClass().getSimpleName());
        }
    }

    /** Every pipeline stage for one modid, in pipeline order. */
    public static List<StageStatus> readAll(Path runsRoot, String modid) {
        Path runDir = runsRoot.resolve(modid);
        List<StageStatus> out = new ArrayList<>();
        for (String s : ORDER) out.add(read(runDir, s));
        return out;
    }

    public JsonObject toJson(Gson gson) {
        JsonObject o = new JsonObject();
        o.addProperty("stage", stage);
        o.addProperty("status", status);
        o.addProperty("startedAt", startedAt);
        o.addProperty("endedAt", endedAt);
        o.addProperty("durationMs", durationMs);
        o.addProperty("command", command);
        o.addProperty("error", error);
        o.add("outputs", gson.toJsonTree(outputs));
        o.add("counts", gson.toJsonTree(counts));
        return o;
    }

    private static String str(JsonObject o, String key, String dflt) {
        if (o == null || !o.has(key)) return dflt;
        JsonElement e = o.get(key);
        if (e == null || e.isJsonNull()) return dflt;
        if (e.isJsonPrimitive()) return e.getAsString();
        return e.toString();
    }
}
