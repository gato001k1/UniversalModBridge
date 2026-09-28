package dev.umb.hostagent.content;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;

/**
 * Test-only probe loaded by the 1.7.10 child loader.  It deliberately uses reflection so this
 * class does not become part of either runtime jar.  The real LegacyBridgeImpl and the real mod
 * classes are still the code under test.
 */
public final class LegacyConformanceProbe {
    private LegacyConformanceProbe() {
    }

    public static String run() throws Exception {
        String tileId = required("umb.conformance.tileId");
        String blockId = required("umb.conformance.blockId");
        String neighborId = System.getProperty("umb.conformance.neighborId", "minecraft:air");
        ClassLoader loader = LegacyConformanceProbe.class.getClassLoader();
        Class<?> bridgeClass = Class.forName("dev.umb.legacy.legacyside.LegacyBridgeImpl", true, loader);
        Class<?> hostWorldClass = Class.forName("dev.umb.bridge.api.HostWorld", true, loader);
        Class<?> tileClass = Class.forName("dev.umb.bridge.api.TileHandle", true, loader);
        Object world = Proxy.newProxyInstance(loader, new Class<?>[]{hostWorldClass},
                new WorldHandler());
        Object bridge = bridgeClass.getConstructor().newInstance();
        invoke(bridgeClass.getMethod("boot", hostWorldClass), bridge, world);

        Method createTile = bridgeClass.getMethod("createTile", String.class, int.class, int.class, int.class);
        Object tile = invoke(createTile, bridge, tileId, 100, 64, 100);
        if (tile == null) throw new IllegalStateException("createTile returned null for " + tileId);
        Method save = tileClass.getMethod("saveNbt");
        Method load = tileClass.getMethod("loadNbt", byte[].class);
        byte[] before = requireBytes((byte[]) invoke(save, tile), "initial tile");

        Method neighbor = bridgeClass.getMethod("neighborChanged", String.class, int.class, int.class,
                int.class, String.class);
        invoke(neighbor, bridge, blockId, 100, 64, 100, neighborId);

        ((WorldHandler) Proxy.getInvocationHandler(world)).meta = 1;
        Object swapped = invoke(createTile, bridge, tileId, 100, 64, 100);
        if (swapped == null) throw new IllegalStateException("variant-swap createTile returned null for " + tileId);
        invoke(load, swapped, before);
        byte[] after = requireBytes((byte[]) invoke(save, swapped), "variant-swap tile");
        boolean equal = sameNbt(before, after);
        if (!equal) {
            throw new AssertionError("P3 assertion failed: semantic tile NBT changed across save/load and metadata variant swap"
                    + " beforeBytes=" + before.length + " afterBytes=" + after.length
                    + " before=" + nbtText(before) + " after=" + nbtText(after));
        }
        return "PERSISTENCE-OK\nP3 neighborDispatch=returned variantSwapNbtEqual=true tile=" + tileId;
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }

    private static byte[] requireBytes(byte[] bytes, String label) {
        if (bytes == null || bytes.length == 0) throw new IllegalStateException(label + " saveNbt returned empty");
        return bytes;
    }

    /**
     * Compare persisted state, not gzip timestamps or variant identity metadata.  A metadata
     * twin is allowed to save its own legacy id (IronChest does exactly that), and some legacy
     * constructors materialize an empty optional name on first load (HBM does this).  Everything
     * else, including coordinates and mod-owned inventory/energy/tank/progress fields, remains
     * strict.
     */
    private static boolean sameNbt(byte[] before, byte[] after) throws Exception {
        Class<?> tools = Class.forName("net.minecraft.nbt.CompressedStreamTools");
        Class<?> tracker = Class.forName("net.minecraft.nbt.NBTSizeTracker");
        Object unlimited = tracker.getField("field_152451_a").get(null);
        Method read = tools.getMethod("func_152457_a", byte[].class, tracker);
        Object a = read.invoke(null, before, unlimited);
        Object b = read.invoke(null, after, unlimited);
        Method remove = a.getClass().getMethod("func_82580_o", String.class);
        remove.invoke(a, "id");
        remove.invoke(b, "id");
        Method getString = a.getClass().getMethod("func_74779_i", String.class);
        if (((String) getString.invoke(a, "name")).isEmpty()) remove.invoke(a, "name");
        if (((String) getString.invoke(b, "name")).isEmpty()) remove.invoke(b, "name");
        // NBTTagCompound is a map; its toString order follows insertion order and is not
        // semantic equality (HBM's load path legitimately reorders the same fields).
        return a.equals(b);
    }

    private static String nbtText(byte[] bytes) throws Exception {
        Class<?> tools = Class.forName("net.minecraft.nbt.CompressedStreamTools");
        Class<?> tracker = Class.forName("net.minecraft.nbt.NBTSizeTracker");
        Object unlimited = tracker.getField("field_152451_a").get(null);
        Method read = tools.getMethod("func_152457_a", byte[].class, tracker);
        Object tag = read.invoke(null, bytes, unlimited);
        return tag.toString();
    }

    private static String required(String key) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("missing -D" + key);
        return value;
    }

    /** Minimal live HostWorld facade; all values are honest defaults, with mutable metadata. */
    private static final class WorldHandler implements InvocationHandler {
        int meta;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            String name = method.getName();
            if (name.equals("isRemote")) return false;
            if (name.equals("getTotalTime")) return 100L;
            if (name.equals("getBlockId")) return "minecraft:air";
            if (name.equals("getMeta")) return meta;
            if (name.equals("randomSeed")) return 7L;
            if (name.equals("log")) return null;
            if (method.getReturnType() == boolean.class) return false;
            if (method.getReturnType() == long.class) return 0L;
            if (method.getReturnType() == int.class) return 0;
            if (method.getReturnType() == double.class) return 0.0D;
            if (method.getReturnType() == float.class) return 0.0F;
            return null;
        }
    }
}
