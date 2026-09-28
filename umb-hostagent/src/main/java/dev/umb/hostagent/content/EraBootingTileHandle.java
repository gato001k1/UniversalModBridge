package dev.umb.hostagent.content;

import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.TileFieldSnapshot;
import dev.umb.bridge.api.TileHandle;

/**
 * Internal createTile result used while an owned era is booting.  It is intentionally not a
 * usable tile: UmbLegacyBlockEntity recognizes it, queues itself, and retries after the era's
 * boot completion on the server thread.  Returning a distinct object is what prevents the
 * normal "no tile for this metadata" result from permanently swallowing the first placement.
 */
final class EraBootingTileHandle implements TileHandle {
    final String era;

    EraBootingTileHandle(String era) {
        this.era = era == null ? "unknown" : era;
    }

    @Override public void tick() { }
    @Override public byte[] saveNbt() { return new byte[0]; }
    @Override public void loadNbt(byte[] nbt) { }
    @Override public boolean isValid() { return false; }
    @Override public TileFieldSnapshot snapshotFields(FieldPath[] paths) {
        return TileFieldSnapshot.EMPTY;
    }
}
