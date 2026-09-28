package dev.umb.legacy.legacyside;

import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTSizeTracker;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import dev.umb.bridge.api.FieldPath;
import dev.umb.bridge.api.StackData;
import dev.umb.bridge.api.TileFieldDatum;
import dev.umb.bridge.api.TileFieldSnapshot;
import dev.umb.bridge.api.TileHandle;
import dev.umb.bridge.api.EntityRenderCapture;
import dev.umb.legacy.legacyside.render.LegacyRenderCapture;

/** Legacy compatibility behavior. */
public final class TileHandleImpl implements TileHandle {

    private final TileEntity te;
    private boolean poisoned;
    private String poisonReason;

    public TileHandleImpl(TileEntity te) {
        if (te == null) {
            throw new IllegalArgumentException("te");
        }
        this.te = te;
    }

    /** The raw legacy TileEntity - package-private, for LegacyBridgeImpl only. */
    TileEntity raw() {
        return te;
    }

    @Override
    public void tick() {
        if (poisoned) {
            return;
        }
        try {
            te.func_145845_h();
        } catch (Throwable t) {
            poisoned = true;
            poisonReason = t.getClass().getName() + ": " + t.getMessage();
            System.err.println("[UMB-TILE] " + te.getClass().getName() + " at (" + te.field_145851_c
                    + "," + te.field_145848_d + "," + te.field_145849_e + ") poisoned on tick: "
                    + poisonReason);
        }
    }

    @Override
    public byte[] saveNbt() {
        try {
            NBTTagCompound tag = new NBTTagCompound();
            te.func_145841_b(tag);
            return CompressedStreamTools.func_74798_a(tag);
        } catch (Throwable t) {
            System.err.println("[UMB-TILE] saveNbt failed for " + te.getClass().getName() + ": " + t);
            return null;
        }
    }

    @Override
    public void loadNbt(byte[] nbt) {
        if (nbt == null) {
            return;
        }
        try {
            NBTTagCompound tag = CompressedStreamTools.func_152457_a(nbt, NBTSizeTracker.field_152451_a);
            te.func_145839_a(tag);
        } catch (Throwable t) {
            System.err.println("[UMB-TILE] loadNbt failed for " + te.getClass().getName() + ": " + t);
        }
    }

    @Override
    public boolean isValid() {
        return !poisoned && !te.func_145837_r();
    }

    @Override
    public EntityRenderCapture renderCapture(float partialTick) {
        try {
            return LegacyRenderCapture.captureTile(te, partialTick);
        } catch (Throwable t) {
            System.err.println("[UMB-TILE] render capture failed for " + te.getClass().getName()
                    + ": " + t);
            return EntityRenderCapture.empty(te.getClass().getName(), "tile-render-failed");
        }
    }

    /**
 * Universal automation surface.
 * The host exposes this handle as a 26.2 Container, while these calls stay on the legacy side and invoke the exact IInventory/ISidedInventory methods.
 */
    private IInventory inventory() {
        return te instanceof IInventory ? (IInventory) te : null;
    }

    @Override
    public int inventorySize() {
        IInventory inv = inventory();
        return inv == null ? 0 : inv.func_70302_i_();
    }

    @Override
    public int inventoryMaxStackSize() {
        IInventory inv = inventory();
        return inv == null ? 64 : inv.func_70297_j_();
    }

    @Override
    public StackData inventoryItem(int slot) {
        IInventory inv = inventory();
        return inv == null ? StackData.EMPTY : UmbItemConv.toStackData(inv.func_70301_a(slot));
    }

    @Override
    public StackData inventoryRemove(int slot, int amount) {
        IInventory inv = inventory();
        return inv == null ? StackData.EMPTY : UmbItemConv.toStackData(inv.func_70298_a(slot, amount));
    }

    @Override
    public void inventorySet(int slot, StackData stack) {
        IInventory inv = inventory();
        if (inv != null) {
            inv.func_70299_a(slot, UmbItemConv.toLegacy(stack));
            inv.func_70296_d();
        }
    }

    @Override
    public boolean inventoryCanPlace(int slot, StackData stack) {
        IInventory inv = inventory();
        return inv != null && inv.func_94041_b(slot, UmbItemConv.toLegacy(stack));
    }

    @Override
    public boolean inventoryCanTake(int slot, StackData stack) {
        return inventory() != null && slot >= 0 && slot < inventorySize();
    }

    @Override
    public int[] inventorySlotsForSide(int side) {
        IInventory inv = inventory();
        if (inv instanceof ISidedInventory) {
            int[] slots = ((ISidedInventory) inv).func_94128_d(side);
            return slots == null ? new int[0] : slots;
        }
        int n = inv == null ? 0 : inv.func_70302_i_();
        int[] slots = new int[n];
        for (int i = 0; i < n; i++) slots[i] = i;
        return slots;
    }

    @Override
    public boolean inventoryCanPlaceThroughFace(int slot, StackData stack, int side) {
        IInventory inv = inventory();
        if (inv == null) return false;
        ItemStack legacy = UmbItemConv.toLegacy(stack);
        return inv instanceof ISidedInventory
                ? ((ISidedInventory) inv).func_102007_a(slot, legacy, side)
                : inv.func_94041_b(slot, legacy);
    }

    @Override
    public boolean inventoryCanTakeThroughFace(int slot, StackData stack, int side) {
        IInventory inv = inventory();
        if (inv == null) return false;
        ItemStack legacy = UmbItemConv.toLegacy(stack);
        return inv instanceof ISidedInventory
                ? ((ISidedInventory) inv).func_102008_b(slot, legacy, side)
                : slot >= 0 && slot < inv.func_70302_i_();
    }

    @Override
    public void inventoryChanged() {
        IInventory inv = inventory();
        if (inv != null) inv.func_70296_d();
    }

    /**
     * TILE-FIELD-SNAPSHOT: reads ONLY the named fields off the live {@code te}, via
     * {@link TileFieldReader}'s bounded, mod-free reflection walk. A path that fails to resolve
     * (the tile just went invalid, an intermediate reference is null, the field/accessor no longer
     * exists on this jar's version of the class) comes back absent at that same index - never a
     * fabricated 0, per THE BOUNDARY CONTRACT's "never fake a value" rule. Never throws.
     */
    @Override
    public TileFieldSnapshot snapshotFields(FieldPath[] paths) {
        if (paths == null || paths.length == 0) {
            return TileFieldSnapshot.EMPTY;
        }
        String[] keys = new String[paths.length];
        double[] values = new double[paths.length];
        boolean[] present = new boolean[paths.length];
        boolean valid = isValid();
        for (int i = 0; i < paths.length; i++) {
            FieldPath p = paths[i];
            keys[i] = p != null ? p.key : null;
            if (!valid || p == null || p.hopNames == null) {
                continue;
            }
            try {
                Object cur = te;
                for (int h = 0; h < p.hopNames.length && cur != null; h++) {
                    boolean accessor = p.hopKinds != null && h < p.hopKinds.length
                            && "accessor".equals(p.hopKinds[h]);
                    cur = TileFieldReader.readHop(cur, p.hopNames[h], accessor);
                }
                Double d = TileFieldReader.toDouble(cur);
                if (d != null) {
                    values[i] = d.doubleValue();
                    present[i] = true;
                }
            } catch (Throwable t) {
                // absent, never fabricated - present[i] stays false
            }
        }
        return new TileFieldSnapshot(keys, values, present);
    }

    /**
     * RENDER-CLASS CHECK (turret follow-up): mirrors vanilla's
     * {@code TileEntityRendererDispatcher} dispatch - the bound-TESR map hit, else the
     * superclass walk stopping before {@code TileEntity} itself. A proxy/dummy tile whose
     * hierarchy never names {@code teClass} reports false, so multiblock filler cells stop
     * double-rendering the model. Never throws (false on any failure).
     */
    @Override
    public boolean rendersAs(String teClass) {
        if (teClass == null) {
            return true;
        }
        if (!isValid()) {
            return false;
        }
        try {
            Class<?> c = te.getClass();
            while (c != null && !"net.minecraft.tileentity.TileEntity".equals(c.getName())) {
                if (teClass.equals(c.getName())) {
                    return true;
                }
                c = c.getSuperclass();
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public String legacyClassName() {
        return te.getClass().getName();
    }

    public String poisonReason() {
        return poisonReason;
    }

    /**
     * RENDER-DISPATCH CALL (door-live follow-up): invokes the zero-arg
     * {@code owner.name()} on the resolved object and returns the result's runtime class
     * name - the exact helper the tile-entity-server method would dispatch to. Null
     * receiver, missing method, any throw, or a null result all come back null, never a
     * fabricated name, and never a throw across the boundary.
     */
    @Override
    public String dispatchRenderer(String owner, String name, FieldPath objectPath) {
        if (owner == null || name == null || !isValid()) {
            return null;
        }
        try {
            Object recv = resolveObject(objectPath);
            if (recv == null) {
                return null;
            }
            Class<?> ownerClass = loadClass(owner);
            if (ownerClass == null) {
                return null;
            }
            java.lang.reflect.Method found = null;
            java.lang.reflect.Method[] methods;
            try {
                methods = ownerClass.getDeclaredMethods();
            } catch (Throwable t) {
                return null;
            }
            for (int i = 0; i < methods.length; i++) {
                java.lang.reflect.Method m = methods[i];
                if (!m.getName().equals(name)) continue;
                if (m.getParameterTypes().length != 0) continue;
                Class<?> ret = m.getReturnType();
                if (ret == Void.TYPE || ret == Void.class) continue;
                found = m;
                break;
            }
            if (found == null) {
                return null;
            }
            boolean isStatic = java.lang.reflect.Modifier.isStatic(found.getModifiers());
            if (!isStatic && !found.getDeclaringClass().isAssignableFrom(recv.getClass())) {
                return null;
            }
            try {
                found.setAccessible(true);
                Object result = found.invoke(isStatic ? null : recv);
                if (result == null) {
                    return null;
                }
                return result.getClass().getName();
            } catch (Throwable t) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private Class<?> loadClass(String name) {
        try {
            return Class.forName(name, false, te.getClass().getClassLoader());
        } catch (Throwable t) {
            // fall through to the context loader below
        }
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    /** One animation recipe's live entry: frozen provider args plus their server-wall entry time. */
    
    private static String recipeKey(String providerOwner, String providerMethod,
                                      FieldPath providerReceiver, FieldPath[] providerArgs,
                                      String clockOwner, String clockMethod, String clockField,
                                      String evalOwner, String evalMethod, String evalArg) {
        StringBuilder sb = new StringBuilder();
        sb.append(providerOwner).append(' ').append(providerMethod).append(' ');
        appendPath(sb, providerReceiver);
        if (providerArgs != null) {
            for (int i = 0; i < providerArgs.length; i++) appendPath(sb, providerArgs[i]);
        }
        sb.append(clockOwner).append(' ').append(clockMethod).append(' ').append(clockField).append(' ');
        sb.append(evalOwner).append(' ').append(evalMethod).append(' ').append(evalArg);
        return sb.toString();
    }

    private static void appendPath(StringBuilder sb, FieldPath p) {
        sb.append('[');
        if (p != null && p.hopNames != null) {
            for (int i = 0; i < p.hopNames.length; i++) {
                if (i > 0) sb.append('.');
                sb.append(p.hopNames[i]);
            }
        }
        sb.append(']').append(' ');
    }

    /** Invokes the mod's own zero-arg {@code ()J} clock; null on any failure. */
    private Long invokeClock(String owner, String name) {
        try {
            Class<?> c = loadClass(owner);
            if (c == null) {
                return null;
            }
            java.lang.reflect.Method m;
            try {
                m = c.getDeclaredMethod(name);
            } catch (Throwable t) {
                return null;
            }
            if (m.getParameterTypes().length != 0) return null;
            if (!long.class.equals(m.getReturnType()) && !Long.class.equals(m.getReturnType())) {
                return null;
            }
            if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) return null;
            try {
                m.setAccessible(true);
                Object out = m.invoke(null);
                if (out instanceof Long) return (Long) out;
                return null;
            } catch (Throwable t) {
                return null;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Resolves provider args to live objects (no numeric coercion - objects pass as-is). */
    private Object[] resolveArgs(FieldPath[] paths) {
        if (paths == null) {
            return new Object[0];
        }
        Object[] out = new Object[paths.length];
        for (int i = 0; i < paths.length; i++) {
            Object o = resolveObject(paths[i]);
            if (o == null) {
                return null;
            }
            out[i] = o;
        }
        return out;
    }

    /** Stable fingerprint of resolved provider args (class + value per arg). */
    private static String fingerprint(Object[] args) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(' ');
            Object a = args[i];
            sb.append(a == null ? "null" : a.getClass().getName());
            sb.append('=');
            try {
                sb.append(String.valueOf(a));
            } catch (Throwable t) {
                sb.append("unprintable");
            }
        }
        return sb.toString();
    }

    /** Invokes the provider on the receiver with the given args; null on any failure. */
    private Object invokeProvider(String owner, String name, Object recv, Object[] args) {
        try {
            Class<?> c = loadClass(owner);
            if (c == null || recv == null) {
                return null;
            }
            java.lang.reflect.Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                return null;
            }
            for (int i = 0; i < methods.length; i++) {
                java.lang.reflect.Method m = methods[i];
                if (!m.getName().equals(name)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != args.length) continue;
                boolean fit = true;
                for (int p = 0; p < params.length; p++) {
                    if (!assignable(params[p], args[p])) {
                        fit = false;
                        break;
                    }
                }
                if (!fit) continue;
                boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                if (!isStatic && !m.getDeclaringClass().isAssignableFrom(recv.getClass())) continue;
                try {
                    m.setAccessible(true);
                    return m.invoke(isStatic ? null : recv, args);
                } catch (Throwable t) {
                    return null;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Assignability including primitive unboxing (a Byte fits a byte param, etc.). */
    private static boolean assignable(Class<?> param, Object arg) {
        if (arg == null) {
            return !param.isPrimitive();
        }
        Class<?> a = arg.getClass();
        if (param.isAssignableFrom(a)) {
            return true;
        }
        if (!param.isPrimitive()) {
            return false;
        }
        return (param == boolean.class && a == Boolean.class)
                || (param == byte.class && a == Byte.class)
                || (param == short.class && (a == Short.class || a == Byte.class))
                || (param == int.class && (a == Integer.class || a == Short.class || a == Byte.class))
                || (param == long.class && (a == Long.class || a == Integer.class
                        || a == Short.class || a == Byte.class))
                || (param == float.class && (a == Float.class || a == Long.class || a == Integer.class
                        || a == Short.class || a == Byte.class))
                || (param == double.class && (a == Double.class || a == Float.class || a == Long.class
                        || a == Integer.class || a == Short.class || a == Byte.class))
                || (param == char.class && a == Character.class);
    }

    /**
     * Backdates the clock field on our fresh clip copy so the evaluator reads the true
     * transit age. The object is created by this call and never shared - mutating our own
     * copy only. False when the field is missing or not a long.
     */
    private static boolean backdateClock(Object clip, String field, long value) {
        if (clip == null || field == null) {
            return false;
        }
        try {
            Class<?> c = clip.getClass();
            java.lang.reflect.Field f = null;
            while (c != null && c != Object.class) {
                try {
                    f = c.getDeclaredField(field);
                    break;
                } catch (NoSuchFieldException n) {
                    c = c.getSuperclass();
                }
            }
            if (f == null || !long.class.equals(f.getType())) {
                return false;
            }
            try {
                f.setAccessible(true);
                f.setLong(clip, value);
                return true;
            } catch (Throwable t) {
                return false;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    /** Invokes the static track evaluator against the clip; null on any failure. */
    private double[] invokeEvaluator(String owner, String name, String stringArg, Object clip) {
        try {
            Class<?> c = loadClass(owner);
            if (c == null || clip == null) {
                return null;
            }
            java.lang.reflect.Method[] methods;
            try {
                methods = c.getDeclaredMethods();
            } catch (Throwable t) {
                return null;
            }
            for (int i = 0; i < methods.length; i++) {
                java.lang.reflect.Method m = methods[i];
                if (!m.getName().equals(name)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2) continue;
                if (!params[0].isAssignableFrom(String.class)) continue;
                if (!params[1].isAssignableFrom(clip.getClass())) continue;
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                try {
                    m.setAccessible(true);
                    Object result = m.invoke(null, stringArg, clip);
                    if (result instanceof double[]) {
                        return (double[]) result;
                    }
                    return null;
                } catch (Throwable t) {
                    return null;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** One animation recipe's live entry: frozen provider args plus their server-wall entry time. */
    private static final class AnimEntry {
        final String argsFp;
        /** Entry instant on the server wall clock ({@link System#currentTimeMillis}). */
        final long t0Wall;
        final Object[] frozenArgs;
        AnimEntry(String argsFp, long t0Wall, Object[] frozenArgs) {
            this.argsFp = argsFp;
            this.t0Wall = t0Wall;
            this.frozenArgs = frozenArgs;
        }
    }

    private final java.util.Map<String, AnimEntry> animEntries =
            new java.util.HashMap<String, AnimEntry>();

    /**
     * ANIMATION-TRACK SYNTHESIS (door-live follow-up): rebuilds the packet-installed
     * animation object the server never receives and evaluates the track against it, so a
     * rest-open door renders open and a transit slides. The provider runs on the resolved
     * receiver with resolved tile-field args; one entry per distinct recipe tracks the
     * argument fingerprint and its server-wall entry time. The entry/elapsed clock is
     * deliberately NOT the mod's own clock: the mod clock only advances when its client
     * code runs (a stalled or throttled client freezes it, which used to freeze transit
     * values mid-pose while server ticks completed). Elapsed is therefore measured on
     * {@link System#currentTimeMillis}, which advances whenever the server itself ticks;
     * the mod clock is used only as the backdate anchor, so a frozen mod clock cancels
     * out exactly (the evaluator reads anchor-minus-backdate equals wall elapsed). A
     * provider returning null preserves
     * the previous entry - a rest state holds its transit clip's end exactly like the
     * vanilla client holding its last packet-installed clip. The clip is always fresh (never
     * shared or stored on the tile); its clock field is backdated to (clock minus elapsed)
     * on our copy only. Every failure mode comes back null, never a fabricated array, and
     * never a throw across the boundary.
     */
    @Override
    public double[] evalAnim(String providerOwner, String providerMethod, FieldPath providerReceiver,
                             FieldPath[] providerArgs, String clockOwner, String clockMethod,
                             String clockField, String evalOwner, String evalMethod, String evalArg) {
        if (providerOwner == null || providerMethod == null || clockOwner == null
                || clockMethod == null || clockField == null || evalOwner == null
                || evalMethod == null || !isValid()) {
            return null;
        }
        try {
            String recipeKey = recipeKey(providerOwner, providerMethod, providerReceiver, providerArgs,
                    clockOwner, clockMethod, clockField, evalOwner, evalMethod, evalArg);
            Long clockNow = invokeClock(clockOwner, clockMethod);
            if (clockNow == null) {
                return null;
            }
            long wallNow = System.currentTimeMillis();
            Object recv = resolveObject(providerReceiver);
            if (recv == null) {
                return null;
            }
            Object[] args = resolveArgs(providerArgs);
            if (args == null) {
                return null;
            }
            AnimEntry prev = animEntries.get(recipeKey);
            AnimEntry entry = prev;
            String fp = fingerprint(args);
            if (prev == null || !prev.argsFp.equals(fp)) {
                entry = new AnimEntry(fp, wallNow, args);
            }
            Object clip = invokeProvider(providerOwner, providerMethod, recv, entry.frozenArgs);
            if (clip == null) {
                if (prev == null) {
                    return null;
                }
                clip = invokeProvider(providerOwner, providerMethod, recv, prev.frozenArgs);
                if (clip == null) {
                    return null;
                }
                entry = prev;
            } else if (entry != prev) {
                animEntries.put(recipeKey, entry);
            }
            long elapsed = wallNow - entry.t0Wall;
            if (elapsed < 0) {
                elapsed = 0;
            }
            if (!backdateClock(clip, clockField, clockNow.longValue() - elapsed)) {
                return null;
            }
            return invokeEvaluator(evalOwner, evalMethod, evalArg, clip);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Resolves a {@link FieldPath} off the live tile to its terminal object (accessor hops
     * included), or null. Shared by {@link #snapshotFields}, {@link #evalStatic},
     * {@link #dispatchRenderer} and {@link #evalAnim}: a null path means the tile itself.
     * Never throws.
     */
    private Object resolveObject(FieldPath path) {        try {
            Object cur = te;
            if (path != null && path.hopNames != null) {
                for (int h = 0; h < path.hopNames.length && cur != null; h++) {
                    boolean accessor = path.hopKinds != null && h < path.hopKinds.length
                            && "accessor".equals(path.hopKinds[h]);
                    cur = TileFieldReader.readHop(cur, path.hopNames[h], accessor);
                }
            }
            return cur;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
 * STATIC-EVALUATOR CALL (door-live animation channels): resolves {@code objectPath} off the live {@code te} exactly like {@link #snapshotFields} (a null path means the TE itself), then reflectively invokes {@code owner.name(String, <runtime type>)} and...
 */
    @Override
    public double[] evalStatic(String owner, String name, String stringArg, FieldPath objectPath) {
        if (owner == null || name == null || !isValid()) {
            return null;
        }
        try {
            Object runtime = te;
            if (objectPath != null && objectPath.hopNames != null) {
                for (int h = 0; h < objectPath.hopNames.length && runtime != null; h++) {
                    boolean accessor = objectPath.hopKinds != null && h < objectPath.hopKinds.length
                            && "accessor".equals(objectPath.hopKinds[h]);
                    runtime = TileFieldReader.readHop(runtime, objectPath.hopNames[h], accessor);
                }
            }
            Class<?> ownerClass = null;
            try {
                ownerClass = Class.forName(owner, false, te.getClass().getClassLoader());
            } catch (Throwable t) {
                ownerClass = null;
            }
            if (ownerClass == null) {
                try {
                    ownerClass = Class.forName(owner);
                } catch (Throwable t) {
                    return null;
                }
            }
            java.lang.reflect.Method[] methods;
            try {
                methods = ownerClass.getDeclaredMethods();
            } catch (Throwable t) {
                return null;
            }
            for (int i = 0; i < methods.length; i++) {
                java.lang.reflect.Method m = methods[i];
                if (!m.getName().equals(name)) continue;
                Class<?>[] params = m.getParameterTypes();
                if (params.length != 2) continue;
                if (!params[0].isAssignableFrom(String.class)) continue;
                if (runtime != null) {
                    if (!params[1].isAssignableFrom(runtime.getClass())) continue;
                } else if (params[1].isPrimitive()) {
                    continue;
                }
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                try {
                    m.setAccessible(true);
                    Object result = m.invoke(null, stringArg, runtime);
                    if (result instanceof double[]) {
                        return (double[]) result;
                    }
                    return null;
                } catch (Throwable t) {
                    return null;
                }
            }
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Automation/observation (the {@code legacy_tile} command): every declared instance
     * field of the live tile, superclass chain included, with display values. Bounded:
     * at most {@link #MAX_DESCRIBE_FIELDS} fields, values truncated to
     * {@link #MAX_DESCRIBE_VALUE_CHARS} characters (arrays expanded readably up to a few
     * dozen elements). One unreadable field degrades to present=false, never a throw -
     * the walk must survive any mod's exotic field types on a live server thread.
     */
    static final int MAX_DESCRIBE_FIELDS = 256;
    static final int MAX_DESCRIBE_VALUE_CHARS = 300;
    static final int MAX_DESCRIBE_ARRAY_ELEMENTS = 32;

    @Override
    public java.util.List<TileFieldDatum> describeFields() {
        java.util.List<TileFieldDatum> out = new java.util.ArrayList<TileFieldDatum>();
        try {
            Class<?> c = te.getClass();
            while (c != null && c != Object.class && out.size() < MAX_DESCRIBE_FIELDS) {
                java.lang.reflect.Field[] fields;
                try {
                    fields = c.getDeclaredFields();
                } catch (Throwable t) {
                    break;
                }
                String owner = c.getName();
                for (int i = 0; i < fields.length && out.size() < MAX_DESCRIBE_FIELDS; i++) {
                    java.lang.reflect.Field f = fields[i];
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                    if (f.isSynthetic()) continue;
                    String type = f.getType().getSimpleName();
                    String value = null;
                    boolean present = false;
                    try {
                        f.setAccessible(true);
                        Object v = f.get(te);
                        if (v != null) {
                            String rendered = renderValue(v);
                            if (rendered != null) {
                                value = rendered;
                                present = true;
                            }
                        }
                    } catch (Throwable t) {
                        // absent, never fabricated
                    }
                    out.add(new TileFieldDatum(owner, f.getName(), type, value, present));
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            // an empty list is an honest "could not observe", never a throw across the boundary
        }
        return out;
    }

    private static String renderValue(Object v) {
        String s;
        if (v instanceof int[]) s = boundedArray((int[]) v);
        else if (v instanceof long[]) s = boundedArray((long[]) v);
        else if (v instanceof float[]) s = boundedArray((float[]) v);
        else if (v instanceof double[]) s = boundedArray((double[]) v);
        else if (v instanceof boolean[]) s = boundedArray((boolean[]) v);
        else if (v instanceof short[]) s = boundedArray((short[]) v);
        else if (v instanceof byte[]) s = boundedArray((byte[]) v);
        else if (v instanceof char[]) s = boundedArray((char[]) v);
        else if (v instanceof Object[]) s = boundedArray((Object[]) v);
        else {
            try {
                s = String.valueOf(v);
            } catch (Throwable t) {
                return null;
            }
        }
        if (s == null) return null;
        return s.length() > MAX_DESCRIBE_VALUE_CHARS
                ? s.substring(0, MAX_DESCRIBE_VALUE_CHARS) + "...[" + s.length() + " chars]"
                : s;
    }

    private static String boundedArray(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(a.length, MAX_DESCRIBE_ARRAY_ELEMENTS);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i]);
        }
        if (a.length > n) sb.append(",...").append(a.length - n).append(" more");
        return sb.append("]").toString();
    }

    private static String boundedArray(long[] a) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(a.length, MAX_DESCRIBE_ARRAY_ELEMENTS);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i]);
        }
        if (a.length > n) sb.append(",...").append(a.length - n).append(" more");
        return sb.append("]").toString();
    }

    private static String boundedArray(float[] a) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(a.length, MAX_DESCRIBE_ARRAY_ELEMENTS);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i]);
        }
        if (a.length > n) sb.append(",...").append(a.length - n).append(" more");
        return sb.append("]").toString();
    }

    private static String boundedArray(double[] a) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(a.length, MAX_DESCRIBE_ARRAY_ELEMENTS);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i]);
        }
        if (a.length > n) sb.append(",...").append(a.length - n).append(" more");
        return sb.append("]").toString();
    }

    private static String boundedArray(boolean[] a) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(a.length, MAX_DESCRIBE_ARRAY_ELEMENTS);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i]);
        }
        if (a.length > n) sb.append(",...").append(a.length - n).append(" more");
        return sb.append("]").toString();
    }

    private static String boundedArray(short[] a) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(a.length, MAX_DESCRIBE_ARRAY_ELEMENTS);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i]);
        }
        if (a.length > n) sb.append(",...").append(a.length - n).append(" more");
        return sb.append("]").toString();
    }

    private static String boundedArray(byte[] a) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(a.length, MAX_DESCRIBE_ARRAY_ELEMENTS);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append(a[i] & 0xFF);
        }
        if (a.length > n) sb.append(",...").append(a.length - n).append(" more");
        return sb.append("]").toString();
    }

    private static String boundedArray(char[] a) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(a.length, MAX_DESCRIBE_ARRAY_ELEMENTS);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            sb.append((int) a[i]);
        }
        if (a.length > n) sb.append(",...").append(a.length - n).append(" more");
        return sb.append("]").toString();
    }

    private static String boundedArray(Object[] a) {
        StringBuilder sb = new StringBuilder("[");
        int n = Math.min(a.length, MAX_DESCRIBE_ARRAY_ELEMENTS);
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(",");
            Object e = a[i];
            String s = null;
            try {
                s = String.valueOf(e);
            } catch (Throwable t) {
                // leave null below
            }
            if (s != null && s.length() > 80) s = s.substring(0, 80) + "...";
            sb.append(s);
        }
        if (a.length > n) sb.append(",...").append(a.length - n).append(" more");
        return sb.append("]").toString();
    }
}
