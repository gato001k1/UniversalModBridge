package dev.umb.legacy1122.legacyside;

import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.EntityRenderCapture;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileFieldDatum;
import dev.umb.bridge.api.TileFieldSnapshot;
import dev.umb.bridge.api.TileHandle;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Generic reflective handle over a genuine Forge 1.12.2 TileEntity. */
final class TileHandle1122 implements TileHandle {
    private final Object te;
    private final Consumer<String> log;
    private final AtomicBoolean tickFailureLogged = new AtomicBoolean();
    private volatile boolean poisoned;

    TileHandle1122(Object te, Consumer<String> log) {
        this.te = te;
        this.log = log == null ? s -> { } : log;
    }

    Object raw() { return te; }

    public EntityRenderCapture renderCapture(float partialTick) {
        String kind = te == null ? "" : te.getClass().getName();
        try {
            ClassLoader loader = te.getClass().getClassLoader();
            Class<?> dispatcherType = Class.forName("net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher", true, loader);
            Object dispatcher = null;
            for (String fieldName : new String[]{"instance", "field_147556_a"}) {
                try { java.lang.reflect.Field f = dispatcherType.getDeclaredField(fieldName); f.setAccessible(true); dispatcher = f.get(null); break; }
                catch (NoSuchFieldException ignored) { }
            }
            if (dispatcher == null) return EntityRenderCapture.empty(kind, "tesr-dispatcher-null");
            Method lookup = find(dispatcherType, "getRenderer", 1);
            if (lookup == null) lookup = find(dispatcherType, "func_147547_b", 1);
            if (lookup == null) return EntityRenderCapture.empty(kind, "tesr-lookup-missing");
            Object renderer = lookup.invoke(dispatcher, te);
            if (renderer == null) return EntityRenderCapture.empty(kind, "tesr-not-registered");
            Method render = null;
            for (Method m : renderer.getClass().getMethods()) {
                if ((m.getName().equals("render") || m.getName().equals("func_192841_a"))
                        && m.getParameterTypes().length == 7 && m.getParameterTypes()[0].isAssignableFrom(te.getClass())) { render = m; break; }
            }
            if (render == null) return EntityRenderCapture.empty(kind, "tesr-render-missing");
            Legacy1122RenderCapture.begin(kind, "partial=" + partialTick);
            render.invoke(renderer, te, 0d, 0d, 0d, partialTick, 0, 1f);
            return Legacy1122RenderCapture.end();
        } catch (Throwable failure) {
            try { Legacy1122RenderCapture.end(); } catch (Throwable ignored) { }
            Throwable root = failure instanceof java.lang.reflect.InvocationTargetException && failure.getCause() != null ? failure.getCause() : failure;
            return EntityRenderCapture.empty(kind, "tesr-render-failed:" + root.getClass().getName());
        }
    }

    @Override public void tick() {
        if (poisoned) return;
        try {
            Method m = find(te.getClass(), "update", 0);
            if (m == null) m = find(te.getClass(), "func_73660_a", 0);
            if (m != null) m.invoke(te);
        } catch (Throwable t) {
            poisoned = true;
            if (tickFailureLogged.compareAndSet(false, true)) {
                Throwable cause = t instanceof InvocationTargetException && ((InvocationTargetException) t).getCause() != null
                        ? ((InvocationTargetException) t).getCause() : t;
                log.accept("UMB-BRIDGE-1122 tile tick failed class=" + te.getClass().getName()
                        + " cause=" + cause);
            }
        }
    }

    @Override public byte[] saveNbt() { return new byte[0]; }
    @Override public void loadNbt(byte[] nbt) { }

    @Override public boolean isValid() {
        if (poisoned) return false;
        try {
            Method m = find(te.getClass(), "isInvalid", 0);
            if (m == null) m = find(te.getClass(), "func_145837_r", 0);
            return m == null || !Boolean.TRUE.equals(m.invoke(te));
        } catch (Throwable t) { return false; }
    }

    public int inventorySize() {
        Object n = invoke("getSizeInventory");
        return n instanceof Number ? ((Number) n).intValue() : 0;
    }

    public StackData inventoryItem(int slot) {
        Object stack = invoke("getStackInSlot", Integer.valueOf(slot));
        return toStack(stack);
    }

    public StackData inventoryRemove(int slot, int amount) {
        Object stack = invoke("decrStackSize", Integer.valueOf(slot), Integer.valueOf(amount));
        return toStack(stack);
    }

    public void inventorySet(int slot, StackData stack) {
        // Host-side inventory writes are intentionally enabled only when a named legacy
        // conversion exists; generic 1.12.2 ItemStack construction is handled by the next
        // bridge increment, never by fabricating an item identity here.
    }

    public boolean inventoryCanPlace(int slot, StackData stack) { return true; }
    public boolean inventoryCanTake(int slot, StackData stack) { return true; }
    public void inventoryChanged() { invoke("markDirty"); }

    @Override public TileFieldSnapshot snapshotFields(FieldPath[] paths) {
        int n = paths == null ? 0 : paths.length;
        return new TileFieldSnapshot(new String[n], new double[n], new boolean[n]);
    }
    @Override public List<TileFieldDatum> describeFields() { return Collections.emptyList(); }

    private Object invoke(String name, Object... args) {
        try {
            Method m = find(te.getClass(), name, args.length);
            return m == null ? null : m.invoke(te, args);
        } catch (Throwable t) { return null; }
    }

    private static Method find(Class<?> type, String name, int arity) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterTypes().length == arity) {
                    try { m.setAccessible(true); } catch (Throwable ignored) { }
                    return m;
                }
            }
        }
        return null;
    }

    private static StackData toStack(Object stack) {
        if (stack == null) return StackData.EMPTY;
        try {
            Object empty = find(stack.getClass(), "isEmpty", 0).invoke(stack);
            if (Boolean.TRUE.equals(empty)) return StackData.EMPTY;
            Object item = find(stack.getClass(), "getItem", 0).invoke(stack);
            Object id = item == null ? null : find(item.getClass(), "getRegistryName", 0).invoke(item);
            Object count = find(stack.getClass(), "getCount", 0).invoke(stack);
            Object damage = find(stack.getClass(), "getMetadata", 0).invoke(stack);
            return new StackData(String.valueOf(id), ((Number) count).intValue(),
                    ((Number) damage).intValue(), null);
        } catch (Throwable t) { return StackData.EMPTY; }
    }
}
