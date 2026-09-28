package dev.umb.pipeline.bridge;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Value key for method bindings and proxy dispatch: (name, parameter types). The
 * parameter list is stored as a {@link List} so equals/hashCode are value-based —
 * two keys built from the same name and the same classes compare equal regardless of
 * which {@link Method} object or varargs call produced them. This is what makes proxy
 * dispatch deterministic: the runtime {@link Method} of an invoked legacy interface
 * member maps to exactly one binding.
 */
public record MethodSignature(String name, List<Class<?>> parameterTypes) {

    public static MethodSignature of(Method m) {
        return new MethodSignature(m.getName(), List.of(m.getParameterTypes()));
    }

    public static MethodSignature of(String name, Class<?>... parameterTypes) {
        return new MethodSignature(name, List.of(parameterTypes));
    }

    /** Human-readable form for failure messages, e.g. {@code name()} or {@code name(java.lang.String)}. */
    public String display() {
        StringBuilder sb = new StringBuilder(name).append('(');
        for (int i = 0; i < parameterTypes.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(parameterTypes.get(i).getName());
        }
        return sb.append(')').toString();
    }
}