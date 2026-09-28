package dev.umb.legacy.legacyside;

import java.lang.reflect.Method;

/** Generic runner for a selected client tick handler; it never names a mod or event class. */
public final class LegacyClientTickRunner {
    private LegacyClientTickRunner() {
    }

    /** Runs the handler's public pre-tick entry point, as W_TickHandler exposes it in 1.7.10. */
    public static void runOne(Object handler) {
        if (handler == null) {
            throw new IllegalArgumentException("handler");
        }
        try {
            try {
                Method m = handler.getClass().getMethod("onTickPre");
                m.setAccessible(true);
                m.invoke(handler);
            } catch (NoSuchMethodException notAnEventHandler) {
                // Individual client tick handlers extend a protected onTick(boolean) base;
                // their common W_TickHandler wrapper calls this same method.
                Method m = handler.getClass().getDeclaredMethod("onTick", boolean.class);
                m.setAccessible(true);
                m.invoke(handler, Boolean.FALSE);
            }
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("legacy client tick failed for "
                    + handler.getClass().getName(), cause);
        }
    }

    /** Invokes one selected handler body for a headless proof when its wrapper has render-only work. */
    public static void invoke(Object handler, String methodName, Class<?>[] parameterTypes,
                              Object... arguments) {
        try {
            Method m = handler.getClass().getDeclaredMethod(methodName, parameterTypes);
            m.setAccessible(true);
            m.invoke(handler, arguments);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("legacy client handler body failed for "
                    + handler.getClass().getName() + "." + methodName, cause);
        }
    }
}
