package dev.umb.objbridge.entity;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.UUID;

/** Reflection-only bridge from a client block-entity twin to its integrated-server legacy tile. */
final class LegacyTileCaptureClient {
    static final class Result {
        final Object capture;
        final String reason;
        Result(Object capture, String reason) { this.capture = capture; this.reason = reason; }
        static Result ok(Object value) { return new Result(value, null); }
        static Result unavailable(String why) { return new Result(null, why); }
    }

    private static volatile String lastReason = "unknown";
    private static final ConcurrentMap<MethodKey, Method> METHODS = new ConcurrentHashMap<>();
    private static final Set<MethodKey> MISSING_METHODS = ConcurrentHashMap.newKeySet();
    private static final ConcurrentMap<Class<?>, Method> RENDER_CAPTURE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> MISSING_RENDER_CAPTURE = ConcurrentHashMap.newKeySet();

    private LegacyTileCaptureClient() { }

    static Result capture(Object clientBlockEntity, float partialTick) {
        if (clientBlockEntity == null) return Result.unavailable("client-block-entity-null");
        try {
            // Prefer the client-facade tile when it exists.  The integrated-server copy is
            // authoritative for world state, but many legacy client renderers initialize their
            // model/door/info fields only on the client-side tile during the visual sync.  Using
            // the server copy first made HBM renderers see an unbound dispatcher/model registry
            // (and doors see a null DoorDecl), even though client_block_entity reported a valid
            // legacy core.  Server resolution remains the generic fallback for providers that
            // do not expose a client tile.
            Object clientTile = clientFacadeLegacyTile(clientBlockEntity);
            if (clientTile != null) {
                Object captured = captureClientTile(clientTile, partialTick);
                if (captured != null) return Result.ok(captured);
            }
            Object handle = invokeNoArg(clientBlockEntity, "legacyHandle");
            if (handle == null) {
                Object serverBlockEntity = integratedServerBlockEntity(clientBlockEntity);
                if (serverBlockEntity != null) handle = invokeNoArg(serverBlockEntity, "legacyHandle");
            }
            if (handle == null) {
                if (clientTile == null) clientTile = clientFacadeLegacyTile(clientBlockEntity);
                if (clientTile != null) {
                    Object captured = captureClientTile(clientTile, partialTick);
                    if (captured != null) return Result.ok(captured);
                    lastReason = "client-facade-tile-capture-null:" + clientTile.getClass().getName();
                }
                return Result.unavailable(lastReason);
            }
            Method method = RENDER_CAPTURE.get(handle.getClass());
            if (method == null && !MISSING_RENDER_CAPTURE.contains(handle.getClass())) {
                method = findMethod(handle.getClass(), "renderCapture", float.class);
                if (method == null) {
                    MISSING_RENDER_CAPTURE.add(handle.getClass());
                    return Result.unavailable("renderCapture-method-missing:" + handle.getClass().getName());
                }
                Method existing = RENDER_CAPTURE.putIfAbsent(handle.getClass(), method);
                if (existing != null) method = existing;
            }
            if (method == null) return Result.unavailable("renderCapture-method-missing:" + handle.getClass().getName());
            return Result.ok(method.invoke(handle, partialTick));
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            return Result.unavailable("renderCapture-threw:" + root.getClass().getName());
        }
    }

    /**
     * The client-universe facade already maintains a synchronized raw legacy TileEntity cache.
     * Use it when a render-thread ServerLevel lookup cannot see the authoritative block entity;
     * this is the same generic fallback used for every legacy TESR, with no mod-specific path.
     */
    private static Object clientFacadeLegacyTile(Object clientBlockEntity) {
        try {
            Object pos = invokeNoArg(clientBlockEntity, "getBlockPos");
            if (pos == null) {
                lastReason = "client-block-pos-null";
                return null;
            }
            int x = coordinate(pos, "getX");
            int y = coordinate(pos, "getY");
            int z = coordinate(pos, "getZ");
            String era = legacyEra(clientBlockEntity);
            String requestedDimension = clientDimension(clientBlockEntity);
            Class<?> sharedBridge = loadClass("dev.umb.bridge.api.LegacyClientTileBridge", null);
            if (sharedBridge != null) {
                Method scopedTileAt = findMethod(sharedBridge, "tileAt", String.class,
                        int.class, int.class, int.class);
                if (scopedTileAt != null) {
                    Object tile = scopedTileAt.invoke(null, era, x, y, z);
                    if (tile != null) return tile;
                    String providerReason = scopedProviderReason(sharedBridge, era,
                            requestedDimension, x, y, z);
                    // A provider for another era must never be presented as an empty 1.7.10
                    // cache, nor should a 1.12.2/1.16.5 request fall through to the 1.7.10
                    // facade classloader.
                    if (!"1.7.10".equals(era)) {
                        lastReason = providerReason;
                        return null;
                    }
                    lastReason = providerReason;
                } else {
                    Method tileAt = findMethod(sharedBridge, "tileAt", int.class, int.class, int.class);
                    if (tileAt != null) {
                        Object tile = tileAt.invoke(null, x, y, z);
                        if (tile != null) return tile;
                    }
                }
            }
            Class<?> facade = loadClass("dev.umb.legacy.legacyside.LegacyClientFacade",
                    Thread.currentThread().getContextClassLoader());
            if (facade == null) {
                if (sharedBridge == null) lastReason = "client-tile-bridge-and-facade-class-missing";
                return null;
            }
            Method lookup = findMethod(facade, "clientLegacyTileAt", int.class, int.class, int.class);
            if (lookup == null) {
                lastReason = "client-facade-lookup-method-missing";
                return null;
            }
            Object tile = lookup.invoke(null, x, y, z);
            if (tile == null) {
                lastReason = lastReason.startsWith("client-tile-provider-")
                        ? lastReason + ";facade-miss=" + x + "," + y + "," + z
                        : "client-facade-tile-missing:" + x + "," + y + "," + z;
            }
            return tile;
        } catch (Throwable failure) {
            lastReason = "client-facade-lookup-threw:" + failure.getClass().getName();
            return null;
        }
    }

    private static int coordinate(Object pos, String methodName) throws Exception {
        Object value = invokeNoArg(pos, methodName);
        if (!(value instanceof Number)) throw new IllegalStateException("coordinate-not-number:" + methodName);
        return ((Number) value).intValue();
    }

    private static Object captureClientTile(Object tile, float partialTick) {
        try {
            Class<?> capture = loadClass("dev.umb.legacy.legacyside.render.LegacyRenderCapture",
                    tile.getClass().getClassLoader());
            if (capture == null) {
                lastReason = "client-facade-capture-class-missing";
                return null;
            }
            Method method = findMethod(capture, "captureTile", tile.getClass(), float.class);
            if (method == null) {
                // captureTile is declared against the legacy TileEntity superclass; find it by
                // assignability so this remains valid for mod-defined TileEntity subclasses.
                for (Method candidate : capture.getMethods()) {
                    Class<?>[] parameters = candidate.getParameterTypes();
                    if (candidate.getName().equals("captureTile") && parameters.length == 2
                            && parameters[1] == float.class && parameters[0].isAssignableFrom(tile.getClass())) {
                        candidate.setAccessible(true);
                        method = candidate;
                        break;
                    }
                }
            }
            if (method == null) {
                lastReason = "client-facade-capture-method-missing";
                return null;
            }
            return method.invoke(null, tile, Float.valueOf(partialTick));
        } catch (Throwable failure) {
            Throwable root = failure;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            lastReason = "client-facade-tile-capture-threw:" + root.getClass().getName();
            return null;
        }
    }

    private static Class<?> loadClass(String name, ClassLoader preferred) {
        if (preferred != null) {
            try { return Class.forName(name, false, preferred); }
            catch (Throwable ignored) { }
        }
        try { return Class.forName(name, false, LegacyTileCaptureClient.class.getClassLoader()); }
        catch (Throwable ignored) { }
        try { return Class.forName(name); }
        catch (Throwable ignored) { return null; }
    }

    private static boolean providerInstalled(Class<?> bridge) {
        try {
            Method available = findMethod(bridge, "available");
            return available != null && Boolean.TRUE.equals(available.invoke(null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String scopedProviderReason(Class<?> bridge, String era, String dimension,
                                               int x, int y, int z) {
        boolean installed = false;
        try {
            Method available = findMethod(bridge, "available", String.class);
            installed = available != null && Boolean.TRUE.equals(available.invoke(null, era));
        } catch (Throwable ignored) { }
        String detail = "diagnostic-unavailable";
        try {
            Method diagnose = findMethod(bridge, "diagnose", String.class, String.class,
                    int.class, int.class, int.class);
            if (diagnose != null) {
                detail = String.valueOf(diagnose.invoke(null, era, dimension, x, y, z));
            }
        } catch (Throwable failure) {
            detail = "diagnostic-threw=" + failure.getClass().getName();
        }
        return "client-tile-provider-" + (installed ? "miss" : "not-installed")
                + ":" + detail;
    }

    private static String legacyEra(Object clientBlockEntity) {
        try {
            Object value = invokeNoArg(clientBlockEntity, "legacyEraForRender");
            if (value != null && !String.valueOf(value).isEmpty()) return String.valueOf(value);
        } catch (Throwable ignored) { }
        return "1.7.10";
    }

    private static String clientDimension(Object clientBlockEntity) {
        try {
            Object level = invokeNoArg(clientBlockEntity, "getLevel");
            Object dimension = level == null ? null : invokeNoArg(level, "dimension");
            return dimension == null ? "unknown" : String.valueOf(dimension);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    private static Object integratedServerBlockEntity(Object clientBlockEntity) throws Exception {
        Object pos = invokeNoArg(clientBlockEntity, "getBlockPos");
        if (pos == null) { lastReason = "client-block-pos-null"; return null; }
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
        Method instance = findMethod(minecraft, "getInstance");
        if (instance == null) { lastReason = "minecraft-instance-method-missing"; return null; }
        Object client = instance.invoke(null);
        if (client == null) { lastReason = "minecraft-instance-null"; return null; }
        Method serverMethod = findMethod(client.getClass(), "getSingleplayerServer");
        if (serverMethod == null) { lastReason = "not-singleplayer:getSingleplayerServer-method-missing"; return null; }
        Object server = serverMethod.invoke(client);
        if (server == null) { lastReason = "not-singleplayer:server-null"; return null; }
        Method levels = findMethod(server.getClass(), "getAllLevels");
        if (levels == null) { lastReason = "server-levels-method-missing"; return null; }
        Object value = levels.invoke(server);
        if (!(value instanceof Iterable<?>)) { lastReason = "server-levels-not-iterable"; return null; }
        Method getBlockEntity = null;
        int levelsSeen = 0;
        int compatibleMethods = 0;
        for (Object level : (Iterable<?>) value) {
            if (level == null) continue;
            levelsSeen++;
            if (getBlockEntity == null) getBlockEntity = findCompatibleMethod(level.getClass(), "getBlockEntity", pos.getClass());
            if (getBlockEntity == null) continue;
            compatibleMethods++;
            Object found;
            try {
                found = getBlockEntity.invoke(level, pos);
            } catch (IllegalArgumentException wrongPositionType) {
                lastReason = "server-block-entity-position-type-mismatch:" + pos.getClass().getName()
                        + " method=" + getBlockEntity.getParameterTypes()[0].getName();
                continue;
            }
            if (found != null) return found;
        }
        lastReason = "server-block-entity-not-found:" + pos + " levels=" + levelsSeen
                + " compatibleMethods=" + compatibleMethods;
        return null;
    }

    private static Object invokeNoArg(Object target, String name) throws Exception {
        Method method = findMethod(target.getClass(), name);
        if (method == null) return null;
        method.setAccessible(true);
        return method.invoke(target);
    }
    private static Method findMethod(Class<?> type, String name, Class<?>... parameters) {
        MethodKey key = new MethodKey(type, name, parameters);
        Method cached = METHODS.get(key);
        if (cached != null) return cached;
        if (MISSING_METHODS.contains(key)) return null;
        Method found = findMethodUncached(type, name, parameters);
        if (found == null) {
            MISSING_METHODS.add(key);
            return null;
        }
        found.setAccessible(true);
        Method existing = METHODS.putIfAbsent(key, found);
        return existing == null ? found : existing;
    }

    private static Method findMethodUncached(Class<?> type, String name, Class<?>... parameters) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredMethod(name, parameters); }
            catch (NoSuchMethodException ignored) { }
        }
        try { return type.getMethod(name, parameters); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    /** Find a public/protected server method when the client supplied a BlockPos subclass. */
    private static Method findCompatibleMethod(Class<?> type, String name, Class<?> argument) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (method.getName().equals(name) && parameters.length == 1
                        && parameters[0].isAssignableFrom(argument)) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        for (Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 1
                    && parameters[0].isAssignableFrom(argument)) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    private static final class MethodKey {
        private final Class<?> type;
        private final String name;
        private final Class<?>[] parameters;

        MethodKey(Class<?> type, String name, Class<?>[] parameters) {
            this.type = type;
            this.name = name;
            this.parameters = parameters.clone();
        }

        @Override public boolean equals(Object other) {
            if (!(other instanceof MethodKey)) return false;
            MethodKey key = (MethodKey) other;
            return type == key.type && name.equals(key.name)
                    && Arrays.equals(parameters, key.parameters);
        }

        @Override public int hashCode() {
            return (31 * (31 * System.identityHashCode(type) + name.hashCode()))
                    + Arrays.hashCode(parameters);
        }
    }
}
