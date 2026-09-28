package dev.umb.pipeline.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Bridge v2 entrypoint discovery. Reads exactly what the jar's metadata actually carries
 * and nothing else:
 * <ul>
 *   <li><b>Fabric</b> — {@code fabric.mod.json} {@code entrypoints} map (checked first):
 *       each key is a category ({@code main}/{@code server}/{@code client} all valid), each
 *       value is an array of class-name strings or {@code {"value": "..."}} objects.</li>
 *   <li><b>Legacy Forge</b> — classes carrying the {@code @Mod} class annotation.
 *       Descriptor {@code Lnet/minecraftforge/fml/common/Mod;} was VERIFIED on
 *       fixtures/fixture-hello-forge (2026-08-30, javap); {@code Lcpw/mods/fml/common/Mod;}
 *       is the pre-1.7 ancestor of the same shape (documented, no fixture). A class is
 *       reported once per class - the scan deduplicates by class name.</li>
 * </ul>
 * Absent or unknown metadata yields an empty {@link EntrypointScan} that still names what
 * was looked for and what was found — a class name is NEVER guessed. Ordering is
 * deterministic: entrypoints sort by (source, className), so jar and JSON entry order are
 * irrelevant.
 */
public final class EntrypointScanner {

    /**
     * Real Forge @Mod descriptors across eras. {@code fml/common/Mod} verified on the M1
     * forge fixture; {@code cpw/mods/fml/common/Mod} is the documented pre-1.7 ancestor;
     * {@code fml/Mod} is included per the project spec but has never been observed in any
     * real jar (it would match nothing in practice - there is no such annotation class).
     */
    static final Set<String> FORGE_MOD_DESCRIPTORS = Set.of(
            "Lnet/minecraftforge/fml/common/Mod;", // 1.7.10 through modern Forge (verified)
            "Lcpw/mods/fml/common/Mod;",           // 1.4.7-1.6.4 (same shape, documented)
            "Lnet/minecraftforge/fml/Mod;");       // spec-listed; never observed, kept for completeness

    private static final String FABRIC_KEY = "fabric.mod.json";
    private static final String FORGE_KEY = "forge:@Mod";
    private static final List<String> LOOKED_FOR = List.of(FABRIC_KEY, FORGE_KEY);

    private EntrypointScanner() {
    }

    public static EntrypointScan scan(Path modJar) throws IOException {
        List<String> seen = new ArrayList<>(2);
        List<EntrypointScan.Entrypoint> found = new ArrayList<>();

        try (JarFile jf = new JarFile(modJar.toFile())) {
            JsonObject fabric = readJsonEntry(jf, FABRIC_KEY);
            if (fabric != null) {
                seen.add(FABRIC_KEY);
                collectFabric(fabric, found);
            }

            TreeSet<String> modClasses = new TreeSet<>();
            for (Enumeration<JarEntry> en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                if (hasForgeModAnnotation(jf, e)) {
                    String internal = e.getName().substring(0, e.getName().length() - ".class".length());
                    modClasses.add(internal.replace('/', '.'));
                }
            }
            if (!modClasses.isEmpty()) {
                seen.add(FORGE_KEY);
                for (String c : modClasses) {
                    found.add(new EntrypointScan.Entrypoint(FORGE_KEY, c));
                }
            }
        }

        found.sort(Comparator.comparing(EntrypointScan.Entrypoint::source)
                .thenComparing(EntrypointScan.Entrypoint::className));
        return new EntrypointScan(List.copyOf(found), List.copyOf(seen), LOOKED_FOR);
    }

    // ---------------- fabric

    private static void collectFabric(JsonObject fabric, List<EntrypointScan.Entrypoint> out) {
        JsonObject eps = fabric.getAsJsonObject("entrypoints");
        if (eps == null) {
            return; // metadata present but declares no entrypoints — honest, not a guess
        }
        for (var en : eps.entrySet()) {
            for (JsonElement item : asArray(en.getValue())) {
                String cls = className(item);
                if (cls != null) {
                    out.add(new EntrypointScan.Entrypoint("fabric:" + en.getKey(), cls));
                }
            }
        }
    }

    /** Entrypoint array item -> class name; supports the string and {"value": "..."} forms. */
    private static String className(JsonElement item) {
        if (item == null || item.isJsonNull()) {
            return null;
        }
        if (item.isJsonPrimitive()) {
            return item.getAsString();
        }
        if (item.isJsonObject() && item.getAsJsonObject().has("value")) {
            JsonElement v = item.getAsJsonObject().get("value");
            return v.isJsonPrimitive() ? v.getAsString() : null;
        }
        return null; // unknown shape — record nothing rather than guess (D4)
    }

    /** fabric.mod.json always uses arrays, but a bare string/object also appears in the wild. */
    private static JsonArray asArray(JsonElement e) {
        if (e == null || !e.isJsonArray()) {
            JsonArray single = new JsonArray();
            if (e != null && !e.isJsonNull()) {
                single.add(e);
            }
            return single;
        }
        return e.getAsJsonArray();
    }

    // ---------------- forge @Mod

    private static boolean hasForgeModAnnotation(JarFile jf, JarEntry entry) {
        try (InputStream in = jf.getInputStream(entry)) {
            ClassReader cr = new ClassReader(in);
            boolean[] found = {false};
            cr.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (FORGE_MOD_DESCRIPTORS.contains(descriptor)) {
                        found[0] = true;
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return found[0];
        } catch (IOException | RuntimeException malformed) {
            return false; // untrusted input: record nothing rather than crash
        }
    }

    // ---------------- shared

    private static JsonObject readJsonEntry(JarFile jf, String path) throws IOException {
        var e = jf.getEntry(path);
        if (e == null) {
            return null;
        }
        try (InputStream in = jf.getInputStream(e)) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (RuntimeException malformed) {
            return null; // declared but unparseable — the source is not "seen"
        }
    }
}