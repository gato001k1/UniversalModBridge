package java.util.jar;

import java.io.InputStream;

/**
 * Java-21 compatibility shim for Forge 1.12.2's removed Pack200 API.
 *
 * This class is installed with --patch-module java.base for the probe process;
 * an ordinary application/child classloader is forbidden from defining
 * java.* classes. The implementation delegates through the context loader so
 * Commons Compress remains an ordinary isolated-loader dependency.
 */
public final class Pack200 {
    private Pack200() {}

    public static Unpacker newUnpacker() {
        return new CommonsUnpacker();
    }

    public interface Unpacker {
        void unpack(InputStream in, JarOutputStream out) throws java.io.IOException;
    }

    private static final class CommonsUnpacker implements Unpacker {
        @Override
        public void unpack(InputStream in, JarOutputStream out) throws java.io.IOException {
            try {
                ClassLoader context = Thread.currentThread().getContextClassLoader();
                // Do not use Pack200CompressorInputStream here: its compatibility path
                // consults java.util.jar.Pack200 and would recurse into this shim. The
                // Commons Compress Harmony unpacker is the underlying implementation.
                Class<?> archiveType = Class.forName(
                        "org.apache.commons.compress.harmony.unpack200.Archive", true, context);
                System.err.println("PACK200-SHIM Archive loader=" + archiveType.getClassLoader()
                        + " codeSource=" + codeSource(archiveType) + " tccl=" + context);
                java.lang.reflect.Constructor<?> ctor = archiveType.getConstructor(
                        InputStream.class, JarOutputStream.class);
                Object archive = ctor.newInstance(in, out);
                archiveType.getMethod("unpack").invoke(archive);
            } catch (java.lang.reflect.InvocationTargetException e) {
                Throwable cause = e.getCause();
                System.err.println("PACK200-SHIM failure=" + cause);
                if (cause instanceof java.io.IOException) throw (java.io.IOException) cause;
                throw new java.io.IOException("Commons Compress Pack200 unpack failed", cause);
            } catch (ReflectiveOperationException e) {
                throw new java.io.IOException("Commons Compress Pack200 implementation unavailable", e);
            }
        }

        private static Object codeSource(Class<?> type) {
            try { return type.getProtectionDomain().getCodeSource(); }
            catch (Throwable ignored) { return "<unavailable>"; }
        }
    }
}
