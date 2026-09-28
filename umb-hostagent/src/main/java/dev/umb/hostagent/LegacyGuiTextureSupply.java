package dev.umb.hostagent;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import com.mojang.blaze3d.platform.NativeImage;

/**
 * Loads legacy-side GUI textures that no 26.2 pack provides: per-era vanilla GUI sheets and
 * stitched legacy atlases, both exported as PNGs under the legacy game dir and referenced through
 * private namespaces (see {@link LegacyGuiTextureResolver}). Render-thread only (GPU upload
 * happens in the {@link DynamicTexture} constructor); every miss is cooldown-cached and every
 * failure degrades to the painter's color fill. Universal across eras: the namespace and the
 * file layout both carry the era tag, and no mod or era name appears here.
 */
final class LegacyGuiTextureSupply {
    private static final long MISSING_RETRY_NANOS = 5_000_000_000L;
    private static final long STALE_SLACK_MILLIS = 60_000L;
    private static final Map<Identifier, AbstractTexture> REGISTERED = new ConcurrentHashMap<>();
    private static final Map<Identifier, Long> MISSING_UNTIL = new ConcurrentHashMap<>();

    private LegacyGuiTextureSupply() {
    }

    /**
     * Makes {@code id} paintable, registering it on first use. Generated-pack and vanilla
     * 26.2 ids are owned by the host resource system and pass straight through; only the
     * private legacy namespaces ({@code umbvanilla*} file exports,
     * {@code umbatlas} stitch exports) load here. Returns false (use the color fallback)
     * when the export is absent, stale, or unreadable.
     */
    static boolean ensure(Identifier id) {
        if (id == null) return false;
        String ns = id.getNamespace();
        if (!ns.startsWith("umbvanilla") && !"umbatlas".equals(ns)) return true;
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.getTextureManager() == null) return false;
            AbstractTexture have = REGISTERED.get(id);
            if (have != null) {
                // Survive resource reloads: re-register if ours is gone.
                try {
                    if (mc.getTextureManager().getTexture(id) == have) return true;
                } catch (Throwable ignored) {
                    // Fall through to a fresh registration attempt below.
                }
                REGISTERED.remove(id, have);
            }
            long now = System.nanoTime();
            Long until = MISSING_UNTIL.get(id);
            if (until != null && now < until.longValue()) return false;
            Path file = fileFor(id);
            if (file == null || !Files.isRegularFile(file) || !freshEnough(id, file)) {
                MISSING_UNTIL.put(id, now + MISSING_RETRY_NANOS);
                return false;
            }
            try (InputStream in = Files.newInputStream(file)) {
                NativeImage image = NativeImage.read(in);
                DynamicTexture texture = new DynamicTexture(() -> "umb-gui", image);
                mc.getTextureManager().register(id, texture);
                REGISTERED.put(id, texture);
                MISSING_UNTIL.remove(id);
                AgentLog.line("[UMB-GUI] host texture registered id=" + id);
                return true;
            }
        } catch (Throwable failure) {
            MISSING_UNTIL.put(id, System.nanoTime() + MISSING_RETRY_NANOS);
            AgentLog.errorOnce("umb-gui-texture:" + id, failure, 2);
            return false;
        }
    }

    private static Path fileFor(Identifier id) {
        try {
            // Both sides must agree byte-for-byte: the legacy side writes under its own
            // umb.legacy.gameDir (the <game>/legacy/ subtree, same JVM, same property).
            // Falling back to <host gameDir>/legacy keeps scratch layouts working.
            String legacyGameDir = null;
            try {
                legacyGameDir = System.getProperty("umb.legacy.gameDir");
            } catch (Throwable ignored) {
                // Fall through to the game-directory fallback below.
            }
            Path base = null;
            if (legacyGameDir != null && !legacyGameDir.isEmpty()) {
                base = Paths.get(legacyGameDir);
            } else {
                Minecraft mc = Minecraft.getInstance();
                if (mc != null && mc.gameDirectory != null) {
                    base = mc.gameDirectory.toPath().resolve("legacy");
                }
            }
            if (base == null) return null;
            return base.resolve(id.getNamespace()).resolve(id.getPath());
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Atlas exports are rewritten every legacy session; a file older than this JVM (with
     * slack) belongs to a previous session's mod set and must not be served. Vanilla sheets
     * are static pixels and never go stale.
     */
    private static boolean freshEnough(Identifier id, Path file) {
        try {
            if (!"umbatlas".equals(id.getNamespace())) return true;
            long modified = Files.getLastModifiedTime(file).toMillis();
            long boot = java.lang.management.ManagementFactory.getRuntimeMXBean().getStartTime();
            return modified + STALE_SLACK_MILLIS >= boot;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
