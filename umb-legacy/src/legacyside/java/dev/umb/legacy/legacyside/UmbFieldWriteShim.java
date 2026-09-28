package dev.umb.legacy.legacyside;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Makes FML's {@code @ObjectHolder} / {@code @ItemStackHolder} injection work on JDK 25.
 *
 * <p>{@code cpw.mods.fml.common.registry.ObjectHolderRef} and {@code ItemStackHolderRef} both hold
 * three private statics that they populate in {@code makeWritable(Field)}:</p>
 * <pre>
 *   reflectionFactory = sun.reflect.ReflectionFactory.getReflectionFactory()
 *   newFieldAccessor  = sun.reflect.ReflectionFactory.newFieldAccessor(Field, boolean)
 *   fieldAccessorSet  = sun.reflect.FieldAccessor.set(Object, Object)
 *   modifiersField    = Field.class.getDeclaredField("modifiers")
 * </pre>
 * <p>On JDK 25 the jdk.unsupported {@code sun.reflect.ReflectionFactory} has no
 * {@code newFieldAccessor}, {@code sun.reflect.FieldAccessor} does not exist, and
 * {@code Field.modifiers} was removed in JDK 12 - so {@code makeWritable} throws and PREINIT dies
 * at {@code ObjectHolderRegistry.findObjectHolders}:</p>
 * <pre>
 *   RuntimeException: NoSuchMethodException:
 *       sun.reflect.ReflectionFactory.newFieldAccessor(java.lang.reflect.Field,boolean)
 *     at cpw.mods.fml.common.registry.ObjectHolderRef.makeWritable(ObjectHolderRef.java:89)
 *     at cpw.mods.fml.common.Loader.preinitializeMods(Loader.java:554)
 * </pre>
 *
 * <p>The trick that keeps Forge's own logic intact: {@code apply()} in both classes only ever uses
 * those statics as</p>
 * <pre>
 *   Object fieldAccessor = newFieldAccessor.invoke(reflectionFactory, field, false);
 *   fieldAccessorSet.invoke(fieldAccessor, null, thing);
 * </pre>
 * <p>so if {@code makeWritable} installs OUR {@link #newFieldAccessor} (a static method, whose
 * receiver reflection ignores) and OUR {@link Accessor#set} (an instance method of the object that
 * factory returns), {@code apply()} needs no patching at all. Only the tiny
 * {@code makeWritable(Field)} body is rewritten, by {@link UmbShimTransformer}.</p>
 */
public final class UmbFieldWriteShim {

    private UmbFieldWriteShim() {
    }

    /** Stands in for {@code sun.reflect.ReflectionFactory}; never dereferenced. */
    private static final Object SENTINEL = new Object();

    /** Stands in for {@code sun.reflect.FieldAccessor}. */
    public static final class Accessor {

        private final Field field;

        Accessor(Field field) {
            this.field = field;
        }

        /** Same shape as {@code sun.reflect.FieldAccessor.set(Object, Object)}. */
        public void set(Object target, Object value) throws Exception {
            EnumHelperShim.setFailsafeFieldValue(field, target, value);
        }
    }

    /** Same shape as {@code sun.reflect.ReflectionFactory.newFieldAccessor(Field, boolean)}. */
    public static Accessor newFieldAccessor(Field field, boolean override) {
        field.setAccessible(true);
        return new Accessor(field);
    }

    /**
     * Replacement body for {@code ObjectHolderRef.makeWritable(Field)} and
     * {@code ItemStackHolderRef.makeWritable(Field)}. {@code owner} is the class whose private
     * statics get wired up; the transformer passes it as an LDC class constant.
     */
    public static void makeWritable(Field f, Class<?> owner) {
        try {
            f.setAccessible(true);
            synchronized (UmbFieldWriteShim.class) {
                Field rf = owner.getDeclaredField("reflectionFactory");
                rf.setAccessible(true);
                if (rf.get(null) != null) {
                    return;
                }
                rf.set(null, SENTINEL);

                Method nfa = UmbFieldWriteShim.class.getMethod("newFieldAccessor",
                        Field.class, boolean.class);
                Field nfaField = owner.getDeclaredField("newFieldAccessor");
                nfaField.setAccessible(true);
                nfaField.set(null, nfa);

                Method set = Accessor.class.getMethod("set", Object.class, Object.class);
                Field setField = owner.getDeclaredField("fieldAccessorSet");
                setField.setAccessible(true);
                setField.set(null, set);
            }
        } catch (Throwable t) {
            throw new RuntimeException("umb field-write shim failed for " + owner.getName(), t);
        }
    }
}
