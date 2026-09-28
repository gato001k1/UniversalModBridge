package dev.umb.core;

import java.io.Serializable;
import java.util.Objects;

/**
 * M8-2: structured MemberInfo (mixin-internals §3.3).
 * Grammar per SpongePowered MemberInfo: {@code [Lowner;|.owner.]name[quantifier][:desc|(args)ret]}
 * with optional tail chain {@code  -> tail}. Whitespace stripped before parsing.
 * Descriptor: after {@code :} for fields, or {@code (args)ret} for methods.
 * Quantifier: none → {0,1} DEFAULT; {@code *} → ANY; {@code +} → at least one;
 * braced {@code {n}}, {@code {,n}}, {@code {n,}}, {@code {n,m}}.
 * Case-insensitive fallback is Mixin matching policy, not parsing — stored verbatim.
 * D4: invalid strings never guess a member; {@code valid=false} carries a reason.
 */
public final class MemberSelector implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String raw;
    private final String owner;       // slash form or null
    private final String name;
    private final Quantifier quantifier;
    private final String descriptor;  // null when absent; method desc starts with '('
    private final boolean isField;    // true iff descriptor != null && !descriptor.startsWith("(")
    private final MemberSelector tail; // chained after "->", null otherwise
    private final boolean valid;
    private final String error;       // nullable when valid

    private MemberSelector(String raw, String owner, String name, Quantifier quantifier,
                           String descriptor, boolean isField, MemberSelector tail,
                           boolean valid, String error) {
        this.raw = raw;
        this.owner = owner;
        this.name = name;
        this.quantifier = quantifier;
        this.descriptor = descriptor;
        this.isField = isField;
        this.tail = tail;
        this.valid = valid;
        this.error = error;
    }

    public String raw() { return raw; }
    public String owner() { return owner; }
    public String name() { return name; }
    public Quantifier quantifier() { return quantifier; }
    public String descriptor() { return descriptor; }
    public boolean isField() { return isField; }
    public MemberSelector tail() { return tail; }
    public boolean valid() { return valid; }
    public String error() { return error; }

    public boolean isMethod() { return descriptor != null && descriptor.startsWith("("); }

    /** Returns true when a descriptor is present and syntactically a method descriptor. */
    public static MemberSelector parse(String rawInput) {
        Objects.requireNonNull(rawInput, "rawInput");
        String raw = rawInput;
        String stripped = raw.replaceAll("\\s+", "");
        if (stripped.isEmpty()) {
            return invalid(raw, null, "empty selector");
        }
        // Tail chain: split on "->" (after whitespace strip it becomes "->")
        int arrow = stripped.indexOf("->");
        MemberSelector tail = null;
        String head = stripped;
        if (arrow >= 0) {
            head = stripped.substring(0, arrow);
            String tailRaw = stripped.substring(arrow + 2);
            if (tailRaw.isEmpty()) {
                return invalid(raw, null, "trailing '->' with no tail");
            }
            MemberSelector parsedTail = parse(tailRaw);
            if (!parsedTail.valid()) {
                return invalid(raw, null, "tail invalid: " + parsedTail.error());
            }
            tail = parsedTail;
            if (head.isEmpty()) {
                return invalid(raw, tail, "empty head before '->'");
            }
        }
        // Separate descriptor
        String descriptor = null;
        String withoutDesc = head;
        int paren = head.indexOf('(');
        int colon = head.indexOf(':');
        // Method descriptor takes precedence when '(' present — field separator ':' before '(' would be ambiguous but MemberInfo grammar treats '(' as method.
        if (paren >= 0) {
            withoutDesc = head.substring(0, paren);
            descriptor = head.substring(paren);
            if (!descriptor.contains(")")) {
                return invalid(raw, tail, "malformed method descriptor missing ')' : " + descriptor);
            }
            // Light descriptor shape check — don't run full ASM parse on every selector, but require balanced.
            if (descriptor.length() < 3) {
                return invalid(raw, tail, "method descriptor too short: " + descriptor);
            }
        } else if (colon >= 0) {
            withoutDesc = head.substring(0, colon);
            descriptor = head.substring(colon + 1);
            if (descriptor.isEmpty()) {
                return invalid(raw, tail, "field descriptor after ':' is empty");
            }
            // Field descriptor must be a single type; allow array/object/primitive leniently.
        }
        if (withoutDesc.isEmpty()) {
            return invalid(raw, tail, "missing name part");
        }
        // Quantifier suffix on withoutDesc (name+owner portion)
        Quantifier quant = Quantifier.DEFAULT;
        String bare = withoutDesc;
        if (withoutDesc.endsWith("*")) {
            quant = Quantifier.ANY;
            bare = withoutDesc.substring(0, withoutDesc.length() - 1);
        } else if (withoutDesc.endsWith("+")) {
            quant = Quantifier.PLUS;
            bare = withoutDesc.substring(0, withoutDesc.length() - 1);
        } else if (withoutDesc.endsWith("}")) {
            int open = withoutDesc.lastIndexOf('{');
            if (open < 0) {
                return invalid(raw, tail, "unmatched '}' in quantifier");
            }
            String braced = withoutDesc.substring(open);
            try {
                quant = Quantifier.parseBraced(braced);
            } catch (Exception e) {
                return invalid(raw, tail, "bad quantifier " + braced + ": " + e.getMessage());
            }
            bare = withoutDesc.substring(0, open);
            if (bare.isEmpty()) {
                return invalid(raw, tail, "quantifier with no name: " + braced);
            }
        }
        if (bare.isEmpty()) {
            return invalid(raw, tail, "name is empty after stripping quantifier");
        }
        // Owner vs name split — handle Lowner; prefix or dot-qualified.
        String owner = null;
        String name;
        if (bare.startsWith("L")) {
            int semi = bare.indexOf(';');
            if (semi < 0) {
                return invalid(raw, tail, "L-owner without ';': " + bare);
            }
            owner = bare.substring(1, semi);
            name = bare.substring(semi + 1);
            if (name.isEmpty()) {
                return invalid(raw, tail, "L-owner with empty member name");
            }
            if (owner.isEmpty()) {
                return invalid(raw, tail, "L-owner empty");
            }
            owner = owner.replace('.', '/');
        } else {
            int dot = bare.lastIndexOf('.');
            if (dot >= 0) {
                owner = bare.substring(0, dot).replace('.', '/');
                name = bare.substring(dot + 1);
                if (owner.isEmpty()) {
                    return invalid(raw, tail, "dotted owner empty");
                }
                if (name.isEmpty()) {
                    return invalid(raw, tail, "trailing '.' with empty member name");
                }
                // Mixed dotted/slash form (e.g. owner.Foo/bar) — split any slash inside name into owner
                if (name.contains("/")) {
                    int sl = name.lastIndexOf('/');
                    String prefix = name.substring(0, sl);
                    String suffix = name.substring(sl + 1);
                    if (suffix.isEmpty()) {
                        return invalid(raw, tail, "trailing '/' with empty member name: " + name);
                    }
                    owner = owner + "/" + prefix;
                    name = suffix;
                }
            } else {
                // No dot — bare may still contain slash (internal owner form without dot)
                int sl = bare.lastIndexOf('/');
                if (sl >= 0) {
                    owner = bare.substring(0, sl);
                    name = bare.substring(sl + 1);
                    if (owner.isEmpty() || name.isEmpty()) {
                        return invalid(raw, tail, "slash owner/name empty: " + bare);
                    }
                } else {
                    name = bare;
                }
            }
        }
        // Name sanity: no slashes, no semicolons, no spaces (already stripped)
        if (name.contains("/") || name.contains(";") || name.contains(":")) {
            return invalid(raw, tail, "illegal chars in member name: " + name);
        }
        // Descriptor light validation when present: field vs method
        boolean isField = descriptor != null && !descriptor.startsWith("(");
        if (descriptor != null) {
            if (isField) {
                // Field descriptor: single type; quick check first char
                char c = descriptor.charAt(0);
                boolean ok = c == 'L' || c == '[' || "BCDFIJSZV".indexOf(c) >= 0;
                if (!ok) {
                    return invalid(raw, tail, "bad field descriptor: " + descriptor);
                }
            } else {
                // Method descriptor already checked for ')', ensure no spaces and contains '(' at start
                if (descriptor.charAt(0) != '(') {
                    return invalid(raw, tail, "method descriptor must start with '(': " + descriptor);
                }
            }
        }
        return new MemberSelector(raw, owner, name, quant, descriptor, isField, tail, true, null);
    }

    private static MemberSelector invalid(String raw, MemberSelector tail, String error) {
        return new MemberSelector(raw, null, null, Quantifier.DEFAULT, null, false, tail, false, error);
    }

    /** Helper to normalize owner for graph lookups (slash form). */
    public String ownerSlash() { return owner; }

    public String ownerDot() { return owner == null ? null : owner.replace('/', '.'); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemberSelector m)) return false;
        return valid == m.valid && isField == m.isField
                && Objects.equals(raw, m.raw) && Objects.equals(owner, m.owner)
                && Objects.equals(name, m.name) && Objects.equals(quantifier, m.quantifier)
                && Objects.equals(descriptor, m.descriptor)
                && Objects.equals(tail, m.tail) && Objects.equals(error, m.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, owner, name, quantifier, descriptor, isField, tail, valid, error);
    }

    @Override
    public String toString() {
        if (!valid) return "MemberSelector[INVALID raw=" + raw + " error=" + error + "]";
        StringBuilder sb = new StringBuilder();
        if (owner != null) sb.append(owner).append('.');
        sb.append(name).append(quantifier.raw() == null ? "" : quantifier.raw());
        if (descriptor != null) sb.append(isField ? ":" : "").append(descriptor);
        if (tail != null) sb.append(" -> ").append(tail);
        return sb.toString();
    }
}
