package dev.umb.hostagent.content;

import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.TileFieldSnapshot;
import dev.umb.bridge.api.TileHandle;
import dev.umb.hostagent.AgentLog;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TILE-FIELD-SNAPSHOT lane: the server-tick-to-client-frame side channel for a GUI's
 * {@link GuiProfile.TileFieldRef} values, mirroring {@link UmbMenuRegistration#LAYOUTS}'s own
 * "plain in-process Map, not a real network packet" shortcut — the embedded server and its client
 * share one JVM here, exactly like the rects/labels/textures side channel already does. The SERVER
 * side refreshes exactly one entry per open GUI, at most once per server TICK, from a real
 * {@link TileHandle#snapshotFields} call (see {@code UmbLegacyMenu#broadcastChanges}); the CLIENT
 * side ({@link UmbLegacyScreen}) reads the SAME entry at most once per rendered FRAME — a plain
 * memory read, never a cross-loader call from the render thread (see the correctness-risk note on
 * {@code UmbLegacyScreen} about never reading legacy state off the render thread).
 */
final class TileSnapshotChannel {
    private static final Map<Integer, TileFieldSnapshot> SNAPSHOTS = new ConcurrentHashMap<>();

    private TileSnapshotChannel() {
    }

    /** Builds the {@code FieldPath[]} request once per menu-open (never per tick) from a GUI's own
     *  deduplicated field list — see {@link GuiProfile.GuiEntry#tileFieldRefs}. */
    static FieldPath[] buildRequest(List<GuiProfile.TileFieldRef> refs) {
        FieldPath[] out = new FieldPath[refs.size()];
        for (int i = 0; i < refs.size(); i++) {
            GuiProfile.TileFieldRef r = refs.get(i);
            out[i] = new FieldPath(r.key, r.hopNames, r.hopKinds);
        }
        return out;
    }

    /**
     * Called on the SERVER thread, at most once per server tick per open GUI — never from the
     * render thread. A poisoned/removed/never-created tile (handle==null, or {@code isValid()}
     * false) publishes an EMPTY snapshot rather than leaving a stale one in place, so a client that
     * reads it after the tile is gone sees every field absent (skip, never a frozen last-known
     * value) — see {@link UmbLegacyScreen}'s own "never fake a value" rule. A no-op when this GUI
     * needs no tile fields at all (the common case — most GUIs' rects need nothing beyond
     * CONST/PANEL_RELATIVE), so the per-tick cost is zero for them.
     */
    static void refresh(int containerId, TileHandle handle, FieldPath[] request) {
        if (handle == null || request.length == 0) {
            return;
        }
        try {
            TileFieldSnapshot s = handle.isValid() ? handle.snapshotFields(request) : TileFieldSnapshot.EMPTY;
            SNAPSHOTS.put(containerId, s);
        } catch (Throwable t) {
            AgentLog.errorOnce("TileSnapshotChannel.refresh:" + containerId, t, 2);
            SNAPSHOTS.put(containerId, TileFieldSnapshot.EMPTY);
        }
    }

    /** Called on the CLIENT/render thread — a plain map read, no bridge call, never null. */
    static TileFieldSnapshot get(int containerId) {
        TileFieldSnapshot s = SNAPSHOTS.get(containerId);
        return s != null ? s : TileFieldSnapshot.EMPTY;
    }

    /** Called once when a menu closes ({@code UmbLegacyMenu#removed}) — a stale entry under a reused
     *  containerId must never be served to a DIFFERENT GUI that happens to reuse the same id. */
    static void clear(int containerId) {
        SNAPSHOTS.remove(containerId);
    }

    /** For tests only. */
    static void resetForTests() {
        SNAPSHOTS.clear();
    }
}
