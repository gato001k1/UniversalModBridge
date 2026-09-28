package dev.umb.core;

import java.io.Serializable;

/**
 * M8-2: selector quantifier per MemberInfo/Quantifier model (mixin-internals §3.3).
 * Undecorated name defaults to {0,1} (DEFAULT), {@code *} to {0,INF}, {@code +} to {1,INF},
 * {@code {n}}, {@code {,m}}, {@code {n,}}, {@code {n,m}} to explicit bounds.
 * Kept immutable and {@link Serializable} for IR records.
 */
public final class Quantifier implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final Quantifier DEFAULT = new Quantifier(0, 1, "");
    public static final Quantifier ANY = new Quantifier(0, Integer.MAX_VALUE, "*");
    public static final Quantifier PLUS = new Quantifier(1, Integer.MAX_VALUE, "+");

    private final int min;
    private final int max;
    private final String raw;

    public Quantifier(int min, int max, String raw) {
        this.min = min;
        this.max = max;
        this.raw = raw;
    }

    public int min() { return min; }
    public int max() { return max; }
    public String raw() { return raw; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Quantifier q)) return false;
        return min == q.min && max == q.max && raw.equals(q.raw);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * min + max) + raw.hashCode();
    }

    @Override
    public String toString() {
        return "Quantifier" + raw + "[" + min + "," + (max == Integer.MAX_VALUE ? "*" : max) + "]";
    }

    static Quantifier parseBraced(String raw) {
        String inner = raw.substring(1, raw.length() - 1);
        if (inner.isEmpty()) return new Quantifier(0, Integer.MAX_VALUE, raw);
        int comma = inner.indexOf(',');
        if (comma < 0) {
            int n = Integer.parseInt(inner.trim());
            if (n < 0) throw new NumberFormatException("negative quantifier " + raw);
            return new Quantifier(n, n, raw);
        }
        String left = inner.substring(0, comma).trim();
        String right = inner.substring(comma + 1).trim();
        int min = left.isEmpty() ? 0 : Integer.parseInt(left);
        int max = right.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(right);
        if (min < 0 || max < 0 || max < min) throw new NumberFormatException("bad range " + raw);
        return new Quantifier(min, max, raw);
    }
}
