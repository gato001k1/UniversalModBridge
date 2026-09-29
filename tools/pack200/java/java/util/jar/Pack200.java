package java.util.jar;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** UMB's Java 21+ compatibility shim for the Pack200 API used by Forge binpatches. */
public final class Pack200 {
    private Pack200() { }
    public static Unpacker newUnpacker() { return new CommonsUnpacker(); }
    public interface Unpacker { void unpack(InputStream in, JarOutputStream out) throws java.io.IOException; }
    private static final class CommonsUnpacker implements Unpacker {
        @Override public void unpack(InputStream in, JarOutputStream out) throws java.io.IOException {
            Path packed = Files.createTempFile("umb-pack200-", ".pack");
            Path unpacked = Files.createTempFile("umb-pack200-", ".jar");
            try {
                ClassLoader loader = Thread.currentThread().getContextClassLoader();
                Class<?> archive = Class.forName("org.apache.commons.compress.harmony.unpack200.Archive", true, loader);
                Files.copy(in, packed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Object value = archive.getConstructor(String.class, String.class).newInstance(packed.toString(), unpacked.toString());
                archive.getMethod("unpack").invoke(value);
                try (JarFile jar = new JarFile(unpacked.toFile())) {
                    java.util.Enumeration<JarEntry> entries = jar.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        if (entry.isDirectory()) continue;
                        out.putNextEntry(new JarEntry(entry.getName()));
                        try (InputStream data = jar.getInputStream(entry)) { data.transferTo(out); }
                        out.closeEntry();
                    }
                }
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof java.io.IOException) throw (java.io.IOException) cause;
                throw new java.io.IOException("Commons Compress Pack200 unpack failed", cause);
            } catch (ReflectiveOperationException e) {
                throw new java.io.IOException("Commons Compress Pack200 implementation unavailable", e);
            } finally {
                Files.deleteIfExists(packed); Files.deleteIfExists(unpacked);
            }
        }
    }
}
