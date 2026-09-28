package dev.umb.legacy.legacyside;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

import cpw.mods.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import cpw.mods.fml.common.asm.transformers.deobf.FMLRemappingAdapter;
import cpw.mods.fml.relauncher.FMLRelaunchLog;
import cpw.mods.fml.relauncher.Side;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;

/**
 * Turns the PRODUCTION Forge universal jar (whose own bytecode references vanilla by notch names)
 * into an SRG-named jar, using FML's own {@code FMLDeobfuscatingRemapper} and
 * {@code FMLRemappingAdapter} so the result is byte-for-byte what FML would have produced at
 * classload in a production boot.
 *
 * <p>This runs INSIDE a legacy loader whose sources include the untouched notch Mojang client jar.
 * That jar is needed only as a descriptor oracle: {@code FMLDeobfuscatingRemapper.setup()} resolves
 * every FD line's field descriptor through {@code ClassPatchManager.getPatchedResource(notchName)},
 * and without the notch bytes every field mapping degrades to a silent no-op.</p>
 *
 * <p>Entry point is static and takes/returns only java.* types, so the bootstrap can call it
 * reflectively without any shared interface.</p>
 */
public final class ForgeSrgifier {

    private ForgeSrgifier() {
    }

    /**
     * @param gameDir       minecraft home (only used for FML logging state)
     * @param srcJarPath    the production forge universal jar
     * @param outJarPath    where to write the SRG-named jar
     * @param skipJarPath   a jar whose entries win over the remapped output (our SRG runtime jar), or ""
     * @param deobfResource classloader resource holding the packed SRG data
     * @return a human readable summary; also the machine-readable counters, one per line
     */
    public static String remap(String gameDir, String srcJarPath, String outJarPath,
                               String skipJarPath, String deobfResource) throws Exception {
        LaunchClassLoader lcl = (LaunchClassLoader) Launch.classLoader;
        Statics.set(FMLRelaunchLog.class, "side", Side.SERVER);
        Statics.set(FMLRelaunchLog.class, "minecraftHome", new File(gameDir));

        long t0 = System.currentTimeMillis();
        FMLDeobfuscatingRemapper.INSTANCE.setup(new File(gameDir), lcl, deobfResource);
        long setupMs = System.currentTimeMillis() - t0;
        if (!FMLDeobfuscatingRemapper.INSTANCE.isRemappedClass("aji")) {
            throw new IllegalStateException("FMLDeobfuscatingRemapper did not load any class mappings"
                    + " (resource " + deobfResource + ")");
        }

        Set<String> skip = new HashSet<String>();
        if (skipJarPath != null && !skipJarPath.isEmpty()) {
            JarFile skipJar = new JarFile(skipJarPath);
            try {
                for (Enumeration<JarEntry> e = skipJar.entries(); e.hasMoreElements(); ) {
                    String n = e.nextElement().getName();
                    if (n.endsWith(".class")) {
                        skip.add(n);
                    }
                }
            } finally {
                skipJar.close();
            }
        }

        int classes = 0;
        int resources = 0;
        int skipped = 0;
        int dropped = 0;
        StringBuilder keptVanilla = new StringBuilder();

        JarFile in = new JarFile(srcJarPath);
        File outFile = new File(outJarPath);
        if (outFile.getParentFile() != null) {
            outFile.getParentFile().mkdirs();
        }
        JarOutputStream out = new JarOutputStream(new FileOutputStream(outFile));
        try {
            Set<String> written = new HashSet<String>();
            for (Enumeration<JarEntry> e = in.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                String name = entry.getName();
                if (entry.isDirectory()) {
                    continue;
                }
                // the jar is signed; every signature artefact is meaningless once we rewrite bytecode
                String upper = name.toUpperCase(Locale.ROOT);
                if (upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF")
                        || upper.endsWith(".DSA") || upper.endsWith(".RSA")
                        || upper.startsWith("META-INF/SIG-")) {
                    dropped++;
                    continue;
                }
                byte[] bytes = readFully(in.getInputStream(entry));
                if (!name.endsWith(".class")) {
                    write(out, name, bytes, written);
                    resources++;
                    continue;
                }
                ClassReader cr = new ClassReader(bytes);
                String mapped = FMLDeobfuscatingRemapper.INSTANCE.map(cr.getClassName());
                String mappedEntry = mapped + ".class";
                if (skip.contains(mappedEntry)) {
                    skipped++;
                    continue;
                }
                if (mapped.startsWith("net/minecraft/") && !mapped.startsWith("net/minecraftforge/")) {
                    keptVanilla.append(cr.getClassName()).append(" -> ").append(mapped).append('\n');
                }
                ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                cr.accept(new FMLRemappingAdapter(cw), ClassReader.EXPAND_FRAMES);
                write(out, mappedEntry, cw.toByteArray(), written);
                classes++;
            }
        } finally {
            out.close();
            in.close();
        }

        StringBuilder b = new StringBuilder();
        b.append("srgify.setupMillis=").append(setupMs).append('\n');
        b.append("srgify.classesRemapped=").append(classes).append('\n');
        b.append("srgify.resourcesCopied=").append(resources).append('\n');
        b.append("srgify.classesSkippedAlreadyInRuntimeJar=").append(skipped).append('\n');
        b.append("srgify.signatureEntriesDropped=").append(dropped).append('\n');
        b.append("srgify.out=").append(outFile.getAbsolutePath()).append('\n');
        b.append("srgify.outBytes=").append(outFile.length()).append('\n');
        b.append("srgify.keptVanillaClasses=\n").append(keptVanilla);
        return b.toString();
    }

    private static void write(JarOutputStream out, String name, byte[] bytes, Set<String> written)
            throws Exception {
        if (!written.add(name)) {
            return;
        }
        ZipEntry ze = new ZipEntry(name);
        ze.setTime(0L);
        out.putNextEntry(ze);
        out.write(bytes);
        out.closeEntry();
    }

    private static byte[] readFully(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, in.available()));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            close(in);
        }
    }

    private static void close(OutputStream o) {
        try {
            o.close();
        } catch (Exception ignored) {
            // nothing useful
        }
    }

    private static void close(InputStream i) {
        try {
            i.close();
        } catch (Exception ignored) {
            // nothing useful
        }
    }
}
