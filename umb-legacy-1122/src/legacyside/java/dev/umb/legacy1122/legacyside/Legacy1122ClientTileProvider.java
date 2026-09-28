package dev.umb.legacy1122.legacyside;

import dev.umb.bridge.api.LegacyClientTileBridge;
import java.util.Map;

/** Child-loader-owned coordinate lookup for the 1.12.2 client render handoff. */
public final class Legacy1122ClientTileProvider implements LegacyClientTileBridge.Provider {
    private final Map<String, ?> tiles;

    public Legacy1122ClientTileProvider(Map<String, ?> tiles) { this.tiles = tiles; }

    @Override public Object tileAt(int x, int y, int z) {
        if (tiles == null) return null;
        synchronized (tiles) { return tiles.get(key(x, y, z)); }
    }

    @Override public String diagnose(String requestedDimension, int x, int y, int z) {
        return "provider=present era=1.12.2 requestedDim=" + String.valueOf(requestedDimension)
                + " requested=" + x + "," + y + "," + z
                + " tile=" + (tileAt(x, y, z) == null ? "miss" : "hit");
    }

    static String key(int x, int y, int z) { return x + "," + y + "," + z; }

    public static void install(Map<String, ?> tiles) {
        LegacyClientTileBridge.install("1.12.2", new Legacy1122ClientTileProvider(tiles));
    }

    public static void uninstall() { LegacyClientTileBridge.install("1.12.2", null); }
}
