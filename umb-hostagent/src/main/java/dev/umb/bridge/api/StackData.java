package dev.umb.bridge.api;

/** Immutable item snapshot crossing the boundary. legacyId like "hbm:item.plate_iron"; nbt may be null. */
public final class StackData {
    public final String legacyId; public final int count; public final int damage; public final byte[] nbt;
    public StackData(String legacyId, int count, int damage, byte[] nbt) {
        this.legacyId = legacyId; this.count = count; this.damage = damage; this.nbt = nbt; }
    public static final StackData EMPTY = new StackData(null, 0, 0, null);
    public boolean isEmpty() { return legacyId == null || count <= 0; }
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/windows/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
