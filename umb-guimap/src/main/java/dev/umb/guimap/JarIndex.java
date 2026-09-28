package dev.umb.guimap;

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

    public ClassNode cls(String internalName) { return classes.get(internalName); }

    /** Superclass chain of {@code internalName}, restricted to classes present in this jar. */
    public List<ClassNode> superChain(String internalName) {
        List<ClassNode> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String cur = internalName;
        while (cur != null && seen.add(cur)) {
            ClassNode cn = classes.get(cur);
            if (cn == null) break;
            out.add(cn);
            cur = cn.superName;
        }
        return out;
    }

    /** All classes in the jar that declare {@code iface} directly or via a superclass in the jar. */
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
                if (implementsIface(classes.get(i), iface, seen)) return true;
            }
        }
        return cn.superName != null && implementsIface(classes.get(cn.superName), iface, seen);
    }

    /** True if {@code sub} is {@code sup} or extends it (within the jar). */
    public boolean isSubclassOf(String sub, String sup) {
        String cur = sub;
        Set<String> seen = new LinkedHashSet<>();
        while (cur != null && seen.add(cur)) {
            if (cur.equals(sup)) return true;
            ClassNode cn = classes.get(cur);
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
            ClassNode cn = classes.get(cur);
            if (cn == null) return owner;
            for (org.objectweb.asm.tree.FieldNode f : cn.fields) if (f.name.equals(name)) return cur;
            cur = cn.superName;
        }
        return owner;
    }

    public static String dotted(String internalName) { return internalName.replace('/', '.'); }
    public static String internal(String dotted) { return dotted.replace('.', '/'); }
}
