package dev.umb.rendermap;

import java.util.ArrayList;
import java.util.List;

/**
 * A symbolic value on the simulated JVM operand stack / in a local slot.
 * Everything the backtracker cannot model collapses to {@link Kind#UNKNOWN}
 * carrying a human-readable reason, so callers can always report *why* a
 * binding is unresolved instead of guessing.
 */
public final class Val {

    public enum Kind {
        UNKNOWN,
        STRING,       // ldc "..."
        NUMBER,       // iconst/bipush/sipush/ldc numeric
        CLASS,        // ldc Ltype;  (org.objectweb.asm.Type)
        STATIC_FIELD, // getstatic owner.name : desc
        NEW_UNINIT,   // result of NEW before <init>
        NEW_OBJ,      // fully constructed object, with ctor args
        ITEM_FROM_BLOCK, // Item.func_150898_a(<block>)
        ARRAY,        // newarray / anewarray with (partially) known elements
        ARRAY_UNKNOWN_INDEX, // aaload/etc. on a *known* ARRAY at a non-constant index (loop var):
                              // arraySource carries the whole array so callers can enumerate it
        DERIVED,      // a modelled accessor applied to a known value, e.g. getUnlocalizedName()
        PARAM,        // an incoming method parameter, identified by its local slot
        CALL,        // result of an unmodelled invokestatic, with its (symbolic) arguments
        NULL,
        THIS,
        /**
         * Moving-parts lane (opt-in {@code trackProvenance} only - never produced otherwise): an
         * instance-field read with its receiver tree preserved ({@code owner}/{@code name}/{@code
         * desc} are the field, {@code inner} is the receiver {@link Val}). Lets the dynamic-op
         * resolver bind GL arguments to tile-entity fields instead of collapsing to UNKNOWN.
         */
        FIELD,
        /**
         * Moving-parts lane (opt-in {@code trackProvenance} only): a pure numeric combinator
         * (add/sub/mul/div/neg and numeric conversions) with its operand trees preserved in
         * {@code ctorArgs} and the operator name in {@code stringValue}. Lets the resolver keep
         * expressions like {@code prev + (cur - prev) * partial} symbolic.
         */
        EXPR,
        /**
         * Door-live lane (opt-in {@code trackProvenance} only): a constant-index read off a
         * static pure call returning an array. {@code inner} is the CALL node,
         * {@code numberValue} the constant index. The resolver turns these into
         * server-evaluated animation channels instead of collapsing them.
         */
        CHANNEL
    }

    public final Kind kind;
    public String stringValue;
    public Number numberValue;
    public String typeName;         // internal name for CLASS / NEW_* / ARRAY element type
    public String owner, name, desc; // STATIC_FIELD
    public List<Val> ctorArgs;      // NEW_OBJ
    /** builder-style calls applied to a NEW_OBJ: {methodName, firstStringArgOrNull}. */
    public List<String[]> calls;
    public Val inner;               // ITEM_FROM_BLOCK payload
    public List<Val> elements;      // ARRAY
    public Val arraySource;         // ARRAY_UNKNOWN_INDEX: the whole array this element came from
    /**
     * ARRAY only, and only for a "virtual" array synthesised by {@link MethodSim} for a
     * {@code getstatic} of a content-holder array field (see {@link MethodSim}'s content-array
     * tracking): maps a non-constant index — identified by REFERENCE identity of the {@link Val}
     * that was loaded for it, which {@link MethodSim} keeps stable per local slot within one
     * method's single linear pass — to the value most recently stored at that symbolic index by
     * an {@code aastore}/{@code iastore}/etc. earlier in the SAME pass. This is what lets
     * {@code array[i] = new Foo(...); ...; register(array[i], ...)} inside one loop body resolve
     * the concrete element even though {@code i} is never a compile-time constant. {@code null}
     * for every other array (ordinary {@code newarray}/{@code anewarray} results never populate
     * this map, so their behaviour is completely unchanged).
     */
    public java.util.Map<Val, Val> symbolicWrites;
    public String reason;           // UNKNOWN
    /** true when this NEW_OBJ was synthesised from a builder call on a non-`new` receiver
     *  (e.g. {@code ModItems.base.copy()}); ctorArgs then holds the originating value. */
    public boolean syntheticBuilder;
    public int uninitId = -1;       // links NEW_UNINIT copies to their <init>
    /**
     * Running concatenation for a {@code java.lang.StringBuilder}/{@code StringBuffer} NEW_OBJ:
     * non-null exactly while every constructor arg and every {@code .append(...)} arg seen so far
     * was itself a resolved {@link Kind#STRING}. Set to {@code null} (poisoned) the moment an
     * append argument is not a known string, so {@code .toString()} only ever resolves when the
     * whole chain was literal/substituted text - never a guess.
     */
    public String builtString;
    /**
     * Door-live follow-up (GL-state ops): constant {@code double[]} payload last
     * {@code put} into this {@code java.nio.DoubleBuffer}-shaped value, or null when never
     * put or last put was non-constant. Attached to the receiver object itself because
     * render code discards {@code put}'s result and reuses the buffer parameter.
     */
    public double[] bufferDoubles;

    private Val(Kind k) { this.kind = k; }

    public static final Val NULL_V = new Val(Kind.NULL);

    public static Val unknown(String reason) {
        Val v = new Val(Kind.UNKNOWN); v.reason = reason; return v;
    }
    public static Val string(String s) { Val v = new Val(Kind.STRING); v.stringValue = s; return v; }
    public static Val number(Number n) { Val v = new Val(Kind.NUMBER); v.numberValue = n; return v; }
    public static Val clazz(String internalName) { Val v = new Val(Kind.CLASS); v.typeName = internalName; return v; }
    public static Val thisRef() { return new Val(Kind.THIS); }
    public static Val param(int argIndex) { Val v = new Val(Kind.PARAM); v.numberValue = argIndex; return v; }
    public static Val call(String owner, String name, List<Val> args) {
        Val v = new Val(Kind.CALL); v.owner = owner; v.name = name; v.ctorArgs = args; return v;
    }

    public static Val staticField(String owner, String name, String desc) {
        Val v = new Val(Kind.STATIC_FIELD); v.owner = owner; v.name = name; v.desc = desc; return v;
    }
    public static Val newUninit(String internalName, int id) {
        Val v = new Val(Kind.NEW_UNINIT); v.typeName = internalName; v.uninitId = id; return v;
    }
    public static Val newObj(String internalName, List<Val> args) {
        Val v = new Val(Kind.NEW_OBJ); v.typeName = internalName; v.ctorArgs = args; return v;
    }
    public static Val itemFromBlock(Val block) {
        Val v = new Val(Kind.ITEM_FROM_BLOCK); v.inner = block; return v;
    }
    public static Val derived(String op, Val inner) {
        Val v = new Val(Kind.DERIVED); v.stringValue = op; v.inner = inner; return v;
    }
    /** Opt-in provenance only: {@code receiver.field} with the receiver tree kept. */
    public static Val field(String owner, String name, String desc, Val receiver) {
        Val v = new Val(Kind.FIELD); v.owner = owner; v.name = name; v.desc = desc; v.inner = receiver;
        return v;
    }
    /** Opt-in provenance only: pure numeric {@code op} over {@code operands} (see {@link Kind#EXPR}). */
    public static Val expr(String op, List<Val> operands) {
        Val v = new Val(Kind.EXPR); v.stringValue = op; v.ctorArgs = operands; return v;
    }
    /** Opt-in provenance only: constant {@code index} read off a static pure {@code call}. */
    public static Val channel(Val call, int index) {
        Val v = new Val(Kind.CHANNEL); v.inner = call; v.numberValue = index; return v;
    }
    public static Val array(String elemType, int len) {
        Val v = new Val(Kind.ARRAY); v.typeName = elemType; v.elements = new ArrayList<>();
        for (int i = 0; i < len; i++) v.elements.add(unknown("array slot never stored"));
        return v;
    }
    /** {@code array[i]} where {@code array} is a known {@link Kind#ARRAY} but {@code i} is not a
     *  constant (a loop induction variable). Callers that recognise a registration call taking
     *  one of these can enumerate {@code arraySource.elements} instead of giving up. */
    public static Val arrayUnknownIndex(Val array) {
        Val v = new Val(Kind.ARRAY_UNKNOWN_INDEX); v.arraySource = array; return v;
    }
    /**
     * A "virtual" array standing in for a {@code getstatic} of a content-holder array field
     * (see {@link MethodSim}'s content-array tracking / {@link #symbolicWrites}). Its real,
     * cross-method contents are unknown (that would require whole-program analysis this project
     * deliberately avoids), so {@link #elements} starts empty and only grows for constant-index
     * stores seen later in the SAME method; {@link #symbolicWrites} separately remembers
     * non-constant-index stores within that same single pass.
     */
    public static Val contentArray(String elemType) {
        Val v = new Val(Kind.ARRAY);
        v.typeName = elemType;
        v.elements = new ArrayList<>();
        v.symbolicWrites = new java.util.IdentityHashMap<>();
        return v;
    }
    /**
     * A fully-known, already-computed list of values presented as one compile-time array so
     * existing "loop over an array" consumers ({@link BindingScanner#expand}/{@code #zipExpand})
     * can enumerate it without any change to that code. Wrapped in {@link #arrayUnknownIndex} by
     * the caller, matching the shape those consumers already expect for a non-constant-index read.
     */
    public static Val arrayLiteral(List<Val> elements) {
        Val v = new Val(Kind.ARRAY);
        v.elements = elements;
        return v;
    }

    public boolean isStaticField() { return kind == Kind.STATIC_FIELD; }

    private String operandsString() {
        StringBuilder sb = new StringBuilder("(");
        if (ctorArgs != null) {
            for (int i = 0; i < ctorArgs.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(ctorArgs.get(i));
            }
        }
        return sb.append(")").toString();
    }

    /** "owner.name" for a static field, else null. */
    public String fieldKey() {
        return kind == Kind.STATIC_FIELD ? owner + "." + name : null;
    }

    @Override public String toString() {
        switch (kind) {
            case STRING: return "\"" + stringValue + "\"";
            case NUMBER: return String.valueOf(numberValue);
            case CLASS: return "class " + typeName;
            case STATIC_FIELD: return owner + "." + name;
            case NEW_OBJ: return "new " + typeName;
            case NEW_UNINIT: return "uninit " + typeName;
            case ITEM_FROM_BLOCK: return "itemFromBlock(" + inner + ")";
            case DERIVED: return stringValue + "(" + inner + ")";
            case PARAM: return "arg" + numberValue;
            case CALL: return "result of " + owner.substring(owner.lastIndexOf(47) + 1) + "." + name + "()";
            case FIELD: return owner + "." + name + " of (" + inner + ")";
            case EXPR: return stringValue + operandsString();
            case CHANNEL: return "channel[" + numberValue + "] of (" + inner + ")";
            case ARRAY: return "array[" + (elements == null ? 0 : elements.size()) + "]";
            case ARRAY_UNKNOWN_INDEX: return "arrayElement(non-constant index, " + arraySource + ")";
            case NULL: return "null";
            case THIS: return "this";
            default: return "?(" + reason + ")";
        }
    }
}
