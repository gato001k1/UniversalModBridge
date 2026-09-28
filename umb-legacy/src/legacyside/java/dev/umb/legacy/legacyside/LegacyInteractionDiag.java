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
