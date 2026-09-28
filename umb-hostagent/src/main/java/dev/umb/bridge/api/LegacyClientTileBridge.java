package dev.umb.bridge.api;

/**
 * Shared render-thread handoff for client-side legacy tile entities.
 *
 * <p>The provider is installed by the active legacy client facade.  Keeping this tiny registry
 * in the parent-delegated bridge API avoids making the host renderer guess which legacy child
 * classloader owns a facade; the returned value is intentionally opaque to the API. Providers
 * are scoped by era because 1.12.2 and 1.16.5 do not share the 1.7.10 UmbWorld.</p>
 */
public final class LegacyClientTileBridge {
    public interface Provider {
        Object tileAt(int x, int y, int z);

        /** Bounded diagnostic; implementations may report their key set and owning dimension. */
        default String diagnose(String requestedDimension, int x, int y, int z) {
            return "provider=present requestedDim=" + String.valueOf(requestedDimension)
                    + " requested=" + x + "," + y + "," + z;
        }
    }

    private static final java.util.concurrent.ConcurrentMap<String, Provider> PROVIDERS =
            new java.util.concurrent.ConcurrentHashMap<String, Provider>();

    private LegacyClientTileBridge() {
    }

    public static void install(Provider value) {
        install("1.7.10", value);
    }

    public static void install(String era, Provider value) {
        String key = normalizeEra(era);
        if (value == null) PROVIDERS.remove(key);
        else PROVIDERS.put(key, value);
    }

    public static Object tileAt(int x, int y, int z) {
        return tileAt("1.7.10", x, y, z);
    }

    public static Object tileAt(String era, int x, int y, int z) {
        Provider value = PROVIDERS.get(normalizeEra(era));
        if (value == null) return null;
        try {
            return value.tileAt(x, y, z);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean available() {
        return !PROVIDERS.isEmpty();
    }

    public static boolean available(String era) {
        return PROVIDERS.containsKey(normalizeEra(era));
    }

    public static String diagnose(String era, String requestedDimension, int x, int y, int z) {
        Provider value = PROVIDERS.get(normalizeEra(era));
        if (value == null) {
            return "provider=not-installed era=" + normalizeEra(era)
                    + " requestedDim=" + String.valueOf(requestedDimension)
                    + " requested=" + x + "," + y + "," + z;
        }
        try {
            return "era=" + normalizeEra(era) + " "
                    + value.diagnose(requestedDimension, x, y, z);
        } catch (Throwable failure) {
            return "era=" + normalizeEra(era) + " provider-diagnose-threw="
                    + failure.getClass().getName();
        }
    }

    private static String normalizeEra(String era) {
        return era == null || era.length() == 0 ? "1.7.10" : era;
    }
}

// MIRROR of the canonical umb-bridge-api owned by Lane A (umb-legacy) -- do not hand-edit.
// Synced verbatim by tools/build-hostagent.ps1 from
// umb-legacy/src/bridge-api/java/dev/umb/bridge/api/ on every build. If you need to change the
// boundary contract, change it there (and change BOTH sides together per DESIGN.md).
