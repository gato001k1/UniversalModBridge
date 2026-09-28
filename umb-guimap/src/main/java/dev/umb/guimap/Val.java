package dev.umb.guimap;

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
        DERIVED,      // a modelled accessor applied to a known value, e.g. getUnlocalizedName()
        PARAM,        // an incoming method parameter, identified by its local slot
        CALL,         // result of an unmodelled invokestatic, with its (symbolic) arguments
        NULL,
        THIS
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
    public String reason;           // UNKNOWN
    /**
     * The receiving object a {@code getfield}-shaped or generic-call-result UNKNOWN was read off
     * (umb-guimap addition; see {@link MethodSim}). Null for statics and everything else. Chaining
     * this backwards (a {@code receiver} that is itself a getfield-shaped UNKNOWN) reconstructs the
     * full access path, e.g. {@code this.container.tileEntity.progress}, without any string parsing.
     */
    public Val receiver;
    /**
     * For a generic (unmodelled) call-result UNKNOWN: the actual argument Vals passed at the call
     * site (umb-guimap addition). Lets a caller with jar access re-simulate the callee body with
     * these exact values substituted for its parameters — see {@code DrawLayerScanner}'s accessor
     * inlining, used for the extremely common 1.7.10 {@code getFooScaled(24)}-style helper where the
     * scale constant is a call-site argument, not a literal inside the callee.
     */
    public List<Val> callArgs;
    /**
     * The exact invoke opcode (an {@code org.objectweb.asm.Opcodes} constant) that produced this
     * still-unresolved call-result {@link Kind#UNKNOWN} value — umb-guimap NOTEXTURE-GAP addition,
     * set only alongside {@link #owner}/{@link #name}/{@link #desc}/{@link #receiver}/{@link #callArgs}
     * on the generic "result of X.y()" fallback in {@code MethodSim#invoke}. -1 everywhere else
     * (every other {@link Val} factory). Lets a later, opt-in re-resolution
     * ({@code MethodSim#resolveAccessorForTextureClassification}) tell a true virtual/interface
     * dispatch (eligible for a this-receiver runtime-type search) from an
     * {@code INVOKESPECIAL}/{@code INVOKESTATIC} call (never eligible — no dispatch to resolve).
     */
    public int invokeOp = -1;
    /**
     * Present when this (still nominally UNKNOWN) value is symbolically known to be an affine/
     * multiplicative expression over exactly one runtime field — the general 1.7.10 progress-bar
     * shape {@code field * SCALE / DIVISOR} (umb-guimap addition; see {@link LinearExpr}).
     */
    public LinearExpr linear;
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
     * Set only when {@code kind==UNKNOWN} and {@code "compare".equals(reason)} (GUARD-EXPRESSIONS
     * lane addition): the two REAL operands an {@code LCMP}/{@code FCMPL}/{@code FCMPG}/
     * {@code DCMPL}/{@code DCMPG} instruction compared, before the JVM collapsed them to a single
     * -1/0/1 int. A following {@code IFxx}-vs-0 test on this value is semantically a direct
     * two-operand compare of {@code cmpLeft} against {@code cmpRight} — see
     * {@code DrawLayerScanner}'s guard-expression builder. Never read by any pre-existing
     * {@code describeVal}/{@code classifyInt}/{@code fieldConditionJson} path (those only ever
     * look at {@code reason}, which stays the literal string {@code "compare"} exactly as before),
     * so this is purely additive — no existing behavior or JSON output changes.
     */
    public Val cmpLeft, cmpRight;

    private Val(Kind k) { this.kind = k; }

    public static final Val NULL_V = new Val(Kind.NULL);

    public static Val unknown(String reason) {
        Val v = new Val(Kind.UNKNOWN); v.reason = reason; return v;
    }
    /** The real two operands behind an {@code LCMP}/{@code FCMPx}/{@code DCMPx} result (see
     *  {@link #cmpLeft}/{@link #cmpRight}) — {@code reason} stays plain {@code "compare"}. */
    public static Val compare(Val left, Val right) {
        Val v = unknown("compare"); v.cmpLeft = left; v.cmpRight = right; return v;
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
    public static Val array(String elemType, int len) {
        Val v = new Val(Kind.ARRAY); v.typeName = elemType; v.elements = new ArrayList<>();
        for (int i = 0; i < len; i++) v.elements.add(unknown("array slot never stored"));
        return v;
    }

    public boolean isStaticField() { return kind == Kind.STATIC_FIELD; }

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
            case ARRAY: return "array[" + (elements == null ? 0 : elements.size()) + "]";
            case NULL: return "null";
            case THIS: return "this";
            default: return "?(" + reason + ")";
        }
    }
}
