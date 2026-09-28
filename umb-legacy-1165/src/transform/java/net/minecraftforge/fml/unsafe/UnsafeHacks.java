package net.minecraftforge.fml.unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Minimal Forge FML unsafe bridge required by Forge's own RuntimeEnumExtender.
 *
 * <p>The 36.2.34 launch jar contains the verified enum transformer, while this FML helper is
 * supplied by the full Forge runtime in a normal ModLauncher launch.  The direct 1.16.5
 * universe intentionally loads the launch/universal pair without the full launcher process, so
 * provide the one helper method emitted by RuntimeEnumExtender.  It uses the same JDK Unsafe
 * field-base/offset operation as the lifecycle's already-proven static-field injector and does
 * not make the transform depend on inaccessible {@code java.lang.reflect} modifiers.</p>
 */
public final class UnsafeHacks {
    private UnsafeHacks() {
    }

    /** Clears Class's lazy enum caches after Forge appends an IExtensibleEnum constant. */
    public static void cleanEnumCache(Class<?> enumClass) {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            java.lang.reflect.Method staticFieldBase =
                    unsafeClass.getMethod("staticFieldBase", Field.class);
            java.lang.reflect.Method staticFieldOffset =
                    unsafeClass.getMethod("staticFieldOffset", Field.class);
            java.lang.reflect.Method objectFieldOffset =
                    unsafeClass.getMethod("objectFieldOffset", Field.class);
            java.lang.reflect.Method putObject = unsafeClass.getMethod("putObject",
                    Object.class, long.class, Object.class);
            for (Field field : Class.class.getDeclaredFields()) {
                if (field.getName().contains("enumConstantDirectory")
                        || field.getName().contains("enumConstants")) {
                    boolean isStatic = Modifier.isStatic(field.getModifiers());
                    Object base = isStatic ? staticFieldBase.invoke(unsafe, field) : enumClass;
                    long offset = ((Long) (isStatic
                            ? staticFieldOffset.invoke(unsafe, field)
                            : objectFieldOffset.invoke(unsafe, field))).longValue();
                    putObject.invoke(unsafe, base, Long.valueOf(offset), null);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot clear enum caches for " + enumClass, e);
        }
    }
}
