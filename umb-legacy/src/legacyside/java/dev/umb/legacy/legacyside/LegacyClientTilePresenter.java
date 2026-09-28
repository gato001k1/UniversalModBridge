package dev.umb.legacy.legacyside;

import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Constructor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.minecraft.client.entity.EntityClientPlayerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;

/**
 * Runs the client-only presentation half of loaded legacy tiles against isolated tile copies.
 *
 * <p>The authoritative server tick remains the only stateful legacy tick. A number of 1.7.10
 * mods put smoke, custom EntityFX, or client sounds in a tile's updateEntity branch guarded by
 * World.isRemote; in UMB the real server tile therefore cannot produce that presentation itself.
 * This bounded copy pass gives those callbacks the synthetic WorldClient and the already-installed
 * client proxies, while NBT-copying each tile prevents client code from mutating server state.
 * No mod class or namespace is inspected.</p>
 */
final class LegacyClientTilePresenter {
    private static final int MAX_TILES_PER_TICK = 256;
    private static final double MAX_DISTANCE_SQUARED = 64.0D * 64.0D;
    /** Constructor and primitive-field metadata are stable per legacy tile class. */
    private static final ConcurrentMap<Class<?>, Constructor<?>> CONSTRUCTORS = new ConcurrentHashMap<>();
    private static final java.util.Set<Class<?>> NO_CONSTRUCTOR = ConcurrentHashMap.newKeySet();
    private static final ConcurrentMap<Class<?>, Field[]> PRIMITIVE_FIELDS = new ConcurrentHashMap<>();

    private LegacyClientTilePresenter() {
    }

    static void tick(UmbWorld serverWorld, WorldClient clientWorld, EntityClientPlayerMP player) {
        if (serverWorld == null || clientWorld == null || player == null) {
            return;
        }
        List<TileEntity> tiles = serverWorld.clientTileSnapshot();
        int considered = 0;
        int ticked = 0;
        int failed = 0;
        for (TileEntity source : tiles) {
            if (source == null || considered >= MAX_TILES_PER_TICK) {
                break;
            }
            double dx = source.field_145851_c + 0.5D - player.field_70165_t;
            double dy = source.field_145848_d + 0.5D - player.field_70163_u;
            double dz = source.field_145849_e + 0.5D - player.field_70161_v;
            if (dx * dx + dy * dy + dz * dz > MAX_DISTANCE_SQUARED) {
                continue;
            }
            considered++;
            try {
                // A legacy GUI may read World#getTileEntity(...).power/progress directly rather
                // than receive the value through its container.  Refresh the persistent client
                // twin immediately before presentation as well as at facade bind time: this
                // closes the one-tick ordering window where a custom PacketDispatcher/updateEntity
                // path or a newly-opened GUI could observe the old twin while the server TE was
                // already nonzero.  The copy remains primitive/NBT-based and never aliases the
                // authoritative object.
                TileEntity persistent = clientWorld.func_147438_o(source.field_145851_c,
                        source.field_145848_d, source.field_145849_e);
                if (persistent != null) {
                    syncPersistentState(source, persistent);
                }
                TileEntity view = copy(source, clientWorld);
                view.func_145845_h();
                ticked++;
            } catch (Throwable t) {
                failed++;
                serverWorld.host().log("UMB-FX client tile presentation failed class="
                        + source.getClass().getName() + " cause=" + t.getClass().getName()
                        + " message=" + String.valueOf(t.getMessage()));
            }
        }
        if (considered > 0) {
            serverWorld.host().log("UMB-FX clientTiles considered=" + considered + " ticked=" + ticked
                    + " failed=" + failed + " max=" + MAX_TILES_PER_TICK);
        }
    }

    private static TileEntity copy(TileEntity source, WorldClient clientWorld) throws Exception {
        TileEntity view = construct(source);
        UmbUnsafe.setField(view, UmbUnsafe.field(TileEntity.class, "field_145850_b"), clientWorld);
        view.field_145851_c = source.field_145851_c;
        view.field_145848_d = source.field_145848_d;
        view.field_145849_e = source.field_145849_e;
        NBTTagCompound tag = new NBTTagCompound();
        source.func_145841_b(tag);
        view.func_145839_a(tag);
        copyPrimitiveState(source, view);
        return view;
    }

    /**
     * Legacy machines and other tiles use constructor-created helpers that are not persisted in NBT
     * (slot arrays, upgrade managers, matcher state, and similar).  An Unsafe-only copy leaves
     * those helpers null and makes an otherwise harmless client presentation tick throw before
     * it can emit smoke or sound.  Prefer the tile's normal no-argument constructor so its own
     * invariants are established; retain the allocation fallback for unusual legacy tiles whose
     * constructor cannot run in the isolated facade.
     */
    private static TileEntity construct(TileEntity source) throws Exception {
        Class<?> tileClass = source.getClass();
        Constructor<?> ctor = CONSTRUCTORS.get(tileClass);
        if (ctor == null && !NO_CONSTRUCTOR.contains(tileClass)) {
            try {
                ctor = tileClass.getDeclaredConstructor();
                ctor.setAccessible(true);
                Constructor<?> existing = CONSTRUCTORS.putIfAbsent(tileClass, ctor);
                if (existing != null) ctor = existing;
            } catch (Throwable ignored) {
                NO_CONSTRUCTOR.add(tileClass);
            }
        }
        if (ctor != null) return (TileEntity) ctor.newInstance();
        return (TileEntity) UmbUnsafe.allocate(tileClass);
    }

    /**
     * NBT is the portable state boundary, but legacy presentation often gates on live counters
     * that deliberately are not saved.  Copy only primitive, non-static, non-final fields below
     * TileEntity; this transfers counters such as onTicks without sharing mutable containers or
     * leaking the authoritative world reference into the client copy.  The rule is type-based and
     * therefore remains universal across mods and tile classes.
     */
    /** Copies server-authoritative primitive TE state into the persistent client twin. */
    static void syncPersistentState(TileEntity source, TileEntity target) {
        if (source == null || target == null || source == target) return;
        try {
            NBTTagCompound tag = new NBTTagCompound();
            source.func_145841_b(tag);
            target.func_145839_a(tag);
        } catch (Throwable ignored) {
            // Some legacy NBT readers require client-only registry state; primitive fields below
            // still carry transient values such as machine power/progress when NBT omits them.
        }
        copyPrimitiveState(source, target);
    }

    private static void copyPrimitiveState(TileEntity source, TileEntity target) {
        for (Field field : primitiveFields(source.getClass())) {
            try {
                field.set(target, field.get(source));
            } catch (Throwable ignored) {
                // One inaccessible helper must not suppress presentation for the whole tile.
            }
        }
    }

    private static Field[] primitiveFields(Class<?> tileClass) {
        Field[] cached = PRIMITIVE_FIELDS.get(tileClass);
        if (cached != null) return cached;
        List<Field> fields = new java.util.ArrayList<>();
        for (Class<?> type = tileClass; type != null && type != TileEntity.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                if (Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)
                        || !field.getType().isPrimitive() || field.isSynthetic()) continue;
                try {
                    field.setAccessible(true);
                    fields.add(field);
                } catch (Throwable ignored) {
                    // Keep the same fail-open rule as the old per-call scan.
                }
            }
        }
        Field[] built = fields.toArray(new Field[0]);
        Field[] existing = PRIMITIVE_FIELDS.putIfAbsent(tileClass, built);
        return existing == null ? built : existing;
    }
}
