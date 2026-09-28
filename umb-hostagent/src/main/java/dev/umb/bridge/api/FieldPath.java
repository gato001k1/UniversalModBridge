package dev.umb.bridge.api;

/**
 * TILE-FIELD-SNAPSHOT: one field to read off a live legacy object (a tile entity today - the walk
 * is generic over "whatever object the handle wraps") for a GUI's gauge/guard snapshot. A short,
 * bounded chain of hops starting at that object itself: a plain field read ("field") or a zero-arg
 * accessor call ("accessor") at each step. {@code TileHandle#snapshotFields} stops the instant one
 * hop fails (a missing field/method, a null intermediate reference) and reports that ONE path
 * absent in the result - it never walks further, and never guesses.
 */
public final class FieldPath {
    /** Caller's own correlation id, echoed back in {@link TileFieldSnapshot#keys} at the same index. */
    public final String key;
    /** Field name (kind "field") or zero-arg method name (kind "accessor") at each hop, in order. */
    public final String[] hopNames;
    /** "field" | "accessor", parallel to {@link #hopNames}. */
    public final String[] hopKinds;

    public FieldPath(String key, String[] hopNames, String[] hopKinds) {
        this.key = key;
        this.hopNames = hopNames;
        this.hopKinds = hopKinds;
    }
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
