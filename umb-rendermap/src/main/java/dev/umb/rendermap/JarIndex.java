package dev.umb.rendermap;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only index of the mod jar: every class as an ASM tree, every asset as raw bytes. */
public class JarIndex {

    public final Map<String, ClassNode> classes = new LinkedHashMap<>();   // internal name -> node
    public final Map<String, byte[]> assets = new LinkedHashMap<>();       // "assets/hbm/models/x.obj" -> bytes
    public final List<String> readErrors = new ArrayList<>();
    /**
     * Vanilla/Forge classes (NOT the mod's own), loaded separately via {@link #loadEngineClasspath}
     * — used ONLY as a fallback for hierarchy walks ({@link #isSubclassOf}, {@link #implementorsOf},
     * {@link #superChain}, {@link #declaringClassOfField}). A mod jar never bundles the engine
     * classes it extends, so a field declared with a concrete subclass type whose OWN superclass
     * is a vanilla intermediate (e.g. Iron Chests' {@code BlockIronChest extends BlockContainer
     * extends Block}, where {@code BlockContainer} ships only in Minecraft's own jar, never in a
     * mod's) would otherwise make the chain walk dead-end at the jar boundary and falsely report
     * "not a Block". This is engine-shared infrastructure every 1.7.10 Forge mod sits on top of —
     * not a mod-specific literal — exactly like hardcoding {@code net/minecraft/item/Item} itself.
     */
    public final Map<String, ClassNode> engine = new LinkedHashMap<>();

    /**
     * Whole-jar memoization for {@link MethodSim}'s two opt-in {@code getfield} resolvers
     * (enum-per-constant, and single-assignment instance fields — see their javadoc there).
     * Deliberately owned by {@code JarIndex}, not by any one {@link MethodSim} instance: a
     * scanner (e.g. {@link BindingScanner}, {@link RegistryScanner}) creates a FRESH
     * {@code MethodSim} for every method in the jar, so a per-instance cache never gets reused
     * across methods — for a popular field read from thousands of call sites (routine in a jar
     * HBM's size), that meant redoing the same class/constructor re-simulation thousands of
     * times over, which is what made the very first, per-instance-cached version of this
     * capability pathologically slow on HBM. One shared map, scoped to the lifetime of one
     * {@code JarIndex} (i.e. one whole run), fixes that without changing any result.
     */
    public final Map<String, Val> enumFieldPerConstantCache = new LinkedHashMap<>();
    public final Map<String, Val> instanceFieldSingleAssignmentCache = new LinkedHashMap<>();

    public static JarIndex open(Path jar) throws IOException {
        JarIndex idx = new JarIndex();
        try (ZipFile zf = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                String n = e.getName();
                if (n.endsWith(".class")) {
                    try (var in = zf.getInputStream(e)) {
                        ClassReader cr = new ClassReader(in.readAllBytes());
                        ClassNode cn = new ClassNode();
                        cr.accept(cn, ClassReader.SKIP_FRAMES);
                        idx.classes.put(cn.name, cn);
                    } catch (Exception ex) {
                        idx.readErrors.add(n + ": " + ex);
                    }
                } else if (n.startsWith("assets/")) {
                    try (var in = zf.getInputStream(e)) {
                        idx.assets.put(n, in.readAllBytes());
                    } catch (Exception ex) {
                        idx.readErrors.add(n + ": " + ex);
                    }
                }
            }
        }
        return idx;
    }

    /**
     * Merges every class from {@code engineJar} (a vanilla client jar or the Forge universal jar)
     * into {@link #engine}, skipping any name already present (the mod's own jar always wins).
     * Safe to call multiple times with different jars; safe to skip entirely (every hierarchy walk
     * below degrades gracefully to jar-only behaviour when {@link #engine} is empty — a mod field
     * declared with the exact vanilla type, e.g. plain {@code Item}/{@code Block}, still resolves
     * with no engine classpath at all).
     */
    public void loadEngineClasspath(Path engineJar) throws IOException {
        if (engineJar == null || !java.nio.file.Files.exists(engineJar)) return;
        try (ZipFile zf = new ZipFile(engineJar.toFile())) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory() || !e.getName().endsWith(".class")) continue;
                try (var in = zf.getInputStream(e)) {
                    ClassReader cr = new ClassReader(in.readAllBytes());
                    ClassNode cn = new ClassNode();
                    cr.accept(cn, ClassReader.SKIP_FRAMES);
                    if (!classes.containsKey(cn.name)) engine.putIfAbsent(cn.name, cn);
                } catch (Exception ex) {
                    readErrors.add(engineJar + "!" + e.getName() + ": " + ex);
                }
            }
        }
    }

    /** The mod's own class, else a merged-in engine class, else {@code null}. */
    public ClassNode cls(String internalName) {
        ClassNode cn = classes.get(internalName);
        return cn != null ? cn : engine.get(internalName);
    }

    /** Superclass chain of {@code internalName}, following into the engine classpath when loaded. */
    public List<ClassNode> superChain(String internalName) {
        List<ClassNode> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String cur = internalName;
        while (cur != null && seen.add(cur)) {
            ClassNode cn = cls(cur);
            if (cn == null) break;
            out.add(cn);
            cur = cn.superName;
        }
        return out;
    }

    /** All classes in the jar that declare {@code iface} directly or via a superclass. */
    public List<ClassNode> implementorsOf(String iface) {
        List<ClassNode> out = new ArrayList<>();
        for (ClassNode cn : classes.values()) {
            if (implementsIface(cn, iface, new LinkedHashSet<>())) out.add(cn);
        }
        return out;
    }

    private boolean implementsIface(ClassNode cn, String iface, Set<String> seen) {
        if (cn == null || !seen.add(cn.name)) return false;
        if (cn.interfaces != null) {
            for (String i : cn.interfaces) {
                if (i.equals(iface)) return true;
                if (implementsIface(cls(i), iface, seen)) return true;
            }
        }
        return cn.superName != null && implementsIface(cls(cn.superName), iface, seen);
    }

    /** True if {@code sub} is {@code sup} or extends it (mod jar, falling through to the engine
     *  classpath when loaded — see {@link #engine}). */
    public boolean isSubclassOf(String sub, String sup) {
        String cur = sub;
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && seen.add(cur)) {
            if (cur.equals(sup)) return true;
            ClassNode cn = cls(cur);
            if (cn == null) return false;
            cur = cn.superName;
        }
        return false;
    }

    /**
     * Resolve a symbolic field reference to the class that actually declares it.
     * {@code RenderConveyor.getRenderId()} reads {@code BlockConveyor.renderID} while
     * {@code BlockConveyorBase.getRenderType()} reads {@code BlockConveyorBase.renderID} —
     * the same field, two different symbolic owners.
     */
    public String declaringClassOfField(String owner, String name) {
        String cur = owner;
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && seen.add(cur)) {
            ClassNode cn = cls(cur);
            if (cn == null) return owner;
            for (org.objectweb.asm.tree.FieldNode f : cn.fields) if (f.name.equals(name)) return cur;
            cur = cn.superName;
        }
        return owner;
    }

    public static String dotted(String internalName) { return internalName.replace('/', '.'); }
    public static String internal(String dotted) { return dotted.replace('.', '/'); }
}
