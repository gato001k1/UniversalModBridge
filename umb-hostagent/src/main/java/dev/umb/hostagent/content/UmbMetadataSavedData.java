package dev.umb.hostagent.content;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * R3: metadata is a per-level SIDE TABLE (pos -&gt; 1.7.10 damage/meta value), never a blockstate
 * property. 26.2's {@link SavedData} is now just a dirty flag (verified via javap -- no more
 * save(CompoundTag)/load(CompoundTag)); persistence is a companion {@link SavedDataType} record
 * with its own {@link Codec}, obtained through
 * {@code serverLevel.getDataStorage().computeIfAbsent(SavedDataType)}
 * ({@code SavedDataStorage}, also javap-verified).
 *
 * The codec is a plain {@code Codec.STRING.xmap(...)} over a trivial "x,y,z,meta;..." encoding --
 * deliberately avoiding RecordCodecBuilder ceremony for a table this simple. DataFixTypes.LEVEL is
 * used as the fix-type placeholder (26.2 has no generic "no fixers" constant); flagged in
 * g2-laneB-progress.md as a follow-up if a later mapping lane needs real data fixers here.
 */
public final class UmbMetadataSavedData extends SavedData {

    private static final Codec<UmbMetadataSavedData> CODEC =
            Codec.STRING.xmap(UmbMetadataSavedData::fromEncoded, UmbMetadataSavedData::toEncoded);

    public static final SavedDataType<UmbMetadataSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("umb", "legacy_meta"),
            UmbMetadataSavedData::new,
            CODEC,
            DataFixTypes.LEVEL);

    private final Map<Long, Integer> meta = new ConcurrentHashMap<>();

    public UmbMetadataSavedData() {
    }

    private static UmbMetadataSavedData fromEncoded(String s) {
        UmbMetadataSavedData d = new UmbMetadataSavedData();
        if (s == null || s.isEmpty()) return d;
        for (String part : s.split(";")) {
            if (part.isEmpty()) continue;
            String[] f = part.split(",");
            if (f.length != 4) continue;
            try {
                int x = Integer.parseInt(f[0]);
                int y = Integer.parseInt(f[1]);
                int z = Integer.parseInt(f[2]);
                int m = Integer.parseInt(f[3]);
                d.meta.put(BlockPos.asLong(x, y, z), m);
            } catch (NumberFormatException ignored) {
                // one corrupt entry must not sink the whole side table
            }
        }
        return d;
    }

    private static String toEncoded(UmbMetadataSavedData d) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Integer> e : d.meta.entrySet()) {
            long packed = e.getKey();
            sb.append(BlockPos.getX(packed)).append(',')
                    .append(BlockPos.getY(packed)).append(',')
                    .append(BlockPos.getZ(packed)).append(',')
                    .append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    public int getMeta(int x, int y, int z) {
        Integer v = meta.get(BlockPos.asLong(x, y, z));
        return v != null ? v : 0;
    }

    public void setMeta(int x, int y, int z, int m) {
        meta.put(BlockPos.asLong(x, y, z), m);
        setDirty();
    }

    public void clearMeta(int x, int y, int z) {
        if (meta.remove(BlockPos.asLong(x, y, z)) != null) setDirty();
    }

    public int size() {
        return meta.size();
    }

    public static UmbMetadataSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }
}
