package dev.umb.hostagent.content;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import dev.umb.bridge.api.LegacyBridge;
import org.junit.jupiter.api.Test;

/**
 * Every host-side bridge DELEGATE must forward every LegacyBridge method explicitly.
 *
 * <p>2026-09-24: LegacyBridge keeps no-op defaults (acceptInput -> false, drainClientEffects ->
 * empty) for fakes. BridgeRouter and then UmbUniverse each silently inherited them, so every
 * input call and every client effect (sounds/particles) died on the default with no error -
 * four live debugging rounds of "the gun does nothing". A delegate that inherits a default is
 * always a bug: this test names the missing method instead.</p>
 */
class BridgeDelegatesOverrideAllTest {
    @Test
    void delegatesOverrideEveryBridgeMethod() {
        for (Class<?> delegate : new Class<?>[] {BridgeRouter.class, UmbUniverse.class, Legacy1122Universe.class, Legacy1165Universe.class}) {
            List<String> missing = new ArrayList<>();
            for (Method m : LegacyBridge.class.getMethods()) {
                if (Modifier.isStatic(m.getModifiers())) continue;
                try {
                    Method impl = delegate.getMethod(m.getName(), m.getParameterTypes());
                    if (impl.getDeclaringClass() == LegacyBridge.class) missing.add(m.getName());
                } catch (NoSuchMethodException e) {
                    missing.add(m.getName());
                }
            }
            assertTrue(missing.isEmpty(), delegate.getSimpleName()
                    + " inherits LegacyBridge default(s) instead of forwarding: " + missing);
        }
    }
}
