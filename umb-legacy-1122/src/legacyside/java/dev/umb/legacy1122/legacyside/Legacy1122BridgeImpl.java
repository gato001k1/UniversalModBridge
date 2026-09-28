package dev.umb.legacy1122.legacyside;

import dev.umb.bridge.api.ActivationResult;
import dev.umb.bridge.api.ContainerHandle;
import dev.umb.bridge.api.EntityHandle;
import dev.umb.bridge.api.HostPlayer;
import dev.umb.bridge.api.HostWorld;
import dev.umb.bridge.api.ItemUseResult;
import dev.umb.bridge.api.LegacyBridge;
import dev.umb.bridge.api.TileHandle;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The 1.12.2 era's implementation of the SAME {@code dev.umb.bridge.api} contract the 1.7.10 era
 * speaks - the host side must never learn there are two eras (ERA-1122-PLAN.md constraint).
 *
 * <p>Honest current state: {@link #boot} proves this class is genuinely running INSIDE an isolated
 * {@code Legacy1122Loader} (loaded by it, so {@code Class.forName} below resolves through it) and
 * that the loader can reach real Forge 1.12.2 classes - the same thing
 * {@code dev.umb.legacy1122.boot.Boot1122ProbeMain} proves from the application side, repeated here
 * to demonstrate it also holds from INSIDE the child loader, which is the position any future
 * FML-lifecycle driver would actually run from. Every other method is an HONEST STUB: no live
 * facade (no {@code UmbWorld1122}, no field-repaired vanilla, no driven FML lifecycle) exists yet.
 * Stubs never throw across the boundary (the contract's own
 * rule) and log once via {@link HostWorld#log} rather than failing silently.</p>
 */
public final class Legacy1122BridgeImpl implements LegacyBridge {

    private volatile boolean booted = false;
    private volatile String bootFailure = null;
    private volatile HostWorld host;
    private final Map<String, Object> liveTiles = new HashMap<String, Object>();
    private final Map<String, TileHandle1122> liveTileHandles = new HashMap<String, TileHandle1122>();
    private final Map<Object, EntityHandle1122> liveEntities = new HashMap<Object, EntityHandle1122>();
    private volatile Object entityWorldFacade;

    @Override
    public void boot(HostWorld world) throws Exception {
        if (booted) {
            return;
        }
        this.host = world;
        ClassLoader me = Legacy1122BridgeImpl.class.getClassLoader();
        // Do not probe Forge classes here. In the isolated path this method runs before
        // Legacy1122Lifecycle installs PatchingTransformer/DeobfuscationTransformer; loading
        // IForgeRegistry here permanently caches its notch descriptor (nf) and later callers
        // compiled against ResourceLocation fail with NoSuchMethodError. The lifecycle's first
        // Forge loads are deliberately after LaunchWrapper and ClassPatchManager setup.
        if (!me.getClass().getName().equals("dev.umb.legacy1122.boot.Legacy1122Loader")) {
            try {
                Class.forName("net.minecraftforge.fml.common.Loader", false, me);
                Class.forName("net.minecraftforge.event.RegistryEvent", false, me);
                Class.forName("net.minecraftforge.registries.IForgeRegistry", false, me);
            } catch (ClassNotFoundException e) {
                bootFailure = "real Forge 1.12.2 classes not reachable from " + me + ": " + e;
                throw new IllegalStateException(bootFailure, e);
            }
        }
        // What a real boot() still needs, in order:
        //   1. a field-repaired-or-captured vanilla jar (the obfuscated client.jar on the
        //      classpath today is not deobfuscated);
        //   2. an FML lifecycle driver (this era's LegacyDriver-equivalent) that drives
        //      CONSTRUCT -> PREINIT -> INIT -> POSTINIT over RegistryEvent.Register;
        //   3. UmbWorld1122/UmbPlayer1122 facades implementing whatever the field-repaired
        //      World/EntityPlayer surface turns out to require.
        // None of that exists yet. This method intentionally goes no further than proving the
        // loader/classpath plumbing.
        if (me.getClass().getName().equals("dev.umb.legacy1122.boot.Legacy1122Loader")) {
            String prop = System.getProperty("umb.1122.modjars", "");
            List<File> mods = new ArrayList<File>();
            File forge = null;
            for (String part : prop.split(";")) {
                if (part.trim().isEmpty()) continue;
                File f = new File(part.trim()).getAbsoluteFile();
                if (f.getName().startsWith("forge-") && f.getName().endsWith("-universal.jar")) forge = f;
                else mods.add(f);
            }
            File repo = new File(System.getProperty("umb.repo", ".")).getAbsoluteFile();
            if (forge == null) forge = new File(repo, "research/out/legacy-1122/forge-1.12.2-14.23.5.2860-universal.jar");
            File gameDir = new File(System.getProperty("umb.1122.gamedir", new File(repo, "research/out/legacy-1122/runtime-live").getPath()));
            Legacy1122Lifecycle.Result lifecycle = Legacy1122Lifecycle.run(me, mods, gameDir, forge, msg -> world.log(msg));
            if (!lifecycle.ok()) throw new IllegalStateException("1.12.2 lifecycle did not reach initializeMods");
            Legacy1122ClientTileProvider.install(liveTileHandles);
            world.log("UMB-BRIDGE-1122 live universe booted: construct/preInit/registry/init/postInit");
        } else {
            world.log("UMB-BRIDGE-1122 Forge classes reachable (non-isolated gate mode)");
        }
        booted = true;
    }

    @Override
    public boolean isBooted() {
        return booted;
    }

    @Override
    public TileHandle createTile(String legacyBlockId, int x, int y, int z) {
        if (!booted) return null;
        try {
            ClassLoader loader = Legacy1122BridgeImpl.class.getClassLoader();
            Object block = findBlock(loader, legacyBlockId);
            if (block == null) return null;
            int meta = host == null ? 0 : host.getMeta(x, y, z);
            // The 1.12.2 runtime is SRG-named for vanilla members (joined-1.12.2.srg:
            // Block.getStateFromMeta = func_176203_a, getDefaultState = func_176223_P); Forge's own
            // hasTileEntity/createTileEntity keep their names. Try MCP first for dev-named jars.
            Object state = null;
            try { state = invoke(block, "getStateFromMeta", Integer.valueOf(meta)); }
            catch (Throwable invalidMeta) { if (host != null) host.log("UMB-BRIDGE-1122 invalid meta " + meta + " for " + legacyBlockId + "; using default state"); }
            if (state == null) try { state = invoke(block, "func_176203_a", Integer.valueOf(meta)); }
            catch (Throwable invalidSrgMeta) { /* default state below */ }
            if (state == null) state = invoke(block, "getDefaultState");
            if (state == null) state = invoke(block, "func_176223_P");
            Object has = invoke(block, "hasTileEntity", state);
            if (Boolean.FALSE.equals(has)) {
                if (host != null) host.log("UMB-BRIDGE-1122 createTile: hasTileEntity=false for " + legacyBlockId
                        + " meta=" + meta + " state=" + state);
                return null;
            }
            // Overload-aware: the first 2-arg createTileEntity found by name was not always
            // (World, IBlockState) - live IronChest 1.12.2 hit "argument type mismatch".
            Method factory = compatible(block.getClass(), "createTileEntity", null, state);
            if (factory == null) {
                if (host != null) {
                    StringBuilder seen = new StringBuilder();
                    for (Class<?> c = block.getClass(); c != null; c = c.getSuperclass())
                        for (Method m : c.getDeclaredMethods())
                            if (m.getName().startsWith("createTileEntity") || m.getName().startsWith("createNewTileEntity"))
                                seen.append(' ').append(m);
                    host.log("UMB-BRIDGE-1122 createTile: no factory for " + legacyBlockId + " state="
                            + (state == null ? "null" : state.getClass().getName() + "@" + state.getClass().getClassLoader())
                            + " candidates:" + seen);
                }
                return null;
            }
            Object legacyWorld = createWorldFacade(host);
            Object te = factory.invoke(block, legacyWorld, state);
            if (te == null && host != null) host.log("UMB-BRIDGE-1122 createTile: factory returned null for " + legacyBlockId);
            if (te == null) return null;
            bindTileWorldAndPosition(te, legacyWorld, x, y, z);
            TileHandle1122 handle = new TileHandle1122(te, host == null ? null : host::log);
            String key = x + "," + y + "," + z;
            synchronized (liveTiles) { liveTiles.put(key, te); liveTileHandles.put(key, handle); }
            return handle;
        } catch (Throwable t) {
            if (host != null) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                StringBuilder where = new StringBuilder();
                StackTraceElement[] st = root.getStackTrace();
                for (int i = 0; i < Math.min(6, st.length); i++) where.append(" | at ").append(st[i]);
                host.log("UMB-BRIDGE-1122 createTile failed: " + t + " root=" + root + where);
            }
            return null;
        }
    }

    private void bindTileWorldAndPosition(Object te, Object world, int x, int y, int z) throws Exception {
        Method setWorld = compatible(te.getClass(), "setWorld", world);
        if (setWorld == null) setWorld = compatible(te.getClass(), "func_145834_a", world);
        if (setWorld == null) throw new NoSuchMethodException("TileEntity.setWorld/func_145834_a");
        setWorld.invoke(te, world);
        ClassLoader loader = te.getClass().getClassLoader();
        // Live universe = deobf-transformed MCP class names (notch "et" only in raw jars).
        Class<?> posType;
        try {
            posType = Class.forName("net.minecraft.util.math.BlockPos", true, loader);
        } catch (ClassNotFoundException rawJar) {
            posType = Class.forName("et", true, loader);
        }
        Object pos = posType.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
        Method setPos = compatible(te.getClass(), "setPos", pos);
        if (setPos == null) setPos = compatible(te.getClass(), "func_174878_a", pos);
        if (setPos == null) throw new NoSuchMethodException("TileEntity.setPos/func_174878_a");
        setPos.invoke(te, pos);
    }

    private static Object createWorldFacade(HostWorld host) throws Exception {
        ClassLoader loader = Legacy1122BridgeImpl.class.getClassLoader();
        Class<?> facade = Class.forName("dev.umb.legacy1122.legacyside.Legacy1122WorldFacadeRaw", true, loader);
        // The helper is package-private in the default package (it must sit beside the notch
        // classes), so open the method explicitly.
        Method create = facade.getDeclaredMethod("create", HostWorld.class);
        create.setAccessible(true);
        return create.invoke(null, host);
    }

    private static Object findBlock(ClassLoader loader, String id) throws Exception {
        Class<?> registries = Class.forName("net.minecraftforge.fml.common.registry.ForgeRegistries", true, loader);
        Object registry = registries.getField("BLOCKS").get(null);
        Class<?> rlType = Class.forName("net.minecraft.util.ResourceLocation", true, loader);
        Object rl = rlType.getConstructor(String.class).newInstance(id);
        // ForgeRegistry has getValue(int) next to getValue(ResourceLocation); pick by argument type.
        Method getValue = compatible(registry.getClass(), "getValue", rl);
        // Forge registries return the DEFAULT entry (air) for unknown keys, never null. The host
        // namespace carries an era suffix (ironchest1122:iron_chest vs ironchest:iron_chest), so
        // only trust getValue when the key really exists; otherwise match by path below.
        Method containsKey = compatible(registry.getClass(), "containsKey", rl);
        boolean present = containsKey == null || Boolean.TRUE.equals(containsKey.invoke(registry, rl));
        Object exact = getValue == null || !present ? null : getValue.invoke(registry, rl);
        if (exact != null) return exact;
        String path = id == null ? "" : id.substring(id.indexOf(':') + 1);
        Object found = null; int matches = 0;
        Method entries = method(registry.getClass(), "getEntries", 0);
        Object raw = entries == null ? null : entries.invoke(registry);
        if (raw instanceof Iterable) for (Object entry : (Iterable<?>) raw) {
            Object key = method(entry.getClass(), "getKey", 0).invoke(entry);
            String keyText = String.valueOf(key);
            if (key != null && path.equals(keyText.substring(keyText.indexOf(':') + 1))) {
                found = method(entry.getClass(), "getValue", 0).invoke(entry); matches++;
            }
        }
        return matches == 1 ? found : null;
    }

    private static Method method(Class<?> type, String name, int arity) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) for (Method m : c.getDeclaredMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == arity) {
                try { m.setAccessible(true); } catch (Throwable ignored) { }
                return m;
            }
        }
        for (Method m : type.getMethods()) if (m.getName().equals(name) && m.getParameterTypes().length == arity) return m;
        return null;
    }

    /** The declared/public method named {@code name} whose parameters accept {@code args}. */
    private static Method compatible(Class<?> type, String name, Object... args) {
        java.util.List<Method> candidates = new java.util.ArrayList<>();
        for (Class<?> c = type; c != null; c = c.getSuperclass()) candidates.addAll(java.util.Arrays.asList(c.getDeclaredMethods()));
        candidates.addAll(java.util.Arrays.asList(type.getMethods()));
        for (Method m : candidates) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            Class<?>[] p = m.getParameterTypes();
            boolean ok = true;
            for (int i = 0; i < p.length && ok; i++) {
                if (args[i] == null) ok = !p[i].isPrimitive();
                else ok = box(p[i]).isInstance(args[i]);
            }
            if (!ok) continue;
            try { m.setAccessible(true); } catch (Throwable ignored) { }
            return m;
        }
        return null;
    }

    private static Class<?> box(Class<?> t) {
        if (!t.isPrimitive()) return t;
        if (t == int.class) return Integer.class;
        if (t == boolean.class) return Boolean.class;
        if (t == long.class) return Long.class;
        if (t == float.class) return Float.class;
        if (t == double.class) return Double.class;
        if (t == byte.class) return Byte.class;
        if (t == short.class) return Short.class;
        if (t == char.class) return Character.class;
        return Void.class;
    }

    private static Object invoke(Object receiver, String name, Object... args) throws Exception {
        Method m = compatible(receiver.getClass(), name, args);
        return m == null ? null : m.invoke(receiver, args);
    }

    @Override
    public ActivationResult activate(String legacyBlockId, int x, int y, int z, HostPlayer player, int side,
                                      float hitX, float hitY, float hitZ) {
        try {
            ClassLoader loader = Legacy1122BridgeImpl.class.getClassLoader();
            Object block = findBlock(loader, legacyBlockId);
            if (block == null) return ActivationResult.DECLINED;
            Object world = createWorldFacade(host);
            Object tile;
            synchronized (liveTiles) { tile = liveTiles.get(x + "," + y + "," + z); }
            if (tile == null) { createTile(legacyBlockId, x, y, z); synchronized (liveTiles) { tile = liveTiles.get(x + "," + y + "," + z); } }
            Legacy1122WorldFacadeRaw.currentTile(tile);
            Object p = Legacy1122PlayerFacadeRaw.create(world, player);
            Class<?> posType = Class.forName("net.minecraft.util.math.BlockPos", true, p.getClass().getClassLoader());
            Object pos = posType.getConstructor(int.class, int.class, int.class).newInstance(x, y, z);
            int meta = host == null ? 0 : host.getMeta(x, y, z);
            Object state = null;
            try { state = invoke(block, "getStateFromMeta", Integer.valueOf(meta)); }
            catch (Throwable invalidMeta) { if (host != null) host.log("UMB-BRIDGE-1122 invalid activation meta " + meta + " for " + legacyBlockId + "; using default state"); }
            if (state == null) try { state = invoke(block, "func_176203_a", Integer.valueOf(meta)); }
            catch (Throwable invalidSrgMeta) { /* default state below */ }
            if (state == null) state = invoke(block, "getDefaultState");
            if (state == null) state = invoke(block, "func_176223_P");
            Legacy1122WorldFacadeRaw.currentState(state);
            Class<?> handType = Class.forName("net.minecraft.util.EnumHand", true, loader);
            Class<?> facingType = Class.forName("net.minecraft.util.EnumFacing", true, loader);
            Object hand = Enum.valueOf((Class) handType, "MAIN_HAND");
            Object[] facings = facingType.getEnumConstants();
            Object facing = side >= 0 && side < facings.length ? facings[side] : facings[1];
            Method activate = compatible(block.getClass(), "onBlockActivated", world, pos, state, p, hand, facing,
                    Float.valueOf(hitX), Float.valueOf(hitY), Float.valueOf(hitZ));
            if (activate == null) activate = compatible(block.getClass(), "func_180639_a", world, pos, state, p, hand, facing,
                    Float.valueOf(hitX), Float.valueOf(hitY), Float.valueOf(hitZ));
            if (activate == null) throw new NoSuchMethodException("Block.onBlockActivated/func_180639_a");
            Object result = activate.invoke(block, world, pos, state, p, hand, facing, hitX, hitY, hitZ);
            boolean handled = Boolean.TRUE.equals(result) || (result != null && "SUCCESS".equals(String.valueOf(result)));
            java.lang.reflect.Field containerField = Legacy1122PlayerFacadeRaw.field(p.getClass(), "field_71070_bA", "by");
            Object container = containerField == null ? null : containerField.get(p);
            ContainerHandle1122 handle = container == null ? null : new ContainerHandle1122(container, p, legacyBlockId);
            if (host != null) host.log("UMB-BRIDGE-1122 activate " + legacyBlockId + " result=" + result + " handled=" + handled
                    + " openContainer=" + (container == null ? "null" : container.getClass().getName())
                    + " playerClass=" + p.getClass().getName());
            return new ActivationResult(handled, handle);
        } catch (Throwable t) {
            if (host != null) {
                Throwable root = t;
                while (root.getCause() != null && root.getCause() != root) root = root.getCause();
                StringBuilder where = new StringBuilder();
                StackTraceElement[] frames = root.getStackTrace();
                for (int i = 0; i < Math.min(8, frames.length); i++) where.append(" | at ").append(frames[i]);
                host.log("UMB-BRIDGE-1122 activate failed: " + t + " root=" + root + where);
            }
            return ActivationResult.DECLINED;
        }
    }

    @Override
    public void clicked(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        // no-op stub
    }

    @Override
    public void tickTile(TileHandle t) {
        if (t != null) t.tick();
    }

    @Override
    public void shutdown() {
        Legacy1122ClientTileProvider.uninstall();
        synchronized (liveTiles) { liveTiles.clear(); liveTileHandles.clear(); }
        booted = false;
    }

    @Override
    public void placedBy(String legacyBlockId, int x, int y, int z, HostPlayer player) {
        // no-op stub
    }

    @Override
    public void added(String legacyBlockId, int x, int y, int z) {
        // no-op stub
    }

    @Override
    public void neighborChanged(String legacyBlockId, int x, int y, int z, String neighborLegacyBlockId) {
        // no-op stub
    }

    @Override
    public void broken(String legacyBlockId, int x, int y, int z, int meta, HostPlayer player) {
        // no-op stub
    }

    @Override
    public boolean canPlaceAt(String legacyBlockId, int x, int y, int z) {
        return true;
    }

    @Override
    public dev.umb.bridge.api.StackData useItemRightClick(String legacyItemId, HostPlayer player) {
        return null;
    }

    @Override
    public ItemUseResult useItemOnBlock(String legacyItemId, HostPlayer player, int x, int y, int z, int side,
                                         float hitX, float hitY, float hitZ) {
        return ItemUseResult.DECLINED;
    }

    @Override
    public EntityHandle restoreEntity(byte[] nbt) {
        if (!booted || nbt == null || nbt.length == 0) return null;
        try {
            ClassLoader loader = Legacy1122BridgeImpl.class.getClassLoader();
            Object world = entityWorldFacade;
            if (world == null) {
                world = createWorldFacade(host);
                entityWorldFacade = world;
            }
            Object tag = readNbt(nbt, loader);
            if (tag == null) return null;
            Class<?> entityList = Class.forName("net.minecraft.entity.EntityList", true, loader);
            Object entity = null;
            for (java.lang.reflect.Method m : entityList.getMethods()) {
                if ((!m.getName().equals("createEntityFromNBT") && !m.getName().equals("func_75615_a"))
                        || m.getParameterTypes().length != 2) continue;
                try {
                    m.setAccessible(true);
                    entity = m.invoke(null, tag, world);
                    break;
                } catch (Throwable ignored) {
                    // Try the next overload spelling.
                }
            }
            if (entity == null) {
                if (host != null) host.log("UMB-BRIDGE-1122 restoreEntity: no entity decoded");
                return null;
            }
            EntityHandle1122 handle = new EntityHandle1122(entity, world);
            synchronized (liveEntities) { liveEntities.put(entity, handle); }
            return handle;
        } catch (Throwable t) {
            if (host != null) host.log("UMB-BRIDGE-1122 restoreEntity failed: " + t);
            return null;
        }
    }

    private static Object readNbt(byte[] nbt, ClassLoader loader) {
        try {
            Class<?> tools = Class.forName("net.minecraft.nbt.CompressedStreamTools", true, loader);
            java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(nbt);
            for (String name : new String[] {"readCompressed", "read", "func_74796_a"}) {
                for (java.lang.reflect.Method m : tools.getMethods()) {
                    if (!m.getName().equals(name) || m.getParameterTypes().length != 1) continue;
                    Class<?> param = m.getParameterTypes()[0];
                    try {
                        m.setAccessible(true);
                        Object tag;
                        if (param == byte[].class) {
                            tag = m.invoke(null, new Object[] {nbt});
                        } else if (param.isAssignableFrom(java.io.InputStream.class)) {
                            tag = m.invoke(null, new Object[] {in});
                        } else {
                            continue;
                        }
                        if (tag != null) return tag;
                    } catch (Throwable ignored) {
                        // Try the next spelling.
                    }
                }
            }
        } catch (Throwable ignored) {
            // No NBT runtime visible; the caller logs the honest null.
        }
        return null;
    }

    @Override
    public void tickEntities() {
        java.util.List<EntityHandle1122> snapshot;
        synchronized (liveEntities) { snapshot = new java.util.ArrayList<EntityHandle1122>(liveEntities.values()); }
        for (EntityHandle1122 handle : snapshot) {
            if (handle == null) continue;
            try {
                handle.tick();
            } catch (Throwable t) {
                if (host != null) host.log("UMB-ENTITY-1122 tick failed: " + t);
            }
            if (!handle.isValid()) {
                synchronized (liveEntities) { liveEntities.values().remove(handle); }
            }
        }
    }

    public String bootFailure() {
        return bootFailure;
    }
}
