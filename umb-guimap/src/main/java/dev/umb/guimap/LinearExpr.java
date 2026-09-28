package dev.umb.guimap;

/**
 * A symbolic affine/multiplicative expression over exactly ONE runtime field, matching the general
 * 1.7.10 progress-bar/gauge idiom seen throughout Forge mods (confirmed against the real HBM jar
 * with {@code javap}, see {@code research/out/legacy/guimap-notes/DYNAMIC-RECTS.md}):
 *
 * <pre>
 *   this.heat * 24 / this.heatMax                 // TileEntityMachineRTG.getHeatScaled(int)
 *   this.power * scale / 100000L                  // TileEntityMachineRTG.getPowerScaled(long)
 *   this.someTE.progress                          // bare field, degenerate case (mulNum=mulDen=1)
 * </pre>
 *
 * The value this describes is:
 * <pre>
 *   result = base ± (field * mulNum / mulDen)              -- when divisorField == null
 *   result = base ± (field * mulNum / divisorField)        -- when divisorField != null (mulDen ignored)
 * </pre>
 * where {@code base} is either a plain integer constant ({@link #hasPanelBase} == false) or a
 * panel-relative quantity ({@link #panelAxis} + {@link #panelDelta}) — the extremely common
 * {@code guiTop + 61 - amt} shape used to grow a bar from a fixed anchor. {@link #clampMax}, when
 * non-null, records that the whole field term was wrapped in {@code Math.min(expr, clampMax)}.
 *
 * <p>Every field below is immutable; the {@code with*} methods return a new instance (or the
 * receiver's declaring construction fails to apply and {@code null} is returned) so a builder chain
 * never silently overwrites information MethodSim already committed to.
 */
public final class LinearExpr {

    // ---- the single runtime field driving this expression ----
    public final String fieldOwner;
    public final String fieldName;
    public final String fieldDesc;
    public final Val fieldReceiver; // the object `fieldOwner.fieldName` was read off; null if statically owned

    // ---- multiplier / divisor ----
    public final double mulNum;
    public final double mulDen;           // used only when divisorField == null
    public final String divOwner;         // non-null together with divName/divReceiver: divisor is itself a field
    public final String divName;
    public final Val divReceiver;
    /** JVM descriptor of the divisor field (e.g. {@code "I"}), when {@link #hasFieldDivisor()} —
     *  TILE-FIELD-REQUIREMENTS lane addition, threaded alongside divOwner/divName/divReceiver so a
     *  consumer can report the divisor's type without re-deriving it. Null otherwise. */
    public final String divDesc;

    // ---- sign of the field term relative to `base` ----
    public final int sign; // +1 or -1

    // ---- base ----
    public final boolean hasPanelBase;
    public final String panelAxis;  // "guiLeft" | "guiTop", only when hasPanelBase
    public final int panelDelta;    // constant folded alongside the panel axis
    public final int constOffset;   // plain constant base/offset (meaningful even when hasPanelBase)

    // ---- optional Math.min/Math.max clamp over the whole field term ----
    public final Double clampMax;

    /**
     * GUI-fidelity lane: optional floor on a FIELD divisor, from the {@code A*K/max(FIELD, C)}
     * divide-by-guarded-maximum idiom (furnace progress/burnTime arrows: {@code Math.max} is a
     * vanilla-JDK call with fixed semantics, so recognising it is evidence, not guessing). Null
     * means a plain field divisor. The host evaluates {@code max(divFloor, divisor)} before the
     * divide-by-zero rule.
     */
    public final Double divFloor;

    /**
     * GUI-fidelity lane: the whole field term passed through {@code Math.ceil} (assembler
     * {@code (int)Math.ceil(70.0*progress)} arrow width). False by default.
     */
    public final boolean ceilResult;

    private LinearExpr(String fieldOwner, String fieldName, String fieldDesc, Val fieldReceiver,
                        double mulNum, double mulDen, String divOwner, String divName, Val divReceiver,
                        String divDesc, int sign, boolean hasPanelBase, String panelAxis, int panelDelta,
                        int constOffset, Double clampMax, Double divFloor, boolean ceilResult) {
        this.fieldOwner = fieldOwner; this.fieldName = fieldName; this.fieldDesc = fieldDesc;
        this.fieldReceiver = fieldReceiver;
        this.mulNum = mulNum; this.mulDen = mulDen;
        this.divOwner = divOwner; this.divName = divName; this.divReceiver = divReceiver; this.divDesc = divDesc;
        this.sign = sign;
        this.hasPanelBase = hasPanelBase; this.panelAxis = panelAxis; this.panelDelta = panelDelta;
        this.constOffset = constOffset;
        this.clampMax = clampMax;
        this.divFloor = divFloor;
        this.ceilResult = ceilResult;
    }

    public boolean hasFieldDivisor() { return divOwner != null; }

    /** The trivial expression {@code field} (multiplier 1, divisor 1, no offset). */
    public static LinearExpr baseOf(Val getfieldVal) {
        return new LinearExpr(getfieldVal.owner, getfieldVal.name, getfieldVal.desc, getfieldVal.receiver,
                1, 1, null, null, null, null, 1, false, null, 0, 0, null, null, false);
    }

    /** {@code this * c} — always legal (scales the numerator, whether or not a divisor is already set). */
    public LinearExpr withMul(double c) {
        return new LinearExpr(fieldOwner, fieldName, fieldDesc, fieldReceiver, mulNum * c, mulDen,
                divOwner, divName, divReceiver, divDesc, sign, hasPanelBase, panelAxis, panelDelta, constOffset, clampMax, divFloor, ceilResult);
    }

    /** {@code this / c} — null (give up, fall back to a generic "arithmetic" unknown) if a divisor
     *  is already fixed (const or field): two divisions is exotic enough not to guess about. */
    public LinearExpr withDiv(double c) {
        if (hasFieldDivisor() || mulDen != 1) return null;
        return new LinearExpr(fieldOwner, fieldName, fieldDesc, fieldReceiver, mulNum, c,
                divOwner, divName, divReceiver, divDesc, sign, hasPanelBase, panelAxis, panelDelta, constOffset, clampMax, divFloor, ceilResult);
    }

    /** {@code this / anotherField} — null if a divisor is already fixed. */
    public LinearExpr withDivField(Val divField) {
        if (hasFieldDivisor() || mulDen != 1) return null;
        return new LinearExpr(fieldOwner, fieldName, fieldDesc, fieldReceiver, mulNum, mulDen,
                divField.owner, divField.name, divField.receiver, divField.desc, sign, hasPanelBase, panelAxis, panelDelta, constOffset, clampMax, divFloor, ceilResult);
    }

    /**
     * GUI-fidelity lane: floors a field divisor at a constant ({@code this / max(field, c)}).
     * Null when no field divisor is fixed yet, or a floor is already recorded — a bare or
     * differently-guarded divisor keeps its existing meaning.
     */
    public LinearExpr withDivFloor(double c) {
        if (!hasFieldDivisor() || divFloor != null) return null;
        return new LinearExpr(fieldOwner, fieldName, fieldDesc, fieldReceiver, mulNum, mulDen,
                divOwner, divName, divReceiver, divDesc, sign, hasPanelBase, panelAxis, panelDelta, constOffset, clampMax, Double.valueOf(c), ceilResult);
    }

    /** {@code this + delta} (or {@code this - delta} for a negative delta) — a plain constant offset. */
    public LinearExpr withOffset(int delta) {
        return new LinearExpr(fieldOwner, fieldName, fieldDesc, fieldReceiver, mulNum, mulDen,
                divOwner, divName, divReceiver, divDesc, sign, hasPanelBase, panelAxis, panelDelta, constOffset + delta, clampMax, divFloor, ceilResult);
    }

    /** Attaches a {@code guiLeft}/{@code guiTop}-relative base — null if one is already attached
     *  (mixing two panel axes into one expression is not a shape this module models). */
    public LinearExpr withPanelBase(String axis, int delta, int fieldSign) {
        if (hasPanelBase) return null;
        return new LinearExpr(fieldOwner, fieldName, fieldDesc, fieldReceiver, mulNum, mulDen,
                divOwner, divName, divReceiver, divDesc, fieldSign, true, axis, delta, constOffset, clampMax, divFloor, ceilResult);
    }

    /**
     * Sets a plain-constant base together with the field term's sign in one step — needed for the
     * {@code CONST - field} shape (e.g. {@code 10 + (51 - amt)}, seen combined with a bar-height
     * term in the real HBM jar), where {@code withOffset} alone can't express "subtract the field
     * from the constant" since it leaves {@link #sign} untouched. Null (don't clobber) once a base
     * or sign is already recorded — this is only safe to apply to a still-fresh expression.
     */
    public LinearExpr withConstBase(int c, int fieldSign) {
        if (hasPanelBase || constOffset != 0 || sign != 1) return null;
        return new LinearExpr(fieldOwner, fieldName, fieldDesc, fieldReceiver, mulNum, mulDen,
                divOwner, divName, divReceiver, divDesc, fieldSign, false, null, 0, c, clampMax, divFloor, ceilResult);
    }

    /** Wraps the field term in {@code Math.min(term, c)} / {@code Math.max(term, c)} — null if a
     *  clamp is already recorded. */
    public LinearExpr withClamp(double c) {
        if (clampMax != null) return null;
        return new LinearExpr(fieldOwner, fieldName, fieldDesc, fieldReceiver, mulNum, mulDen,
                divOwner, divName, divReceiver, divDesc, sign, hasPanelBase, panelAxis, panelDelta, constOffset, c, divFloor, ceilResult);
    }

    /**
     * GUI-fidelity lane: applies {@code Math.ceil} to the whole field term
     * ({@code (int)Math.ceil(K*progress)} progress-arrow idiom). Null once already applied.
     */
    public LinearExpr withCeil() {
        if (ceilResult) return null;
        return new LinearExpr(fieldOwner, fieldName, fieldDesc, fieldReceiver, mulNum, mulDen,
                divOwner, divName, divReceiver, divDesc, sign, hasPanelBase, panelAxis, panelDelta, constOffset, clampMax, divFloor, true);
    }

    @Override public String toString() {
        StringBuilder sb = new StringBuilder();
        if (hasPanelBase) {
            sb.append(panelAxis).append(panelDelta >= 0 ? "+" + panelDelta : String.valueOf(panelDelta));
            sb.append(sign < 0 ? " - " : " + ");
        } else if (sign < 0) {
            // prefix form: "61 - field..." (the CONST - field shape) — constOffset==0 here still
            // reads sensibly as "0 - field...".
            sb.append(constOffset).append(" - ");
        }
        sb.append(fieldOwner).append('.').append(fieldName);
        if (mulNum != 1) sb.append(" * ").append(mulNum);
        if (hasFieldDivisor()) {
            sb.append(" / ").append(divOwner).append('.').append(divName);
            if (divFloor != null) sb.append(" floored at ").append(divFloor);
        } else if (mulDen != 1) sb.append(" / ").append(mulDen);
        if (!hasPanelBase && sign >= 0 && constOffset != 0) {
            // suffix form: "field... + 10" / "field... - 10" (the field +/- CONST shape).
            sb.append(constOffset > 0 ? " + " : " - ").append(Math.abs(constOffset));
        }
        if (clampMax != null) sb.insert(0, "min(").append(", " + clampMax + ")");
        if (ceilResult) sb.insert(0, "ceil(").append(")");
        return sb.toString();
    }
}
