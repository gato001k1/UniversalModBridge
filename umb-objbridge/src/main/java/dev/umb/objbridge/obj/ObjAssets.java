package dev.umb.objbridge.obj;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Turns a render-map asset path into a file under the extracted asset tree. Pure (no JOML, no
 * net.minecraft) so it is testable and so nothing in the class-verification path of the agent's hooks
 * depends on it.
 */
public final class ObjAssets {

    private ObjAssets() { }

    /**
     * {@code hbm:models/weapons/minigun.obj} -> {@code <root>/assets/hbm/models/weapons/minigun.obj}.
     *
     * <p>269 of the 507 HBM OBJ names contain uppercase letters, and the render map records them
     * verbatim; on a case-insensitive filesystem the direct resolve is enough, but a case-insensitive
     * fallback scan of the parent directory is done anyway so the same jar works on Linux.
     * Returns the direct (possibly non-existent) path when nothing matches, so callers can log it.
     */
    public static Path resolve(Path root, String assetPath) {
        if (root == null || assetPath == null || assetPath.isEmpty()) return null;
        String ns = "minecraft", bare = assetPath;
        int colon = assetPath.indexOf(':');
        if (colon > 0) {
            ns = assetPath.substring(0, colon);
            bare = assetPath.substring(colon + 1);
        }
        Path direct = root.resolve("assets/" + ns + "/" + bare);
        if (Files.isRegularFile(direct)) return direct;
        Path parent = direct.getParent();
        Path name = direct.getFileName();
        if (parent != null && name != null && Files.isDirectory(parent)) {
            String want = name.toString();
            try (var s = Files.list(parent)) {
                for (Path c : s.toList()) {
                    Path n = c.getFileName();
                    if (n != null && n.toString().equalsIgnoreCase(want)) return c;
                }
            } catch (Exception ignored) {
                // fall through to `direct` so the caller reports a useful path
            }
        }
        return direct;
    }
}
