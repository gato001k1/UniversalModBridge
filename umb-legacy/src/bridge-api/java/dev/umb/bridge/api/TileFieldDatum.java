package dev.umb.bridge.api;

/**
 * One legacy object's field identity plus its live value, as reported by
 * {@code TileHandle#describeFields} for automation/observation (see the
 * {@code legacy_tile} command). Unlike {@link TileFieldSnapshot} - which carries
 * doubles for renderer/gauge math - this carries the human-readable form, so agents can
 * see enums, strings and object references too.
 *
 * <p>{@code present==false} means the field could not be read on THIS call (or its value
 * was null); {@code value} is then meaningless and MUST be treated as absent, never as a
 * real reading - the same honest-absence rule as {@link TileFieldSnapshot}. Static fields
 * are never reported (this is live object state, not class data).</p>
 */
public final class TileFieldDatum {
    /** Dotted name of the class in the hierarchy that declares this field. */
    public final String owner;
    /** Declared field name. */
    public final String name;
    /** Simple type name ({@code int}, {@code long}, {@code String}, ...). */
    public final String type;
    /** Value rendered for display (arrays expanded, truncated - see implementor bounds). */
    public final String value;
    public final boolean present;

    public TileFieldDatum(String owner, String name, String type, String value, boolean present) {
        this.owner = owner;
        this.name = name;
        this.type = type;
        this.value = value;
        this.present = present;
    }
}
