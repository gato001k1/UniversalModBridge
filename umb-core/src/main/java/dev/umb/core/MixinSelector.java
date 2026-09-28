package dev.umb.core;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * M8-2: one parsed mixin handler's selector IR — the structured record consumable
 * by the later rewriter stage. Mirrors the injector dispatch in mixin-internals §3.4.
 *
 * <p>Each record binds: owning mixin class, handler method, injector kind, target
 * selectors ({@code method} strings or {@code @Desc} dynamic selectors — stored as
 * {@link MemberSelector} list, invalid entries kept invalid), {@code @At} points,
 * {@code @Slice} anchors, and expectations ({@code require}/{@code expect}/{@code allow}).
 *
 * <p>D4: invalid selectors are kept, not dropped — the rewriter must report unresolvable
 * rather than silently skip. All lists are immutable copies.
 */
public final class MixinSelector implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String mixinClass;               // internal name slash form
    private final String handlerMethod;
    private final String handlerDesc;              // method descriptor, nullable
    private final InjectionPointKind kind;
    private final List<MemberSelector> targets;    // from "method" member (+ @Desc when present — currently string-only)
    private final List<AtSelector> atSelectors;
    private final List<SliceSpec> slices;
    private final int require;
    private final int expect;                      // -1 when absent
    private final Integer allow;                   // null when absent
    private final String sliceRef;                 // nullable slice id referenced by @At

    public MixinSelector(String mixinClass, String handlerMethod, String handlerDesc,
                         InjectionPointKind kind,
                         List<MemberSelector> targets,
                         List<AtSelector> atSelectors,
                         List<SliceSpec> slices,
                         int require, int expect, Integer allow,
                         String sliceRef) {
        this.mixinClass = Objects.requireNonNull(mixinClass, "mixinClass");
        this.handlerMethod = Objects.requireNonNull(handlerMethod, "handlerMethod");
        this.handlerDesc = handlerDesc;
        this.kind = Objects.requireNonNull(kind, "kind");
        this.targets = targets == null ? List.of() : List.copyOf(targets);
        this.atSelectors = atSelectors == null ? List.of() : List.copyOf(atSelectors);
        this.slices = slices == null ? List.of() : List.copyOf(slices);
        this.require = require;
        this.expect = expect;
        this.allow = allow;
        this.sliceRef = sliceRef;
    }

    public String mixinClass() { return mixinClass; }
    public String handlerMethod() { return handlerMethod; }
    public String handlerDesc() { return handlerDesc; }
    public InjectionPointKind kind() { return kind; }
    public List<MemberSelector> targets() { return targets; }
    public List<AtSelector> atSelectors() { return atSelectors; }
    public List<SliceSpec> slices() { return slices; }
    public int require() { return require; }
    public int expect() { return expect; }
    public Integer allow() { return allow; }
    public String sliceRef() { return sliceRef; }

    /** True when every target and every At target parsed without error. */
    public boolean allSelectorsValid() {
        for (MemberSelector m : targets) if (!m.valid()) return false;
        for (AtSelector a : atSelectors) {
            if (!a.valid()) return false;
            if (a.target() != null && !a.target().valid()) return false;
        }
        for (SliceSpec s : slices) {
            if (s.from() != null && s.from().target() != null && !s.from().target().valid()) return false;
            if (s.to() != null && s.to().target() != null && !s.to().target().valid()) return false;
        }
        return true;
    }

    /** Human-readable invalidation reasons. */
    public List<String> invalidReasons() {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (MemberSelector m : targets) if (!m.valid()) out.add("target method '" + m.raw() + "': " + m.error());
        for (AtSelector a : atSelectors) {
            if (!a.valid()) out.add("@At '" + a.rawValue() + "': " + a.error());
            else if (a.target() != null && !a.target().valid()) out.add("@At target '" + a.rawTarget() + "': " + a.target().error());
        }
        for (SliceSpec s : slices) {
            if (s.from() != null && !s.from().valid()) out.add("@Slice from '" + s.from().rawValue() + "': " + s.from().error());
            if (s.to() != null && !s.to().valid()) out.add("@Slice to '" + s.to().rawValue() + "': " + s.to().error());
        }
        return List.copyOf(out);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MixinSelector m)) return false;
        return require == m.require && expect == m.expect
                && Objects.equals(mixinClass, m.mixinClass) && Objects.equals(handlerMethod, m.handlerMethod)
                && Objects.equals(handlerDesc, m.handlerDesc) && kind == m.kind
                && Objects.equals(targets, m.targets) && Objects.equals(atSelectors, m.atSelectors)
                && Objects.equals(slices, m.slices) && Objects.equals(allow, m.allow) && Objects.equals(sliceRef, m.sliceRef);
    }

    @Override
    public int hashCode() { return Objects.hash(mixinClass, handlerMethod, handlerDesc, kind, targets, atSelectors, slices, require, expect, allow, sliceRef); }

    @Override
    public String toString() {
        return "MixinSelector[" + kind + " " + mixinClass + "#" + handlerMethod
                + " targets=" + targets + " ats=" + atSelectors + (validSummary()) + "]";
    }

    private String validSummary() { return allSelectorsValid() ? "" : " INVALID:" + invalidReasons(); }

    /**
     * One {@code @Slice(from=@At(...), to=@At(...), id="...")} anchor.
     * Either bound may be absent (slice start/end of method).
     */
    public record SliceSpec(AtSelector from, AtSelector to, String id) implements Serializable {}
}
