package dev.umb.objbridge;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** Append-only log for the OBJ bridge agent. Never throws; falls back to stderr. */
public final class ObjLog {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static PrintWriter out;
    private static Path file;

    private ObjLog() { }

    public static synchronized void open(Path p) {
        try {
            file = p;
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            OutputStream os = Files.newOutputStream(p, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            out = new PrintWriter(new OutputStreamWriter(os, StandardCharsets.UTF_8), true);
            line("==== UMB-OBJBRIDGE log opened ====");
        } catch (IOException | RuntimeException e) {
            out = null;
            System.err.println("[UMB-OBJBRIDGE] cannot open log " + p + ": " + e);
        }
    }

    public static Path file() { return file; }

    public static synchronized void line(String s) {
        String msg = "[" + TS.format(LocalDateTime.now()) + "] " + s;
        if (out != null) {
            out.println(msg);
            out.flush();
        } else {
            System.err.println("[UMB-OBJBRIDGE] " + s);
        }
    }

    /** Log to both the agent log and stdout (stdout ends up in the game's latest.log). */
    public static synchronized void loud(String s) {
        line(s);
        System.out.println("[UMB-OBJBRIDGE] " + s);
        System.out.flush();
    }

    public static synchronized void error(String what, Throwable t, int frames) {
        StringBuilder sb = new StringBuilder();
        sb.append("ERROR ").append(what).append(" : ").append(t.getClass().getName());
        if (t.getMessage() != null) sb.append(": ").append(t.getMessage());
        StackTraceElement[] st = t.getStackTrace();
        for (int i = 0; i < frames && i < st.length; i++) sb.append(" | at ").append(st[i]);
        Throwable c = t.getCause();
        if (c != null) sb.append(" | caused by ").append(c);
        line(sb.toString());
    }

    public static void error(String what, Throwable t) {
        error(what, t, 4);
    }
}
