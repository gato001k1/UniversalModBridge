package dev.umb.pipeline;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Headless smoke loader (compatibility ladder stage 3): loads every *.class entry of
 * a mod jar under the HOST classloader shape — a child URLClassLoader over the mod jar
 * whose parent is a URLClassLoader over hostJar + lib jars standing on the platform
 * classloader. The mod sees host and JDK; the host never sees the mod. A type that
 * does not exist anywhere below the mod loader fails.
 *
 * <p>Each entry is first defined via {@code Class.forName(binName, false, loader)}
 * (structural load: format, class version, superclass chain — NO static initializers),
 * then every class name in the entry's constant pool is forced through the SAME
 * loader. The JVM resolves constant-pool references lazily, so a plain load would
 * green-light a class whose method invokes an absent class; forcing the refs restores
 * the "loads under the host universe" verdict. Failures record the ROOT cause, and a
 * CNFE/NCDFE chain's message names the absent class for the missing-symbol sample.
 *
 * <p>Single-shot, no mutable static state. Every load Throwable lands in exactly one
 * report bucket; only structural IO failures (unreadable jar, unwalkable lib dir)
 * propagate as {@link IOException}.
 */
public final class SmokeLoader {

    private SmokeLoader() {
    }

    public static SmokeReport smoke(Path modJar, Path hostJar, List<Path> libJars) throws IOException {
        List<URL> hostUrls = new ArrayList<>();
        hostUrls.add(hostJar.toUri().toURL());
        for (Path lib : libJars) {
            hostUrls.add(lib.toUri().toURL());
        }
        try (URLClassLoader host = new URLClassLoader(
                hostUrls.toArray(new URL[0]), ClassLoader.getPlatformClassLoader());
             URLClassLoader mod = new URLClassLoader(
                     new URL[]{modJar.toUri().toURL()}, host)) {
            return run(modJar, mod);
        }
    }

    /**
     * Expands --lib paths into the sorted jar list for the host classpath: a file must
     * be a .jar; a directory contributes every *.jar under it (recursively — Maven
     * library trees nest several levels deep). Sorted for determinism; nothing drops,
     * so a missing path or unwalkable directory raises.
     */
    public static List<Path> resolveLibJars(List<Path> libPaths) throws IOException {
        List<Path> jars = new ArrayList<>();
        for (Path lib : libPaths) {
            if (Files.isDirectory(lib)) {
                try (var walk = Files.walk(lib)) {
                    for (Path p : (Iterable<Path>) walk::iterator) {
                        String n = p.getFileName() == null ? null : p.getFileName().toString();
                        if (n != null && n.endsWith(".jar") && Files.isRegularFile(p)) {
                            jars.add(p);
                        }
                    }
                }
            } else if (Files.isRegularFile(lib)) {
                jars.add(lib);
            } else {
                throw new IOException("--lib path is neither a jar nor a directory: " + lib);
            }
        }
        jars.sort(Comparator.naturalOrder());
        return Collections.unmodifiableList(jars);
    }

    // ---------------- loading

    private static SmokeReport run(Path modJar, ClassLoader modLoader) throws IOException {
        int total = 0, skipped = 0, parseable = 0, loaded = 0, failed = 0;
        Map<String, Integer> missing = new TreeMap<>();
        Map<String, String> failures = new TreeMap<>();

        try (JarFile jf = new JarFile(modJar.toFile())) {
            for (var en = jf.entries(); en.hasMoreElements(); ) {
                JarEntry e = en.nextElement();
                if (!e.getName().endsWith(".class")) {
                    continue;
                }
                total++;
                String bin = binName(e.getName());
                if (isSkippable(bin)) {
                    skipped++;
                    continue;
                }
                parseable++;
                if (attemptClass(bin, e, jf, modLoader, missing, failures)) {
                    loaded++;
                } else {
                    failed++;
                }
            }
        }
        if (loaded + failed != parseable) {
            throw new IOException("smoke bucket arithmetic violated: " + loaded + " + " + failed
                    + " != " + parseable);
        }
        return new SmokeReport(total, skipped, parseable, loaded, failed, missing, failures);
    }

    /** module-info / package-info are not classes a host loader would instantiate. */
    private static boolean isSkippable(String bin) {
        return bin.equals("module-info") || bin.equals("package-info")
                || bin.endsWith(".module-info") || bin.endsWith(".package-info");
    }

    /** True = LOADED; any Throwable is reported and false returned. No load error escapes. */
    private static boolean attemptClass(String bin, JarEntry entry, JarFile j, ClassLoader loader,
                                        Map<String, Integer> missing, Map<String, String> failures) {
        try {
            byte[] bytes;
            try (InputStream in = j.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
            Class.forName(bin, false, loader); // define; NO static initializers
            String self = bin.replace('.', '/');
            for (String ref : constantPoolClassRefs(bytes)) {
                if (ref.equals(self)) {
                    continue;
                }
                try {
                    forceResolve(ref, loader);
                } catch (ClassNotFoundException | LinkageError rex) {
                    // Rethrow with the ABSENT class as the message so the missing-symbol
                    // sample (which reads the root cause's message) captures the ref.
                    throw new ClassNotFoundException(binName(ref), rex);
                }
            }
            return true;
        } catch (Throwable t) {
            failures.put(bin, reason(t));
            String symbol = missingSymbol(t);
            if (symbol != null) {
                missing.merge(symbol, 1, Integer::sum);
            }
            return false;
        }
    }

    /** Resolves one internal name through the loader; arrays unwrap to their element. */
    private static void forceResolve(String internalName, ClassLoader loader) throws ClassNotFoundException {
        String bin = binName(internalName);
        if (!bin.startsWith("[")) {
            Class.forName(bin, false, loader);
            return;
        }
        String elem = bin;
        while (elem.startsWith("[")) {
            elem = elem.substring(1);
        }
        if (elem.startsWith("L") && elem.endsWith(";")) {
            Class.forName(elem.substring(1, elem.length() - 1), false, loader);
        }
        // primitive arrays ([I etc.) need no class lookup
    }

    /** Entry path {@code q/foo/Bar.class} -> binary name {@code q.foo.Bar}. */
    private static String binName(String entryName) {
        String noExt = entryName.endsWith(".class")
                ? entryName.substring(0, entryName.length() - ".class".length()) : entryName;
        return noExt.replace('/', '.');
    }

    // ---------------- failure analysis

    /** Root cause class + short message, message capped at 200 chars. */
    private static String reason(Throwable t) {
        Throwable root = rootOf(t);
        String cls = root.getClass().getSimpleName();
        String msg = root.getMessage();
        if (msg == null || msg.isEmpty()) {
            return cls;
        }
        String trim = msg.trim();
        return cls + ": " + (trim.length() > 200 ? trim.substring(0, 200) + "..." : trim);
    }

    /**
     * The absent class named by a CNFE/NCDFE chain, dotted; null when the failure does
     * not name a missing class (ClassFormatError, UnsupportedClassVersionError...).
     */
    private static String missingSymbol(Throwable t) {
        Throwable root = rootOf(t);
        if (root instanceof ClassNotFoundException c) {
            return firstToken(c.getMessage());
        }
        if (root instanceof NoClassDefFoundError e) {
            return firstToken(e.getMessage()); // NCDFE message is the internal name
        }
        return null;
    }

    private static Throwable rootOf(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    private static String firstToken(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.split("\\s+")[0].replace('/', '.');
    }

    // ---------------- constant pool reference scan

    /**
     * Every CONSTANT_Class name in the classfile's constant pool (tag 7): method/field
     * owners, method descriptor types, supertypes, LDC class literals and catch types
     * all flow through CONSTANT_Class entries. Runs only after defineClass succeeded on
     * the SAME bytes, so the pool is structurally valid; a scanner surprise yields an
     * empty list rather than a crash — the class verdict is already given.
     */
    private static List<String> constantPoolClassRefs(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) {
                return List.of();
            }
            in.readUnsignedShort(); // minor
            in.readUnsignedShort(); // major
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            List<Integer> classIdx = new ArrayList<>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classIdx.add(in.readUnsignedShort()); // CONSTANT_Class
                    case 8, 16, 19, 20 -> in.skipBytes(2);        // String, MethodType, Module, Package
                    case 15 -> in.skipBytes(3);                    // MethodHandle
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    // Integer, Float, Fieldref, Methodref, InterfaceMethodref,
                    // NameAndType, Dynamic, InvokeDynamic
                    case 5, 6 -> {                                // Long, Double: two pool slots
                        in.skipBytes(8);
                        i++;
                    }
                    default -> {
                        return List.of(); // unknown tag — see javadoc
                    }
                }
            }
            List<String> refs = new ArrayList<>(classIdx.size());
            for (int idx : classIdx) {
                String name = idx < utf8.length ? utf8[idx] : null;
                if (name != null) {
                    refs.add(name);
                }
            }
            return refs;
        } catch (IOException unexpected) {
            return List.of();
        }
    }
}