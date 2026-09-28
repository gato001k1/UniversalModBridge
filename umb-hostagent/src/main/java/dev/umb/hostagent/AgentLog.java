package dev.umb.hostagent;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Append-only agent log. Never throws; falls back to stderr. */
public final class AgentLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static PrintWriter out;
    private static Path file;
    /** Keys already emitted by {@link #errorOnce}. */
    private static final Set<String> LOGGED_ONCE = ConcurrentHashMap.newKeySet();

    private AgentLog() {
    }

    public static synchronized void open(Path p) {
        try {
            file = p;
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            OutputStream os = Files.newOutputStream(p, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            out = new PrintWriter(new java.io.OutputStreamWriter(os, StandardCharsets.UTF_8), true);
            line("==== UMB-HOSTAGENT log opened ====");
        } catch (IOException | RuntimeException e) {
            out = null;
            System.err.println("[UMB-HOSTAGENT] cannot open log " + p + ": " + e);
        }
    }

    public static Path file() {
        return file;
    }

    public static synchronized void line(String s) {
        String msg = "[" + TS.format(LocalDateTime.now()) + "] " + s;
        if (out != null) {
            out.println(msg);
            out.flush();
        } else {
            System.err.println("[UMB-HOSTAGENT] " + s);
        }
    }

    /** Log to both the agent log and stdout. */
    public static synchronized void loud(String s) {
        line(s);
        System.out.println("[UMB-HOSTAGENT] " + s);
        System.out.flush();
    }

    /** Log a throwable with at most {@code frames} stack frames. */
    public static synchronized void error(String what, Throwable t, int frames) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR ").append(what).append(" : ").append(t.getClass().getName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < frames && i < st.length; i++) {
            sb.append(" | at ").append(st[i]);
        }
        Throwable c = t.getCause();
        if (c != null) sb.append(" | caused by ").append(c);
        line(sb.toString());
    }

    public static void error(String what, Throwable t) {
        error(what, t, 3);
    }

    /**
     * Logs the first occurrence of {@code key}. Facade gaps can repeat every tick, so subsequent
     * occurrences are suppressed to keep the log useful.
     */
    public static void errorOnce(String key, Throwable t, int frames) {
        if (LOGGED_ONCE.add(key)) {
            error(key, t, frames);
        }
    }

    /** Test-only: forget everything {@link #errorOnce} has already logged. */
    public static void resetErrorOnceForTests() {
        LOGGED_ONCE.clear();
    }
}
