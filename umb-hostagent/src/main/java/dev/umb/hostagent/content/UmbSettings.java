package dev.umb.hostagent.content;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.umb.hostagent.AgentLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/** Universal UMB settings.  There are deliberately no namespace or mod-specific keys. */
public final class UmbSettings {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Object LOCK = new Object();
    private static final Map<String, Boolean> ERA_ENABLED = new LinkedHashMap<>();
    private static boolean loaded;

    private static int animationDistance = 32;
    private static boolean farMachineThrottle = true;
    private static boolean cacheEnabled = true;
    private static long captureCacheBytes = 256L * 1024L * 1024L;
    private static boolean showHitboxes;
    private static boolean crosshairInspector;
    private static boolean perfHud;
    private static String lastError = "";

    private UmbSettings() {
    }

    private static Path path() {
        String game = System.getProperty("umb.legacy.gameDir");
        if (game == null || game.isBlank()) game = System.getProperty("user.dir", ".");
        return Paths.get(game).resolve("config").resolve("umb.json");
    }

    private static void ensureLoaded() {
        synchronized (LOCK) {
            if (loaded) return;
            loaded = true;
            Path file = path();
            try {
                if (!Files.isRegularFile(file)) return;
                JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                        .getAsJsonObject();
                if (root.has("animationDistance")) animationDistance = root.get("animationDistance").getAsInt();
                if (root.has("farMachineThrottle")) farMachineThrottle = root.get("farMachineThrottle").getAsBoolean();
                if (root.has("cacheEnabled")) cacheEnabled = root.get("cacheEnabled").getAsBoolean();
                if (root.has("captureCacheBytes")) captureCacheBytes = clampCaptureCacheBytes(root.get("captureCacheBytes").getAsLong());
                if (root.has("showHitboxes")) showHitboxes = root.get("showHitboxes").getAsBoolean();
                if (root.has("crosshairInspector")) crosshairInspector = root.get("crosshairInspector").getAsBoolean();
                if (root.has("perfHud")) perfHud = root.get("perfHud").getAsBoolean();
                if (root.has("lastError")) lastError = root.get("lastError").getAsString();
                if (root.has("eras") && root.get("eras").isJsonObject()) {
                    for (Map.Entry<String, com.google.gson.JsonElement> e : root.getAsJsonObject("eras").entrySet())
                        ERA_ENABLED.put(e.getKey(), e.getValue().getAsBoolean());
                }
            } catch (Throwable t) {
                AgentLog.error("UmbSettings.load " + file, t, 2);
            }
        }
    }

    public static int animationDistance() { ensureLoaded(); synchronized (LOCK) { return animationDistance; } }
    public static boolean farMachineThrottle() { ensureLoaded(); synchronized (LOCK) { return farMachineThrottle; } }
    public static boolean cacheEnabled() { ensureLoaded(); synchronized (LOCK) { return cacheEnabled; } }
    public static long captureCacheBytes() { ensureLoaded(); synchronized (LOCK) { return captureCacheBytes; } }
    public static boolean showHitboxes() { ensureLoaded(); synchronized (LOCK) { return showHitboxes; } }
    public static boolean crosshairInspector() { ensureLoaded(); synchronized (LOCK) { return crosshairInspector; } }
    public static boolean perfHud() { ensureLoaded(); synchronized (LOCK) { return perfHud; } }
    public static String lastError() { ensureLoaded(); synchronized (LOCK) { return lastError; } }

    public static boolean eraEnabled(String era) {
        ensureLoaded();
        synchronized (LOCK) { return ERA_ENABLED.getOrDefault(era, true); }
    }

    public static void setAnimationDistance(int value) { ensureLoaded(); synchronized (LOCK) { animationDistance = Math.max(1, Math.min(256, value)); saveLocked(); } }
    public static void setFarMachineThrottle(boolean value) { ensureLoaded(); synchronized (LOCK) { farMachineThrottle = value; saveLocked(); } }
    public static void setCacheEnabled(boolean value) { ensureLoaded(); synchronized (LOCK) { cacheEnabled = value; saveLocked(); } }
    public static void setCaptureCacheBytes(long value) { ensureLoaded(); synchronized (LOCK) { captureCacheBytes = clampCaptureCacheBytes(value); saveLocked(); } }
    public static void setShowHitboxes(boolean value) { ensureLoaded(); synchronized (LOCK) { showHitboxes = value; saveLocked(); } }
    public static void setCrosshairInspector(boolean value) { ensureLoaded(); synchronized (LOCK) { crosshairInspector = value; saveLocked(); } }
    public static void setPerfHud(boolean value) { ensureLoaded(); synchronized (LOCK) { perfHud = value; saveLocked(); } }
    public static void setEraEnabled(String era, boolean value) { ensureLoaded(); synchronized (LOCK) { ERA_ENABLED.put(era, value); saveLocked(); } }

    public static void rememberError(Throwable error) {
        if (error == null) return;
        synchronized (LOCK) { lastError = error.toString(); saveLocked(); }
    }

    private static void saveLocked() {
        loaded = true;
        JsonObject root = new JsonObject();
        root.addProperty("animationDistance", animationDistance);
        root.addProperty("farMachineThrottle", farMachineThrottle);
        root.addProperty("cacheEnabled", cacheEnabled);
        root.addProperty("captureCacheBytes", captureCacheBytes);
        root.addProperty("showHitboxes", showHitboxes);
        root.addProperty("crosshairInspector", crosshairInspector);
        root.addProperty("perfHud", perfHud);
        root.addProperty("lastError", lastError == null ? "" : lastError);
        JsonObject eras = new JsonObject();
        for (Map.Entry<String, Boolean> e : ERA_ENABLED.entrySet()) eras.addProperty(e.getKey(), e.getValue());
        root.add("eras", eras);
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(root) + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            AgentLog.error("UmbSettings.save " + path(), e, 2);
        }
    }

    private static long clampCaptureCacheBytes(long value) {
        return Math.max(16L * 1024L * 1024L, Math.min(1024L * 1024L * 1024L, value));
    }

    static void resetForTests() {
        synchronized (LOCK) {
            loaded = false;
            ERA_ENABLED.clear();
            animationDistance = 32;
            farMachineThrottle = true;
            cacheEnabled = true;
            captureCacheBytes = 256L * 1024L * 1024L;
            showHitboxes = false;
            crosshairInspector = false;
            perfHud = false;
            lastError = "";
        }
    }
}
