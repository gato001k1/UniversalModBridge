package dev.umb.pipeline.bridge;

import dev.umb.pipeline.SmokeLoader;
import dev.umb.pipeline.SmokeReport;

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
 * M12 universal host harness (Decision C).
 *
 * <p>A single 26.2 {@link HostUniverse} image (research/jars/26.2/client.jar + 88 libs,
 * JDK 25, classfile 69) that can smoke + launch ANY one research/out/campaign/* jar
 * (1.7.10, 1.12.2, 1.21.11) each in its own {@link ModLoader} child — isolation
 * {@code mod ← host ← platform} holds. Sequential gate (A): one host, one mod at a
 * time, each ModLoader child closed after the run. Concurrent stretch (B): one host
 * ALIVE with N ModLoaders + one shared {@link InteropRegistry} (§29–30 identity
 * across loaders) — {@link HostUniverse} lifetime == registry lifetime.
 *
 * <p>Deterministic: lib resolution sorted, TreeMap samples. Honest failures: every
 * named {@link MaterializationException} kind surfaces, nothing stubbed (D4). Original
 * jars never mutated (§24). CC0 synthetic fixtures (ASM 9.9) only in tests.
 */
public final class UniversalHost implements AutoCloseable {

    private final HostUniverse universe;
    private final InteropRegistry registry;
    private final List<ModLoader> mods = new ArrayList<>();
    private final Path hostJar;
    private final List<Path> libJars;
    private boolean closed;

    public UniversalHost(Path hostJar, List<Path> libJars) throws IOException {
        if (hostJar == null || !Files.isRegularFile(hostJar)) {
            throw new IOException("host jar not found: " + hostJar);
        }
        List<Path> resolved = libJars == null ? List.of() : List.copyOf(libJars);
        this.hostJar = hostJar;
        this.libJars = List.copyOf(SmokeLoader.resolveLibJars(resolved));
        this.universe = new HostUniverse(hostJar, this.libJars);
        this.registry = new InteropRegistry();
    }

    public HostUniverse universe() {
        return universe;
    }

    public InteropRegistry registry() {
        return registry;
    }

    public Path hostJar() {
        return hostJar;
    }

    public List<Path> libJars() {
        return libJars;
    }

    /**
     * Open a mod jar as a child of this host universe. Sequential gate: open one,
     * drive/smoke it, close it, then open the next. Concurrent stretch: open many
     * and keep them alive together — the shared registry sees one host instance
     * across all loaders.
     */
    public ModLoader openMod(Path modJar) throws IOException {
        if (closed) {
            throw new IOException("UniversalHost already closed");
        }
        if (modJar == null || !Files.isRegularFile(modJar)) {
            throw new IOException("mod jar not found: " + modJar);
        }
        ModLoader ml = new ModLoader(modJar, universe);
        mods.add(ml);
        return ml;
    }

    /**
     * Smoke a mod jar UNDER this already-open host universe (no new host loader).
     * Shape is identical to {@link SmokeLoader#smoke(Path, Path, List)} — every
     * *.class entry defined via Class.forName(false, modLoader) then every
     * CONSTANT_Class (tag 7) pool reference force-resolved through the SAME loader
     * — but the host is the single live image, so all sequential smokes share the
     * same host instance. §24: original jar never mutated.
     */
    public SmokeReport smoke(Path modJar) throws IOException {
        ModLoader ml = openMod(modJar);
        try {
            return smokeUnder(modJar, ml.loader());
        } finally {
            // Sequential gate keeps host alive but may close the mod child eagerly;
            // caller that wants concurrent retention should call openMod + smokeUnder directly.
            // Here we close the child after the report so sequential reuse is safe and
            // file handles don't leak; concurrent callers should use openMod + manual lifecycle.
            // To preserve the concurrent use-case, DON'T auto-close when the caller already
            // holds concurrent mods — instead only close the just-opened child if the host
            // currently holds exactly that one extra mod. Simpler: always close the
            // dedicated smoke child; concurrent hosts should call smokeUnder via openMod.
            ml.close();
            mods.remove(ml);
        }
    }

    /**
     * Smoke already-opened mod loader under this host (concurrent-friendly variant).
     */
    public static SmokeReport smokeUnder(Path modJar, ClassLoader modLoader) throws IOException {
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
            throw new IOException("smoke bucket arithmetic violated: " + loaded + " + " + failed + " != " + parseable);
        }
        return new SmokeReport(total, skipped, parseable, loaded, failed, missing, failures);
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        IOException first = null;
        for (ModLoader ml : new ArrayList<>(mods)) {
            try {
                ml.close();
            } catch (IOException e) {
                if (first == null) first = e;
                else first.addSuppressed(e);
            }
        }
        mods.clear();
        try {
            universe.close();
        } catch (IOException e) {
            if (first == null) first = e;
            else first.addSuppressed(e);
        }
        if (first != null) throw first;
    }

    // ---------------- helpers (mirrors SmokeLoader internals, kept deterministic)

    private static boolean isSkippable(String bin) {
        return bin.equals("module-info") || bin.equals("package-info")
                || bin.endsWith(".module-info") || bin.endsWith(".package-info");
    }

    private static boolean attemptClass(String bin, JarEntry entry, JarFile j, ClassLoader loader,
                                        Map<String, Integer> missing, Map<String, String> failures) {
        try {
            byte[] bytes;
            try (InputStream in = j.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
            Class.forName(bin, false, loader);
            String self = bin.replace('.', '/');
            for (String ref : constantPoolClassRefs(bytes)) {
                if (ref.equals(self)) continue;
                try {
                    forceResolve(ref, loader);
                } catch (ClassNotFoundException | LinkageError rex) {
                    throw new ClassNotFoundException(binName(ref), rex);
                }
            }
            return true;
        } catch (Throwable t) {
            failures.put(bin, reason(t));
            String symbol = missingSymbol(t);
            if (symbol != null) missing.merge(symbol, 1, Integer::sum);
            return false;
        }
    }

    private static void forceResolve(String internalName, ClassLoader loader) throws ClassNotFoundException {
        String bin = binName(internalName);
        if (!bin.startsWith("[")) {
            Class.forName(bin, false, loader);
            return;
        }
        String elem = bin;
        while (elem.startsWith("[")) elem = elem.substring(1);
        if (elem.startsWith("L") && elem.endsWith(";")) {
            Class.forName(elem.substring(1, elem.length() - 1), false, loader);
        }
    }

    private static String binName(String entryName) {
        String noExt = entryName.endsWith(".class") ? entryName.substring(0, entryName.length() - ".class".length()) : entryName;
        return noExt.replace('/', '.');
    }

    private static String reason(Throwable t) {
        Throwable root = rootOf(t);
        String cls = root.getClass().getSimpleName();
        String msg = root.getMessage();
        if (msg == null || msg.isEmpty()) return cls;
        String trim = msg.trim();
        return cls + ": " + (trim.length() > 200 ? trim.substring(0, 200) + "..." : trim);
    }

    private static String missingSymbol(Throwable t) {
        Throwable root = rootOf(t);
        if (root instanceof ClassNotFoundException c) return firstToken(c.getMessage());
        if (root instanceof NoClassDefFoundError e) return firstToken(e.getMessage());
        return null;
    }

    private static Throwable rootOf(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) root = root.getCause();
        return root;
    }

    private static String firstToken(String s) {
        if (s == null) return null;
        String trimmed = s.trim();
        if (trimmed.isEmpty()) return null;
        return trimmed.split("\\s+")[0].replace('/', '.');
    }

    private static List<String> constantPoolClassRefs(byte[] bytes) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) return List.of();
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            String[] utf8 = new String[count];
            List<Integer> classIdx = new ArrayList<>();
            for (int i = 1; i < count; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1 -> utf8[i] = in.readUTF();
                    case 7 -> classIdx.add(in.readUnsignedShort());
                    case 8, 16, 19, 20 -> in.skipBytes(2);
                    case 15 -> in.skipBytes(3);
                    case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                    case 5, 6 -> { in.skipBytes(8); i++; }
                    default -> { return List.of(); }
                }
            }
            List<String> refs = new ArrayList<>(classIdx.size());
            for (int idx : classIdx) {
                String name = idx < utf8.length ? utf8[idx] : null;
                if (name != null) refs.add(name);
            }
            return refs;
        } catch (IOException unexpected) {
            return List.of();
        }
    }
}
