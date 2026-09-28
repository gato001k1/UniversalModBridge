package dev.umb.core;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * M8-2: structured {@code @At} injection point.
 * Mirrors {@code org.spongepowered.asm.mixin.injection.At} plus
 * {@code InjectionPointData.createPattern} parsing.
 *
 * <p>Raw point string grammar: {@code id} or {@code id:shift} where shift is
 * {@code BEFORE|AFTER|DEFAULT}. The modern {@code shift=BY} + {@code by} is a
 * separate annotation member and does not appear in the colon suffix (per
 * mixin-internals §3.5).
 *
 * <p>Captured fields are verbatim from the annotation: target (MemberSelector when
 * present), ordinal, opcode, args k=v, slice, id, remap, unsafe. Invalid nodes
 * set {@code valid=false} with a reason — D4 never invents a point type.
 */
public final class AtSelector implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Known built-in point ids (mixin-internals §3.5 registry). Unknown ids are kept verbatim with valid=false route. */
    public static final java.util.Set<String> KNOWN_POINTS = java.util.Set.of(
            "HEAD", "TAIL", "INVOKE", "FIELD", "NEW", "JUMP", "CONSTANT",
            "RETURN", "CTOR_HEAD", "INVOKE_ASSIGN", "INVOKE_STRING",
            "INVOKE_STRING_ASSIGN");

    private static final Pattern POINT_SUFFIX = Pattern.compile("^(.+?)(:(BEFORE|AFTER|DEFAULT))?$");

    public enum Shift implements Serializable { NONE, BEFORE, AFTER, DEFAULT, BY }

    private final String rawValue;          // e.g. "INVOKE:AFTER"
    private final String pointId;           // e.g. "INVOKE"
    private final Shift shift;
    private final MemberSelector target;    // null when At has no target string
    private final String rawTarget;         // nullable raw string before parse
    private final int ordinal;              // -1 = any
    private final int opcode;               // -1 = any
    private final Map<String, String> args; // parsed from String[] args k=v
    private final String slice;
    private final String id;
    private final boolean remap;
    private final boolean unsafe;
    private final boolean valid;
    private final String error;

    private AtSelector(String rawValue, String pointId, Shift shift,
                       MemberSelector target, String rawTarget,
                       int ordinal, int opcode, Map<String, String> args,
                       String slice, String id, boolean remap, boolean unsafe,
                       boolean valid, String error) {
        this.rawValue = rawValue;
        this.pointId = pointId;
        this.shift = shift;
        this.target = target;
        this.rawTarget = rawTarget;
        this.ordinal = ordinal;
        this.opcode = opcode;
        this.args = args == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(args));
        this.slice = slice;
        this.id = id;
        this.remap = remap;
        this.unsafe = unsafe;
        this.valid = valid;
        this.error = error;
    }

    public String rawValue() { return rawValue; }
    public String pointId() { return pointId; }
    public Shift shift() { return shift; }
    public MemberSelector target() { return target; }
    public String rawTarget() { return rawTarget; }
    public int ordinal() { return ordinal; }
    public int opcode() { return opcode; }
    public Map<String, String> args() { return args; }
    public String slice() { return slice; }
    public String id() { return id; }
    public boolean remap() { return remap; }
    public boolean unsafe() { return unsafe; }
    public boolean valid() { return valid; }
    public String error() { return error; }

    public boolean isHead() { return "HEAD".equals(pointId); }
    public boolean isTail() { return "TAIL".equals(pointId); }

    /**
     * Parses a single {@code @At} node from raw annotation members.
     * Members come directly from ASM's AnnotationNode values:
     * value, target, ordinal, opcode, args, slice, id, remap, unsafe, shift, by.
     */
    public static AtSelector parse(String rawValue,
                                   String rawTarget,
                                   Integer ordinal,
                                   Integer opcode,
                                   java.util.List<String> rawArgs,
                                   String slice,
                                   String atId,
                                   Boolean remap,
                                   Boolean unsafe,
                                   String shiftRaw,
                                   Integer by) {
        if (rawValue == null || rawValue.isBlank()) {
            return invalid(rawValue, null, Shift.NONE, rawTarget, null,
                    ordinal, opcode, rawArgs, slice, atId, remap, unsafe,
                    "At value is blank");
        }
        String trimmed = rawValue.trim();
        var m = POINT_SUFFIX.matcher(trimmed);
        if (!m.matches()) {
            return invalid(rawValue, trimmed, Shift.NONE, rawTarget, null,
                    ordinal, opcode, rawArgs, slice, atId, remap, unsafe,
                    "At value does not match ^(.+?)(:(BEFORE|AFTER|DEFAULT))?$ : " + rawValue);
        }
        String idPart = m.group(1);
        String suffix = m.group(3);
        Shift shift = Shift.NONE;
        if (suffix != null) {
            shift = switch (suffix) {
                case "BEFORE" -> Shift.BEFORE;
                case "AFTER" -> Shift.AFTER;
                case "DEFAULT" -> Shift.DEFAULT;
                default -> Shift.NONE;
            };
        }
        // Explicit shift=BY overrides colon suffix
        if ("BY".equals(shiftRaw)) {
            shift = Shift.BY;
            if (by != null && (by < -5 || by > 5)) {
                return invalid(rawValue, idPart, shift, rawTarget, null,
                        ordinal, opcode, rawArgs, slice, atId, remap, unsafe,
                        "shift BY value out of range [-5,5]: " + by);
            }
        } else if (shiftRaw != null && !"NONE".equals(shiftRaw) && !"BEFORE".equals(shiftRaw)
                && !"AFTER".equals(shiftRaw) && !"DEFAULT".equals(shiftRaw)) {
            return invalid(rawValue, idPart, shift, rawTarget, null,
                    ordinal, opcode, rawArgs, slice, atId, remap, unsafe,
                    "unknown shift: " + shiftRaw);
        }

        // Target parsing
        MemberSelector targetSel = null;
        if (rawTarget != null && !rawTarget.isBlank()) {
            String stripped = rawTarget.trim();
            // At target is a MemberInfo string (INVOKE's method ref, FIELD's field ref)
            targetSel = MemberSelector.parse(stripped);
            if (!targetSel.valid()) {
                return invalid(rawValue, idPart, shift, rawTarget, targetSel,
                        ordinal, opcode, rawArgs, slice, atId, remap, unsafe,
                        "At target invalid: " + targetSel.error());
            }
        }

        int ord = ordinal == null ? -1 : ordinal;
        if (ord < -1) {
            return invalid(rawValue, idPart, shift, rawTarget, targetSel,
                    ordinal, opcode, rawArgs, slice, atId, remap, unsafe,
                    "ordinal must be >= -1: " + ord);
        }
        int opc = opcode == null ? -1 : opcode;
        // opcode -1 means any; else must be a plausible JVM opcode — keep light check
        if (opc < -1 || opc > 255) {
            return invalid(rawValue, idPart, shift, rawTarget, targetSel,
                    ordinal, opcode, rawArgs, slice, atId, remap, unsafe,
                    "opcode out of byte range: " + opc);
        }

        Map<String, String> argsMap = parseArgs(rawArgs);
        if (argsMap == null) {
            return invalid(rawValue, idPart, shift, rawTarget, targetSel,
                    ordinal, opcode, rawArgs, slice, atId, remap, unsafe,
                    "args entry not k=v: " + rawArgs);
        }

        // Point-specific light guard: FIELD expects FIELD opcodes if opcode specified; INVOKE expects invoke.
        // We do not hard-fail here — D4 says report but keep valid for honest surfacing — except for fully unknown point ids.
        boolean known = KNOWN_POINTS.contains(idPart) || idPart.contains(".");
        // Dotted ids are custom injection points (FQCN of InjectionPoint subclass) — accept.
        if (!known && !idPart.contains(".")) {
            // Unknown built-in: mark invalid but keep fields so caller can diagnose
            return invalid(rawValue, idPart, shift, rawTarget, targetSel,
                    ord, opc, argsMap, slice, atId, remap, unsafe,
                    "unknown At point id: " + idPart);
        }

        boolean remapV = remap == null ? true : remap;
        boolean unsafeV = unsafe != null && unsafe;
        return new AtSelector(rawValue, idPart, shift, targetSel, rawTarget,
                ord, opc, argsMap, slice, atId, remapV, unsafeV, true, null);
    }

    /** Minimal parser for the frequent At(value="HEAD") case. */
    public static AtSelector parseSimple(String rawValue) {
        return parse(rawValue, null, null, null, null, null, null, null, null, null, null);
    }

    private static AtSelector invalid(String rawValue, String pointId, Shift shift,
                                      String rawTarget, MemberSelector target,
                                      Integer ordinal, Integer opcode, java.util.List<String> rawArgs,
                                      String slice, String atId, Boolean remap, Boolean unsafe,
                                      String error) {
        Map<String, String> argsMap = null;
        if (rawArgs != null) {
            try { argsMap = parseArgs(rawArgs); } catch (Exception ignored) { argsMap = Map.of(); }
        }
        if (argsMap == null) argsMap = Map.of();
        // Also try to handle the case where rawArgs was actually a map already
        int ord = ordinal == null ? -1 : ordinal;
        int opc = opcode == null ? -1 : opcode;
        boolean remapV = remap == null ? true : remap;
        boolean unsafeV = unsafe != null && unsafe;
        return new AtSelector(rawValue, pointId, shift, target, rawTarget, ord, opc, argsMap, slice, atId, remapV, unsafeV, false, error);
    }

    private static AtSelector invalid(String rawValue, String pointId, Shift shift,
                                      String rawTarget, MemberSelector target,
                                      int ordinal, int opcode, Map<String, String> argsMap,
                                      String slice, String atId, Boolean remap, Boolean unsafe,
                                      String error) {
        int ord = ordinal;
        int opc = opcode;
        boolean remapV = remap == null ? true : remap;
        boolean unsafeV = unsafe != null && unsafe;
        return new AtSelector(rawValue, pointId, shift, target, rawTarget, ord, opc, argsMap, slice, atId, remapV, unsafeV, false, error);
    }

    private static Map<String, String> parseArgs(java.util.List<String> rawArgs) {
        if (rawArgs == null || rawArgs.isEmpty()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        for (String kv : rawArgs) {
            if (kv == null) continue;
            String s = kv.trim();
            if (s.isEmpty()) continue;
            int eq = s.indexOf('=');
            if (eq <= 0 || eq == s.length() - 1) {
                // args are sometimes "fuzz=50" or "ordinal=0"; bare values illegal
                return null;
            }
            String k = s.substring(0, eq).trim();
            String v = s.substring(eq + 1).trim();
            if (k.isEmpty() || v.isEmpty()) return null;
            out.put(k, v);
        }
        return out;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AtSelector a)) return false;
        return ordinal == a.ordinal && opcode == a.opcode && remap == a.remap && unsafe == a.unsafe
                && valid == a.valid && Objects.equals(rawValue, a.rawValue) && Objects.equals(pointId, a.pointId)
                && shift == a.shift && Objects.equals(target, a.target) && Objects.equals(rawTarget, a.rawTarget)
                && Objects.equals(args, a.args) && Objects.equals(slice, a.slice) && Objects.equals(id, a.id)
                && Objects.equals(error, a.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawValue, pointId, shift, target, rawTarget, ordinal, opcode, args, slice, id, remap, unsafe, valid, error);
    }

    @Override
    public String toString() {
        if (!valid) return "AtSelector[INVALID raw=" + rawValue + " error=" + error + "]";
        return "AtSelector[" + pointId + (shift == Shift.NONE ? "" : ":" + shift)
                + (target == null ? "" : " target=" + target)
                + (ordinal != -1 ? " ordinal=" + ordinal : "")
                + (opcode != -1 ? " opcode=" + opcode : "")
                + (args.isEmpty() ? "" : " args=" + args)
                + (id != null ? " id=" + id : "") + "]";
    }
}
