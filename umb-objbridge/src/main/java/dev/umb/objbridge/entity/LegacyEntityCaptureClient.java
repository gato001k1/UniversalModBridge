package dev.umb.objbridge.entity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Reflection-only host/legacy bridge; objbridge remains independent of hostagent classes. */
final class LegacyEntityCaptureClient {
    private static volatile Field HANDLE;
    private static volatile Method RENDER_CAPTURE;
    private static volatile Method RENDER_CAPTURE_VIEWER;
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Set<String>> MISSING_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, Method>> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Set<String>> MISSING_METHODS = new ConcurrentHashMap<>();
    private static long fieldDiscoveries;
    private static long methodDiscoveries;

    private LegacyEntityCaptureClient() {}

    static final class Result {
        final Object capture;
        final String reason;
        Result(Object capture, String reason) {
            this.capture = capture;
            this.reason = reason;
        }
        static Result ok(Object capture) { return new Result(capture, null); }
        static Result unavailable(String reason) { return new Result(null, reason); }
    }

    static Result capture(Object hostEntity, float partialTick) {
        return capture(hostEntity, partialTick, -1);
    }

    /**
     * {@code riderCameraMode} is the local player's camera mode (legacy thirdPersonView: 0 first
     * person, 1 back, 2 front) when {@code hostEntity} is the vehicle that player rides, else -1.
     */
    static Result capture(Object hostEntity, float partialTick, int riderCameraMode) {
        if (hostEntity == null) return Result.unavailable("host-entity-null");
        try {
            Object handle = handle(hostEntity);
            if (handle != null) return invokeCapture(handle, partialTick, riderCameraMode);

            Object serverEntity = integratedServerEntity(hostEntity);
            if (serverEntity == null) return Result.unavailable(lastReason);
            handle = handle(serverEntity);
            if (handle == null) return Result.unavailable("server-handle-null");
            return invokeCapture(handle, partialTick, riderCameraMode);
        } catch (Throwable failure) {
            Throwable cause = failure instanceof InvocationTargetException
                    && failure.getCause() != null ? failure.getCause() : failure;
            return Result.unavailable("capture-bridge-throw:" + cause.getClass().getName());
        }
    }

    private static volatile String lastReason = "unknown";

    private static Object handle(Object entity) throws IllegalAccessException {
        Field f = findField(entity.getClass(), "handle");
        if (f == null) {
            lastReason = "handle-field-missing:" + entity.getClass().getName();
            return null;
        }
        f.setAccessible(true);
        Object value = f.get(entity);
        if (value == null) lastReason = "handle-null:" + entity.getClass().getName();
        return value;
    }

    static Result invokeCapture(Object handle, float partialTick, int riderCameraMode) throws Exception {
        if (riderCameraMode >= 0) {
            Method viewer = RENDER_CAPTURE_VIEWER;
            if (viewer == null || !viewer.getDeclaringClass().isAssignableFrom(handle.getClass())) {
                viewer = findMethod(handle.getClass(), "renderCapture", float.class, int.class);
                if (viewer != null) {
                    viewer.setAccessible(true);
                    RENDER_CAPTURE_VIEWER = viewer;
                }
            }
            // An older bridge without the camera-aware overload keeps the neutral capture.
            if (viewer != null) {
                try {
                    return Result.ok(viewer.invoke(handle, partialTick, riderCameraMode));
                } catch (InvocationTargetException ex) {
                    Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                    return Result.unavailable("renderCapture-threw:" + cause.getClass().getName());
                }
            }
        }
        Method method = RENDER_CAPTURE;
        if (method == null || !method.getDeclaringClass().isAssignableFrom(handle.getClass())) {
            method = findMethod(handle.getClass(), "renderCapture", float.class);
            if (method == null) return Result.unavailable("renderCapture-method-missing:" + handle.getClass().getName());
            method.setAccessible(true);
            RENDER_CAPTURE = method;
        }
        try {
            return Result.ok(method.invoke(handle, partialTick));
        } catch (InvocationTargetException ex) {
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            return Result.unavailable("renderCapture-threw:" + cause.getClass().getName());
        }
    }

    /** Resolves the server twin in an integrated server; the client twin intentionally has no handle. */
    private static Object integratedServerEntity(Object clientEntity) throws Exception {
        UUID uuid = entityUuid(clientEntity);
        if (uuid == null) return null;
        Class<?> minecraft = Class.forName("net.minecraft.client.Minecraft");
        Method instanceMethod = findMethod(minecraft, "getInstance");
        if (instanceMethod == null) {
            lastReason = "minecraft-instance-method-missing";
            return null;
        }
        Object client = instanceMethod.invoke(null);
        if (client == null) {
            lastReason = "minecraft-instance-null";
            return null;
        }
        Method serverMethod = findMethod(client.getClass(), "getSingleplayerServer");
        if (serverMethod == null) {
            lastReason = "not-singleplayer:getSingleplayerServer-method-missing";
            return null;
        }
        Object server = serverMethod.invoke(client);
        if (server == null) {
            lastReason = "not-singleplayer:server-null";
            return null;
        }
        Method levelsMethod = findMethod(server.getClass(), "getAllLevels");
        if (levelsMethod == null) {
            lastReason = "server-levels-method-missing";
            return null;
        }
        Object levels = levelsMethod.invoke(server);
        if (!(levels instanceof Iterable<?>)) {
            lastReason = "server-levels-not-iterable";
            return null;
        }
        for (Object level : (Iterable<?>) levels) {
            if (level == null) continue;
            Method entityMethod = findMethod(level.getClass(), "getEntity", UUID.class);
            if (entityMethod == null) continue;
            Object found = entityMethod.invoke(level, uuid);
            if (found != null) return found;
        }
        lastReason = "server-entity-not-found:" + uuid;
        return null;
    }

    private static UUID entityUuid(Object entity) throws Exception {
        Method method = findMethod(entity.getClass(), "getUUID");
        if (method == null) {
            lastReason = "client-uuid-method-missing";
            return null;
        }
        Object value = method.invoke(entity);
        if (!(value instanceof UUID)) {
            lastReason = "client-uuid-null";
            return null;
        }
        return (UUID) value;
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... params) {
        String key = name + java.util.Arrays.toString(params);
        Map<String, Method> typeCache = METHOD_CACHE.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>());
        Method cached = typeCache.get(key);
        if (cached != null) return cached;
        Set<String> missing = MISSING_METHODS.computeIfAbsent(type, ignored -> ConcurrentHashMap.newKeySet());
        if (missing.contains(key)) return null;
        methodDiscoveries++;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Method found = c.getDeclaredMethod(name, params);
                typeCache.put(key, found);
                return found;
            }
            catch (NoSuchMethodException ignored) { }
        }
        try {
            Method found = type.getMethod(name, params);
            typeCache.put(key, found);
            return found;
        } catch (NoSuchMethodException ignored) {
            missing.add(key);
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Map<String, Field> typeCache = FIELD_CACHE.computeIfAbsent(type, ignored -> new ConcurrentHashMap<>());
        Field cached = typeCache.get(name);
        if (cached != null) return cached;
        Set<String> missing = MISSING_FIELDS.computeIfAbsent(type, ignored -> ConcurrentHashMap.newKeySet());
        if (missing.contains(name)) return null;
        fieldDiscoveries++;
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try {
                Field found = c.getDeclaredField(name);
                typeCache.put(name, found);
                return found;
            }
            catch (NoSuchFieldException ignored) { }
        }
        missing.add(name);
        return null;
    }

    static void probeReflectionCachesForTests(Class<?> type) {
        findField(type, "handle");
        findMethod(type, "getUUID");
    }

    static long[] reflectionDiscoveryCountsForTests() {
        return new long[] {fieldDiscoveries, methodDiscoveries};
    }
}
