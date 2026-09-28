package dev.umb.hostagent.content.fluid;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import dev.umb.hostagent.content.LegacyIds;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads fluids independently because the older LegacySnapshot reader predates fluids. */
public final class FluidSnapshotReader {
    private FluidSnapshotReader() {}

    public static List<FluidEntry> load(Path file, String namespace) throws IOException {
        List<FluidEntry> all = loadAll(file);
        List<FluidEntry> owned = new ArrayList<>();
        String ns = LegacyIds.sanitizeNamespace(namespace);
        for (FluidEntry f : all) if (f.belongsTo(ns)) owned.add(f);
        return owned;
    }

    public static List<FluidEntry> loadAll(Path file) throws IOException {
        List<FluidEntry> out = new ArrayList<>();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(r);
            if (root == null || !root.isJsonObject()) return out;
            JsonElement fluids = root.getAsJsonObject().get("fluids");
            if (fluids == null || !fluids.isJsonArray()) return out;
            for (JsonElement e : fluids.getAsJsonArray()) {
                if (e.isJsonObject()) {
                    FluidEntry f = FluidEntry.from(e.getAsJsonObject());
                    if (f != null) out.add(f);
                }
            }
        }
        return out;
    }
}
