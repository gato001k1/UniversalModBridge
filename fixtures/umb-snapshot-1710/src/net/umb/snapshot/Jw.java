// UMB Snapshot — minimal deterministic pretty-printing JSON writer.
// SPDX-License-Identifier: CC0-1.0
package net.umb.snapshot;

import java.io.IOException;
import java.io.Writer;

/**
 * Tiny streaming JSON writer. Hand-rolled on purpose: the 1.7.10 runtime does
 * ship Gson 2.2.4, but a hand-rolled writer keeps element ordering exactly as
 * emitted (no reflective field ordering) and streams straight to disk so a
 * multi-megabyte snapshot never has to live in the 1 GB heap as one String.
 */
public final class Jw {
    private final Writer w;
    private final boolean[] empty = new boolean[64];
    private int depth;
    private boolean pendingName;

    public Jw(Writer w) {
        this.w = w;
        this.empty[0] = true;
    }

    private void indent(int d) throws IOException {
        for (int i = 0; i < d; i++) {
            w.write("  ");
        }
    }

    private void sep() throws IOException {
        if (empty[depth]) {
            empty[depth] = false;
        } else {
            w.write(',');
        }
        w.write('\n');
        indent(depth);
    }

    private void beforeValue() throws IOException {
        if (pendingName) {
            pendingName = false;
            return;
        }
        sep();
    }

    public Jw name(String n) throws IOException {
        sep();
        w.write(quote(n));
        w.write(": ");
        pendingName = true;
        return this;
    }

    public Jw beginObject() throws IOException {
        beforeValue();
        w.write('{');
        depth++;
        empty[depth] = true;
        return this;
    }

    public Jw endObject() throws IOException {
        boolean wasEmpty = empty[depth];
        depth--;
        if (!wasEmpty) {
            w.write('\n');
            indent(depth);
        }
        w.write('}');
        return this;
    }

    public Jw beginArray() throws IOException {
        beforeValue();
        w.write('[');
        depth++;
        empty[depth] = true;
        return this;
    }

    public Jw endArray() throws IOException {
        boolean wasEmpty = empty[depth];
        depth--;
        if (!wasEmpty) {
            w.write('\n');
            indent(depth);
        }
        w.write(']');
        return this;
    }

    public Jw value(String s) throws IOException {
        beforeValue();
        w.write(s == null ? "null" : quote(s));
        return this;
    }

    public Jw value(long v) throws IOException {
        beforeValue();
        w.write(Long.toString(v));
        return this;
    }

    public Jw value(boolean b) throws IOException {
        beforeValue();
        w.write(b ? "true" : "false");
        return this;
    }

    /** Floats round-trip via Float.toString; non-finite values degrade to strings (JSON has no NaN). */
    public Jw value(float f) throws IOException {
        beforeValue();
        if (Float.isNaN(f) || Float.isInfinite(f)) {
            w.write(quote(Float.toString(f)));
        } else {
            w.write(Float.toString(f));
        }
        return this;
    }

    public Jw nul() throws IOException {
        beforeValue();
        w.write("null");
        return this;
    }

    public Jw prop(String n, String v) throws IOException {
        return name(n).value(v);
    }

    public Jw prop(String n, long v) throws IOException {
        return name(n).value(v);
    }

    public Jw prop(String n, boolean v) throws IOException {
        return name(n).value(v);
    }

    public Jw prop(String n, float v) throws IOException {
        return name(n).value(v);
    }

    public Jw propNull(String n) throws IOException {
        return name(n).nul();
    }

    public void finish() throws IOException {
        w.write('\n');
        w.flush();
    }

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20 || c == 0x7f) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }
}
