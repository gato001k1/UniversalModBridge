package dev.umb.console;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * ONE running job at a time, ever.
 *
 * <p>Every stage of this pipeline either takes the single Minecraft window slot or saturates the
 * box (PackGen writes ~16k files, RenderMap wants 3 GB of heap), so the console refuses a second
 * concurrent job instead of queueing it - a queue would silently serialise two window-taking
 * stages behind each other while the user watched an idle log.
 *
 * <p>{@link #stop} kills the process we spawned and only its descendants, so a game window some
 * OTHER lane launched can never be caught in it.
 */
public final class Jobs {

    /** Lines kept per job. Old lines are dropped from the front, and the drop is announced. */
    private static final int MAX_LINES = 4000;

    public static final class Job {
        public final String id;
        public final String label;
        public final List<String> command;
        public final Instant startedAt = Instant.now();
        volatile Instant endedAt;
        volatile Integer exitCode;
        volatile boolean stopping;
        volatile Process process;
        final List<String> lines = new ArrayList<>();
        volatile int dropped;

        Job(String id, String label, List<String> command) {
            this.id = id;
            this.label = label;
            this.command = List.copyOf(command);
        }

        public boolean running() { return endedAt == null; }

        /** A snapshot of lines from {@code from} (0-based, counting dropped lines). */
        public synchronized List<String> linesFrom(int from) {
            int start = Math.max(0, from - dropped);
            if (start >= lines.size()) return List.of();
            return new ArrayList<>(lines.subList(start, lines.size()));
        }

        public synchronized int lineCount() { return dropped + lines.size(); }

        synchronized void add(String line) {
            lines.add(line);
            if (lines.size() > MAX_LINES) {
                int cut = lines.size() - MAX_LINES;
                lines.subList(0, cut).clear();
                dropped += cut;
            }
        }

        public Map<String, Object> summary() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", id);
            m.put("label", label);
            m.put("command", String.join(" ", command));
            m.put("startedAt", startedAt.toString());
            m.put("endedAt", endedAt == null ? null : endedAt.toString());
            m.put("running", running());
            m.put("exitCode", exitCode);
            m.put("stopping", stopping);
            m.put("lines", lineCount());
            return m;
        }
    }

    private final Path workDir;
    private final AtomicInteger seq = new AtomicInteger();
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private volatile Job current;

    public Jobs(Path workDir) {
        this.workDir = workDir;
    }

    public synchronized Job current() { return current; }

    public synchronized Job get(String id) { return jobs.get(id); }

    public synchronized List<Job> recent(int n) {
        List<Job> all = new ArrayList<>(jobs.values());
        java.util.Collections.reverse(all);
        return all.subList(0, Math.min(n, all.size()));
    }

    /** @throws IllegalStateException when a job is already running */
    public synchronized Job start(String label, List<String> command) {
        if (current != null && current.running()) {
            throw new IllegalStateException("job " + current.id + " (" + current.label + ") is still running");
        }
        String id = "j" + System.currentTimeMillis() + "-" + seq.incrementAndGet();
        Job job = new Job(id, label, command);
        jobs.put(id, job);
        if (jobs.size() > 50) {
            var it = jobs.keySet().iterator();
            it.next();
            it.remove();
        }
        current = job;
        job.add("$ " + String.join(" ", command));
        Thread t = new Thread(() -> run(job), "umb-job-" + id);
        t.setDaemon(true);
        t.start();
        return job;
    }

    private void run(Job job) {
        try {
            ProcessBuilder pb = new ProcessBuilder(job.command);
            pb.directory(workDir.toFile());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            job.process = p;
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) job.add(line);
            }
            job.exitCode = p.waitFor();
        } catch (IOException e) {
            job.add("[console] cannot start the process: " + e.getMessage());
            job.exitCode = -1;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            job.add("[console] interrupted");
            job.exitCode = -1;
        } catch (RuntimeException e) {
            job.add("[console] " + e.getClass().getSimpleName() + ": " + e.getMessage());
            job.exitCode = -1;
        } finally {
            job.endedAt = Instant.now();
            job.add("[console] exit=" + job.exitCode);
        }
    }

    /** Kills the spawned process and its descendants only. Returns the pids we asked to stop. */
    public List<Long> stop(Job job) {
        List<Long> killed = new ArrayList<>();
        Process p = job.process;
        if (p == null || !p.isAlive()) return killed;
        job.stopping = true;
        job.add("[console] stop requested - destroying the process tree WE started");
        ProcessHandle h = p.toHandle();
        List<ProcessHandle> kids = h.descendants().toList();
        for (ProcessHandle k : kids) {
            killed.add(k.pid());
            k.destroy();
        }
        killed.add(h.pid());
        h.destroy();
        try {
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                for (ProcessHandle k : kids) k.destroyForcibly();
                h.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return killed;
    }
}
