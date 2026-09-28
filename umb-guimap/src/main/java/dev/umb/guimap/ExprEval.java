package dev.umb.guimap;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decodes the {@code "getfield Owner.field_XXX+N-M..."}-shaped reason strings produced by
 * {@link MethodSim}'s IADD/ISUB tagging back into a base field name plus an accumulated integer
 * delta. Used to (a) fold a draw-call argument to an absolute pixel constant when the base field
 * is itself a resolved constant (e.g. {@code ySize - 96 + 2} once {@code ySize} is known to be
 * 166), and (b) recognise the extremely common {@code guiLeft}/{@code guiTop} + constant-offset
 * shape of a {@code drawTexturedModalRect} x/y argument even when the base isn't a compile-time
 * constant (guiLeft/guiTop are only known at render time).
 */
public final class ExprEval {
    private ExprEval() {}

    private static final Pattern SHAPE = Pattern.compile("^getfield ([^.]+)\\.(\\w+)((?:[+-]\\d+)*)$");
    private static final Pattern DELTA = Pattern.compile("([+-]\\d+)");

    public static final class Decoded {
        public final String owner;
        public final String fieldName;
        public final int delta;
        Decoded(String owner, String f, int d) { this.owner = owner; fieldName = f; delta = d; }
    }

    /** Parses a getfield(+delta)* reason string; null if it isn't that shape. */
    public static Decoded decode(String reason) {
        if (reason == null) return null;
        Matcher m = SHAPE.matcher(reason);
        if (!m.matches()) return null;
        int total = 0;
        Matcher d = DELTA.matcher(m.group(3));
        while (d.find()) total += Integer.parseInt(d.group(1));
        return new Decoded(m.group(1), m.group(2), total);
    }
}
