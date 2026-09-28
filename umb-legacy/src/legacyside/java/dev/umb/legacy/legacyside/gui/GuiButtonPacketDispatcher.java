package dev.umb.legacy.legacyside.gui;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import dev.umb.legacy.legacyside.UmbPlayer;
import dev.umb.legacy.legacyside.network.LegacyNetworkLoopback;

/** Builds only bytecode-proven GUI messages and sends them through the generic server loopback. */
public final class GuiButtonPacketDispatcher {
    private GuiButtonPacketDispatcher() { }

    public static boolean dispatch(String guiClass, int buttonId, int x, int y, int z,
                                   String[] recipe, UmbPlayer player) {
        if (recipe == null || player == null) return false;
        String messageClass = null;
        List<String> values = new ArrayList<String>();
        for (String value : recipe) {
            if (value != null && value.startsWith("__messageClass=")) messageClass = value.substring(15);
            else values.add(value);
        }
        if (messageClass == null || messageClass.length() == 0) return false;
        try {
            Class<?> type = Class.forName(messageClass, true, GuiButtonPacketDispatcher.class.getClassLoader());
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                Class<?>[] params = ctor.getParameterTypes();
                if (params.length != values.size()) continue;
                Object[] args = new Object[params.length];
                boolean ok = true;
                for (int i = 0; i < params.length; i++) {
                    args[i] = value(values.get(i), params[i], buttonId, x, y, z);
                    if (args[i] == null && params[i].isPrimitive()) { ok = false; break; }
                }
                if (!ok) continue;
                ctor.setAccessible(true);
                Object message = ctor.newInstance(args);
                if (!(message instanceof IMessage)) return false;
                LegacyNetworkLoopback.deliverToServer((IMessage) message, player);
                return true;
            }
        } catch (Throwable ignored) {
            // A recipe that cannot be instantiated is an unresolved extraction, never a guessed click.
        }
        return false;
    }

    private static Object value(String token, Class<?> type, int buttonId, int x, int y, int z) {
        if (token == null) return null;
        String value = token;
        if ("buttonId".equals(token)) value = Integer.toString(buttonId);
        else if ("x".equals(token)) value = Integer.toString(x);
        else if ("y".equals(token)) value = Integer.toString(y);
        else if ("z".equals(token)) value = Integer.toString(z);
        else if (token.startsWith("const:")) value = token.substring(6);
        if (type == String.class) return value;
        try {
            if (type == int.class || type == Integer.class) return Integer.valueOf(value);
            if (type == long.class || type == Long.class) return Long.valueOf(value);
            if (type == short.class || type == Short.class) return Short.valueOf(value);
            if (type == byte.class || type == Byte.class) return Byte.valueOf(value);
            if (type == boolean.class || type == Boolean.class) return Boolean.valueOf(value);
            if (type == float.class || type == Float.class) return Float.valueOf(value);
            if (type == double.class || type == Double.class) return Double.valueOf(value);
        } catch (NumberFormatException ignored) { }
        return null;
    }
}
