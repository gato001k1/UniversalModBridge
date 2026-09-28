package dev.umb.legacy.legacyside;

import cpw.mods.fml.common.ModContainer;
import cpw.mods.fml.common.registry.EntityRegistry;
import dev.umb.bridge.api.EntityRenderCapture;
import dev.umb.legacy.legacyside.render.LegacyRenderCapture;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.entity.Entity;
import net.minecraft.world.World;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded, real-FML render sweep. It discovers the live EntityRegistry, constructs every
 * registered class against the synthetic legacy world, and runs the same RenderManager capture
 * boundary used by live twins. A bad constructor or renderer becomes one CSV row, never a probe
 * abort. Runtime code is deliberately identity-free: the registry supplies all mod/entity names.
 */
public final class EntityRenderSweepProbe {
    private EntityRenderSweepProbe() {}

    public static String run() throws Exception {
        StringBuilder report = new StringBuilder();
        VehicleProbe.FakeHostLevel host = new VehicleProbe.FakeHostLevel();
        LegacyBridgeImpl bridge = new LegacyBridgeImpl();
        bridge.boot(host);
        World world = (World) field(LegacyBridgeImpl.class, "umbWorld").get(bridge);

        List<Row> rows = registrations();
        rows.sort(Comparator.comparing((Row r) -> r.modid == null ? "" : r.modid)
                .thenComparing(r -> r.registryName == null ? "" : r.registryName)
                .thenComparing(r -> r.entityClass == null ? "" : r.entityClass.getName()));
        int rendered = 0, empty = 0, failed = 0, staticRows = 0, animatedRows = 0;
        for (Row row : rows) {
            try {
                Entity entity = instantiate(row.entityClass, world);
                if (entity == null) throw new IllegalStateException("no supported constructor");
                hydrateAircraftInfo(entity);
                entity.func_70107_b(0.0D, 1.0D, 0.0D);
                entity.field_70177_z = 0.0F;
                entity.field_70125_A = 25.0F;
                row.tickCount = tickForRender(entity);
                // The normal spawn path establishes relationships/info during its first ticks;
                // restore the same render prerequisites after bounded probe ticks that may clear
                // unresolved client-side state.
                hydrateAircraftInfo(entity);
                EntityRenderCapture first = LegacyRenderCapture.capture(entity, 0.0F);
                EntityRenderCapture second = LegacyRenderCapture.capture(entity, 0.5F);
                // A renderer may initialize model state after its first no-geometry return. Give
                // the established capture warmup one more bounded attempt before counting empty.
                EntityRenderCapture finalCapture = second.vertexCount() > 0
                        ? second : LegacyRenderCapture.capture(entity, 0.0F);
                row.firstVerts = first.vertexCount();
                row.secondVerts = finalCapture.vertexCount();
                row.firstDraws = first.draws.size();
                row.secondDraws = finalCapture.draws.size();
                row.textures = textures(finalCapture);
                row.animated = first.vertexCount() > 0 && finalCapture.vertexCount() > 0
                        && !sameGeometry(first, finalCapture);
                row.status = finalCapture.vertexCount() > 0 ? "captured" : "empty";
                row.cause = classify(row, entity);
                if (row.animated) animatedRows++;
                else if (finalCapture.vertexCount() > 0) staticRows++;
                if (finalCapture.vertexCount() > 0) rendered++; else empty++;
            } catch (Throwable failure) {
                row.status = "failed";
                row.error = reason(failure);
                row.cause = "construction-or-tick-exception";
                failed++;
            }
        }
        Benchmark benchmark = benchmark(rows, world);
        writeCsv(rows);
        // M1ProbeMain recognizes ENTITY-OK as a successful real-FML probe result; retain that
        // outer contract while making the sweep marker explicit for report parsers.
        report.insert(0, "ENTITY-OK\n");
        report.append("ENTITY-RENDER-SWEEP-OK\n");
        report.append("registered=").append(rows.size())
                .append(" captured=").append(rendered)
                .append(" empty=").append(empty)
                .append(" failed=").append(failed)
                .append(" static=").append(staticRows)
                .append(" animated=").append(animatedRows).append('\n');
        report.append("benchmark_entities=").append(benchmark.entities)
                .append(" benchmark_ms=").append(benchmark.millis)
                .append(" benchmark_ms_per_entity=").append(benchmark.entities == 0 ? 0.0D
                        : benchmark.millis / (double) benchmark.entities)
                .append(" benchmark_class=").append(benchmark.entityClass).append('\n');
        report.append("transform_cache_hits=").append(LegacyRenderCapture.transformCacheHits())
                .append(" transform_cache_misses=").append(LegacyRenderCapture.transformCacheMisses())
                .append('\n');
        report.append("csv=").append(System.getProperty("umb.entity.render.sweep.csv",
                new File(System.getProperty("umb.legacy.out", "."), "entity-render-sweep.csv").getPath()))
                .append('\n');
        assertRequestedRegression(rows, report);
        for (Row row : rows) report.append(row.csv()).append('\n');
        return report.toString();
    }

    private static int tickForRender(Entity entity) {
        int requested;
        try {
            requested = Integer.parseInt(System.getProperty("umb.entity.render.sweep.ticks", "3"));
        } catch (NumberFormatException e) {
            requested = 3;
        }
        requested = Math.max(0, Math.min(20, requested));
        int completed = 0;
        for (; completed < requested; completed++) {
            try {
                entity.func_70071_h_();
            } catch (Throwable failure) {
                // A real tick is valuable evidence even when a mod's optional branch fails;
                // leave the row renderable and let its final capture classify the root cause.
                break;
            }
        }
        return completed;
    }

    private static String classify(Row row, Entity entity) {
        if (row.status.equals("captured") && row.textures.length() == 0) return "texture-binding";
        if (!row.status.equals("empty")) return row.status;
        String diagnostic = LegacyRenderCapture.lastDiagnostic(
                row.entityClass == null ? "" : row.entityClass.getName());
        if (diagnostic.indexOf("renderer-null") >= 0) return "no-renderer-mapping";
        String info = infoSummary(entity);
        if (info.indexOf("=null") >= 0) return "no-info";
        if (diagnostic.indexOf("renderer-returned-no-geometry") >= 0) return "renderer-early-return";
        return "empty-unknown";
    }

    private static String infoSummary(Entity entity) {
        StringBuilder out = new StringBuilder();
        try {
            for (java.lang.reflect.Method method : entity.getClass().getMethods()) {
                String name = method.getName();
                if (method.getParameterTypes().length != 0 || method.getReturnType() == Void.TYPE
                        || !name.startsWith("get") || !name.endsWith("Info")) continue;
                Object value = method.invoke(entity);
                if (out.length() > 0) out.append(',');
                out.append(name).append('=').append(value == null ? "null" : "set");
            }
        } catch (Throwable ignored) {
            return "info-scan-failed";
        }
        return out.toString();
    }

    /** Optional generic gate used by focused regression scripts; production runtime has no class names. */
    private static void assertRequestedRegression(List<Row> rows, StringBuilder report) {
        String wanted = System.getProperty("umb.entity.render.regression.class", "").trim();
        if (wanted.length() == 0) return;
        int minimum;
        try {
            minimum = Integer.parseInt(System.getProperty("umb.entity.render.regression.minVerts", "0"));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("invalid regression vertex threshold");
        }
        Row match = null;
        for (Row row : rows) {
            if (row.entityClass != null && wanted.equals(row.entityClass.getName())) {
                match = row;
                break;
            }
        }
        if (match == null) {
            throw new IllegalStateException("render regression class not registered: " + wanted);
        }
        report.append("regression_class=").append(wanted)
                .append(" regression_verts=").append(match.secondVerts)
                .append(" regression_min=").append(minimum).append('\n');
        if (match.secondVerts < minimum) {
            throw new IllegalStateException("render regression failed class=" + wanted
                    + " verts=" + match.secondVerts + " min=" + minimum
                    + " status=" + match.status + " error=" + match.error);
        }
    }

    /** Measures the same per-frame path used by live twins, with ten independent instances. */
    private static Benchmark benchmark(List<Row> rows, World world) {
        Row candidate = null;
        String requestedClass = System.getProperty("umb.entity.render.benchmark.class", "").trim();
        for (Row row : rows) {
            if (row.firstVerts > 0 && row.entityClass != null
                    && ((requestedClass.length() > 0 && requestedClass.equals(row.entityClass.getName()))
                        || (requestedClass.length() == 0
                            && (candidate == null || row.firstVerts > candidate.firstVerts)))) {
                candidate = row;
                if (requestedClass.length() > 0) break;
            }
        }
        if (candidate == null) return new Benchmark(0, 0L, "none");
        List<Entity> entities = new ArrayList<Entity>();
        try {
            for (int i = 0; i < 10; i++) {
                Entity entity = instantiate(candidate.entityClass, world);
                hydrateAircraftInfo(entity);
                entity.func_70107_b(i * 2.0D, 1.0D, 0.0D);
                entity.field_70177_z = i * 13.0F;
                entity.field_70125_A = 25.0F;
                tickForRender(entity);
                entities.add(entity);
            }
            for (Entity entity : entities) LegacyRenderCapture.captureTransform(entity, 0.25F);
            long start = System.nanoTime();
            for (Entity entity : entities) LegacyRenderCapture.captureTransform(entity, 0.25F);
            return new Benchmark(entities.size(), (System.nanoTime() - start) / 1_000_000L,
                    candidate.entityClass.getName());
        } catch (Throwable failure) {
            System.out.println("[UMB-LEGACY] entity render benchmark skipped: " + reason(failure));
            return new Benchmark(0, 0L, candidate.entityClass.getName());
        }
    }

    private static List<Row> registrations() throws Exception {
        Object registry = EntityRegistry.instance();
        Object multimap = Statics.getInstance(registry, EntityRegistry.class, "entityRegistrations");
        if (multimap == null) return new ArrayList<Row>();
        Collection<?> entries = (Collection<?>) multimap.getClass().getMethod("entries").invoke(multimap);
        List<Row> rows = new ArrayList<Row>();
        for (Object entry : entries) {
            java.lang.reflect.Method getKey = entry.getClass().getMethod("getKey");
            java.lang.reflect.Method getValue = entry.getClass().getMethod("getValue");
            getKey.setAccessible(true);
            getValue.setAccessible(true);
            ModContainer mod = (ModContainer) getKey.invoke(entry);
            EntityRegistry.EntityRegistration registration =
                    (EntityRegistry.EntityRegistration) getValue.invoke(entry);
            rows.add(new Row(mod == null ? null : mod.getModId(), registration.getEntityName(),
                    registration.getEntityClass()));
        }
        return rows;
    }

    private static Entity instantiate(Class<? extends Entity> type, World world) throws Exception {
        if (type == null || Modifier.isAbstract(type.getModifiers())) {
            throw new IllegalStateException("abstract-or-null-class");
        }
        Entity fromItem = instantiateFromRegisteredItem(type, world);
        if (fromItem != null) return fromItem;
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        for (Constructor<?> constructor : constructors) {
            Class<?>[] p = constructor.getParameterTypes();
            if (p.length == 1 && p[0].isAssignableFrom(world.getClass())) {
                try {
                    constructor.setAccessible(true);
                    return (Entity) constructor.newInstance(world);
                } catch (InvocationTargetException e) {
                    throw e.getCause() instanceof Exception ? (Exception) e.getCause() : e;
                }
            }
        }
        // A few legacy registrations expose only coordinate constructors. Try those without
        // naming a family; the renderer sees the same live world and normalized transform.
        for (Constructor<?> constructor : constructors) {
            Class<?>[] p = constructor.getParameterTypes();
            if (p.length == 4 && p[0].isAssignableFrom(world.getClass())
                    && p[1] == double.class && p[2] == double.class && p[3] == double.class) {
                constructor.setAccessible(true);
                return (Entity) constructor.newInstance(world, 0.0D, 1.0D, 0.0D);
            }
        }
        Entity entity = (Entity) allocate(type);
        field(net.minecraft.entity.Entity.class, "field_70170_p").set(entity, world);
        return entity;
    }

    /**
     * Vehicle entities commonly load their model/info only through the item factory. Use the
     * registry's own factory when its result is the class under test; this is generic and avoids
     * guessing a type-name field or a mod-specific NBT schema.
     */
    private static Entity instantiateFromRegisteredItem(Class<? extends Entity> wanted, World world) {
        Iterable<Item> items = cpw.mods.fml.common.registry.GameData.getItemRegistry().typeSafeIterable();
        for (Item item : items) {
            try {
                ItemStack stack = new ItemStack(item, 1, 0);
                for (java.lang.reflect.Method factory : item.getClass().getMethods()) {
                    if (!isEntityFactory(factory, world, stack)) continue;
                    Object[] args = factoryArguments(factory, world, stack);
                    Object candidate = factory.invoke(item, args);
                    if (candidate instanceof Entity && wanted.isInstance(candidate)) return (Entity) candidate;
                }
            } catch (Throwable ignored) {
                // One malformed item factory must not prevent the remaining registered classes.
            }
        }
        return null;
    }

    /** Finds registered item spawn helpers without depending on any mod's method or class names. */
    private static boolean isEntityFactory(java.lang.reflect.Method method, World world, ItemStack stack) {
        Class<?>[] p = method.getParameterTypes();
        String name = method.getName().toLowerCase(java.util.Locale.ROOT);
        return p.length == 5 && name.contains("create")
                && Entity.class.isAssignableFrom(method.getReturnType())
                && p[0].isAssignableFrom(world.getClass())
                && numeric(p[1]) && numeric(p[2]) && numeric(p[3])
                && p[4].isAssignableFrom(stack.getClass());
    }

    private static Object[] factoryArguments(java.lang.reflect.Method method, World world, ItemStack stack) {
        Class<?>[] p = method.getParameterTypes();
        return new Object[] {world, number(p[1], 0.0D), number(p[2], 1.0D),
                number(p[3], 0.0D), stack};
    }

    private static boolean numeric(Class<?> type) {
        return type == double.class || type == float.class || type == int.class
                || type == long.class;
    }

    private static Object number(Class<?> type, double value) {
        if (type == float.class) return Float.valueOf((float) value);
        if (type == int.class) return Integer.valueOf((int) value);
        if (type == long.class) return Long.valueOf((long) value);
        return Double.valueOf(value);
    }

    /** Populate constructor-created aircraft from the same registered info objects their item path uses. */
    private static void hydrateAircraftInfo(Entity entity) {
        hydrateNamedInfo(entity);
        hydrateRenderState(entity);
        java.lang.reflect.Method typeMethod = noArg(entity.getClass(), "getEntityType");
        java.lang.reflect.Method setter = oneArg(entity.getClass(), "setTypeName", String.class);
        java.lang.reflect.Method updater = noArg(entity.getClass(), "onUpdateAircraft");
        if (typeMethod == null || setter == null || updater == null) return;
        try {
            String entityType = String.valueOf(typeMethod.invoke(entity)).toLowerCase(java.util.Locale.ROOT)
                    + " " + entity.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
            Iterable<Item> items = cpw.mods.fml.common.registry.GameData.getItemRegistry().typeSafeIterable();
            for (Item item : items) {
                try {
                    java.lang.reflect.Method infoMethod = noArg(item.getClass(), "getAircraftInfo");
                    if (infoMethod == null) continue;
                    Object info = infoMethod.invoke(item);
                    if (info == null) continue;
                    String infoType = info.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
                    if (!sameAircraftFamily(entityType, infoType)) continue;
                    Field name = field(info.getClass(), "name");
                    Object typeName = name.get(info);
                    if (typeName == null || String.valueOf(typeName).isEmpty()) continue;
                    setter.invoke(entity, String.valueOf(typeName));
                    updater.invoke(entity);
                    return;
                } catch (Throwable ignored) {
                    // Continue to the next registered aircraft info.
                }
            }
        } catch (Throwable ignored) {
            // A missing info record remains an honest empty row; another entity must still run.
        }
    }

    /**
     * Some registered projectile classes are constructed by weapon code rather than an Item.
     * Resolve their already-loaded info manager from the entity getter's return type, seed the
     * first valid registered name through the entity's own setter, then invoke its normal
     * parameter hook. The lookup is reflection-only and derives every class from live types.
     */
    private static void hydrateNamedInfo(Entity entity) {
        java.lang.reflect.Method getter = noArg(entity.getClass(), "getInfo");
        java.lang.reflect.Method setter = nameSetter(entity.getClass());
        if (getter == null || getter.getReturnType() == Object.class) return;
        try {
            Class<?> infoType = getter.getReturnType();
            String packageName = infoType.getPackage().getName();
            String managerName = infoType.getSimpleName().endsWith("Info")
                    ? infoType.getSimpleName() + "Manager" : "InfoManager";
            Class<?> managerType = Class.forName(packageName + "." + managerName,
                    true, infoType.getClassLoader());
            java.lang.reflect.Method values = noArg(managerType, "getValues");
            java.lang.reflect.Method infoSetter = oneArg(entity.getClass(), "setInfo", infoType);
            Object all = values == null ? null : values.invoke(null);
            if (values == null && infoSetter != null) {
                java.lang.reflect.Method keys = noArg(managerType, "getKeySet");
                java.lang.reflect.Method infoGetter = oneArg(managerType, "get", String.class);
                if (keys != null && infoGetter != null) {
                    Object keySet = keys.invoke(null);
                    if (keySet instanceof Iterable<?>) {
                        for (Object key : (Iterable<?>) keySet) {
                            Object info = infoGetter.invoke(null, String.valueOf(key));
                            if (info != null && infoType.isInstance(info)) {
                                infoSetter.invoke(entity, info);
                                return;
                            }
                        }
                    }
                }
                return;
            }
            if (!(all instanceof Iterable<?>)) return;
            java.lang.reflect.Method infoName = noArg(infoType, "getName");
            java.lang.reflect.Method onSet = noArg(entity.getClass(), "onSetWeasponInfo");
            for (Object info : (Iterable<?>) all) {
                if (info == null || !infoType.isInstance(info)) continue;
                if (infoSetter != null) {
                    infoSetter.invoke(entity, info);
                    return;
                }
                String name = infoName == null ? readNameField(infoType, info) :
                        String.valueOf(infoName.invoke(info));
                if (name == null || name.length() == 0 || "null".equals(name)) continue;
                if (setter != null) {
                    setter.invoke(entity, name);
                    if (onSet != null) onSet.invoke(entity);
                    return;
                }
            }
        } catch (Throwable ignored) {
            // An absent or unloaded info manager remains an honest empty row.
        }
    }

    private static java.lang.reflect.Method nameSetter(Class<?> type) {
        java.lang.reflect.Method setter = oneArg(type, "setName", String.class);
        if (setter != null) return setter;
        return oneArg(type, "setWeaponName", String.class);
    }

    /**
     * Seeds bounded render prerequisites that normal spawn helpers establish before the first
     * client render. This is method/field-shape based; it does not depend on a mod identity.
     */
    private static void hydrateRenderState(Entity entity) {
        invokeIntSetter(entity, "setType", 1);
        invokeIntSetter(entity, "setKind", 1);
        invokeBoolSetter(entity, "setOpen", true);
        invokeIntSetter(entity, "setChainLength", 16);
        setFieldIfPresent(entity, "despawnCount", Integer.valueOf(21));
        try {
            java.lang.reflect.Method tow = method(entity.getClass(), "setTowEntity", 2);
            if (tow != null || hasField(entity, "towEntity")) {
                Object towEntity = allocateEntity(entity.field_70170_p, 0.0D, 1.0D, 0.0D);
                Object towedEntity = allocateEntity(entity.field_70170_p, 0.0D, 1.0D, 8.0D);
                if (tow != null) {
                    tow.setAccessible(true);
                    tow.invoke(entity, towEntity, towedEntity);
                }
                setPreviousPosition((Entity) towEntity, 0.0D, 1.0D, 0.0D);
                setPreviousPosition((Entity) towedEntity, 0.0D, 1.0D, 8.0D);
                setFieldIfPresent(entity, "towEntity", towEntity);
                setFieldIfPresent(entity, "towedEntity", towedEntity);
            }
        } catch (Throwable ignored) {
            // Optional relationship state is not required for unrelated renderers.
        }
        setFieldIfPresent(entity, "rotCover", 1.0F);
        setFieldIfPresent(entity, "prevRotCover", 1.0F);
    }

    private static void invokeIntSetter(Entity entity, String name, int value) {
        try {
            java.lang.reflect.Method setter = method(entity.getClass(), name, 1);
            if (setter != null && setter.getParameterTypes()[0] == int.class) {
                setter.setAccessible(true);
                setter.invoke(entity, Integer.valueOf(value));
            }
        } catch (Throwable ignored) { }
    }

    private static void invokeBoolSetter(Entity entity, String name, boolean value) {
        try {
            java.lang.reflect.Method setter = method(entity.getClass(), name, 1);
            if (setter != null && setter.getParameterTypes()[0] == boolean.class) {
                setter.setAccessible(true);
                setter.invoke(entity, Boolean.valueOf(value));
            }
        } catch (Throwable ignored) { }
    }

    private static void setFieldIfPresent(Entity entity, String name, Object value) {
        try {
            Field f = field(entity.getClass(), name);
            if (f.getType().isInstance(value)
                    || (f.getType() == float.class && value instanceof Float)
                    || (f.getType() == int.class && value instanceof Integer)
                    || (f.getType() == boolean.class && value instanceof Boolean)
                    || (f.getType() == double.class && value instanceof Double)) {
                f.set(entity, value);
            }
        } catch (Throwable ignored) { }
    }

    private static Entity allocateEntity(World world, double x, double y, double z) throws Exception {
        // Entity itself is abstract on this runtime; use a concrete vanilla carrier solely for
        // relationship positions, so the probe exercises the renderer's real branch.
        Entity entity = new net.minecraft.entity.item.EntityItem(world, x, y, z,
                new ItemStack((net.minecraft.item.Item) null, 1, 0));
        return entity;
    }

    private static void setPreviousPosition(Entity entity, double x, double y, double z) {
        setFieldIfPresent(entity, "field_70142_S", Double.valueOf(x));
        setFieldIfPresent(entity, "field_70137_T", Double.valueOf(y));
        setFieldIfPresent(entity, "field_70136_U", Double.valueOf(z));
        setFieldIfPresent(entity, "field_70169_q", Double.valueOf(x));
        setFieldIfPresent(entity, "field_70167_r", Double.valueOf(y));
        setFieldIfPresent(entity, "field_70166_s", Double.valueOf(z));
    }

    private static boolean hasField(Entity entity, String name) {
        try {
            field(entity.getClass(), name);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readNameField(Class<?> infoType, Object info) {
        try {
            return String.valueOf(field(infoType, "name").get(info));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean sameAircraftFamily(String entityType, String infoType) {
        String e = familyName(entityType);
        String i = familyName(infoType);
        return e.length() >= 3 && i.length() >= 3 && (e.contains(i) || i.contains(e));
    }

    private static String familyName(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        lower = lower.replace("mch_", "").replace("mcp_", "").replace("entity", "")
                .replace("aircraft", "").replace("info", "");
        return lower.replaceAll("[^a-z0-9]", "");
    }

    private static java.lang.reflect.Method noArg(Class<?> type, String name) {
        for (java.lang.reflect.Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == 0) return method;
        }
        return null;
    }

    private static java.lang.reflect.Method method(Class<?> type, String name, int arity) {
        for (java.lang.reflect.Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterTypes().length == arity) return method;
        }
        return null;
    }

    private static java.lang.reflect.Method oneArg(Class<?> type, String name, Class<?> argument) {
        for (java.lang.reflect.Method method : type.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (method.getName().equals(name) && parameters.length == 1
                    && parameters[0].isAssignableFrom(argument)) return method;
        }
        return null;
    }

    private static boolean sameGeometry(EntityRenderCapture a, EntityRenderCapture b) {
        if (a.draws.size() != b.draws.size()) return false;
        for (int i = 0; i < a.draws.size(); i++) {
            EntityRenderCapture.Draw x = a.draws.get(i), y = b.draws.get(i);
            if (!java.util.Objects.equals(x.texture, y.texture)
                    || x.vertexCount != y.vertexCount
                    || !java.util.Arrays.equals(x.vertices, y.vertices)) return false;
        }
        return true;
    }

    private static String textures(EntityRenderCapture capture) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<String>();
        for (EntityRenderCapture.Draw draw : capture.draws) if (draw.texture != null) out.add(draw.texture);
        return String.join("|", out);
    }

    private static void writeCsv(List<Row> rows) {
        String path = System.getProperty("umb.entity.render.sweep.csv");
        if (path == null || path.isEmpty()) {
            String out = System.getProperty("umb.legacy.out");
            path = out == null || out.isEmpty() ? null : new File(out, "entity-render-sweep.csv").getPath();
        }
        if (path == null || path.isEmpty()) return;
        try (Writer out = new OutputStreamWriter(new FileOutputStream(new File(path)), StandardCharsets.UTF_8)) {
            out.write("modid,registry_name,entity_class,status,first_verts,second_verts,first_draws,second_draws,animated,ticks,cause,textures,error\n");
            for (Row row : rows) out.write(row.csv());
        } catch (Throwable failure) {
            System.out.println("[UMB-LEGACY] entity render sweep CSV failed: " + reason(failure));
        }
    }

    private static Object allocate(Class<?> type) throws Exception {
        Class<?> helper = Class.forName("dev.umb.legacy.legacyside.UmbUnsafe");
        java.lang.reflect.Method method = helper.getDeclaredMethod("allocate", Class.class);
        method.setAccessible(true);
        return method.invoke(null, type);
    }

    private static Field field(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                Field result = type.getDeclaredField(name);
                result.setAccessible(true);
                return result;
            } catch (NoSuchFieldException ignored) { }
        }
        throw new IllegalStateException("missing field " + owner.getName() + "." + name);
    }

    private static String reason(Throwable failure) {
        Throwable root = failure;
        if (root instanceof InvocationTargetException && root.getCause() != null) root = root.getCause();
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getClass().getName() + (root.getMessage() == null ? "" : ":" + root.getMessage());
    }

    private static final class Row {
        final String modid, registryName;
        final Class<? extends Entity> entityClass;
        int firstVerts, secondVerts, firstDraws, secondDraws, tickCount;
        boolean animated;
        String status = "unattempted", textures = "", cause = "", error = "";

        Row(String modid, String registryName, Class<?> entityClass) {
            this.modid = modid;
            this.registryName = registryName;
            @SuppressWarnings("unchecked") Class<? extends Entity> c = (Class<? extends Entity>) entityClass;
            this.entityClass = c;
        }

        String csv() {
            return csv(modid) + ',' + csv(registryName) + ',' + csv(entityClass == null ? "" : entityClass.getName())
                    + ',' + csv(status) + ',' + firstVerts + ',' + secondVerts + ',' + firstDraws + ',' + secondDraws
                    + ',' + animated + ',' + tickCount + ',' + csv(cause) + ',' + csv(textures) + ',' + csv(error) + '\n';
        }

        private static String csv(String value) {
            if (value == null) return "";
            return '"' + value.replace("\"", "\"\"") + '"';
        }
    }

    private static final class Benchmark {
        final int entities;
        final long millis;
        final String entityClass;

        Benchmark(int entities, long millis, String entityClass) {
            this.entities = entities;
            this.millis = millis;
            this.entityClass = entityClass;
        }
    }
}
