package dev.umb.objbridge.itemeffects;

import com.google.gson.JsonObject;
import dev.umb.objbridge.ObjBridge;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Runtime lookup for the manifest's per-mod held-item sidecars. */
public final class HeldItemRuntime {
    private static volatile Map<String, HeldItemState.ItemState> ITEMS = Map.of();
    private static volatile boolean loaded;
    private HeldItemRuntime() { }

    public static void loadFromManifest() {
        if (loaded) return;
        synchronized (HeldItemRuntime.class) {
            if (loaded) return;
            Map<String, HeldItemState.ItemState> merged = new ConcurrentHashMap<>();
            for (var mod : ObjBridge.loadedMods()) {
                Path map = mod.mod().renderMapPath();
                Path sidecar = map == null ? null : map.resolveSibling("held-item-states.json");
                try { merged.putAll(HeldItemState.read(sidecar).items()); }
                catch (IOException ignored) { }
            }
            ITEMS = Map.copyOf(merged);
            loaded = true;
        }
    }

    public static boolean hasHeldData(String itemId) { loadFromManifest(); return ITEMS.containsKey(itemId); }
    public static HeldItemState.ItemState state(String itemId) { loadFromManifest(); return ITEMS.get(itemId); }
    public static boolean isHeldContext(ItemDisplayContext context) {
        return context == ItemDisplayContext.FIRST_PERSON_RIGHT_HAND
                || context == ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                || context == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                || context == ItemDisplayContext.GUI
                || context == ItemDisplayContext.GROUND
                || context == ItemDisplayContext.FIXED;
    }
    public static HeldItemAnimationSource animations() { return HeldItemAnimationRegistry.instance(); }
}
