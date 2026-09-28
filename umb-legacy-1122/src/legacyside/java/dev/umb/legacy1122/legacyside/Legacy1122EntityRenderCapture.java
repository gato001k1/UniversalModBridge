package dev.umb.legacy1122.legacyside;

import dev.umb.bridge.api.EntityRenderCapture;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 1.12.2 entity render entry, mirroring the tile path ({@code TileHandle1122} resolves its
 * renderer from a static dispatcher) and 1.7.10's {@code LegacyRenderCapture.capture} shape:
 * look the entity's renderer up, run {@code doRender} at zero-relative coordinates inside a
 * {@code Legacy1122RenderCapture} begin/end session, and return the sealed mesh.
 *
 * <p>Same compile discipline as the rest of this module (bridge-api only, reflection-first,
 * dual MCP/SRG names, honest counted empties): a {@code RenderManager} is allocated without
 * its client constructor and wired minimally (render map populated through Forge's own
 * {@code RenderingRegistry.loadEntityRenderers}, like 1.7.10's
 * {@code RenderingRegistry.instance().loadEntityRenderers}). Texture binding never executes:
 * the universe transformer redirects it into the capture sink, so the allocated
 * {@code TextureManager} is a non-null placeholder only.
 */
final class Legacy1122EntityRenderCapture {
    private static final Object LOCK = new Object();
    private static volatile Object manager;
    private static volatile boolean managerReady;
    private static volatile String managerProblem = "manager-not-built";

    private Legacy1122EntityRenderCapture() {
    }

    static EntityRenderCapture capture(Object entity, Object world, float partialTick) {
        if (entity == null) return EntityRenderCapture.empty("", "");
        String entityClass = entity.getClass().getName();
        synchronized (LOCK) {
            try {
                Object renderManager = manager(world);
                if (renderManager == null) {
                    return EntityRenderCapture.empty(entityClass, "render-manager-" + managerProblem);
                }
                setManagerWorld(renderManager, world, entity);
                Object renderer = lookup(renderManager, entity);
                if (renderer == null) {
                    return EntityRenderCapture.empty(entityClass, "renderer-null");
                }
                double yaw = fieldDouble(entity, new String[] {"rotationYaw", "field_70177_z"}, 0.0D);
                Legacy1122RenderCapture.begin(entityClass, "");
                try {
                    invoke(renderer, new String[] {"doRender", "func_76986_a"},
                            entity, 0.0D, 0.0D, 0.0D, (float) yaw, partialTick);
                } finally {
                    // Sealed even when doRender throws mid-mesh: a partial capture still
                    // carries the geometry recorded so far, like the tile path.
                }
                EntityRenderCapture result = Legacy1122RenderCapture.end();
                if (result == null) return EntityRenderCapture.empty(entityClass, "capture-null");
                if (result.vertexCount() == 0) {
                    return EntityRenderCapture.empty(entityClass, "renderer-returned-no-geometry");
                }
                return result;
            } catch (Throwable t) {
                return EntityRenderCapture.empty(entityClass,
                        "render-threw:" + t.getClass().getName());
            }
        }
    }

    private static Object manager(Object world) {
        if (managerReady) return manager;
        try {
            ClassLoader loader = Legacy1122EntityRenderCapture.class.getClassLoader();
            Class<?> managerType = Class.forName(
                    "net.minecraft.client.renderer.entity.RenderManager", true, loader);
            Object unsafe = theUnsafe();
            Object instance = unsafeAllocate(unsafe, managerType);
            Object map = new HashMap<Class<?>, Object>();
            setField(managerType, instance,
                    new String[] {"entityRenderMap", "field_78729_o"}, map);
            Class<?> textureManagerType = Class.forName(
                    "net.minecraft.client.renderer.texture.TextureManager", true, loader);
            Object textureManager = unsafeAllocate(unsafe, textureManagerType);
            setField(managerType, instance,
                    new String[] {"renderEngine", "field_78724_e"}, textureManager);
            if (world != null) {
                setField(managerType, instance,
                        new String[] {"world", "field_78722_g"}, world);
            }
            // Same population call 1.7.10 makes (there an instance method on the Forge
            // registry singleton; here the 1.12.2 static form taking manager and map).
            Class<?> registry = Class.forName(
                    "net.minecraftforge.fml.client.registry.RenderingRegistry", true, loader);
            boolean populated = false;
            for (Method m : registry.getMethods()) {
                if (!m.getName().equals("loadEntityRenderers")) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2) continue;
                try {
                    m.setAccessible(true);
                    m.invoke(null, instance, map);
                    populated = true;
                    break;
                } catch (Throwable ignored) {
                    // Try the next overload.
                }
            }
            if (!populated) {
                managerProblem = "render-registry-populate-missing";
                return null;
            }
            manager = instance;
            managerReady = true;
            managerProblem = "";
            return instance;
        } catch (Throwable t) {
            managerProblem = t.getClass().getName();
            return null;
        }
    }

    private static void setManagerWorld(Object renderManager, Object world, Object entity) {
        if (renderManager == null || world == null) return;
        try {
            setField(renderManager.getClass(), renderManager,
                    new String[] {"world", "field_78722_g"}, world);
            // Camera-relative zeroing like 1.7.10's RenderCameraScope: doRender runs
            // at the origin, so the manager's render offset must match the entity.
            setRenderPos(renderManager, entity);
        } catch (Throwable ignored) {
            // Optional camera state; a stale offset only shifts the mesh.
        }
    }

    private static void setRenderPos(Object renderManager, Object entity) {
        try {
            double x = 0.0D, y = 0.0D, z = 0.0D;
            if (entity != null) {
                x = fieldDouble(entity, new String[] {"posX", "field_70165_t"}, 0.0D);
                y = fieldDouble(entity, new String[] {"posY", "field_70163_u"}, 0.0D);
                z = fieldDouble(entity, new String[] {"posZ", "field_70161_v"}, 0.0D);
            }
            setDouble(renderManager.getClass(), renderManager,
                    new String[] {"renderPosX", "field_78725_b"}, x);
            setDouble(renderManager.getClass(), renderManager,
                    new String[] {"renderPosY", "field_78726_c"}, y);
            setDouble(renderManager.getClass(), renderManager,
                    new String[] {"renderPosZ", "field_78723_d"}, z);
        } catch (Throwable ignored) {
            // Optional camera state.
        }
    }

    private static Object lookup(Object renderManager, Object entity) {
        try {
            return invoke(renderManager,
                    new String[] {"getEntityRenderObject", "func_78713_a"}, entity);
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- minimal reflection helpers (bridge-api-only discipline, as in EntityHandle1122) ----

    private static Object theUnsafe() throws Exception {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return singleton.get(null);
    }

    private static Object unsafeAllocate(Object unsafe, Class<?> type) throws Exception {
        Method allocate = unsafe.getClass().getMethod("allocateInstance", Class.class);
        return allocate.invoke(unsafe, type);
    }

    private static Object invoke(Object receiver, String[] names, Object... args) throws Exception {
        NoSuchMethodException missing = null;
        for (String name : names) {
            for (Class<?> c = receiver.getClass(); c != null; c = c.getSuperclass()) {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name)
                            || m.getParameterTypes().length != args.length) continue;
                    try {
                        m.setAccessible(true);
                        return m.invoke(receiver, args);
                    } catch (NoSuchMethodError e) {
                        missing = new NoSuchMethodException(name);
                    }
                }
            }
            if (missing == null) missing = new NoSuchMethodException(names[0]);
        }
        throw missing == null ? new NoSuchMethodException(names[0]) : missing;
    }

    private static void setField(Class<?> owner, Object receiver, String[] names, Object value)
            throws Exception {
        NoSuchFieldException missing = null;
        for (String name : names) {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    f.set(receiver, value);
                    return;
                } catch (NoSuchFieldException e) {
                    missing = e;
                }
            }
        }
        throw missing == null ? new NoSuchFieldException(names[0]) : missing;
    }

    private static Object readField(Object receiver, String[] names) {
        if (receiver == null) return null;
        for (String name : names) {
            for (Class<?> c = receiver.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(receiver);
                } catch (NoSuchFieldException e) {
                    // Try the next spelling, then the superclass.
                } catch (Throwable ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static void setDouble(Class<?> owner, Object receiver, String[] names, double value)
            throws Exception {
        NoSuchFieldException missing = null;
        for (String name : names) {
            for (Class<?> c = owner; c != null && c != Object.class; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    if (f.getType() == Double.TYPE) {
                        f.setDouble(receiver, value);
                        return;
                    }
                } catch (NoSuchFieldException e) {
                    missing = e;
                }
            }
        }
        throw missing == null ? new NoSuchFieldException(names[0]) : missing;
    }

    private static double fieldDouble(Object o, String[] names, double fallback) {
        try {
            for (String name : names) {
                for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
                    try {
                        Field f = c.getDeclaredField(name);
                        f.setAccessible(true);
                        Object v = f.get(o);
                        if (v instanceof Number) return ((Number) v).doubleValue();
                    } catch (NoSuchFieldException e) {
                        // Try the next spelling, then the superclass.
                    }
                }
            }
        } catch (Throwable ignored) {
            // Fall through to the fallback.
        }
        return fallback;
    }
}
