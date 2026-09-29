package dev.umb.bridge.api;

/**
 * Result of {@code TileHandle#snapshotFields}, parallel to the requested {@link FieldPath}s by
 * index (not by name lookup - {@link #keys} carries the correlation). {@code present[i]==false}
 * means that path could not be resolved on THIS call (the tile is gone, an intermediate reference
 * was null, the field/accessor no longer exists) - the value at {@code values[i]} is then
 * meaningless and MUST be treated as absent, never as zero: a frozen/zero gauge reads as working
 * software that lies, which is strictly worse than one that is simply not drawn.
 */
public final class TileFieldSnapshot {
    public final String[] keys;
    public final double[] values;
    public final boolean[] present;

    public TileFieldSnapshot(String[] keys, double[] values, boolean[] present) {
        this.keys = keys;
        this.values = values;
        this.present = present;
    }

    public static final TileFieldSnapshot EMPTY =
            new TileFieldSnapshot(new String[0], new double[0], new boolean[0]);
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/windows/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
