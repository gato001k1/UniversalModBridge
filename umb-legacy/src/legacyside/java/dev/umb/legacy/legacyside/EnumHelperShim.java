package dev.umb.legacy.legacyside;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The Java-25 replacements for the two things {@code net.minecraftforge.common.util.EnumHelper}
 * can no longer do. {@link UmbShimTransformer} rewrites EnumHelper's method bodies to call these.
 *
 * <p>This is THE hard blocker for the legacy universe: HBM's {@code MainRegistry.<clinit>} calls
 * {@code EnumHelper.addToolMaterial(...)}, and Forge's EnumHelper fails at CONSTRUCTING with</p>
 * <pre>
 *   RuntimeException: Cannot invoke "java.lang.reflect.Method.invoke(Object, Object[])" because
 *       "net.minecraftforge.common.util.EnumHelper.newConstructorAccessor" is null
 *     at net.minecraftforge.common.util.EnumHelper.addEnum(EnumHelper.java:279)
 *     at net.minecraftforge.common.util.EnumHelper.addToolMaterial(EnumHelper.java:105)
 *     at com.hbm.main.MainRegistry.&lt;clinit&gt;(MainRegistry.java:110)
 * </pre>
 *
 * <p>Four things Forge's version needs are gone from JDK 25:</p>
 * <ol>
 *   <li>{@code sun.reflect.ReflectionFactory.newConstructorAccessor(Constructor)} - the
 *       jdk.unsupported {@code sun.reflect.ReflectionFactory} that survives only exposes the
 *       serialization helpers; the accessor factory moved to {@code jdk.internal.reflect} (verified
 *       still present on 25.0.4.1, but behind {@code --add-exports}).</li>
 *   <li>{@code sun.reflect.ConstructorAccessor} and {@code sun.reflect.FieldAccessor} - gone.</li>
 *   <li>{@code Field.class.getDeclaredField("modifiers")} - removed in JDK 12, so the classic
 *       "clear the FINAL bit" trick throws NoSuchFieldException before any accessor is even used.
 *       This is why setting the statics alone cannot fix EnumHelper.</li>
 *   <li>plain {@code Constructor.newInstance} on an enum, which the JDK refuses outright
 *       ("Cannot reflectively create enum objects") - the reason Forge went to accessors at all.</li>
 * </ol>
 *
 * <p>The replacements need NO {@code --add-exports} into JDK internals:</p>
 * <ul>
 *   <li>enum instantiation: {@code MethodHandles.privateLookupIn(enumClass, lookup)} +
 *       {@code findConstructor}. The enum guard lives in {@code java.lang.reflect.Constructor}, not
 *       in {@code MethodHandles.Lookup}, and the JVM itself happily runs an enum constructor. The
 *       enum classes are in the legacy loader's unnamed module, which is open, so no flag needed.
 *       Verified on 25.0.4.1.</li>
 *   <li>writing a {@code static final} field ($VALUES): {@code sun.misc.Unsafe}
 *       staticFieldBase/staticFieldOffset/putObject, reached reflectively. This is the ONLY
 *       remaining route - {@code VarHandle.set} and {@code Lookup.findStaticSetter} both refuse
 *       final fields. Needs {@code --sun-misc-unsafe-memory-access=allow} on JDK 24+ and will need
 *       a different answer when those methods are finally removed.</li>
 *   <li>{@code Class.enumConstants} / {@code Class.enumConstantDirectory} still exist in JDK 25 and
 *       are non-final, so Forge's own {@code blankField} works once it routes through here - it
 *       just needs {@code --add-opens java.base/java.lang=ALL-UNNAMED}.</li>
 * </ul>
 *
 * <p>Compiled with {@code --release 8} like everything else inside the universe (ASM 5.0.3 in
 * {@code ASMModParser} walks every classpath jar and rejects major &gt; 52), which is why
 * {@code privateLookupIn} - @since 9 - is invoked reflectively.</p>
 */
public final class EnumHelperShim {

    private EnumHelperShim() {
    }

    private static Object unsafe;
    private static Method staticFieldBase;
    private static Method staticFieldOffset;
    private static Method objectFieldOffset;
    private static Method putObject;
    private static Method privateLookupIn;

    static {
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = uc.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            staticFieldBase = uc.getMethod("staticFieldBase", Field.class);
            staticFieldOffset = uc.getMethod("staticFieldOffset", Field.class);
            objectFieldOffset = uc.getMethod("objectFieldOffset", Field.class);
            putObject = uc.getMethod("putObject", Object.class, long.class, Object.class);
        } catch (Throwable t) {
            unsafe = null;
        }
        try {
            privateLookupIn = MethodHandles.class.getMethod("privateLookupIn",
                    Class.class, MethodHandles.Lookup.class);
        } catch (Throwable t) {
            privateLookupIn = null;
        }
    }

    /** Replacement body for {@code EnumHelper.setup()} - nothing to look up any more. */
    public static void setup() {
        // intentionally empty
    }

    /**
     * Replacement body for
     * {@code EnumHelper.makeEnum(Class, String, int, Class[], Object[])}.
     * Signature and erasure must match exactly: {@code (Ljava/lang/Class;Ljava/lang/String;I
     * [Ljava/lang/Class;[Ljava/lang/Object;)Ljava/lang/Enum;}
     */
    public static Enum<?> makeEnum(Class<?> enumClass, String value, int ordinal,
                                   Class<?>[] additionalTypes, Object[] additionalValues)
            throws Exception {
        if (privateLookupIn == null) {
            throw new IllegalStateException("MethodHandles.privateLookupIn is unavailable;"
                    + " a JDK 9+ runtime is required for the umb EnumHelper shim");
        }
        Class<?>[] parameterTypes = new Class<?>[additionalTypes.length + 2];
        parameterTypes[0] = String.class;
        parameterTypes[1] = int.class;
        System.arraycopy(additionalTypes, 0, parameterTypes, 2, additionalTypes.length);

        Object[] parms = new Object[additionalValues.length + 2];
        parms[0] = value;
        parms[1] = Integer.valueOf(ordinal);
        System.arraycopy(additionalValues, 0, parms, 2, additionalValues.length);

        try {
            MethodHandles.Lookup lookup = (MethodHandles.Lookup) privateLookupIn.invoke(null,
                    enumClass, MethodHandles.lookup());
            MethodHandle ctor = lookup.findConstructor(enumClass,
                    MethodType.methodType(void.class, parameterTypes));
            return (Enum<?>) ctor.invokeWithArguments(parms);
        } catch (Exception e) {
            throw e;
        } catch (Throwable t) {
            throw new Exception("cannot construct enum " + enumClass.getName() + "." + value, t);
        }
    }

    /**
     * Replacement body for {@code EnumHelper.setFailsafeFieldValue(Field, Object, Object)}.
     * Public in Forge, so mods call it directly - the descriptor must not change.
     */
    public static void setFailsafeFieldValue(Field field, Object target, Object value)
            throws Exception {
        field.setAccessible(true);
        if (!Modifier.isFinal(field.getModifiers())) {
            field.set(target, value);
            return;
        }
        if (unsafe == null) {
            throw new IllegalStateException("cannot write final field " + field
                    + ": sun.misc.Unsafe is unavailable"
                    + " (needs --sun-misc-unsafe-memory-access=allow on JDK 24+)");
        }
        if (Modifier.isStatic(field.getModifiers())) {
            Object base = staticFieldBase.invoke(unsafe, field);
            Long off = (Long) staticFieldOffset.invoke(unsafe, field);
            putObject.invoke(unsafe, base, off, value);
        } else {
            Long off = (Long) objectFieldOffset.invoke(unsafe, field);
            putObject.invoke(unsafe, target, off, value);
        }
    }
}
