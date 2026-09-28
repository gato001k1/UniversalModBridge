package dev.umb.console;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The UMB legacy-bridge console: one local web page over {@code harness\legacy.ps1}.
 *
 * <pre>
 *   java -Xmx128m -cp umb-console.jar;gson.jar dev.umb.console.ConsoleServer [--port 8765] [--repo .]
 * </pre>
 *
 * <p>Binds 127.0.0.1 ONLY, runs one pipeline job at a time, and serves files exclusively out of
 * {@code research/out/legacy} through {@link SafeFiles}. Plain JDK: {@code com.sun.net.httpserver}
 * plus Gson; no framework, no build tool.
 */
public final class ConsoleServer {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final List<String> RUNNABLE_STAGES =
            List.of("analyze", "extract", "rendermap", "pack", "probe", "launch", "status", "all");
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9_.-]{1,40}$");
    private static final Pattern JUNIT_LINE =
            Pattern.compile("(\\d+)\\s+(tests|containers)\\s+(found|successful|failed|skipped|aborted)");

    private final Path repo;
    private final Path outRoot;
    private final Path runsRoot;
    private final List<Path> modDirs;
    private final Jobs jobs;
    private final Slot slot;
    private final Map<String, Path> gates = new LinkedHashMap<>();
    private final String indexHtml;

    ConsoleServer(Path repo, List<Path> modDirs) {
        this.repo = repo.toAbsolutePath().normalize();
        this.outRoot = this.repo.resolve("research/out/legacy");
        this.runsRoot = this.outRoot.resolve("runs");
        this.modDirs = modDirs;
        this.jobs = new Jobs(this.repo);
        this.slot = new Slot(this.repo);
        for (Map.Entry<String, String> e : Map.of(
                "hostagent", "tools/run-hostagent-tests.ps1",
                "rendermap", "tools/run-rendermap-tests.ps1",
                "objbridge", "tools/run-objbridge-tests.ps1",
                "legacy", "tools/run-legacy-tests.ps1",
                "console", "tools/run-console-tests.ps1").entrySet()) {
            Path p = this.repo.resolve(e.getValue());
            if (Files.isRegularFile(p)) gates.put(e.getKey(), p);
        }
        this.indexHtml = loadIndex(this.repo);
    }

    public static void main(String[] args) throws IOException {
        int port = 8765;
        Path repo = Paths.get("").toAbsolutePath().normalize();
        List<Path> extraModDirs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--repo" -> repo = Paths.get(args[++i]).toAbsolutePath().normalize();
                case "--mods" -> extraModDirs.add(Paths.get(args[++i]).toAbsolutePath().normalize());
                default -> {
                    System.err.println("usage: ConsoleServer [--port 8765] [--repo <dir>] [--mods <dir>]...");
                    System.exit(2);
                }
            }
        }
        List<Path> modDirs = new ArrayList<>();
        modDirs.add(repo.resolve("research/mods-hbm"));
        modDirs.add(repo.resolve("legacy-mods"));
        modDirs.addAll(extraModDirs);

        ConsoleServer app = new ConsoleServer(repo, modDirs);
        // 8765 is the documented default, but this box already has a tunnel-client and an
        // "Agent Grid" listening there, so walk forward a few ports rather than dying.
        HttpServer http = null;
        int bound = -1;
        for (int p = port; p < port + 10 && http == null; p++) {
            try {
                http = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), p), 32);
                bound = p;
            } catch (java.net.BindException e) {
                System.out.println("UMB-CONSOLE port " + p + " is in use - trying " + (p + 1));
            }
        }
        if (http == null) {
            System.err.println("UMB-CONSOLE could not bind any port in " + port + ".." + (port + 9));
            System.exit(1);
        }
        port = bound;
        ExecutorService pool = Executors.newFixedThreadPool(8, r -> {
            Thread t = new Thread(r, "umb-console-http");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(pool);
        app.routes(http);
        http.start();
        System.out.println("UMB-CONSOLE listening on http://127.0.0.1:" + port + "/");
        System.out.println("UMB-CONSOLE repo   = " + app.repo);
        System.out.println("UMB-CONSOLE mods   = " + app.modDirs);
        System.out.println("UMB-CONSOLE files  = " + app.outRoot + "  (the ONLY served root)");
        System.out.println("UMB-CONSOLE gates  = " + app.gates.keySet());
        System.out.flush();
    }

    private void routes(HttpServer http) {
        http.createContext("/", guard(this::handleIndex));
        http.createContext("/api/mods", guard(this::handleMods));
        http.createContext("/api/status", guard(this::handleStatus));
        http.createContext("/api/slot", guard(this::handleSlot));
        http.createContext("/api/run", guard(this::handleRun));
        http.createContext("/api/jobs", guard(this::handleJobs));
        http.createContext("/api/shots", guard(this::handleShots));
        http.createContext("/api/evidence", guard(this::handleEvidence));
        http.createContext("/api/gates", guard(this::handleGates));
        http.createContext("/api/docs", guard(this::handleDocs));
        http.createContext("/files/", guard(this::handleFiles));
    }

    /** Nothing a bad request can do may take the server down. */
    private HttpHandler guard(HttpHandler h) {
        return ex -> {
            try {
                h.handle(ex);
            } catch (IllegalArgumentException e) {
                safeError(ex, 400, e.getMessage());
            } catch (IllegalStateException e) {
                safeError(ex, 409, e.getMessage());
            } catch (RuntimeException | IOException e) {
                safeError(ex, 500, e.getClass().getSimpleName() + ": " + e.getMessage());
            } finally {
                ex.close();
            }
        };
    }

    // ------------------------------------------------------------------ pages
    private void handleIndex(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (!path.equals("/") && !path.equals("/index.html")) {
            safeError(ex, 404, "not found");
            return;
        }
        byte[] body = indexHtml.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    // ------------------------------------------------------------------ api
    private void handleMods(HttpExchange ex) throws IOException {
        List<Mods.ModInfo> list = Mods.list(repo, modDirs);
        JsonObject o = new JsonObject();
        o.add("mods", GSON.toJsonTree(list));
        JsonObject dirs = new JsonObject();
        for (Path d : modDirs) dirs.addProperty(d.toString(), Files.isDirectory(d));
        o.add("searched", dirs);
        json(ex, 200, o);
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI());
        String modid = modidFromQuery(q);
        JsonObject o = new JsonObject();
        o.addProperty("modid", modid);
        o.addProperty("runDir", runsRoot.resolve(modid).toString());
        var arr = new com.google.gson.JsonArray();
        for (StageStatus s : StageStatus.readAll(runsRoot, modid)) arr.add(s.toJson(GSON));
        o.add("stages", arr);
        StageStatus st = StageStatus.read(runsRoot.resolve(modid), "status");
        o.add("statusStage", st.toJson(GSON));
        o.add("job", jobs.current() == null ? null : GSON.toJsonTree(jobs.current().summary()));
        json(ex, 200, o);
    }

    private void handleSlot(HttpExchange ex) throws IOException {
        json(ex, 200, GSON.toJsonTree(slot.state()));
    }

    private void handleRun(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            safeError(ex, 405, "POST only");
            return;
        }
        JsonObject body = readJsonBody(ex);
        String stage = optString(body, "stage", "status");
        if (!RUNNABLE_STAGES.contains(stage)) throw new IllegalArgumentException("unknown stage: " + stage);
        String mod = optString(body, "mod", null);
        Path jar = validateModPath(mod);

        List<String> cmd = new ArrayList<>(List.of(
                "powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                "-File", repo.resolve("harness/legacy.ps1").toString(), stage,
                "-Mod", jar.toString()));
        if (body.has("force") && body.get("force").getAsBoolean()) cmd.add("-Force");
        if (body.has("dryRun") && body.get("dryRun").getAsBoolean()) cmd.add("-DryRun");
        if (body.has("stopAfter") && body.get("stopAfter").getAsBoolean()) cmd.add("-StopAfter");
        if (body.has("drive") && body.get("drive").getAsBoolean()) cmd.add("-Drive");
        String name = optString(body, "name", null);
        if (name != null && !name.isBlank()) {
            if (!SAFE_NAME.matcher(name).matches()) throw new IllegalArgumentException("bad run name");
            cmd.add("-Name");
            cmd.add(name);
        }
        Jobs.Job job = jobs.start("legacy.ps1 " + stage, cmd);
        json(ex, 200, GSON.toJsonTree(job.summary()));
    }

    /**
     * {@code /api/jobs}, {@code /api/jobs/<id>}, {@code /api/jobs/<id>/events} (SSE),
     * {@code POST /api/jobs/<id>/stop}.
     */
    private void handleJobs(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String rest = path.length() > "/api/jobs".length() ? path.substring("/api/jobs".length()) : "";
        while (rest.startsWith("/")) rest = rest.substring(1);
        if (rest.isEmpty()) {
            var arr = new com.google.gson.JsonArray();
            for (Jobs.Job j : jobs.recent(20)) arr.add(GSON.toJsonTree(j.summary()));
            JsonObject o = new JsonObject();
            o.add("jobs", arr);
            o.add("current", jobs.current() == null ? null : GSON.toJsonTree(jobs.current().summary()));
            json(ex, 200, o);
            return;
        }
        String[] parts = rest.split("/");
        Jobs.Job job = jobs.get(parts[0]);
        if (job == null) {
            safeError(ex, 404, "no such job");
            return;
        }
        if (parts.length == 1) {
            JsonObject o = (JsonObject) GSON.toJsonTree(job.summary());
            o.add("gate", GSON.toJsonTree(parseJunit(job.linesFrom(0))));
            json(ex, 200, o);
            return;
        }
        if (parts[1].equals("events")) {
            sse(ex, job);
            return;
        }
        if (parts[1].equals("stop")) {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                safeError(ex, 405, "POST only");
                return;
            }
            List<Long> killed = jobs.stop(job);
            JsonObject o = new JsonObject();
            o.add("stopped", GSON.toJsonTree(killed));
            o.add("job", GSON.toJsonTree(job.summary()));
            json(ex, 200, o);
            return;
        }
        safeError(ex, 404, "not found");
    }

    /** Server-sent events: replay the buffer, then tail. Bounded so a thread can never be pinned. */
    private void sse(HttpExchange ex, Jobs.Job job) throws IOException {
        ex.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.getResponseHeaders().add("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        long deadline = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(45);
        int idx = 0;
        try (OutputStream os = ex.getResponseBody()) {
            writeSse(os, "hello", GSON.toJson(job.summary()));
            while (System.currentTimeMillis() < deadline) {
                List<String> batch = job.linesFrom(idx);
                if (!batch.isEmpty()) {
                    for (String line : batch) writeSse(os, "line", line);
                    idx += batch.size();
                } else if (!job.running()) {
                    writeSse(os, "end", GSON.toJson(job.summary()));
                    return;
                } else {
                    os.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                try {
                    Thread.sleep(batch.isEmpty() ? 700 : 60);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        } catch (IOException e) {
            // the browser navigated away or closed the panel; nothing to do
        }
    }

    private static void writeSse(OutputStream os, String event, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event).append('\n');
        for (String l : data.split("\n", -1)) sb.append("data: ").append(l).append('\n');
        sb.append('\n');
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private void handleShots(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI());
        String modid = modidFromQuery(q);
        List<Path> roots = new ArrayList<>();
        roots.add(runsRoot.resolve(modid));
        if (Files.isDirectory(outRoot)) {
            try (var s = Files.list(outRoot)) {
                s.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith("win-"))
                        .forEach(p -> roots.add(p.resolve("shots")));
            } catch (IOException ignored) { /* listed best-effort */ }
        }
        final List<Map<String, Object>> found = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            try (var walk = Files.walk(root, 6)) {
                walk.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                        .filter(p -> p.getParent() != null && p.getParent().getFileName().toString().equals("shots"))
                        .forEach(p -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            String rel = SafeFiles.relativeUrl(outRoot, p);
                            if (rel == null) return;
                            m.put("name", p.getFileName().toString());
                            m.put("group", p.getParent().getParent() == null
                                    ? "?" : p.getParent().getParent().getFileName().toString());
                            m.put("url", "/files/" + rel);
                            try {
                                m.put("sizeBytes", Files.size(p));
                                m.put("mtime", Files.getLastModifiedTime(p).toMillis());
                            } catch (IOException e) {
                                m.put("sizeBytes", -1);
                                m.put("mtime", 0L);
                            }
                            found.add(m);
                        });
            } catch (IOException ignored) { /* skip an unreadable tree */ }
        }
        found.sort(Comparator.comparingLong((Map<String, Object> m) -> (Long) m.getOrDefault("mtime", 0L)).reversed());
        List<Map<String, Object>> shots = found.size() > 400 ? found.subList(0, 400) : found;
        JsonObject o = new JsonObject();
        o.addProperty("modid", modid);
        o.add("shots", GSON.toJsonTree(shots));
        json(ex, 200, o);
    }

    private void handleEvidence(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI());
        String modid = modidFromQuery(q);
        JsonObject o = new JsonObject();
        o.addProperty("modid", modid);

        // newest research/out/legacy/win-*/logs/hostagent.log
        Path newest = null;
        long newestAt = -1;
        if (Files.isDirectory(outRoot)) {
            try (var s = Files.list(outRoot)) {
                for (Path d : s.filter(Files::isDirectory).toList()) {
                    if (!d.getFileName().toString().startsWith("win-")) continue;
                    Path log = d.resolve("logs/hostagent.log");
                    if (!Files.isRegularFile(log)) continue;
                    long t = Files.getLastModifiedTime(log).toMillis();
                    if (t > newestAt) { newestAt = t; newest = log; }
                }
            } catch (IOException ignored) { /* best effort */ }
        }
        if (newest != null) {
            JsonObject a = new JsonObject();
            a.addProperty("path", newest.toString());
            a.addProperty("url", "/files/" + SafeFiles.relativeUrl(outRoot, newest));
            a.addProperty("mtime", newestAt);
            List<String> lines = readLines(newest, 400);
            a.addProperty("lines", lines.size());
            String summary = null;
            int errors = 0;
            for (String l : lines) {
                if (l.contains("UMB-HOSTAGENT blocks")) summary = l.trim();
                if (l.contains("ERROR") || l.contains("FAILED")) errors++;
            }
            a.addProperty("summary", summary);
            a.addProperty("errorLines", errors);
            a.add("tail", GSON.toJsonTree(lines.subList(Math.max(0, lines.size() - 25), lines.size())));
            o.add("agentLog", a);
        } else {
            o.add("agentLog", null);
        }

        o.add("packReport", reportJson(outRoot.resolve("packs/" + modid + "-generated-report.txt"), 40));
        o.add("objReport", reportJson(outRoot.resolve("objbridge/objpackgen-report.md"), 40));
        o.add("probeLog", reportJson(outRoot.resolve("hostagent-probe.log"), 40));
        json(ex, 200, o);
    }

    private JsonElement reportJson(Path p, int headLines) {
        if (!Files.isRegularFile(p)) return com.google.gson.JsonNull.INSTANCE;
        JsonObject o = new JsonObject();
        o.addProperty("path", p.toString());
        String rel = SafeFiles.relativeUrl(outRoot, p);
        o.addProperty("url", rel == null ? null : "/files/" + rel);
        try {
            o.addProperty("mtime", Files.getLastModifiedTime(p).toMillis());
        } catch (IOException e) {
            o.addProperty("mtime", 0);
        }
        o.add("head", GSON.toJsonTree(readLines(p, headLines)));
        return o;
    }

    private void handleGates(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.endsWith("/run")) {
            if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
                safeError(ex, 405, "POST only");
                return;
            }
            JsonObject body = readJsonBody(ex);
            String gate = optString(body, "gate", null);
            Path script = gate == null ? null : gates.get(gate);
            if (script == null) throw new IllegalArgumentException("unknown gate: " + gate);
            List<String> cmd = List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass",
                    "-File", script.toString());
            Jobs.Job job = jobs.start("gate " + gate, cmd);
            json(ex, 200, GSON.toJsonTree(job.summary()));
            return;
        }
        JsonObject o = new JsonObject();
        JsonObject g = new JsonObject();
        for (Map.Entry<String, Path> e : gates.entrySet()) {
            g.addProperty(e.getKey(), repo.relativize(e.getValue()).toString());
        }
        o.add("gates", g);
        json(ex, 200, o);
    }

    private void handleDocs(HttpExchange ex) throws IOException {
        Map<String, String> q = query(ex.getRequestURI());
        String name = q.getOrDefault("name", "LEGACY_BRIDGE.md");
        Map<String, Path> allowed = new LinkedHashMap<>();
        allowed.put("LEGACY_BRIDGE.md", repo.resolve("docs/LEGACY_BRIDGE.md"));
        allowed.put("harness/README.md", repo.resolve("harness/README.md"));
        allowed.put("laneb-progress.md", outRoot.resolve("laneb-progress.md"));
        allowed.put("lanef-progress.md", outRoot.resolve("lanef-progress.md"));
        allowed.put("console/progress.md", outRoot.resolve("console/progress.md"));
        Path p = allowed.get(name);
        JsonObject o = new JsonObject();
        var avail = new com.google.gson.JsonArray();
        for (Map.Entry<String, Path> e : allowed.entrySet()) {
            if (Files.isRegularFile(e.getValue())) avail.add(e.getKey());
        }
        o.add("available", avail);
        o.addProperty("name", name);
        if (p == null || !Files.isRegularFile(p)) {
            o.addProperty("text", "(not available: " + name + ")");
        } else {
            o.addProperty("path", p.toString());
            try {
                o.addProperty("text", Files.readString(p, StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException e) {
                o.addProperty("text", "(unreadable: " + e.getClass().getSimpleName() + ")");
            }
        }
        json(ex, 200, o);
    }

    private void handleFiles(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getRawPath();
        String rel = path.length() > "/files/".length() ? path.substring("/files/".length()) : "";
        Path file = SafeFiles.resolve(outRoot, rel);
        if (file == null) {
            safeError(ex, 404, "not found");
            return;
        }
        byte[] body = Files.readAllBytes(file);
        ex.getResponseHeaders().add("Content-Type", SafeFiles.contentType(file.getFileName().toString()));
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    // ------------------------------------------------------------------ helpers
    static Map<String, Object> parseJunit(List<String> lines) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String l : lines) {
            Matcher mm = JUNIT_LINE.matcher(l);
            while (mm.find()) {
                m.put(mm.group(2) + "-" + mm.group(3), Integer.parseInt(mm.group(1)));
            }
            if (l.contains("PROBE-OK") || l.contains("PROBE-FAIL")) m.put("probe", l.trim());
        }
        return m;
    }

    private String modidFromQuery(Map<String, String> q) {
        String mod = q.get("mod");
        if (mod == null || mod.isBlank()) {
            String modid = q.get("modid");
            if (modid != null && SAFE_NAME.matcher(modid).matches()) return modid;
            return "hbm";
        }
        Path jar = validateModPath(mod);
        return Mods.describe(repo, jar.getParent(), jar).modid;
    }

    /** A mod path is only accepted when it is an existing {@code *.jar} inside the repo. */
    private Path validateModPath(String mod) {
        if (mod == null || mod.isBlank()) throw new IllegalArgumentException("mod is required");
        if (mod.indexOf('\0') >= 0) throw new IllegalArgumentException("bad mod path");
        Path p = Paths.get(mod);
        if (!p.isAbsolute()) p = repo.resolve(mod);
        p = p.toAbsolutePath().normalize();
        if (!p.startsWith(repo)) throw new IllegalArgumentException("mod must live inside the repo");
        if (!p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IllegalArgumentException("mod must be a .jar");
        }
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("mod jar not found");
        return p;
    }

    private static List<String> readLines(Path p, int max) {
        try {
            List<String> all = Files.readAllLines(p, StandardCharsets.UTF_8);
            if (all.size() <= max) return all;
            return new ArrayList<>(all.subList(all.size() - max, all.size()));
        } catch (IOException | RuntimeException e) {
            return List.of("(unreadable: " + e.getClass().getSimpleName() + ")");
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new LinkedHashMap<>();
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty()) return out;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                out.put(SafeFiles.decode(pair), "");
            } else {
                String k = SafeFiles.decode(pair.substring(0, eq));
                String v = SafeFiles.decode(pair.substring(eq + 1));
                if (k != null) out.put(k, v == null ? "" : v);
            }
        }
        return out;
    }

    private static JsonObject readJsonBody(HttpExchange ex) throws IOException {
        byte[] raw;
        try (InputStream in = ex.getRequestBody()) {
            raw = in.readNBytes(64 * 1024);
        }
        if (raw.length == 0) return new JsonObject();
        try {
            JsonElement el = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            return el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("body is not JSON");
        }
    }

    private static String optString(JsonObject o, String k, String dflt) {
        if (o == null || !o.has(k) || o.get(k).isJsonNull() || !o.get(k).isJsonPrimitive()) return dflt;
        return o.get(k).getAsString();
    }

    private static void json(HttpExchange ex, int code, JsonElement body) throws IOException {
        byte[] bytes = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void safeError(HttpExchange ex, int code, String message) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("error", message == null ? "error" : message);
            json(ex, code, o);
        } catch (IOException | RuntimeException ignored) {
            // the client is gone; there is nowhere to report this
        }
    }

    private static String loadIndex(Path repo) {
        // packaged resource first, then the source tree (so the page can be edited without a rebuild)
        try (InputStream in = ConsoleServer.class.getResourceAsStream("/web/index.html")) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) { /* fall through */ }
        Path dev = repo.resolve("umb-console/src/main/resources/web/index.html");
        try {
            if (Files.isRegularFile(dev)) return Files.readString(dev, StandardCharsets.UTF_8);
        } catch (IOException ignored) { /* fall through */ }
        return "<!doctype html><title>umb-console</title><h1>web/index.html is missing</h1>";
    }
}
