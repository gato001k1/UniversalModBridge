// UMB Snapshot — runtime texture-atlas icon dump (lane A3).
// SPDX-License-Identifier: CC0-1.0
package net.umb.snapshot;

import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.util.ResourceLocation;

/**
 * Dumps the STITCHED pixels of every texture-atlas sprite the snapshot referenced.
 *
 * Why this exists: a 1.7.10 mod may build a sprite at runtime instead of shipping a png
 * (HBM composites e.g. {@code hbm:bedrock_ore_base_light-0}, {@code hbm:scraps-Beryllium},
 * {@code hbm:watz_pellet-HESitem.watz_pellet} from layers in a TextureAtlasSprite subclass).
 * Such an icon has no file in the mod jar at all, so a static asset extraction can never
 * find it — the only place those pixels exist is the stitched atlas of a running client.
 *
 * Two atlases, not one: 1.7.10 keeps {@code textures/atlas/blocks.png} and
 * {@code textures/atlas/items.png} separate (they were merged in 1.8), so both maps are
 * walked and each dumped sprite records which atlas it came from.
 *
 * Two pixel sources, in this order:
 *   frameData   {@code TextureAtlasSprite.func_147965_a(0)[0]} — the exact ARGB rows the
 *               game uploaded. Only available for ANIMATED sprites: TextureMap's
 *               loadTextureAtlas calls {@code func_130103_l()} (clearFramesTextureData)
 *               on every sprite without animation metadata right after uploading it.
 *   glReadback  one {@code glGetTexImage} of the whole atlas at mipmap level 0, cropped
 *               per sprite by {@code getOriginX/getOriginY} × {@code getIconWidth/Height}.
 *               Read back as BGRA/UNSIGNED_INT_8_8_8_8_REV, which is exactly the format
 *               1.7.10's TextureUtil uploaded ARGB ints with, so no channel swizzle and no
 *               row flip is needed.
 *
 * Must run on the render thread (the client tick that Snapshot.dump also runs on).
 *
 * Output layout, under {@code <root>}:
 *   assets/&lt;ns&gt;/textures/atlas-dump/&lt;blocks|items&gt;/&lt;sanitized icon path&gt;.png
 *   icon-index.json   every referenced name -> its file, size, frame count, source
 *
 * The sanitizer is a byte-for-byte copy of
 * {@code dev.umb.hostagent.content.LegacyIds.sanitizePath/sanitizeNamespace}: lowercase
 * (Locale.ROOT) then any character outside the allowed set becomes '_'. The namespace is
 * split off into the {@code assets/<ns>/} directory exactly the way PackGen splits it.
 */
public final class IconDump {

    /** LegacyIds.PATH_OK */
    private static final String PATH_OK = "abcdefghijklmnopqrstuvwxyz0123456789/._-";
    /** LegacyIds.NS_OK */
    private static final String NS_OK = "abcdefghijklmnopqrstuvwxyz0123456789_.-";

    private static final long MAX_ATLAS_BYTES = 768L * 1024L * 1024L;

    private IconDump() {
    }

    // --------------------------------------------------------------------- atlas

    private static final class Atlas {
        final String key;
        final TextureMap map;
        Object missing;
        final Map<String, TextureAtlasSprite> byName = new HashMap<String, TextureAtlasSprite>();
        int glId = -1;
        int width;
        int height;
        int[] pixels;
        boolean readTried;
        String error;
        int anisotropic = -1;

        Atlas(String key, TextureMap map) {
            this.key = key;
            this.map = map;
        }
    }

    private static final class Row {
        String name;
        String ns;
        String path;
        String sanitized;
        String atlas;
        String file;
        int width;
        int height;
        int frames;
        boolean animated;
        boolean missing;
        String source;
        String error;
        int originX = -1;
        int originY = -1;
    }

    // ---------------------------------------------------------------------- run

    /**
     * @param root output root (the directory that CONTAINS assets/ and icon-index.json)
     * @return one-line summary for the log
     */
    public static String run(File root, Collection<String> iconNames) {
        long t0 = System.currentTimeMillis();
        // Deterministic order so two runs produce byte-identical indexes.
        TreeSet<String> names = new TreeSet<String>();
        for (String n : iconNames) {
            if (n != null && n.length() > 0) {
                names.add(n);
            }
        }

        List<Atlas> atlases = new ArrayList<Atlas>();
        try {
            atlases = findAtlases();
        } catch (Throwable t) {
            return "atlas lookup FAILED: " + Refl.describe(t);
        }
        if (atlases.isEmpty()) {
            return "no texture atlas found";
        }
        for (Atlas a : atlases) {
            indexSprites(a);
            System.out.println("[umbsnap] atlas " + a.key + " glId=" + a.map.func_110552_b()
                    + " sprites=" + a.byName.size() + " aniso=" + a.anisotropic
                    + " missingSprite=" + (a.missing == null ? "null" : nameOf(a.missing)));
        }

        List<Row> rows = new ArrayList<Row>(names.size());
        Map<String, String> fileOwner = new HashMap<String, String>();
        int dumped = 0;
        int fromFrameData = 0;
        int fromGl = 0;
        int missing = 0;
        int failed = 0;
        int collisions = 0;
        int originMismatch = 0;

        for (String name : names) {
            boolean any = false;
            for (Atlas a : atlases) {
                TextureAtlasSprite s = lookup(a, name);
                if (s == null) {
                    continue;
                }
                any = true;
                Row r = new Row();
                r.name = name;
                r.ns = nsOf(name);
                r.path = pathOf(name);
                r.sanitized = sanitize(r.path, PATH_OK);
                r.atlas = a.key;
                try {
                    r.width = s.func_94211_a();
                    r.height = s.func_94216_b();
                } catch (Throwable t) {
                    r.error = "size: " + Refl.describe(t);
                }
                try {
                    r.frames = s.func_110970_k();
                } catch (Throwable t) {
                    r.frames = -1;
                }
                try {
                    r.animated = s.func_130098_m();
                } catch (Throwable t) {
                    r.animated = false;
                }
                try {
                    r.originX = s.func_130010_a();
                    r.originY = s.func_110967_i();
                } catch (Throwable ignored) {
                    // origin stays -1; crop() will refuse
                }
                if (r.width <= 0 || r.height <= 0 || r.sanitized == null || r.sanitized.length() == 0) {
                    r.error = (r.error == null ? "" : r.error + "; ") + "unusable size/name";
                    failed++;
                    rows.add(r);
                    continue;
                }

                int[] px = null;
                try {
                    int[][] fd = s.func_147965_a(0);
                    if (fd != null && fd.length > 0 && fd[0] != null && fd[0].length >= r.width * r.height) {
                        px = fd[0];
                        r.source = "frameData";
                    }
                } catch (Throwable ignored) {
                    // cleared after upload for non-animated sprites — fall through to GL
                }
                if (px == null) {
                    if (!a.readTried) {
                        readAtlas(a);
                    }
                    if (a.pixels != null) {
                        if (r.originX >= 0 && r.originY >= 0) {
                            int uvx = uvOrigin(s, a.width, true);
                            if (uvx >= 0 && uvx != r.originX) {
                                originMismatch++;
                            }
                        }
                        px = crop(a, r.originX, r.originY, r.width, r.height);
                        if (px == null) {
                            r.error = "origin " + r.originX + "," + r.originY + " + " + r.width + "x" + r.height
                                    + " outside atlas " + a.width + "x" + a.height;
                        } else {
                            r.source = "glReadback";
                        }
                    } else {
                        r.error = "atlas readback: " + a.error;
                    }
                }
                if (px == null) {
                    failed++;
                    rows.add(r);
                    continue;
                }

                String rel = "assets/" + sanitize(r.ns, NS_OK) + "/textures/atlas-dump/"
                        + a.key + "/" + r.sanitized + ".png";
                File f = new File(root, rel.replace('/', File.separatorChar));
                String prev = fileOwner.put(rel, name);
                if (prev != null && !prev.equals(name)) {
                    collisions++;
                }
                try {
                    File p = f.getParentFile();
                    if (p != null) {
                        p.mkdirs();
                    }
                    BufferedImage img = new BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_ARGB);
                    img.setRGB(0, 0, r.width, r.height, px, 0, r.width);
                    if (!ImageIO.write(img, "PNG", f)) {
                        throw new java.io.IOException("no PNG writer");
                    }
                    r.file = rel;
                    dumped++;
                    if ("frameData".equals(r.source)) {
                        fromFrameData++;
                    } else {
                        fromGl++;
                    }
                } catch (Throwable t) {
                    r.error = "write " + rel + ": " + Refl.describe(t);
                    r.source = null;
                    failed++;
                }
                rows.add(r);
            }
            if (!any) {
                Row r = new Row();
                r.name = name;
                r.ns = nsOf(name);
                r.path = pathOf(name);
                r.sanitized = sanitize(r.path, PATH_OK);
                r.missing = true;
                rows.add(r);
                missing++;
            }
        }

        String summary = "icons=" + names.size() + " rows=" + rows.size() + " dumped=" + dumped
                + " frameData=" + fromFrameData + " glReadback=" + fromGl
                + " missingInAtlas=" + missing + " failed=" + failed
                + " fileCollisions=" + collisions + " originUvMismatch=" + originMismatch
                + " ms=" + (System.currentTimeMillis() - t0);

        try {
            writeIndex(new File(root, "icon-index.json"), atlases, rows, summary,
                    names.size(), dumped, fromFrameData, fromGl, missing, failed, collisions);
        } catch (Throwable t) {
            summary = summary + " INDEX_WRITE_FAILED=" + Refl.describe(t);
        }
        return summary;
    }

    // ------------------------------------------------------------------ atlases

    private static List<Atlas> findAtlases() throws Exception {
        List<Atlas> out = new ArrayList<Atlas>();
        Minecraft mc = Minecraft.func_71410_x();
        TextureMap blocks = null;
        try {
            blocks = mc.func_147117_R();
        } catch (Throwable ignored) {
            blocks = null;
        }
        if (blocks != null) {
            out.add(new Atlas("blocks", blocks));
        }
        TextureManager tm = null;
        try {
            tm = mc.func_110434_K();
        } catch (Throwable ignored) {
            tm = null;
        }
        if (tm != null) {
            TextureMap items = atlasAt(tm, "field_110576_c", "locationItemsTexture");
            if (items != null && items != blocks) {
                out.add(new Atlas("items", items));
            }
            if (blocks == null) {
                TextureMap b2 = atlasAt(tm, "field_110575_b", "locationBlocksTexture");
                if (b2 != null) {
                    out.add(0, new Atlas("blocks", b2));
                }
            }
        }
        return out;
    }

    private static TextureMap atlasAt(TextureManager tm, String... field) {
        try {
            Object loc = Refl.get(TextureMap.class, null, field);
            if (!(loc instanceof ResourceLocation)) {
                return null;
            }
            ITextureObject o = tm.func_110581_b((ResourceLocation) loc);
            return (o instanceof TextureMap) ? (TextureMap) o : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Index every sprite the map holds by its own getIconName(). Going through the map's
     * mapUploadedSprites / mapRegisteredSprites rather than getAtlasSprite(name) removes any
     * dependence on what the map used as its key.
     */
    @SuppressWarnings("unchecked")
    private static void indexSprites(Atlas a) {
        a.missing = Refl.getOrNull(TextureMap.class, a.map, "field_94249_f", "missingImage");
        if (a.missing == null) {
            // Fallback: whatever getAtlasSprite hands back for a name that cannot exist.
            try {
                a.missing = a.map.func_110572_b("umbsnap__no_such_sprite__" + System.nanoTime());
            } catch (Throwable ignored) {
                a.missing = null;
            }
        }
        Object aniso = Refl.getOrNull(TextureMap.class, a.map, "field_147637_k", "anisotropicFiltering");
        if (aniso instanceof Number) {
            a.anisotropic = ((Number) aniso).intValue();
        }
        String[] mapFields = {"field_94252_e", "mapUploadedSprites", "field_110574_e", "mapRegisteredSprites"};
        for (int i = 0; i < mapFields.length; i += 2) {
            Object m = Refl.getOrNull(TextureMap.class, a.map, mapFields[i], mapFields[i + 1]);
            if (!(m instanceof Map)) {
                continue;
            }
            for (Object v : ((Map<Object, Object>) m).values()) {
                if (!(v instanceof TextureAtlasSprite)) {
                    continue;
                }
                TextureAtlasSprite s = (TextureAtlasSprite) v;
                if (a.missing != null && s == a.missing) {
                    continue;
                }
                String n = nameOf(s);
                if (n == null) {
                    continue;
                }
                if (!a.byName.containsKey(n)) {
                    a.byName.put(n, s);
                }
            }
        }
    }

    /** null when this atlas has no sprite of that name (or only the missing placeholder). */
    private static TextureAtlasSprite lookup(Atlas a, String name) {
        TextureAtlasSprite s = a.byName.get(name);
        if (s != null) {
            return s;
        }
        try {
            s = a.map.func_110572_b(name);
        } catch (Throwable t) {
            return null;
        }
        if (s == null) {
            return null;
        }
        if (a.missing != null && s == a.missing) {
            return null;
        }
        // getAtlasSprite returns missingImage for unknown names; if we could not identify
        // missingImage, fall back to comparing the reported name.
        String n = nameOf(s);
        if (n != null && !n.equals(name)) {
            return null;
        }
        return s;
    }

    private static String nameOf(Object sprite) {
        try {
            return ((TextureAtlasSprite) sprite).func_94215_i();
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------- pixels

    private static void readAtlas(Atlas a) {
        a.readTried = true;
        int prev = 0;
        try {
            prev = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int id = a.map.func_110552_b();
            a.glId = id;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
            while (GL11.glGetError() != GL11.GL_NO_ERROR) {
                // drain pre-existing errors so ours is unambiguous
            }
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            a.width = w;
            a.height = h;
            if (w <= 0 || h <= 0) {
                a.error = "atlas level 0 is " + w + "x" + h;
                return;
            }
            long bytes = (long) w * (long) h * 4L;
            if (bytes > MAX_ATLAS_BYTES) {
                a.error = "atlas " + w + "x" + h + " = " + bytes + " bytes exceeds cap";
                return;
            }
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
            IntBuffer ib = BufferUtils.createIntBuffer(w * h);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, ib);
            int err = GL11.glGetError();
            if (err != GL11.GL_NO_ERROR) {
                a.error = "glGetTexImage error 0x" + Integer.toHexString(err);
                return;
            }
            int[] px = new int[w * h];
            ib.rewind();
            ib.get(px);
            a.pixels = px;
            System.out.println("[umbsnap] atlas " + a.key + " readback " + w + "x" + h
                    + " (" + bytes + " bytes) OK");
        } catch (Throwable t) {
            a.error = Refl.describe(t);
        } finally {
            try {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prev);
            } catch (Throwable ignored) {
                // context is about to die anyway
            }
        }
    }

    private static int[] crop(Atlas a, int ox, int oy, int w, int h) {
        if (a.pixels == null || ox < 0 || oy < 0) {
            return null;
        }
        if (ox + w > a.width || oy + h > a.height) {
            return null;
        }
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            System.arraycopy(a.pixels, (oy + y) * a.width + ox, out, y * w, w);
        }
        return out;
    }

    /** Origin implied by the sprite's u (or v) span — a cross-check on getOriginX/Y. */
    private static int uvOrigin(TextureAtlasSprite s, int atlasSize, boolean horizontal) {
        try {
            float min = horizontal ? s.func_94209_e() : s.func_94206_g();
            return (int) Math.round((double) min * (double) atlasSize);
        } catch (Throwable t) {
            return -1;
        }
    }

    // ------------------------------------------------------------------- naming

    private static String nsOf(String icon) {
        int c = icon.indexOf(':');
        return c > 0 ? icon.substring(0, c) : "minecraft";
    }

    private static String pathOf(String icon) {
        int c = icon.indexOf(':');
        return c >= 0 ? icon.substring(c + 1) : icon;
    }

    private static String sanitize(String raw, String allowed) {
        if (raw == null) {
            return "";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            sb.append(allowed.indexOf(c) >= 0 ? c : '_');
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------- index

    private static void writeIndex(File out, List<Atlas> atlases, List<Row> rows, String summary,
                                   int uniqueNames, int dumped, int frameData, int gl,
                                   int missing, int failed, int collisions) throws Exception {
        File p = out.getParentFile();
        if (p != null) {
            p.mkdirs();
        }
        Writer w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(out), "UTF-8"), 1 << 16);
        try {
            Jw j = new Jw(w);
            j.beginObject();
            j.prop("format", "umb-icon-index/1");
            j.prop("summary", summary);
            j.prop("sanitizer", "LegacyIds.sanitizePath (lowercase, [a-z0-9/._-] kept, else '_'); "
                    + "namespace split into assets/<ns>/");
            j.name("atlases").beginArray();
            for (Atlas a : atlases) {
                j.beginObject();
                j.prop("name", a.key);
                j.prop("glId", a.glId);
                j.prop("width", a.width);
                j.prop("height", a.height);
                j.prop("sprites", a.byName.size());
                j.prop("anisotropicFiltering", a.anisotropic);
                j.prop("readback", a.pixels != null);
                j.prop("error", a.error);
                j.endObject();
            }
            j.endArray();
            j.name("counts").beginObject();
            j.prop("iconsReferencedUnique", uniqueNames);
            j.prop("rows", rows.size());
            j.prop("dumped", dumped);
            j.prop("sourceFrameData", frameData);
            j.prop("sourceGlReadback", gl);
            j.prop("missingInAtlas", missing);
            j.prop("failed", failed);
            j.prop("fileCollisions", collisions);
            j.endObject();
            j.name("icons").beginArray();
            for (Row r : rows) {
                j.beginObject();
                j.prop("name", r.name);
                j.prop("ns", r.ns);
                j.prop("path", r.path);
                j.prop("sanitized", r.sanitized);
                j.prop("atlas", r.atlas);
                j.prop("file", r.file);
                j.prop("width", r.width);
                j.prop("height", r.height);
                j.prop("frames", r.frames);
                j.prop("animated", r.animated);
                j.prop("missing", r.missing);
                j.prop("source", r.source);
                j.prop("originX", r.originX);
                j.prop("originY", r.originY);
                if (r.error != null) {
                    j.prop("error", r.error);
                }
                j.endObject();
            }
            j.endArray();
            j.endObject();
            j.finish();
        } finally {
            w.flush();
            w.close();
        }
    }
}
