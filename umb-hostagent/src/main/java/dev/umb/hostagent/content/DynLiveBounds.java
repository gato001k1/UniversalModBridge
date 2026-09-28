package dev.umb.hostagent.content;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.umb.hostagent.AgentLog;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Door-live lane: the set of block ids whose collision bounds genuinely follow tile-entity
 * state (doors, hatches), read from the extract-time sidecar's per-block
 * {@code liveBounds} flags (see {@code LiveBoundsDetector} in umb-rendermap - the detector
 * fires only on reads of tile-entity-typed values inside bounds methods, never on pure
 * positional math). {@link UmbLegacyBlock#getCollisionShape} queries live bounds through
 * the bridge ONLY for these blocks, only on the server thread, and only when the cell's
 * own block entity is live - every other block keeps its statically extracted shape
 * byte-for-byte, so a wrong or stale entry can only ever affect a door-like block.
 * Tolerant-absent like {@link DynFieldChannel}: no sidecar means no live bounds anywhere.
 */
final class DynLiveBounds {

    private static volatile Set<String> BLOCKS;

    private DynLiveBounds() {
    }

    /** True when this block id may need live collision bounds. Never throws. */
    static boolean isLiveBounds(String blockId) {
        if (blockId == null) return false;
        try {
            Set<String> cached = BLOCKS;
            if (cached == null) {
                cached = read(DynFieldChannel.sidecarPath());
                BLOCKS = cached;
            }
            return cached.contains(blockId);
        } catch (Throwable t) {
            return false;
        }
    }

    /** For tests only. */
    static void resetForTests() {
        BLOCKS = null;
    }

    /** For tests only: point the source at a scratch sidecar. */
    static void setSidecarForTests(Path sidecar) {
        BLOCKS = read(sidecar);
    }

    static Set<String> read(Path sidecar) {
        if (sidecar == null || !Files.isRegularFile(sidecar)) return Collections.emptySet();
        try {
            String text = Files.readString(sidecar, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            JsonObject blocks = root.has("blocks") && root.get("blocks").isJsonObject()
                    ? root.getAsJsonObject("blocks") : new JsonObject();
            Set<String> out = new LinkedHashSet<>();
            for (Map.Entry<String, JsonElement> e : blocks.entrySet()) {
                if (e.getValue() != null && e.getValue().isJsonObject()
                        && e.getValue().getAsJsonObject().has("liveBounds")
                        && !e.getValue().getAsJsonObject().get("liveBounds").isJsonNull()
                        && e.getValue().getAsJsonObject().get("liveBounds").getAsBoolean()) {
                    out.add(e.getKey());
                }
            }
            return Collections.unmodifiableSet(out);
        } catch (Throwable t) {
            AgentLog.error("DynLiveBounds.read " + sidecar, t, 2);
            return Collections.emptySet();
        }
    }
}
