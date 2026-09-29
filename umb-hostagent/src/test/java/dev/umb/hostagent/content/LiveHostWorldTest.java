package dev.umb.hostagent.content;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The universe keeps a LiveHostWorld from boot instead of the HostWorldImpl itself. Legacy code
 * discovers host capabilities with instanceof (entity spawning is HostLevel), so the wrapper must
 * implement every interface the real world does and override every method, defaults included -
 * a default body would silently do nothing instead of reaching the live level.
 */
class LiveHostWorldTest {

    @Test
    void implementsEveryInterfaceOfHostWorldImpl() {
        Set<Class<?>> real = new HashSet<>(Arrays.asList(HostWorldImpl.class.getInterfaces()));
        Set<Class<?>> live = new HashSet<>(Arrays.asList(LiveHostWorld.class.getInterfaces()));
        assertEquals(real, live);
    }

    @Test
    void overridesEveryInterfaceMethod() {
        for (Class<?> api : LiveHostWorld.class.getInterfaces()) {
            for (Method m : api.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                try {
                    Method own = LiveHostWorld.class.getDeclaredMethod(m.getName(), m.getParameterTypes());
                    assertTrue(own.getDeclaringClass() == LiveHostWorld.class, m.toString());
                } catch (NoSuchMethodException e) {
                    throw new AssertionError("LiveHostWorld does not forward " + m, e);
                }
            }
        }
    }
}
