package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import dev.umb.legacy.legacyside.input.LegacyInputDiag;

/**
 * Generic diagnostics for the SRG Entity interaction contract. The transformer calls this at
 * every boolean return of func_130002_c(EntityPlayer), preserving the returned value and the
 * mod's control flow. Reflection keeps the diagnostic independent of any vehicle implementation.
 */
public final class LegacyInteractionDiag {
    private static final long LOG_INTERVAL_NANOS = 5_000_000_000L;

    private LegacyInteractionDiag() {
    }

    public static void before(Object vehicle, Object player) {
        String key = "interaction-before:" + (vehicle == null ? "null" : vehicle.getClass().getName());
        if (!LegacyInputDiag.oncePer(key, LOG_INTERVAL_NANOS)) {
            return;
        }
        StringBuilder line = new StringBuilder();
        line.append("interaction before vehicle=").append(identity(vehicle))
                .append(" player=").append(identity(player))
                .append(" directPassenger=").append(identity(field(vehicle, "field_70153_n")))
                .append(" playerVehicle=").append(identity(field(player, "field_70154_o")));
        appendWorldState(line, vehicle);
        appendCanopyState(line, vehicle);
        LegacyInputDiag.log(line.toString());
    }

    public static void record(Object vehicle, Object player, String owner, String method,
            int path, boolean result) {
        String key = "interaction-return:" + owner + ":" + path + ":" + result;
        if (!LegacyInputDiag.oncePer(key, LOG_INTERVAL_NANOS)) {
            return;
        }
        StringBuilder line = new StringBuilder();
        line.append("interaction return owner=").append(owner)
                .append(" method=").append(method)
                .append(" path=").append(path)
                .append(" result=").append(result)
                .append(" vehicle=").append(identity(vehicle))
                .append(" player=").append(identity(player))
                .append(" directPassenger=").append(identity(field(vehicle, "field_70153_n")))
                .append(" playerVehicle=").append(identity(field(player, "field_70154_o")));
        appendWorldState(line, vehicle);
        Object acInfo = invokeValue(vehicle, "getAcInfo");
        line.append(" acInfo=").append(identity(acInfo))
                .append(" acInfo.canRide=").append(fieldValue(acInfo, "canRide"));
        appendSeatsInfoState(line, vehicle);
        appendCall(line, acInfo, "haveCanopy", new Class<?>[0], new Object[0]);
        appendCanopyState(line, vehicle);
        appendCall(line, vehicle, "isDestroyed", new Class<?>[0], new Object[0]);
        appendCall(line, vehicle, "checkTeam", new Class<?>[] { player == null ? Object.class : player.getClass() },
                new Object[] { player });
        appendCall(line, player, "func_70093_af", new Class<?>[0], new Object[0]);
        appendCall(line, vehicle, "isUAV", new Class<?>[0], new Object[0]);
        appendCall(line, vehicle, "canRideSeatOrRack", new Class<?>[] { int.class,
                player == null ? Object.class : player.getClass() }, new Object[] { Integer.valueOf(0), player });
        appendCall(line, vehicle, "haveCanopy", new Class<?>[0], new Object[0]);
        appendCall(line, vehicle, "isCanopyClose", new Class<?>[0], new Object[0]);
        appendCall(line, vehicle, "getModeSwitchCooldown", new Class<?>[0], new Object[0]);
        LegacyInputDiag.log(line.toString());
    }

    /** Records the final interaction state after the mod's boolean method has returned. */
    public static void completed(Object vehicle, Object player, boolean accepted) {
        if (!accepted) {
            return;
        }
        String key = "interaction-completed:" + (vehicle == null ? "null" : vehicle.getClass().getName());
        if (!LegacyInputDiag.oncePer(key, LOG_INTERVAL_NANOS)) {
            return;
        }
        StringBuilder line = new StringBuilder("interaction completed accepted=true vehicle=")
                .append(identity(vehicle))
                .append(" player=").append(identity(player))
                .append(" directPassenger=").append(identity(field(vehicle, "field_70153_n")))
                .append(" playerVehicle=").append(identity(field(player, "field_70154_o")));
        LegacyInputDiag.log(line.toString());
    }

    /**
     * the config-loaded {@code acInfo.seatList} is correctly 2-long for a fresh AH-1Z, and the
     * bytecode-confirmed array math in {@code updateSeatsPosition} is correct for a properly-sized
     * cache - so the one remaining, unconfirmed suspect is {@code getSeatsInfo()}'s own one-time
     * lazy cache (built once from {@code getAcInfo().seatList} and never rebuilt) catching a SHORT
     * array. The live check of this (deploy after SS E) hit a DIFFERENT gap first: the interact
     * RECEIVER for a guest/gunner seat is the seat entity itself (e.g. MCH_EntitySeat), which has
     * no getSeatsInfo()/getAcInfo() of its own at all - the dump correctly showed
     * "acInfo=null seatsInfo=absent-or-null", just on the wrong object. Seat entities of every
     * vehicle-mod shape this project has seen delegate to a parent vehicle via a getParent()
     * now resolves that parent STRUCTURALLY (does the candidate itself expose getSeatsInfo()? if
     * not, does its getParent() result expose getSeatsInfo()?) rather than assuming any mod class
     * name, and reports the seat's own seatID/index alongside the parent's real seatsInfo so the
     * two can be cross-checked (a wrong index reads a real entry at the wrong offset; a wrong
     * seatsInfo length hits the fallback branch regardless of index).
     *
     * <p>Reflection only, no mod class/method name is assumed to exist (every call degrades to
     * "absent"/"error" instead of throwing) - this generalizes to any future vehicle mod with the
     * same "getParent()/getSeatsInfo()/pos/gunner/seatID" shape, not just MCHeli.</p>
     */
    private static void appendSeatsInfoState(StringBuilder line, Object candidate) {
        Object owner = candidate;
        Object seatsInfo = invokeValue(owner, "getSeatsInfo");
        if (seatsInfo == null) {
            // Not itself a seats-info owner (e.g. it's a guest/gunner seat, not the vehicle) -
            // resolve its parent BY STRUCTURE: only trust getParent() if the result actually
            // exposes getSeatsInfo() too, never by assuming a hardcoded mod class name.
            Object parent = invokeValue(candidate, "getParent");
            if (parent != null && invokeValue(parent, "getSeatsInfo") != null) {
                owner = parent;
                seatsInfo = invokeValue(parent, "getSeatsInfo");
                line.append(" seatsOwner=parent(").append(identity(parent)).append(')')
                        .append(" seatID=").append(fieldValue(candidate, "seatID"))
                        .append(" parentSeatNum=").append(invokeValue(parent, "getSeatNum"));
            } else {
                line.append(" seatsOwner=none seatsInfo=absent-or-null");
                return;
            }
        }
        Object ownerAcInfo = invokeValue(owner, "getAcInfo");
        if (!seatsInfo.getClass().isArray()) {
            line.append(" seatsInfo=non-array:").append(identity(seatsInfo));
            return;
        }
        int length = java.lang.reflect.Array.getLength(seatsInfo);
        Object seatList = field(ownerAcInfo, "seatList");
        Object seatListSize = seatList instanceof java.util.Collection
                ? Integer.valueOf(((java.util.Collection<?>) seatList).size()) : "n/a";
        line.append(" seatsInfoLength=").append(length).append(" acInfoSeatListSize=").append(seatListSize);
        for (int i = 0; i < length; i++) {
            Object entry = java.lang.reflect.Array.get(seatsInfo, i);
            Object pos = field(entry, "pos");
            line.append(" seatsInfo[").append(i).append("]=");
            if (entry == null) {
                line.append("null");
            } else {
                line.append("pos(").append(fieldValue(pos, "field_72450_a"))
                        .append(',').append(fieldValue(pos, "field_72448_b"))
                        .append(',').append(fieldValue(pos, "field_72449_c"))
                        .append(")gunner=").append(fieldValue(entry, "gunner"));
            }
        }
    }

    /**
     * Periodic (oncePer-bounded, keyed by class so this stays cheap across many entities) sample
     * of the SAME seats-info state as {@link #appendSeatsInfoState}, called generically from every
     * legacy entity's tick ({@code UmbWorld.tickEntities}) - never gated on a mod class name; it
     * is a structural no-op ("absent-or-none") for any entity that isn't itself a seats-info owner
     * TICK-time sample (running right after the vehicle's OWN real onUpdate, which is exactly
     * where updateSeatsPosition/newSeatsPos run) ever shows a short/wrong seatsInfo for a vehicle,
     * or shows the SEAT's own position field drifting to match its rider's rather than the
     * per-tick MCHeli-computed offset, that is caught here across many ticks - not just the one
     * moment an interact happens to fire.
     */
    public static void tickSnapshot(Object entity) {
        if (entity == null) {
            return;
        }
        Object seatsInfo = invokeValue(entity, "getSeatsInfo");
        Object parent = seatsInfo == null ? invokeValue(entity, "getParent") : null;
        boolean isSeatsOwner = seatsInfo != null;
        boolean isSeatDelegate = !isSeatsOwner && parent != null && invokeValue(parent, "getSeatsInfo") != null;
        if (!isSeatsOwner && !isSeatDelegate) {
            return;
        }
        String key = "tick-seatsinfo:" + entity.getClass().getName();
        if (!LegacyInputDiag.oncePer(key, LOG_INTERVAL_NANOS)) {
            return;
        }
        StringBuilder line = new StringBuilder("tick seatsinfo entity=").append(identity(entity));
        line.append(" pos=(").append(fieldValue(entity, "field_70165_t")).append(',')
                .append(fieldValue(entity, "field_70163_u")).append(',')
                .append(fieldValue(entity, "field_70161_v")).append(')')
                .append(" directRider=").append(identity(field(entity, "field_70153_n")));
        appendSeatsInfoState(line, entity);
        LegacyInputDiag.log(line.toString());
    }

    private static void appendCanopyState(StringBuilder line, Object vehicle) {
        Object partCanopy = field(vehicle, "partCanopy");
        Object partParent = field(partCanopy, "parent");
        line.append(" partCanopy=").append(identity(partCanopy))
                .append(" partParent=").append(identity(partParent))
                .append(" partParentWorldRemote=").append(fieldValue(partParent, "field_72995_K"))
                .append(" partDataWatcher=").append(identity(field(partCanopy, "dataWatcher")))
                .append(" vehicleDataWatcher=").append(identity(field(vehicle, "field_70180_af")));
        appendCall(line, vehicle, "getCanopyStat", new Class<?>[0], new Object[0]);
        appendCall(line, vehicle, "isCanopyClose", new Class<?>[0], new Object[0]);
        appendCall(line, vehicle, "getCanopyRotation", new Class<?>[0], new Object[0]);
    }

    private static void appendWorldState(StringBuilder line, Object vehicle) {
        Object world = field(vehicle, "field_70170_p");
        line.append(" vehicleWorld=").append(identity(world));
        line.append(" vehicleWorldRemote=").append(fieldValue(world, "field_72995_K"));
    }

    private static Object invokeValue(Object receiver, String name) {
        if (receiver == null) return null;
        try {
            Method m = findMethod(receiver.getClass(), name, new Class<?>[0]);
            if (m == null) return null;
            m.setAccessible(true);
            return m.invoke(receiver);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void appendCall(StringBuilder line, Object receiver, String name,
            Class<?>[] parameterTypes, Object[] args) {
        line.append(' ').append(name).append('=');
        if (receiver == null) {
            line.append("receiver-null");
            return;
        }
        try {
            Method m = findMethod(receiver.getClass(), name, parameterTypes);
            if (m == null) {
                line.append("absent");
                return;
            }
            m.setAccessible(true);
            Object value = m.invoke(receiver, args);
            if (value == null) {
                line.append("null");
            } else if (value instanceof Boolean || value instanceof Number
                    || value instanceof String) {
                line.append(value);
            } else {
                line.append(identity(value));
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            line.append("error:").append(cause.getClass().getSimpleName());
        }
    }

    private static Method findMethod(Class<?> type, String name, Class<?>[] parameterTypes) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            Method[] methods = c.getDeclaredMethods();
            for (Method m : methods) {
                if (!name.equals(m.getName()) || m.getParameterTypes().length != parameterTypes.length) {
                    continue;
                }
                boolean compatible = true;
                Class<?>[] actual = m.getParameterTypes();
                for (int i = 0; i < actual.length; i++) {
                    if (parameterTypes[i] == Object.class) {
                        continue;
                    }
                    if (parameterTypes[i] == int.class) {
                        if (actual[i] != int.class) {
                            compatible = false;
                        }
                    } else if (!actual[i].isAssignableFrom(parameterTypes[i])
                            && !parameterTypes[i].isAssignableFrom(actual[i])) {
                        compatible = false;
                    }
                }
                if (compatible) {
                    return m;
                }
            }
        }
        return null;
    }

    private static Object field(Object receiver, String name) {
        if (receiver == null) {
            return null;
        }
        try {
            for (Class<?> c = receiver.getClass(); c != null; c = c.getSuperclass()) {
                try {
                    Field f = c.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(receiver);
                } catch (NoSuchFieldException ignored) {
                    // Continue through the legacy inheritance chain.
                }
            }
        } catch (Throwable ignored) {
            // Diagnostics are strictly non-invasive.
        }
        return null;
    }

    private static String fieldValue(Object receiver, String name) {
        Object value = field(receiver, name);
        return value == null ? "null" : String.valueOf(value);
    }

    private static String identity(Object value) {
        if (value == null) {
            return "null";
        }
        return value.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }
}
