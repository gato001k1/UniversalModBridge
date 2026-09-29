package dev.umb.objbridge.entity;

import com.mojang.blaze3d.platform.NativeImage;
import dev.umb.objbridge.ObjBridge;
import dev.umb.objbridge.ObjBridgeManifest;
import dev.umb.objbridge.obj.ObjAssets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves captured legacy texture identifiers from the manifest's extracted mod assets. */
public final class LegacyCaptureTextureResolver {
    private static final Set<Identifier> REGISTERED = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> REPORTED = ConcurrentHashMap.newKeySet();
    private static final Set<Identifier> MISSING_REPORTED = ConcurrentHashMap.newKeySet();
    private static final Map<Identifier, String> MISSING = new ConcurrentHashMap<>();
    private static final Map<Identifier, Long> RETRY_AFTER_NANOS = new ConcurrentHashMap<>();

    private LegacyCaptureTextureResolver() { }

    public static void ensure(Identifier id) {
        if (id == null || "minecraft".equals(id.getNamespace()) || REGISTERED.contains(id)) return;
        Path source = source(id);
        String sourceKey = source == null ? null : source.toAbsolutePath().normalize().toString();
        String missingKey = MISSING.get(id);
        // A client-facade bind can happen before ObjBridge's manifest is populated.  Do not
        // permanently poison the identifier in that window: the same captured texture must be
        // retried once the mod asset roots become visible.
        if (missingKey != null && sourceKey != null && sourceKey.equals(missingKey)) return;
        if (missingKey != null) MISSING.remove(id, missingKey);
        Long retryAfter = RETRY_AFTER_NANOS.get(id);
        if (retryAfter != null && System.nanoTime() < retryAfter.longValue()) return;
        if ((source == null || !Files.isRegularFile(source)) && uploadFromAssetJars(id)) return;
        if (source == null || !Files.isRegularFile(source)) {
            String path = sourceKey == null ? "asset-root-unavailable" : sourceKey;
            if (source != null) MISSING.putIfAbsent(id, path);
            if (MISSING_REPORTED.add(id)) {
                System.out.println("[UMB-OBJBRIDGE] [CAPTURE-TEXTURE] id=" + id
                        + " missing-source=" + path);
            }
            return;
        }
        try (InputStream in = Files.newInputStream(source)) {
            NativeImage image = NativeImage.read(in);
            DynamicTexture texture = new DynamicTexture(() -> "umb-capture " + id, image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            REGISTERED.add(id);
            if (REPORTED.add(id)) {
                System.out.println("[UMB-OBJBRIDGE] [CAPTURE-TEXTURE] id=" + id
                        + " resolved=" + source.toAbsolutePath() + " upload=dynamic");
            }
        } catch (Throwable failure) {
            // Upload failures can be transient while the host texture manager is being rebound;
            // retry at a bounded cadence instead of making a one-frame failure permanent.
            RETRY_AFTER_NANOS.put(id, System.nanoTime() + 1_000_000_000L);
            if (REPORTED.add(id)) {
                System.out.println("[UMB-OBJBRIDGE] [CAPTURE-TEXTURE] id=" + id
                        + " resolved=" + source.toAbsolutePath() + " upload=failed reason="
                        + reason(failure));
            }
        }
    }

    /**
     * Mod jars whose assets captured draws may name, published by universes that are not in
     * the ObjBridge manifest (isolated eras: their mod jars are never extracted into an asset
     * root). {@link java.io.File#pathSeparator}-separated jar paths; append-only.
     */
    public static final String ASSET_JARS_PROPERTY = "umb.capture.assetJars";

    /** Entry name of an identifier's image inside a mod jar: {@code assets/<ns>/<path>.png}. */
    static String jarEntry(Identifier id) {
        String path = id.getPath();
        if (!path.toLowerCase(Locale.ROOT).endsWith(".png")) path += ".png";
        return "assets/" + id.getNamespace() + "/" + path;
    }

    /** Reads the image bytes for {@code id} from the published asset jars, or null. */
    /** id -> the jar list it was last missing from (a newly published jar retries it). */
    private static final Map<Identifier, String> JAR_MISSES = new ConcurrentHashMap<>();

    static byte[] readFromAssetJars(Identifier id) {
        String jars = System.getProperty(ASSET_JARS_PROPERTY, "");
        if (jars.isEmpty() || jars.equals(JAR_MISSES.get(id))) return null;
        byte[] found = scanAssetJars(id, jars);
        if (found == null) JAR_MISSES.put(id, jars);
        return found;
    }

    private static byte[] scanAssetJars(Identifier id, String jars) {
        String entryName = jarEntry(id);
        for (String part : jars.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            String jar = part.trim();
            if (jar.isEmpty()) continue;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jar)) {
                java.util.zip.ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) continue;
                try (InputStream in = zip.getInputStream(entry)) {
                    return in.readAllBytes();
                }
            } catch (Throwable ignored) {
                // A missing/unreadable jar never blocks the other roots.
            }
        }
        return null;
    }

    private static boolean uploadFromAssetJars(Identifier id) {
        byte[] bytes = readFromAssetJars(id);
        if (bytes == null) return false;
        try (InputStream in = new java.io.ByteArrayInputStream(bytes)) {
            NativeImage image = NativeImage.read(in);
            DynamicTexture texture = new DynamicTexture(() -> "umb-capture " + id, image);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            REGISTERED.add(id);
            if (REPORTED.add(id)) {
                System.out.println("[UMB-OBJBRIDGE] [CAPTURE-TEXTURE] id=" + id
                        + " resolved=jar:" + jarEntry(id) + " upload=dynamic");
            }
            return true;
        } catch (Throwable failure) {
            RETRY_AFTER_NANOS.put(id, System.nanoTime() + 1_000_000_000L);
            if (REPORTED.add(id)) {
                System.out.println("[UMB-OBJBRIDGE] [CAPTURE-TEXTURE] id=" + id
                        + " resolved=jar:" + jarEntry(id) + " upload=failed reason=" + reason(failure));
            }
            return true;
        }
    }

    private static Path source(Identifier id) {
        String asset = assetPath(id);
        for (ObjBridgeManifest.Loaded mod : ObjBridge.loadedMods()) {
            if (mod == null || mod.mod() == null || !id.getNamespace().equals(mod.mod().namespace())) continue;
            return ObjAssets.resolve(mod.mod().assetsRoot(), asset);
        }
        // Keep a generic fallback for the short startup interval in which the loaded-mod list is
        // not published yet but the aggregate asset root is already known.
        Path root = ObjBridge.assetsRoot();
        return root == null ? null : ObjAssets.resolve(root, asset);
    }

    /** Legacy TextureManager binds already include the image suffix; model-side callers may not. */
    static String assetPath(Identifier id) {
        String path = id.getPath();
        if (!path.toLowerCase(Locale.ROOT).endsWith(".png")) path += ".png";
        return id.getNamespace() + ":" + path;
    }

    private static String reason(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String message = root.getMessage();
        return root.getClass().getName() + (message == null ? "" : ":" + message);
    }
}
