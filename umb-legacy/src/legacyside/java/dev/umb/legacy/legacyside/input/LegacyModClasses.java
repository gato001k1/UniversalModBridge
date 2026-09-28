package dev.umb.legacy.legacyside.input;

/**
 * Mod-class resolution for plan data. Mod classes live in FML's {@code ModClassLoader}, a
 * CHILD of this class's loader — so plain {@code Class.forName} is blind to them even when
 * the mods are fully loaded, and every plan would be skipped as "absent mod". The chain
 * below tries the thread context loader, this loader, then FML's mod loader, preserving
 * {@code ClassNotFoundException} (genuinely absent mods still skip) when all fail.
 */
public final class LegacyModClasses {
    private LegacyModClasses() { }

    public static Class<?> forName(String name) throws ClassNotFoundException {
        ClassNotFoundException first = null;
        ClassLoader[] loaders = new ClassLoader[] {
                Thread.currentThread().getContextClassLoader(),
                LegacyModClasses.class.getClassLoader(),
                modClassLoader(),
        };
        for (int i = 0; i < loaders.length; i++) {
            if (loaders[i] == null) {
                continue;
            }
            try {
                return Class.forName(name, true, loaders[i]);
            } catch (ClassNotFoundException e) {
                if (first == null) {
                    first = e;
                }
            }
        }
        if (first != null) {
            throw first;
        }
        throw new ClassNotFoundException(name);
    }

    private static ClassLoader modClassLoader() {
        try {
            return cpw.mods.fml.common.Loader.instance().getModClassLoader();
        } catch (Throwable t) {
            return null;
        }
    }
}
