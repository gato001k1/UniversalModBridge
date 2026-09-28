package dev.umb.legacy1165.legacyside;

import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraft.client.renderer.model.ModelManager;
import net.minecraft.client.renderer.texture.AtlasTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.renderer.texture.SpriteMap;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.data.AnimationMetadataSection;
import net.minecraft.util.ResourceLocation;

/**
 * Headless texture-atlas provision for the 1.16.5 CLIENT pass (the atlas gap:
 * a real iron chest capture needs {@code RenderMaterial.getSprite()} to resolve,
 * which needs {@code ModelManager.getAtlasTexture} to return a working atlas).
 *
 * <p>UNIVERSAL, demand-driven, data-only: no mod id, no texture path, no atlas
 * location is hardcoded anywhere. Every vanilla atlas location is discovered by
 * reflecting over {@code AtlasTexture}'s own {@code public static final
 * ResourceLocation} fields; every sprite is materialized the FIRST time real
 * renderer code asks for it ({@code AtlasTexture.getSprite} reads a map this
 * class owns), with pixel dimensions read from the REAL PNG inside the
 * universe's own jars (the isolated loader's URLs plus {@code umb.1165.modjars}
 * entries). Atlas membership is therefore exactly "what renderers request" -
 * never guessed ahead of evidence. A texture with no PNG anywhere resolves to
 * null with a logged path (the honest headless equivalent of production's
 * missing-texture log), so the failing call chain still names real data.</p>
 *
 * <p>Packing is append-only shelf packing on a fixed sheet (frames never move,
 * so already-recorded draws keep valid UVs); UVs are frame/sheet ratios from
 * the sprite's own constructor, exact for full frames. Mipmap level is 0 (no
 * mipmap generation can run headlessly). The input {@code NativeImage} is
 * retained by the sprite (vanilla's constructor chains it into its mipmap
 * array), so it is deliberately NOT closed - one PNG per requested sprite for
 * the life of the universe.</p>
 *
 * {@code SpriteMap(Collection<AtlasTexture>)},
 * {@code AtlasTexture} fields set without its constructor (the real ctor calls
 * {@code RenderSystem.maxSupportedTextureSize()} - headless failure, proven),
 * {@code AtlasTexture.func_195424_a_} reads only {@code Map.get} on
 * {@code field_94252_e}, {@code TextureAtlasSprite(AtlasTexture, Info, int*5,
 * NativeImage)} protected (called from the {@code HeadlessSprite} subclass),
 * {@code Info(ResourceLocation, int, int, AnimationMetadataSection)},
 * {@code AnimationMetadataSection(List, int, int, int, boolean)} with -1/-1
 * frame size meaning "whole image" (identity, one frame),
 * {@code NativeImage.func_195713_a_(InputStream)} read,
 * {@code ResourceLocation.func_110623_a()=path / func_110624_b()=namespace}.</p>
 */
public final class HeadlessAtlas1165 {

    /**
     * Sheet size. Arbitrary but harmless: only frame/sheet ratios ever ship in
     * UVs (see the sprite constructor's own divisions), so any sheet that fits
     * the requested frames yields exact coordinates. 1024 matches the scale of
     * production atlases without pretending to be one.
     */
    static final int ATLAS_W = 1024;
    static final int ATLAS_H = 1024;

    private HeadlessAtlas1165() {
    }

    /**
     * Builds one demand-filled {@code AtlasTexture} per vanilla atlas location
     * and installs the resulting {@code SpriteMap} into {@code models}
     * ({@code field_229352_b_}). The caller installs {@code models} itself
     * (placeholder {@code field_175617_aL}).
     */
    public static void installInto(ModelManager models, Consumer<String> log) throws Exception {
        List<ResourceLocation> locations = vanillaAtlasLocations();
        List<AtlasTexture> atlases = new ArrayList<AtlasTexture>();
        for (ResourceLocation location : locations) {
            AtlasTexture atlas = allocateAtlas(location, log);
            replaceSpriteMap(atlas, log);
            atlases.add(atlas);
        }
        SpriteMap spriteMap = new SpriteMap(atlases);
        // Demand-driven atlas map: any atlas location real renderer code ever asks
        // for materializes on first get (SpriteMap.func_229152_a_ is a plain Map.get
        // chest/shield/banner locations) with zero hardcoded paths; every miss is
        // logged with the exact location so the next step stays data-driven.
        DemandAtlases demand = new DemandAtlases(spriteMap, log);
        Field map = SpriteMap.class.getDeclaredField("field_229150_a_");
        map.setAccessible(true);
        try {
            map.set(spriteMap, demand);
        } catch (IllegalAccessException e) {
            setFinalField(spriteMap, map, demand);
        }
        Field spriteMapField = ModelManager.class.getDeclaredField("field_229352_b_");
        spriteMapField.setAccessible(true);
        spriteMapField.set(models, spriteMap);
        log.accept("[headless-atlas] installed atlases=" + atlases.size() + " + demand map");
    }

    /**
     * Allocates an {@code AtlasTexture} WITHOUT its constructor: the real ctor
     * queries {@code RenderSystem.maxSupportedTextureSize()}, which throws
     * "Rendersystem called from wrong thread" headlessly (proven by failure -
     * field the constructor sets is set here instead (same placeholder pattern
     * as the lifecycle's headless managers); the size cap is the sheet size,
     * which is the only size this atlas ever addresses since nothing uploads.
     * (sprite map, replaced right after), {@code field_94258_i} (list),
     * {@code field_195427_i} (set), {@code field_215265_o} (max size int).
     */
    static AtlasTexture allocateAtlas(ResourceLocation location, Consumer<String> log)
            throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        AtlasTexture atlas =
                (AtlasTexture) allocate.invoke(unsafe, AtlasTexture.class);
        Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
        Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class,
                Object.class);
        Method putInt = unsafeClass.getMethod("putInt", Object.class, long.class, int.class);
        setAtlasField(unsafe, objectFieldOffset, putObject, atlas, "field_229214_j_", location);
        setAtlasField(unsafe, objectFieldOffset, putObject, atlas, "field_94258_i",
                new ArrayList<TextureAtlasSprite>());
        setAtlasField(unsafe, objectFieldOffset, putObject, atlas, "field_195427_i",
                new HashSet<ResourceLocation>());
        Field sizeField = AtlasTexture.class.getDeclaredField("field_215265_o");
        long sizeOffset = ((Long) objectFieldOffset.invoke(unsafe, sizeField)).longValue();
        putInt.invoke(unsafe, atlas, sizeOffset, ATLAS_W);
        return atlas;
    }

    private static void setAtlasField(Object unsafe, Method objectFieldOffset,
            Method putObject, AtlasTexture atlas, String field, Object value) throws Exception {
        Field target = AtlasTexture.class.getDeclaredField(field);
        long offset = ((Long) objectFieldOffset.invoke(unsafe, target)).longValue();
        putObject.invoke(unsafe, atlas, offset, value);
    }

    /** Every {@code public static final ResourceLocation} on {@code AtlasTexture}. */
    static List<ResourceLocation> vanillaAtlasLocations() throws Exception {
        List<ResourceLocation> out = new ArrayList<ResourceLocation>();
        for (Field field : AtlasTexture.class.getDeclaredFields()) {
            int modifiers = field.getModifiers();
            if (Modifier.isStatic(modifiers) && Modifier.isFinal(modifiers)
                    && ResourceLocation.class.isAssignableFrom(field.getType())) {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value != null) {
                    out.add((ResourceLocation) value);
                }
            }
        }
        return out;
    }

    /** Swaps an atlas's sprite map for a demand-filling one (final instance field). */
    private static void replaceSpriteMap(AtlasTexture atlas, Consumer<String> log) throws Exception {
        Field sprites = AtlasTexture.class.getDeclaredField("field_94252_e");
        sprites.setAccessible(true);
        DemandSprites demand = new DemandSprites(atlas, log);
        try {
            sprites.set(atlas, demand);
        } catch (IllegalAccessException e) {
            setFinalField(atlas, sprites, demand);
        }
    }

    /** Final-field fallback via {@code sun.misc.Unsafe} (same pattern as the lifecycle). */
    private static void setFinalField(Object owner, Field field, Object value) throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Object unsafe = unsafeField.get(null);
        Method objectFieldOffset = unsafeClass.getMethod("objectFieldOffset", Field.class);
        Method putObject = unsafeClass.getMethod("putObject", Object.class, long.class,
                Object.class);
        long offset = ((Long) objectFieldOffset.invoke(unsafe, field)).longValue();
        putObject.invoke(unsafe, owner, offset, value);
    }

    /** A sprite the demand map materialized: texture location plus where it was read from. */
    static final class BuiltSprite {
        final TextureAtlasSprite sprite;
        final String source;

        BuiltSprite(TextureAtlasSprite sprite, String source) {
            this.sprite = sprite;
            this.source = source;
        }
    }

    /**
     * Subclass only to reach the protected {@code TextureAtlasSprite} constructor;
     * every argument is real (dimensions from the decoded PNG, frame at the packed
     * cursor, sheet size constant above).
     */
    static final class HeadlessSprite extends TextureAtlasSprite {
        static TextureAtlasSprite create(AtlasTexture atlas, TextureAtlasSprite.Info info,
                NativeImage image, int x, int y) {
            return new HeadlessSprite(atlas, info, image, x, y);
        }

        private HeadlessSprite(AtlasTexture atlas, TextureAtlasSprite.Info info,
                NativeImage image, int x, int y) {
            super(atlas, info, 0, ATLAS_W, ATLAS_H, x, y, image);
        }
    }

    /**
     * A sprite map that builds real sprites on first request. Hits are lock-free
     * ({@code ConcurrentHashMap.get}); misses serialize per atlas. Textures are
     * located as {@code assets/<namespace>/textures/<path>.png} inside the
     * universe's own jars (the isolated loader's URLs plus
     * {@code umb.1165.modjars}), i.e. exactly where production resource packs
     * (including every mod jar) serve them from.
     */
    static final class DemandSprites extends ConcurrentHashMap<ResourceLocation, TextureAtlasSprite> {
        private static final long serialVersionUID = 1L;

        private final AtlasTexture atlas;
        private final Consumer<String> log;
        private final Set<String> loggedMissing =
                Collections.synchronizedSet(new HashSet<String>());
        // Append-only shelf packing cursor (frames never move once placed).
        private int cursorX;
        private int cursorY;
        private int rowHeight;

        DemandSprites(AtlasTexture atlas, Consumer<String> log) {
            this.atlas = atlas;
            this.log = log;
        }

        @Override
        public TextureAtlasSprite get(Object key) {
            TextureAtlasSprite hit = super.get(key);
            if (hit != null) {
                return hit;
            }
            if (!(key instanceof ResourceLocation)) {
                return null;
            }
            synchronized (this) {
                hit = super.get(key);
                if (hit != null) {
                    return hit;
                }
                BuiltSprite built = buildSprite((ResourceLocation) key);
                if (built == null) {
                    return null;
                }
                super.put((ResourceLocation) key, built.sprite);
                return built.sprite;
            }
        }

        private BuiltSprite buildSprite(ResourceLocation texture) {
            String assetPath = "assets/" + texture.func_110624_b() + "/textures/"
                    + texture.func_110623_a() + ".png";
            for (File jar : universeJars()) {
                ZipFile zip = null;
                try {
                    zip = new ZipFile(jar);
                    ZipEntry entry = zip.getEntry(assetPath);
                    if (entry == null) {
                        continue;
                    }
                    InputStream in = zip.getInputStream(entry);
                    NativeImage image = null;
                    try {
                        image = NativeImage.func_195713_a(in);
                    } finally {
                        try {
                            in.close();
                        } catch (Exception ignored) {
                        }
                    }
                    int width = image.func_195702_a();
                    int height = image.func_195714_b();
                    int[] frame = place(width, height);
                    if (frame == null) {
                        try {
                            image.close();
                        } catch (Exception ignored) {
                        }
                        log.accept("[headless-atlas] sheet full, cannot place " + texture
                                + " (" + width + "x" + height + ")");
                        return null;
                    }
                    TextureAtlasSprite.Info info = new TextureAtlasSprite.Info(texture,
                            width, height,
                            new AnimationMetadataSection(Collections.emptyList(), -1, -1,
                                    1, false));
                    TextureAtlasSprite sprite = HeadlessSprite.create(atlas, info, image,
                            frame[0], frame[1]);
                    log.accept("[headless-atlas] sprite " + texture + " " + width + "x"
                            + height + " @(" + frame[0] + "," + frame[1] + ") from "
                            + jar.getName());
                    return new BuiltSprite(sprite, jar.getName());
                } catch (Exception e) {
                    log.accept("[headless-atlas] unreadable " + assetPath + " in "
                            + jar.getName() + ": " + e);
                    return null;
                } finally {
                    if (zip != null) {
                        try {
                            zip.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
            if (loggedMissing.add(assetPath)) {
                log.accept("[headless-atlas] MISSING texture " + texture
                        + " (no " + assetPath + " in universe jars)");
            }
            return null;
        }

        /** Shelf-pack next frame; null when the sheet is full. */
        private int[] place(int width, int height) {
            if (width <= 0 || height <= 0 || width > ATLAS_W || height > ATLAS_H) {
                return null;
            }
            if (cursorX + width > ATLAS_W) {
                cursorX = 0;
                cursorY += rowHeight;
                rowHeight = 0;
            }
            if (cursorY + height > ATLAS_H) {
                return null;
            }
            int[] frame = new int[] {cursorX, cursorY};
            cursorX += width;
            if (height > rowHeight) {
                rowHeight = height;
            }
            return frame;
        }

        /** Jars serving this universe: the isolated loader's own URLs plus the
         * named mod jars (covers embeddings where discovery and loading differ). */
        private List<File> universeJars() {
            List<File> jars = new ArrayList<File>();
            Set<String> seen = new HashSet<String>();
            ClassLoader loader = HeadlessAtlas1165.class.getClassLoader();
            if (loader instanceof URLClassLoader) {
                for (URL url : ((URLClassLoader) loader).getURLs()) {
                    if (!"file".equals(url.getProtocol()) || url.getPath() == null) {
                        continue;
                    }
                    try {
                        File file = new File(url.toURI());
                        if (file.isFile() && file.getName().endsWith(".jar")
                                && seen.add(file.getAbsolutePath())) {
                            jars.add(file);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
            String modJars = System.getProperty("umb.1165.modjars", "");
            for (String part : modJars.split(";")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                File file = new File(trimmed).getAbsoluteFile();
                if (file.isFile() && seen.add(file.getAbsolutePath())) {
                    jars.add(file);
                }
                File parent = file.getParentFile();
                if (parent != null && parent.isDirectory()) {
                    File[] siblings = parent.listFiles();
                    if (siblings != null) {
                        for (File sibling : siblings) {
                            if (sibling.isFile() && sibling.getName().endsWith(".jar")
                                    && seen.add(sibling.getAbsolutePath())) {
                                jars.add(sibling);
                            }
                        }
                    }
                }
            }
            return jars;
        }
    }

    /**
     * An atlas map that materializes whole atlases on first request. Hits are
     * lock-free; misses serialize. Pre-seeded from the installing
     * {@code SpriteMap}'s own entries so the common atlases never even miss.
     */
    static final class DemandAtlases extends ConcurrentHashMap<ResourceLocation, AtlasTexture> {
        private static final long serialVersionUID = 1L;

        private final Consumer<String> log;

        DemandAtlases(SpriteMap seed, Consumer<String> log) {
            this.log = log;
            try {
                Field map = SpriteMap.class.getDeclaredField("field_229150_a_");
                map.setAccessible(true);
                Object seeded = map.get(seed);
                if (seeded instanceof Map) {
                    for (Map.Entry<?, ?> entry : ((Map<?, ?>) seeded).entrySet()) {
                        if (entry.getKey() instanceof ResourceLocation
                                && entry.getValue() instanceof AtlasTexture) {
                            super.put((ResourceLocation) entry.getKey(),
                                    (AtlasTexture) entry.getValue());
                        }
                    }
                }
            } catch (Throwable t) {
                log.accept("[headless-atlas] atlas seed copy failed: " + t);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public AtlasTexture get(Object key) {
            AtlasTexture hit = super.get(key);
            if (hit != null) {
                return hit;
            }
            if (!(key instanceof ResourceLocation)) {
                return null;
            }
            synchronized (this) {
                hit = super.get(key);
                if (hit != null) {
                    return hit;
                }
                ResourceLocation location = (ResourceLocation) key;
                try {
                    AtlasTexture atlas = allocateAtlas(location, log);
                    replaceSpriteMap(atlas, log);
                    super.put(location, atlas);
                    log.accept("[headless-atlas] on-demand atlas " + location);
                    return atlas;
                } catch (Throwable t) {
                    log.accept("[headless-atlas] atlas build failed for " + location + ": "
                            + t);
                    return null;
                }
            }
        }
    }
}
